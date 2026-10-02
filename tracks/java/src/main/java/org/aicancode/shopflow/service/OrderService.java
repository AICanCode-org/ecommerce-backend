package org.aicancode.shopflow.service;

import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import org.aicancode.shopflow.domain.Models.Order;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.repo.CartRepository;
import org.aicancode.shopflow.repo.OrderRepository;

/** Idempotent checkout, all-or-nothing stock, the order state machine. */
public class OrderService {

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
        // TODO(phase-3 M5): ADR-0004: validate the Idempotency-Key; an order with that key -> return it
        //   (replayed=true). Empty cart -> 409 cart-empty; inactive product -> 409 insufficient-stock. Price
        //   the cart, snapshot the lines and the coupon (only if applied), new payment id, place_order in ONE
        //   transaction. InsufficientStock -> 409; DuplicateError (same key raced us) -> return the winner,
        //   replayed.
        throw new UnsupportedOperationException("TODO(phase-3 M5): OrderService.checkout");
    }

    public Order get(Principal principal, long orderId) {
        // TODO(phase-3 M5): missing, or someone else's order (unless ADMIN) -> 404 not-found 'order' (never
        //   403: don't confirm it exists).
        throw new UnsupportedOperationException("TODO(phase-3 M5): OrderService.get");
    }

    public List<Order> mine(Principal principal, String limit) {
        // TODO(phase-3 M5): the caller's orders, newest first, validated limit.
        throw new UnsupportedOperationException("TODO(phase-3 M5): OrderService.mine");
    }

    public Order cancel(Principal principal, long orderId) {
        // TODO(phase-3 M5): get (404 rules) then apply 'cancel'.
        throw new UnsupportedOperationException("TODO(phase-3 M5): OrderService.cancel");
    }

    public List<Order> adminList(Principal principal, String status, String limit) {
        // TODO(phase-3 M5): ADMIN only; validated status filter and limit.
        throw new UnsupportedOperationException("TODO(phase-3 M5): OrderService.adminList");
    }

    public Order adminSetStatus(Principal principal, long orderId, Object payload) {
        // TODO(phase-3 M5): ADMIN only; validate status (SHIPPED or DELIVERED), map it to the action
        //   (ship/deliver), get, apply.
        throw new UnsupportedOperationException("TODO(phase-3 M5): OrderService.adminSetStatus");
    }

    public Order apply(Order order, String action) {
        // TODO(phase-3 M5): apply_transition(transition_for(order, action, now)); anything but 'ok' -> 409
        //   (the order changed meanwhile: compare-and-set on the status). Return the order read back.
        throw new UnsupportedOperationException("TODO(phase-3 M5): OrderService.apply");
    }
}
