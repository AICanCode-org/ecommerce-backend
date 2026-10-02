# ShopFlow architecture

> **Phase 2 deliverable.** Replace the TODOs. Diagrams use [Mermaid](https://mermaid.js.org/), which
> GitHub renders natively. Reference: `git show phase-2-end:spec/architecture.md`.

## 1. Context diagram
TODO: who/what talks to ShopFlow (shoppers, admins, a storefront front end, the payment provider,
the optional LLM, whatever consumes order events)?

## 2. Container diagram
TODO: app, PostgreSQL, optional LLM, event consumers. Which calls are on the request path, and which
can fail safely?

## 3. Checkout sequence
TODO: sequence diagram for checkout with an Idempotency-Key: first call, a retried call, two
customers racing for the last item.

## 4. Payment webhook sequence
TODO: the provider calls back (signed), a redelivered event, a payment that arrives after the order
was cancelled.

## 5. Order state machine
TODO: PENDING_PAYMENT → PAID → SHIPPED → DELIVERED, and every way to CANCELLED.

## 6. Data model
TODO: tables, keys, constraints, indexes, and why (link to `db/migrations/V1__init.sql`).

## 7. Key decisions
TODO: link your ADRs (`adr/0001-...`).
