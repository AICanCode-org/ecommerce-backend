# ADR-0004: Idempotent checkout and webhook de-duplication

* **Status:** accepted
* **Date:** 2026-10-02

## Context
Networks fail in the worst place: the order was created but the response was lost. The client retries;
a customer double-taps "Pay" (threat T2). Payment providers deliver webhooks **at least once**: the
same "payment succeeded" event can arrive twice, or minutes apart (threat T8). Without protection, a
retry creates a second order and a redelivery can apply a state change twice.

## Options considered
1. **Do nothing / "disable the button"**: the browser cannot protect the server.
2. **Detect duplicates by content** (same cart within N seconds): guesses; buying the same thing twice
   on purpose is legitimate.
3. **Idempotency keys**: the client generates a unique key per checkout *intent* and sends it in an
   `Idempotency-Key` header; the server stores it with the order under a unique constraint. Webhook
   events carry their own unique id, which the server records when it processes the event.

## Decision
Option 3 for both.
* **Checkout:** `Idempotency-Key` (8–64 chars) is required. A key that already has an order returns
  **that** order (`200`, `Idempotent-Replayed: true`) without touching the cart or the stock. Two
  identical requests racing are settled by `UNIQUE (user_id, idempotency_key)`: the loser returns the
  winner's order.
* **Webhook:** the signature is verified first (HMAC-SHA256 of the raw body, constant-time compare).
  The event id is inserted into `payment_events` **in the same transaction** as the state change; a
  known id returns `{"status":"duplicate"}` and changes nothing.

## Consequences
* Retries are always safe; clients must keep the key for the duration of one checkout attempt.
* Keys are scoped per user, so one customer cannot replay another's key.
* The key is stored forever in v1 (it is small); a retention job could delete keys older than 30 days.
