# ShopFlow runbook

Operational how-tos for ShopFlow v1.x. Keep it short, keep it true: if a step here is wrong, fix it in
the same PR that taught you so.

| | |
|---|---|
| Service | ShopFlow REST API (one track deployed: `DEPLOY_TRACK`, default `python`) |
| Images | `ghcr.io/aicancode-org/shopflow-{java,python,typescript}:<version>` (`latest` = newest release) |
| Health | `GET /health` → `200 {"status":"UP","checks":{"database":"UP"}}`; `503` when the database is unreachable |
| Dependencies | PostgreSQL (required), the payment provider's webhook (required for orders to become PAID), LLM endpoint (optional; template/rules fallback) |
| Configuration | environment variables only: see `.env.example`. A deployment MUST set its own `JWT_SECRET`, `PAYMENT_WEBHOOK_SECRET` and `ADMIN_PASSWORD` |

## 1. Start and stop
**Laptop (Docker Compose):**
```bash
docker compose --profile python up -d --build --wait     # or --profile java / typescript
scripts/smoke-test.sh http://localhost:8080              # expect "... 0 failed"
docker compose --profile python logs -f app-python       # follow logs
docker compose --profile python down                     # stop (keeps data)
docker compose --profile python down -v                  # stop AND delete the database volume
```
**Without Docker for the app:** `docker compose up -d postgres`, then `cd tracks/<track> && make run`.
Port clash with another PostgreSQL or app? `POSTGRES_PORT=55432 APP_PORT=8081 docker compose …`.

**Render (optional):** Dashboard → service → **Manual Deploy** / **Suspend** / **Resume**. A free
instance sleeps when idle; the first request can take up to a minute. A sleeping instance also answers
the payment webhook late: the provider retries, and de-duplication makes the retries harmless.

## 2. Check that it works
1. `curl -s $URL/health`. `database` must be `UP`.
2. `SMOKE_ADMIN_EMAIL=… SMOKE_ADMIN_PASSWORD=… SMOKE_WEBHOOK_SECRET=… scripts/smoke-test.sh $URL`. The
   smoke test creates its own uniquely named customers (`alice-…@smoke.shopflow.test`,
   `bob-…@smoke.shopflow.test`), a smoke product with a little stock, carts and orders, pays one order
   through a signed webhook, and deactivates the product at the end. Without admin credentials add
   `SMOKE_ALLOW_NO_ADMIN=true`: the auth and read-only catalog checks still run. **Against production,
   the smoke orders are real rows**: they are easy to find by the e-mail domain and the `SMOKE-` SKU.
3. From v1.1: `curl -s $URL/metrics | grep _total`.

## 3. Release
1. Make sure `solution`/`main` CI is green (`ci-ok`).
2. `CHANGELOG.md`: move *Unreleased* under `## [x.y.z] - YYYY-MM-DD`; bump the track versions.
3. `git tag -a vX.Y.Z -m "ShopFlow vX.Y.Z" && git push origin vX.Y.Z`.
4. Watch **Actions → Deploy**: three `GHCR · <track>` jobs, then `Render (optional)`.

## 4. Roll back
The schema only ever changes in a backward-compatible way (expand-only migrations; see the Phase 7
migration rule), so the previous image runs fine against the newer schema. v1.0.0 against the v1.1
schema (`V2__order_payment_window.sql`) is tested: its inserts simply get the column default.

**Render:** call the deploy hook with the previous image:
```bash
IMG="ghcr.io/aicancode-org/shopflow-python:1.0.0"
curl -fsS -X POST "$RENDER_DEPLOY_HOOK_URL&imgURL=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=""))' "$IMG")"
```
Or Dashboard → **Events** → pick the previous deploy → **Rollback**. Then run the smoke test.

**Laptop:** `git switch --detach v1.0.0 && docker compose --profile python up -d --build --wait`.

Never "roll back" by deleting a git tag or a GHCR version. Releases are immutable; fix forward with a
new patch version. Never roll back by restoring an old database backup: that would lose paid orders.

