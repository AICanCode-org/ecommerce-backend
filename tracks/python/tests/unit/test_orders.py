"""M5: OrderService - idempotent checkout, all-or-nothing stock, the order state machine."""

import pytest

from app.cart import CartService
from app.catalog import CatalogService
from app.errors import ApiError
from app.models import Principal
from app.orders import OrderService, next_status
from tests.conftest import PaymentIds

ADMIN = Principal(1, "ADMIN")
ASHA = Principal(2, "CUSTOMER")
RAVI = Principal(3, "CUSTOMER")
KEY = "order-key-0001"


@pytest.fixture
def catalog(store, clock) -> CatalogService:
    c = CatalogService(store, clock)
    c.create(ADMIN, {"sku": "TEE-1", "name": "Tee", "category": "apparel", "pricePaise": 29_900, "stock": 3})
    c.create(ADMIN, {"sku": "MUG-1", "name": "Mug", "category": "kitchen", "pricePaise": 45_000, "stock": 1})
    return c


@pytest.fixture
def cart(store, clock, catalog) -> CartService:
    return CartService(store, store, clock)


@pytest.fixture
def svc(store, clock) -> OrderService:
    return OrderService(store, store, clock, PaymentIds())


def status(fn, *args) -> tuple[int, str, str | None]:
    with pytest.raises(ApiError) as e:
        fn(*args)
    return e.value.status, e.value.slug, e.value.detail


def stock(catalog, sku):
    return catalog.get(ADMIN, sku).stock


def test_checkout_snapshots_prices_reserves_stock_and_empties_the_cart(svc, cart, catalog, store):
    cart.set_item(ASHA, "TEE-1", {"quantity": 2})
    cart.set_item(ASHA, "MUG-1", {"quantity": 1})
    cart.apply_coupon(ASHA, {"code": "SAVE10"})
    order, replayed = svc.checkout(ASHA, KEY)
    assert not replayed and order.status == "PENDING_PAYMENT" and order.user_id == 2
    assert [(x.sku, x.unit_price_paise, x.quantity) for x in order.lines] == [
        ("TEE-1", 29_900, 2),
        ("MUG-1", 45_000, 1),
    ]
    assert (order.subtotal_paise, order.discount_paise, order.shipping_paise, order.total_paise) == (
        104_800,
        10_480,
        4_900,
        99_220,
    )
    assert order.coupon_code == "SAVE10"
    assert (order.payment.id, order.payment.status, order.payment.amount_paise) == ("pay_test_1", "PENDING", 99_220)
    assert stock(catalog, "TEE-1") == 1 and stock(catalog, "MUG-1") == 0
    assert cart.view(ASHA).lines == [] and cart.view(ASHA).coupon_code is None
    assert [(e.type, e.payload) for e in store.events] == [
        ("order.placed", {"orderId": order.id, "userId": 2, "status": "PENDING_PAYMENT", "totalPaise": 99_220})
    ]
    catalog.update(ADMIN, "TEE-1", {"version": 1, "pricePaise": 1})
    assert svc.get(ASHA, order.id).lines[0].unit_price_paise == 29_900  # the order never changes


def test_same_idempotency_key_returns_the_same_order(svc, cart, catalog):
    cart.set_item(ASHA, "TEE-1", {"quantity": 1})
    first, _ = svc.checkout(ASHA, KEY)
    again, replayed = svc.checkout(ASHA, KEY)
    assert replayed and again.id == first.id and stock(catalog, "TEE-1") == 2
    # the key is per customer: Ravi's identical key is a different checkout
    assert status(svc.checkout, RAVI, KEY)[:2] == (409, "cart-empty")


def test_checkout_needs_a_key_and_a_cart(svc):
    assert status(svc.checkout, ASHA, None) == (400, "validation-error", "Idempotency-Key header is required")
    assert status(svc.checkout, ASHA, KEY) == (409, "cart-empty", "add something to your cart before checking out")


def test_overselling_is_impossible_and_nothing_is_half_written(svc, cart, catalog, store):
    cart.set_item(ASHA, "TEE-1", {"quantity": 1})
    cart.set_item(ASHA, "MUG-1", {"quantity": 1})
    cart.set_item(RAVI, "MUG-1", {"quantity": 1})
    svc.checkout(RAVI, "ravi-key-0001")  # Ravi buys the last mug first
    assert status(svc.checkout, ASHA, KEY) == (409, "insufficient-stock", "not enough stock for MUG-1")
    assert stock(catalog, "TEE-1") == 3  # the tee was NOT decremented: all or nothing
    assert len(cart.view(ASHA).lines) == 2 and len(store.orders) == 1


