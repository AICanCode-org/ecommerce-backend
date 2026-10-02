# ShopFlow: build an e-commerce backend that never oversells, with AI as your pair programmer

[![CI](https://github.com/AICanCode-org/ecommerce-backend/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/AICanCode-org/ecommerce-backend/actions/workflows/ci.yml)
[![CI (solution)](https://github.com/AICanCode-org/ecommerce-backend/actions/workflows/ci.yml/badge.svg?branch=solution)](https://github.com/AICanCode-org/ecommerce-backend/actions/workflows/ci.yml?query=branch%3Asolution)

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
