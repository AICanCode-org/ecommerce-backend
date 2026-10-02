---
phase: 7
slug: phase-7-observability
title: Observability and maintenance
duration: 3 h
start_tag: phase-6-end
end_tag: phase-7-end
release: v1.1.0
---
# Phase 7 · Observability and maintenance (v1.1.0)

## 1. Why it matters
Shipping v1.0.0 is the start, not the end. In production you have to answer "is it working right now,
and if not, why?" without attaching a debugger. That needs three things: **logs** you can search,
**metrics** you can graph, and a **request ID** that ties a customer's complaint ("my order failed at
19:42") to log lines. ShopFlow also depends on something you do not control, an LLM, so you will run a
**game day** where the LLM hangs, measure what shoppers feel, and fix it with a **circuit breaker**.
Finally you make your first **maintenance change**, driven by a real business problem (abandoned
checkouts lock stock forever): a schema migration, a new admin job and a backward-compatible API
field, released safely as v1.1.0.

## 2. Objectives
- Give every request an `X-Request-ID` (reuse a safe incoming one, or generate a UUID) and put it in every log line.
- Emit structured **JSON logs**: one object per line, one access-log line per request.
- Expose `/metrics` in Prometheus text format with eight counters that are identical in every track.
- Run a **game day**: make the LLM hang, measure search-assist latency, check the checkout path, write a short blameless post-mortem.
- Add an LLM **circuit breaker** (3 failures in a row → open for 30 s → one half-open trial).
- Ship a backward-compatible change: `V2__order_payment_window.sql`, a payment window on every order (`expiresAt`), the admin expiry job, OpenAPI 1.1.0, CHANGELOG, tag `v1.1.0`.
- Review and merge one Dependabot PR.

## 3. Step-by-step instructions

### Step 7.1 · Request IDs
Rule (shared by all tracks): if the incoming `X-Request-ID` matches `^[A-Za-z0-9._-]{1,64}$`, echo it;
otherwise generate a UUID. Never echo arbitrary input: a header containing a newline could inject
fake log lines or headers.

<!-- track:java -->
A `OncePerRequestFilter` (`web/RequestIdFilter.java`) sets the response header, puts `requestId` in
the SLF4J **MDC** (so every log line in that request carries it) and logs one `shopflow.access` line
with `method`, `path`, `status` and `durationMs` using the fluent API
`LOG.atInfo().addKeyValue("status", …).log()`. `@Order(Ordered.HIGHEST_PRECEDENCE)` makes it the first
filter, so even a `401` or a `404` gets an ID (ShopFlow uses only `spring-security-crypto`, not the filter chain).
<!-- /track -->

<!-- track:python -->
A Starlette middleware (`app/observability.py`) stores the ID in `request.state`, sets the response
header and logs one `shopflow.access` record with `extra={"fields": {...}}`. The middleware wraps the
whole app, so error responses (`400`, `401`, `404`) carry the header too.
<!-- /track -->

<!-- track:typescript -->
Fastify has request IDs built in: `genReqId` (calling `incomingOrNewId()` from `src/observability.ts`)
with `requestIdHeader: false`, so we validate the incoming value ourselves, and a `LogController` with `requestIdLogLabel: "requestId"`.
An `onSend` hook copies `request.id` into the `X-Request-ID` response header.
<!-- /track -->

### Step 7.2 · JSON logs
<!-- track:java -->
One property: `logging.structured.format.console=logstash` in `application.properties`. MDC values
and key/value pairs become JSON fields.
<!-- /track -->

<!-- track:python -->
A small `logging.Formatter` subclass (`JsonFormatter`) that `json.dumps` `ts`, `level`, `logger`, `msg`
and the `fields`; installed by `configure_logging()` at startup.
<!-- /track -->

<!-- track:typescript -->
Nothing to add: Fastify's logger (pino) already writes JSON, one line per event (`incoming request`,
`request completed` with `responseTime`).
<!-- /track -->

**Never log secrets.** Passwords, access tokens, the `Authorization` header, the webhook secret and
signatures must not appear in any log line. Grep for them after the smoke test (`docker compose logs app-<track> | grep -i bearer`
must print nothing).

Check it:
```bash
docker compose --profile <track> up -d --build --wait
curl -s -H 'X-Request-ID: demo-123' localhost:8080/health -o /dev/null -D - | grep -i x-request-id
docker compose logs app-<track> | grep demo-123
```

