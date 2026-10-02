# ShopFlow requirements

> Reference version (tag `phase-1-end`). Yours may differ in wording; it must not differ in what can be
> tested.

## 1. Problem statement
A small shop in Pune (handmade bags, running gear, home goods) sells on Instagram and WhatsApp and
types orders into a spreadsheet. Twice last Diwali it sold the same last item to two people, and a
customer who double-tapped "Pay" was charged twice. Hosted storefronts take a commission on every
order and give the owner no API for their own app. ShopFlow is a small, self-hostable REST backend
that gets the money basics right: exact prices in paise, no overselling, no duplicate orders, payments
confirmed by the provider (never by the browser). AI helps the owner write product descriptions and
helps shoppers search in plain language, but it is never on the checkout path.

## 2. Users / personas and roles
| Persona | Role | Goal |
|---|---|---|
| **Priya**, shops on her phone in the metro | anonymous → CUSTOMER | find "running shoes under 3000", keep a cart across devices, pay once, see her order status |
| **Kabir**, owner and only full-time employee | ADMIN | add and edit products without overwriting his assistant's changes, ship paid orders, never oversell |
| **Fakepay**, the payment provider (a system, not a person) | signed webhook | tell ShopFlow that a payment succeeded or failed, possibly more than once |

Permission matrix (✔ = allowed, 401/403/404 = what a refused request returns):

| Action | anonymous | CUSTOMER | ADMIN |
|---|---|---|---|
| Browse/search active products, categories, search assist | ✔ | ✔ | ✔ |
| See an inactive product | 404 | 404 | ✔ |
| Create/edit a product, move stock, draft a description | 401 | 403 | ✔ |
| Cart, coupons, checkout, own orders, cancel own order | 401 | ✔ | ✔ |
| Someone else's order | 401 | **404** (not 403) | ✔ |
| All orders, ship/deliver, outbox | 401 | 403 | ✔ |
| Payment webhook | signature only (401 `invalid-signature` without it) | | |

## 3. Functional requirements (FR)
- **FR-1 Register.** `POST /api/v1/auth/register` with `email` (trimmed, lower-cased, ≤ 254 chars,
  valid format), `password` (8–72 bytes in UTF-8) and `displayName` (1–50 chars) creates a **CUSTOMER**
  and returns `201` with the user (never the password or its hash). A `role` field in the payload is
  ignored. An e-mail that is already registered returns `409 email-taken`.
- **FR-2 Login.** `POST /api/v1/auth/login` returns `200` with a JWT access token (`expiresIn: 3600`)
  and the user. Unknown e-mail and wrong password return the **same** `401 invalid-credentials`.
  `GET /api/v1/me` returns the current user.
- **FR-3 Roles.** CUSTOMER and ADMIN. The first admin is created (or promoted) at start-up from
  `ADMIN_EMAIL` / `ADMIN_PASSWORD`; there is no other way to become an admin.
- **FR-4 Catalog.** `GET /api/v1/products` lists **active** products ordered by id with keyset
  pagination (`limit` 1–50, default 20, opaque `nextCursor`), filters `q` (full-text), `category`,
  `minPricePaise`, `maxPricePaise`, `inStock`. `GET /api/v1/categories` returns category counts.
  `GET /api/v1/products/{sku}` returns one product; inactive products are `404` except for admins.
- **FR-5 Product admin.** ADMIN creates products (`sku` 3–32 `A-Z0-9-`, unique → `409 sku-taken`;
  `name` 1–120; `description` ≤ 2000; `category` 2–30 `a-z0-9-`; `pricePaise` integer 1–100 000 000;
  `stock` 0–1 000 000). Edits (`PATCH`) require the `version` last seen: a stale version returns
  `409 version-conflict` with the current version. Products are deactivated, never deleted (old
  orders point at them).
- **FR-6 Stock.** `POST /api/v1/products/{sku}/stock` with a non-zero `delta` moves stock in ONE
  guarded statement; a result below 0 is refused with `409 insufficient-stock`.
- **FR-7 Money.** Every amount is an **integer number of paise** in the API, the code and the database.
  Displayed prices use Indian digit grouping (`₹1,23,456.78`).
- **FR-8 Cart.** One cart per customer, stored in the database. `PUT /api/v1/cart/items/{sku}` sets a
  quantity of 1–10 (more than the stock → `409 insufficient-stock`; an inactive product → `404`); at most
  20 different products. `DELETE` removes a line (idempotent). The cart is priced **now**, with current
  prices: `subtotalPaise`, `discountPaise`, `shippingPaise`, `totalPaise`.
- **FR-9 Shipping.** ₹49 (4 900 paise); free when the subtotal **after** the discount is ₹999 or more;
  an empty cart costs nothing.
