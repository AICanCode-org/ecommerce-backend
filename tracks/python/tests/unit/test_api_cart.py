"""M4: the cart endpoints over HTTP."""


def test_cart_needs_a_token(client):
    assert client.get("/api/v1/cart").status_code == 401
    assert client.put("/api/v1/cart/items/X-1", json={"quantity": 1}).status_code == 401


def test_cart_json(api, client):
    session = api.customer()
    api.product(sku="MUG-1", name="Mug", pricePaise=45_000, stock=2)
    body = api.add(session, "mug-1", 2)
    assert body == {
        "items": [
            {
                "sku": "MUG-1",
                "name": "Mug",
                "unitPricePaise": 45_000,
                "quantity": 2,
                "lineTotalPaise": 90_000,
                "available": True,
            }  # fmt: skip
        ],
        "couponCode": None,
        "couponApplied": False,
        "subtotalPaise": 90_000,
        "discountPaise": 0,
        "shippingPaise": 4_900,
        "totalPaise": 94_900,
        "total": "₹949.00",
    }


def test_lines_become_unavailable_when_stock_runs_out(api, client):
    session = api.customer()
    api.product(sku="MUG-1", stock=2)
    api.add(session, "MUG-1", 2)
    client.post("/api/v1/products/MUG-1/stock", json={"delta": -1}, headers=api.h(api.admin()))
    assert client.get("/api/v1/cart", headers=api.h(session)).json()["items"][0]["available"] is False


def test_coupon_endpoints(api, client):
    session = api.customer()
    api.product(sku="MUG-1", pricePaise=60_000)
    api.add(session, "MUG-1")
    r = client.put("/api/v1/cart/coupon", json={"code": "flat100"}, headers=api.h(session))
    assert r.status_code == 200 and r.json()["discountPaise"] == 10_000 and r.json()["couponCode"] == "FLAT100"
    bad = client.put("/api/v1/cart/coupon", json={"code": "NOPE99"}, headers=api.h(session))
    assert bad.status_code == 404 and bad.json()["detail"] == "coupon not found"
    r = client.delete("/api/v1/cart/coupon", headers=api.h(session))
    assert r.json()["couponCode"] is None


def test_delete_item(api, client):
    session = api.customer()
    api.product(sku="MUG-1")
    api.add(session, "MUG-1")
    r = client.delete("/api/v1/cart/items/MUG-1", headers=api.h(session))
    assert r.status_code == 200 and r.json()["items"] == []
