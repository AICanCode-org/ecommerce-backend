---
phase: 0
slug: phase-0-setup
title: Setup and orientation
duration: 1.5 h
start_ref: main
end_tag: phase-0-end
---
# Phase 0 · Setup and orientation

## 1. Why it matters
Every professional project starts with a working local environment and a clean Git workflow. Teams
lose days to "works on my machine". In this phase you set up the tools for **one** track, run the
starter, meet the test suite that will guide you, open your first pull request and start your
`AI_LOG.md`. That is the evidence a reviewer looks for first: a repo that builds from a clean checkout
with one command.

## 2. Objectives
By the end of this phase you can:
- Fork the repository **with all branches and tags**, clone it and add the `upstream` remote.
- Pick a track (Java, Python or TypeScript) and install its toolchain on Windows (WSL2), macOS or Linux.
- Start PostgreSQL with Docker Compose and run the starter so `GET /health` returns `UP`.
- Run lint and tests locally and see which tests are *pending* and why.
- Open a pull request that passes CI and write your first `AI_LOG.md` entry.

## 3. Step-by-step instructions

### Step 0.1 · Fork and clone (keep the tags!)
1. On GitHub open `AICanCode-org/ecommerce-backend` → **Fork**. **Untick "Copy the `main` branch only"**.
   The `solution` branch and the `phase-N-end` tags are your safety net in later phases.
2. Clone **your** fork and connect the original as `upstream`:
   ```bash
   git clone https://github.com/<you>/ecommerce-backend.git
   cd ecommerce-backend
   git remote add upstream https://github.com/AICanCode-org/ecommerce-backend.git
   git fetch upstream --tags
   git tag -l 'phase-*'      # you should see phase-0-end … phase-8-end
   ```
3. **Windows:** do all of this inside **WSL2** (Ubuntu) and keep the repo in the Linux file system
   (`~/code/ecommerce-backend`), not under `/mnt/c`. File watching and Docker volumes are 5–10× faster there.

### Step 0.2 · Install Docker and the tools for ONE track
* **All tracks:** Git, Docker (Docker Desktop on Windows/macOS with the WSL2 backend; Docker Engine +
  compose plugin on Linux), `make` and `curl`.
* Then run the doctor script. It tells you exactly what is missing:
  ```bash
  scripts/doctor.sh
  ```

<!-- track:java -->
**Java track:** JDK 21 (Temurin) and Maven 3.9+. The easiest way on Linux/macOS/WSL is SDKMAN:
```bash
curl -s https://get.sdkman.io | bash   # then open a new terminal
sdk install java 21-tem && sdk install maven
java -version && mvn -v
```
<!-- /track -->

<!-- track:python -->
**Python track:** Python 3.11+ with `venv` (Ubuntu/WSL: `sudo apt install python3 python3-venv`;
macOS: `brew install python@3.12`).
```bash
python3 --version
cd tracks/python && make install     # creates .venv and installs pinned dependencies
```
<!-- /track -->

<!-- track:typescript -->
**TypeScript track:** Node.js 20+ (22 LTS recommended). Install it with nvm:
```bash
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash   # new terminal
nvm install 22 && node -v
cd tracks/typescript && make install   # npm ci
```
<!-- /track -->

### Step 0.3 · Start the database
```bash
docker compose up -d postgres
docker compose ps          # postgres should be "healthy"
```
The first start also creates a second database, `shopflow_test`, that the integration tests use
(`spec/db/init/01-create-test-db.sql`), so tests can never wipe your dev data. Port 5432 already taken
(another Postgres)? Set `POSTGRES_PORT=55432` in `.env` and use that port in `DATABASE_URL`.

### Step 0.4 · Run the starter
<!-- track:java -->
```bash
cd tracks/java
make run                   # mvn spring-boot:run, listens on :8080
```
<!-- /track -->

<!-- track:python -->
```bash
cd tracks/python
make run                   # python -m app (uvicorn), listens on :8080
```
<!-- /track -->

