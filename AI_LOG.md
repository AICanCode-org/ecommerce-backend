# AI_LOG: how I used AI on this project

Employers and reviewers increasingly ask *how* you used AI, not *whether*. This log is your evidence
that you stayed in control. Add one entry every time an AI assistant (ChatGPT, Gemini, Copilot,
Cursor, Claude, a local Ollama model, ...) produced something you kept, changed or rejected.

**Rules**
1. Never paste secrets, `.env` contents, JWT secrets, tokens, API keys or other people's personal data
   into a prompt.
2. Every AI output is a *draft*. Record how you **verified** it (test, docs, running it, a second source).
3. Be honest about rejections: they are the most valuable entries. Money, concurrency and security code
   (prices, stock, checkout, webhooks, hashing, tokens, authorisation) gets the strictest review.

## Entry template (copy for each entry)

### YYYY-MM-DD · Phase N · <short title>
* **Tool / model:** e.g. Gemini 2.0 Flash (free tier), Copilot in VS Code, Ollama llama3.2:1b
* **Prompt (summary or paste):**
  > ...
* **What it produced:** one or two lines, or a link to the commit
* **What I kept / changed / rejected, and why:**
* **How I verified it:** e.g. `make test` (60 passed), compared with the OpenAPI spec, read the official docs at ...
* **Time saved / lost (honest guess):**
* **What I learned:**

---

## My entries

<!-- Start below this line. Phase 0 asks you to write your first entry. -->

> The reference solution was written by the AiCanCode maintainers working with an AI coding agent.
> These entries record checks that really happened while building it. Your entries will be about *your* work.

### 2026-10-02 · Phase 0 · Port 5432 was already taken
* **Tool / model:** AI coding agent (chat + shell)
* **Prompt (summary):**
  > `docker compose up -d postgres` fails with "Bind for 0.0.0.0:5432 failed: port is already allocated". What is going on and what is the least invasive fix?
* **What it produced:** the explanation that another PostgreSQL (here: a different project's Compose
  stack on the same machine) already published 5432, and three options: stop the other stack, change the
  published port with `POSTGRES_PORT` (already supported by our `docker-compose.yml`), or stop a system
  PostgreSQL.
* **What I kept / changed / rejected and why:** kept the `POSTGRES_PORT` override (plus a separate
  Compose project name) and pointed `DATABASE_URL` / `TEST_DATABASE_URL` at the new port, because
  stopping someone else's stack is not my call. Rejected the suggestion to `kill` whatever process owned the port.
* **How I verified it:** `docker compose ps` → `healthy`; `psql … -c '\l'` lists both `shopflow` and
  `shopflow_test` (created by `spec/db/init/01-create-test-db.sql`); each starter boots and
  `curl localhost:<port>/health` → `{"status":"UP","checks":{"database":"UP"}}`; `make lint test` in
  each track (only the always-on tests pass, the rest are skipped as pending).
* **Time saved / lost (honest guess):** saved ~10 min.
* **What I learned:** make ports configurable from day one; it costs one line in Compose.
