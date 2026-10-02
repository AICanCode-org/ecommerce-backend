---
phase: 2
slug: phase-2-design
title: Design (architecture, money, concurrency, DB schema, API contract)
duration: 3.5 h
start_tag: phase-1-end
end_tag: phase-2-end
---
# Phase 2 · Design: architecture, money, concurrency, database schema, API contract

## 1. Why it matters
Design is where you make the expensive decisions on purpose: how money is represented, what stops two
customers buying the last unit, what happens when a checkout request is retried, how a payment
callback is trusted, and how other systems learn that an order was paid without ever losing that
message. Writing them down as diagrams and **Architecture Decision Records (ADRs)** lets a reviewer
follow your reasoning in minutes. The SQL schema is the one artefact shared by all three tracks, so a
mistake here costs three times, and a missing constraint is an overselling bug waiting for a sale day.

## 2. Objectives
- Draw context, container and sequence diagrams (checkout, payment webhook) and the order state machine in Mermaid.
- Write five ADRs: money as integer paise, guarded stock decrement, optimistic locking for product edits,
  idempotent checkout + webhook de-duplication, and a transactional outbox.
- Design the PostgreSQL schema and write it as migration `spec/db/migrations/V1__init.sql`.
- Read the given OpenAPI contract and map each endpoint to a requirement.
- Watch your track's migrator apply V1 on start-up and prove the constraints by breaking them.

## 3. Step-by-step instructions

### Step 2.1 · Diagrams in `spec/architecture.md`
Mermaid is text, so it diffs and reviews like code. Start with a container diagram:
```text
flowchart TB
    client([Storefront, mobile app, curl]) --> app["ShopFlow app :8080"]
    pay([Payment provider]) -- "signed webhook" --> app
    app -- "SQL (source of truth)" --> pg[("PostgreSQL 16")]
    app -. "optional, timeout + fallback" .-> llm[("LLM endpoint")]
```
Wrap it in a code fence with the language `mermaid` and preview it on GitHub or in VS Code (extension
"Markdown Preview Mermaid Support"). Then draw:
1. **Checkout** with an `Idempotency-Key`: first call, a retry with the same key, two customers racing
   for the last unit.
2. **Payment webhook**: a valid event, the same event redelivered, a bad signature, a success that
   arrives after the customer cancelled.
3. **The order state machine** (`stateDiagram-v2`): PENDING_PAYMENT, PAID, SHIPPED, DELIVERED, CANCELLED
   and the action on every arrow.

### Step 2.2 · ADRs
Copy `spec/adr/0000-template.md` five times:
1. `0001-money-as-integer-paise.md`: floats vs decimal types vs integers in the smallest unit. How is a
   percentage rounded? What does the JSON look like?
2. `0002-guarded-stock-decrement.md`: read-check-write vs `SELECT … FOR UPDATE` vs one guarded
   `UPDATE … WHERE stock >= :q`. What does "0 rows updated" mean?
3. `0003-optimistic-locking-for-products.md`: last-write-wins vs a `version` column. Why must stock
   changes **not** bump the version?
4. `0004-idempotent-checkout-and-webhook-dedup.md`: where is the key stored, what makes two identical
   requests racing safe, how is a redelivered webhook recognised?
5. `0005-transactional-outbox.md`: publish-after-commit vs 2PC vs an outbox table. What delivery
   guarantee do consumers get, and what must they do about it?

Each ADR is one page: context, options with pros/cons, decision, consequences.

### Step 2.3 · Schema → `spec/db/migrations/V1__init.sql`
Requirements to satisfy:
- `users`: unique, lower-case `email` (enforce lower case with a `CHECK`), `password_hash`, `role`
  restricted by a `CHECK` to CUSTOMER/ADMIN.
- `products`: unique `sku`, `price_paise BIGINT` with a range `CHECK`, `stock` with
  **`CHECK (stock >= 0)`**, `version`, `active`, and a **generated** `tsvector` column for full-text
  search with a GIN index. A partial index on active products.
- `coupons` (PERCENT/FLAT, optional cap and minimum) seeded with the demo codes `SAVE10`, `FLAT100`,
  `EXPIRED5`.
- `carts` + `cart_items` (one cart per user, quantity `CHECK` 1–10).
- `orders` with every money column, `CHECK (total_paise = subtotal_paise - discount_paise +
  shipping_paise)`, a status `CHECK`, and **`UNIQUE (user_id, idempotency_key)`**.
