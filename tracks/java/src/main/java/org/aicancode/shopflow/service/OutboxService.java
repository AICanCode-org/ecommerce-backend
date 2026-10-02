package org.aicancode.shopflow.service;

import java.time.Clock;
import java.util.List;
import org.aicancode.shopflow.domain.Models.OutboxEvent;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.repo.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The transactional outbox relay (ADR-0005). */
public class OutboxService {

    public static final int BATCH = 100;
    private static final Logger LOG = LoggerFactory.getLogger(OutboxService.class);
    private final OrderRepository orders;
    private final EventPublisher publisher;
    private final Clock clock;

    public OutboxService(OrderRepository orders, EventPublisher publisher, Clock clock) {
        this.orders = orders;
        this.publisher = publisher;
        this.clock = clock;
    }

    public List<OutboxEvent> list(Principal principal, String pending, String limit) {
        // TODO(phase-3 M5): ADMIN only; pending filter (true/false) and limit; events in id order.
        throw new UnsupportedOperationException("TODO(phase-3 M5): OutboxService.list");
    }

    /**
     * Publish in id order, mark each one after the broker accepted it. Stops at the first failure. A crash between
     * publish and mark means the event is published again: at-least-once delivery, so consumers de-duplicate on the
     * event id.
     */
    public int publishPending(Principal principal) {
        // TODO(phase-3 M5): ADMIN only. The relay (ADR-0005): pending events in id order (OUTBOX_BATCH),
        //   publish each and mark it published; on the FIRST publish failure log a warning and stop. Return how
        //   many were published.
        throw new UnsupportedOperationException("TODO(phase-3 M5): OutboxService.publishPending");
    }
}
