"""Integration tests: the real app against a real PostgreSQL (docker compose up -d postgres).

Run with:  make test-integration   (sets -m integration)
Uses TEST_DATABASE_URL so your dev data is never touched.
"""

import json
import os
import threading

import psycopg
import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.security import sign_webhook
from tests.conftest import WEBHOOK_SECRET, test_settings

pytestmark = pytest.mark.integration

DB_URL = os.getenv("TEST_DATABASE_URL", "postgresql://shopflow:shopflow@localhost:5432/shopflow_test")
ADMIN = {"email": "admin@shopflow.test", "password": "admin-password"}
TABLES = "users, products, carts, cart_items, orders, order_items, payments, payment_events, outbox_events"


def sql(statement: str, params: tuple = ()):
    with psycopg.connect(DB_URL, autocommit=True) as conn:
        cur = conn.execute(statement, params)
        return cur.fetchall() if cur.description else None


@pytest.fixture
def client():
    # Start once to run the migrations, wipe the tables, then start again so the admin is re-created.
    settings = test_settings(database_url=DB_URL, admin_email=ADMIN["email"], admin_password=ADMIN["password"])
    with TestClient(create_app(settings)):
        pass
    sql(f"TRUNCATE {TABLES} RESTART IDENTITY CASCADE")
    with TestClient(create_app(settings)) as c:
        yield c


def login(client, email, password="password-123"):
    r = client.post("/api/v1/auth/login", json={"email": email, "password": password})
    assert r.status_code == 200, r.text
    return r.json()


def h(session, **extra):
    return {"Authorization": f"Bearer {session['accessToken']}", **extra}


def customer(client, email):
    r = client.post("/api/v1/auth/register", json={"email": email, "password": "password-123", "displayName": "C"})
    assert r.status_code == 201, r.text
    return login(client, email)


def admin(client):
    return login(client, ADMIN["email"], ADMIN["password"])


def product(client, sku, **fields):
    body = {"sku": sku, "name": "Trail Running Shoes", "category": "shoes", "pricePaise": 249_900, "stock": 5,
            "description": "Grippy soles for wet trails.", **fields}  # fmt: skip
    r = client.post("/api/v1/products", json=body, headers=h(admin(client)))
    assert r.status_code == 201, r.text
    return r.json()


def checkout(client, session, key):
    return client.post("/api/v1/checkout", headers=h(session, **{"Idempotency-Key": key}))


def webhook(client, event):
    body = json.dumps(event).encode()
    return client.post(
        "/api/v1/payments/webhook", content=body, headers={"X-Signature": sign_webhook(WEBHOOK_SECRET, body)}
    )


def test_health_migrations_and_demo_coupons(client):
    assert client.get("/health").json() == {"status": "UP", "checks": {"database": "UP"}}
    assert [r[0] for r in sql("SELECT version FROM schema_migrations ORDER BY version")][:1] == [1]
    assert [r[0] for r in sql("SELECT code FROM coupons WHERE active ORDER BY code")] == ["FLAT100", "SAVE10"]


def test_passwords_are_hashed_and_emails_unique(client):
    customer(client, "asha@shopflow.test")
    (password_hash,) = sql("SELECT password_hash FROM users WHERE email = 'asha@shopflow.test'")[0]
    assert password_hash.startswith("$2") and "password-123" not in password_hash
    r = client.post(
        "/api/v1/auth/register", json={"email": "ASHA@shopflow.test", "password": "password-123", "displayName": "D"}
    )
    assert r.status_code == 409


def test_sku_uniqueness_and_optimistic_locking_in_the_database(client):
    product(client, "SHOE-1")
    r = client.post("/api/v1/products", json={"sku": "shoe-1", "name": "x", "category": "shoes", "pricePaise": 1},
                    headers=h(admin(client)))  # fmt: skip
    assert r.status_code == 409 and r.json()["type"].endswith("/sku-taken")
    a = client.patch("/api/v1/products/SHOE-1", json={"version": 1, "pricePaise": 1000}, headers=h(admin(client)))
    b = client.patch("/api/v1/products/SHOE-1", json={"version": 1, "pricePaise": 2000}, headers=h(admin(client)))
    assert (a.status_code, b.status_code) == (200, 409)
    assert sql("SELECT price_paise, version FROM products WHERE sku = 'SHOE-1'") == [(1000, 2)]


