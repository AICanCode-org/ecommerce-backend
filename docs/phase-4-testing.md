---
phase: 4
slug: phase-4-testing
title: Testing (unit, integration, security, coverage)
duration: 4 h
start_tag: phase-3-end
end_tag: phase-4-end
---
# Phase 4 · Testing: integration, security and concurrency tests, coverage gate

## 1. Why it matters
Unit tests with in-memory fakes prove your logic. They cannot prove that your SQL is valid, that the
database really refuses negative stock, that a failed checkout leaves **nothing** behind, or that ten
customers racing for the last three units really get exactly three orders. Integration tests against a
**real** PostgreSQL do. A coverage gate then stops the suite from quietly rotting. Together they let you
change code (or accept an AI's refactor) without fear, which is exactly what you need before CI in Phase 5.

## 2. Objectives
- Explain the test pyramid for ShopFlow: unit (fast, many), integration (real database), smoke (black-box, few).
- Run the integration suite against the Compose database using a **separate** test database.
- Prove money, security and concurrency behaviour at the database level: hashed passwords, unique
  constraints, all-or-nothing checkout, no overselling under parallel checkouts, one order per
  idempotency key, a webhook delivered twice at once applied once.
- Enforce ≥ 85 % line coverage locally and understand what the number does and does not mean.

## 3. Step-by-step instructions

### Step 4.1 · Know the layers you already have
| Layer | Where | Needs | Run |
|---|---|---|---|
| Unit | `…/unit/…`, in-memory repositories, fake clock, fake LLM | nothing | `make test` |
| Integration | `…/integration/…`, the real app against real PostgreSQL | `docker compose up -d postgres` | `make test-integration` |
| Smoke (black-box) | `scripts/smoke-test.sh` (Phase 6) | a running app | `scripts/smoke-test.sh` |

The integration tests use `TEST_DATABASE_URL` (default `…/shopflow_test`), so they **truncate test data,
never your dev data**. They use bcrypt cost 4 and their own throw-away JWT and webhook secrets.

### Step 4.2 · Turn the integration tests on
Delete the last line of your pending list (the integration entry), then:
```bash
docker compose up -d postgres
make test-integration
```

<!-- track:java -->
Java: `ApiIntegrationTest` is tagged `@Tag("integration")` and excluded by default; `-Pintegration`
selects it (`mvn verify -Pintegration`). It boots the **whole** Spring app on a random port
(`@SpringBootTest(webEnvironment = RANDOM_PORT)`) and calls it over HTTP. `@DynamicPropertySource`
points the app at the test database and sets the test-only secret and admin.
<!-- /track -->

<!-- track:python -->
Python: tests marked `pytest.mark.integration` are deselected by default (`addopts` in
`pyproject.toml`); `make test-integration` runs `pytest -m integration`. The app is created with
`create_app(Settings(...))` pointing at the test database and driven with FastAPI's `TestClient`.
<!-- /track -->

<!-- track:typescript -->
TypeScript: integration tests live in `test/integration/` and run with `npm run test:integration`
(vitest). The suite builds the real Fastify app with the `Pg*Repository` classes via `buildApp()` and
uses `app.inject()`; `beforeEach` truncates all tables with `RESTART IDENTITY CASCADE`.
<!-- /track -->

### Step 4.3 · Read the money, security and concurrency tests
Open the integration file and find these cases. Each maps to a requirement:
- **migrations are recorded and the demo coupons are seeded** (FR-10, Phase 2).
- **passwords are hashed and e-mails unique** (FR-1, NFR-4): the test reads `users.password_hash` with
  SQL and checks for a bcrypt prefix; a duplicate e-mail fails even as a direct insert.
- **SKU uniqueness and optimistic locking in the database** (FR-5, ADR-0003).
- **full-text search stems and filters** ("shoe" finds "Shoes") and **keyset pagination over the real
  index** (FR-4).
- **the database refuses negative stock** (`ck_products_stock`, NFR-2): even an UPDATE by hand fails.
- **checkout is one transaction** (FR-11): a cart whose *second* line is out of stock leaves the first
  product's stock, the orders table, the payments and the outbox exactly as they were.
- **concurrent checkouts never oversell** (NFR-2, ADR-0002): many customers check out the last units
  at once; exactly `stock` of them get `201`, the rest `409 insufficient-stock`, and stock ends at 0.
- **concurrent retries with one key create one order** (NFR-3, ADR-0004).
- **a webhook delivered twice at once is processed exactly once** (FR-13).
- **cancel restocks and the relay marks events published** (FR-12, FR-14, ADR-0005).
- **the admin is bootstrapped from the environment** (FR-3) and **health reports DOWN when the
  database is unreachable** (FR-18).

### Step 4.4 · Prove one test can fail
A test you have never seen fail proves nothing. Pick the overselling test and break the code on purpose
in a scratch branch: change your guarded decrement so it reads the stock first and writes
`stock - quantity` in a second statement (or simply drop `AND stock >= :q` from the `UPDATE`). Run
`make test-integration` (repeat a few times if needed): it must go red, for the right reason (more
`201`s than units, or the CHECK constraint firing as a `500`). Restore the line. Do the same for the
`status = :from` condition of the order transition and the webhook test.

### Step 4.5 · Coverage gate
```bash
make coverage        # unit + integration, fails below 85 % line coverage
```

<!-- track:java -->
JaCoCo: `mvn verify -Pcoverage`; HTML report in `target/site/jacoco/index.html`; the rule is in the
`coverage` profile of `pom.xml`.
<!-- /track -->

<!-- track:python -->
pytest-cov: `--cov-fail-under=85` in the Makefile; `coverage.xml` is written for CI; add
`--cov-report=html` for a browsable report.
<!-- /track -->

<!-- track:typescript -->
@vitest/coverage-v8: `thresholds: { lines: 85 }` in `vitest.config.ts`; report in `coverage/`.
<!-- /track -->

Look at the uncovered lines. Are they untested **behaviour** (write a test) or genuinely unreachable
glue (fine)? 100 % is not the goal. Tested behaviour is. In a shop, an uncovered `if` that
refuses something (stock, a role, a signature) is worth a test every time.

### Step 4.6 · Commit
```bash
git switch -c test/phase-4-integration
git commit -am "test: enable integration suite and coverage gate"
```

## 4. AI-assist prompts

### Prompt 1: Missing integration scenarios
```text
These are my integration tests for a shop API (cart, idempotent checkout, payment webhook) backed by PostgreSQL: <paste test names>.
These are my requirements and threats: <paste FR/NFR/T>. Which requirements are only covered by unit
tests with in-memory fakes, where a fake could hide a real bug (SQL, constraints, transactions,
concurrency, time zones)? Propose up to 3 new integration tests with their exact assertions.
```
**Verify the output by:** writing one of the proposed tests, making it fail on purpose (break the SQL or
drop the constraint in a scratch database), and confirming it fails for the right reason before you
restore the code.

### Prompt 2: Is this test flaky?
```text
This test sometimes fails in CI but passes locally: <paste test + failure output>. List the likely
sources of non-determinism (time, ordering without ORDER BY, shared state, async, concurrency) and how
to make the test deterministic without adding sleeps.
```
**Verify the output by:** running the test 20 times in a loop
(`for i in $(seq 20); do make test-integration || break; done`) before and after the change.

### Prompt 3: Write a race test
```text
I want an integration test proving that when 8 customers check out concurrently and the product has
stock 3, exactly 3 orders are created and stock ends at 0. Here is my test setup: <paste>. Write the
test in <pytest | Vitest | JUnit 5> using real concurrency (threads or Promise.all), and explain why it
would fail if the stock check were a SELECT followed by an UPDATE.
```
**Verify the output by:** replacing the guarded UPDATE with read-then-write and checking that the
test fails at least once in 10 runs, then restoring it and checking it passes 20 times in a row. A race test that never fails without
the fix is not testing the race.

## 5. Deliverables
- [ ] Pending list empty; `make test-integration` passes against the Compose database.
- [ ] `make coverage` passes (≥ 85 %), with a note in the PR on what the uncovered lines are.
- [ ] Evidence in the PR that the overselling test fails when the guarded decrement is removed.
- [ ] `AI_LOG.md`: one entry about a test an assistant suggested and how you validated it.

## 6. Self-check quiz

### Q1. Which bug can ONLY an integration test catch here?
- [ ] A wrong error message for a too-long product name
- [x] A typo in the `UPDATE products SET stock = stock - :q WHERE … AND stock >= :q` statement
- [ ] A coupon cap that is applied in the wrong order
- [ ] A missing role check in `CatalogService.create`
> **Answer:** Unit tests use the in-memory repositories, so the SQL never runs there. The other bugs live in pure logic and are covered by unit tests.

### Q2. How does the integration suite prove that passwords are not stored in clear text?
- [ ] It trusts the `PasswordHasher` unit test
- [x] It reads `users.password_hash` with SQL and checks it is a bcrypt hash, not the password
- [ ] It logs in with the password
- [ ] It checks the HTTP response does not contain the password
> **Answer:** The response and the database are different places a secret can leak. Only a test that looks at the stored row proves the storage requirement.

### Q3. Why does the concurrency test need real parallel requests?
- [ ] Sequential requests are too slow
- [x] The overselling bug only appears when two checkouts read the same stock before either writes
- [ ] The database rejects sequential requests
- [ ] To test the connection pool size
> **Answer:** Sequentially, the second checkout already sees the lower stock. The guarded statement under PostgreSQL's row lock is what makes the parallel case safe.

### Q4. Your coverage is 93 %. What does that prove?
- [ ] The code has no security bugs
- [ ] All requirements are implemented
- [x] 93 % of lines executed during the tests. It says nothing about whether the assertions are meaningful
- [ ] The integration tests are not needed
> **Answer:** Coverage finds *untested* code; it cannot find *wrong* tests. Use it as a floor, not a target.

### Q5. Why break the code on purpose and watch a test fail?
- [ ] To practise git
- [x] A test that cannot fail gives false confidence; seeing it go red proves it checks the behaviour
- [ ] The coverage tool requires it
- [ ] To make CI slower
> **Answer:** It is a cheap form of mutation testing. Assistants often write tests that pass against any implementation.

## 7. What you learned
- The test pyramid and what each layer can and cannot prove.
- Proving "all-or-nothing" by checking every table a failed checkout could have touched.
- Real-database integration tests with an isolated database and clean state per test.
- Testing money and security properties where they live: stored rows, constraints and concurrent requests.
- Seeing a test fail on purpose, and coverage gates as a floor, not a goal.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
git switch -c my-phase-5 phase-4-end
git diff phase-3-end phase-4-end          # just the pending-list change: the tests were there all along
```
