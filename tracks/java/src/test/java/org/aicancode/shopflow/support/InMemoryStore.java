package org.aicancode.shopflow.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.aicancode.shopflow.domain.DuplicateException;
import org.aicancode.shopflow.domain.InsufficientStockException;
import org.aicancode.shopflow.domain.Models;
import org.aicancode.shopflow.domain.Models.CartLine;
import org.aicancode.shopflow.domain.Models.CartState;
import org.aicancode.shopflow.domain.Models.CategoryCount;
import org.aicancode.shopflow.domain.Models.Coupon;
import org.aicancode.shopflow.domain.Models.NewEvent;
import org.aicancode.shopflow.domain.Models.Order;
import org.aicancode.shopflow.domain.Models.OrderDraft;
import org.aicancode.shopflow.domain.Models.OrderLine;
import org.aicancode.shopflow.domain.Models.OutboxEvent;
import org.aicancode.shopflow.domain.Models.Payment;
import org.aicancode.shopflow.domain.Models.Pricing;
import org.aicancode.shopflow.domain.Models.Product;
import org.aicancode.shopflow.domain.Models.ProductQuery;
import org.aicancode.shopflow.domain.Models.Transition;
import org.aicancode.shopflow.domain.Models.User;
import org.aicancode.shopflow.repo.CartRepository;
import org.aicancode.shopflow.repo.OrderRepository;
import org.aicancode.shopflow.repo.ProductRepository;
import org.aicancode.shopflow.repo.UserRepository;

/**
 * In-memory adapters for unit tests: one object implements every port, like one database holds all tables. Every
 * method is synchronized and checks everything before it writes, so each one is atomic like a transaction. Search is
 * plain word matching here; the integration tests cover the real PostgreSQL full-text search.
 */
public class InMemoryStore implements UserRepository, ProductRepository, CartRepository, OrderRepository {

    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
    public static final List<Coupon> DEMO_COUPONS = List.of(
            new Coupon("SAVE10", "PERCENT", 10, 0, 20000L, true),
            new Coupon("FLAT100", "FLAT", 10000, 50000, null, true),
            new Coupon("EXPIRED5", "PERCENT", 5, 0, null, false));

    public final Map<Long, User> users = new LinkedHashMap<>();
    public final Map<Long, Product> products = new LinkedHashMap<>();
    public final Map<String, Coupon> coupons = new LinkedHashMap<>();
    public final Map<Long, LinkedHashMap<Long, Integer>> cartItems = new LinkedHashMap<>();
    public final Map<Long, String> cartCoupons = new LinkedHashMap<>();
    public final Map<Long, Order> orders = new LinkedHashMap<>();
    public final Map<String, Payment> payments = new LinkedHashMap<>();
    public final Set<String> paymentEvents = new HashSet<>();
    public final List<OutboxEvent> events = new ArrayList<>();
    public volatile boolean healthy = true;
    private long userIds;
    private long productIds;
    private long orderIds;
    private long eventIds;

    public InMemoryStore() {
        DEMO_COUPONS.forEach(c -> coupons.put(c.code(), c));
    }

    private static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    // ---- users ----
    @Override
    public synchronized User insertUser(String email, String passwordHash, String displayName, String role, Instant at) {
        if (users.values().stream().anyMatch(u -> u.email().equals(email))) {
            throw new DuplicateException("email");
        }
        User user = new User(++userIds, email, passwordHash, displayName, role, at);
        users.put(user.id(), user);
        return user;
    }

    @Override
    public synchronized Optional<User> findUserByEmail(String email) {
        return users.values().stream().filter(u -> u.email().equals(email)).findFirst();
    }

