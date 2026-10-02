// M1: money is integer paise. Formatting and cart pricing are pure functions.
import { expect, it } from "vitest";
import type { Coupon } from "../../src/models.js";
import { couponDiscount, formatInr, priceCart } from "../../src/money.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("money");
const coupon = (
  code: string,
  kind: "PERCENT" | "FLAT",
  value: number,
  min: number,
  max: number | null,
  active = true,
): Coupon => ({
  code,
  kind,
  value,
  minSubtotalPaise: min,
  maxDiscountPaise: max,
  active,
});
const SAVE10 = coupon("SAVE10", "PERCENT", 10, 0, 20_000);
const FLAT100 = coupon("FLAT100", "FLAT", 10_000, 50_000, null);
const pricing = (subtotal: number, discount: number, shipping: number, total: number, applied: boolean) => ({
  subtotalPaise: subtotal,
  discountPaise: discount,
  shippingPaise: shipping,
  totalPaise: total,
  couponApplied: applied,
});

describe("money", () => {
  it("formats rupees with Indian digit grouping", () => {
    expect(formatInr(0)).toBe("₹0.00");
    expect(formatInr(5)).toBe("₹0.05");
    expect(formatInr(99_900)).toBe("₹999.00");
    expect(formatInr(100_000)).toBe("₹1,000.00");
    expect(formatInr(12_345_678)).toBe("₹1,23,456.78");
    expect(formatInr(100_000_000)).toBe("₹10,00,000.00");
    expect(formatInr(123_456_789_012)).toBe("₹1,23,45,67,890.12");
    expect(formatInr(-1_200)).toBe("-₹12.00");
  });

  it("rounds a percent coupon down and caps it", () => {
    expect(couponDiscount(99_999, SAVE10)).toBe(9_999); // 9 999.9 paise -> 9 999
    expect(couponDiscount(500_000, SAVE10)).toBe(20_000); // 10 % would be ₹500, the cap is ₹200
    expect(couponDiscount(1_001, coupon("BIG", "PERCENT", 50, 0, null))).toBe(500);
  });

  it("needs the minimum for a flat coupon and never exceeds the subtotal", () => {
    expect(couponDiscount(49_999, FLAT100)).toBeNull();
    expect(couponDiscount(50_000, FLAT100)).toBe(10_000);
    expect(couponDiscount(3_000, coupon("TINY", "FLAT", 10_000, 0, null))).toBe(3_000);
  });

  it("ignores a missing or inactive coupon", () => {
    expect(couponDiscount(10_000, null)).toBeNull();
    expect(couponDiscount(10_000, coupon("OFF", "PERCENT", 5, 0, null, false))).toBeNull();
  });

  it("applies the shipping rules", () => {
    expect(priceCart([], null)).toEqual(pricing(0, 0, 0, 0, false));
    expect(priceCart([[49_900, 1]], null)).toEqual(pricing(49_900, 0, 4_900, 54_800, false));
    expect(priceCart([[49_950, 2]], null)).toEqual(pricing(99_900, 0, 0, 99_900, false)); // exactly ₹999: free
    // the threshold is checked AFTER the discount
    expect(priceCart([[100_000, 1]], SAVE10)).toEqual(pricing(100_000, 10_000, 4_900, 94_900, true));
  });

  it("reports a coupon whose minimum is not met", () => {
    expect(priceCart([[20_000, 2]], FLAT100)).toEqual(pricing(40_000, 0, 4_900, 44_900, false));
    expect(priceCart([[30_000, 2]], FLAT100)).toEqual(pricing(60_000, 10_000, 4_900, 54_900, true));
  });
});
