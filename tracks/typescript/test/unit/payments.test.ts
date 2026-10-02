// M5: the signed, idempotent payment webhook and the outbox relay.
import { beforeEach, expect, it } from "vitest";
import { ApiError } from "../../src/errors.js";
import type { OutboxEvent, Principal } from "../../src/models.js";
import { OutboxService, PaymentService } from "../../src/orders.js";
import { signWebhook } from "../../src/security.js";
import { Api, WEBHOOK_SECRET } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("payments");
const ADMIN: Principal = { userId: 1, role: "ADMIN" };

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

class FlakyBroker {
  seen: number[] = [];
  constructor(public failOn: number | null = null) {}
  async publish(event: OutboxEvent) {
    if (event.id === this.failOn) throw new Error("broker down");
    this.seen.push(event.id);
  }
}

describe("payments and outbox", () => {
  let api: Api;
  let payments: PaymentService;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let order: any;
  beforeEach(async () => {
    api = await Api.create();
    payments = new PaymentService(api.store, WEBHOOK_SECRET, api.clock.fn);
    order = await api.order(await api.customer(), undefined, { sku: "TEE-1", stock: 2 });
  });
  const send = (event: object, secret = WEBHOOK_SECRET) => {
    const body = Buffer.from(JSON.stringify(event));
    return payments.handleWebhook(body, signWebhook(secret, body));
  };
  const types = () => api.store.events.map((e) => e.type);

  it("marks the order paid exactly once", async () => {
    const event = { id: "evt_0000001", type: "payment.succeeded", paymentId: order.payment.id };
    expect(await send(event)).toEqual({ eventId: "evt_0000001", status: "processed" });
    expect(await send(event)).toEqual({ eventId: "evt_0000001", status: "duplicate" });
    expect(api.store.orders.get(order.id)!.status).toBe("PAID");
    expect(api.store.payments.get(order.payment.id)!.status).toBe("SUCCEEDED");
    expect(types()).toEqual(["order.placed", "order.paid"]);
  });

  it("cancels and restocks on failure", async () => {
    await send({ id: "evt_0000002", type: "payment.failed", paymentId: order.payment.id });
    expect(api.store.orders.get(order.id)!.status).toBe("CANCELLED");
    expect((await api.store.findProduct("TEE-1"))!.stock).toBe(2);
    expect(types()).toEqual(["order.placed", "order.cancelled"]);
  });

  it("refunds money for a cancelled order instead of losing it", async () => {
    const session = await api.login("user1@shopflow.test", "password-123");
    await api.request("POST", `/api/v1/orders/${order.id}/cancel`, undefined, session);
    await send({ id: "evt_0000003", type: "payment.succeeded", paymentId: order.payment.id });
    expect(api.store.orders.get(order.id)!.status).toBe("CANCELLED");
    expect(api.store.payments.get(order.payment.id)!.status).toBe("SUCCEEDED");
    expect(types().at(-1)).toBe("refund.requested");
  });

  it("ignores a second, different event for a settled payment", async () => {
    const pid = order.payment.id;
    await send({ id: "evt_0000004", type: "payment.succeeded", paymentId: pid });
    const result = await send({ id: "evt_0000005", type: "payment.failed", paymentId: pid });
    expect(result.status).toBe("ignored");
    expect(api.store.orders.get(order.id)!.status).toBe("PAID");
    expect(api.store.paymentEvents.has("evt_0000005")).toBe(true);
  });

  it("checks the signature before anything else", async () => {
    const body = Buffer.from('{"id": "evt_0000006", "type": "payment.succeeded"}');
    expect(await status(payments.handleWebhook(body, undefined))).toEqual([
      401,
      "invalid-signature",
      "webhook signature is invalid",
    ]);
    expect((await status(payments.handleWebhook(body, signWebhook("x".repeat(32), body))))[0]).toBe(401);
    expect((await status(payments.handleWebhook(body, "sha256=" + "0".repeat(64))))[0]).toBe(401);
  });

  it("validates the webhook payload", async () => {
    const pid = order.payment.id;
    expect((await status(send({ id: "x", type: "payment.succeeded", paymentId: pid })))[2]).toBe(
      "id must be 8-64 characters: A-Z, a-z, 0-9, _ and -",
    );
    expect((await status(send({ id: "evt_0000007", type: "refund", paymentId: pid })))[2]).toBe(
      "type must be one of payment.succeeded, payment.failed",
    );
    expect((await status(send({ id: "evt_0000007", type: "payment.failed" })))[2]).toBe(
      "paymentId is required",
    );
    expect(await status(send({ id: "evt_0000007", type: "payment.failed", paymentId: "pay_nope" }))).toEqual([
      404,
      "not-found",
      "payment not found",
    ]);
    const body = Buffer.from("not json");
    expect((await status(payments.handleWebhook(body, signWebhook(WEBHOOK_SECRET, body))))[0]).toBe(400);
  });

  it("publishes the outbox in order and stops at the first failure", async () => {
    await send({ id: "evt_0000008", type: "payment.succeeded", paymentId: order.payment.id });
    const broker = new FlakyBroker(2);
    const relay = new OutboxService(api.store, broker, api.clock.fn, () => {});
    expect(await relay.publishPending(ADMIN)).toBe(1);
    expect(broker.seen).toEqual([1]);
    expect((await relay.list(ADMIN, "true", undefined)).map((e) => e.id)).toEqual([2]);
    broker.failOn = null;
    expect(await relay.publishPending(ADMIN)).toBe(1);
    expect(broker.seen).toEqual([1, 2]);
    expect(await relay.list(ADMIN, "true", undefined)).toEqual([]);
    expect(await relay.list(ADMIN, undefined, undefined)).toHaveLength(2);
    expect(await relay.publishPending(ADMIN)).toBe(0);
    await expect(relay.publishPending({ userId: 2, role: "CUSTOMER" })).rejects.toBeInstanceOf(ApiError);
  });
});
