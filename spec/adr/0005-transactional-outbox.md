# ADR-0005: Transactional outbox for order events

* **Status:** accepted
* **Date:** 2026-10-02

## Context
Other systems need to know when an order is placed, paid, cancelled or shipped (warehouse, e-mail,
accounting, refunds). The naive code commits the order and then calls the broker. If the process
crashes or the broker is down between the two, the order is PAID but nobody ships it (threat T9).
Calling the broker *inside* the transaction is worse: a slow broker holds database locks, and a
rollback after a successful publish announces something that never happened.

## Options considered
1. **Publish after commit**: loses events on crashes.
2. **Distributed transaction (2PC)** between the database and the broker: heavy, rarely supported.
3. **Transactional outbox**: write the event as a row in `outbox_events` in the **same** transaction as
   the order change; a relay reads unpublished rows in id order, publishes them and marks them
   published.

## Decision
Option 3. Every order transition writes its event (`order.placed`, `order.paid`, `order.cancelled`,
`order.shipped`, `order.delivered`, `refund.requested`) in its own transaction. The relay
(`POST /api/v1/admin/outbox/publish` in v1; a scheduled job later) publishes in id order and stops at
the first failure, so events are never reordered and a broker outage loses nothing.

## Consequences
* **At-least-once delivery:** a crash between "published" and "marked" publishes the event again.
  Consumers must de-duplicate on the event id (it is in every message).
* The outbox table grows; published rows can be archived after N days.
* The publisher in v1 only logs (`LogPublisher`); swapping in a real broker changes one class.
