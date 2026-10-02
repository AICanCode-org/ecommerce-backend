"""M5: checkout, orders, the webhook and the outbox over HTTP."""


def test_checkout_201_then_replay_200(api, client):
    session = api.customer()
    api.product(sku="MUG-1", name="Mug", pricePaise=45_000, stock=2)
    api.add(session, "MUG-1", 1)
    r = api.checkout(session)
    assert r.status_code == 201 and r.headers["location"] == f"/api/v1/orders/{r.json()['id']}"
    assert "idempotent-replayed" not in r.headers
    assert r.json() == {
        "id": r.json()["id"],
        "userId": session["user"]["id"],
        "status": "PENDING_PAYMENT",
        "items": [{"sku": "MUG-1", "name": "Mug", "unitPricePaise": 45_000, "quantity": 1, "lineTotalPaise": 45_000}],
        "subtotalPaise": 45_000,
        "discountPaise": 0,
        "shippingPaise": 4_900,
        "totalPaise": 49_900,
        "total": "₹499.00",
        "couponCode": None,
        "payment": {"id": "pay_test_1", "provider": "fakepay", "status": "PENDING", "amountPaise": 49_900},
        "createdAt": "2026-10-02T09:30:00.000Z",
        "updatedAt": "2026-10-02T09:30:00.000Z",
    }
    again = api.checkout(session)
    assert again.status_code == 200 and again.headers["idempotent-replayed"] == "true"
    assert again.json() == r.json()


def test_checkout_errors(api, client):
    session = api.customer()
    r = client.post("/api/v1/checkout", headers=api.h(session))
    assert r.status_code == 400 and r.json()["detail"] == "Idempotency-Key header is required"
    assert api.checkout(session).status_code == 409
    assert client.post("/api/v1/checkout", headers={"Idempotency-Key": "checkout-key-0001"}).status_code == 401


def test_order_endpoints(api, client):
    asha, ravi = api.customer(), api.customer()
    order = api.order(asha)
    url = f"/api/v1/orders/{order['id']}"
    assert client.get(url, headers=api.h(asha)).json()["id"] == order["id"]
    assert client.get(url, headers=api.h(ravi)).status_code == 404
    assert client.get("/api/v1/orders/abc", headers=api.h(asha)).status_code == 404
    assert [o["id"] for o in client.get("/api/v1/orders", headers=api.h(asha)).json()["items"]] == [order["id"]]
    r = client.post(f"{url}/cancel", headers=api.h(asha))
    assert r.status_code == 200 and r.json()["status"] == "CANCELLED"
    again = client.post(f"{url}/cancel", headers=api.h(asha))
    assert again.status_code == 409 and again.json()["type"].endswith("/invalid-transition")


def test_webhook_over_http(api, client):
    order = api.order(api.customer())
    r = api.pay(order)
    assert r.status_code == 200 and r.json() == {"eventId": "evt_test_0001", "status": "processed"}
    assert api.pay(order).json()["status"] == "duplicate"
    bad = api.webhook({"id": "evt_test_0002", "type": "payment.failed", "paymentId": "x"}, signature="sha256=00")
    assert bad.status_code == 401 and bad.json()["type"].endswith("/invalid-signature")
    admin = api.h(api.admin())
    assert client.get(f"/api/v1/orders/{order['id']}", headers=admin).json()["payment"]["status"] == "SUCCEEDED"


def test_admin_fulfilment_flow(api, client):
    order = api.order(api.customer())
    api.pay(order)
    admin = api.h(api.admin())
    url = f"/api/v1/admin/orders/{order['id']}/status"
    assert client.post(url, json={"status": "SHIPPED"}, headers=admin).json()["status"] == "SHIPPED"
    assert client.post(url, json={"status": "PAID"}, headers=admin).status_code == 400
    assert client.post(url, json={"status": "DELIVERED"}, headers=admin).json()["status"] == "DELIVERED"
    listed = client.get("/api/v1/admin/orders?status=DELIVERED", headers=admin).json()["items"]
    assert [o["id"] for o in listed] == [order["id"]]
    assert client.get("/api/v1/admin/orders", headers=api.h(api.customer())).status_code == 403


def test_outbox_endpoints(api, client):
    order = api.order(api.customer())
    api.pay(order)
    admin = api.h(api.admin())
    events = client.get("/api/v1/admin/outbox?pending=true", headers=admin).json()["items"]
    assert [(e["type"], e["orderId"], e["publishedAt"]) for e in events] == [
        ("order.placed", order["id"], None),
        ("order.paid", order["id"], None),
    ]
    assert events[1]["payload"] == {"orderId": order["id"], "userId": order["userId"], "status": "PAID",
                                    "totalPaise": order["totalPaise"]}  # fmt: skip
    assert client.post("/api/v1/admin/outbox/publish", headers=admin).json() == {"published": 2}
    assert client.get("/api/v1/admin/outbox?pending=true", headers=admin).json()["items"] == []
    published = client.get("/api/v1/admin/outbox", headers=admin).json()["items"]
    assert all(e["publishedAt"] == "2026-10-02T09:30:00.000Z" for e in published)
    assert client.post("/api/v1/admin/outbox/publish", headers=api.h(api.customer())).status_code == 403