- `order_items` with SKU, name and unit price **copied** from the product.
- `payments` (one per order) and `payment_events` (the webhook event id is the primary key).
- `outbox_events` (JSONB payload, nullable `published_at`, a partial index on pending rows).

Name every constraint (`uq_users_email`, `ck_products_stock`, …) so error handling and tests can refer
to it. Never edit a migration after it is merged. Add `V2__…` instead (you will do that in Phase 7).

### Step 2.4 · Watch the migrator apply it
Every track runs the same tiny migrator at start-up (no Flyway/Alembic, so the three tracks can share
one SQL file). It takes a PostgreSQL advisory lock, so two instances never migrate at the same time,
and applies each file in its own transaction. Start your app (`make run`) and inspect the result:
```bash
docker compose exec postgres psql -U shopflow -d shopflow -c '\d products' -c 'select * from schema_migrations'
```

<!-- track:java -->
Java: `tracks/java/src/main/java/org/aicancode/shopflow/db/Migrator.java`, called from the `migrator`
bean in `config/AppConfig.java`. The repository beans depend on it, so Spring cannot serve traffic
against an old schema.
<!-- /track -->

<!-- track:python -->
Python: `tracks/python/app/db.py` → `run_migrations()`, called in the FastAPI `lifespan` in `app/main.py`.
<!-- /track -->

<!-- track:typescript -->
TypeScript: `tracks/typescript/src/db.ts` → `runMigrations()`, awaited in `src/index.ts` before `listen()`.
<!-- /track -->

Now prove the constraints work by breaking them in `psql` (each must fail):
```sql
INSERT INTO users (email, password_hash, display_name, created_at) VALUES ('Asha@Example.com', 'x', 'A', now());
INSERT INTO products (sku, name, category, price_paise, stock, created_at, updated_at)
  VALUES ('BAG-1', 'Bag', 'bags', 49900, -1, now(), now());
UPDATE products SET stock = stock - 5 WHERE sku = 'BAG-1';   -- after inserting it with stock 2
```
The last one is the important one: even if the application has a bug, the database refuses to sell
stock it does not have.

### Step 2.5 · Map the contract
For every path in `spec/openapi.yaml`, write the FR number next to it (a table in `architecture.md`).
Any endpoint without a requirement, or any requirement without an endpoint, is a design bug: fix one of
the two. Check that every endpoint that changes data declares `security: bearerAuth`, except the
webhook (signature). Validate the file:
```bash
pipx run openapi-spec-validator spec/openapi.yaml       # or: npx @redocly/cli lint spec/openapi.yaml
```

### Step 2.6 · Commit
```bash
git switch -c docs/phase-2-design
git add spec/
git commit -m "docs(spec): architecture, ADRs 0001-0005, V1 schema"
```

## 4. AI-assist prompts

### Prompt 1: Mermaid sequence diagram for an idempotent checkout
```text
Write a Mermaid sequenceDiagram with participants Customer, App, Postgres for an idempotent checkout:
(1) POST /checkout with Idempotency-Key k1: look up an order by (user, k1), none found; price the cart in
integer paise; BEGIN; for each line UPDATE products SET stock = stock - q WHERE id = ? AND stock >= q
(0 rows -> ROLLBACK, 409); INSERT order, lines, a PENDING payment and an outbox event; empty the cart;
COMMIT; 201. (2) The same request again: return the stored order with 200. (3) Two identical requests
racing: the UNIQUE (user_id, idempotency_key) constraint lets one win; the loser returns the winner's
order. Use alt blocks and keep messages short.
```
**Verify the output by:** pasting it into https://mermaid.live (it must render), then walking through
each branch against FR-11, NFR-2 and NFR-3. Check that the stock check and the decrement are **one**
statement (not a SELECT followed by an UPDATE) and that nothing is written outside the transaction.

### Prompt 2: Review my schema
```text
Review this PostgreSQL 16 migration for a small online shop: <paste V1__init.sql>. Requirements:
<paste FR-1..18 and NFR-1..3>. Point out missing constraints, wrong types for money, missing or useless
indexes, anything that would allow overselling, a second order for the same idempotency key, a webhook
applied twice, or an order whose total does not add up. Do not add tables for out-of-scope features.
```
**Verify the output by:** running the migration (`make run`, then `\d orders`), trying the failure
cases by hand in `psql` (negative stock, a duplicate idempotency key, a total that does not add up, an
unknown order status) and running `EXPLAIN` on "my orders" (`WHERE user_id = ? ORDER BY id DESC LIMIT
20`): it must use `idx_orders_user`, not a `Seq Scan` (insert a few thousand rows first with
`generate_series`). Reject any suggestion to store money as `NUMERIC` in rupees *and* integers: one
representation only.

