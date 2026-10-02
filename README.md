# ShopFlow: build an e-commerce backend that never oversells, with AI as your pair programmer

[![CI](https://github.com/AICanCode-org/ecommerce-backend/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/AICanCode-org/ecommerce-backend/actions/workflows/ci.yml)
[![CI (solution)](https://github.com/AICanCode-org/ecommerce-backend/actions/workflows/ci.yml/badge.svg?branch=solution)](https://github.com/AICanCode-org/ecommerce-backend/actions/workflows/ci.yml?query=branch%3Asolution)

## Case study (reference solution)

> **ShopFlow is the backend of a small online shop that never oversells, never charges twice and never
> loses a payment, with AI search and product copy that are allowed to fail. It is built three times,
> in Java, Python and TypeScript, against one contract.**

```console
$ curl -s -X POST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' \
    -d '{"email":"meera@example.com","password":"correct-horse-42","displayName":"Meera","role":"ADMIN"}'
{"id":27,"email":"meera@example.com","displayName":"Meera","role":"CUSTOMER", ...}          # role ignored
$ curl -si -X POST localhost:8080/api/v1/checkout -H "Authorization: Bearer $MEERA" -H 'Idempotency-Key: meera-tap-0001'
HTTP/1.1 201 Created
{"id":18,"status":"PENDING_PAYMENT","subtotalPaise":59900,"discountPaise":0,"shippingPaise":4900, ...}
$ curl -si -X POST localhost:8080/api/v1/checkout -H "Authorization: Bearer $MEERA" -H 'Idempotency-Key: meera-tap-0001'
HTTP/1.1 200 OK
idempotent-replayed: true                                                                     # same order, no second charge
$ curl -si -X POST localhost:8080/api/v1/checkout -H "Authorization: Bearer $RAVI" -H 'Idempotency-Key: ravi-tap-0001'
HTTP/1.1 409 Conflict
{"type":"https://shopflow.example/problems/insufficient-stock","title":"Insufficient stock","status":409, ...}
$ curl -s -X POST localhost:8080/api/v1/payments/webhook -H 'X-Signature: sha256=0000' -d '{"type":"payment.succeeded", ...}'
{"type":"https://shopflow.example/problems/invalid-signature","title":"Invalid signature","status":401, ...}
```

**Problem → solution.** Most shop tutorials stop at "add to cart works" and leave out the parts that
cost real shops real money: floating-point prices, two customers buying the last item, a double tap
creating two orders, a forged or replayed "payment succeeded", a lost "order paid" message. ShopFlow
handles each of them on purpose and proves it with tests: money as integer paise with a database
`CHECK` on the total, a guarded stock decrement inside the checkout transaction, an `Idempotency-Key`
on checkout, an HMAC-SHA256 signed webhook compared in constant time and de-duplicated by event id, a
transactional outbox, optimistic locking for product edits, someone else's order `404`, bcrypt and
short-lived HS256 JWTs with the algorithm pinned. It runs on a laptop with one command and needs no
API key.

**Architecture.** One stateless API container per track and PostgreSQL as the only stateful component:
see the diagrams and the order state machine in [`spec/architecture.md`](spec/architecture.md). Three
decisions worth discussing:
1. **A guarded decrement instead of locks**: `UPDATE products SET stock = stock - ? WHERE id = ? AND
   active AND stock >= ?` must affect one row (rows locked in product-id order, so no deadlocks), otherwise the checkout rolls back, plus `CHECK (stock >= 0)` as the
   last line of defence ([ADR-0002](spec/adr/0002-guarded-stock-decrement.md)).
2. **Idempotency at both ends of the payment**: a unique `(user, Idempotency-Key)` for checkout and a
   unique provider event id for webhooks, so retries are harmless in both directions
   ([ADR-0004](spec/adr/0004-idempotent-checkout-and-webhook-dedup.md)).
3. **A transactional outbox**: `order.paid` is written in the same transaction as the status change and
   published afterwards (at least once), so the database and the broker cannot disagree
   ([ADR-0005](spec/adr/0005-transactional-outbox.md)).

**Security and quality evidence (v1.1.0, measured on the maintainers' Linux build box with Docker).**

| | Java 21 / Spring Boot | Python / FastAPI | TypeScript / Fastify |
|---|---|---|---|
| Tests (unit + integration) | 153 unit + 14 integration | 160 unit + 14 integration | 157 unit + 14 integration |
| Line coverage (gate 85 %) | 96.3 % (JaCoCo) | 97.8 % (coverage.py) | 97.3 % (V8) |
| Docker smoke test (`scripts/smoke-test.sh`) | 81/81 (+ 84/84 with the LLM unreachable) | 81/81 (+ 84/84) | 81/81 (+ 84/84) |

Each threat in [`spec/requirements.md`](spec/requirements.md) (T1–T11: forged and replayed webhooks,
double checkout, overselling, `role` mass assignment, order enumeration, price tampering, lost product
edits, lost events, prompt injection, account enumeration) maps to unit and integration tests, and the
black-box smoke test re-checks the most important ones against every Docker image. Every phase tag
(`phase-0-end` … `phase-8-end`) builds and passes the tests of all three tracks
(`scripts/verify-tags.sh`). CI runs lint, unit and integration tests against a PostgreSQL service
container, a coverage gate, contract checks and a Docker smoke test per track. Release tags publish
images to GHCR.

**Operations.** `/health` checks the database. `/metrics` exposes eight Prometheus counters (orders
placed, checkout replays, duplicate webhooks, expired orders, AI fallbacks, LLM short-circuits, …), and
every log line is JSON with the request's `X-Request-ID`. v1.1.0 adds a 15-minute payment window so
abandoned checkouts give their stock back, shipped as an expand-only migration that v1.0.0 also runs
on. A **game day** made the LLM hang: search assist kept answering through the offline rules and
checkout was untouched, but in v1.0.0 each assist waited the full 5 s timeout, and in the Python track
50 concurrent assists slowed even `/health` to 4 s. A circuit breaker fixed it in v1.1.0 (10 assists
during the fault: avg ≈ 1.5 s, three wait for the timeout and seven answer in milliseconds):
[post-mortem](docs/postmortem-2026-10-02-llm-hang.md), [runbook](docs/runbook.md).

**How AI was used.** Assistants drafted the starter stubs, Dockerfiles, workflow skeletons, test
scaffolding, the game-day fake LLM and first versions of docs. Their output needed the strictest review
exactly where it mattered: money arithmetic, stock checks, signature comparison, hints attached to the
wrong method, and version-specific framework details. Every suggestion was verified by tests,
`actionlint`, the smoke test or official documentation, and logged in [`AI_LOG.md`](AI_LOG.md).
See also [`docs/retrospective.md`](docs/retrospective.md).

**What I'd do next.** A scheduled outbox relay with a real broker; rate limiting on login; a
hanging-LLM game day in CI with a latency assertion; an async LLM client or a bulkhead in the Python
track; real payment-provider integration (Razorpay test mode) behind the same webhook contract.

---

ShopFlow is a guided project from **[AiCanCode](https://www.aicancode.org)**. Over 9 phases (about 36
hours) you build the backend of a small Indian online shop like a professional team would:
requirements and threats, design, a test-first implementation, CI/CD, containers, observability and a
retrospective. AI assistants help you at every step, and you **verify** everything they produce.

**What you build:** a REST API with a product catalog, a server-side cart with coupons, checkout,
orders with a state machine, and payments confirmed by a (fake) payment provider. The hard parts are
the ones that cost real shops real money, and they are done properly:

- **money as integer paise** everywhere (API, code, database), with exact coupon rounding and a
  database `CHECK` that the total adds up;
- **no overselling**: stock is decremented with one guarded statement inside the checkout transaction,
  and the database refuses negative stock; proven with parallel checkouts for the last units;
- **idempotent checkout** (`Idempotency-Key`): a double tap or a retry never creates a second order;
- a **signed payment webhook** (HMAC-SHA256, constant-time comparison) that is **de-duplicated** by
  event id, and handles a payment that arrives after a cancel (refund requested);
- a **transactional outbox** so `order.paid` can never be lost between the database and a broker;
- bcrypt, short-lived **JWTs** (HS256 pinned), roles (CUSTOMER, ADMIN), optimistic locking for product
  edits, someone else's order is `404`, errors as RFC 9457 `application/problem+json`.

Shoppers get a keyset-paginated catalog with **PostgreSQL full-text search** and an **AI search
assist** that turns *"running shoes under 3000 in stock"* into filters (validated like user input).
The owner gets **AI product description drafts** that are never saved automatically. Both AI features
work fully offline with deterministic rules/templates; a local Ollama model or a free Gemini/Groq key
are optional, and checkout never depends on them.

**Pick one track:** the spec, database, API contract, tests and smoke test are shared. Only the code differs.

| Track | Stack | Folder |
|---|---|---|
| Java | Java 21 · Spring Boot 4 (Web MVC + JDBC) · Spring Security Crypto (BCrypt) · Nimbus JOSE+JWT · JUnit 5 · JaCoCo | `tracks/java` |
| Python | Python 3.11+ · FastAPI · psycopg 3 · PyJWT · bcrypt · pytest · coverage | `tracks/python` |
| TypeScript | Node 20+ · Fastify 5 · `pg` · `jose` · bcryptjs · Vitest · ESLint | `tracks/typescript` |

**Why these three tracks?** They are the three stacks most often asked for in backend job posts for
juniors, and each teaches the same security ideas with a different amount of "magic": Spring Boot is
the enterprise standard (we deliberately use only `spring-security-crypto`, not the filter chain, so you
see every check), FastAPI is the fastest way to a typed, documented Python API, and
Fastify is a lean, fast Node framework where nothing is hidden. Because the contract and tests are
shared, you can switch tracks later or compare all three.

Course repository: **<https://github.com/AICanCode-org/ecommerce-backend>** (`main` = starter, `solution` = reference).

Everything runs **on your laptop** with Docker Compose (app + PostgreSQL). Deploying to the cloud is an
**optional** extra in Phase 6 and needs no credit card.

---

## Repository layout
```text
.
├── README.md                 ← you are here
├── AI_LOG.md                 ← your log of AI prompts, outputs and how you verified them
├── docs/                     ← the course: phase-0 … phase-8 + GitHub Actions explained
├── spec/                     ← shared spec: requirements, architecture, ADRs, openapi.yaml, db/
├── tracks/{java,python,typescript}/   ← code + tests (same Makefile targets in each)
├── scripts/                  ← doctor.sh, check-all.sh, verify-tags.sh, build-cms.py (+ smoke-test.sh from Phase 6)
├── cms/                      ← machine-readable export of the course for the AiCanCode site
├── docker-compose.yml        ← PostgreSQL (+ the app per track from Phase 6, optional Ollama)
└── .github/                  ← CI/CD workflows, issue/PR templates (+ Dependabot from Phase 5)
```

## Prerequisites (per operating system)
You need **Git, Docker with Compose v2, `make`, `curl`** plus the toolchain of **one** track.
Run `scripts/doctor.sh` at any time to see what is missing.

| | Windows 10/11 | macOS | Linux (Ubuntu/Debian) |
|---|---|---|---|
| Shell | **WSL2** with Ubuntu: `wsl --install` in an admin PowerShell, reboot | Terminal (zsh) | any |
| Docker | Docker Desktop, *Use the WSL 2 based engine* + *WSL integration* for Ubuntu enabled | Docker Desktop (or Colima) | Docker Engine + `docker-compose-plugin`; `sudo usermod -aG docker $USER`, log out/in |
| Git, make, curl | inside WSL: `sudo apt install git make curl` | `xcode-select --install` | `sudo apt install git make curl` |
| Java track | inside WSL: SDKMAN → `sdk install java 21-tem && sdk install maven` | SDKMAN or `brew install openjdk@21 maven` | SDKMAN |
| Python track | inside WSL: `sudo apt install python3 python3-venv` | `brew install python@3.12` | `sudo apt install python3 python3-venv` |
| TypeScript track | inside WSL: nvm → `nvm install 22` | nvm or `brew install node@22` | nvm |

**Windows, important:** clone and work **inside the WSL file system** (`~/code/ecommerce-backend`), not under
`/mnt/c/...`. It is many times faster and avoids line-ending and file-watching problems. VS Code: install
the *WSL* extension and run `code .` from the WSL terminal.

Hardware: 8 GB RAM is enough. The optional Ollama model (`llama3.2:1b`) needs about 2 GB more disk/RAM.

## Quick start (5 minutes)
```bash
git clone https://github.com/<you>/ecommerce-backend.git && cd ecommerce-backend   # your fork (docs/phase-0-setup.md)
scripts/doctor.sh                        # check your tools
cp .env.example .env                     # optional: change ports / AI provider
docker compose up -d postgres            # database (also creates shopflow_test for the tests)
cd tracks/python                         # or tracks/java, tracks/typescript
make install                             # dependencies
make lint test                           # a few passing tests, the rest are "pending" until Phase 3
make run                                 # http://localhost:8080
curl -s localhost:8080/health            # {"status":"UP","checks":{"database":"UP"}}
```

Every track offers the same commands:

| Command | What it does |
|---|---|
| `make install` | install dependencies (Python: creates `.venv`) |
| `make lint` | linter + formatter check (Checkstyle / ruff / ESLint + Prettier + tsc) |
| `make test` | unit tests (no database needed) |
| `make test-integration` | integration tests against the `shopflow_test` database |
| `make coverage` | unit + integration with an 85 % line-coverage gate |
| `make run` | start the API on port 8080 (with local-only dev defaults for the JWT and webhook secrets and the admin) |
| `make build` | build the Docker image (from Phase 6) |

From Phase 6 on, the app runs in Docker too: `docker compose --profile python up --build`
(or `--profile java` / `--profile typescript`), then `scripts/smoke-test.sh http://localhost:8080`.

## How the phases work
| Phase | Topic | You end at tag |
|---|---|---|
| 0 | [Setup and orientation](docs/phase-0-setup.md) | `phase-0-end` |
| 1 | [Requirements, threats and user stories](docs/phase-1-requirements.md) | `phase-1-end` |
| 2 | [Design: architecture, money, concurrency, DB schema, API contract](docs/phase-2-design.md) | `phase-2-end` |
| 3 | [Implementation in five milestones](docs/phase-3-implementation.md) | `phase-3-end` |
| 4 | [Testing: integration, money, security and concurrency tests, coverage gate](docs/phase-4-testing.md) | `phase-4-end` |
| 5 | [CI with GitHub Actions](docs/phase-5-ci.md) | `phase-5-end` |
| 6 | [Containerise, deliver, optional deploy](docs/phase-6-deploy.md) | `phase-6-end` = **`v1.0.0`** |
| 7 | [Observability and maintenance](docs/phase-7-observability.md) | `phase-7-end` = **`v1.1.0`** |
| 8 | [Retrospective and portfolio](docs/phase-8-retrospective.md) | `phase-8-end` |

Each phase page has the same 8 blocks: *Why it matters · Objectives · Step-by-step instructions ·
AI-assist prompts · Deliverables · Self-check quiz · What you learned · Catch-up git commands*.

### Branches and tags
- **`main`**: the **starter**. Tracks have the structure, the full (pending) test suite and `/health`;
  the business logic is `TODO`.
- **`solution`**: the reference implementation, one or more commits per phase, with an annotated tag at
  the end of every phase: `phase-0-end` … `phase-8-end`, plus releases `v1.0.0` and `v1.1.0`.
  Every tag builds and passes the tests of all three tracks.

**Stuck or behind?** Jump to the end state of the previous phase and continue from there:
```bash
git fetch upstream --tags
git switch -c my-phase-4 phase-3-end     # start Phase 4 from the reference end of Phase 3
```
**Compare your work with the reference:** `git diff phase-3-end -- tracks/python`.
**Peek at a single file:** `git show phase-3-end:tracks/python/app/orders.py`.
Try first, then compare. You learn far more that way.

### Pending tests
The complete test suite already exists on `main` but is switched off in one list per track
(`Pending.java`, `tests/conftest.py`, `test/pending.ts`). In Phase 3 you remove one entry at a time,
watch the tests fail, and make them pass. Skipped tests are reported as skipped, never as passed.

## Working with AI
Use any assistant you like. Each phase contains copy-paste prompts and a **"Verify the output by"**
checklist. Record what you asked and how you checked it in [`AI_LOG.md`](AI_LOG.md). Never paste
secrets, tokens, `.env` files or personal data into a prompt. Money, concurrency and security code get the
strictest review: an assistant that "simplifies" a stock check into read-then-write, or compares a
signature with `==`, is the classic way real shops get broken.

## More
- [docs/README.md](docs/README.md): index of all docs
- [docs/github-actions-explained.md](docs/github-actions-explained.md): every CI/CD concept, step by step
- [spec/README.md](spec/README.md): the shared specification
- [CONTRIBUTING.md](CONTRIBUTING.md) · [CHANGELOG.md](CHANGELOG.md) · [LICENSE](LICENSE) (MIT)

Questions or problems: open an issue using the templates, or contact us via <https://www.aicancode.org/contact>.
