---
phase: 6
slug: phase-6-deploy
title: Containerise, deliver, and (optionally) deploy to a free tier
duration: 3 h
start_tag: phase-5-end
end_tag: phase-6-end
release: v1.0.0
---
# Phase 6 · Containerise, deliver and (optionally) deploy

## 1. Why it matters
A container image is the unit you actually ship: the same bytes run on your laptop, in CI and in
the cloud. In this phase you write a production-grade Dockerfile, run the whole stack with one
command, prove it with a black-box smoke test, and publish versioned images to GitHub Container
Registry (GHCR) automatically on every release tag. Deploying to the cloud is **optional**: everything
graded in this phase works offline on your laptop.

## 2. Objectives
- Write a multi-stage, non-root Dockerfile with a `HEALTHCHECK` for your track.
- Add your app to `docker-compose.yml` as a profile and start the stack with `docker compose --profile <track> up`.
- Run the language-agnostic smoke test against your container (and against the other tracks' images).
- Add a Docker build + smoke job to CI.
- Write `deploy.yml`: on a `v*.*.*` tag, build and push images to GHCR; optionally trigger a Render
  deploy that is **skipped gracefully** when no secret is configured.
- Cut release **v1.0.0** with a CHANGELOG entry.

## 3. Step-by-step instructions

### Step 6.1 · The Dockerfile (build context = repository root)
All tracks copy the shared migrations into the image, so the build context is the repo root and the
Dockerfile lives in the track: `docker build -f tracks/<track>/Dockerfile .`. Rules:
- **Multi-stage:** compilers and dev dependencies stay in the build stage.
- **Dependencies before source:** copy the manifest, install, then copy `src`, so code changes reuse the dependency layer.
- **Non-root user**, `EXPOSE 8080`, `HEALTHCHECK` hitting `/health`, `MIGRATIONS_DIR=/app/migrations`.

<!-- track:java -->
```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY tracks/java/pom.xml tracks/java/checkstyle.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY tracks/java/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests -Dcheckstyle.skip -Djacoco.skip=true \
    && java -Djarmode=tools -jar target/app.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre
ENV PORT=8080 MIGRATIONS_DIR=/app/migrations JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
COPY spec/db/migrations ./migrations
USER 10001
HEALTHCHECK CMD curl -fsS http://127.0.0.1:8080/health > /dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```
(abridged; the full file installs `curl` and sets the health-check timings)
<!-- /track -->

<!-- track:python -->
```dockerfile
FROM python:3.12-slim AS build
WORKDIR /build
COPY tracks/python/requirements.txt .
RUN pip wheel --no-cache-dir --wheel-dir /wheels -r requirements.txt

FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PORT=8080 MIGRATIONS_DIR=/app/migrations
WORKDIR /app
COPY --from=build /wheels /wheels
RUN pip install --no-cache-dir /wheels/* && rm -rf /wheels && useradd --system --uid 10001 appuser
COPY tracks/python/app ./app
COPY spec/db/migrations ./migrations
USER 10001
HEALTHCHECK CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=2).status == 200 else 1)"
CMD ["python", "-m", "app"]
```
<!-- /track -->

<!-- track:typescript -->
```dockerfile
FROM node:22-alpine AS build
WORKDIR /build
COPY tracks/typescript/package.json tracks/typescript/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tracks/typescript/tsconfig.json tracks/typescript/tsconfig.build.json ./
COPY tracks/typescript/src ./src
RUN npm run build

FROM node:22-alpine
ENV NODE_ENV=production PORT=8080 MIGRATIONS_DIR=/app/migrations
WORKDIR /app
COPY tracks/typescript/package.json tracks/typescript/package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund && npm cache clean --force
COPY --from=build /build/dist ./dist
COPY spec/db/migrations ./migrations
USER node
HEALTHCHECK CMD node -e "fetch('http://127.0.0.1:8080/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "dist/index.js"]
```
<!-- /track -->

The root `.dockerignore` (already in the starter: `**/node_modules`, `**/.venv`, `**/target`, `.git`, …)
keeps the build context small and excludes `.env`, so local secrets never reach the Docker daemon. Build and check the size and user:
```bash
docker build -f tracks/<track>/Dockerfile -t shopflow-<track>:local .
docker image ls shopflow-<track>
docker run --rm --entrypoint id shopflow-<track>:local      # must NOT be uid=0
```

### Step 6.2 · Compose profiles: one command for the whole stack
Add a service per track to `docker-compose.yml` under a **profile**, sharing one block of environment
variables (a YAML anchor `x-app-env`), `depends_on` with `condition: service_healthy`, and port
`${APP_PORT:-8080}:8080`. Inside Compose the app reaches the database as `postgres:5432`, not `localhost`.
The Compose file sets **local-only** values for `JWT_SECRET` and the first admin
(`admin@shopflow.local` / `admin-password-123`) through `${VAR:-default}`, so `docker compose up` works out
of the box. A real deployment sets its own values: the defaults are public, in this repository.
```bash
docker compose --profile <track> up -d --build --wait
docker compose ps
```

### Step 6.3 · Smoke test (the same for every track)
```bash
scripts/smoke-test.sh                      # default http://localhost:8080
```
It checks 73 behaviours with only `bash`, `curl` and `openssl`: health, registration (the `role`
field is ignored), duplicate e-mail, `problem+json` errors, login, `/me` with and without a token, the
catalog, categories and search assist, then (logged in as the Compose admin) creating a product, `403`
for customers, optimistic locking (`409`), a guarded stock move, a description draft, the cart and a
coupon (with the ₹200 cap and free shipping), checkout **twice with the same Idempotency-Key** (one
order), someone else's order (`404`), a **signed** webhook, the same webhook again (`duplicate`), a
forged signature (`401`), shipping (a SHIPPED order can no longer be cancelled), a cancel that
restocks, and the outbox relay. It creates
its own uniquely named customers and product (deactivated at the end), so it is safe to run
repeatedly.
Against a deployment, pass the admin credentials as `SMOKE_ADMIN_EMAIL` / `SMOKE_ADMIN_PASSWORD`. If you
cannot, `SMOKE_ALLOW_NO_ADMIN=true` turns the admin-dependent block into one `SKIP` instead of failures.
Because the contract is shared, try another track's
image against the same database: `docker compose stop app-<yours>` and `docker compose --profile python up -d`.
Same results. Also run the AI fallback check:
```bash
AI_PROVIDER=llm LLM_BASE_URL=http://127.0.0.1:9/v1 LLM_TIMEOUT_MS=1000 docker compose --profile <track> up -d --wait
EXPECT_FALLBACK=true scripts/smoke-test.sh      # the description draft and search assist report fallback: true
```

### Step 6.4 · CI: build the image and smoke-test it
Add a `docker` job (matrix over tracks) to `ci.yml`: `docker/setup-buildx-action`,
`docker/build-push-action` with `load: true` and the GitHub Actions layer cache (`cache-from/to: type=gha`),
then `docker compose --profile <track> up -d --no-build --wait`, the smoke test, logs on failure and `down -v`.

### Step 6.5 · Continuous delivery to GHCR: `deploy.yml`
Trigger on `push: tags: ["v*.*.*"]`. The job needs `permissions: packages: write` and logs in to
`ghcr.io` with the built-in `GITHUB_TOKEN`; no personal token required. Image names must be lower-case
(the org is `AICanCode-org`), so compute `ghcr.io/${GITHUB_REPOSITORY_OWNER,,}/shopflow-<track>`.
`docker/metadata-action` turns tag `v1.0.0` into image tags `1.0.0`, `1.0`, `sha-<short>` and `latest`.
Read `docs/github-actions-explained.md` §9–§12 for every detail.

### Step 6.6 · Release v1.0.0
```bash
# update CHANGELOG.md: move "Unreleased" items under "## [1.0.0] - <date>"
git commit -am "chore(release): v1.0.0"
git tag -a v1.0.0 -m "ShopFlow v1.0.0"
git push origin main --follow-tags
```
Watch **Actions → Deploy**: three images appear under your profile's **Packages**. New GHCR packages
are **private** by default. Make one public (Package settings → Change visibility) if a host should pull
it without credentials.

### Step 6.7 · OPTIONAL: deploy to a free tier
You do not need a cloud account to finish this course. If you want a public URL:

| Piece | Free option | Notes |
|---|---|---|
| App | **Render** web service from an *existing image* (`ghcr.io/<you>/shopflow-<track>:latest`) | Free instances spin down after 15 minutes without traffic; the next request takes about a minute (cold start). |
| PostgreSQL | **Neon** free tier | Use its connection string as `DATABASE_URL` and keep `?sslmode=require`. Java: if the string contains `channel_binding=require`, remove that parameter. Render's own free Postgres also works but **expires after 30 days**. |
| Secrets | Render env vars | Set your own `JWT_SECRET` (`openssl rand -base64 48`), `PAYMENT_WEBHOOK_SECRET` (`openssl rand -hex 32`), `ADMIN_EMAIL`, a strong `ADMIN_PASSWORD`. Never reuse the Compose defaults. |
| LLM | none (rules/template), or a free Gemini / Groq key | `AI_PROVIDER=llm`, `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY` as Render env vars. Tell your users if their search sentences are sent to a hosted model. |

Free-tier limits change. Check each provider's pricing page before you rely on it. Fly.io is **not**
used: at the time of writing it needs a payment method and bills usage, with no free tier for new accounts.

To let CI deploy for you:
1. Render → your service → **Settings → Deploy Hook**: copy the URL.
2. GitHub → **Settings → Environments → New environment `production`** (optionally add yourself as a
   required reviewer). Add the **secret** `RENDER_DEPLOY_HOOK_URL` and the **variables** `RENDER_APP_URL`
   (e.g. `https://shopflow-xyz.onrender.com`) and `DEPLOY_TRACK` (`java`, `python` or `typescript`).
   Optionally add the secrets `SMOKE_ADMIN_EMAIL` / `SMOKE_ADMIN_PASSWORD` / `SMOKE_WEBHOOK_SECRET` so
   the live smoke test also runs the admin, cart, checkout and payment steps.
3. Push a tag. The `render` job calls the hook with `imgURL=` set to the exact version, waits for
   `/health`, and runs the smoke test against the live URL. Without the secret the job prints a notice
   and **succeeds**, so forks without cloud accounts stay green.

## 4. AI-assist prompts

### Prompt 1: Review my Dockerfile
```text
Review this Dockerfile for a <Spring Boot 4 / FastAPI / Fastify> service. Check: multi-stage, layer
caching order, non-root user, HEALTHCHECK, image size, secrets leaking into layers, pinned base image
versions, and anything that would differ between my laptop (amd64/arm64) and CI. <paste Dockerfile>
```
**Verify the output by:** rebuilding and measuring: `docker image ls` (size), `docker run --rm --entrypoint id`
(uid), `docker history` (no secrets, small layers), and `docker compose up --wait` (health check passes).

### Prompt 2: Explain the deploy workflow's security model
```text
In this workflow <paste deploy.yml>, which token pushes to GHCR, what can it do, and for how long?
Is RENDER_DEPLOY_HOOK_URL exposed to pull requests from forks? What would happen if someone pushed a
tag from a fork?
```
**Verify the output by:** reading the GitHub docs pages "Automatic token authentication" and "Using secrets
in GitHub Actions" (secrets are not passed to workflows triggered from forks), and checking that
`packages: write` appears only on the image job.

### Prompt 3: Cold start troubleshooting
```text
My container works locally but on Render /health times out for the first minute after idle, then works.
Explain cold starts on free tiers, what my smoke test should do about it, and how to tell a cold start
apart from a crash in the logs.
```
**Verify the output by:** reading your service's Render logs for the boot sequence (migrations applied,
listening on port) and timing `curl -w '%{time_total}' $URL/health` after 20 minutes idle.

