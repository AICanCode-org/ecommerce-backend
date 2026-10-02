package org.aicancode.shopflow.domain;

import java.util.ArrayList;
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
        String sign = paise < 0 ? "-" : "";
        long abs = Math.abs(paise);
        String digits = Long.toString(abs / 100);
        if (digits.length() > 3) {
            String head = digits.substring(0, digits.length() - 3);
            String tail = digits.substring(digits.length() - 3);
            List<String> groups = new ArrayList<>();
            while (head.length() > 2) {
                groups.add(0, head.substring(head.length() - 2));
                head = head.substring(0, head.length() - 2);
            }
            if (!head.isEmpty()) {
                groups.add(0, head);
            }
            groups.add(tail);
            digits = String.join(",", groups);
        }
        return String.format("%s₹%s.%02d", sign, digits, abs % 100);
    }

    /** The discount in paise, or null when there is no coupon or it does not apply (minimum not met). */
    public static Long couponDiscount(long subtotalPaise, Coupon coupon) {
        if (coupon == null || !coupon.active() || subtotalPaise < coupon.minSubtotalPaise()) {
            return null;
        }
        if ("PERCENT".equals(coupon.kind())) {
            long discount = subtotalPaise * coupon.value() / 100; // integer maths: rounds down, by less than one paisa
            if (coupon.maxDiscountPaise() != null) {
                discount = Math.min(discount, coupon.maxDiscountPaise());
            }
            return discount;
        }
        return Math.min(coupon.value(), subtotalPaise);
    }

    /** lines: {unit price in paise, quantity}. Shipping is free from ₹999 after the discount. */
    public static Pricing priceCart(List<long[]> lines, Coupon coupon) {
        long subtotal = 0;
        for (long[] line : lines) {
            subtotal += line[0] * line[1];
        }
        Long found = couponDiscount(subtotal, coupon);
        long discount = found == null ? 0 : found;
        long shipping = subtotal == 0 || subtotal - discount >= FREE_SHIPPING_THRESHOLD_PAISE ? 0 : SHIPPING_FEE_PAISE;
        return new Pricing(subtotal, discount, shipping, subtotal - discount + shipping, found != null);
    }
}
