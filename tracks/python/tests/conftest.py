from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app.ai import DescriptionService, RuleParser, SearchAssistService, TemplateWriter
from app.config import Settings
from app.main import create_app
from app.repository import InMemoryStore
from app.security import sign_webhook

# ---------------------------------------------------------------------------
# PENDING tests describe behaviour you have not built yet, so they are skipped
# (reported as "s" by pytest). When you start a milestone, delete its line here,
# watch the tests fail (red), implement until they pass (green). See docs/phase-3.
# ---------------------------------------------------------------------------
PENDING: dict[str, str] = {
    "test_api_integration.py": "Phase 4 - integration tests",
}


def pytest_collection_modifyitems(config, items):
    for item in items:
        reason = PENDING.get(item.path.name)
        if reason:
            item.add_marker(pytest.mark.skip(reason=f"PENDING ({reason}) - see tests/conftest.py"))


TEST_SECRET = "test-secret-that-is-at-least-32-bytes-long"
WEBHOOK_SECRET = "test-webhook-secret-at-least-32-bytes"


class FakeClock:
    """A clock you can move. Tests should never depend on the real time."""

    def __init__(self, start: datetime | None = None):
        self.now = start or datetime(2026, 10, 2, 9, 30, tzinfo=UTC)

    def __call__(self) -> datetime:
        return self.now

    def advance(self, **kwargs):
        self.now = self.now + timedelta(**kwargs)


class PaymentIds:
    """Predictable payment ids: pay_test_1, pay_test_2, ..."""

    def __init__(self):
        self.n = 0

    def __call__(self) -> str:
        self.n += 1
        return f"pay_test_{self.n}"


def test_settings(**overrides) -> Settings:
    base = Settings.from_env()
    values = {**base.__dict__, "jwt_secret": TEST_SECRET, "bcrypt_cost": 4, "admin_email": None,
              "admin_password": None, "payment_webhook_secret": WEBHOOK_SECRET, "ai_provider": "offline",
              **overrides}  # fmt: skip
    return Settings(**values)


test_settings.__test__ = False  # not a test, despite the name


def offline_ai():
    return DescriptionService(TemplateWriter()), SearchAssistService(RuleParser())


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


@pytest.fixture
def store() -> InMemoryStore:
    return InMemoryStore()


@pytest.fixture
def client(store, clock):
    """The real FastAPI app on in-memory adapters (no database, no network)."""
    app = create_app(
        test_settings(admin_email="admin@shopflow.test", admin_password="admin-password"),
        users=store,
        products=store,
        carts=store,
        orders=store,
        clock=clock,
        ai=offline_ai(),
        payment_ids=PaymentIds(),
    )
    with TestClient(app) as c:
        yield c


class Api:
    """Small helpers so API tests read like the user stories."""

    def __init__(self, client: TestClient, store: InMemoryStore):
        self.client = client
        self.store = store
        self._n = 0
        self._admin: dict | None = None

    def customer(self, name: str | None = None) -> dict:
        """Register + log in a new customer. Returns the login JSON."""
        self._n += 1
        email = f"user{self._n}@shopflow.test"
        r = self.client.post(
            "/api/v1/auth/register",
            json={"email": email, "password": "password-123", "displayName": name or f"User {self._n}"},
        )
        assert r.status_code == 201, r.text
        return self.login(email, "password-123")

    def admin(self) -> dict:
        if self._admin is None:
            self._admin = self.login("admin@shopflow.test", "admin-password")
        return self._admin

    def login(self, email: str, password: str) -> dict:
        r = self.client.post("/api/v1/auth/login", json={"email": email, "password": password})
        assert r.status_code == 200, r.text
        return r.json()

    @staticmethod
    def h(session: dict, **extra) -> dict:
        return {"Authorization": f"Bearer {session['accessToken']}", **extra}

    def product(self, **fields) -> dict:
        self._n += 1
        body = {"sku": f"SKU-{self._n:03d}", "name": "Trail Running Shoes", "category": "shoes",
                "pricePaise": 249_900, "stock": 5, "description": "Light and grippy. Made for wet trails.",
                **fields}  # fmt: skip
        r = self.client.post("/api/v1/products", json=body, headers=self.h(self.admin()))
        assert r.status_code == 201, r.text
        return r.json()

    def add(self, session: dict, sku: str, quantity: int = 1) -> dict:
        r = self.client.put(f"/api/v1/cart/items/{sku}", json={"quantity": quantity}, headers=self.h(session))
        assert r.status_code == 200, r.text
        return r.json()

    def checkout(self, session: dict, key: str = "checkout-key-0001"):
        return self.client.post("/api/v1/checkout", headers=self.h(session, **{"Idempotency-Key": key}))

    def order(self, session: dict, key: str = "checkout-key-0001", **product_fields) -> dict:
        product = self.product(**product_fields)
        self.add(session, product["sku"])
        r = self.checkout(session, key)
        assert r.status_code == 201, r.text
        return r.json()

    def webhook(self, event: dict, secret: str = WEBHOOK_SECRET, signature: str | None = None):
        import json

        body = json.dumps(event).encode()
        sig = signature if signature is not None else sign_webhook(secret, body)
        return self.client.post("/api/v1/payments/webhook", content=body, headers={"X-Signature": sig})

    def pay(self, order: dict, event_id: str = "evt_test_0001", kind: str = "payment.succeeded"):
        return self.webhook({"id": event_id, "type": kind, "paymentId": order["payment"]["id"]})


@pytest.fixture
def api(client, store) -> Api:
    return Api(client, store)
