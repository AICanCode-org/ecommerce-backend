package org.aicancode.shopflow.service;

import org.aicancode.shopflow.domain.Models.OutboxEvent;

/** Where outbox events go. LogPublisher today; RabbitMQ/Kafka/SNS later without touching the services. */
@FunctionalInterface
public interface EventPublisher {

    void publish(OutboxEvent event) throws Exception;
}