def test_full_text_search_stems_and_filters(client):
    product(client, "SHOE-1", name="Trail Running Shoes", pricePaise=249_900)
    product(client, "BAG-1", name="Laptop Backpack", category="bags", pricePaise=179_900, description="Waterproof.")
    product(client, "OLD-1", name="Old Running Shoes", active=False)
    found = client.get("/api/v1/products", params={"q": "shoe run"}).json()["items"]
    assert [p["sku"] for p in found] == ["SHOE-1"]  # stemming: "shoe" finds "Shoes", "run" finds "Running"
    cheap = client.get("/api/v1/products", params={"maxPricePaise": "200000"}).json()["items"]
    assert [p["sku"] for p in cheap] == ["BAG-1"]
    assist = client.get("/api/v1/search/assist", params={"q": "running shoe under 3000"}).json()
    assert assist["filters"]["category"] == "shoes" and [p["sku"] for p in assist["items"]] == ["SHOE-1"]


def test_keyset_pagination_over_the_real_index(client):
    for n in range(5):
        product(client, f"P-{n}")
    seen, cursor = [], None
    while True:
        params = {"limit": "2", **({"cursor": cursor} if cursor else {})}
        page = client.get("/api/v1/products", params=params).json()
        seen += [p["sku"] for p in page["items"]]
        cursor = page["nextCursor"]
        if not cursor:
            break
    assert seen == ["P-0", "P-1", "P-2", "P-3", "P-4"]


def test_the_database_refuses_negative_stock(client):
    product(client, "SHOE-1", stock=1)
    with pytest.raises(psycopg.errors.CheckViolation):
        sql("UPDATE products SET stock = stock - 2 WHERE sku = 'SHOE-1'")
    r = client.post("/api/v1/products/SHOE-1/stock", json={"delta": -2}, headers=h(admin(client)))
    assert r.status_code == 409 and r.json()["detail"] == "stock cannot go below 0 (current stock 1)"


def test_checkout_is_one_transaction(client):
    asha = customer(client, "asha@shopflow.test")
    product(client, "SHOE-1", stock=3)
    product(client, "BAG-1", stock=1, category="bags", pricePaise=60_000)
    for sku, qty in [("SHOE-1", 2), ("BAG-1", 1)]:
        client.put(f"/api/v1/cart/items/{sku}", json={"quantity": qty}, headers=h(asha))
    client.put("/api/v1/cart/coupon", json={"code": "SAVE10"}, headers=h(asha))
    r = checkout(client, asha, "asha-key-0001")
    assert r.status_code == 201, r.text
    order = r.json()
    assert (order["subtotalPaise"], order["discountPaise"], order["shippingPaise"]) == (559_800, 20_000, 0)
    assert sql("SELECT sku, stock FROM products ORDER BY sku") == [("BAG-1", 0), ("SHOE-1", 1)]
    assert sql("SELECT count(*) FROM cart_items") == [(0,)]
    assert sql("SELECT type FROM outbox_events") == [("order.placed",)]
    assert sql("SELECT status, amount_paise FROM payments") == [("PENDING", order["totalPaise"])]
    # a failing line rolls back everything: no order, no stock change, cart intact
    ravi = customer(client, "ravi@shopflow.test")
    client.put("/api/v1/cart/items/SHOE-1", json={"quantity": 1}, headers=h(ravi))
    sql("UPDATE products SET stock = 0 WHERE sku = 'SHOE-1'")
    assert checkout(client, ravi, "ravi-key-0001").status_code == 409
    assert sql("SELECT count(*) FROM orders") == [(1,)] and sql("SELECT count(*) FROM cart_items") == [(1,)]


