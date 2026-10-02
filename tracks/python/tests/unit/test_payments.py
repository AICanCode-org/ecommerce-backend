"""M5: the signed, idempotent payment webhook and the outbox relay."""

import json

import pytest

from app.errors import ApiError
from app.models import Principal
from app.orders import OutboxService, PaymentService
from app.security import sign_webhook
from tests.conftest import WEBHOOK_SECRET

ADMIN = Principal(1, "ADMIN")


@pytest.fixture
def payments(store, clock) -> PaymentService:
    return PaymentService(store, WEBHOOK_SECRET, clock)


def send(payments, event: dict, secret: str = WEBHOOK_SECRET):
    body = json.dumps(event).encode()
    return payments.handle_webhook(body, sign_webhook(secret, body))


def status(fn, *args) -> tuple[int, str, str | None]:
    with pytest.raises(ApiError) as e:
        fn(*args)
    return e.value.status, e.value.slug, e.value.detail


@pytest.fixture
def order(api):
    return api.order(api.customer(), sku="TEE-1", stock=2)


def test_success_marks_the_order_paid_exactly_once(payments, order, store):
    event = {"id": "evt_0000001", "type": "payment.succeeded", "paymentId": order["payment"]["id"]}
    assert send(payments, event) == {"eventId": "evt_0000001", "status": "processed"}
    assert send(payments, event) == {"eventId": "evt_0000001", "status": "duplicate"}
    assert store.orders[order["id"]].status == "PAID"
    assert store.payments[order["payment"]["id"]].status == "SUCCEEDED"
    assert [e.type for e in store.events] == ["order.placed", "order.paid"]


def test_failure_cancels_and_restocks(payments, order, store):
    send(payments, {"id": "evt_0000002", "type": "payment.failed", "paymentId": order["payment"]["id"]})
    assert store.orders[order["id"]].status == "CANCELLED"
    assert store.find_product("TEE-1").stock == 2
    assert [e.type for e in store.events] == ["order.placed", "order.cancelled"]


def test_money_for_a_cancelled_order_is_refunded_not_lost(payments, order, store, api):
    api.client.post(
        f"/api/v1/orders/{order['id']}/cancel", headers=api.h(api.login("user1@shopflow.test", "password-123"))
    )
    send(payments, {"id": "evt_0000003", "type": "payment.succeeded", "paymentId": order["payment"]["id"]})
    assert store.orders[order["id"]].status == "CANCELLED"
    assert store.payments[order["payment"]["id"]].status == "SUCCEEDED"
    assert [e.type for e in store.events][-1] == "refund.requested"


def test_a_second_different_event_for_a_settled_payment_is_ignored(payments, order, store):
    pid = order["payment"]["id"]
    send(payments, {"id": "evt_0000004", "type": "payment.succeeded", "paymentId": pid})
    result = send(payments, {"id": "evt_0000005", "type": "payment.failed", "paymentId": pid})
    assert result["status"] == "ignored" and store.orders[order["id"]].status == "PAID"
    assert "evt_0000005" in store.payment_events


def test_signature_is_checked_before_anything_else(payments, order):
    body = b'{"id": "evt_0000006", "type": "payment.succeeded"}'
    assert status(payments.handle_webhook, body, None) == (401, "invalid-signature", "webhook signature is invalid")
    assert status(payments.handle_webhook, body, sign_webhook("x" * 32, body))[0] == 401
    assert status(payments.handle_webhook, body, "sha256=" + "0" * 64)[0] == 401


def test_webhook_payload_validation(payments, order):
    pid = order["payment"]["id"]
    assert status(send, payments, {"id": "x", "type": "payment.succeeded", "paymentId": pid})[2] == (
        "id must be 8-64 characters: A-Z, a-z, 0-9, _ and -"
    )
    assert status(send, payments, {"id": "evt_0000007", "type": "refund", "paymentId": pid})[2] == (
        "type must be one of payment.succeeded, payment.failed"
    )
    assert status(send, payments, {"id": "evt_0000007", "type": "payment.failed"})[2] == "paymentId is required"
    assert status(send, payments, {"id": "evt_0000007", "type": "payment.failed", "paymentId": "pay_nope"}) == (
        404,
        "not-found",
        "payment not found",
    )
    body = b"not json"
    assert status(payments.handle_webhook, body, sign_webhook(WEBHOOK_SECRET, body))[0] == 400


class FlakyBroker:
    def __init__(self, fail_on: int | None = None):
        self.seen: list[int] = []
        self.fail_on = fail_on

    def publish(self, event):
        if event.id == self.fail_on:
            raise ConnectionError("broker down")
        self.seen.append(event.id)


def test_outbox_relay_publishes_in_order_and_stops_at_the_first_failure(payments, order, store, clock):
    send(payments, {"id": "evt_0000008", "type": "payment.succeeded", "paymentId": order["payment"]["id"]})
    broker = FlakyBroker(fail_on=2)
    relay = OutboxService(store, broker, clock)
    assert relay.publish_pending(ADMIN) == 1 and broker.seen == [1]
    assert [e.id for e in relay.list(ADMIN, "true", None)] == [2]
    broker.fail_on = None
    assert relay.publish_pending(ADMIN) == 1 and broker.seen == [1, 2]
    assert relay.list(ADMIN, "true", None) == [] and len(relay.list(ADMIN, None, None)) == 2
    assert relay.publish_pending(ADMIN) == 0
    with pytest.raises(ApiError):
        relay.publish_pending(Principal(2, "CUSTOMER"))
