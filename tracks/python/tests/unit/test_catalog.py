"""M3: CatalogService - products, optimistic locking, stock, search and keyset pagination."""

import pytest

from app.catalog import CatalogService
from app.errors import ApiError
from app.models import Principal

ADMIN = Principal(1, "ADMIN")
CUSTOMER = Principal(2, "CUSTOMER")
KETTLE = {"sku": "kit-001", "name": "Steel Kettle", "category": "Kitchen", "pricePaise": 129_900, "stock": 3}


@pytest.fixture
def svc(store, clock) -> CatalogService:
    return CatalogService(store, clock)


def status(fn, *args, **kwargs) -> tuple[int, str, str | None]:
    with pytest.raises(ApiError) as e:
        fn(*args, **kwargs)
    return e.value.status, e.value.slug, e.value.detail


def test_create_normalises_and_defaults(svc):
    p = svc.create(ADMIN, KETTLE)
    assert (p.sku, p.category, p.description, p.active, p.version) == ("KIT-001", "kitchen", "", True, 1)
    assert p.created_at == p.updated_at


def test_only_admins_create_and_skus_are_unique(svc):
    assert status(svc.create, CUSTOMER, KETTLE)[:2] == (403, "forbidden")
    svc.create(ADMIN, KETTLE)
    assert status(svc.create, ADMIN, {**KETTLE, "sku": "KIT-001"}) == (
        409,
        "sku-taken",
        "a product with this SKU exists",
    )


def test_create_validates_money_as_integer_paise(svc):
    assert status(svc.create, ADMIN, {**KETTLE, "pricePaise": 1299.0})[0] == 400
    assert status(svc.create, ADMIN, {**KETTLE, "active": "yes"})[2] == "active must be true or false"


def test_inactive_products_are_hidden_from_everyone_but_admins(svc):
    svc.create(ADMIN, {**KETTLE, "active": False})
    assert svc.get(ADMIN, "kit-001").sku == "KIT-001"
    assert status(svc.get, CUSTOMER, "KIT-001") == (404, "not-found", "product not found")
    assert status(svc.get, None, "KIT-001")[0] == 404
    assert status(svc.get, None, "NOPE-1")[0] == 404


def test_update_uses_optimistic_locking(svc, clock):
    svc.create(ADMIN, KETTLE)
    clock.advance(minutes=5)
    p = svc.update(ADMIN, "KIT-001", {"version": 1, "pricePaise": 119_900, "name": " Steel Kettle 1.5L "})
    assert (p.price_paise, p.name, p.version, p.stock) == (119_900, "Steel Kettle 1.5L", 2, 3)
    assert p.updated_at > p.created_at
    assert status(svc.update, ADMIN, "KIT-001", {"version": 1, "pricePaise": 1}) == (
        409,
        "version-conflict",
        "the product was changed by someone else (current version 2)",
    )


def test_update_order_of_checks(svc):
    svc.create(ADMIN, KETTLE)
    assert status(svc.update, CUSTOMER, "KIT-001", {"version": 1, "name": "x"})[0] == 403
    assert status(svc.update, ADMIN, "KIT-001", {"name": "x"})[2] == "version must be a positive integer"
    assert status(svc.update, ADMIN, "KIT-001", {"version": 1, "stock": 5})[2] == (
        "nothing to update: send at least one of name, description, category, pricePaise, active"
    )
    assert status(svc.update, ADMIN, "NOPE-1", {"version": 1, "name": "x"})[0] == 404
    assert status(svc.update, ADMIN, "KIT-001", {"version": 1, "name": ""})[0] == 400


def test_stock_moves_by_a_delta_and_never_below_zero(svc):
    svc.create(ADMIN, KETTLE)
    assert svc.adjust_stock(ADMIN, "KIT-001", {"delta": 7}).stock == 10
    p = svc.adjust_stock(ADMIN, "KIT-001", {"delta": -10})
    assert p.stock == 0 and p.version == 1  # stock changes do not bump the version (ADR-0003)
    assert status(svc.adjust_stock, ADMIN, "KIT-001", {"delta": -1}) == (
        409,
        "insufficient-stock",
        "stock cannot go below 0 (current stock 0)",
    )
    assert status(svc.adjust_stock, CUSTOMER, "KIT-001", {"delta": 1})[0] == 403
    assert status(svc.adjust_stock, ADMIN, "KIT-001", {"delta": 0})[0] == 400


def seed(svc):
    rows = [
        ("SHOE-1", "Trail Running Shoes", "shoes", 249_900, 5, "Grippy soles for wet trails."),
        ("SHOE-2", "Canvas Sneakers", "shoes", 99_900, 0, "Everyday white sneakers."),
        ("KIT-1", "Steel Kettle", "kitchen", 129_900, 2, "Boils water fast."),
        ("KIT-2", "Cast Iron Pan", "kitchen", 189_900, 1, "Pre-seasoned pan."),
        ("BAG-1", "Laptop Backpack", "bags", 179_900, 9, "Water resistant, fits 15 inch laptops."),
    ]
    for sku, name, cat, price, stock, desc in rows:
        svc.create(ADMIN, {"sku": sku, "name": name, "category": cat, "pricePaise": price, "stock": stock,
                           "description": desc})  # fmt: skip
    svc.create(ADMIN, {"sku": "OLD-1", "name": "Old Shoes", "category": "shoes", "pricePaise": 100, "active": False})


def skus(page):
    return [p.sku for p in page[0]]


def test_list_filters(svc):
    seed(svc)
    assert skus(svc.list()) == ["SHOE-1", "SHOE-2", "KIT-1", "KIT-2", "BAG-1"]  # active only, id order
    assert skus(svc.list(category="Shoes")) == ["SHOE-1", "SHOE-2"]
    assert skus(svc.list(min_price="130000")) == ["SHOE-1", "KIT-2", "BAG-1"]
    assert skus(svc.list(max_price="129900")) == ["SHOE-2", "KIT-1"]
    assert skus(svc.list(in_stock="true", category="shoes")) == ["SHOE-1"]
    assert skus(svc.list(in_stock="false")) == ["SHOE-2"]
    assert skus(svc.list(q="water")) == ["KIT-1", "BAG-1"]
    assert skus(svc.list(q="laptop backpack")) == ["BAG-1"]
    assert skus(svc.list(q="steel kettle")) == ["KIT-1"]


def test_list_rejects_an_impossible_price_range(svc):
    assert status(svc.list, min_price="500", max_price="100")[2] == (
        "minPricePaise must not be greater than maxPricePaise"
    )


def test_keyset_pagination_walks_every_product_once(svc):
    seed(svc)
    page1, cursor = svc.list(limit="2")
    assert [p.sku for p in page1] == ["SHOE-1", "SHOE-2"] and cursor
    page2, cursor = svc.list(limit="2", cursor=cursor)
    assert [p.sku for p in page2] == ["KIT-1", "KIT-2"]
    page3, cursor = svc.list(limit="2", cursor=cursor)
    assert [p.sku for p in page3] == ["BAG-1"] and cursor is None


def test_categories_count_active_products(svc):
    seed(svc)
    assert [(c.name, c.products) for c in svc.categories()] == [("bags", 1), ("kitchen", 2), ("shoes", 2)]