    @Override
    public synchronized Optional<User> findUser(long id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public synchronized Optional<User> updateRole(long id, String role) {
        User user = users.get(id);
        if (user == null) {
            return Optional.empty();
        }
        users.put(id, user.withRole(role));
        return Optional.of(users.get(id));
    }

    @Override
    public boolean ping() {
        return healthy;
    }

    // ---- products ----
    @Override
    public synchronized Product insertProduct(Product product) {
        if (products.values().stream().anyMatch(p -> p.sku().equals(product.sku()))) {
            throw new DuplicateException("sku");
        }
        Product saved = product.withId(++productIds);
        products.put(saved.id(), saved);
        return saved;
    }

    @Override
    public synchronized Optional<Product> findProduct(String sku) {
        return products.values().stream().filter(p -> p.sku().equals(sku)).findFirst();
    }

    @Override
    public synchronized Optional<Product> updateProduct(Product product, int expectedVersion) {
        Product current = products.get(product.id());
        if (current == null || current.version() != expectedVersion) {
            return Optional.empty();
        }
        Product saved = product.withVersion(current.version() + 1, current.stock());
        products.put(saved.id(), saved);
        return Optional.of(saved);
    }

    @Override
    public synchronized Optional<Product> adjustStock(long productId, int delta, Instant at) {
        Product current = products.get(productId);
        if (current == null || current.stock() + delta < 0) {
            return Optional.empty();
        }
        Product saved = current.withStock(current.stock() + delta, at);
        products.put(productId, saved);
        return Optional.of(saved);
    }

    @Override
    public synchronized List<Product> searchProducts(ProductQuery q) {
        Set<String> wanted = q.q() == null ? Set.of() : words(q.q());
        List<Product> out = new ArrayList<>();
        for (Product p : products.values().stream().sorted(Comparator.comparingLong(Product::id)).toList()) {
            if (!p.active() || (q.afterId() != null && p.id() <= q.afterId())
                    || !words(p.name() + " " + p.description()).containsAll(wanted)
                    || (q.category() != null && !p.category().equals(q.category()))
                    || (q.minPricePaise() != null && p.pricePaise() < q.minPricePaise())
                    || (q.maxPricePaise() != null && p.pricePaise() > q.maxPricePaise())
                    || (q.inStock() != null && (p.stock() > 0) != q.inStock())) {
                continue;
            }
            out.add(p);
            if (out.size() == q.limit()) {
                break;
            }
        }
        return out;
    }

    @Override
    public synchronized List<CategoryCount> categories() {
        Map<String, Long> counts = new TreeMap<>();
        products.values().stream().filter(Product::active).forEach(p -> counts.merge(p.category(), 1L, Long::sum));
        return counts.entrySet().stream().map(e -> new CategoryCount(e.getKey(), e.getValue())).toList();
    }

    // ---- carts ----
    @Override
    public synchronized CartState cart(long userId) {
        Map<Long, Integer> items = cartItems.getOrDefault(userId, new LinkedHashMap<>());
        List<CartLine> lines = items.entrySet().stream()
                .map(e -> new CartLine(products.get(e.getKey()), e.getValue())).toList();
        return new CartState(lines, cartCoupons.get(userId));
    }

    @Override
    public synchronized void setCartItem(long userId, long productId, int quantity, Instant at) {
        cartItems.computeIfAbsent(userId, k -> new LinkedHashMap<>()).put(productId, quantity);
    }

    @Override
    public synchronized void removeCartItem(long userId, long productId) {
        Map<Long, Integer> items = cartItems.get(userId);
        if (items != null) {
            items.remove(productId);
        }
    }

    @Override
    public synchronized void setCoupon(long userId, String code, Instant at) {
        cartCoupons.put(userId, code);
    }

    @Override
    public synchronized Optional<Coupon> findCoupon(String code) {
        return Optional.ofNullable(coupons.get(code));
    }

    // ---- orders, payments, outbox ----
    private Order withPayment(Order o) {
        return new Order(o.id(), o.userId(), o.status(), o.lines(), o.subtotalPaise(), o.discountPaise(),
                o.shippingPaise(), o.totalPaise(), o.couponCode(), o.idempotencyKey(), payments.get(o.payment().id()),
                o.createdAt(), o.updatedAt());
    }

    @Override
    public synchronized Optional<Order> findOrderByKey(long userId, String key) {
        return orders.values().stream().filter(o -> o.userId() == userId && o.idempotencyKey().equals(key))
                .findFirst().map(this::withPayment);
    }

    @Override
    public synchronized Order placeOrder(OrderDraft d) {
        if (orders.values().stream().anyMatch(o -> o.userId() == d.userId()
                && o.idempotencyKey().equals(d.idempotencyKey()))) {
            throw new DuplicateException("idempotency_key");
        }
        for (OrderLine line : d.lines()) { // check everything first: all or nothing, like a transaction
            Product p = products.get(line.productId());
            if (p == null || !p.active() || p.stock() < line.quantity()) {
                throw new InsufficientStockException(line.sku());
            }
        }
        for (OrderLine line : d.lines()) {
            Product p = products.get(line.productId());
            products.put(p.id(), p.withStock(p.stock() - line.quantity(), d.at()));
        }
        long id = ++orderIds;
        Pricing pr = d.pricing();
        Payment payment = new Payment(d.paymentId(), id, PAYMENT_PROVIDER, "PENDING", pr.totalPaise(), d.at(), d.at());
        Order order = new Order(id, d.userId(), "PENDING_PAYMENT", List.copyOf(d.lines()), pr.subtotalPaise(),
                pr.discountPaise(), pr.shippingPaise(), pr.totalPaise(), d.couponCode(), d.idempotencyKey(), payment,
                d.at(), d.at());
        payments.put(payment.id(), payment);
        orders.put(id, order);
        event("order.placed", id, Models.orderEventPayload(d.userId(), id, "PENDING_PAYMENT", pr.totalPaise()), d.at());
        cartItems.remove(d.userId());
        cartCoupons.remove(d.userId());
        return withPayment(order);
    }

    private void event(String type, long orderId, Map<String, Object> payload, Instant at) {
        events.add(new OutboxEvent(++eventIds, type, orderId, new LinkedHashMap<>(payload), at, null));
    }

    @Override
    public synchronized Optional<Order> findOrder(long id) {
        return Optional.ofNullable(orders.get(id)).map(this::withPayment);
    }

    @Override
    public synchronized List<Order> ordersForUser(long userId, int limit) {
        return orders.values().stream().filter(o -> o.userId() == userId)
                .sorted(Comparator.comparingLong(Order::id).reversed()).limit(limit).map(this::withPayment).toList();
    }

    @Override
    public synchronized List<Order> ordersByStatus(String status, int limit) {
        return orders.values().stream().filter(o -> status == null || o.status().equals(status))
                .sorted(Comparator.comparingLong(Order::id).reversed()).limit(limit).map(this::withPayment).toList();
    }

    @Override
    public synchronized Optional<Payment> findPayment(String paymentId) {
        return Optional.ofNullable(payments.get(paymentId));
    }

    @Override
    public synchronized boolean hasPaymentEvent(String eventId) {
        return paymentEvents.contains(eventId);
    }

    @Override
    public synchronized String applyTransition(Transition t) {
        if (t.webhookEventId() != null && paymentEvents.contains(t.webhookEventId())) {
            return "duplicate";
        }
        Order order = orders.get(t.orderId());
        if (t.paymentStatus() != null && !"PENDING".equals(payments.get(t.paymentId()).status())) {
            return "conflict";
        }
        if (t.toStatus() != null && !order.status().equals(t.fromStatus())) {
            return "conflict";
        }
        // every check passed: now write (all or nothing)
        if (t.webhookEventId() != null) {
            paymentEvents.add(t.webhookEventId());
        }
        if (t.paymentStatus() != null) {
            Payment p = payments.get(t.paymentId());
            payments.put(p.id(), new Payment(p.id(), p.orderId(), p.provider(), t.paymentStatus(), p.amountPaise(),
                    p.createdAt(), t.at()));
        }
        if (t.toStatus() != null) {
            orders.put(order.id(), new Order(order.id(), order.userId(), t.toStatus(), order.lines(),
                    order.subtotalPaise(), order.discountPaise(), order.shippingPaise(), order.totalPaise(),
                    order.couponCode(), order.idempotencyKey(), order.payment(), order.createdAt(), t.at()));
        }
        if (t.restock()) {
            for (OrderLine line : order.lines()) {
                Product p = products.get(line.productId());
                products.put(p.id(), p.withStock(p.stock() + line.quantity(), p.updatedAt()));
            }
        }
        for (NewEvent e : t.events()) {
            event(e.type(), t.orderId(), e.payload(), t.at());
        }
        return "ok";
    }

    @Override
    public synchronized List<OutboxEvent> outbox(boolean pendingOnly, int limit) {
        return events.stream().filter(e -> !pendingOnly || e.publishedAt() == null).limit(limit).toList();
    }

    @Override
    public synchronized void markPublished(long eventId, Instant at) {
        for (int i = 0; i < events.size(); i++) {
            OutboxEvent e = events.get(i);
            if (e.id() == eventId && e.publishedAt() == null) {
                events.set(i, new OutboxEvent(e.id(), e.type(), e.orderId(), e.payload(), e.createdAt(), at));
            }
        }
    }

    /** Test helper: force an order into a status (e.g. "a webhook won the race"). */
    public synchronized void setOrderStatus(long orderId, String status) {
        Order o = orders.get(orderId);
        orders.put(orderId, new Order(o.id(), o.userId(), status, o.lines(), o.subtotalPaise(), o.discountPaise(),
                o.shippingPaise(), o.totalPaise(), o.couponCode(), o.idempotencyKey(), o.payment(), o.createdAt(),
                o.updatedAt()));
    }

    /** Test helper: change a user's role directly (there is no role endpoint in ShopFlow). */
    public synchronized void setRole(long userId, String role) {
        updateRole(userId, role);
    }
}