### Prompt 3: Steel-man the option I rejected
```text
For a small online shop, argue as convincingly as you can FOR reserving stock with SELECT ... FOR
UPDATE (pessimistic locking) instead of a single guarded UPDATE ... WHERE stock >= :q. Then list when
pessimistic locking is genuinely better. Keep it under 250 words.
```
**Verify the output by:** checking each claim against your ADR-0002 (round trips, lock duration,
deadlocks when two carts lock the same products in a different order, what happens on a crash) and
recording the strongest counter-argument in the ADR's *Consequences*. A good ADR admits what the
rejected option does better.

## 5. Deliverables
- [ ] `spec/architecture.md` with context, container, checkout, webhook, state machine and ER diagrams
  that render on GitHub.
- [ ] `spec/adr/0001…0005` accepted.
- [ ] `spec/db/migrations/V1__init.sql`; the app applies it on start-up (`schema_migrations` has version 1).
- [ ] Every OpenAPI path mapped to a requirement; the constraint experiments in Step 2.4 fail as expected.
- [ ] Compare: `git diff phase-1-end phase-2-end --stat`.

## 6. Self-check quiz

### Q1. Why is `UPDATE products SET stock = stock - 1 WHERE id = ? AND stock >= 1` safe when `SELECT stock` followed by `UPDATE` is not?
- [ ] It is faster
- [x] The check and the write happen in one statement under the row lock, so two buyers cannot both see "1 left"
- [ ] PostgreSQL caches the SELECT
- [ ] It does not need a transaction
> **Answer:** With read-check-write, two requests can read 1 at the same time and both write 0. The guarded update makes the second one match 0 rows, which the code turns into `409 insufficient-stock`.

### Q2. A 10 % coupon on a subtotal of 99 999 paise. What is the discount?
- [ ] 10 000 paise
- [x] 9 999 paise
- [ ] 9 999.9 paise
- [ ] 9 999.90 rupees
> **Answer:** 99 999 × 10 / 100 = 9 999.9, rounded **down** to 9 999 paise in integer arithmetic (ADR-0001). The shop never gives away more than it advertised, and there is no fractional paisa anywhere.

### Q3. Why is `UNIQUE (user_id, idempotency_key)` needed when the code already looks the key up first?
- [ ] It makes the lookup faster
- [x] Two identical requests can both pass the lookup at the same moment; the constraint lets only one INSERT win
- [ ] Keys must be unique across all users
- [ ] PostgreSQL requires it for foreign keys
> **Answer:** The lookup handles retries; the constraint handles races. The loser of the race catches the duplicate error and returns the winner's order instead.

### Q4. Why does adjusting stock NOT increase the product's `version`?
- [ ] Stock is not stored in the products table
- [x] Checkouts change stock constantly; bumping the version would make every admin edit during a sale fail with 409
- [ ] Optimistic locking only works for text columns
- [ ] The version is only for the outbox
> **Answer:** Optimistic locking protects human edits from each other. Stock is protected differently (the guarded decrement), so it must not interfere.

### Q5. The order is committed as PAID, then the process crashes before calling the message broker. With a transactional outbox, what happens?
- [ ] The event is lost
- [ ] The order is rolled back
- [x] The `order.paid` row was committed with the order; the relay publishes it later
- [ ] The broker retries automatically
> **Answer:** The event is written in the same transaction as the state change, so they exist together or not at all. The price is at-least-once delivery: consumers de-duplicate on the event id.

### Q6. Why are the product name and price copied into `order_items`?
- [ ] To save a JOIN
- [x] An order must show what the customer actually bought and paid, even after the product is renamed or repriced
- [ ] Products are deleted after checkout
- [ ] PostgreSQL cannot join on SKU
> **Answer:** An order is a historical record (and the basis of an invoice). Products change; orders must not change with them.

## 7. What you learned
- Diagrams-as-code with Mermaid: context, containers, sequences, a state machine, ER.
- Money as integers in the smallest unit, with an explicit rounding rule.
- Concurrency answers you can explain: a guarded update, a unique constraint, optimistic locking, compare-and-set state transitions.
- Idempotency keys, webhook de-duplication and the transactional outbox as answers to unreliable networks.
- Schema design with named constraints, CHECKs, generated columns, partial and GIN indexes.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
git switch -c my-phase-3 phase-2-end
git diff phase-1-end phase-2-end -- spec/        # the reference design
git checkout phase-2-end -- spec/db/migrations/V1__init.sql   # take only the schema
```
