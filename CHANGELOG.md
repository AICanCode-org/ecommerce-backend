# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.0] - 2026-10-02
### Added
- Requirements (FR-1..18, NFR-1..8, threats T1..T11, user stories with Given/When/Then), architecture
  diagrams, order state machine, ADRs 0001-0005 (integer paise, guarded stock decrement, optimistic
  locking, idempotent checkout + webhook de-duplication, transactional outbox), `V1__init.sql`,
  OpenAPI 1.0.0.
- Auth in all three tracks: registration (always CUSTOMER), bcrypt, 60-minute HS256 JWTs (algorithm
  pinned), roles CUSTOMER and ADMIN, an admin bootstrapped from the environment.
- Catalog: products with SKU, price in paise and stock; keyset pagination, category and price filters,
  PostgreSQL full-text search; admin create/edit with optimistic locking (`409 version-conflict`) and
  soft delete; category list with counts.
- Server-side cart (quantity limits, prices re-read at checkout) and coupons (percent or flat, minimum
  order, exact integer rounding).
- Checkout: `Idempotency-Key` required (replay returns the same order with `Idempotent-Replayed: true`,
  a different body is `422`), guarded stock decrement in one transaction (`409 insufficient-stock`, never
  negative), orders with a state machine (PENDING_PAYMENT → PAID → SHIPPED → DELIVERED, CANCELLED),
  customer cancel restores stock, someone else's order is `404`.
- Payments: a payment record per order (fake provider), HMAC-SHA256 signed webhook (raw body, constant-time comparison),
  de-duplication by event id, a payment after cancel requests a refund.
- Transactional outbox (`order.placed`, `order.paid`, `order.cancelled`, `refund.requested`) and an admin
  endpoint to read it.
- AI: product description drafts (never saved automatically, template fallback) and search assist
  (natural language → validated filters, rules fallback). Ollama/Gemini/Groq optional, timeouts everywhere.
- Unit + integration tests per track (including parallel checkouts for the last units and webhook
  replays), 85 % line-coverage gate.
- CI: path filters, reusable per-track workflow with a PostgreSQL service container, contract checks
  (OpenAPI, shellcheck, CMS export), Docker image smoke test, `ci-ok`.
- Dockerfiles (multi-stage, non-root, health check), Compose profiles per track,
  `scripts/smoke-test.sh` (73 checks).
- `deploy.yml`: images to GHCR on version tags; optional Render deploy.
- `docs/runbook.md`.

## [0.1.0] - starter
### Added
- Starter: three track skeletons (Java, Python, TypeScript) with the complete (pending) test suite,
  shared OpenAPI contract, `/health`, Docker Compose with PostgreSQL, minimal CI.
