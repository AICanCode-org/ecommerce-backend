package org.aicancode.shopflow.service;

import static org.aicancode.shopflow.support.Problems.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.aicancode.shopflow.domain.ApiException;
import org.aicancode.shopflow.domain.Models.Order;
import org.aicancode.shopflow.domain.Models.OutboxEvent;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.support.InMemoryStore;
import org.aicancode.shopflow.support.MutableClock;
import org.aicancode.shopflow.support.TestApp;
import org.aicancode.shopflow.support.TestApp.Res;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** v1.1 (Phase 7): unpaid orders hold their stock for 15 minutes, then an admin job cancels them. */
class ExpiryTest {

    private static final Principal ADMIN = new Principal(1, "ADMIN");
    private static final Principal ASHA = new Principal(2, "CUSTOMER");

    /** An in-memory store that can replay a stale "expired orders" answer (a webhook wins the race). */
    private static final class RacyStore extends InMemoryStore {
        List<Order> stale;

        @Override
        public synchronized List<Order> expiredOrders(Instant now, int limit) {
            return stale != null ? stale : super.expiredOrders(now, limit);
        }
    }

    private final RacyStore store = new RacyStore();
    private final MutableClock clock = new MutableClock();
    private final CatalogService catalog = new CatalogService(store, clock);
    private final CartService cart = new CartService(store, store, clock);
    private final OrderService svc = new OrderService(store, store, clock, TestApp.paymentIds());

    ExpiryTest() {
        catalog.create(ADMIN, Map.of("sku", "TEE-1", "name", "Tee", "category", "apparel", "pricePaise", 29_900,
                "stock", 3));
    }

    private Order checkout(int quantity, String key) {
        cart.setItem(ASHA, "TEE-1", Map.of("quantity", quantity));
        return svc.checkout(ASHA, key).order();
    }

    private int stock() {
        return catalog.get(ADMIN, "TEE-1").stock();
    }

    @Test
    void everyOrderGetsAFifteenMinutePaymentWindow() {
        Order order = checkout(1, "expiry-key-0001");
        assertThat(OrderService.RESERVATION_TTL).isEqualTo(Duration.ofMinutes(15));
        assertThat(order.expiresAt()).isEqualTo(clock.instant().plus(OrderService.RESERVATION_TTL));
    }

    @Test
    void cancelsOnlyOverdueUnpaidOrdersAndRestocksThem() {
        Order old = checkout(2, "expiry-key-0001");
        clock.advance(Duration.ofMinutes(10));
        Order fresh = checkout(1, "expiry-key-0002");
        assertThat(stock()).isZero();
        clock.advance(Duration.ofSeconds(4 * 60 + 59));
        assertThat(svc.expireOverdue(ADMIN)).isZero(); // 14:59 after the first checkout: still open
        clock.advance(Duration.ofSeconds(1));
        assertThat(svc.expireOverdue(ADMIN)).isEqualTo(1); // exactly 15:00: the window is closed
        assertThat(store.orders.get(old.id()).status()).isEqualTo("CANCELLED");
        assertThat(store.orders.get(fresh.id()).status()).isEqualTo("PENDING_PAYMENT");
        assertThat(stock()).isEqualTo(2);
        assertThat(store.events.stream().map(OutboxEvent::type))
                .containsExactly("order.placed", "order.placed", "order.cancelled");
        assertThat(svc.expireOverdue(ADMIN)).isZero(); // running it again changes nothing
    }

    @Test
    void neverExpiresAPaidOrder() {
        Order order = checkout(1, "expiry-key-0001");
        store.setOrderStatus(order.id(), "PAID");
        clock.advance(Duration.ofHours(1));
        assertThat(svc.expireOverdue(ADMIN)).isZero();
        assertThat(store.orders.get(order.id()).status()).isEqualTo("PAID");
    }

    @Test
    void isForAdminsOnly() {
        assertThat(codeOf(() -> svc.expireOverdue(ASHA))).isEqualTo(403);
    }

    @Test
    void skipsAnOrderTheWebhookPaidAMomentAgo() {
        Order order = checkout(1, "expiry-key-0001");
        clock.advance(Duration.ofMinutes(20));
        store.stale = new ArrayList<>(store.expiredOrders(clock.instant(), 10));
        store.setOrderStatus(order.id(), "PAID"); // the payment webhook won the race
        assertThat(svc.expireOverdue(ADMIN)).isZero();
        assertThat(store.orders.get(order.id()).status()).isEqualTo("PAID");
    }

    @Test
    void isAStateMachineTransition() {
        assertThat(Orders.nextStatus("PENDING_PAYMENT", "expire")).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> Orders.nextStatus("PAID", "expire")).isInstanceOf(ApiException.class);
    }

    @Test
    void theEndpointExpiresOrdersAndALatePaymentIsRefundedNotResurrected() {
        TestApp app = new TestApp();
        JsonNode asha = app.customer();
        JsonNode order = app.order(asha, "checkout-key-0001", Map.of("stock", 1));
        assertThat(order.get("expiresAt").asString()).isEqualTo("2026-10-02T09:45:00.000Z");
        String url = "/api/v1/admin/orders/expire";
        assertThat(app.call("POST", url, null, asha).status()).isEqualTo(403);
        assertThat(app.call("POST", url, null, (String) null).status()).isEqualTo(401);
        assertThat(app.call("POST", url, null, app.admin()).json().get("expired").asInt()).isZero();
        app.clock.advance(Duration.ofMinutes(15));
        assertThat(app.call("POST", url, null, app.admin()).json().get("expired").asInt()).isEqualTo(1);
        JsonNode after = app.get("/api/v1/orders/" + TestApp.id(order), asha).json();
        assertThat(after.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(after.at("/payment/status").asString()).isEqualTo("PENDING");
        String sku = order.at("/items/0/sku").asString();
        assertThat(app.get("/api/v1/products/" + sku).json().get("stock").asInt()).isEqualTo(1);
        Res paid = app.pay(order);
        assertThat(paid.json().get("status").asString()).isEqualTo("processed");
        JsonNode events = app.get("/api/v1/admin/outbox", app.admin()).json().get("items");
        List<String> types = new ArrayList<>();
        events.forEach(e -> types.add(e.get("type").asString()));
        assertThat(types.subList(types.size() - 2, types.size())).containsExactly("order.cancelled", "refund.requested");
    }
}
