package org.aicancode.shopflow.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.aicancode.shopflow.domain.Models.Order;
import org.aicancode.shopflow.domain.Models.OrderDraft;
import org.aicancode.shopflow.domain.Models.OrderLine;
import org.aicancode.shopflow.domain.Models.OutboxEvent;
import org.aicancode.shopflow.domain.Models.Payment;
import org.aicancode.shopflow.domain.Models.Transition;
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
        // TODO(phase-3 M5): the order with this (user id, idempotency key), or null.
        throw new UnsupportedOperationException("TODO(phase-3 M5): JdbcOrderRepository.findOrderByKey");
    }

    @Override
    public Order placeOrder(OrderDraft d) {
        // TODO(phase-3 M5): ONE transaction: for every line a GUARDED stock decrement (WHERE id = ? AND
        //   active AND stock >= qty; 0 rows -> InsufficientStock + rollback), INSERT the order (unique (user,
        //   key) -> DuplicateError), its lines, the PENDING payment, an order.placed outbox event, and empty
        //   the cart.
        throw new UnsupportedOperationException("TODO(phase-3 M5): JdbcOrderRepository.placeOrder");
    }

    @Override
    public Optional<Order> findOrder(long id) {
        // TODO(phase-3 M5): the order by id with its lines, or null.
        throw new UnsupportedOperationException("TODO(phase-3 M5): JdbcOrderRepository.findOrder");
    }

    @Override
    public List<Order> ordersForUser(long userId, int limit) {
        // TODO(phase-3 M5): the user's orders, newest first (ORDER BY id DESC), LIMIT n.
        throw new UnsupportedOperationException("TODO(phase-3 M5): JdbcOrderRepository.ordersForUser");
    }

    @Override
    public List<Order> ordersByStatus(String status, int limit) {
        // TODO(phase-3 M5): all orders [with this status], newest first, LIMIT n.
        throw new UnsupportedOperationException("TODO(phase-3 M5): JdbcOrderRepository.ordersByStatus");
    }

    @Override
    public Optional<Payment> findPayment(String paymentId) {
        // TODO(phase-3 M5): the payment by its provider id, or null.
        throw new UnsupportedOperationException("TODO(phase-3 M5): JdbcOrderRepository.findPayment");
    }

    @Override
    public boolean hasPaymentEvent(String eventId) {
        // TODO(phase-3 M5): true when this webhook event id was already recorded.
        throw new UnsupportedOperationException("TODO(phase-3 M5): JdbcOrderRepository.hasPaymentEvent");
    }

    @Override
    public String applyTransition(Transition t) {
        // TODO(phase-3 M5): ONE transaction: record the webhook event if any (unique id -> 'duplicate'),
        //   compare-and-set the order status (UPDATE ... WHERE id AND status = from; 0 rows -> 'conflict' +
        //   rollback), update the payment, restock, insert the outbox events. 'ok' when it all committed.
        throw new UnsupportedOperationException("TODO(phase-3 M5): JdbcOrderRepository.applyTransition");
    }

    @Override
    public List<OutboxEvent> outbox(boolean pendingOnly, int limit) {
        // TODO(phase-3 M5): outbox events [only unpublished] ORDER BY id LIMIT n.
        throw new UnsupportedOperationException("TODO(phase-3 M5): JdbcOrderRepository.outbox");
    }

    @Override
    public void markPublished(long eventId, Instant at) {
        // TODO(phase-3 M5): set published_at on the event (only if it is still null).
        throw new UnsupportedOperationException("TODO(phase-3 M5): JdbcOrderRepository.markPublished");
    }
}
