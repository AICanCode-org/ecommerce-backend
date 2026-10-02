package org.aicancode.shopflow.service;

import java.time.Instant;
import java.util.Map;
import org.aicancode.shopflow.domain.ApiException;
import org.aicancode.shopflow.domain.Models;
import org.aicancode.shopflow.domain.Models.Order;
import org.aicancode.shopflow.domain.Models.Transition;

/** The order state machine: (current status, action) -> new status. Anything else is a 409 invalid-transition. */
public final class Orders {

    public static final Map<String, String> TRANSITIONS = Map.of(
            "PENDING_PAYMENT:pay", "PAID",
            "PENDING_PAYMENT:fail", "CANCELLED",
            "PENDING_PAYMENT:cancel", "CANCELLED",
            "PAID:cancel", "CANCELLED",
            "PAID:ship", "SHIPPED",
            "SHIPPED:deliver", "DELIVERED");
    static final Map<String, String> ACTION_FOR_TARGET = Map.of("SHIPPED", "ship", "DELIVERED", "deliver");
    static final Map<String, String> EVENT_FOR_STATUS = Map.of("PAID", "order.paid", "CANCELLED", "order.cancelled",
            "SHIPPED", "order.shipped", "DELIVERED", "order.delivered");

    private Orders() {
    }

    public static String nextStatus(String current, String action) {
        // TODO(phase-3 M5): look up (current, action) in TRANSITIONS; not there -> 409 invalid-transition
        //   'cannot <action> an order that is <status>'.
        throw new UnsupportedOperationException("TODO(phase-3 M5): Orders.nextStatus");
    }

    public static ApiException concurrentChange() {
        return ApiException.invalidTransition("the order changed while we were updating it: reload and retry");
    }

    static Map<String, Object> payload(Order o, String status) {
        return Models.orderEventPayload(o.userId(), o.id(), status, o.totalPaise());
    }

    /** The status change plus its side effects: restock on cancel, outbox events, refund if it was paid. */
    public static Transition transitionFor(Order order, String action, Instant at) {
        // TODO(phase-3 M5): next_status + the side effects: an outbox event for the new status
        //   (EVENT_FOR_STATUS), a refund.requested event when a PAID order is cancelled, and restock when the
        //   target is CANCELLED.
        throw new UnsupportedOperationException("TODO(phase-3 M5): Orders.transitionFor");
    }
}
