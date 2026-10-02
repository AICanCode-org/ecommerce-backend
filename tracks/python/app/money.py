"""Money is an integer number of paise (1 rupee = 100 paise). Never a float (ADR-0001).

Pure functions: formatting and cart pricing (subtotal, coupon discount, shipping, total).
"""

from __future__ import annotations

from .models import Coupon, Pricing

FREE_SHIPPING_THRESHOLD_PAISE = 99_900  # ₹999
SHIPPING_FEE_PAISE = 4_900  # ₹49


def format_inr(paise: int) -> str:
    """₹ with Indian digit grouping: 12345678 paise -> '₹1,23,456.78'. Negative: '-₹12.00'."""
    sign = "-" if paise < 0 else ""
    rupees, rest = divmod(abs(paise), 100)
    digits = str(rupees)
    if len(digits) > 3:
        head, tail = digits[:-3], digits[-3:]
        groups = []
        while len(head) > 2:
            groups.insert(0, head[-2:])
            head = head[:-2]
        if head:
            groups.insert(0, head)
        digits = ",".join([*groups, tail])
    return f"{sign}₹{digits}.{rest:02d}"


def coupon_discount(subtotal_paise: int, coupon: Coupon | None) -> int | None:
    """The discount in paise, or None when there is no coupon or it does not apply (minimum not met)."""
    if coupon is None or not coupon.active or subtotal_paise < coupon.min_subtotal_paise:
        return None
    if coupon.kind == "PERCENT":
        discount = subtotal_paise * coupon.value // 100  # integer maths: rounds down, by less than one paisa
        if coupon.max_discount_paise is not None:
            discount = min(discount, coupon.max_discount_paise)
        return discount
    return min(coupon.value, subtotal_paise)


def price_cart(lines: list[tuple[int, int]], coupon: Coupon | None) -> Pricing:
    """lines: (unit price in paise, quantity). Shipping is free from ₹999 after the discount."""
    subtotal = sum(unit * quantity for unit, quantity in lines)
    discount = coupon_discount(subtotal, coupon)
    applied = discount is not None
    discount = discount or 0
    if subtotal == 0 or subtotal - discount >= FREE_SHIPPING_THRESHOLD_PAISE:
        shipping = 0
    else:
        shipping = SHIPPING_FEE_PAISE
    return Pricing(subtotal, discount, shipping, subtotal - discount + shipping, applied)
