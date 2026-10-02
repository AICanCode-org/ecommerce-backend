"""Checkout, the order state machine, the payment webhook and the transactional outbox."""

from __future__ import annotations

import json
import logging
from collections.abc import Callable
from typing import Protocol

from . import validation as v
from .auth import require_role
from .catalog import is_admin
from .clock import Clock
from .errors import (
    ApiError,
    DuplicateError,
    InsufficientStockError,
    cart_empty,
    insufficient_stock,
    invalid_signature,
    invalid_transition,
    not_found,
    validation_error,
)
from .models import NewEvent, Order, OrderDraft, OrderLine, OutboxEvent, Principal, Transition
from .money import price_cart
from .repository import CartRepository, OrderRepository, order_event_payload
from .security import verify_webhook

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
    target = TRANSITIONS.get((current, action))
    if target is None:
        raise invalid_transition(f"cannot {action} an order that is {current}")
    return target


def concurrent_change() -> ApiError:
    return invalid_transition("the order changed while we were updating it: reload and retry")


def transition_for(order: Order, action: str, at) -> Transition:
    """The status change plus its side effects: restock on cancel, outbox events, refund if it was paid."""
    target = next_status(order.status, action)
    events = [NewEvent(EVENT_FOR_STATUS[target], order_event_payload(order, order.id, target))]
    if action == "cancel" and order.status == "PAID":
        events.append(NewEvent("refund.requested", order_event_payload(order, order.id, target)))
    return Transition(order.id, order.status, target, at, restock=target == "CANCELLED", events=events)


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
        key = v.idempotency_key(idempotency_key)
        existing = self.orders.find_order_by_key(principal.user_id, key)
        if existing is not None:
            return existing, True
        state = self._carts.cart(principal.user_id)
        if not state.lines:
            raise cart_empty()
        for line in state.lines:
            if not line.product.active:
                raise insufficient_stock(f"{line.product.sku} is no longer available")
        coupon = self._carts.find_coupon(state.coupon_code) if state.coupon_code else None
        pricing = price_cart([(line.product.price_paise, line.quantity) for line in state.lines], coupon)
        draft = OrderDraft(
            user_id=principal.user_id,
            idempotency_key=key,
            lines=[
                OrderLine(x.product.id, x.product.sku, x.product.name, x.product.price_paise, x.quantity)
                for x in state.lines
            ],  # fmt: skip
            pricing=pricing,
            coupon_code=state.coupon_code if pricing.coupon_applied else None,
            payment_id=self._new_payment_id(),
            at=self._clock(),
        )
        try:
            return self.orders.place_order(draft), False
        except InsufficientStockError as exc:
            raise insufficient_stock(f"not enough stock for {exc.sku}") from exc
        except DuplicateError:
            # The same key raced us (double click, retry after a timeout): return the winner's order.
            winner = self.orders.find_order_by_key(principal.user_id, key)
            if winner is None:  # pragma: no cover - the unique key guarantees it exists
                raise
            return winner, True

    def get(self, principal: Principal, order_id: int) -> Order:
        order = self.orders.find_order(order_id)
        if order is None or (order.user_id != principal.user_id and not is_admin(principal)):
            raise not_found("order")  # someone else's order is "not found", never "forbidden"
        return order

    def mine(self, principal: Principal, limit: str | None) -> list[Order]:
        return self.orders.orders_for_user(principal.user_id, v.limit(limit))

    def cancel(self, principal: Principal, order_id: int) -> Order:
        return self._apply(self.get(principal, order_id), "cancel")

    def admin_list(self, principal: Principal, status: str | None, limit: str | None) -> list[Order]:
        require_role(principal, "ADMIN")
        return self.orders.orders_by_status(v.order_status_filter(status), v.limit(limit))

    def admin_set_status(self, principal: Principal, order_id: int, payload: object) -> Order:
        require_role(principal, "ADMIN")
        target = v.order_status_target(v.require_object(payload).get("status"))
        return self._apply(self.get(principal, order_id), ACTION_FOR_TARGET[target])

    def _apply(self, order: Order, action: str) -> Order:
        result = self.orders.apply_transition(transition_for(order, action, self._clock()))
        if result != "ok":
            raise concurrent_change()
        return self.orders.find_order(order.id)  # type: ignore[return-value]


