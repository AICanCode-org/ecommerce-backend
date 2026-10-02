// Checkout, the order state machine, the payment webhook and the transactional outbox.
import { requireRole } from "./auth.js";
import { isAdmin } from "./catalog.js";
import type { Clock } from "./clock.js";
import {
  cartEmpty,
  DuplicateError,
  InsufficientStockError,
  insufficientStock,
  invalidSignature,
  invalidTransition,
  notFound,
  validationError,
} from "./errors.js";
import type { NewEvent, Order, OrderStatus, OutboxEvent, Principal, Transition } from "./models.js";
import { priceCart } from "./money.js";
import { orderEventPayload, type CartRepository, type OrderRepository } from "./repository.js";
import { verifyWebhook } from "./security.js";
import * as v from "./validation.js";

export type Action = "pay" | "fail" | "cancel" | "expire" | "ship" | "deliver";

/** (current status, action) -> new status. Anything else is a 409 invalid-transition. */
export const TRANSITIONS: Record<string, OrderStatus> = {
  "PENDING_PAYMENT:pay": "PAID",
  "PENDING_PAYMENT:fail": "CANCELLED",
  "PENDING_PAYMENT:cancel": "CANCELLED",
  "PENDING_PAYMENT:expire": "CANCELLED", // v1.1: the payment window closed
  "PAID:cancel": "CANCELLED",
  "PAID:ship": "SHIPPED",
  "SHIPPED:deliver": "DELIVERED",
};
const ACTION_FOR_TARGET: Record<string, Action> = { SHIPPED: "ship", DELIVERED: "deliver" };
const EVENT_FOR_STATUS: Record<string, string> = {
  PAID: "order.paid",
  CANCELLED: "order.cancelled",
  SHIPPED: "order.shipped",
  DELIVERED: "order.delivered",
};
export const WEBHOOK_TYPES = ["payment.succeeded", "payment.failed"];
export const OUTBOX_BATCH = 100;
/** v1.1: how long an unpaid order holds its stock. */
export const RESERVATION_TTL_MS = 15 * 60 * 1000;
export const EXPIRE_BATCH = 100;

export function nextStatus(current: OrderStatus, action: Action): OrderStatus {
  const target = TRANSITIONS[`${current}:${action}`];
  if (!target) throw invalidTransition(`cannot ${action} an order that is ${current}`);
  return target;
}

export const concurrentChange = () =>
  invalidTransition("the order changed while we were updating it: reload and retry");

const payloadOf = (o: Order, status: OrderStatus) => orderEventPayload(o.userId, o.id, status, o.totalPaise);

/** The status change plus its side effects: restock on cancel, outbox events, refund if it was paid. */
export function transitionFor(order: Order, action: Action, at: Date): Transition {
  const target = nextStatus(order.status, action);
  const events: NewEvent[] = [{ type: EVENT_FOR_STATUS[target]!, payload: payloadOf(order, target) }];
  if (action === "cancel" && order.status === "PAID") {
    events.push({ type: "refund.requested", payload: payloadOf(order, target) });
  }
  return {
    orderId: order.id,
    fromStatus: order.status,
    toStatus: target,
    at,
    restock: target === "CANCELLED",
    events,
  };
}

export class OrderService {
  constructor(
    readonly orders: OrderRepository,
    private readonly carts: CartRepository,
    private readonly clock: Clock,
    private readonly newPaymentId: () => string,
  ) {}

