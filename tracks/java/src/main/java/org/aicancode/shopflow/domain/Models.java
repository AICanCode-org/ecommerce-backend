package org.aicancode.shopflow.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plain data objects shared by services and repositories (no framework imports). Money is always long paise. */
public final class Models {

    public static final List<String> ROLES = List.of("CUSTOMER", "ADMIN");
    public static final List<String> ORDER_STATUSES = List.of("PENDING_PAYMENT", "PAID", "SHIPPED", "DELIVERED", "CANCELLED");

    private Models() {
    }

    public record User(long id, String email, String passwordHash, String displayName, String role, Instant createdAt) {
        public User withRole(String newRole) {
            return new User(id, email, passwordHash, displayName, newRole, createdAt);
        }
    }

    /** Who is calling, as proven by a verified access token. */
    public record Principal(long userId, String role) {
        public boolean isAdmin() {
            return "ADMIN".equals(role);
        }
    }

    public record LoginResult(String accessToken, User user) {
    }

    public record Product(long id, String sku, String name, String description, String category, long pricePaise,
                          int stock, boolean active, int version, Instant createdAt, Instant updatedAt) {
        /** The stored row: a new id and version 1. */
        public Product withId(long newId) {
            return new Product(newId, sku, name, description, category, pricePaise, stock, active, 1, createdAt, updatedAt);
        }

        public Product withStock(int newStock, Instant at) {
            return new Product(id, sku, name, description, category, pricePaise, newStock, active, version, createdAt, at);
        }

        public Product withVersion(int newVersion, int currentStock) {
            return new Product(id, sku, name, description, category, pricePaise, currentStock, active, newVersion,
                    createdAt, updatedAt);
        }
    }

    /** Catalog filters. Only active products are ever listed. Ordered by id; afterId is the keyset cursor. */
    public record ProductQuery(int limit, Long afterId, String q, String category, Long minPricePaise, Long maxPricePaise,
                               Boolean inStock) {
        public ProductQuery withLimit(int newLimit) {
            return new ProductQuery(newLimit, afterId, q, category, minPricePaise, maxPricePaise, inStock);
        }
    }

    public record CategoryCount(String name, long products) {
    }

    /** kind: PERCENT (value = percent, capped by maxDiscountPaise) or FLAT (value = paise). */
    public record Coupon(String code, String kind, long value, long minSubtotalPaise, Long maxDiscountPaise, boolean active) {
    }

    public record CartLine(Product product, int quantity) {
    }

    public record CartState(List<CartLine> lines, String couponCode) {
    }

    public record Pricing(long subtotalPaise, long discountPaise, long shippingPaise, long totalPaise,
                          boolean couponApplied) {
    }

    public record CartView(List<CartLine> lines, String couponCode, Pricing pricing) {
    }

    public record OrderLine(long productId, String sku, String name, long unitPricePaise, int quantity) {
    }

    public record Payment(String id, long orderId, String provider, String status, long amountPaise, Instant createdAt,
                          Instant updatedAt) {
    }

    public record Order(long id, long userId, String status, List<OrderLine> lines, long subtotalPaise,
                        long discountPaise, long shippingPaise, long totalPaise, String couponCode, String idempotencyKey,
                        Payment payment, Instant createdAt, Instant updatedAt) {
    }

    /** Everything placeOrder needs to write in ONE transaction. */
    public record OrderDraft(long userId, String idempotencyKey, List<OrderLine> lines, Pricing pricing,
                             String couponCode, String paymentId, Instant at) {
    }

    public record NewEvent(String type, Map<String, Object> payload) {
    }

    /** One atomic state change: order status (compare-and-set), stock, payment, webhook event, outbox. */
    public record Transition(long orderId, String fromStatus, String toStatus, Instant at, boolean restock,
                             List<NewEvent> events, String paymentId, String paymentStatus, String webhookEventId,
                             String webhookEventType) {
    }

    public record OutboxEvent(long id, String type, long orderId, Map<String, Object> payload, Instant createdAt,
                              Instant publishedAt) {
    }

    public record SearchFilters(List<String> terms, String category, Long minPricePaise, Long maxPricePaise,
                                Boolean inStock) {
    }

    /** The payload of every order event (identical in every track). */
    public static Map<String, Object> orderEventPayload(long userId, long orderId, String status, long totalPaise) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", orderId);
        m.put("userId", userId);
        m.put("status", status);
        m.put("totalPaise", totalPaise);
        return m;
    }
}
