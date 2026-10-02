---
phase: 1
slug: phase-1-requirements
title: Requirements, threats and user stories
duration: 2.5 h
start_tag: phase-0-end
end_tag: phase-1-end
---
# Phase 1 · Requirements, threats and user stories

## 1. Why it matters
Most failed projects fail before the first line of code: nobody wrote down what "done" means. A shop
adds a second question that costs real money when it is skipped: **what could go wrong with an
order?** The same bag sold twice, a customer charged twice, a "payment succeeded" message that nobody
sent. A short, testable requirements document with an explicit threat list gives every test a reason
to exist, lets you say *no* to scope creep, and is the first thing an interviewer asks about. AI
assistants are great at drafting requirements, and just as good at inventing requirements nobody asked
for. Your job is to keep the ones you can test.

## 2. Objectives
- Turn a one-paragraph brief into numbered **functional** (FR) and **measurable non-functional** (NFR) requirements.
- Map personas (and one system: the payment provider) to **roles** and write down who may do what.
- List at least six **threats** (abuse cases and failure cases) and the requirement that answers each.
- Write 8–10 **user stories** with Given/When/Then acceptance criteria and create them as GitHub Issues.
- Write an explicit **out-of-scope** list and a **back-of-the-envelope estimate** for a festival sale.

## 3. Step-by-step instructions

### Step 1.1 · Read the brief and the contract
> *Build ShopFlow: the backend of a small Indian online shop. Anyone can browse and search the
> catalog. Customers register, keep a cart (with coupons), check out and pay through a payment
> provider that calls us back. The owner adds products, manages stock and ships paid orders. Prices are
> in rupees and must be exact. It must never sell more than it has, never create two orders for one
> checkout, and only believe the payment provider about payments. AI may help the owner write
> descriptions and help shoppers search in plain language, but the shop must work without it. It must
> run on a laptop for free.*

Open `spec/openapi.yaml`. The HTTP contract is **given** so the three tracks stay compatible; your
requirements must be consistent with it (status codes, limits, the `problem+json` error format, which
endpoints declare `security: bearerAuth`, the `Idempotency-Key` and `X-Signature` headers).