def test_a_deactivated_product_blocks_checkout(svc, cart, catalog):
    cart.set_item(ASHA, "TEE-1", {"quantity": 1})
    catalog.update(ADMIN, "TEE-1", {"version": 1, "active": False})
    assert status(svc.checkout, ASHA, KEY) == (409, "insufficient-stock", "TEE-1 is no longer available")


def test_other_peoples_orders_are_404(svc, cart):
    cart.set_item(ASHA, "TEE-1", {"quantity": 1})
    order, _ = svc.checkout(ASHA, KEY)
    assert svc.get(ADMIN, order.id).id == order.id
    assert status(svc.get, RAVI, order.id) == (404, "not-found", "order not found")
    assert status(svc.get, ASHA, 999)[0] == 404
    assert [o.id for o in svc.mine(ASHA, None)] == [order.id] and svc.mine(RAVI, None) == []


def test_cancel_restocks_and_records_an_event(svc, cart, catalog, store):
    cart.set_item(ASHA, "TEE-1", {"quantity": 2})
    order, _ = svc.checkout(ASHA, KEY)
    cancelled = svc.cancel(ASHA, order.id)
    assert cancelled.status == "CANCELLED" and stock(catalog, "TEE-1") == 3
    assert [e.type for e in store.events] == ["order.placed", "order.cancelled"]
    assert status(svc.cancel, ASHA, order.id) == (409, "invalid-transition", "cannot cancel an order that is CANCELLED")


def test_admin_ships_and_delivers_paid_orders(svc, cart, store):
    cart.set_item(ASHA, "TEE-1", {"quantity": 1})
    order, _ = svc.checkout(ASHA, KEY)
    assert status(svc.admin_set_status, ADMIN, order.id, {"status": "SHIPPED"})[2] == (
        "cannot ship an order that is PENDING_PAYMENT"
    )
    store.orders[order.id].status = "PAID"
    assert status(svc.admin_set_status, ASHA, order.id, {"status": "SHIPPED"})[0] == 403
    assert svc.admin_set_status(ADMIN, order.id, {"status": "SHIPPED"}).status == "SHIPPED"
    assert status(svc.cancel, ASHA, order.id)[2] == "cannot cancel an order that is SHIPPED"
    assert svc.admin_set_status(ADMIN, order.id, {"status": "DELIVERED"}).status == "DELIVERED"
    assert [e.type for e in store.events][-2:] == ["order.shipped", "order.delivered"]


def test_cancelling_a_paid_order_requests_a_refund(svc, cart, store):
    cart.set_item(ASHA, "TEE-1", {"quantity": 1})
    order, _ = svc.checkout(ASHA, KEY)
    store.orders[order.id].status = "PAID"
    svc.cancel(ASHA, order.id)
    assert [e.type for e in store.events] == ["order.placed", "order.cancelled", "refund.requested"]


def test_admin_list_filters_by_status(svc, cart):
    cart.set_item(ASHA, "TEE-1", {"quantity": 1})
    a, _ = svc.checkout(ASHA, KEY)
    cart.set_item(RAVI, "TEE-1", {"quantity": 1})
    b, _ = svc.checkout(RAVI, KEY)
    svc.cancel(RAVI, b.id)
    assert [o.id for o in svc.admin_list(ADMIN, None, None)] == [b.id, a.id]  # newest first
    assert [o.id for o in svc.admin_list(ADMIN, "CANCELLED", None)] == [b.id]
    assert status(svc.admin_list, ASHA, None, None)[0] == 403


def test_a_concurrent_change_is_a_409(svc, cart, store):
    cart.set_item(ASHA, "TEE-1", {"quantity": 1})
    order, _ = svc.checkout(ASHA, KEY)
    stale = svc.get(ASHA, order.id)
    store.orders[order.id].status = "PAID"  # a webhook won the race
    with pytest.raises(ApiError) as e:
        svc._apply(stale, "cancel")
    assert e.value.status == 409 and "changed while we were updating it" in e.value.detail


def test_state_machine_table():
    assert next_status("PENDING_PAYMENT", "pay") == "PAID"
    assert next_status("PENDING_PAYMENT", "fail") == "CANCELLED"
    assert next_status("PAID", "ship") == "SHIPPED"
    assert next_status("SHIPPED", "deliver") == "DELIVERED"
    for current, action in [("DELIVERED", "cancel"), ("CANCELLED", "pay"), ("PAID", "pay"), ("PAID", "deliver")]:
        with pytest.raises(ApiError):
            next_status(current, action)
