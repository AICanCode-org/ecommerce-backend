// M5: OrderService - idempotent checkout, all-or-nothing stock, the order state machine.
import { beforeEach, expect, it } from "vitest";
import { CartService } from "../../src/cart.js";
import { CatalogService } from "../../src/catalog.js";
import { ApiError } from "../../src/errors.js";
import type { Principal } from "../../src/models.js";
import { nextStatus, OrderService } from "../../src/orders.js";
import { InMemoryStore } from "../../src/repository.js";
import { FakeClock, paymentIds } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("orders");
const ADMIN: Principal = { userId: 1, role: "ADMIN" };
const ASHA: Principal = { userId: 2, role: "CUSTOMER" };
const RAVI: Principal = { userId: 3, role: "CUSTOMER" };
const KEY = "order-key-0001";

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

describe("order service", () => {
  let store: InMemoryStore;
  let catalog: CatalogService;
  let cart: CartService;
  let svc: OrderService;
  beforeEach(async () => {
    store = new InMemoryStore();
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
      stock: 1,
    });
    cart = new CartService(store, store, clock.fn);
    svc = new OrderService(store, store, clock.fn, paymentIds());
  });
  const stock = async (sku: string) => (await catalog.get(ADMIN, sku)).stock;
  const types = () => store.events.map((e) => e.type);

  it("snapshots prices, reserves stock and empties the cart on checkout", async () => {
    await cart.setItem(ASHA, "TEE-1", { quantity: 2 });
    await cart.setItem(ASHA, "MUG-1", { quantity: 1 });
    await cart.applyCoupon(ASHA, { code: "SAVE10" });
    const { order, replayed } = await svc.checkout(ASHA, KEY);
    expect([replayed, order.status, order.userId]).toEqual([false, "PENDING_PAYMENT", 2]);
    expect(order.lines.map((l) => [l.sku, l.unitPricePaise, l.quantity])).toEqual([
      ["TEE-1", 29_900, 2],
      ["MUG-1", 45_000, 1],
    ]);
    expect([order.subtotalPaise, order.discountPaise, order.shippingPaise, order.totalPaise]).toEqual([
      104_800, 10_480, 4_900, 99_220,
    ]);
    expect(order.couponCode).toBe("SAVE10");
    expect([order.payment.id, order.payment.status, order.payment.amountPaise]).toEqual([
      "pay_test_1",
      "PENDING",
      99_220,
    ]);
    expect([await stock("TEE-1"), await stock("MUG-1")]).toEqual([1, 0]);
    const view = await cart.view(ASHA);
    expect([view.lines, view.couponCode]).toEqual([[], null]);
    expect(store.events.map((e) => [e.type, e.payload])).toEqual([
      ["order.placed", { orderId: order.id, userId: 2, status: "PENDING_PAYMENT", totalPaise: 99_220 }],
    ]);
    await catalog.update(ADMIN, "TEE-1", { version: 1, pricePaise: 1 });
    expect((await svc.get(ASHA, order.id)).lines[0]!.unitPricePaise).toBe(29_900); // the order never changes
  });

  it("returns the same order for the same idempotency key", async () => {
    await cart.setItem(ASHA, "TEE-1", { quantity: 1 });
    const first = await svc.checkout(ASHA, KEY);
    const again = await svc.checkout(ASHA, KEY);
    expect([again.replayed, again.order.id, await stock("TEE-1")]).toEqual([true, first.order.id, 2]);
    // the key is per customer: Ravi's identical key is a different checkout
    expect((await status(svc.checkout(RAVI, KEY))).slice(0, 2)).toEqual([409, "cart-empty"]);
  });

  it("needs a key and a cart", async () => {
    expect(await status(svc.checkout(ASHA, undefined))).toEqual([
      400,
      "validation-error",
      "Idempotency-Key header is required",
    ]);
    expect(await status(svc.checkout(ASHA, KEY))).toEqual([
      409,
      "cart-empty",
      "add something to your cart before checking out",
    ]);
  });

  it("makes overselling impossible and never half-writes", async () => {
    await cart.setItem(ASHA, "TEE-1", { quantity: 1 });
    await cart.setItem(ASHA, "MUG-1", { quantity: 1 });
    await cart.setItem(RAVI, "MUG-1", { quantity: 1 });
    await svc.checkout(RAVI, "ravi-key-0001"); // Ravi buys the last mug first
    expect(await status(svc.checkout(ASHA, KEY))).toEqual([
      409,
      "insufficient-stock",
      "not enough stock for MUG-1",
    ]);
    expect(await stock("TEE-1")).toBe(3); // the tee was NOT decremented: all or nothing
    expect((await cart.view(ASHA)).lines).toHaveLength(2);
    expect(store.orders.size).toBe(1);
  });

  it("blocks checkout for a deactivated product", async () => {
    await cart.setItem(ASHA, "TEE-1", { quantity: 1 });
    await catalog.update(ADMIN, "TEE-1", { version: 1, active: false });
    expect(await status(svc.checkout(ASHA, KEY))).toEqual([
      409,
      "insufficient-stock",
      "TEE-1 is no longer available",
    ]);
  });

  it("answers 404 for other people's orders", async () => {
    await cart.setItem(ASHA, "TEE-1", { quantity: 1 });
    const { order } = await svc.checkout(ASHA, KEY);
    expect((await svc.get(ADMIN, order.id)).id).toBe(order.id);
    expect(await status(svc.get(RAVI, order.id))).toEqual([404, "not-found", "order not found"]);
    expect((await status(svc.get(ASHA, 999)))[0]).toBe(404);
    expect((await svc.mine(ASHA, undefined)).map((o) => o.id)).toEqual([order.id]);
    expect(await svc.mine(RAVI, undefined)).toEqual([]);
  });

  it("restocks on cancel and records an event", async () => {
    await cart.setItem(ASHA, "TEE-1", { quantity: 2 });
    const { order } = await svc.checkout(ASHA, KEY);
    const cancelled = await svc.cancel(ASHA, order.id);
    expect([cancelled.status, await stock("TEE-1")]).toEqual(["CANCELLED", 3]);
    expect(types()).toEqual(["order.placed", "order.cancelled"]);
    expect(await status(svc.cancel(ASHA, order.id))).toEqual([
      409,
      "invalid-transition",
      "cannot cancel an order that is CANCELLED",
    ]);
  });

  it("lets an admin ship and deliver paid orders", async () => {
    await cart.setItem(ASHA, "TEE-1", { quantity: 1 });
    const { order } = await svc.checkout(ASHA, KEY);
    expect((await status(svc.adminSetStatus(ADMIN, order.id, { status: "SHIPPED" })))[2]).toBe(
      "cannot ship an order that is PENDING_PAYMENT",
    );
    store.orders.get(order.id)!.status = "PAID";
    expect((await status(svc.adminSetStatus(ASHA, order.id, { status: "SHIPPED" })))[0]).toBe(403);
    expect((await svc.adminSetStatus(ADMIN, order.id, { status: "SHIPPED" })).status).toBe("SHIPPED");
    expect((await status(svc.cancel(ASHA, order.id)))[2]).toBe("cannot cancel an order that is SHIPPED");
    expect((await svc.adminSetStatus(ADMIN, order.id, { status: "DELIVERED" })).status).toBe("DELIVERED");
    expect(types().slice(-2)).toEqual(["order.shipped", "order.delivered"]);
  });

  it("requests a refund when a paid order is cancelled", async () => {
    await cart.setItem(ASHA, "TEE-1", { quantity: 1 });
    const { order } = await svc.checkout(ASHA, KEY);
    store.orders.get(order.id)!.status = "PAID";
    await svc.cancel(ASHA, order.id);
    expect(types()).toEqual(["order.placed", "order.cancelled", "refund.requested"]);
  });

  it("filters the admin list by status", async () => {
    await cart.setItem(ASHA, "TEE-1", { quantity: 1 });
    const a = (await svc.checkout(ASHA, KEY)).order;
    await cart.setItem(RAVI, "TEE-1", { quantity: 1 });
    const b = (await svc.checkout(RAVI, KEY)).order;
    await svc.cancel(RAVI, b.id);
    expect((await svc.adminList(ADMIN, undefined, undefined)).map((o) => o.id)).toEqual([b.id, a.id]); // newest first
    expect((await svc.adminList(ADMIN, "CANCELLED", undefined)).map((o) => o.id)).toEqual([b.id]);
    await expect(svc.adminList(ASHA, undefined, undefined)).rejects.toMatchObject({ status: 403 });
  });

  it("answers 409 for a concurrent change", async () => {
    await cart.setItem(ASHA, "TEE-1", { quantity: 1 });
    const { order } = await svc.checkout(ASHA, KEY);
    const stale = await svc.get(ASHA, order.id);
    store.orders.get(order.id)!.status = "PAID"; // a webhook won the race
    const [code, , detail] = await status(svc.apply(stale, "cancel"));
    expect(code).toBe(409);
    expect(detail).toContain("changed while we were updating it");
  });

  it("follows the state machine table", () => {
    expect(nextStatus("PENDING_PAYMENT", "pay")).toBe("PAID");
    expect(nextStatus("PENDING_PAYMENT", "fail")).toBe("CANCELLED");
    expect(nextStatus("PAID", "ship")).toBe("SHIPPED");
    expect(nextStatus("SHIPPED", "deliver")).toBe("DELIVERED");
    const bad = [
      ["DELIVERED", "cancel"],
      ["CANCELLED", "pay"],
      ["PAID", "pay"],
      ["PAID", "deliver"],
    ] as const;
    for (const [current, action] of bad) expect(() => nextStatus(current, action)).toThrow(ApiError);
  });
});
