// M4: the cart endpoints over HTTP.
import { beforeEach, expect, it } from "vitest";
import { Api } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("api-cart");

describe("cart endpoints", () => {
  let api: Api;
  beforeEach(async () => {
    api = await Api.create();
  });

  it("needs a token", async () => {
    expect((await api.request("GET", "/api/v1/cart")).statusCode).toBe(401);
    expect((await api.request("PUT", "/api/v1/cart/items/X-1", { quantity: 1 })).statusCode).toBe(401);
  });

  it("returns the cart JSON", async () => {
    const session = await api.customer();
    await api.product({ sku: "MUG-1", name: "Mug", pricePaise: 45_000, stock: 2 });
    expect(await api.add(session, "mug-1", 2)).toEqual({
      items: [
        {
          sku: "MUG-1",
          name: "Mug",
          unitPricePaise: 45_000,
          quantity: 2,
          lineTotalPaise: 90_000,
          available: true,
        },
      ],
      couponCode: null,
      couponApplied: false,
      subtotalPaise: 90_000,
      discountPaise: 0,
      shippingPaise: 4_900,
      totalPaise: 94_900,
      total: "₹949.00",
    });
  });

  it("marks lines unavailable when stock runs out", async () => {
    const session = await api.customer();
    await api.product({ sku: "MUG-1", stock: 2 });
    await api.add(session, "MUG-1", 2);
    await api.request("POST", "/api/v1/products/MUG-1/stock", { delta: -1 }, await api.admin());
    expect((await api.request("GET", "/api/v1/cart", undefined, session)).json().items[0].available).toBe(
      false,
    );
  });

  it("applies and removes coupons", async () => {
    const session = await api.customer();
    await api.product({ sku: "MUG-1", pricePaise: 60_000 });
    await api.add(session, "MUG-1");
    const r = await api.request("PUT", "/api/v1/cart/coupon", { code: "flat100" }, session);
    expect(r.statusCode).toBe(200);
    expect([r.json().discountPaise, r.json().couponCode]).toEqual([10_000, "FLAT100"]);
    const bad = await api.request("PUT", "/api/v1/cart/coupon", { code: "NOPE99" }, session);
    expect(bad.statusCode).toBe(404);
    expect(bad.json().detail).toBe("coupon not found");
    expect(
      (await api.request("DELETE", "/api/v1/cart/coupon", undefined, session)).json().couponCode,
    ).toBeNull();
  });

  it("deletes an item", async () => {
    const session = await api.customer();
    await api.product({ sku: "MUG-1" });
    await api.add(session, "MUG-1");
    const r = await api.request("DELETE", "/api/v1/cart/items/MUG-1", undefined, session);
    expect(r.statusCode).toBe(200);
    expect(r.json().items).toEqual([]);
  });
});
