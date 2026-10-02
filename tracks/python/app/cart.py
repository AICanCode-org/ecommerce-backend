"""The cart: one per signed-in user, stored in the database, priced live with current prices."""

from __future__ import annotations

from . import validation as v
from .clock import Clock
from .errors import insufficient_stock, not_found, validation_error
from .models import CartView, Principal
from .money import price_cart
from .repository import CartRepository, ProductRepository

MAX_LINES = 20


class CartService:
    def __init__(self, carts: CartRepository, products: ProductRepository, clock: Clock):
        self.carts = carts
        self._products = products
        self._clock = clock

    def view(self, principal: Principal) -> CartView:
        state = self.carts.cart(principal.user_id)
        coupon = self.carts.find_coupon(state.coupon_code) if state.coupon_code else None
        pricing = price_cart([(line.product.price_paise, line.quantity) for line in state.lines], coupon)
        return CartView(state.lines, state.coupon_code, pricing)

    def set_item(self, principal: Principal, sku: str, payload: object) -> CartView:
        qty = v.quantity(v.require_object(payload).get("quantity"))
        product = self._products.find_product(sku.strip().upper())
        if product is None or not product.active:
            raise not_found("product")
        if qty > product.stock:
            raise insufficient_stock(f"only {product.stock} left of {product.sku}")
        lines = self.carts.cart(principal.user_id).lines
        if len(lines) >= MAX_LINES and all(line.product.id != product.id for line in lines):
            raise validation_error(f"a cart holds at most {MAX_LINES} different products")
        self.carts.set_cart_item(principal.user_id, product.id, qty, self._clock())
        return self.view(principal)

    def remove_item(self, principal: Principal, sku: str) -> CartView:
        product = self._products.find_product(sku.strip().upper())
        if product is None:
            raise not_found("product")
        self.carts.remove_cart_item(principal.user_id, product.id)  # idempotent: removing twice is fine
        return self.view(principal)

    def apply_coupon(self, principal: Principal, payload: object) -> CartView:
        code = v.coupon_code(v.require_object(payload).get("code"))
        coupon = self.carts.find_coupon(code)
        if coupon is None or not coupon.active:
            raise not_found("coupon")
        self.carts.set_coupon(principal.user_id, code, self._clock())
        return self.view(principal)

    def remove_coupon(self, principal: Principal) -> CartView:
        self.carts.set_coupon(principal.user_id, None, self._clock())
        return self.view(principal)