## 5. Deliverables
- [ ] `tracks/<track>/Dockerfile` (multi-stage, non-root, HEALTHCHECK).
- [ ] `docker compose --profile <track> up -d --build --wait` + `scripts/smoke-test.sh`: all checks pass,
  and `EXPECT_FALLBACK=true` passes with an unreachable LLM.
- [ ] CI `docker` job green; `deploy.yml` pushed images to GHCR for tag `v1.0.0`.
- [ ] `CHANGELOG.md` with `[1.0.0]`; `docs/runbook.md` with start/stop/rollback/rotate-secret steps.
- [ ] Optional: a live URL in your README and a green `render` job.

## 6. Self-check quiz

### Q1. Why do the Dockerfiles copy the dependency manifest before the source code?
- [ ] Docker requires that order
- [x] So that the dependency layer is cached and only rebuilt when the manifest changes
- [ ] To make the image smaller
- [ ] To run tests faster
> **Answer:** Layers are cached top-down; anything after a changed layer is rebuilt. Source changes often, dependencies rarely.

### Q2. Inside Docker Compose, which `DATABASE_URL` host does the app use?
- [ ] `localhost`
- [x] `postgres` (the service name)
- [ ] `127.0.0.1`
- [ ] `host.docker.internal`
> **Answer:** Each container has its own `localhost`. Compose gives services DNS names on a shared network.

