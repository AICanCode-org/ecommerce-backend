"""M1: money is integer paise. Formatting and cart pricing are pure functions."""

from app.models import Coupon, Pricing
from app.money import coupon_discount, format_inr, price_cart

SAVE10 = Coupon("SAVE10", "PERCENT", 10, 0, 20_000, True)
FLAT100 = Coupon("FLAT100", "FLAT", 10_000, 50_000, None, True)


def test_format_inr_uses_indian_digit_grouping():
    assert format_inr(0) == "₹0.00"
    assert format_inr(5) == "₹0.05"
    assert format_inr(99_900) == "₹999.00"
    assert format_inr(100_000) == "₹1,000.00"
    assert format_inr(12_345_678) == "₹1,23,456.78"
    assert format_inr(100_000_000) == "₹10,00,000.00"
    assert format_inr(123_456_789_012) == "₹1,23,45,67,890.12"
    assert format_inr(-1_200) == "-₹12.00"


def test_percent_coupon_rounds_down_and_is_capped():
    assert coupon_discount(99_999, SAVE10) == 9_999  # 9 999.9 paise -> 9 999
    assert coupon_discount(500_000, SAVE10) == 20_000  # 10 % would be ₹500, the cap is ₹200
    uncapped = Coupon("BIG", "PERCENT", 50, 0, None, True)
    assert coupon_discount(1_001, uncapped) == 500


def test_flat_coupon_needs_the_minimum_and_never_exceeds_the_subtotal():
    assert coupon_discount(49_999, FLAT100) is None
    assert coupon_discount(50_000, FLAT100) == 10_000
    tiny = Coupon("TINY", "FLAT", 10_000, 0, None, True)
    assert coupon_discount(3_000, tiny) == 3_000


def test_no_or_inactive_coupon():
    assert coupon_discount(10_000, None) is None
    assert coupon_discount(10_000, Coupon("OFF", "PERCENT", 5, 0, None, False)) is None


def test_price_cart_shipping_rules():
    assert price_cart([], None) == Pricing(0, 0, 0, 0, False)
    assert price_cart([(49_900, 1)], None) == Pricing(49_900, 0, 4_900, 54_800, False)
    assert price_cart([(49_950, 2)], None) == Pricing(99_900, 0, 0, 99_900, False)  # exactly ₹999: free
    # the threshold is checked AFTER the discount
    assert price_cart([(100_000, 1)], SAVE10) == Pricing(100_000, 10_000, 4_900, 94_900, True)


def test_price_cart_coupon_not_met_is_reported():
    assert price_cart([(20_000, 2)], FLAT100) == Pricing(40_000, 0, 4_900, 44_900, False)
    assert price_cart([(30_000, 2)], FLAT100) == Pricing(60_000, 10_000, 4_900, 54_900, True)
