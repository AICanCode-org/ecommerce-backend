package org.aicancode.shopflow.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.aicancode.shopflow.support.TestApp;
import org.aicancode.shopflow.support.TestApp.Res;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M4: the cart endpoints over HTTP. */
class ApiCartTest {

    private final TestApp api = new TestApp();

    @Test
    void needsAToken() {
        assertThat(api.get("/api/v1/cart").status()).isEqualTo(401);
        assertThat(api.call("PUT", "/api/v1/cart/items/X-1", Map.of("quantity", 1), (String) null).status()).isEqualTo(401);
    }

    @Test
    void returnsTheCartJson() {
        JsonNode session = api.customer();
        api.product(Map.of("sku", "MUG-1", "name", "Mug", "pricePaise", 45_000, "stock", 2));
        assertThat(api.add(session, "mug-1", 2).toString()).isEqualTo("{\"items\":[{\"sku\":\"MUG-1\",\"name\":\"Mug\","
                + "\"unitPricePaise\":45000,\"quantity\":2,\"lineTotalPaise\":90000,\"available\":true}],"
                + "\"couponCode\":null,\"couponApplied\":false,\"subtotalPaise\":90000,\"discountPaise\":0,"
                + "\"shippingPaise\":4900,\"totalPaise\":94900,\"total\":\"₹949.00\"}");
    }

    @Test
    void marksLinesUnavailableWhenStockRunsOut() {
        JsonNode session = api.customer();
        api.product(Map.of("sku", "MUG-1", "stock", 2));
        api.add(session, "MUG-1", 2);
        api.call("POST", "/api/v1/products/MUG-1/stock", Map.of("delta", -1), api.admin());
        assertThat(api.get("/api/v1/cart", session).json().at("/items/0/available").asBoolean()).isFalse();
    }

    @Test
    void appliesAndRemovesCoupons() {
        JsonNode session = api.customer();
        api.product(Map.of("sku", "MUG-1", "pricePaise", 60_000));
        api.add(session, "MUG-1", 1);
        Res r = api.call("PUT", "/api/v1/cart/coupon", Map.of("code", "flat100"), session);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("discountPaise").asLong()).isEqualTo(10_000);
        assertThat(r.json().get("couponCode").asString()).isEqualTo("FLAT100");
        Res bad = api.call("PUT", "/api/v1/cart/coupon", Map.of("code", "NOPE99"), session);
        assertThat(bad.status()).isEqualTo(404);
        assertThat(bad.json().get("detail").asString()).isEqualTo("coupon not found");
        assertThat(api.call("DELETE", "/api/v1/cart/coupon", null, session).json().get("couponCode").isNull()).isTrue();
    }

    @Test
    void deletesAnItem() {
        JsonNode session = api.customer();
        api.product(Map.of("sku", "MUG-1"));
        api.add(session, "MUG-1", 1);
        Res r = api.call("DELETE", "/api/v1/cart/items/MUG-1", null, session);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("items").size()).isZero();
    }
}
