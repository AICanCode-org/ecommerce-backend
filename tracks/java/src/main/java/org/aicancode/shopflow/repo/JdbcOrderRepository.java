package org.aicancode.shopflow.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.aicancode.shopflow.domain.DuplicateException;
import org.aicancode.shopflow.domain.InsufficientStockException;
import org.aicancode.shopflow.domain.Models;
import org.aicancode.shopflow.domain.Models.NewEvent;
import org.aicancode.shopflow.domain.Models.Order;
import org.aicancode.shopflow.domain.Models.OrderDraft;
import org.aicancode.shopflow.domain.Models.OrderLine;
import org.aicancode.shopflow.domain.Models.OutboxEvent;
import org.aicancode.shopflow.domain.Models.Payment;
import org.aicancode.shopflow.domain.Models.Pricing;
import org.aicancode.shopflow.domain.Models.Transition;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;

/** PostgreSQL adapter for orders, payments and the outbox. The two writes that matter are ONE transaction each. */
public class JdbcOrderRepository implements OrderRepository {

    static final String ORDER_SELECT = """
            SELECT o.id, o.user_id, o.status, o.subtotal_paise, o.discount_paise, o.shipping_paise, o.total_paise,
                   o.coupon_code, o.idempotency_key, o.created_at, o.updated_at,
                   p.id AS payment_id, p.provider, p.status AS payment_status, p.amount_paise,
                   p.created_at AS payment_created_at, p.updated_at AS payment_updated_at,
                   COALESCE((SELECT json_agg(json_build_object('product_id', i.product_id, 'sku', i.sku, 'name', i.name,
                             'unit_price_paise', i.unit_price_paise, 'quantity', i.quantity) ORDER BY i.line_no)
                             FROM order_items i WHERE i.order_id = o.id), '[]') AS lines
            FROM orders o JOIN payments p ON p.order_id = o.id
            """;
    private static final TypeReference<List<Map<String, Object>>> LINES = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JdbcOrderRepository(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Thrown inside a transaction to roll it back; mapped to the "duplicate" / "conflict" results. */
    private static final class Abort extends RuntimeException {
        private final String result;

        Abort(String result) {
            super(result, null, false, false);
            this.result = result;
        }
    }

    private static Order order(ResultSet rs, int row) throws SQLException {
        Payment payment = new Payment(rs.getString("payment_id"), rs.getLong("id"), rs.getString("provider"),
                rs.getString("payment_status"), rs.getLong("amount_paise"), Jdbc.instant(rs, "payment_created_at"),
                Jdbc.instant(rs, "payment_updated_at"));
        List<OrderLine> lines = new ArrayList<>();
        for (Map<String, Object> l : Jdbc.JSON.readValue(rs.getString("lines"), LINES)) {
            lines.add(new OrderLine(((Number) l.get("product_id")).longValue(), (String) l.get("sku"),
                    (String) l.get("name"), ((Number) l.get("unit_price_paise")).longValue(),
                    ((Number) l.get("quantity")).intValue()));
        }
        return new Order(rs.getLong("id"), rs.getLong("user_id"), rs.getString("status"), List.copyOf(lines),
                rs.getLong("subtotal_paise"), rs.getLong("discount_paise"), rs.getLong("shipping_paise"),
                rs.getLong("total_paise"), rs.getString("coupon_code"), rs.getString("idempotency_key"), payment,
                Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "updated_at"));
    }

    @Override
    public Optional<Order> findOrderByKey(long userId, String key) {
        return jdbc.sql(ORDER_SELECT + "WHERE o.user_id = ? AND o.idempotency_key = ?").params(userId, key)
                .query(JdbcOrderRepository::order).optional();
    }

    @Override
    public Order placeOrder(OrderDraft d) {
        try {
            return tx.execute(status -> {
                // Lock rows in a fixed order (product id) so two checkouts can never deadlock.
                List<OrderLine> sorted = d.lines().stream().sorted(Comparator.comparingLong(OrderLine::productId)).toList();
                for (OrderLine line : sorted) {
                    int changed = jdbc.sql("UPDATE products SET stock = stock - ?, updated_at = ? "
                                    + "WHERE id = ? AND active AND stock >= ?")
                            .params(line.quantity(), Jdbc.ts(d.at()), line.productId(), line.quantity()).update();
                    if (changed != 1) {
                        throw new InsufficientStockException(line.sku()); // rolls back the whole transaction
                    }
                }
                Pricing p = d.pricing();
                long orderId = jdbc.sql("INSERT INTO orders (user_id, status, subtotal_paise, discount_paise, "
                                + "shipping_paise, total_paise, coupon_code, idempotency_key, created_at, updated_at) "
                                + "VALUES (?, 'PENDING_PAYMENT', ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")
                        .params(d.userId(), p.subtotalPaise(), p.discountPaise(), p.shippingPaise(), p.totalPaise(),
                                d.couponCode(), d.idempotencyKey(), Jdbc.ts(d.at()), Jdbc.ts(d.at()))
                        .query(Long.class).single();
                int n = 0;
                for (OrderLine line : d.lines()) {
                    jdbc.sql("INSERT INTO order_items (order_id, line_no, product_id, sku, name, unit_price_paise, "
                                    + "quantity) VALUES (?, ?, ?, ?, ?, ?, ?)")
                            .params(orderId, ++n, line.productId(), line.sku(), line.name(), line.unitPricePaise(),
                                    line.quantity())
                            .update();
                }
                jdbc.sql("INSERT INTO payments (id, order_id, provider, status, amount_paise, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'PENDING', ?, ?, ?)")
                        .params(d.paymentId(), orderId, PAYMENT_PROVIDER, p.totalPaise(), Jdbc.ts(d.at()), Jdbc.ts(d.at()))
                        .update();
                jdbc.sql("INSERT INTO outbox_events (type, order_id, payload, created_at) "
                                + "VALUES ('order.placed', ?, ?::jsonb, ?)")
                        .params(orderId, Jdbc.json(Models.orderEventPayload(d.userId(), orderId, "PENDING_PAYMENT",
                                p.totalPaise())), Jdbc.ts(d.at()))
                        .update();
                jdbc.sql("DELETE FROM cart_items WHERE user_id = ?").param(d.userId()).update();
                jdbc.sql("UPDATE carts SET coupon_code = NULL, updated_at = ? WHERE user_id = ?")
                        .params(Jdbc.ts(d.at()), d.userId()).update();
                return findOrder(orderId).orElseThrow();
            });
        } catch (DuplicateKeyException e) {
            throw new DuplicateException("idempotency_key");
        }
    }

