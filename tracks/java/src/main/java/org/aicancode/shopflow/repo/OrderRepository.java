package org.aicancode.shopflow.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.aicancode.shopflow.domain.Models.Order;
import org.aicancode.shopflow.domain.Models.OrderDraft;
import org.aicancode.shopflow.domain.Models.OutboxEvent;
import org.aicancode.shopflow.domain.Models.Payment;
import org.aicancode.shopflow.domain.Models.Transition;

/** Persistence port for orders, payments and the outbox. */
public interface OrderRepository {

    String PAYMENT_PROVIDER = "fakepay";

    Optional<Order> findOrderByKey(long userId, String key);

    /**
     * ONE transaction: stock, order, lines, payment, outbox, empty cart. Throws InsufficientStockException or
     * DuplicateException (same idempotency key) and then nothing is written.
     */
    Order placeOrder(OrderDraft draft);

    Optional<Order> findOrder(long id);

    List<Order> ordersForUser(long userId, int limit);

    List<Order> ordersByStatus(String status, int limit);

    /** v1.1: unpaid orders whose payment window closed at or before {@code now}, oldest first. */
    List<Order> expiredOrders(Instant now, int limit);

    Optional<Payment> findPayment(String paymentId);

    boolean hasPaymentEvent(String eventId);

    /** ONE transaction. Returns "ok", "duplicate" (webhook event seen before) or "conflict" (compare-and-set lost). */
    String applyTransition(Transition t);

    List<OutboxEvent> outbox(boolean pendingOnly, int limit);

    void markPublished(long eventId, Instant at);
}
