// Checkout, the order state machine, the payment webhook and the transactional outbox.
import type { Clock } from "./clock.js";
import { invalidTransition } from "./errors.js";
import type { Order, OrderStatus, OutboxEvent, Principal, Transition } from "./models.js";
import { orderEventPayload, type CartRepository, type OrderRepository } from "./repository.js";

export type Action = "pay" | "fail" | "cancel" | "ship" | "deliver";

/** (current status, action) -> new status. Anything else is a 409 invalid-transition. */
export const TRANSITIONS: Record<string, OrderStatus> = {
  "PENDING_PAYMENT:pay": "PAID",
  "PENDING_PAYMENT:fail": "CANCELLED",
  "PENDING_PAYMENT:cancel": "CANCELLED",
  "PAID:cancel": "CANCELLED",
  "PAID:ship": "SHIPPED",
  "SHIPPED:deliver": "DELIVERED",
};
export const ACTION_FOR_TARGET: Record<string, Action> = { SHIPPED: "ship", DELIVERED: "deliver" };
export const EVENT_FOR_STATUS: Record<string, string> = {
  PAID: "order.paid",
  CANCELLED: "order.cancelled",
  SHIPPED: "order.shipped",
  DELIVERED: "order.delivered",
};
export const WEBHOOK_TYPES = ["payment.succeeded", "payment.failed"];
export const OUTBOX_BATCH = 100;

export function nextStatus(current: OrderStatus, action: Action): OrderStatus {
  // TODO(phase-3 M5): look up (current, action) in TRANSITIONS; not there -> 409 invalid-transition 'cannot
  //   <action> an order that is <status>'.
  throw new Error("TODO(phase-3 M5): nextStatus");
}

export const concurrentChange = () =>
  invalidTransition("the order changed while we were updating it: reload and retry");

export const payloadOf = (o: Order, status: OrderStatus) =>
  orderEventPayload(o.userId, o.id, status, o.totalPaise);

/** The status change plus its side effects: restock on cancel, outbox events, refund if it was paid. */
export function transitionFor(order: Order, action: Action, at: Date): Transition {
  // TODO(phase-3 M5): next_status + the side effects: an outbox event for the new status (EVENT_FOR_STATUS),
  //   a refund.requested event when a PAID order is cancelled, and restock when the target is CANCELLED.
  throw new Error("TODO(phase-3 M5): transitionFor");
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
    // TODO(phase-3 M5): ADR-0004: validate the Idempotency-Key; an order with that key -> return it
    //   (replayed=true). Empty cart -> 409 cart-empty; inactive product -> 409 insufficient-stock. Price the
    //   cart, snapshot the lines and the coupon (only if applied), new payment id, place_order in ONE
    //   transaction. InsufficientStock -> 409; DuplicateError (same key raced us) -> return the winner,
    //   replayed.
    throw new Error("TODO(phase-3 M5): OrderService.checkout");
  }

  async get(principal: Principal, orderId: number): Promise<Order> {
    // TODO(phase-3 M5): missing, or someone else's order (unless ADMIN) -> 404 not-found 'order' (never 403:
    //   don't confirm it exists).
    throw new Error("TODO(phase-3 M5): OrderService.get");
  }

  async mine(principal: Principal, limit: string | undefined): Promise<Order[]> {
    // TODO(phase-3 M5): the caller's orders, newest first, validated limit.
    throw new Error("TODO(phase-3 M5): OrderService.mine");
  }

  async cancel(principal: Principal, orderId: number): Promise<Order> {
    // TODO(phase-3 M5): get (404 rules) then apply 'cancel'.
    throw new Error("TODO(phase-3 M5): OrderService.cancel");
  }

  async adminList(
    principal: Principal,
    status: string | undefined,
    limit: string | undefined,
  ): Promise<Order[]> {
    // TODO(phase-3 M5): ADMIN only; validated status filter and limit.
    throw new Error("TODO(phase-3 M5): OrderService.adminList");
  }

  async adminSetStatus(principal: Principal, orderId: number, payload: unknown): Promise<Order> {
    // TODO(phase-3 M5): ADMIN only; validate status (SHIPPED or DELIVERED), map it to the action
    //   (ship/deliver), get, apply.
    throw new Error("TODO(phase-3 M5): OrderService.adminSetStatus");
  }

  async apply(order: Order, action: Action): Promise<Order> {
    // TODO(phase-3 M5): apply_transition(transition_for(order, action, now)); anything but 'ok' -> 409 (the
    //   order changed meanwhile: compare-and-set on the status). Return the order read back.
    throw new Error("TODO(phase-3 M5): OrderService.apply");
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
    // TODO(phase-3 M5): verify the signature on the RAW body FIRST (401 invalid-signature); then JSON object,
    //   id (8-64 chars), type (payment.succeeded / payment.failed), paymentId; unknown payment -> 404; a seen
    //   event id -> {status: duplicate}. Decide the transition (see the payments tests: pay, late payment ->
    //   refund.requested, fail -> cancel + restock, settled -> ignored) and apply it atomically together with
    //   recording the event; 'duplicate' -> duplicate, 'conflict' -> 409.
    throw new Error("TODO(phase-3 M5): PaymentService.handleWebhook");
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
    // TODO(phase-3 M5): ADMIN only; pending filter (true/false) and limit; events in id order.
    throw new Error("TODO(phase-3 M5): OutboxService.list");
  }

  /**
   * The relay: publish in id order, mark each one after the broker accepted it. Stops at the first failure.
   * A crash between publish and mark means the event is published again: at-least-once delivery,
   * so consumers de-duplicate on the event id (ADR-0005).
   */
  async publishPending(principal: Principal): Promise<number> {
    // TODO(phase-3 M5): ADMIN only. The relay (ADR-0005): pending events in id order (OUTBOX_BATCH), publish
    //   each and mark it published; on the FIRST publish failure log a warning and stop. Return how many were
    //   published.
    throw new Error("TODO(phase-3 M5): OutboxService.publishPending");
  }
}
