package org.aicancode.shopflow.domain;

import java.util.List;
import org.aicancode.shopflow.domain.Models.Coupon;
import org.aicancode.shopflow.domain.Models.Pricing;

/**
 * Money is an integer number of paise (1 rupee = 100 paise). Never a double (ADR-0001).
 * Pure functions: formatting and cart pricing (subtotal, coupon discount, shipping, total).
 */
public final class Money {

    public static final long FREE_SHIPPING_THRESHOLD_PAISE = 99_900; // ₹999
    public static final long SHIPPING_FEE_PAISE = 4_900; // ₹49

    private Money() {
    }

    /** ₹ with Indian digit grouping: 12345678 paise -> "₹1,23,456.78". Negative: "-₹12.00". */
    public static String formatInr(long paise) {
        // TODO(phase-3 M1): Indian digit grouping: 12345678 paise -> '₹1,23,456.78' (last 3 digits, then
        //   groups of 2); negative -> '-₹12.00'. Integer maths only, never floats.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Money.formatInr");
    }

    /** The discount in paise, or null when there is no coupon or it does not apply (minimum not met). */
    public static Long couponDiscount(long subtotalPaise, Coupon coupon) {
        // TODO(phase-3 M1): null when there is no coupon, it is inactive, or the subtotal is below
        //   min_subtotal_paise. PERCENT: subtotal * value / 100 rounded DOWN, capped at max_discount_paise;
        //   FLAT: value, but never more than the subtotal.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Money.couponDiscount");
    }

    /** lines: {unit price in paise, quantity}. Shipping is free from ₹999 after the discount. */
    public static Pricing priceCart(List<long[]> lines, Coupon coupon) {
        // TODO(phase-3 M1): subtotal = sum(unit * qty); discount from coupon_discount (couponApplied = it was
        //   not null); shipping SHIPPING_FEE_PAISE unless the cart is empty or subtotal - discount >=
        //   FREE_SHIPPING_THRESHOLD_PAISE; total = subtotal - discount + shipping.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Money.priceCart");
    }
}