### Step 1.2 · Personas and the permission matrix
Write at least three personas, e.g. *Priya, who shops on her phone*; *Kabir, the owner*; and
**fakepay**, the payment provider: not a person, but an actor with its own way to authenticate (a
signature). Then fill a matrix: rows = actions (browse, see an inactive product, edit a product, use a
cart, check out, see my order, see someone else's order, ship an order, read the outbox, call the
webhook), columns = anonymous, CUSTOMER, ADMIN. Every "no" in this table becomes a test in Phase 3.
Decide now what a refused request returns: `401` (not logged in), `403` (logged in, not allowed) or
`404` (you should not even learn that it exists, e.g. someone else's order).

### Step 1.3 · Functional requirements
In `spec/requirements.md` write FR-1 … FR-n as "The system shall …" statements. Each needs a number
or a status code, so a test can check it. Bad: *"Shipping is cheap."* Good: *"Shipping is 4 900 paise,
free when the subtotal after the discount is at least 99 900 paise."* Cover registration and login,
roles, the catalog (browse, search, admin edits, stock), money, the cart and coupons, checkout, orders
and their states, the payment webhook, the outbox, the two AI helpers, errors and health.

Money deserves its own requirement. Write down **how amounts are represented** (integer paise
everywhere) and **how a percentage discount is rounded** (down, to the paisa). If you leave this open,
the three tracks will disagree by one paisa and every test that compares totals becomes flaky.

### Step 1.4 · Non-functional requirements and threats
Cover money correctness, overselling, duplicates, security, performance, availability, privacy, cost
and portability. Make each one measurable: *"under 20 parallel checkouts for the last unit exactly
one succeeds"*, *"catalog reads answer in < 200 ms p95 on a free-tier instance"*, *"checkout works
when the LLM is down"*.

Then the threat list. For each abuse or failure case, name the requirement that stops it:

| Threat | Answer |
|---|---|
| A shopper calls `/payments/webhook` herself with "payment succeeded" | HMAC signature on the raw body (FR-13) |
| The customer double-taps "Pay", or the app retries after a timeout | `Idempotency-Key` + a unique constraint (FR-11) |
| Two customers buy the last unit at the same moment | guarded stock decrement + `CHECK (stock >= 0)` (FR-11, NFR-2) |
| A new user sends `"role":"ADMIN"` when registering | registration ignores `role` (FR-1) |
| Someone walks through `/orders/1`, `/orders/2`, … | someone else's order is `404` (FR-12) |
| The order is paid but the warehouse never hears about it | transactional outbox (FR-14) |

### Step 1.5 · User stories → GitHub Issues
For each story use the **User story** issue template (`.github/ISSUE_TEMPLATE/user_story.yml`). It asks
for the story, the acceptance criteria and the Phase 3 milestone. Label them `user-story`. With the
GitHub CLI:
```bash
gh issue create --template user_story.yml      # or use the web UI: Issues → New issue
```
Write at least one acceptance criterion per story that describes a **refusal or a failure** (wrong
role, someone else's order, not enough stock, a redelivered webhook), not only the happy path.

### Step 1.6 · Out of scope + estimates
List what v1 will **not** do (real payment providers, actual refunds, GST invoices, images, reviews,
a front end, e-mails, multi-currency…). Then estimate: products, visitors/day, orders/day, the peak of
a festival sale (× 10 for two hours), read:write ratio, requests/second at peak and storage per year.
Write one sentence on what the numbers mean for the design (spoiler: one PostgreSQL instance is
plenty; the hard part is **correctness under concurrency**, not scale).

### Step 1.7 · Commit
```bash
git switch -c docs/phase-1-requirements
git add spec/requirements.md AI_LOG.md
git commit -m "docs(spec): requirements, threats and user stories"
git push -u origin docs/phase-1-requirements      # open a PR, wait for CI, merge
```
Compare with the reference: `git diff phase-0-end phase-1-end -- spec/requirements.md`.

## 4. AI-assist prompts

### Prompt 1: Draft requirements from the brief
```text
Here is a product brief: <paste the brief> and an OpenAPI contract: <paste spec/openapi.yaml>.
Draft numbered functional requirements ("The system shall ...") and measurable non-functional
requirements for v1. Every requirement must be testable and consistent with the contract (quote the
status code, header or limit). All money is integer paise. Do not add features that are not in the
brief or the contract.
```
**Verify the output by:** checking every number, header and status code against `spec/openapi.yaml`,
deleting anything not in the brief (AI loves adding wishlists, reviews and "Login with Google"), and
asking "which test would prove this?" for each line. If you cannot name the test, rewrite or drop it.

### Prompt 2: Threat-model the shop
```text
Act as a security reviewer for a small online shop API (contract: <paste>) with roles CUSTOMER and
ADMIN, JWT access tokens, a cart, an idempotent checkout and a payment webhook signed with
HMAC-SHA256. List the 8 most likely abuse or failure cases (include concurrency and message
redelivery, not only attackers). For each: the scenario in one sentence, the impact in rupees or
customer trust, and a concrete, testable countermeasure. Name the OWASP API Security Top 10 category
where one applies.
```
**Verify the output by:** looking up each named OWASP API Top 10 category on owasp.org (assistants
sometimes misname them), keeping only cases that apply to *this* contract, and making sure each kept
countermeasure appears as an FR or NFR. A countermeasure like "use a distributed lock" for a shop with
60 orders a day is a sign the assistant is pattern-matching, not thinking.

### Prompt 3: Turn my stories into Given/When/Then
```text
Here are my user stories: <paste>. For each, write 2-4 acceptance criteria in Given/When/Then form,
including at least one refusal or failure case (wrong role, someone else's order, not enough stock,
the same webhook twice, invalid input). Use only status codes from this contract: <paste the relevant
paths>. Amounts are in paise.
```
**Verify the output by:** recomputing every amount yourself (shipping, coupon caps, rounding) and
cross-checking every status code with the contract and your permission matrix. A criterion that says
`403` where your matrix says `404` (someone else's order!) is a design decision you must make
consciously, not accept by default.

## 5. Deliverables
- [ ] `spec/requirements.md` with problem statement, personas + permission matrix, FRs (including the
  money rules), measurable NFRs, threats, out of scope and estimates.
- [ ] 8–10 GitHub Issues created from the *User story* template, each with a refusal or failure criterion.
- [ ] A merged PR and an `AI_LOG.md` entry (what the assistant invented that you removed).

## 6. Self-check quiz

### Q1. Which requirement is testable?
- [ ] "Checkout should be reliable."
- [ ] "Prices must be accurate."
- [x] "A PERCENT coupon's discount is subtotal × percent / 100 rounded down to the paisa, capped at max_discount_paise."
- [ ] "The shop should handle festival traffic."
> **Answer:** It has an exact rule a unit test can check with numbers. The others need a measurable threshold first.

### Q2. A logged-in CUSTOMER requests `GET /api/v1/orders/42`, which belongs to someone else. What should the API answer?
- [ ] `200` with the order
- [ ] `401 Unauthorized`
- [ ] `403 Forbidden`
- [x] `404 Not Found`
> **Answer:** `403` would confirm that order 42 exists (and tell a competitor how many orders you have). For resources the caller should not even know about, `404` leaks nothing.

### Q3. Why does the payment webhook need a signature instead of a bearer token?
- [ ] Bearer tokens are too long
- [x] The caller is the provider's server, not a user; an HMAC of the raw body proves the message came from someone who knows the shared secret and was not changed
- [ ] Webhooks cannot send headers
- [ ] The signature makes the request faster
> **Answer:** Without it anyone could POST "payment succeeded" for their own unpaid order. The signature covers the exact bytes of the body, so it also detects tampering.

### Q4. Why store money as integer paise rather than rupees in a float?
- [ ] Integers use less disk space
- [x] Floating point cannot represent most decimal amounts exactly (0.1 + 0.2 ≠ 0.3), so totals and threshold checks drift
- [ ] JSON does not support decimals
- [ ] PostgreSQL has no decimal type
> **Answer:** Integers in the smallest unit are exact in every language and in SQL. Rupees only appear when formatting for humans.

### Q5. Your estimate says ~60 orders/day, 600 on a festival day. What does that mean for the design?
- [ ] You need a message queue and microservices
- [x] One PostgreSQL instance is plenty; spend the effort on correctness under concurrency
- [ ] You must shard the orders table
- [ ] You need a cache in front of checkout
> **Answer:** Even the peak is a few checkouts per minute. What breaks a small shop is two requests at the same instant for the last unit, not volume.

### Q6. An AI draft adds "FR-20: customers can pay with saved cards". What do you do?
- [ ] Keep it, more features are better
- [x] Remove it (or move it to out of scope): it is not in the brief, and storing card data brings PCI DSS obligations
- [ ] Add a cards table to the schema
- [ ] Ask the AI if it is needed
> **Answer:** Requirements come from the brief and the contract, not from the assistant's idea of a typical shop. Card data should never touch ShopFlow at all; log the rejection in `AI_LOG.md`.

## 7. What you learned
- Writing testable FRs and measurable NFRs, consistent with a given API contract.
- A permission matrix with a non-human actor, and the 401 vs 403 vs 404 decision.
- Threat modelling that includes failure cases: double clicks, races and redelivered messages.
- Pinning down money rules (unit, rounding) before any code exists.
- User stories with refusal criteria, tracked as GitHub Issues, and estimates that keep the design simple.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
git switch -c my-phase-2 phase-1-end
git show phase-1-end:spec/requirements.md | less     # the reference requirements
git diff phase-0-end phase-1-end --stat
```
