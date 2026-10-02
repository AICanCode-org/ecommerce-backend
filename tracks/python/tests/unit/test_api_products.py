"""M3: the catalog endpoints over HTTP."""

NEW = {"sku": "tee-blk-m", "name": "Black Tee (M)", "category": "apparel", "pricePaise": 59_900, "stock": 4}


def test_create_returns_201_location_and_the_full_product(api, client):
    r = client.post("/api/v1/products", json=NEW, headers=api.h(api.admin()))
    assert r.status_code == 201 and r.headers["location"] == "/api/v1/products/TEE-BLK-M"
    assert r.json() == {
        "id": r.json()["id"],
        "sku": "TEE-BLK-M",
        "name": "Black Tee (M)",
        "description": "",
        "category": "apparel",
        "pricePaise": 59_900,
        "price": "₹599.00",
        "stock": 4,
        "inStock": True,
        "active": True,
        "version": 1,
        "createdAt": "2026-10-02T09:30:00.000Z",
        "updatedAt": "2026-10-02T09:30:00.000Z",
    }


def test_catalog_writes_need_an_admin(api, client):
    r = client.post("/api/v1/products", json=NEW)
    assert r.status_code == 401 and r.headers["www-authenticate"] == "Bearer"
    assert client.post("/api/v1/products", json=NEW, headers=api.h(api.customer())).status_code == 403


def test_public_read_and_list(api, client):
    p = api.product(sku="TEE-1")
    api.product(sku="TEE-2", active=False)
    assert client.get("/api/v1/products/tee-1").json()["sku"] == "TEE-1"
    assert client.get("/api/v1/products/TEE-2").status_code == 404
    assert client.get("/api/v1/products/TEE-2", headers=api.h(api.admin())).status_code == 200
    body = client.get("/api/v1/products").json()
    assert body == {
        "items": [
            {
                "sku": "TEE-1",
                "name": p["name"],
                "category": "shoes",
                "pricePaise": 249_900,
                "price": "₹2,499.00",
                "inStock": True,
            }  # fmt: skip
        ],
        "nextCursor": None,
    }


def test_list_query_parameters_are_validated(client):
    assert client.get("/api/v1/products?limit=0").status_code == 400
    assert client.get("/api/v1/products?cursor=%%%").status_code == 400
    assert client.get("/api/v1/products?inStock=maybe").json()["detail"] == "inStock must be true or false"
    assert client.get("/api/v1/products?minPricePaise=9&maxPricePaise=1").status_code == 400


def test_patch_with_optimistic_locking(api, client):
    p = api.product(sku="TEE-1")
    url, admin = "/api/v1/products/TEE-1", api.h(api.admin())
    ok = client.patch(url, json={"version": 1, "pricePaise": 199_900}, headers=admin)
    assert ok.status_code == 200 and ok.json()["version"] == 2 and ok.json()["price"] == "₹1,999.00"
    stale = client.patch(url, json={"version": 1, "name": "Lost update"}, headers=admin)
    assert stale.status_code == 409 and stale.json()["type"].endswith("/version-conflict")
    assert client.get(url).json()["name"] == p["name"]


def test_stock_endpoint(api, client):
    api.product(sku="TEE-1", stock=1)
    admin = api.h(api.admin())
    assert client.post("/api/v1/products/TEE-1/stock", json={"delta": -1}, headers=admin).json()["inStock"] is False
    r = client.post("/api/v1/products/TEE-1/stock", json={"delta": -1}, headers=admin)
    assert r.status_code == 409 and r.json()["type"].endswith("/insufficient-stock")


def test_categories_endpoint(api, client):
    api.product(sku="TEE-1", category="apparel")
    api.product(sku="TEE-2", category="apparel")
    assert client.get("/api/v1/categories").json() == {"items": [{"name": "apparel", "products": 2}]}