class PaymentService:
    """The fake payment provider calls POST /api/v1/payments/webhook. Never trust the browser's 'paid!'."""

    def __init__(self, orders: OrderRepository, secret: str, clock: Clock):
        self.orders = orders
        self._secret = secret
        self._clock = clock

    def handle_webhook(self, body: bytes, signature: str | None) -> dict:
        if not verify_webhook(self._secret, body, signature):
            raise invalid_signature()
        try:
            data = json.loads(body)
        except (ValueError, UnicodeDecodeError) as exc:
            raise validation_error("request body must be a JSON object") from exc
        data = v.require_object(data)
        event_id, kind, payment_id = data.get("id"), data.get("type"), data.get("paymentId")
        if not isinstance(event_id, str) or not v.IDEMPOTENCY_KEY_RE.match(event_id):
            raise validation_error("id must be 8-64 characters: A-Z, a-z, 0-9, _ and -")
        if kind not in WEBHOOK_TYPES:
            raise validation_error(f"type must be one of {', '.join(WEBHOOK_TYPES)}")
        if not isinstance(payment_id, str) or not payment_id:
            raise validation_error("paymentId is required")
        payment = self.orders.find_payment(payment_id)
        if payment is None:
            raise not_found("payment")
        if self.orders.has_payment_event(event_id):
            return {"eventId": event_id, "status": "duplicate"}
        order = self.orders.find_order(payment.order_id)
        assert order is not None
        now = self._clock()
        base = {"order_id": order.id, "from_status": order.status, "at": now, "payment_id": payment.id,
                "webhook_event_id": event_id, "webhook_event_type": kind}  # fmt: skip
        if payment.status != "PENDING":
            # A second, different event for a payment we already settled: record it, change nothing.
            t, outcome = Transition(**base, to_status=None), "ignored"
        elif kind == "payment.succeeded" and order.status == "PENDING_PAYMENT":
            pay = transition_for(order, "pay", now)
            t = Transition(**base, to_status="PAID", payment_status="SUCCEEDED", events=pay.events)
            outcome = "processed"
        elif kind == "payment.succeeded":
            # Money arrived for an order that was cancelled meanwhile: keep the order, refund the customer.
            refund = NewEvent("refund.requested", order_event_payload(order, order.id, order.status))
            t, outcome = Transition(**base, to_status=None, payment_status="SUCCEEDED", events=[refund]), "processed"
        elif order.status == "PENDING_PAYMENT":
            fail = transition_for(order, "fail", now)
            t = Transition(**base, to_status="CANCELLED", payment_status="FAILED", restock=True, events=fail.events)
            outcome = "processed"
        else:
            t, outcome = Transition(**base, to_status=None, payment_status="FAILED"), "processed"
        result = self.orders.apply_transition(t)
        if result == "duplicate":
            return {"eventId": event_id, "status": "duplicate"}
        if result == "conflict":
            raise concurrent_change()  # 409: the provider retries the webhook later
        return {"eventId": event_id, "status": outcome}


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
        require_role(principal, "ADMIN")
        only_pending = v.bool_filter(pending, "pending")
        return self.orders.outbox(bool(only_pending), v.limit(limit))

    def publish_pending(self, principal: Principal) -> int:
        """The relay: publish in id order, mark each one after the broker accepted it. Stops at the first failure.

        A crash between publish and mark means the event is published again: at-least-once delivery,
        so consumers de-duplicate on the event id (ADR-0005).
        """
        require_role(principal, "ADMIN")
        published = 0
        for event in self.orders.outbox(True, OUTBOX_BATCH):
            try:
                self._publisher.publish(event)
            except Exception as exc:  # noqa: BLE001 - a broker failure must not lose the remaining events
                log.warning("publishing event %s failed, will retry: %s", event.id, exc)
                break
            self.orders.mark_published(event.id, self._clock())
            published += 1
        return published
