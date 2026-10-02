// M4: CartService - quantities, stock checks, coupons and live pricing.
import { beforeEach, expect, it } from "vitest";
import { CartService } from "../../src/cart.js";
import { CatalogService } from "../../src/catalog.js";
import { ApiError } from "../../src/errors.js";
import type { Principal } from "../../src/models.js";
import { InMemoryStore } from "../../src/repository.js";
import { FakeClock } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("cart");
const ADMIN: Principal = { userId: 1, role: "ADMIN" };
const ASHA: Principal = { userId: 2, role: "CUSTOMER" };
const RAVI: Principal = { userId: 3, role: "CUSTOMER" };

async function status(promise: Promise<unknown>): Promise<[number, string, string]> {
  try {
    await promise;
  } catch (err) {
    expect(err).toBeInstanceOf(ApiError);
    const e = err as ApiError;
    return [e.status, e.slug, e.detail ?? ""];
  }
  throw new Error("expected an ApiError");
}

describe("cart service", () => {
  let catalog: CatalogService;
  let svc: CartService;
  beforeEach(async () => {
    const store = new InMemoryStore();
    const clock = new FakeClock();
    catalog = new CatalogService(store, clock.fn);
    await catalog.create(ADMIN, {
      sku: "TEE-1",
      name: "Tee",
      category: "apparel",
      pricePaise: 29_900,
      stock: 3,
    });
    await catalog.create(ADMIN, {
      sku: "MUG-1",
      name: "Mug",
      category: "kitchen",
      pricePaise: 45_000,
      stock: 10,
    });
    svc = new CartService(store, store, clock.fn);
  });
  const totals = (p: { subtotalPaise: number; shippingPaise: number; totalPaise: number }) => [
    p.subtotalPaise,
    p.shippingPaise,
    p.totalPaise,
  ];

  it("starts empty", async () => {
    const view = await svc.view(ASHA);
    expect(view.lines).toEqual([]);
    expect(view.couponCode).toBeNull();
    expect(totals(view.pricing)).toEqual([0, 0, 0]);
  });

  it("sets the quantity, it does not add", async () => {
    await svc.setItem(ASHA, "tee-1", { quantity: 2 });
    const view = await svc.setItem(ASHA, "TEE-1", { quantity: 1 });
    expect(view.lines.map((l) => [l.product.sku, l.quantity])).toEqual([["TEE-1", 1]]);
    expect(totals(view.pricing)).toEqual([29_900, 4_900, 34_800]);
  });

  it("keeps one cart per user", async () => {
    await svc.setItem(ASHA, "TEE-1", { quantity: 1 });
    expect((await svc.view(RAVI)).lines).toEqual([]);
  });

  it("checks the quantity against stock", async () => {
    expect(await status(svc.setItem(ASHA, "TEE-1", { quantity: 4 }))).toEqual([
      409,
      "insufficient-stock",
      "only 3 left of TEE-1",
    ]);
    expect((await status(svc.setItem(ASHA, "TEE-1", { quantity: 0 })))[0]).toBe(400);
    expect(await status(svc.setItem(ASHA, "NOPE-1", { quantity: 1 }))).toEqual([
      404,
      "not-found",
      "product not found",
    ]);
  });

  it("refuses inactive products", async () => {
    await catalog.update(ADMIN, "MUG-1", { version: 1, active: false });
    expect((await status(svc.setItem(ASHA, "MUG-1", { quantity: 1 })))[0]).toBe(404);
  });

  it("removes idempotently", async () => {
    await svc.setItem(ASHA, "TEE-1", { quantity: 1 });
    expect((await svc.removeItem(ASHA, "TEE-1")).lines).toEqual([]);
    expect((await svc.removeItem(ASHA, "TEE-1")).lines).toEqual([]);
    expect((await status(svc.removeItem(ASHA, "NOPE-1")))[0]).toBe(404);
  });

  it("holds at most 20 products", async () => {
    for (let n = 0; n < 20; n++) {
      const sku = `P-${String(n).padStart(3, "0")}`;
      await catalog.create(ADMIN, { sku, name: "P", category: "misc", pricePaise: 100, stock: 1 });
      await svc.setItem(ASHA, sku, { quantity: 1 });
    }
    expect((await status(svc.setItem(ASHA, "TEE-1", { quantity: 1 })))[2]).toBe(
      "a cart holds at most 20 different products",
    );
    await svc.setItem(ASHA, "P-000", { quantity: 1 }); // changing an existing line is still fine
  });

  it("applies coupons", async () => {
    await svc.setItem(ASHA, "MUG-1", { quantity: 2 }); // ₹900
    const view = await svc.applyCoupon(ASHA, { code: " save10 " });
    const p = view.pricing;
    expect([view.couponCode, p.couponApplied]).toEqual(["SAVE10", true]);
    expect([p.subtotalPaise, p.discountPaise, p.shippingPaise, p.totalPaise]).toEqual([
      90_000, 9_000, 4_900, 85_900,
    ]);
    expect(await status(svc.applyCoupon(ASHA, { code: "EXPIRED5" }))).toEqual([
      404,
      "not-found",
      "coupon not found",
    ]);
    expect((await status(svc.applyCoupon(ASHA, { code: "NOPE99" })))[0]).toBe(404);
    expect((await svc.removeCoupon(ASHA)).pricing.discountPaise).toBe(0);
  });

  it("keeps a coupon whose minimum is not met, without applying it", async () => {
    await svc.setItem(ASHA, "TEE-1", { quantity: 1 }); // ₹299 < ₹500
    let view = await svc.applyCoupon(ASHA, { code: "FLAT100" });
    expect([view.couponCode, view.pricing.couponApplied, view.pricing.discountPaise]).toEqual([
      "FLAT100",
      false,
      0,
    ]);
    view = await svc.setItem(ASHA, "TEE-1", { quantity: 2 }); // ₹598
    expect([view.pricing.couponApplied, view.pricing.discountPaise]).toEqual([true, 10_000]);
  });

  it("keeps prices live until checkout", async () => {
    await svc.setItem(ASHA, "TEE-1", { quantity: 1 });
    await catalog.update(ADMIN, "TEE-1", { version: 1, pricePaise: 19_900 });
    expect((await svc.view(ASHA)).pricing.subtotalPaise).toBe(19_900);
  });
});
