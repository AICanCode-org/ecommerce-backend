// Money is an integer number of paise (1 rupee = 100 paise). Never a float (ADR-0001).
// Pure functions: formatting and cart pricing (subtotal, coupon discount, shipping, total).
// JavaScript numbers are exact integers up to 2^53 (9 * 10^15 paise), far above any cart here.
import type { Coupon, Pricing } from "./models.js";

export const FREE_SHIPPING_THRESHOLD_PAISE = 99_900; // ₹999
export const SHIPPING_FEE_PAISE = 4_900; // ₹49

/** ₹ with Indian digit grouping: 12345678 paise -> '₹1,23,456.78'. Negative: '-₹12.00'. */
export function formatInr(paise: number): string {
  // TODO(phase-3 M1): Indian digit grouping: 12345678 paise -> '₹1,23,456.78' (last 3 digits, then groups of
  //   2); negative -> '-₹12.00'. Integer maths only, never floats.
  throw new Error("TODO(phase-3 M1): formatInr");
}

/** The discount in paise, or null when there is no coupon or it does not apply (minimum not met). */
export function couponDiscount(subtotalPaise: number, coupon: Coupon | null): number | null {
  // TODO(phase-3 M1): null when there is no coupon, it is inactive, or the subtotal is below
  //   min_subtotal_paise. PERCENT: subtotal * value / 100 rounded DOWN, capped at max_discount_paise; FLAT:
  //   value, but never more than the subtotal.
  throw new Error("TODO(phase-3 M1): couponDiscount");
}

/** lines: [unit price in paise, quantity]. Shipping is free from ₹999 after the discount. */
export function priceCart(lines: [number, number][], coupon: Coupon | null): Pricing {
  // TODO(phase-3 M1): subtotal = sum(unit * qty); discount from coupon_discount (couponApplied = it was not
  //   null); shipping SHIPPING_FEE_PAISE unless the cart is empty or subtotal - discount >=
  //   FREE_SHIPPING_THRESHOLD_PAISE; total = subtotal - discount + shipping.
  throw new Error("TODO(phase-3 M1): priceCart");
}
