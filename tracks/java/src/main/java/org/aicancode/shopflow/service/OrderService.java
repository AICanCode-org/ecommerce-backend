package org.aicancode.shopflow.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.aicancode.shopflow.domain.ApiException;
import org.aicancode.shopflow.domain.DuplicateException;
import org.aicancode.shopflow.domain.InsufficientStockException;
import org.aicancode.shopflow.domain.Models.CartLine;
import org.aicancode.shopflow.domain.Models.CartState;
import org.aicancode.shopflow.domain.Models.Coupon;
import org.aicancode.shopflow.domain.Models.Order;
import org.aicancode.shopflow.domain.Models.OrderDraft;
import org.aicancode.shopflow.domain.Models.OrderLine;
import org.aicancode.shopflow.domain.Models.Pricing;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.domain.Money;
import org.aicancode.shopflow.domain.Times;
import org.aicancode.shopflow.domain.Validation;
import org.aicancode.shopflow.repo.CartRepository;
import org.aicancode.shopflow.repo.OrderRepository;

/** Idempotent checkout, all-or-nothing stock, the order state machine. */
public class OrderService {

    /** v1.1: how long an unpaid order holds its stock. */
    public static final Duration RESERVATION_TTL = Duration.ofMinutes(15);
    static final int EXPIRE_BATCH = 100;

    private final OrderRepository orders;
    private final CartRepository carts;
    private final Clock clock;
    private final Supplier<String> newPaymentId;

    public OrderService(OrderRepository orders, CartRepository carts, Clock clock, Supplier<String> newPaymentId) {
        this.orders = orders;
        this.carts = carts;
        this.clock = clock;
        this.newPaymentId = newPaymentId;
    }

    /** The order, and whether it is a replay of an earlier checkout with the same key. */
    public record Checkout(Order order, boolean replayed) {
    }

    /** The same Idempotency-Key always returns the same order (ADR-0004). */
    public Checkout checkout(Principal principal, String idempotencyKey) {
        String key = Validation.idempotencyKey(idempotencyKey);
        Order existing = orders.findOrderByKey(principal.userId(), key).orElse(null);
        if (existing != null) {
            return new Checkout(existing, true);
        }
        CartState state = carts.cart(principal.userId());
        if (state.lines().isEmpty()) {
            throw ApiException.cartEmpty();
        }
        for (CartLine line : state.lines()) {
            if (!line.product().active()) {
                throw ApiException.insufficientStock(line.product().sku() + " is no longer available");
            }
        }
        Coupon coupon = state.couponCode() == null ? null : carts.findCoupon(state.couponCode()).orElse(null);
        Pricing pricing = Money.priceCart(CartService.priced(state.lines()), coupon);
        List<OrderLine> lines = state.lines().stream().map(x -> new OrderLine(x.product().id(), x.product().sku(),
                x.product().name(), x.product().pricePaise(), x.quantity())).toList();
        Instant now = Times.now(clock);
        OrderDraft draft = new OrderDraft(principal.userId(), key, lines, pricing,
                pricing.couponApplied() ? state.couponCode() : null, newPaymentId.get(), now,
                now.plus(RESERVATION_TTL));
        try {
            return new Checkout(orders.placeOrder(draft), false);
        } catch (InsufficientStockException e) {
            throw ApiException.insufficientStock("not enough stock for " + e.sku());
        } catch (DuplicateException e) {
            // The same key raced us (double click, retry after a timeout): return the winner's order.
            return new Checkout(orders.findOrderByKey(principal.userId(), key).orElseThrow(() -> e), true);
        }
    }

    public Order get(Principal principal, long orderId) {
        Order order = orders.findOrder(orderId).orElse(null);
        if (order == null || (order.userId() != principal.userId() && !principal.isAdmin())) {
            throw ApiException.notFound("order"); // someone else's order is "not found", never "forbidden"
        }
        return order;
    }

    public List<Order> mine(Principal principal, String limit) {
        return orders.ordersForUser(principal.userId(), Validation.limit(limit));
    }

    public Order cancel(Principal principal, long orderId) {
        return apply(get(principal, orderId), "cancel");
    }

    public List<Order> adminList(Principal principal, String status, String limit) {
        AuthService.requireRole(principal, "ADMIN");
        return orders.ordersByStatus(Validation.orderStatusFilter(status), Validation.limit(limit));
    }

    public Order adminSetStatus(Principal principal, long orderId, Object payload) {
        AuthService.requireRole(principal, "ADMIN");
        String target = Validation.orderStatusTarget(Validation.requireObject(payload).get("status"));
        return apply(get(principal, orderId), Orders.ACTION_FOR_TARGET.get(target));
    }

    /**
     * v1.1: cancel unpaid orders whose payment window closed and put their stock back. Safe to run any time and
     * from several places at once: each order goes through the same compare-and-set transition, so an order paid
     * a millisecond ago is simply skipped.
     */
    public int expireOverdue(Principal principal) {
        AuthService.requireRole(principal, "ADMIN");
        Instant now = Times.now(clock);
        int expired = 0;
        for (Order order : orders.expiredOrders(now, EXPIRE_BATCH)) {
            if ("ok".equals(orders.applyTransition(Orders.transitionFor(order, "expire", now)))) {
                expired++;
            }
        }
        return expired;
    }

    public Order apply(Order order, String action) {
        String result = orders.applyTransition(Orders.transitionFor(order, action, Times.now(clock)));
        if (!"ok".equals(result)) {
            throw Orders.concurrentChange();
        }
        return orders.findOrder(order.id()).orElseThrow();
    }
}