### Step 7.3 · `/metrics`
Eight counters, same names in all tracks (see `GET /metrics` in `spec/openapi.yaml`):

| Counter | Incremented when |
|---|---|
| `shopflow_users_registered_total` | a registration succeeds |
| `shopflow_logins_failed_total` | a login is rejected (watch it for credential stuffing) |
| `shopflow_orders_placed_total` | a checkout creates a new order |
| `shopflow_checkout_replays_total` | a checkout was answered from its Idempotency-Key (retries, double taps) |
| `shopflow_webhooks_duplicate_total` | the payment provider redelivered an event we had already processed |
| `shopflow_orders_expired_total` | the expiry job cancelled an unpaid order (Step 7.6) |
| `shopflow_ai_fallback_total` | an AI call fell back to the offline rules/template |
| `shopflow_llm_short_circuits_total` | the circuit breaker skipped an LLM call (Step 7.5) |

The replay and duplicate counters are worth a second look: in v1.0 the protections from ADR-0004 worked
silently. Now you can *see* how often they save you.

They are hand-rolled on purpose: the Prometheus text format is just
```text
# HELP shopflow_logins_failed_total Login attempts answered with 401.
# TYPE shopflow_logins_failed_total counter
shopflow_logins_failed_total 3
```
In a bigger service you would use Micrometer (Java), `prometheus-client` (Python) or `prom-client` (Node).
`/metrics` is public in this course project; in production you would restrict it to the scraper's network.

### Step 7.4 · Game day: the LLM hangs
A dead LLM is easy: the connection is refused and the fallback answers in milliseconds. A **hanging** LLM
is worse: it accepts the connection and never answers, so every search that needs it waits for the full
`LLM_TIMEOUT_MS` (5 s by default). That is what an overloaded hosted model or a stuck local Ollama looks like.
1. Start the stack at v1.0.0: `git switch --detach v1.0.0`, `docker compose --profile <track> up -d --build --wait`.
2. Get the script from the reference: `git show phase-7-end:scripts/game-day.sh > /tmp/game-day.sh && chmod +x /tmp/game-day.sh`,
   then `/tmp/game-day.sh <track>`. It creates a product and a customer, then runs four phases of
   10 searches each (`GET /api/v1/search/assist?q=running shoes under 3000 in stock`): **offline**
   baseline, a **healthy** fake LLM, the fake LLM **hanging**, and the LLM **back**. While the LLM hangs
   it also fires 50 searches at once and measures `/health` and `GET /cart` (the checkout path) meanwhile. The fake LLM is a 15-line Python HTTP server in a throw-away container on the Compose network
   (`MODE=ok` answers like `/v1/chat/completions`, `MODE=hang` never answers), so no model download and no API key.
