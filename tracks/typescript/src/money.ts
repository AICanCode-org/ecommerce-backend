// Money is an integer number of paise (1 rupee = 100 paise). Never a float (ADR-0001).
// Pure functions: formatting and cart pricing (subtotal, coupon discount, shipping, total).
// JavaScript numbers are exact integers up to 2^53 (9 * 10^15 paise), far above any cart here.
import type { Coupon, Pricing } from "./models.js";

export const FREE_SHIPPING_THRESHOLD_PAISE = 99_900; // ₹999
export const SHIPPING_FEE_PAISE = 4_900; // ₹49

/** ₹ with Indian digit grouping: 12345678 paise -> '₹1,23,456.78'. Negative: '-₹12.00'. */
export function formatInr(paise: number): string {
  const sign = paise < 0 ? "-" : "";
  const abs = Math.abs(paise);
  const rest = abs % 100;
  let digits = String((abs - rest) / 100);
  if (digits.length > 3) {
    let head = digits.slice(0, -3);
    const groups: string[] = [];
    while (head.length > 2) {
      groups.unshift(head.slice(-2));
      head = head.slice(0, -2);
    }
    if (head) groups.unshift(head);
    digits = [...groups, digits.slice(-3)].join(",");
  }
  return `${sign}₹${digits}.${String(rest).padStart(2, "0")}`;
}

/** The discount in paise, or null when there is no coupon or it does not apply (minimum not met). */
export function couponDiscount(subtotalPaise: number, coupon: Coupon | null): number | null {
  if (!coupon || !coupon.active || subtotalPaise < coupon.minSubtotalPaise) return null;
  if (coupon.kind === "PERCENT") {
    let discount = Math.floor((subtotalPaise * coupon.value) / 100); // rounds down, by less than one paisa
    if (coupon.maxDiscountPaise !== null) discount = Math.min(discount, coupon.maxDiscountPaise);
    return discount;
  }
  return Math.min(coupon.value, subtotalPaise);
}

/** lines: [unit price in paise, quantity]. Shipping is free from ₹999 after the discount. */
export function priceCart(lines: [number, number][], coupon: Coupon | null): Pricing {
  const subtotal = lines.reduce((sum, [unit, qty]) => sum + unit * qty, 0);
  const found = couponDiscount(subtotal, coupon);
  const discount = found ?? 0;
  const shipping =
    subtotal === 0 || subtotal - discount >= FREE_SHIPPING_THRESHOLD_PAISE ? 0 : SHIPPING_FEE_PAISE;
  return {
    subtotalPaise: subtotal,
    discountPaise: discount,
    shippingPaise: shipping,
    totalPaise: subtotal - discount + shipping,
    couponApplied: found !== null,
  };
}