    @Override
    public Optional<Order> findOrder(long id) {
        return jdbc.sql(ORDER_SELECT + "WHERE o.id = ?").param(id).query(JdbcOrderRepository::order).optional();
    }

    @Override
    public List<Order> ordersForUser(long userId, int limit) {
        return jdbc.sql(ORDER_SELECT + "WHERE o.user_id = ? ORDER BY o.id DESC LIMIT ?").params(userId, limit)
                .query(JdbcOrderRepository::order).list();
    }

    @Override
    public List<Order> ordersByStatus(String status, int limit) {
        if (status == null) {
            return jdbc.sql(ORDER_SELECT + "ORDER BY o.id DESC LIMIT ?").param(limit).query(JdbcOrderRepository::order).list();
        }
        return jdbc.sql(ORDER_SELECT + "WHERE o.status = ? ORDER BY o.id DESC LIMIT ?").params(status, limit)
                .query(JdbcOrderRepository::order).list();
    }

    @Override
    public Optional<Payment> findPayment(String paymentId) {
        return jdbc.sql("SELECT id, order_id, provider, status, amount_paise, created_at, updated_at FROM payments "
                        + "WHERE id = ?").param(paymentId)
                .query((rs, row) -> new Payment(rs.getString("id"), rs.getLong("order_id"), rs.getString("provider"),
                        rs.getString("status"), rs.getLong("amount_paise"), Jdbc.instant(rs, "created_at"),
                        Jdbc.instant(rs, "updated_at")))
                .optional();
    }

    @Override
    public boolean hasPaymentEvent(String eventId) {
        return jdbc.sql("SELECT 1 FROM payment_events WHERE event_id = ?").param(eventId).query(Integer.class)
                .optional().isPresent();
    }

    @Override
    public String applyTransition(Transition t) {
        try {
            tx.executeWithoutResult(status -> {
                if (t.webhookEventId() != null) {
                    int inserted = jdbc.sql("INSERT INTO payment_events (event_id, payment_id, type, received_at) "
                                    + "VALUES (?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING")
                            .params(t.webhookEventId(), t.paymentId(), t.webhookEventType(), Jdbc.ts(t.at())).update();
                    if (inserted == 0) {
                        throw new Abort("duplicate");
                    }
                }
                if (t.paymentStatus() != null) {
                    int changed = jdbc.sql("UPDATE payments SET status = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'")
                            .params(t.paymentStatus(), Jdbc.ts(t.at()), t.paymentId()).update();
                    if (changed != 1) {
                        throw new Abort("conflict");
                    }
                }
                if (t.toStatus() != null) {
                    // Compare-and-set on the status: two admins (or an admin and a webhook) cannot both win.
                    int changed = jdbc.sql("UPDATE orders SET status = ?, updated_at = ? WHERE id = ? AND status = ?")
                            .params(t.toStatus(), Jdbc.ts(t.at()), t.orderId(), t.fromStatus()).update();
                    if (changed != 1) {
                        throw new Abort("conflict");
                    }
                }
                if (t.restock()) {
                    jdbc.sql("UPDATE products p SET stock = p.stock + i.quantity, updated_at = ? "
                                    + "FROM order_items i WHERE i.order_id = ? AND p.id = i.product_id")
                            .params(Jdbc.ts(t.at()), t.orderId()).update();
                }
                for (NewEvent e : t.events()) {
                    jdbc.sql("INSERT INTO outbox_events (type, order_id, payload, created_at) VALUES (?, ?, ?::jsonb, ?)")
                            .params(e.type(), t.orderId(), Jdbc.json(e.payload()), Jdbc.ts(t.at())).update();
                }
            });
        } catch (Abort a) {
            return a.result;
        }
        return "ok";
    }

    @Override
    public List<OutboxEvent> outbox(boolean pendingOnly, int limit) {
        String where = pendingOnly ? "WHERE published_at IS NULL " : "";
        return jdbc.sql("SELECT id, type, order_id, payload::text AS payload, created_at, published_at FROM outbox_events "
                        + where + "ORDER BY id LIMIT ?").param(limit)
                .query((rs, row) -> new OutboxEvent(rs.getLong("id"), rs.getString("type"), rs.getLong("order_id"),
                        Jdbc.map(rs.getString("payload")), Jdbc.instant(rs, "created_at"),
                        Jdbc.instant(rs, "published_at")))
                .list();
    }

    @Override
    public void markPublished(long eventId, Instant at) {
        jdbc.sql("UPDATE outbox_events SET published_at = ? WHERE id = ? AND published_at IS NULL")
                .params(Jdbc.ts(at), eventId).update();
    }
}
