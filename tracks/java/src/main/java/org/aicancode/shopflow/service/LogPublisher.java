package org.aicancode.shopflow.service;

import java.util.TreeMap;
import org.aicancode.shopflow.domain.Models.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stands in for a message broker: one log line per event. */
public class LogPublisher implements EventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(LogPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        LOG.info("event {} {} {}", event.id(), event.type(), new TreeMap<>(event.payload()));
    }
}