  /** The same Idempotency-Key always returns the same order (ADR-0004). */
  async checkout(
    principal: Principal,
    idempotencyKey: string | undefined,
  ): Promise<{ order: Order; replayed: boolean }> {
    const key = v.idempotencyKey(idempotencyKey);
    const existing = await this.orders.findOrderByKey(principal.userId, key);
    if (existing) return { order: existing, replayed: true };
    const state = await this.carts.cart(principal.userId);
    if (!state.lines.length) throw cartEmpty();
    for (const line of state.lines) {
      if (!line.product.active) throw insufficientStock(`${line.product.sku} is no longer available`);
    }
    const coupon = state.couponCode ? await this.carts.findCoupon(state.couponCode) : null;
    const pricing = priceCart(
      state.lines.map((l) => [l.product.pricePaise, l.quantity]),
      coupon,
    );
    const now = this.clock();
    try {
      const order = await this.orders.placeOrder({
        userId: principal.userId,
        idempotencyKey: key,
        lines: state.lines.map((l) => ({
          productId: l.product.id,
          sku: l.product.sku,
          name: l.product.name,
          unitPricePaise: l.product.pricePaise,
          quantity: l.quantity,
        })),
        pricing,
        couponCode: pricing.couponApplied ? state.couponCode : null,
        paymentId: this.newPaymentId(),
        at: now,
        expiresAt: new Date(now.getTime() + RESERVATION_TTL_MS),
      });
      return { order, replayed: false };
    } catch (err) {
      if (err instanceof InsufficientStockError) throw insufficientStock(`not enough stock for ${err.sku}`);
      if (err instanceof DuplicateError) {
        // The same key raced us (double click, retry after a timeout): return the winner's order.
        const winner = await this.orders.findOrderByKey(principal.userId, key);
        if (winner) return { order: winner, replayed: true };
      }
      throw err;
    }
  }

  async get(principal: Principal, orderId: number): Promise<Order> {
    const order = await this.orders.findOrder(orderId);
    if (!order || (order.userId !== principal.userId && !isAdmin(principal))) {
      throw notFound("order"); // someone else's order is "not found", never "forbidden"
    }
    return order;
  }

  async mine(principal: Principal, limit: string | undefined): Promise<Order[]> {
    return this.orders.ordersForUser(principal.userId, v.limit(limit));
  }

  async cancel(principal: Principal, orderId: number): Promise<Order> {
    return this.apply(await this.get(principal, orderId), "cancel");
  }

  async adminList(
    principal: Principal,
    status: string | undefined,
    limit: string | undefined,
  ): Promise<Order[]> {
    requireRole(principal, "ADMIN");
    return this.orders.ordersByStatus(v.orderStatusFilter(status), v.limit(limit));
  }

  async adminSetStatus(principal: Principal, orderId: number, payload: unknown): Promise<Order> {
    requireRole(principal, "ADMIN");
    const target = v.orderStatusTarget(v.requireObject(payload).status);
    return this.apply(await this.get(principal, orderId), ACTION_FOR_TARGET[target]!);
  }

  /**
   * v1.1: cancel unpaid orders whose payment window closed and put their stock back.
   * Safe to run any time and from several places at once: each order goes through the same
   * compare-and-set transition, so an order paid a millisecond ago is simply skipped.
   */
  async expireOverdue(principal: Principal): Promise<number> {
    requireRole(principal, "ADMIN");
    const now = this.clock();
    let expired = 0;
    for (const order of await this.orders.expiredOrders(now, EXPIRE_BATCH)) {
      if ((await this.orders.applyTransition(transitionFor(order, "expire", now))) === "ok") expired += 1;
    }
    return expired;
  }

  async apply(order: Order, action: Action): Promise<Order> {
    const result = await this.orders.applyTransition(transitionFor(order, action, this.clock()));
    if (result !== "ok") throw concurrentChange();
    return (await this.orders.findOrder(order.id))!;
  }
}

export interface WebhookResult {
  eventId: string;
  status: "processed" | "duplicate" | "ignored";
}

/** The fake payment provider calls POST /api/v1/payments/webhook. Never trust the browser's 'paid!'. */
export class PaymentService {
  constructor(
    readonly orders: OrderRepository,
    private readonly secret: string,
    private readonly clock: Clock,
  ) {}

