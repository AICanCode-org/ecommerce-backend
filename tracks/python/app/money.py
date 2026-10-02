"""Money is an integer number of paise (1 rupee = 100 paise). Never a float (ADR-0001).

Pure functions: formatting and cart pricing (subtotal, coupon discount, shipping, total).
"""

from __future__ import annotations

from .models import Coupon, Pricing

FREE_SHIPPING_THRESHOLD_PAISE = 99_900  # ₹999
SHIPPING_FEE_PAISE = 4_900  # ₹49


def format_inr(paise: int) -> str:
    """₹ with Indian digit grouping: 12345678 paise -> '₹1,23,456.78'. Negative: '-₹12.00'."""
    # TODO(phase-3 M1): Indian digit grouping: 12345678 paise -> '₹1,23,456.78' (last 3 digits, then
    #   groups of 2); negative -> '-₹12.00'. Integer maths only, never floats.
    raise NotImplementedError("TODO(phase-3 M1): format_inr")


def coupon_discount(subtotal_paise: int, coupon: Coupon | None) -> int | None:
    """The discount in paise, or None when there is no coupon or it does not apply (minimum not met)."""
    # TODO(phase-3 M1): null when there is no coupon, it is inactive, or the subtotal is below
    #   min_subtotal_paise. PERCENT: subtotal * value / 100 rounded DOWN, capped at
    #   max_discount_paise; FLAT: value, but never more than the subtotal.
    raise NotImplementedError("TODO(phase-3 M1): coupon_discount")


def price_cart(lines: list[tuple[int, int]], coupon: Coupon | None) -> Pricing:
    """lines: (unit price in paise, quantity). Shipping is free from ₹999 after the discount."""
    # TODO(phase-3 M1): subtotal = sum(unit * qty); discount from coupon_discount (couponApplied =
    #   it was not null); shipping SHIPPING_FEE_PAISE unless the cart is empty or subtotal - discount
    #   >= FREE_SHIPPING_THRESHOLD_PAISE; total = subtotal - discount + shipping.
    raise NotImplementedError("TODO(phase-3 M1): price_cart")