<!-- track:typescript -->
```bash
cd tracks/typescript
make run                   # tsx watch src/index.ts, listens on :8080
```
<!-- /track -->

In another terminal:
```bash
curl -s localhost:8080/health
# {"status":"UP","checks":{"database":"UP"}}
curl -s -X POST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' -d '{}'
# {"type":"https://shopflow.example/problems/internal-error","title":"Internal server error","status":500}
```
The endpoints already exist, but they answer `500` because the business logic is a `TODO`: that is
Phase 3. Notice that even the crash is a clean `application/problem+json` body with no stack trace:
error handling is part of the starter. Stop the database (`docker compose stop postgres`) and call
`/health` again: it answers `503` with `"database":"DOWN"`. Start it again with `docker compose start postgres`.

`make run` sets **local-only** defaults for `JWT_SECRET`, `PAYMENT_WEBHOOK_SECRET` and the first admin
(`admin@shopflow.local`). They are fine on your laptop and wrong anywhere else; Phase 6 shows how a
deployment sets its own. The log also shows `TODO(phase-3 M2): ensure_admin - starting without an
admin`: creating that admin is one of your first Phase 3 tasks.

### Step 0.5 · Run the checks and meet the pending tests
```bash
make lint test
```
You will see **a few passing tests** (`/health`, and in Java the `DATABASE_URL` parser) and well over a
hundred **skipped** ones. They are *pending*: the complete test suite for ShopFlow is already written and
switched off in one file per track:

| Track | Pending list |
|---|---|
| Java | `tracks/java/src/test/java/org/aicancode/shopflow/support/Pending.java` |
| Python | `tracks/python/tests/conftest.py` (`PENDING`) |
| TypeScript | `tracks/typescript/test/pending.ts` (`PENDING`) |

Open the list now. Each entry names a milestone ("Phase 3 M4 - cart endpoints"). In Phase 3 you delete
one entry at a time, watch the tests go red, and make them green.

### Step 0.6 · Your first pull request
1. Create a branch: `git switch -c chore/phase-0-setup`.
2. Fill in the first entry in `AI_LOG.md`, e.g. how an assistant helped you install the toolchain and
   how you checked its advice.
3. Commit and push, then open a PR **in your fork** against your `main`:
   ```bash
   git add AI_LOG.md
   git commit -m "docs: start AI_LOG for phase 0"
   git push -u origin chore/phase-0-setup
   ```
4. Open the **Actions** tab. Forks have Actions switched off until you click **"I understand my
   workflows, go ahead and enable them"**. The CI workflow runs lint + unit tests for all three tracks.
   Wait for green, fill in the PR template and merge.

## 4. AI-assist prompts

### Prompt 1: Fix my toolchain install
```text
I'm on <Windows 11 + WSL2 Ubuntu 24.04 | macOS 15 | Ubuntu 24.04>. I want to run a <Java 21 + Maven |
Python 3.12 | Node 22> project with Docker Compose. Here is the output of scripts/doctor.sh:
<paste output>. Give me the exact commands to install what is missing, explain each one in one line,
and tell me how to verify each install.
```
**Verify the output by:** running each verification command it gives you, re-running
`scripts/doctor.sh` until every line is green, and checking any download URL against the official site
(adoptium.net, python.org, nodejs.org, docker.com). Never pipe an unknown URL into `bash`.

### Prompt 2: Explain the starter
```text
Here is the file tree of a starter project and the file <tracks/<track>/…/main entry file>:
<paste>. Explain in plain English what happens from "make run" until GET /health returns, which
files are involved, where environment variables are read, and where an unexpected exception is turned
into an HTTP 500 response. Do not suggest code changes.
```
**Verify the output by:** finding each file and function it names (an assistant may invent ones that
do not exist), then changing `PORT=8081 make run` to confirm the app really listens on the new port.