  async handleWebhook(body: Buffer, signature: string | undefined): Promise<WebhookResult> {
    if (!verifyWebhook(this.secret, body, signature)) throw invalidSignature();
    let parsed: unknown;
    try {
      parsed = JSON.parse(body.toString("utf8"));
    } catch {
      throw validationError("request body must be a JSON object");
    }
    const data = v.requireObject(parsed);
    const { id: eventId, type: kind, paymentId } = data;
    if (typeof eventId !== "string" || !v.IDEMPOTENCY_KEY_RE.test(eventId)) {
      throw validationError("id must be 8-64 characters: A-Z, a-z, 0-9, _ and -");
    }
    if (typeof kind !== "string" || !WEBHOOK_TYPES.includes(kind)) {
      throw validationError(`type must be one of ${WEBHOOK_TYPES.join(", ")}`);
    }
    if (typeof paymentId !== "string" || !paymentId) throw validationError("paymentId is required");
    const payment = await this.orders.findPayment(paymentId);
    if (!payment) throw notFound("payment");
    if (await this.orders.hasPaymentEvent(eventId)) return { eventId, status: "duplicate" };
    const order = (await this.orders.findOrder(payment.orderId))!;
    const now = this.clock();
    const base = {
      orderId: order.id,
      fromStatus: order.status,
      at: now,
      paymentId: payment.id,
      webhookEventId: eventId,
      webhookEventType: kind,
    };
    let t: Transition;
    let outcome: WebhookResult["status"] = "processed";
    if (payment.status !== "PENDING") {
      // A second, different event for a payment we already settled: record it, change nothing.
      t = { ...base, toStatus: null };
      outcome = "ignored";
    } else if (kind === "payment.succeeded" && order.status === "PENDING_PAYMENT") {
      t = {
        ...base,
        toStatus: "PAID",
        paymentStatus: "SUCCEEDED",
        events: transitionFor(order, "pay", now).events,
      };
    } else if (kind === "payment.succeeded") {
      // Money arrived for an order that was cancelled meanwhile: keep the order, refund the customer.
      const refund = { type: "refund.requested", payload: payloadOf(order, order.status) };
      t = { ...base, toStatus: null, paymentStatus: "SUCCEEDED", events: [refund] };
    } else if (order.status === "PENDING_PAYMENT") {
      const fail = transitionFor(order, "fail", now);
      t = { ...base, toStatus: "CANCELLED", paymentStatus: "FAILED", restock: true, events: fail.events };
    } else {
      t = { ...base, toStatus: null, paymentStatus: "FAILED" };
    }
    const result = await this.orders.applyTransition(t);
    if (result === "duplicate") return { eventId, status: "duplicate" };
    if (result === "conflict") throw concurrentChange(); // 409: the provider retries the webhook later
    return { eventId, status: outcome };
  }
}

export interface EventPublisher {
  publish(event: OutboxEvent): Promise<void>;
}

/** Stands in for a message broker: one log line per event. Swap for RabbitMQ/Kafka/SNS later. */
export class LogPublisher implements EventPublisher {
  constructor(private readonly log: (msg: string) => void = console.log) {}

  async publish(event: OutboxEvent) {
    this.log(`event ${event.id} ${event.type} ${JSON.stringify(event.payload)}`);
  }
}

export class OutboxService {
  constructor(
    readonly orders: OrderRepository,
    private readonly publisher: EventPublisher,
    private readonly clock: Clock,
    private readonly warn: (msg: string) => void = console.warn,
  ) {}

  async list(
    principal: Principal,
    pending: string | undefined,
    limit: string | undefined,
  ): Promise<OutboxEvent[]> {
    requireRole(principal, "ADMIN");
    const onlyPending = v.boolFilter(pending, "pending");
    return this.orders.outbox(onlyPending === true, v.limit(limit));
  }

  /**
   * The relay: publish in id order, mark each one after the broker accepted it. Stops at the first failure.
   * A crash between publish and mark means the event is published again: at-least-once delivery,
   * so consumers de-duplicate on the event id (ADR-0005).
   */
  async publishPending(principal: Principal): Promise<number> {
    requireRole(principal, "ADMIN");
    let published = 0;
    for (const event of await this.orders.outbox(true, OUTBOX_BATCH)) {
      try {
        await this.publisher.publish(event);
      } catch (err) {
        this.warn(`publishing event ${event.id} failed, will retry: ${(err as Error).message}`);
        break;
      }
      await this.orders.markPublished(event.id, this.clock());
      published += 1;
    }
    return published;
  }
}
