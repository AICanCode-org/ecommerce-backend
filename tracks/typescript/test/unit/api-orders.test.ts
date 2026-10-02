// M5: checkout, orders, the webhook and the outbox over HTTP.
import { beforeEach, expect, it } from "vitest";
import { Api } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("api-orders");

describe("order endpoints", () => {
  let api: Api;
  beforeEach(async () => {
    api = await Api.create();
  });

  it("answers 201 for a checkout and 200 for its replay", async () => {
    const session = await api.customer();
    await api.product({ sku: "MUG-1", name: "Mug", pricePaise: 45_000, stock: 2 });
    await api.add(session, "MUG-1", 1);
    const r = await api.checkout(session);
    expect(r.statusCode).toBe(201);
    expect(r.headers.location).toBe(`/api/v1/orders/${r.json().id}`);
    expect(r.headers["idempotent-replayed"]).toBeUndefined();
    expect(r.json()).toEqual({
      id: r.json().id,
      userId: session.user.id,
      status: "PENDING_PAYMENT",
      items: [{ sku: "MUG-1", name: "Mug", unitPricePaise: 45_000, quantity: 1, lineTotalPaise: 45_000 }],
      subtotalPaise: 45_000,
      discountPaise: 0,
      shippingPaise: 4_900,
      totalPaise: 49_900,
      total: "₹499.00",
      couponCode: null,
      payment: { id: "pay_test_1", provider: "fakepay", status: "PENDING", amountPaise: 49_900 },
      createdAt: "2026-10-02T09:30:00.000Z",
      updatedAt: "2026-10-02T09:30:00.000Z",
    });
    const again = await api.checkout(session);
    expect(again.statusCode).toBe(200);
    expect(again.headers["idempotent-replayed"]).toBe("true");
    expect(again.json()).toEqual(r.json());
  });

  it("maps checkout errors", async () => {
    const session = await api.customer();
    const r = await api.request("POST", "/api/v1/checkout", undefined, session);
    expect(r.statusCode).toBe(400);
    expect(r.json().detail).toBe("Idempotency-Key header is required");
    expect((await api.checkout(session)).statusCode).toBe(409);
    const anonymous = await api.request("POST", "/api/v1/checkout", undefined, undefined, {
      "idempotency-key": "checkout-key-0001",
    });
    expect(anonymous.statusCode).toBe(401);
  });

  it("serves the order endpoints", async () => {
    const [asha, ravi] = [await api.customer(), await api.customer()];
    const order = await api.order(asha);
    const url = `/api/v1/orders/${order.id}`;
    expect((await api.request("GET", url, undefined, asha)).json().id).toBe(order.id);
    expect((await api.request("GET", url, undefined, ravi)).statusCode).toBe(404);
    expect((await api.request("GET", "/api/v1/orders/abc", undefined, asha)).statusCode).toBe(404);
    const mine = (await api.request("GET", "/api/v1/orders", undefined, asha)).json().items;
    expect(mine.map((o: { id: number }) => o.id)).toEqual([order.id]);
    const r = await api.request("POST", `${url}/cancel`, undefined, asha);
    expect([r.statusCode, r.json().status]).toEqual([200, "CANCELLED"]);
    const again = await api.request("POST", `${url}/cancel`, undefined, asha);
    expect(again.statusCode).toBe(409);
    expect(again.json().type.endsWith("/invalid-transition")).toBe(true);
  });

  it("takes the webhook over HTTP", async () => {
    const order = await api.order(await api.customer());
    const r = await api.pay(order);
    expect(r.statusCode).toBe(200);
    expect(r.json()).toEqual({ eventId: "evt_test_0001", status: "processed" });
    expect((await api.pay(order)).json().status).toBe("duplicate");
    const bad = await api.webhook(
      { id: "evt_test_0002", type: "payment.failed", paymentId: "x" },
      undefined,
      "sha256=00",
    );
    expect(bad.statusCode).toBe(401);
    expect(bad.json().type.endsWith("/invalid-signature")).toBe(true);
    const seen = (
      await api.request("GET", `/api/v1/orders/${order.id}`, undefined, await api.admin())
    ).json();
    expect(seen.payment.status).toBe("SUCCEEDED");
  });

  it("runs the admin fulfilment flow", async () => {
    const order = await api.order(await api.customer());
    await api.pay(order);
    const admin = await api.admin();
    const url = `/api/v1/admin/orders/${order.id}/status`;
    expect((await api.request("POST", url, { status: "SHIPPED" }, admin)).json().status).toBe("SHIPPED");
    expect((await api.request("POST", url, { status: "PAID" }, admin)).statusCode).toBe(400);
    expect((await api.request("POST", url, { status: "DELIVERED" }, admin)).json().status).toBe("DELIVERED");
    const listed = (
      await api.request("GET", "/api/v1/admin/orders?status=DELIVERED", undefined, admin)
    ).json().items;
    expect(listed.map((o: { id: number }) => o.id)).toEqual([order.id]);
    expect(
      (await api.request("GET", "/api/v1/admin/orders", undefined, await api.customer())).statusCode,
    ).toBe(403);
  });

  it("serves the outbox endpoints", async () => {
    const order = await api.order(await api.customer());
    await api.pay(order);
    const admin = await api.admin();
    const events = (await api.request("GET", "/api/v1/admin/outbox?pending=true", undefined, admin)).json()
      .items;
    expect(
      events.map((e: { type: string; orderId: number; publishedAt: string | null }) => [
        e.type,
        e.orderId,
        e.publishedAt,
      ]),
    ).toEqual([
      ["order.placed", order.id, null],
      ["order.paid", order.id, null],
    ]);
    expect(events[1].payload).toEqual({
      orderId: order.id,
      userId: order.userId,
      status: "PAID",
      totalPaise: order.totalPaise,
    });
    const published = await api.request("POST", "/api/v1/admin/outbox/publish", undefined, admin);
    expect(published.json()).toEqual({ published: 2 });
    expect(
      (await api.request("GET", "/api/v1/admin/outbox?pending=true", undefined, admin)).json().items,
    ).toEqual([]);
    const all = (await api.request("GET", "/api/v1/admin/outbox", undefined, admin)).json().items;
    expect(all.every((e: { publishedAt: string }) => e.publishedAt === "2026-10-02T09:30:00.000Z")).toBe(
      true,
    );
    expect(
      (await api.request("POST", "/api/v1/admin/outbox/publish", undefined, await api.customer())).statusCode,
    ).toBe(403);
  });
});
