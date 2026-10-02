package org.aicancode.shopflow.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.aicancode.shopflow.domain.ApiException;
import org.aicancode.shopflow.domain.Models;
import org.aicancode.shopflow.domain.Models.NewEvent;
import org.aicancode.shopflow.domain.Models.Order;
import org.aicancode.shopflow.domain.Models.Transition;

/** The order state machine: (current status, action) -> new status. Anything else is a 409 invalid-transition. */
public final class Orders {

    public static final Map<String, String> TRANSITIONS = Map.of(
            "PENDING_PAYMENT:pay", "PAID",
            "PENDING_PAYMENT:fail", "CANCELLED",
            "PENDING_PAYMENT:cancel", "CANCELLED",
            "PENDING_PAYMENT:expire", "CANCELLED", // v1.1: the payment window closed
            "PAID:cancel", "CANCELLED",
            "PAID:ship", "SHIPPED",
            "SHIPPED:deliver", "DELIVERED");
    static final Map<String, String> ACTION_FOR_TARGET = Map.of("SHIPPED", "ship", "DELIVERED", "deliver");
    static final Map<String, String> EVENT_FOR_STATUS = Map.of("PAID", "order.paid", "CANCELLED", "order.cancelled",
            "SHIPPED", "order.shipped", "DELIVERED", "order.delivered");

    private Orders() {
    }

    public static String nextStatus(String current, String action) {
        String target = TRANSITIONS.get(current + ":" + action);
        if (target == null) {
            throw ApiException.invalidTransition("cannot " + action + " an order that is " + current);
        }
        return target;
    }

    public static ApiException concurrentChange() {
        return ApiException.invalidTransition("the order changed while we were updating it: reload and retry");
    }

    static Map<String, Object> payload(Order o, String status) {
        return Models.orderEventPayload(o.userId(), o.id(), status, o.totalPaise());
    }

    /** The status change plus its side effects: restock on cancel, outbox events, refund if it was paid. */
    public static Transition transitionFor(Order order, String action, Instant at) {
        String target = nextStatus(order.status(), action);
        List<NewEvent> events = new ArrayList<>();
        events.add(new NewEvent(EVENT_FOR_STATUS.get(target), payload(order, target)));
        if ("cancel".equals(action) && "PAID".equals(order.status())) {
            events.add(new NewEvent("refund.requested", payload(order, target)));
        }
        return new Transition(order.id(), order.status(), target, at, "CANCELLED".equals(target), List.copyOf(events),
                null, null, null, null);
    }
}
