# ShopFlow retrospective (reference solution, v1.0.0 → v1.1.0)

Format: *What went well / What didn't / What I'd change*, plus one technical, one process and one AI
lesson, as asked in Phase 8. Written by the maintainers who built the reference solution. Yours will
(and should) look different.

## What went well
- **One contract, three implementations.** `spec/openapi.yaml`, the SQL migrations and
  `scripts/smoke-test.sh` are shared. All three tracks pass the same 81-check smoke test against their
  Docker images (73 at v1.0.0), including the money and security checks: exact coupon rounding in paise,
  `Idempotent-Replayed: true` on a retried checkout, `409 insufficient-stock` instead of negative stock,
  a forged webhook `401`, a duplicate webhook ignored, someone else's order `404`.
- **Let the database enforce the invariants.** `CHECK (stock >= 0)`, `CHECK` that the order total adds up, a unique
  `(user_id, idempotency_key)` and a unique webhook event id turn "should never happen" into "cannot
  happen". The parallel-checkout integration test sends several checkouts for the last units at once and
  asserts that exactly the available number succeed; it passes because the decrement is one guarded
  `UPDATE … SET stock = stock - ? WHERE id = ? AND active AND stock >= ?`, not a read followed by a write.
- **Money as integers from the first line.** Paise everywhere, rupees only when formatting. Not a single
  rounding bug made it past the unit tests, and the coupon tests pin the rounding rule (percent discounts round
  down in integer maths, then the cap applies), so the shop never gives away more than it promised.
- **Tests before code with a pending list.** Turning on one test group at a time kept every commit green
  and made "done" objective. Every phase tag builds and passes its tests in all three tracks
  (`scripts/verify-tags.sh`).
- **AI off the money path.** Description drafts are never saved automatically and search assist only
  produces filters that are validated like user input. When the LLM hung, the worst case was a slow
  search box, never a wrong price or a lost order.

## What didn't go well
- **Failure tests were too kind.** Every "LLM is down" test failed fast (connection refused). The Phase 7
  game day made the LLM *hang* instead: in v1.0.0 every search-assist request waited the full 5 s
  timeout, and in the Python track 50 concurrent requests exhausted the worker thread pool so that even
  `/health` took 4 s (`docs/postmortem-2026-10-02-llm-hang.md`). Correct behaviour, unacceptable latency.
- **Abandoned checkouts locked stock forever in v1.0.0.** An unpaid order kept its units until somebody
  cancelled it. Nothing in Phase 1 asked "for how long is stock reserved?". v1.1.0 added the payment
  window (`expires_at`, an expiry endpoint) as an expand-only migration, but the question belonged in the
  requirements.
- **Version churn.** Framework majors (Spring Boot 4 / Jackson 3, Fastify 5) moved APIs between "what
  the assistant remembered" and "what the docs say today". Several snippets had to be rewritten against
  current documentation.
- **The outbox relay is manual.** `POST /api/v1/admin/outbox/publish` proves the pattern and its
  at-least-once semantics, but a real shop needs a scheduled relay and a real broker.

## What I'd change next time
1. Write a **degraded-mode latency budget** next to NFR-6 on day one, and test it with a hanging
   dependency, not only a refused one.
2. Ask the **"how long?" questions** in Phase 1: stock reservation, payment window, refund deadline.
3. Put the **game day into CI** (fake LLM in `MODE=hang` inside the Docker smoke job, assert latency).
4. Keep slow I/O off the request path in the Python track (async client or a bulkhead) so one slow
   dependency cannot starve checkout.
5. Rate-limit `POST /auth/login` per account and IP. Today `shopflow_logins_failed_total` only *shows*
   credential stuffing; it does not stop it.

## Three lessons
- **Technical:** concurrency bugs are designed out, not tested out. One guarded `UPDATE`, a unique key
  and a `CHECK` constraint are simpler and stronger than any amount of locking in application code. The
  tests then prove it.
- **Process:** a pending-test list plus small milestone commits made progress visible and reviews small.
  The catch-up tags made it safe to fall behind.
- **AI:** assistants were excellent at boilerplate (Dockerfiles, workflow skeletons, test scaffolding,
  the fake LLM) and needed the strictest review exactly where it matters most: money arithmetic,
  read-then-write stock checks, signature comparison and anything version-specific. Every accepted
  suggestion is in `AI_LOG.md` with how it was verified; tests, `actionlint` and the smoke test caught
  what reading did not.

## Evidence
| | Java | Python | TypeScript |
|---|---|---|---|
| Unit + integration tests (v1.1.0) | 153 + 14 | 160 + 14 | 157 + 14 |
| Line coverage (gate ≥ 85 %) | 96.3 % (JaCoCo) | 97.8 % (coverage.py) | 97.3 % (V8) |
| Docker smoke test v1.1.0 (LLM unreachable) | 81/81 (84/84) | 81/81 (84/84) | 81/81 (84/84) |
| 10 search assists while the LLM hangs, v1.0.0 → v1.1.0 (avg) | 5 011 → 1 507 ms | 5 011 → 1 505 ms | 5 006 → 1 504 ms |
