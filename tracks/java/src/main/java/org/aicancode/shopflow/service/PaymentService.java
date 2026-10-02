package org.aicancode.shopflow.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aicancode.shopflow.domain.ApiException;
import org.aicancode.shopflow.domain.Models.NewEvent;
import org.aicancode.shopflow.domain.Models.Order;
import org.aicancode.shopflow.domain.Models.Payment;
import org.aicancode.shopflow.domain.Models.Transition;
import org.aicancode.shopflow.domain.Times;
import org.aicancode.shopflow.domain.Validation;
import org.aicancode.shopflow.repo.OrderRepository;
import org.aicancode.shopflow.security.Webhooks;
import tools.jackson.core.JacksonException;
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
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", eventId);
        m.put("status", status);
        return m;
    }

    public Map<String, Object> handleWebhook(byte[] body, String signature) {
        if (!Webhooks.verify(secret, body, signature)) {
            throw ApiException.invalidSignature();
        }
        Object parsed;
        try {
            parsed = JSON.readValue(body, Object.class);
        } catch (JacksonException e) {
            throw ApiException.validation("request body must be a JSON object");
        }
        Map<String, Object> data = Validation.requireObject(parsed);
        if (!(data.get("id") instanceof String eventId) || !Validation.IDEMPOTENCY_KEY.matcher(eventId).matches()) {
            throw ApiException.validation("id must be 8-64 characters: A-Z, a-z, 0-9, _ and -");
        }
        Object kind = data.get("type");
        if (!WEBHOOK_TYPES.contains(kind)) {
            throw ApiException.validation("type must be one of " + String.join(", ", WEBHOOK_TYPES));
        }
        if (!(data.get("paymentId") instanceof String paymentId) || paymentId.isEmpty()) {
            throw ApiException.validation("paymentId is required");
        }
        Payment payment = orders.findPayment(paymentId).orElseThrow(() -> ApiException.notFound("payment"));
        if (orders.hasPaymentEvent(eventId)) {
            return result(eventId, "duplicate");
        }
        Order order = orders.findOrder(payment.orderId()).orElseThrow();
        Instant now = Times.now(clock);
        String type = (String) kind;
        Transition t;
        String outcome = "processed";
        if (!"PENDING".equals(payment.status())) {
            // A second, different event for a payment we already settled: record it, change nothing.
            t = transition(order, eventId, type, payment, null, null, false, List.of(), now);
            outcome = "ignored";
        } else if ("payment.succeeded".equals(type) && "PENDING_PAYMENT".equals(order.status())) {
            List<NewEvent> events = Orders.transitionFor(order, "pay", now).events();
            t = transition(order, eventId, type, payment, "PAID", "SUCCEEDED", false, events, now);
        } else if ("payment.succeeded".equals(type)) {
            // Money arrived for an order that was cancelled meanwhile: keep the order, refund the customer.
            NewEvent refund = new NewEvent("refund.requested", Orders.payload(order, order.status()));
            t = transition(order, eventId, type, payment, null, "SUCCEEDED", false, List.of(refund), now);
        } else if ("PENDING_PAYMENT".equals(order.status())) {
            List<NewEvent> events = Orders.transitionFor(order, "fail", now).events();
            t = transition(order, eventId, type, payment, "CANCELLED", "FAILED", true, events, now);
        } else {
            t = transition(order, eventId, type, payment, null, "FAILED", false, List.of(), now);
        }
        String applied = orders.applyTransition(t);
        if ("duplicate".equals(applied)) {
            return result(eventId, "duplicate");
        }
        if ("conflict".equals(applied)) {
            throw Orders.concurrentChange(); // 409: the provider retries the webhook later
        }
        return result(eventId, outcome);
    }

    private static Transition transition(Order order, String eventId, String type, Payment payment, String toStatus,
                                         String paymentStatus, boolean restock, List<NewEvent> events, Instant at) {
        return new Transition(order.id(), order.status(), toStatus, at, restock, events, payment.id(), paymentStatus,
                eventId, type);
    }
}