- **FR-10 Coupons.** `PUT /api/v1/cart/coupon` applies an active coupon (unknown or inactive → `404`).
  PERCENT coupons round the discount **down** to the paisa and may have a cap; FLAT coupons never
  exceed the subtotal; a minimum subtotal that is not met means the coupon is kept but not applied
  (`couponApplied: false`). Demo codes: `SAVE10` (10 %, max ₹200), `FLAT100` (₹100 off ₹500+),
  `EXPIRED5` (inactive).
- **FR-11 Checkout.** `POST /api/v1/checkout` requires an `Idempotency-Key` header (8–64 chars). It
  turns the cart into an order **all-or-nothing**: every line's stock is decremented with a guard, the
  order, its lines (name, SKU and price **copied**), a PENDING payment and an `order.placed` event are
  written in one transaction, and the cart is emptied. `201` with the order. The same key again
  returns the **same** order with `200` and `Idempotent-Replayed: true`. Empty cart → `409 cart-empty`;
  not enough stock for any line → `409 insufficient-stock` and nothing changes.
- **FR-12 Orders.** `GET /api/v1/orders` (mine, newest first), `GET /api/v1/orders/{id}` (someone
  else's → `404`). States: `PENDING_PAYMENT → PAID → SHIPPED → DELIVERED`; a customer may cancel a
  PENDING_PAYMENT or PAID order (`→ CANCELLED`, stock is put back, a paid order also emits
  `refund.requested`). Admins ship PAID orders and deliver SHIPPED ones. Any other transition →
  `409 invalid-transition`.
- **FR-13 Payment webhook.** `POST /api/v1/payments/webhook` is called by the provider with
  `X-Signature: sha256=<hex HMAC-SHA256 of the raw body>`. A bad signature → `401 invalid-signature`.
  `payment.succeeded` marks the order PAID; `payment.failed` cancels it and restocks. The same event
  id twice → `{"status":"duplicate"}` and no change. A success for an order that was cancelled in the
  meantime keeps the order cancelled and emits `refund.requested`.
- **FR-14 Outbox.** Every order state change writes an event (`order.placed`, `order.paid`,
  `order.cancelled`, `order.shipped`, `order.delivered`, `refund.requested`) in the **same**
  transaction. ADMIN lists them (`GET /api/v1/admin/outbox`) and relays pending ones
  (`POST /api/v1/admin/outbox/publish`, at-least-once).
- **FR-15 AI description drafts.** `POST /api/v1/products/{sku}/description-draft` (ADMIN, optional
  `keywords` ≤ 5) returns a draft, `provider` (`template` or `llm`) and `fallback`. The draft is
  **never saved automatically**: the admin edits and PATCHes it.
- **FR-16 AI search assist.** `GET /api/v1/search/assist?q=running shoes under 3000 in stock` turns a
  sentence into filters (`terms`, `category`, `minPricePaise`, `maxPricePaise`, `inStock`) and returns
  them **with** the matching products, so the shopper sees how the sentence was understood. Offline
  rules by default; with an LLM, any failure falls back to the rules (`fallback: true`).
- **FR-17 Errors.** Every error is `application/problem+json` (RFC 9457) with `type`
  `https://shopflow.example/problems/<slug>`, `title`, `status`, `detail`; `401 unauthorized` also sends
  `WWW-Authenticate: Bearer`.
- **FR-18 Health.** `GET /health` returns `200 {"status":"UP"}` with a database check, `503` when the
  database is down.

## 4. Non-functional requirements (NFR)
- **NFR-1 Money correctness.** No floating point anywhere money is computed or stored. The database
  checks `total = subtotal − discount + shipping`.
- **NFR-2 No overselling.** Under any concurrency, stock never goes below 0 and the number of units
  sold never exceeds the stock (database CHECK + guarded decrement; tested with parallel checkouts).
- **NFR-3 Exactly one order per checkout intent.** Retries with the same Idempotency-Key never create
  a second order or charge; webhook redeliveries never apply twice.
- **NFR-4 Security.** bcrypt (cost ≥ 10 in production), HS256 JWT with a ≥ 32-byte secret, pinned
  algorithm, constant-time signature comparison, parameterised SQL only, no secrets in the repository.
- **NFR-5 Performance.** p95 < 200 ms for catalog reads and < 500 ms for checkout at 20 requests/s on a
  free-tier instance. Catalog search uses indexes (no sequential scans on `products` at 10 000 rows).
- **NFR-6 Availability.** The AI helpers are optional: with the LLM down or slow, every endpoint still
  answers (fallback) and checkout is not affected at all.
- **NFR-7 Privacy.** Store only e-mail, display name and orders; no card data ever touches ShopFlow
  (the provider handles it). Logs never contain passwords, tokens or full webhook bodies.
- **NFR-8 Portability + cost.** One container per track, PostgreSQL 16, runs on a free tier; the same
  smoke test passes against all three tracks.

## 5. Threats (abuse cases)
| # | Threat | Answered by |
|---|---|---|
| T1 | A shopper calls the webhook herself: "payment succeeded" for an unpaid order | FR-13 HMAC signature, constant-time compare, NFR-4 |
| T2 | Double-tap on "Pay", a retry after a timeout, two tabs | FR-11 Idempotency-Key + unique (user, key) |
| T3 | Two customers buy the last item at the same moment | FR-11 guarded decrement, NFR-2 CHECK (stock ≥ 0) |
| T4 | Mass assignment: `"role":"ADMIN"` in the register payload | FR-1 role ignored |
| T5 | Order enumeration: GET /orders/1, /orders/2, … | FR-12 someone else's order is 404 |
| T6 | Price tampering: the client sends its own price or total | prices are read from the database, the client sends SKU + quantity only |
| T7 | Two admins edit the same product, one edit is lost | FR-5 optimistic locking |
| T8 | The provider redelivers an event (or an attacker replays a signed one) | FR-13 event id stored, duplicate = no-op |
| T9 | An order is paid but the "order.paid" message to the warehouse is lost | FR-14 transactional outbox |
| T10 | Prompt injection in the search box or in a product description | the LLM output is validated like user input (FR-16), drafts are never saved automatically (FR-15) |
| T11 | Account enumeration through login | FR-2 same 401 for unknown e-mail and wrong password |

## 6. User stories + acceptance criteria
1. **US-1 Register and sign in.** As Priya I want an account so that my cart and orders follow me.
   *Given* a new e-mail, *when* I register with `"role":"ADMIN"`, *then* I get 201 and role CUSTOMER.
   *Given* a wrong password, *when* I log in, *then* 401 invalid-credentials (same as an unknown e-mail).
2. **US-2 Browse and search.** As Priya I want to filter by category and price so that I only see what
   I can afford. *Given* 25 active products, *when* I ask for `limit=20`, *then* I get 20 and a
   `nextCursor` that returns the remaining 5. *Given* an inactive product, *then* it never appears.
3. **US-3 Cart across devices.** As Priya I want my cart stored on the server. *Given* 2 lines, *when*
   I sign in elsewhere, *then* `GET /cart` shows the same 2 lines priced with current prices.
4. **US-4 Coupon.** As Priya I want to apply SAVE10. *Given* a ₹2 500 subtotal, *then* the discount is
   ₹200 (capped, not ₹250) and shipping is free. *Given* FLAT100 on a ₹300 cart, *then*
   `couponApplied` is false and nothing is discounted.
5. **US-5 Checkout exactly once.** As Priya I want to tap "Pay" twice without paying twice. *Given* a
   cart, *when* I POST /checkout twice with the same key, *then* the first is 201, the second 200 with
   `Idempotent-Replayed: true` and the same order id, and stock went down once.
6. **US-6 No overselling.** As Kabir I never want to sell what I don't have. *Given* stock 1 and two
   customers checking out concurrently, *then* exactly one gets 201 and the other 409
   insufficient-stock, and stock ends at 0.
7. **US-7 Payment confirmation.** As Kabir I only ship paid orders. *Given* a PENDING_PAYMENT order,
   *when* a correctly signed `payment.succeeded` arrives, *then* the order is PAID and an `order.paid`
   event is in the outbox; *when* the same event arrives again, *then* `duplicate` and nothing changes;
   *when* the signature is wrong, *then* 401.
8. **US-8 Cancel and refund.** As Priya I want to cancel a paid order. *Given* a PAID order, *when* I
   cancel, *then* it is CANCELLED, stock is back, and `refund.requested` is in the outbox.
9. **US-9 Safe product edits.** As Kabir I don't want my assistant's price change overwritten.
   *Given* both of us loaded version 3, *when* she saves first, *then* my save returns 409
   version-conflict naming the current version (4).
10. **US-10 AI helpers.** As Kabir I want a draft description; as Priya I want to type "running shoes
    under 3000 in stock". *Given* the LLM is down, *then* both still answer, with `fallback: true`.

## 7. Out of scope (v1)
Real payment providers (fakepay only), refunds themselves (we only emit `refund.requested`), tax/GST
invoices, multiple addresses and shipping providers, product images, reviews, wishlists, a front end,
e-mails, multi-currency, reservations of unpaid stock (added in v1.1).

## 8. Back-of-the-envelope estimates
* 2 000 products, 3 000 visitors/day, 60 orders/day; a festival sale: 10× for 2 hours.
* Peak: 600 orders/day in 2 h → ~5 checkouts/minute; reads ~50:1 → ~4 requests/s peak. Plenty of
  headroom on one small instance; the risk is **correctness under concurrency**, not throughput.
* Storage: an order with 3 lines + payment + 3 events ≈ 2 KB → 60 × 365 × 2 KB ≈ 45 MB/year.