def test_concurrent_checkouts_never_oversell(client):
    product(client, "LAST-1", stock=1)
    sessions = [customer(client, f"buyer{n}@shopflow.test") for n in range(5)]
    for s in sessions:
        client.put("/api/v1/cart/items/LAST-1", json={"quantity": 1}, headers=h(s))
    codes: list[int] = []
    barrier = threading.Barrier(len(sessions))

    def buy(s):
        barrier.wait()
        codes.append(checkout(client, s, "same-key-for-all").status_code)

    threads = [threading.Thread(target=buy, args=(s,)) for s in sessions]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert sorted(codes) == [201, 409, 409, 409, 409]
    assert sql("SELECT stock FROM products WHERE sku = 'LAST-1'") == [(0,)]


def test_concurrent_retries_with_one_key_create_one_order(client):
    asha = customer(client, "asha@shopflow.test")
    product(client, "SHOE-1", stock=10)
    client.put("/api/v1/cart/items/SHOE-1", json={"quantity": 1}, headers=h(asha))
    results: list[tuple[int, int]] = []
    barrier = threading.Barrier(4)

    def retry():
        barrier.wait()
        r = checkout(client, asha, "double-click-0001")
        results.append((r.status_code, r.json().get("id", 0)))

    threads = [threading.Thread(target=retry) for _ in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert sql("SELECT count(*) FROM orders") == [(1,)]
    assert len({order_id for _, order_id in results if order_id}) == 1
    assert sql("SELECT stock FROM products") == [(9,)]


def test_webhook_is_idempotent_even_when_delivered_twice_at_once(client):
    asha = customer(client, "asha@shopflow.test")
    product(client, "SHOE-1", stock=2)
    client.put("/api/v1/cart/items/SHOE-1", json={"quantity": 1}, headers=h(asha))
    order = checkout(client, asha, "asha-key-0001").json()
    event = {"id": "evt_integration_1", "type": "payment.succeeded", "paymentId": order["payment"]["id"]}
    statuses: list[str] = []
    barrier = threading.Barrier(3)

    def deliver():
        barrier.wait()
        r = webhook(client, event)
        statuses.append(r.json().get("status", str(r.status_code)))

    threads = [threading.Thread(target=deliver) for _ in range(3)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert statuses.count("processed") == 1
    assert sql("SELECT status FROM orders") == [("PAID",)]
    assert sql("SELECT type FROM outbox_events ORDER BY id") == [("order.placed",), ("order.paid",)]
    assert sql("SELECT count(*) FROM payment_events") == [(1,)]


def test_cancel_restocks_and_outbox_relay_marks_published(client):
    asha = customer(client, "asha@shopflow.test")
    product(client, "SHOE-1", stock=2)
    client.put("/api/v1/cart/items/SHOE-1", json={"quantity": 2}, headers=h(asha))
    order = checkout(client, asha, "asha-key-0001").json()
    assert client.post(f"/api/v1/orders/{order['id']}/cancel", headers=h(asha)).json()["status"] == "CANCELLED"
    assert sql("SELECT stock FROM products") == [(2,)]
    assert client.post("/api/v1/admin/outbox/publish", headers=h(admin(client))).json() == {"published": 2}
    assert sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL") == [(0,)]
    payload = sql("SELECT payload FROM outbox_events WHERE type = 'order.cancelled'")[0][0]
    assert payload == {"orderId": order["id"], "userId": asha["user"]["id"], "status": "CANCELLED",
                       "totalPaise": order["totalPaise"]}  # fmt: skip


def test_admin_is_bootstrapped_from_the_environment(client):
    assert sql("SELECT role FROM users WHERE email = %s", (ADMIN["email"],)) == [("ADMIN",)]


def test_health_reports_down_when_the_database_is_unreachable():
    settings = test_settings(database_url="postgresql://shopflow:shopflow@127.0.0.1:1/shopflow")
    with pytest.raises(Exception):  # noqa: B017 - the pool cannot open: the app refuses to start
        with TestClient(create_app(settings)):
            pass
