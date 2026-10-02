package org.aicancode.shopflow.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.aicancode.shopflow.domain.Models.NewEvent;
import org.aicancode.shopflow.domain.Models.Order;
import org.aicancode.shopflow.domain.Models.Payment;
import org.aicancode.shopflow.domain.Models.Transition;
import org.aicancode.shopflow.repo.OrderRepository;
import tools.jackson.databind.json.JsonMapper;

/** The fake payment provider calls POST /api/v1/payments/webhook. Never trust the browser's "paid!". */
public class PaymentService {

    public static final List<String> WEBHOOK_TYPES = List.of("payment.succeeded", "payment.failed");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final OrderRepository orders;
    private final String secret;
    private final Clock clock;

    public PaymentService(OrderRepository orders, String secret, Clock clock) {
        this.orders = orders;
        this.secret = secret;
        this.clock = clock;
    }

    private static Map<String, Object> result(String eventId, String status) {
        // TODO(phase-3 M5): helper: the {eventId, status} answer.
        throw new UnsupportedOperationException("TODO(phase-3 M5): PaymentService.result");
    }

    public Map<String, Object> handleWebhook(byte[] body, String signature) {
        // TODO(phase-3 M5): verify the signature on the RAW body FIRST (401 invalid-signature); then JSON
        //   object, id (8-64 chars), type (payment.succeeded / payment.failed), paymentId; unknown payment ->
        //   404; a seen event id -> {status: duplicate}. Decide the transition (see the payments tests: pay,
        //   late payment -> refund.requested, fail -> cancel + restock, settled -> ignored) and apply it
        //   atomically together with recording the event; 'duplicate' -> duplicate, 'conflict' -> 409.
        throw new UnsupportedOperationException("TODO(phase-3 M5): PaymentService.handleWebhook");
    }

    private static Transition transition(Order order, String eventId, String type, Payment payment, String toStatus,
                                         String paymentStatus, boolean restock, List<NewEvent> events, Instant at) {
        // TODO(phase-3 M5): helper: a Transition for this payment event (order, from status, webhook event
        //   id/type, payment id).
        throw new UnsupportedOperationException("TODO(phase-3 M5): PaymentService.transition");
    }
}
