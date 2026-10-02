"""v1.1 (Phase 7): unpaid orders hold their stock for 15 minutes, then an admin job cancels them."""

import pytest

from app.cart import CartService
from app.catalog import CatalogService
from app.errors import ApiError
from app.models import Principal
from app.orders import RESERVATION_TTL, OrderService, next_status
from tests.conftest import PaymentIds

ADMIN = Principal(1, "ADMIN")
ASHA = Principal(2, "CUSTOMER")


@pytest.fixture
def catalog(store, clock) -> CatalogService:
    c = CatalogService(store, clock)
    c.create(ADMIN, {"sku": "TEE-1", "name": "Tee", "category": "apparel", "pricePaise": 29_900, "stock": 3})
    return c


@pytest.fixture
def cart(store, clock, catalog) -> CartService:
    return CartService(store, store, clock)


@pytest.fixture
def svc(store, clock) -> OrderService:
    return OrderService(store, store, clock, PaymentIds())


def test_checkout_sets_a_15_minute_payment_window(svc, cart, clock):
    cart.set_item(ASHA, "TEE-1", {"quantity": 1})
    order, _ = svc.checkout(ASHA, "expiry-key-0001")
    assert RESERVATION_TTL.total_seconds() == 15 * 60
    assert order.expires_at == clock.now + RESERVATION_TTL


def test_expire_cancels_only_overdue_unpaid_orders_and_restocks(svc, cart, catalog, store, clock):
    cart.set_item(ASHA, "TEE-1", {"quantity": 2})
    old, _ = svc.checkout(ASHA, "expiry-key-0001")
    clock.advance(minutes=10)
    cart.set_item(ASHA, "TEE-1", {"quantity": 1})
    fresh, _ = svc.checkout(ASHA, "expiry-key-0002")
    assert catalog.get(ADMIN, "TEE-1").stock == 0
    clock.advance(minutes=4, seconds=59)
    assert svc.expire_overdue(ADMIN) == 0  # 14:59 after the first checkout: still inside the window
    clock.advance(seconds=1)
    assert svc.expire_overdue(ADMIN) == 1  # exactly 15:00: the window is closed
    assert store.orders[old.id].status == "CANCELLED" and store.orders[fresh.id].status == "PENDING_PAYMENT"
    assert catalog.get(ADMIN, "TEE-1").stock == 2
    assert [e.type for e in store.events] == ["order.placed", "order.placed", "order.cancelled"]
    assert svc.expire_overdue(ADMIN) == 0  # running it again changes nothing


def test_paid_orders_never_expire(svc, cart, store, clock):
    cart.set_item(ASHA, "TEE-1", {"quantity": 1})
    order, _ = svc.checkout(ASHA, "expiry-key-0001")
    store.orders[order.id].status = "PAID"
    clock.advance(hours=1)
    assert svc.expire_overdue(ADMIN) == 0 and store.orders[order.id].status == "PAID"


def test_only_admins_run_the_expiry_job(svc):
    with pytest.raises(ApiError) as e:
        svc.expire_overdue(ASHA)
    assert e.value.status == 403


def test_a_race_with_the_webhook_is_skipped_not_an_error(svc, cart, store, clock, monkeypatch):
    cart.set_item(ASHA, "TEE-1", {"quantity": 1})
    order, _ = svc.checkout(ASHA, "expiry-key-0001")
    clock.advance(minutes=20)
    stale = store.expired_orders(clock.now, 10)
    store.orders[order.id].status = "PAID"  # the payment webhook won the race
    monkeypatch.setattr(store, "expired_orders", lambda now, limit: stale)
    assert svc.expire_overdue(ADMIN) == 0 and store.orders[order.id].status == "PAID"


def test_expire_is_a_state_machine_transition():
    assert next_status("PENDING_PAYMENT", "expire") == "CANCELLED"
    with pytest.raises(ApiError):
        next_status("PAID", "expire")


# ---- over HTTP -----------------------------------------------------------------------------------
def test_expire_endpoint_and_a_late_payment_is_refunded(api, client, clock):
    asha = api.customer()
    order = api.order(asha, stock=1)
    assert order["expiresAt"] == "2026-10-02T09:45:00.000Z"
    url = "/api/v1/admin/orders/expire"
    assert client.post(url, headers=api.h(asha)).status_code == 403
    assert client.post(url).status_code == 401
    assert client.post(url, headers=api.h(api.admin())).json() == {"expired": 0}
    clock.advance(minutes=15)
    assert client.post(url, headers=api.h(api.admin())).json() == {"expired": 1}
    after = client.get(f"/api/v1/orders/{order['id']}", headers=api.h(asha)).json()
    assert after["status"] == "CANCELLED" and after["payment"]["status"] == "PENDING"
    sku = order["items"][0]["sku"]
    assert client.get(f"/api/v1/products/{sku}").json()["stock"] == 1
    # The customer paid at the very last second and the provider's webhook arrives late: refund, no resurrection.
    assert api.pay(order).json() == {"eventId": "evt_test_0001", "status": "processed"}
    events = client.get("/api/v1/admin/outbox", headers=api.h(api.admin())).json()["items"]
    assert [e["type"] for e in events][-2:] == ["order.cancelled", "refund.requested"]
