package org.aicancode.shopflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.aicancode.shopflow.domain.Models.Coupon;
import org.aicancode.shopflow.domain.Models.Pricing;
import org.junit.jupiter.api.Test;

/** M1: money is integer paise. Formatting and cart pricing are pure functions. */
class MoneyTest {

    private static final Coupon SAVE10 = new Coupon("SAVE10", "PERCENT", 10, 0, 20_000L, true);
    private static final Coupon FLAT100 = new Coupon("FLAT100", "FLAT", 10_000, 50_000, null, true);

    private static Pricing pricing(long subtotal, long discount, long shipping, long total, boolean applied) {
        return new Pricing(subtotal, discount, shipping, total, applied);
    }

    private static List<long[]> lines(long... priceQuantityPairs) {
        List<long[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < priceQuantityPairs.length; i += 2) {
            out.add(new long[] {priceQuantityPairs[i], priceQuantityPairs[i + 1]});
        }
        return out;
    }

    @Test
    void formatsRupeesWithIndianDigitGrouping() {
        assertThat(Money.formatInr(0)).isEqualTo("₹0.00");
        assertThat(Money.formatInr(5)).isEqualTo("₹0.05");
        assertThat(Money.formatInr(99_900)).isEqualTo("₹999.00");
        assertThat(Money.formatInr(100_000)).isEqualTo("₹1,000.00");
        assertThat(Money.formatInr(12_345_678)).isEqualTo("₹1,23,456.78");
        assertThat(Money.formatInr(100_000_000)).isEqualTo("₹10,00,000.00");
        assertThat(Money.formatInr(123_456_789_012L)).isEqualTo("₹1,23,45,67,890.12");
        assertThat(Money.formatInr(-1_200)).isEqualTo("-₹12.00");
    }

    @Test
    void roundsAPercentCouponDownAndCapsIt() {
        assertThat(Money.couponDiscount(99_999, SAVE10)).isEqualTo(9_999L); // 9 999.9 paise -> 9 999
        assertThat(Money.couponDiscount(500_000, SAVE10)).isEqualTo(20_000L); // 10 % would be ₹500, the cap is ₹200
        assertThat(Money.couponDiscount(1_001, new Coupon("BIG", "PERCENT", 50, 0, null, true))).isEqualTo(500L);
    }

    @Test
    void needsTheMinimumForAFlatCouponAndNeverExceedsTheSubtotal() {
        assertThat(Money.couponDiscount(49_999, FLAT100)).isNull();
        assertThat(Money.couponDiscount(50_000, FLAT100)).isEqualTo(10_000L);
        assertThat(Money.couponDiscount(3_000, new Coupon("TINY", "FLAT", 10_000, 0, null, true))).isEqualTo(3_000L);
    }

    @Test
    void ignoresAMissingOrInactiveCoupon() {
        assertThat(Money.couponDiscount(10_000, null)).isNull();
        assertThat(Money.couponDiscount(10_000, new Coupon("OFF", "PERCENT", 5, 0, null, false))).isNull();
    }

    @Test
    void appliesTheShippingRules() {
        assertThat(Money.priceCart(List.of(), null)).isEqualTo(pricing(0, 0, 0, 0, false));
        assertThat(Money.priceCart(lines(49_900, 1), null)).isEqualTo(pricing(49_900, 0, 4_900, 54_800, false));
        assertThat(Money.priceCart(lines(49_950, 2), null)).isEqualTo(pricing(99_900, 0, 0, 99_900, false)); // ₹999: free
        // the threshold is checked AFTER the discount
        assertThat(Money.priceCart(lines(100_000, 1), SAVE10)).isEqualTo(pricing(100_000, 10_000, 4_900, 94_900, true));
    }

    @Test
    void reportsACouponWhoseMinimumIsNotMet() {
        assertThat(Money.priceCart(lines(20_000, 2), FLAT100)).isEqualTo(pricing(40_000, 0, 4_900, 44_900, false));
        assertThat(Money.priceCart(lines(30_000, 2), FLAT100)).isEqualTo(pricing(60_000, 10_000, 4_900, 54_900, true));
    }
}
