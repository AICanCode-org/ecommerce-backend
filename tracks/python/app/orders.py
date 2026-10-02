"""Checkout, the order state machine, the payment webhook and the transactional outbox."""

from __future__ import annotations

import json
import logging
from collections.abc import Callable
from typing import Protocol

from .clock import Clock
from .errors import (
    ApiError,
    invalid_transition,
)
from .models import Order, OutboxEvent, Principal, Transition
from .repository import CartRepository, OrderRepository

log = logging.getLogger("shopflow.orders")

# (current status, action) -> new status. Anything else is a 409 invalid-transition.
TRANSITIONS: dict[tuple[str, str], str] = {
    ("PENDING_PAYMENT", "pay"): "PAID",
    ("PENDING_PAYMENT", "fail"): "CANCELLED",
    ("PENDING_PAYMENT", "cancel"): "CANCELLED",
    ("PAID", "cancel"): "CANCELLED",
    ("PAID", "ship"): "SHIPPED",
    ("SHIPPED", "deliver"): "DELIVERED",
}
ACTION_FOR_TARGET = {"SHIPPED": "ship", "DELIVERED": "deliver"}
EVENT_FOR_STATUS = {"PAID": "order.paid", "CANCELLED": "order.cancelled", "SHIPPED": "order.shipped",
                    "DELIVERED": "order.delivered"}  # fmt: skip
WEBHOOK_TYPES = ("payment.succeeded", "payment.failed")
OUTBOX_BATCH = 100


def next_status(current: str, action: str) -> str:
    # TODO(phase-3 M5): look up (current, action) in TRANSITIONS; not there -> 409 invalid-
    #   transition 'cannot <action> an order that is <status>'.
    raise NotImplementedError("TODO(phase-3 M5): next_status")


def concurrent_change() -> ApiError:
    return invalid_transition("the order changed while we were updating it: reload and retry")


def transition_for(order: Order, action: str, at) -> Transition:
    """The status change plus its side effects: restock on cancel, outbox events, refund if it was paid."""
    # TODO(phase-3 M5): next_status + the side effects: an outbox event for the new status
    #   (EVENT_FOR_STATUS), a refund.requested event when a PAID order is cancelled, and restock when
    #   the target is CANCELLED.
    raise NotImplementedError("TODO(phase-3 M5): transition_for")


class OrderService:
    def __init__(
        self,
        orders: OrderRepository,
        carts: CartRepository,
        clock: Clock,
        new_payment_id: Callable[[], str],
    ):
        self.orders = orders
        self._carts = carts
        self._clock = clock
        self._new_payment_id = new_payment_id

    def checkout(self, principal: Principal, idempotency_key: str | None) -> tuple[Order, bool]:
        """Returns (order, replayed). The same Idempotency-Key always returns the same order (ADR-0004)."""
        # TODO(phase-3 M5): ADR-0004: validate the Idempotency-Key; an order with that key -> return
        #   it (replayed=true). Empty cart -> 409 cart-empty; inactive product -> 409 insufficient-
        #   stock. Price the cart, snapshot the lines and the coupon (only if applied), new payment
        #   id, place_order in ONE transaction. InsufficientStock -> 409; DuplicateError (same key
        #   raced us) -> return the winner, replayed.
        raise NotImplementedError("TODO(phase-3 M5): OrderService.checkout")

    def get(self, principal: Principal, order_id: int) -> Order:
        # TODO(phase-3 M5): missing, or someone else's order (unless ADMIN) -> 404 not-found 'order'
        #   (never 403: don't confirm it exists).
        raise NotImplementedError("TODO(phase-3 M5): OrderService.get")

    def mine(self, principal: Principal, limit: str | None) -> list[Order]:
        # TODO(phase-3 M5): the caller's orders, newest first, validated limit.
        raise NotImplementedError("TODO(phase-3 M5): OrderService.mine")

    def cancel(self, principal: Principal, order_id: int) -> Order:
        # TODO(phase-3 M5): get (404 rules) then apply 'cancel'.
        raise NotImplementedError("TODO(phase-3 M5): OrderService.cancel")

    def admin_list(self, principal: Principal, status: str | None, limit: str | None) -> list[Order]:
        # TODO(phase-3 M5): ADMIN only; validated status filter and limit.
        raise NotImplementedError("TODO(phase-3 M5): OrderService.admin_list")

    def admin_set_status(self, principal: Principal, order_id: int, payload: object) -> Order:
        # TODO(phase-3 M5): ADMIN only; validate status (SHIPPED or DELIVERED), map it to the action
        #   (ship/deliver), get, apply.
        raise NotImplementedError("TODO(phase-3 M5): OrderService.admin_set_status")

    def _apply(self, order: Order, action: str) -> Order:
        # TODO(phase-3 M5): apply_transition(transition_for(order, action, now)); anything but 'ok'
        #   -> 409 (the order changed meanwhile: compare-and-set on the status). Return the order read
        #   back.
        raise NotImplementedError("TODO(phase-3 M5): OrderService._apply")


class PaymentService:
    """The fake payment provider calls POST /api/v1/payments/webhook. Never trust the browser's 'paid!'."""

    def __init__(self, orders: OrderRepository, secret: str, clock: Clock):
        self.orders = orders
        self._secret = secret
        self._clock = clock

    def handle_webhook(self, body: bytes, signature: str | None) -> dict:
        # TODO(phase-3 M5): verify the signature on the RAW body FIRST (401 invalid-signature); then
        #   JSON object, id (8-64 chars), type (payment.succeeded / payment.failed), paymentId;
        #   unknown payment -> 404; a seen event id -> {status: duplicate}. Decide the transition (see
        #   the payments tests: pay, late payment -> refund.requested, fail -> cancel + restock,
        #   settled -> ignored) and apply it atomically together with recording the event; 'duplicate'
        #   -> duplicate, 'conflict' -> 409.
        raise NotImplementedError("TODO(phase-3 M5): PaymentService.handle_webhook")


class EventPublisher(Protocol):
    def publish(self, event: OutboxEvent) -> None: ...


class LogPublisher:
    """Stands in for a message broker: one log line per event. Swap for RabbitMQ/Kafka/SNS later."""

    def publish(self, event: OutboxEvent) -> None:
        log.info("event %s %s %s", event.id, event.type, json.dumps(event.payload, sort_keys=True))


class OutboxService:
    def __init__(self, orders: OrderRepository, publisher: EventPublisher, clock: Clock):
        self.orders = orders
        self._publisher = publisher
        self._clock = clock

    def list(self, principal: Principal, pending: str | None, limit: str | None) -> list[OutboxEvent]:
        # TODO(phase-3 M5): ADMIN only; pending filter (true/false) and limit; events in id order.
        raise NotImplementedError("TODO(phase-3 M5): OutboxService.list")

    def publish_pending(self, principal: Principal) -> int:
        """The relay: publish in id order, mark each one after the broker accepted it. Stops at the first failure.

        A crash between publish and mark means the event is published again: at-least-once delivery,
        so consumers de-duplicate on the event id (ADR-0005).
        """
        # TODO(phase-3 M5): ADMIN only. The relay (ADR-0005): pending events in id order
        #   (OUTBOX_BATCH), publish each and mark it published; on the FIRST publish failure log a
        #   warning and stop. Return how many were published.
        raise NotImplementedError("TODO(phase-3 M5): OutboxService.publish_pending")