## 5. Rotate a secret
| Secret | Where it lives | Rotate | Effect |
|---|---|---|---|
| `JWT_SECRET` | host env vars (Render) | generate `openssl rand -base64 48`, update, redeploy | every access token becomes invalid at once; users log in again |
| `PAYMENT_WEBHOOK_SECRET` | host env vars and the payment provider's dashboard | generate `openssl rand -hex 32`, set it at the provider and in the app as close together as possible, redeploy | webhooks signed with the old secret get `401` while the two differ; the provider retries, so pay attention to the order: update, then check that `PENDING_PAYMENT` orders become `PAID` |
| `ADMIN_PASSWORD` | host env vars | change it, redeploy | applied at start-up only when the admin is created; for an existing admin, change the password in the database or create a new admin and demote the old one |
| `DATABASE_URL` password | host env vars | reset the role password at the DB provider, update, redeploy, smoke test | |
| `LLM_API_KEY` | host env vars | revoke in the provider console, create a new one, update, redeploy | until then description drafts and search assist use template/rules (`fallback: true`) |
| `RENDER_DEPLOY_HOOK_URL` | GitHub environment `production` | Render → Settings → Deploy Hook → **Regenerate**, then `gh secret set RENDER_DEPLOY_HOOK_URL --env production` | |

**Suspected leak of `JWT_SECRET`:** rotate it immediately (anyone with it can mint ADMIN tokens).
**Suspected leak of `PAYMENT_WEBHOOK_SECRET`:** rotate it immediately (anyone with it can mark orders as
paid), then compare every `PAID` order since the leak with the provider's payment list. If a secret
leaked (committed, pasted into a prompt, printed in a log): **rotate first**, then clean up. Removing it
from git history does not un-leak it.

## 6. Common incidents
| Symptom | Likely cause | Action |
|---|---|---|
| `/health` → `503`, `database: DOWN` | Postgres unreachable / wrong `DATABASE_URL` | Check the DB provider status and the connection string (`sslmode=require` on hosted Postgres); app logs show the driver error. |
| All users get `401` after a deploy | `JWT_SECRET` changed or differs between instances | Expected once after a rotation. If it persists, make every instance use the same secret. |
| Orders stay `PENDING_PAYMENT` although customers paid | webhooks rejected (`401 invalid-signature`) or not arriving | Check the app logs and the provider's webhook log. Wrong secret → see §5. A proxy that re-encodes the body also breaks the signature (it covers the raw bytes). Then ask the provider to resend: duplicates are ignored by event id. |
| A customer was charged for a cancelled order | the payment arrived after the cancel (or after the v1.1 payment window) | Expected and handled: the order stays `CANCELLED` and a `refund.requested` event is in the outbox (`GET /api/v1/admin/outbox`). Make sure the refund is carried out at the provider. |
| `409 insufficient-stock` at checkout although the product page shows stock | someone else bought the last units in between, or the cart is older than the stock change | Expected (the guarded decrement refuses to oversell). If it happens for every product, check `products.stock` and recent admin stock edits. |
| A customer says a double tap created two orders | a client that does not send the same `Idempotency-Key` on retry | The API guarantees one order per key. Find both orders by user and time; cancel one (stock is restored). Fix the client. |
| Outbox events pile up (`pending=true` keeps growing) | the relay is not running, or the broker rejects events (log "publishing event … failed, will retry") | `POST /api/v1/admin/outbox/publish` runs one batch; it stops at the first failure and keeps the order. Fix the broker, run it again. Consumers de-duplicate on event id. |
| Search assist or description drafts are slow | LLM slow or hanging: every call waits up to `LLM_TIMEOUT_MS` | Set `AI_PROVIDER=offline` and redeploy, or lower `LLM_TIMEOUT_MS`. Checkout is not affected. (v1.1.0 adds a circuit breaker.) |
| AI responses always `fallback: true` | LLM unreachable, slow or key invalid | Expected without an LLM. Otherwise check `LLM_BASE_URL`, `LLM_MODEL` (model names change) and the key. |
| Container restarts at boot | migration failed, or `JWT_SECRET` / `PAYMENT_WEBHOOK_SECRET` shorter than 32 bytes | Logs show the cause. Fix the migration in a new `V<n>__*.sql`; never edit an applied one. |
| First request after idle takes ~1 min | free-tier cold start | Expected. Upgrade or accept. |

## 7. Data
- Back up: `docker compose exec -T postgres pg_dump -U shopflow shopflow > backup.sql` (hosted Postgres: use the provider's backups / point-in-time restore).
- Restore: `docker compose exec -T postgres psql -U shopflow shopflow < backup.sql`. Restoring loses
  every order since the backup; reconcile with the payment provider afterwards.
- Backups contain bcrypt hashes, e-mail addresses and order histories: treat them as personal data
  (encrypt, limit access, delete old ones).
- Money is stored as integer paise (`BIGINT`). Never export it through a tool that turns it into a
  floating-point number and back.
- Privacy: no card data ever reaches ShopFlow (the provider handles it, NFR-7); no IPs or user agents
  are stored. Products are soft-deleted (deactivated) so old orders keep their lines.
