package org.aicancode.shopflow.service;

import static org.aicancode.shopflow.support.Problems.codeOf;
import static org.aicancode.shopflow.support.Problems.problem;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.aicancode.shopflow.domain.Models.OutboxEvent;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.security.Webhooks;
import org.aicancode.shopflow.support.TestApp;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M5: the signed, idempotent payment webhook and the outbox relay. */
class PaymentServiceTest {

    private static final Principal ADMIN = new Principal(1, "ADMIN");
    private final TestApp api = new TestApp();
    private final PaymentService payments = new PaymentService(api.store, TestApp.WEBHOOK_SECRET, api.clock);
    private final JsonNode order = api.order(api.customer(), "checkout-key-0001", Map.of("sku", "TEE-1", "stock", 2));
    private final long orderId = order.get("id").asLong();
    private final String paymentId = order.at("/payment/id").asString();

    /** Fails on one event id, records the rest. */
    static final class FlakyBroker implements EventPublisher {
        final List<Long> seen = new ArrayList<>();
        Long failOn;

        FlakyBroker(Long failOn) {
            this.failOn = failOn;
        }

        @Override
        public void publish(OutboxEvent event) {
            if (failOn != null && event.id() == failOn) {
                throw new IllegalStateException("broker down");
            }
            seen.add(event.id());
        }
    }

    private Map<String, Object> send(Map<String, Object> event) {
        byte[] body = TestApp.JSON.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        return payments.handleWebhook(body, Webhooks.sign(TestApp.WEBHOOK_SECRET, body));
    }

    private static Map<String, Object> event(String id, String type, String paymentId) {
        return Map.of("id", id, "type", type, "paymentId", paymentId);
    }

    private List<String> types() {
        return api.store.events.stream().map(OutboxEvent::type).toList();
    }

    @Test
    void marksTheOrderPaidExactlyOnce() {
        Map<String, Object> e = event("evt_0000001", "payment.succeeded", paymentId);
        assertThat(send(e)).isEqualTo(Map.of("eventId", "evt_0000001", "status", "processed"));
        assertThat(send(e)).isEqualTo(Map.of("eventId", "evt_0000001", "status", "duplicate"));
        assertThat(api.store.orders.get(orderId).status()).isEqualTo("PAID");
        assertThat(api.store.payments.get(paymentId).status()).isEqualTo("SUCCEEDED");
        assertThat(types()).containsExactly("order.placed", "order.paid");
    }

    @Test
    void cancelsAndRestocksOnFailure() {
        send(event("evt_0000002", "payment.failed", paymentId));
        assertThat(api.store.orders.get(orderId).status()).isEqualTo("CANCELLED");
        assertThat(api.store.findProduct("TEE-1").orElseThrow().stock()).isEqualTo(2);
        assertThat(types()).containsExactly("order.placed", "order.cancelled");
    }

    @Test
    void refundsMoneyForACancelledOrderInsteadOfLosingIt() {
        JsonNode session = api.login("user1@shopflow.test", "password-123");
        api.call("POST", "/api/v1/orders/" + orderId + "/cancel", null, session);
        send(event("evt_0000003", "payment.succeeded", paymentId));
        assertThat(api.store.orders.get(orderId).status()).isEqualTo("CANCELLED");
        assertThat(api.store.payments.get(paymentId).status()).isEqualTo("SUCCEEDED");
        assertThat(types().get(types().size() - 1)).isEqualTo("refund.requested");
    }

    @Test
    void ignoresASecondDifferentEventForASettledPayment() {
        send(event("evt_0000004", "payment.succeeded", paymentId));
        assertThat(send(event("evt_0000005", "payment.failed", paymentId)).get("status")).isEqualTo("ignored");
        assertThat(api.store.orders.get(orderId).status()).isEqualTo("PAID");
        assertThat(api.store.paymentEvents).contains("evt_0000005");
    }

    @Test
    void checksTheSignatureBeforeAnythingElse() {
        byte[] body = "{\"id\": \"evt_0000006\", \"type\": \"payment.succeeded\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(problem(() -> payments.handleWebhook(body, null)))
                .containsExactly(401, "invalid-signature", "webhook signature is invalid");
        assertThat(codeOf(() -> payments.handleWebhook(body, Webhooks.sign("x".repeat(32), body)))).isEqualTo(401);
        assertThat(codeOf(() -> payments.handleWebhook(body, "sha256=" + "0".repeat(64)))).isEqualTo(401);
    }

    @Test
    void validatesTheWebhookPayload() {
        assertThat(problem(() -> send(event("x", "payment.succeeded", paymentId))).get(2))
                .isEqualTo("id must be 8-64 characters: A-Z, a-z, 0-9, _ and -");
        assertThat(problem(() -> send(event("evt_0000007", "refund", paymentId))).get(2))
                .isEqualTo("type must be one of payment.succeeded, payment.failed");
        assertThat(problem(() -> send(Map.of("id", "evt_0000007", "type", "payment.failed"))).get(2))
                .isEqualTo("paymentId is required");
        assertThat(problem(() -> send(event("evt_0000007", "payment.failed", "pay_nope"))))
                .containsExactly(404, "not-found", "payment not found");
        byte[] body = "not json".getBytes(StandardCharsets.UTF_8);
        assertThat(codeOf(() -> payments.handleWebhook(body, Webhooks.sign(TestApp.WEBHOOK_SECRET, body)))).isEqualTo(400);
    }

    @Test
    void publishesTheOutboxInOrderAndStopsAtTheFirstFailure() {
        send(event("evt_0000008", "payment.succeeded", paymentId));
        FlakyBroker broker = new FlakyBroker(2L);
        OutboxService relay = new OutboxService(api.store, broker, api.clock);
        assertThat(relay.publishPending(ADMIN)).isEqualTo(1);
        assertThat(broker.seen).containsExactly(1L);
        assertThat(relay.list(ADMIN, "true", null)).extracting(OutboxEvent::id).containsExactly(2L);
        broker.failOn = null;
        assertThat(relay.publishPending(ADMIN)).isEqualTo(1);
        assertThat(broker.seen).containsExactly(1L, 2L);
        assertThat(relay.list(ADMIN, "true", null)).isEmpty();
        assertThat(relay.list(ADMIN, null, null)).hasSize(2);
        assertThat(relay.publishPending(ADMIN)).isZero();
        assertThat(codeOf(() -> relay.publishPending(new Principal(2, "CUSTOMER")))).isEqualTo(403);
    }
}
