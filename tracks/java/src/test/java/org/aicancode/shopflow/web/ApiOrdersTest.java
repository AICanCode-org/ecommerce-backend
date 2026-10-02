package org.aicancode.shopflow.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.aicancode.shopflow.support.TestApp;
import org.aicancode.shopflow.support.TestApp.Res;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M5: checkout, orders, the webhook and the outbox over HTTP. */
class ApiOrdersTest {

    private final TestApp api = new TestApp();

    private static List<Long> ids(JsonNode items) {
        List<Long> out = new ArrayList<>();
        items.forEach(o -> out.add(o.get("id").asLong()));
        return out;
    }

    @Test
    void answers201ForACheckoutAnd200ForItsReplay() {
        JsonNode session = api.customer();
        api.product(Map.of("sku", "MUG-1", "name", "Mug", "pricePaise", 45_000, "stock", 2));
        api.add(session, "MUG-1", 1);
        Res r = api.checkout(session, "checkout-key-0001");
        assertThat(r.status()).isEqualTo(201);
        long id = r.json().get("id").asLong();
        assertThat(r.header("Location")).isEqualTo("/api/v1/orders/" + id);
        assertThat(r.header("Idempotent-Replayed")).isNull();
        assertThat(r.json().toString()).isEqualTo("{\"id\":" + id + ",\"userId\":" + session.at("/user/id").asLong()
                + ",\"status\":\"PENDING_PAYMENT\",\"items\":[{\"sku\":\"MUG-1\",\"name\":\"Mug\",\"unitPricePaise\":45000,"
                + "\"quantity\":1,\"lineTotalPaise\":45000}],\"subtotalPaise\":45000,\"discountPaise\":0,"
                + "\"shippingPaise\":4900,\"totalPaise\":49900,\"total\":\"₹499.00\",\"couponCode\":null,"
                + "\"payment\":{\"id\":\"pay_test_1\",\"provider\":\"fakepay\",\"status\":\"PENDING\",\"amountPaise\":49900},"
                + "\"createdAt\":\"2026-10-02T09:30:00.000Z\",\"updatedAt\":\"2026-10-02T09:30:00.000Z\"}");
        Res again = api.checkout(session, "checkout-key-0001");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(again.json()).isEqualTo(r.json());
    }

    @Test
    void mapsCheckoutErrors() {
        JsonNode session = api.customer();
        Res r = api.call("POST", "/api/v1/checkout", null, session);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().get("detail").asString()).isEqualTo("Idempotency-Key header is required");
        assertThat(api.checkout(session, "checkout-key-0001").status()).isEqualTo(409);
        Res anonymous = api.call("POST", "/api/v1/checkout", null, null, Map.of("Idempotency-Key", "checkout-key-0001"));
        assertThat(anonymous.status()).isEqualTo(401);
    }

    @Test
    void servesTheOrderEndpoints() {
        JsonNode asha = api.customer();
        JsonNode ravi = api.customer();
        JsonNode order = api.order(asha);
        String url = "/api/v1/orders/" + order.get("id").asLong();
        assertThat(api.get(url, asha).json().get("id")).isEqualTo(order.get("id"));
        assertThat(api.get(url, ravi).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/orders/abc", asha).status()).isEqualTo(404);
        assertThat(ids(api.get("/api/v1/orders", asha).json().get("items"))).containsExactly(order.get("id").asLong());
        Res r = api.call("POST", url + "/cancel", null, asha);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("status").asString()).isEqualTo("CANCELLED");
        Res again = api.call("POST", url + "/cancel", null, asha);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.json().get("type").asString()).endsWith("/invalid-transition");
    }

    @Test
    void takesTheWebhookOverHttp() {
        JsonNode order = api.order(api.customer());
        Res r = api.pay(order);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().toString()).isEqualTo("{\"eventId\":\"evt_test_0001\",\"status\":\"processed\"}");
        assertThat(api.pay(order).json().get("status").asString()).isEqualTo("duplicate");
        Res bad = api.webhook(Map.of("id", "evt_test_0002", "type", "payment.failed", "paymentId", "x"), null, "sha256=00");
        assertThat(bad.status()).isEqualTo(401);
        assertThat(bad.json().get("type").asString()).endsWith("/invalid-signature");
        JsonNode seen = api.get("/api/v1/orders/" + order.get("id").asLong(), api.admin()).json();
        assertThat(seen.at("/payment/status").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    void runsTheAdminFulfilmentFlow() {
        JsonNode order = api.order(api.customer());
        api.pay(order);
        String url = "/api/v1/admin/orders/" + order.get("id").asLong() + "/status";
        assertThat(api.call("POST", url, Map.of("status", "SHIPPED"), api.admin()).json().get("status").asString())
                .isEqualTo("SHIPPED");
        assertThat(api.call("POST", url, Map.of("status", "PAID"), api.admin()).status()).isEqualTo(400);
        assertThat(api.call("POST", url, Map.of("status", "DELIVERED"), api.admin()).json().get("status").asString())
                .isEqualTo("DELIVERED");
        assertThat(ids(api.get("/api/v1/admin/orders?status=DELIVERED", api.admin()).json().get("items")))
                .containsExactly(order.get("id").asLong());
        assertThat(api.get("/api/v1/admin/orders", api.customer()).status()).isEqualTo(403);
    }

    @Test
    void servesTheOutboxEndpoints() {
        JsonNode order = api.order(api.customer());
        long id = order.get("id").asLong();
        api.pay(order);
        JsonNode events = api.get("/api/v1/admin/outbox?pending=true", api.admin()).json().get("items");
        List<List<Object>> seen = new ArrayList<>();
        events.forEach(e -> seen.add(List.of(e.get("type").asString(), e.get("orderId").asLong(),
                e.get("publishedAt").isNull())));
        assertThat(seen).containsExactly(List.of("order.placed", id, true), List.of("order.paid", id, true));
        assertThat(events.get(1).get("payload").toString()).isEqualTo("{\"orderId\":" + id + ",\"userId\":"
                + order.get("userId").asLong() + ",\"status\":\"PAID\",\"totalPaise\":" + order.get("totalPaise").asLong()
                + "}");
        Res published = api.call("POST", "/api/v1/admin/outbox/publish", null, api.admin());
        assertThat(published.json().toString()).isEqualTo("{\"published\":2}");
        assertThat(api.get("/api/v1/admin/outbox?pending=true", api.admin()).json().get("items").size()).isZero();
        api.get("/api/v1/admin/outbox", api.admin()).json().get("items")
                .forEach(e -> assertThat(e.get("publishedAt").asString()).isEqualTo("2026-10-02T09:30:00.000Z"));
        assertThat(api.call("POST", "/api/v1/admin/outbox/publish", null, api.customer()).status()).isEqualTo(403);
    }
}