3. Write down for each phase: status codes, average and maximum latency, the assist `provider` and
   `fallback` flag, the warnings in the logs, and (from v1.1) the counters. Did any search fail? Was any
   slow? Did the slow searches slow down `/health` or the cart? (In the reference run they slowed the
   Python track's `/health` to 4 s: its worker threads were all waiting for the LLM.)
4. Write `docs/postmortem-<date>-llm-hang.md`: summary, impact, timeline, root cause, what went well,
   what to improve, action items. Blameless: describe systems, not people. Read the reference
   post-mortem **after** writing yours (`git show phase-7-end:docs/postmortem-2026-10-02-llm-hang.md`).

### Step 7.5 · Fix: an LLM circuit breaker
Wrap the one LLM client (shared by description drafts and search assist) in a `CircuitBreaker`:
- **CLOSED:** calls go through. 3 failures **in a row** open the circuit.
- **OPEN:** calls fail immediately (the caller uses its offline fallback) for 30 s, and
  `shopflow_llm_short_circuits_total` is incremented.
- **HALF-OPEN:** after the cooldown, exactly **one** trial call goes through. Success closes the circuit;
  failure re-opens it for another 30 s.

Test it with a fake clock and a fake client (no sleeping in tests): 3 failures → the 4th call does not reach
the client; advance the clock 30 s → one trial; a concurrent second call during the trial is short-circuited.

<!-- track:java -->
`ai/CircuitBreaker.java` implements `LlmClient`, so `AppConfig` wraps the HTTP client once and both
`LlmWriter` and `LlmParser` receive the wrapped instance. State changes are `synchronized`;
the slow HTTP call happens **outside** the lock.
<!-- /track -->

<!-- track:python -->
`CircuitBreaker` in `app/ai.py` has the same `chat()` signature as the HTTP client, a `threading.Lock`
around state changes and `time.monotonic` as the default clock (wall-clock time can jump).
<!-- /track -->

<!-- track:typescript -->
`CircuitBreaker` in `src/ai.ts` wraps the client's `chat()`. Node is single-threaded, but `await` still
interleaves requests, so the `trialRunning` flag matters exactly as it does in Java and Python.
<!-- /track -->

Re-run the game day on your branch: in the *hang* phase the first 3 searches still wait for the timeout,
the other 7 answer in milliseconds with `fallback: true` (average ≈ 1.5 s instead of ≈ 5 s). In the *back* phase the circuit stays open until the
30 s cooldown has passed, then one trial call closes it again.

### Step 7.6 · Maintenance change: a payment window for unpaid orders (v1.1.0)
The problem from the shop floor: checkout decrements stock immediately (ADR-0002), so a customer who
checks out and never pays locks those units **forever**. v1.1 gives every order a payment window.
1. `spec/db/migrations/V2__order_payment_window.sql`:
   `ALTER TABLE orders ADD COLUMN expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '15 minutes');`
   then backfill existing rows (`created_at + 15 minutes`) and add a **partial index**
   `ON orders (expires_at) WHERE status = 'PENDING_PAYMENT'`. The column has a default, so v1.0.0 code
   that does not know it keeps inserting orders during a rolling deploy.
2. Checkout sets `expires_at = now + RESERVATION_TTL` (15 minutes) explicitly.
3. A new transition `PENDING_PAYMENT --expire--> CANCELLED` (restock + `order.cancelled`), and an
   admin job `POST /api/v1/admin/orders/expire` that cancels overdue orders (oldest first, at most 100
   per call) and answers `{"expired": n}`. It uses the **same compare-and-set** as every other
   transition, so an order whose payment arrives at the same moment is either PAID or expired, never
   both. A payment that arrives after the expiry is handled by the existing rule: refund requested.
4. `Order` gains `expiresAt`. Adding a field is backward compatible; removing or renaming one is not.
5. `spec/openapi.yaml` → `version: 1.1.0`; `CHANGELOG.md` → `## [1.1.0]`; bump the track's version.
6. Tests first: the expiry service tests (only overdue unpaid orders, restock, the race with a webhook),
   an API test for the admin endpoint (`403` for customers), an integration test against the real index.
7. Release: `git tag -a v1.1.0 -m "ShopFlow v1.1.0" && git push origin main --follow-tags`.
   `deploy.yml` publishes `:1.1.0` and moves `:latest`. In production a scheduler (a cron job calling
   the endpoint every minute) runs the expiry; the endpoint keeps it testable and observable.

### Step 7.7 · Merge a Dependabot PR
Pick one grouped update. Read its release notes, check that CI is green **including integration tests**,
run it locally if it is a major version, then merge. Write down in `AI_LOG.md` or the PR what you checked.

## 4. AI-assist prompts

### Prompt 1: Structured logging setup
```text
In <Spring Boot 4 | FastAPI | Fastify 5> I want one JSON log line per request with requestId, method,
path, status and durationMs, where requestId comes from a validated X-Request-ID header or a new UUID
and appears on every log line of that request, including 401 responses from the auth layer. Show the
minimal code with no new dependencies, and explain how the request ID is propagated (MDC / contextvars /
child logger). Passwords and tokens must never be logged.
```
**Verify the output by:** sending a request with `X-Request-ID: demo-123` and with a malicious value
(`$'bad\nInjected: 1'` in bash). The first must be echoed and logged, the second replaced by a UUID.
Check `docker compose logs` shows valid JSON (`| jq .`) and that `grep -i bearer` finds nothing.

### Prompt 2: Review my circuit breaker
```text
Here is my LLM circuit breaker in <language>: <paste>. Rules: 3 consecutive failures open it for 30 s,
then exactly one half-open trial; success closes, failure re-opens. Find race conditions (two requests
during the trial), places where the slow call happens while holding a lock, and missing tests. Suggest
tests that use a fake clock instead of sleeping.
```
**Verify the output by:** running the suggested tests and the game day. The short-circuit counter in
`/metrics` must grow by one for every search after the third in the *hang* phase.

### Prompt 3: Is this migration backward compatible?
```text
I run several app instances and deploy one at a time. Is this migration safe to apply while the old
version is still serving traffic? <paste V2 SQL> The old version does not know the new column. What
would make it unsafe (NOT NULL without a default, volatile defaults on big tables, renames, long locks)?
How would I roll it back?
```
**Verify the output by:** running the **v1.0.0** image against a database that already has V2 applied
(`git switch --detach v1.0.0`, `docker compose --profile <track> up -d --build`) and running the v1.0.0
smoke test: it must still pass.

## 5. Deliverables
- [ ] `X-Request-ID` echoed/generated; JSON access logs visible in `docker compose logs`, no tokens in them.
- [ ] `/metrics` with the eight counters; the v1.1 smoke test passes (81 checks).
- [ ] `docs/postmortem-…-llm-hang.md` with real numbers from your game day.
- [ ] Circuit breaker with fake-clock tests; the game day re-run shows fast fallbacks after 3 failures.
- [ ] `V2__order_payment_window.sql`, `expiresAt` + the expiry job in the API, OpenAPI 1.1.0, CHANGELOG,
  tag `v1.1.0`; the v1.0.0 smoke test still passes against the V2 schema.
- [ ] One merged Dependabot PR.

## 6. Self-check quiz

### Q1. Why validate an incoming `X-Request-ID` before echoing it?
- [ ] To make IDs shorter
- [x] An unvalidated value could inject newlines or fake fields into logs and headers
- [ ] Browsers reject long headers
- [ ] UUIDs are faster to generate
> **Answer:** Treat every header as untrusted input. A strict allow-list regex is the simplest defence.

### Q2. During the game day every search still returned 200. Why is a hanging LLM still an incident?
- [ ] Searches returned wrong products
- [x] Every search waited for the full LLM timeout, so shoppers felt 5 s of latency and server threads/connections were tied up
- [ ] The database went down
- [ ] Checkout stopped working
> **Answer:** Graceful degradation kept correctness (the rules fallback answered) but not latency. Availability is also about how long users wait.

### Q3. What does the circuit breaker do in the OPEN state?
- [ ] Retries the LLM three times
- [ ] Returns 503 to the client
- [x] Skips the LLM call immediately so the caller uses its offline fallback, until the cooldown ends
- [ ] Switches to a different LLM provider
> **Answer:** It turns a slow failure into a fast one. After the cooldown one half-open trial checks whether the LLM has recovered.

### Q4. Why does `expires_at` get `NOT NULL DEFAULT (now() + INTERVAL '15 minutes')` instead of plain `NOT NULL`?
- [ ] Postgres requires a default on every column
- [x] v1.0.0 code that does not know the column keeps inserting orders during a rolling deploy; the default fills it in
- [ ] It makes the column indexed
- [ ] It saves disk space
> **Answer:** Expand first, (optionally) contract later. A `NOT NULL` column without a default would make every v1.0.0 checkout fail the moment V2 is applied.

### Q5. Adding `expiresAt` to the order response and a new admin endpoint is a … change.
- [ ] Major (breaking)
- [x] Minor (backward-compatible feature), so 1.0.0 → 1.1.0
- [ ] Patch (bug fix)
- [ ] No version change needed
> **Answer:** SemVer: new optional fields are minor. Removing or renaming a field would be major (2.0.0).

### Q6. Which metric would you alert on to spot a credential-stuffing attack?
- [ ] `shopflow_users_registered_total`
- [x] A sudden rise in `shopflow_logins_failed_total`
- [ ] `shopflow_checkout_replays_total`
- [ ] `shopflow_llm_short_circuits_total`
> **Answer:** Many rejected logins in a short time is the classic signal. The fix (rate limiting per IP/account) is listed as a gap in Phase 8.

### Q7. What makes a post-mortem "blameless"?
- [ ] Nobody writes it
- [ ] It omits the root cause
- [x] It focuses on how the system and process allowed the failure, not on who made a mistake
- [ ] It is kept private
> **Answer:** People hide problems when they fear blame. Blameless reviews produce better action items.

## 7. What you learned
- Correlation IDs, MDC/context propagation and structured JSON logs without secrets.
- Prometheus counters and the text exposition format.
- Game days, blameless post-mortems, and why "hangs" is worse than "down".
- The circuit-breaker pattern, tested with a fake clock.
- Expand-style, backward-compatible migrations, a race-safe background job, and SemVer minor releases.
- Dependency maintenance with Dependabot, and the role of CI as the safety net.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
git switch -c my-phase-8 phase-7-end          # == v1.1.0
git diff v1.0.0 v1.1.0 --stat                 # the whole maintenance change
git diff v1.0.0 v1.1.0 -- spec/               # contract + migration only
```