### Prompt 3: What will I build?
```text
Here is an OpenAPI 3.1 contract: <paste spec/openapi.yaml>. Summarise in 10 bullet points what this
API does, which endpoints need authentication, which need a specific role, and which three endpoints
you expect to be the hardest to get right. Quote the path for every claim.
```
**Verify the output by:** checking every quoted path and `security`/role note against the file. Mark
anything it got wrong; you will meet the same contract in Phases 2 and 3.

## 5. Deliverables
- [ ] Your fork contains the `solution` branch and the `phase-*` tags (`git tag -l` shows them).
- [ ] `docker compose up -d postgres` works; `GET /health` returns `UP` from your track.
- [ ] `make lint test` passes locally (a few passed, the rest skipped).
- [ ] A merged PR with a green CI run.
- [ ] `AI_LOG.md` has its first entry.

## 6. Self-check quiz

### Q1. Why must you untick "Copy the main branch only" when forking?
- [ ] Otherwise GitHub Actions is disabled in the fork
- [x] Otherwise the `solution` branch and phase tags are missing from your fork
- [ ] Otherwise you cannot open pull requests
- [ ] Otherwise Docker Compose cannot find the database
> **Answer:** Forks copy only `main` by default. The tags (`phase-N-end`) live on the `solution` branch; without them you cannot compare your work or catch up. Fix it later with `git fetch upstream --tags`.

### Q2. On Windows, where should the repository live?
- [ ] `C:\Users\you\ecommerce-backend`, opened from WSL as `/mnt/c/Users/you/ecommerce-backend`
- [x] Inside the WSL2 Linux file system, e.g. `~/code/ecommerce-backend`
- [ ] On a network drive so Docker can share it
- [ ] It does not matter
> **Answer:** Cross-file-system access from WSL to `/mnt/c` is slow and breaks file watching. Keep code in the Linux file system and open it with VS Code's WSL extension.

### Q3. Why do integration tests use a separate `shopflow_test` database?
- [ ] PostgreSQL allows only one connection per database
- [x] The tests empty the tables before each test, which must never touch your dev data
- [ ] The test database is faster
- [ ] CI cannot reach the `shopflow` database
> **Answer:** Integration tests truncate tables to start from a known state. A separate database (created by `spec/db/init`) makes that safe on your laptop.

### Q4. Why are most tests skipped in the starter?
- [ ] They are flaky
- [ ] CI is too slow to run them
- [x] They describe behaviour you will implement in Phases 3–4 and are switched on one milestone at a time
- [ ] They only run on the solution branch
> **Answer:** The full suite is written up-front (a test-first workflow). Deleting a line from the pending list turns a group back on, so you always have a clear red → green target.

### Q5. Which of these should never go into an AI prompt?
- [ ] The output of `scripts/doctor.sh`
- [ ] A stack trace from your own code
- [x] Your `.env` file with the JWT secret and API keys
- [ ] The OpenAPI spec
> **Answer:** Secrets and personal data never go into prompts. Anyone with the JWT secret can mint an ADMIN token for your API. Redact before you paste.

### Q6. The starter answers `500` for `POST /api/v1/auth/register`. Why is the body still JSON without a stack trace?
- [ ] The framework hides errors in production mode automatically
- [x] The starter already maps unexpected exceptions to a generic `problem+json` response and logs the details server-side
- [ ] The database rejected the request
- [ ] The endpoint is not implemented in the router
> **Answer:** Leaking stack traces tells attackers your stack and file layout. Clients get a generic problem body; the details go to the server log.

## 7. What you learned
- A reproducible local environment: Docker Compose for the database, one toolchain per track.
- The difference between `origin` (your fork) and `upstream` (the course repo), and why tags matter.
- How a health check reports its dependency, and that errors are a clean, generic contract.
- Pending tests as a to-do list: the spec is executable before you write any code.
- The PR + CI loop and the habit of logging AI assistance without leaking secrets.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
# Start Phase 1 from the reference end of Phase 0:
git switch -c my-phase-1 phase-0-end
# See what the reference changed in this phase:
git diff main phase-0-end --stat
```
