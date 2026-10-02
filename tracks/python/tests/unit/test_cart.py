"""M4: CartService - quantities, stock checks, coupons and live pricing."""

import pytest

from app.cart import CartService
from app.catalog import CatalogService
from app.errors import ApiError
from app.models import Principal

ADMIN = Principal(1, "ADMIN")
ASHA = Principal(2, "CUSTOMER")
RAVI = Principal(3, "CUSTOMER")


@pytest.fixture
def catalog(store, clock) -> CatalogService:
    c = CatalogService(store, clock)
    c.create(ADMIN, {"sku": "TEE-1", "name": "Tee", "category": "apparel", "pricePaise": 29_900, "stock": 3})
    c.create(ADMIN, {"sku": "MUG-1", "name": "Mug", "category": "kitchen", "pricePaise": 45_000, "stock": 10})
    return c


@pytest.fixture
def svc(store, clock, catalog) -> CartService:
    return CartService(store, store, clock)


def status(fn, *args) -> tuple[int, str, str | None]:
    with pytest.raises(ApiError) as e:
        fn(*args)
    return e.value.status, e.value.slug, e.value.detail


def test_empty_cart(svc):
    view = svc.view(ASHA)
    assert view.lines == [] and view.coupon_code is None
    assert (view.pricing.subtotal_paise, view.pricing.shipping_paise, view.pricing.total_paise) == (0, 0, 0)


def test_set_item_sets_the_quantity_it_does_not_add(svc):
    svc.set_item(ASHA, "tee-1", {"quantity": 2})
    view = svc.set_item(ASHA, "TEE-1", {"quantity": 1})
    assert [(x.product.sku, x.quantity) for x in view.lines] == [("TEE-1", 1)]
    assert (view.pricing.subtotal_paise, view.pricing.shipping_paise, view.pricing.total_paise) == (
        29_900,
        4_900,
        34_800,
    )


def test_carts_are_per_user(svc):
    svc.set_item(ASHA, "TEE-1", {"quantity": 1})
    assert svc.view(RAVI).lines == []


def test_quantity_is_checked_against_stock(svc):
    assert status(svc.set_item, ASHA, "TEE-1", {"quantity": 4}) == (409, "insufficient-stock", "only 3 left of TEE-1")
    assert status(svc.set_item, ASHA, "TEE-1", {"quantity": 0})[0] == 400
    assert status(svc.set_item, ASHA, "NOPE-1", {"quantity": 1}) == (404, "not-found", "product not found")


def test_inactive_products_cannot_be_added(svc, catalog):
    catalog.update(ADMIN, "MUG-1", {"version": 1, "active": False})
    assert status(svc.set_item, ASHA, "MUG-1", {"quantity": 1})[0] == 404


def test_remove_is_idempotent(svc):
    svc.set_item(ASHA, "TEE-1", {"quantity": 1})
    assert svc.remove_item(ASHA, "TEE-1").lines == []
    assert svc.remove_item(ASHA, "TEE-1").lines == []
    assert status(svc.remove_item, ASHA, "NOPE-1")[0] == 404


def test_a_cart_holds_at_most_20_products(svc, catalog):
    for n in range(20):
        catalog.create(ADMIN, {"sku": f"P-{n:03d}", "name": "P", "category": "misc", "pricePaise": 100, "stock": 1})
        svc.set_item(ASHA, f"P-{n:03d}", {"quantity": 1})
    assert status(svc.set_item, ASHA, "TEE-1", {"quantity": 1})[2] == "a cart holds at most 20 different products"
    svc.set_item(ASHA, "P-000", {"quantity": 1})  # changing an existing line is still fine


def test_coupons(svc):
    svc.set_item(ASHA, "MUG-1", {"quantity": 2})  # ₹900
    view = svc.apply_coupon(ASHA, {"code": " save10 "})
    p = view.pricing
    assert view.coupon_code == "SAVE10" and p.coupon_applied
    assert (p.subtotal_paise, p.discount_paise, p.shipping_paise, p.total_paise) == (90_000, 9_000, 4_900, 85_900)
    assert status(svc.apply_coupon, ASHA, {"code": "EXPIRED5"}) == (404, "not-found", "coupon not found")
    assert status(svc.apply_coupon, ASHA, {"code": "NOPE99"})[0] == 404
    assert svc.remove_coupon(ASHA).pricing.discount_paise == 0


def test_a_coupon_whose_minimum_is_not_met_stays_on_the_cart_but_does_not_apply(svc):
    svc.set_item(ASHA, "TEE-1", {"quantity": 1})  # ₹299 < ₹500
    view = svc.apply_coupon(ASHA, {"code": "FLAT100"})
    assert view.coupon_code == "FLAT100" and not view.pricing.coupon_applied and view.pricing.discount_paise == 0
    view = svc.set_item(ASHA, "TEE-1", {"quantity": 2})  # ₹598
    assert view.pricing.coupon_applied and view.pricing.discount_paise == 10_000


def test_prices_are_live_until_checkout(svc, catalog):
    svc.set_item(ASHA, "TEE-1", {"quantity": 1})
    catalog.update(ADMIN, "TEE-1", {"version": 1, "pricePaise": 19_900})
    assert svc.view(ASHA).pricing.subtotal_paise == 19_900
