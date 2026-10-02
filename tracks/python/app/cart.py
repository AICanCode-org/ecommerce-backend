"""The cart: one per signed-in user, stored in the database, priced live with current prices."""

from __future__ import annotations

from .clock import Clock
from .models import CartView, Principal
from .repository import CartRepository, ProductRepository

MAX_LINES = 20


class CartService:
    def __init__(self, carts: CartRepository, products: ProductRepository, clock: Clock):
        self.carts = carts
        self._products = products
        self._clock = clock

    def view(self, principal: Principal) -> CartView:
        # TODO(phase-3 M4): the cart lines + coupon code, priced with price_cart (the coupon may no
        #   longer apply: then couponApplied is false).
        raise NotImplementedError("TODO(phase-3 M4): CartService.view")

    def set_item(self, principal: Principal, sku: str, payload: object) -> CartView:
        # TODO(phase-3 M4): validate quantity; the product must exist and be active (404); quantity
        #   > stock -> 409 insufficient-stock; a NEW line when the cart already has MAX_LINES lines ->
        #   400; upsert the line (it sets the quantity, it does not add) and return the view.
        raise NotImplementedError("TODO(phase-3 M4): CartService.set_item")

    def remove_item(self, principal: Principal, sku: str) -> CartView:
        # TODO(phase-3 M4): unknown sku -> 404; removing a line that is not in the cart is fine
        #   (idempotent); return the view.
        raise NotImplementedError("TODO(phase-3 M4): CartService.remove_item")

    def apply_coupon(self, principal: Principal, payload: object) -> CartView:
        # TODO(phase-3 M4): validate the code; unknown or inactive coupon -> 404 not-found 'coupon';
        #   store it on the cart and return the view.
        raise NotImplementedError("TODO(phase-3 M4): CartService.apply_coupon")

    def remove_coupon(self, principal: Principal) -> CartView:
        # TODO(phase-3 M4): clear the cart's coupon and return the view.
        raise NotImplementedError("TODO(phase-3 M4): CartService.remove_coupon")
