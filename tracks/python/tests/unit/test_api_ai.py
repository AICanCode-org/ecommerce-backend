"""M6: the AI endpoints over HTTP (offline providers)."""


def test_description_draft_is_admin_only_and_never_saved(api, client):
    p = api.product(sku="SHOE-1")
    url = "/api/v1/products/SHOE-1/description-draft"
    r = client.post(url, json={"keywords": ["Vegan"]}, headers=api.h(api.admin()))
    assert r.status_code == 200
    assert r.json() == {
        "sku": "SHOE-1",
        "provider": "template",
        "fallback": False,
        "description": "Trail Running Shoes from our shoes range. Light and grippy. Highlights: vegan. "
        "In stock and ready to ship.",
        "generatedAt": "2026-10-02T09:30:00.000Z",
    }
    assert client.get("/api/v1/products/SHOE-1").json()["description"] == p["description"]
    assert client.post(url, headers=api.h(api.admin())).status_code == 200  # the body is optional
    assert client.post(url, headers=api.h(api.customer())).status_code == 403
    assert client.post(url).status_code == 401
    assert client.post(url, json={"keywords": "x"}, headers=api.h(api.admin())).status_code == 400
    assert client.post("/api/v1/products/NOPE-1/description-draft", headers=api.h(api.admin())).status_code == 404


def test_search_assist(api, client):
    api.product(sku="SHOE-1", name="Trail Running Shoes", pricePaise=249_900)
    api.product(sku="SHOE-2", name="Road Running Shoes", pricePaise=349_900)
    api.product(sku="BAG-1", name="Running Belt", category="bags", pricePaise=99_900)
    r = client.get("/api/v1/search/assist", params={"q": "running shoes under 3000"})
    assert r.status_code == 200
    body = r.json()
    assert body["query"] == "running shoes under 3000"
    assert (body["provider"], body["fallback"]) == ("rules", False)
    assert body["filters"] == {"terms": ["running"], "category": "shoes", "minPricePaise": None,
                               "maxPricePaise": 300_000, "inStock": None}  # fmt: skip
    assert [p["sku"] for p in body["items"]] == ["SHOE-1"]
    assert client.get("/api/v1/search/assist").status_code == 400
    assert client.get("/api/v1/search/assist", params={"q": "x" * 201}).status_code == 400