### Q3. Why does the image push use `${GITHUB_REPOSITORY_OWNER,,}`?
- [ ] To hide the owner name
- [x] GHCR image names must be lower-case and the org name `AICanCode-org` has capitals
- [ ] It is required by Render
- [ ] To support forks only
> **Answer:** `,,` is bash's lower-case expansion. `ghcr.io/AICanCode-org/...` would be rejected.

### Q4. A fork has no `RENDER_DEPLOY_HOOK_URL` secret. What does the `render` job do on a tag push?
- [ ] Fails the workflow
- [ ] Deploys to a default account
- [x] Prints a notice and succeeds; the image is still pushed to the fork's GHCR
- [ ] Waits for someone to add the secret
> **Answer:** Optional infrastructure must not make the pipeline red. Secrets are read into `env:` and tested with `if: env.X == ''`.

### Q5. Why are free hosting instances slow on the first request after a while?
- [ ] The database is re-created
- [x] Idle instances are stopped to save cost and must boot again (cold start)
- [ ] The image is rebuilt
- [ ] DNS caching
> **Answer:** That is why the deploy job polls `/health` for up to 10 minutes before smoke-testing.

### Q6. Why must a deployment never use the `JWT_SECRET` from `docker-compose.yml`?
- [ ] It is too short
- [x] It is public in the repository, so anyone could sign a valid ADMIN token for your API
- [ ] Render rejects it
- [ ] It expires after 30 days
> **Answer:** Whoever knows an HS256 secret can mint any token. Defaults are for laptops; deployments get their own secret from the host's environment variables.

## 7. What you learned
- Production Dockerfiles: multi-stage, layer caching, non-root, health checks, `.dockerignore`.
- Compose profiles to run any track against the same dependencies with one command.
- Black-box smoke tests that work for any implementation of a contract.
- Image builds with layer caching in CI, and delivery to GHCR with the built-in token on version tags.
- Optional, secret-gated deployment that degrades gracefully, plus free-tier trade-offs (cold starts, TLS URLs).
- Semantic versioning and a CHANGELOG-driven release.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
git switch -c my-phase-7 phase-6-end          # == v1.0.0
git checkout phase-6-end -- tracks/<track>/Dockerfile docker-compose.yml scripts/smoke-test.sh
git checkout phase-6-end -- .github/workflows/deploy.yml
```
