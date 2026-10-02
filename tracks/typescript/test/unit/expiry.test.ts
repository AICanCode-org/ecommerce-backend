// v1.1 (Phase 7): unpaid orders hold their stock for 15 minutes, then an admin job cancels them.
import { beforeEach, describe, expect, it } from "vitest";
import { CartService } from "../../src/cart.js";
import { CatalogService } from "../../src/catalog.js";
import { ApiError } from "../../src/errors.js";
import type { Principal } from "../../src/models.js";
import { nextStatus, OrderService, RESERVATION_TTL_MS } from "../../src/orders.js";
import { InMemoryStore } from "../../src/repository.js";
import { Api, FakeClock, paymentIds } from "../helpers.js";

const ADMIN: Principal = { userId: 1, role: "ADMIN" };
const ASHA: Principal = { userId: 2, role: "CUSTOMER" };

describe("payment window expiry", () => {
  let store: InMemoryStore;
  let clock: FakeClock;
  let catalog: CatalogService;
  let cart: CartService;
  let svc: OrderService;
  beforeEach(async () => {
    store = new InMemoryStore();
    clock = new FakeClock();
    catalog = new CatalogService(store, clock.fn);
    await catalog.create(ADMIN, {
      sku: "TEE-1",
      name: "Tee",
      category: "apparel",
      pricePaise: 29_900,
      stock: 3,
    });
    cart = new CartService(store, store, clock.fn);
    svc = new OrderService(store, store, clock.fn, paymentIds());
  });
  const stock = async () => (await catalog.get(ADMIN, "TEE-1")).stock;

  it("gives every order a 15 minute payment window", async () => {
    await cart.setItem(ASHA, "TEE-1", { quantity: 1 });
    const { order } = await svc.checkout(ASHA, "expiry-key-0001");
    expect(RESERVATION_TTL_MS).toBe(15 * 60 * 1000);
    expect(order.expiresAt.getTime()).toBe(clock.now.getTime() + RESERVATION_TTL_MS);
  });

  it("cancels only overdue unpaid orders and restocks them", async () => {
    await cart.setItem(ASHA, "TEE-1", { quantity: 2 });
    const old = (await svc.checkout(ASHA, "expiry-key-0001")).order;
    clock.advance({ minutes: 10 });
    await cart.setItem(ASHA, "TEE-1", { quantity: 1 });
    const fresh = (await svc.checkout(ASHA, "expiry-key-0002")).order;
    expect(await stock()).toBe(0);
    clock.advance({ minutes: 4, seconds: 59 });
    expect(await svc.expireOverdue(ADMIN)).toBe(0); // 14:59 after the first checkout: still open
    clock.advance({ seconds: 1 });
    expect(await svc.expireOverdue(ADMIN)).toBe(1); // exactly 15:00: the window is closed
    expect(store.orders.get(old.id)!.status).toBe("CANCELLED");
    expect(store.orders.get(fresh.id)!.status).toBe("PENDING_PAYMENT");
    expect(await stock()).toBe(2);
    expect(store.events.map((e) => e.type)).toEqual(["order.placed", "order.placed", "order.cancelled"]);
    expect(await svc.expireOverdue(ADMIN)).toBe(0); // running it again changes nothing
  });

  it("never expires a paid order", async () => {
    await cart.setItem(ASHA, "TEE-1", { quantity: 1 });
    const { order } = await svc.checkout(ASHA, "expiry-key-0001");
    store.orders.get(order.id)!.status = "PAID";
    clock.advance({ hours: 1 });
    expect(await svc.expireOverdue(ADMIN)).toBe(0);
    expect(store.orders.get(order.id)!.status).toBe("PAID");
  });

  it("is for admins only", async () => {
    await expect(svc.expireOverdue(ASHA)).rejects.toMatchObject({ status: 403 });
  });

  it("skips an order the webhook paid a moment ago instead of failing", async () => {
    await cart.setItem(ASHA, "TEE-1", { quantity: 1 });
    const { order } = await svc.checkout(ASHA, "expiry-key-0001");
    clock.advance({ minutes: 20 });
    const stale = await store.expiredOrders(clock.now, 10);
    store.orders.get(order.id)!.status = "PAID"; // the payment webhook won the race
    store.expiredOrders = async () => stale;
    expect(await svc.expireOverdue(ADMIN)).toBe(0);
    expect(store.orders.get(order.id)!.status).toBe("PAID");
  });

  it("is a state machine transition", () => {
    expect(nextStatus("PENDING_PAYMENT", "expire")).toBe("CANCELLED");
    expect(() => nextStatus("PAID", "expire")).toThrow(ApiError);
  });
});

describe("POST /api/v1/admin/orders/expire", () => {
  it("expires overdue orders, and a late payment is refunded, not resurrected", async () => {
    const api = await Api.create();
    const asha = await api.customer();
    const order = await api.order(asha, "checkout-key-0001", { stock: 1 });
    expect(order.expiresAt).toBe("2026-10-02T09:45:00.000Z");
    const url = "/api/v1/admin/orders/expire";
    expect((await api.request("POST", url, undefined, asha)).statusCode).toBe(403);
    expect((await api.request("POST", url)).statusCode).toBe(401);
    expect((await api.request("POST", url, undefined, await api.admin())).json()).toEqual({ expired: 0 });
    api.clock.advance({ minutes: 15 });
    expect((await api.request("POST", url, undefined, await api.admin())).json()).toEqual({ expired: 1 });
    const after = (await api.request("GET", `/api/v1/orders/${order.id}`, undefined, asha)).json();
    expect([after.status, after.payment.status]).toEqual(["CANCELLED", "PENDING"]);
    const sku = order.items[0].sku;
    expect((await api.request("GET", `/api/v1/products/${sku}`)).json().stock).toBe(1);
    expect((await api.pay(order)).json()).toEqual({ eventId: "evt_test_0001", status: "processed" });
    const events = (await api.request("GET", "/api/v1/admin/outbox", undefined, await api.admin())).json();
    expect(events.items.map((e: { type: string }) => e.type).slice(-2)).toEqual([
      "order.cancelled",
      "refund.requested",
    ]);
  });
});
