#!/usr/bin/env bash
# ShopFlow language-agnostic smoke test. Works against ANY track (Java, Python, TypeScript).
# Usage:  scripts/smoke-test.sh [BASE_URL]        (default http://localhost:8080)
# Needs only bash + curl + openssl (Linux, macOS, WSL, Git Bash). Creates its own customers, product
# and orders with unique names, so it is safe to run repeatedly; the smoke product is deactivated at the end.
# Env:
#   SMOKE_ADMIN_EMAIL / SMOKE_ADMIN_PASSWORD  the bootstrap admin (default: the local Compose values)
#   SMOKE_WEBHOOK_SECRET        PAYMENT_WEBHOOK_SECRET of the app (default: the local Compose value)
#   SMOKE_ALLOW_NO_ADMIN=true   skip (instead of fail) the admin, cart, checkout and payment checks when the
#                               admin cannot log in, e.g. against a live URL without the admin secret
#   EXPECT_FALLBACK=true        the app runs with AI_PROVIDER=llm and an unreachable LLM: expect fallbacks
set -uo pipefail

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
BASE_URL="${BASE_URL%/}"
ADMIN_EMAIL="${SMOKE_ADMIN_EMAIL:-admin@shopflow.local}"
ADMIN_PASSWORD="${SMOKE_ADMIN_PASSWORD:-admin-password-123}"
WEBHOOK_SECRET="${SMOKE_WEBHOOK_SECRET:-local-webhook-secret-change-me-0123456789}"
PASS=0
FAIL=0
SKIP=0
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

ok()   { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; [ -s "$TMP" ] && printf '       body: %s\n' "$(head -c 300 "$TMP")"; }
skip() { SKIP=$((SKIP + 1)); printf '  \033[33mSKIP\033[0m %s\n' "$1"; }

# request METHOD PATH [JSON_BODY] [TOKEN] [EXTRA_HEADER...] -> sets STATUS, HEADERS; body in $TMP
request() {
  local method="$1" path="$2" body="${3:-}" token="${4:-}"
  shift 4 2>/dev/null || shift $#
  local args=(-s -o "$TMP" -D - -X "$method" "$BASE_URL$path")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' --data "$body")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  local h
  for h in "$@"; do args+=(-H "$h"); done
  HEADERS="$(curl "${args[@]}" | tr -d '\r')"
  STATUS="$(printf '%s\n' "$HEADERS" | awk 'NR==1{print $2}')"
}
header() { printf '%s\n' "$HEADERS" | awk -v h="$(echo "$1" | tr 'A-Z' 'a-z')" -F': ' 'tolower($1)==h{print $2}' | tail -1; }
# First occurrence of a key in the (single-line) JSON body.
json_str()  { grep -o "\"$1\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "$TMP" | head -1 | sed -E 's/.*:[[:space:]]*"([^"]*)"$/\1/'; }
json_num()  { grep -oE "\"$1\"[[:space:]]*:[[:space:]]*[0-9]+" "$TMP" | head -1 | sed -E 's/.*:[[:space:]]*//'; }
json_bool() { grep -oE "\"$1\"[[:space:]]*:[[:space:]]*(true|false)" "$TMP" | head -1 | sed -E 's/.*:[[:space:]]*//'; }
expect() { if [ "$STATUS" = "$1" ]; then ok "$2 ($STATUS)"; else bad "$2 (expected $1, got ${STATUS:-no response})"; fi; }
is_problem() { case "$(header Content-Type)" in application/problem+json*) ok "$1 uses application/problem+json" ;; *) bad "$1 uses application/problem+json" ;; esac; }
check() { if [ "$1" = "$2" ]; then ok "$3"; else bad "$3 (expected '$2', got '$1')"; fi; }
# webhook JSON_BODY [SIGNATURE] -> signs the exact bytes with HMAC-SHA256 unless a signature is given
webhook() {
  local body="$1" sig="${2:-}"
  [ -n "$sig" ] || sig="sha256=$(printf '%s' "$body" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" | sed 's/^.*= *//')"
  HEADERS="$(curl -s -o "$TMP" -D - -X POST "$BASE_URL/api/v1/payments/webhook" -H 'Content-Type: application/json' \
    -H "X-Signature: $sig" --data-binary "$body" | tr -d '\r')"
  STATUS="$(printf '%s\n' "$HEADERS" | awk 'NR==1{print $2}')"
}

RUN="$(date +%s)$RANDOM"
PW="smoke-password-$RUN"
echo "ShopFlow smoke test against $BASE_URL (run $RUN)"

# ---- ops ----
request GET /health
expect 200 "GET /health"
grep -q '"database":[[:space:]]*"UP"' "$TMP" && ok "database is UP" || bad "database is UP"

# ---- accounts (FR-1..FR-4) ----
ALICE_EMAIL="alice-$RUN@smoke.shopflow.test"
request POST /api/v1/auth/register "{\"email\":\"$ALICE_EMAIL\",\"password\":\"$PW\",\"displayName\":\"Smoke Alice\",\"role\":\"ADMIN\"}"
expect 201 "POST /auth/register"
check "$(json_str role)" CUSTOMER "a 'role' field in the request is ignored (role=CUSTOMER)"
request POST /api/v1/auth/register "{\"email\":\"$ALICE_EMAIL\",\"password\":\"$PW\",\"displayName\":\"Again\"}"
expect 409 "duplicate e-mail is rejected"
request POST /api/v1/auth/register "{\"email\":\"short-$RUN@smoke.shopflow.test\",\"password\":\"short\",\"displayName\":\"X\"}"
expect 400 "password shorter than 8 characters is rejected"
is_problem "validation error"
request POST /api/v1/auth/login "{\"email\":\"$ALICE_EMAIL\",\"password\":\"wrong-password\"}"
expect 401 "wrong password is 401"
request POST /api/v1/auth/login "{\"email\":\"$ALICE_EMAIL\",\"password\":\"$PW\"}"
expect 200 "POST /auth/login"
ALICE="$(json_str accessToken)"
[ -n "$ALICE" ] && ok "login returns an access token" || bad "login returns an access token"
request GET /api/v1/me "" "$ALICE"
expect 200 "GET /me with a token"
request GET /api/v1/me
expect 401 "GET /me without a token is 401"
[ -n "$(header WWW-Authenticate)" ] && ok "401 carries WWW-Authenticate" || bad "401 carries WWW-Authenticate"

# ---- public catalog ----
request GET "/api/v1/products?limit=5"
expect 200 "GET /products"
request GET /api/v1/categories
expect 200 "GET /categories"
request GET "/api/v1/products/NO-SUCH-$RUN"
expect 404 "unknown product is 404"
is_problem "404"
request GET "/api/v1/products?minPricePaise=abc"
expect 400 "a non-numeric price filter is 400"

# ---- admin, catalog writes, AI, cart, checkout, payments (needs the bootstrap admin) ----
request POST /api/v1/auth/login "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"
if [ "$STATUS" != "200" ] && [ "${SMOKE_ALLOW_NO_ADMIN:-}" = "true" ]; then
  skip "admin login failed ($STATUS): skipping the admin, cart, checkout and payment checks (set SMOKE_ADMIN_EMAIL/SMOKE_ADMIN_PASSWORD)"
else
  expect 200 "admin login ($ADMIN_EMAIL)"
  ADMIN="$(json_str accessToken)"
  SKU="SMK-$RUN"
  WORD="zyxsmoke$RUN"
  PRODUCT="{\"sku\":\"$SKU\",\"name\":\"Smoke Kettle $WORD\",\"description\":\"A steel kettle for smoke tests. It boils.\",\"category\":\"smoke\",\"pricePaise\":129900,\"stock\":3}"
  request POST /api/v1/products "$PRODUCT" "$ALICE"
  expect 403 "a CUSTOMER cannot create products"
  request POST /api/v1/products "$PRODUCT" "$ADMIN"
  expect 201 "ADMIN creates a product"
  check "$(header Location)" "/api/v1/products/$SKU" "Location header"
  check "$(json_str price)" "₹1,299.00" "price is formatted in rupees (₹1,299.00)"
  request PATCH "/api/v1/products/$SKU" '{"version":1,"pricePaise":119900}' "$ADMIN"
  expect 200 "PATCH with version 1"
  check "$(json_num version)" 2 "version is now 2"
  request PATCH "/api/v1/products/$SKU" '{"version":1,"pricePaise":99}' "$ADMIN"
  expect 409 "a stale version is 409 (optimistic locking)"
  request POST "/api/v1/products/$SKU/stock" '{"delta":2}' "$ADMIN"
  expect 200 "ADMIN restocks (+2)"
  check "$(json_num stock)" 5 "stock is now 5"
  request GET "/api/v1/products?q=$WORD&category=smoke"
  expect 200 "search + category filter"
  grep -q "\"sku\":[[:space:]]*\"$SKU\"" "$TMP" && ok "search finds the product" || bad "search finds the product"

  # ---- AI (FR-AI) ----
  request POST "/api/v1/products/$SKU/description-draft" '{"keywords":["steel","1500 ml"]}' "$ALICE"
  expect 403 "a CUSTOMER cannot draft descriptions"
  request POST "/api/v1/products/$SKU/description-draft" '{"keywords":["steel","1500 ml"]}' "$ADMIN"
  expect 200 "ADMIN drafts a description (AI)"
  PROVIDER="$(json_str provider)"
  if [ "$PROVIDER" = "template" ] || [ "$PROVIDER" = "llm" ]; then ok "draft provider=$PROVIDER fallback=$(json_bool fallback)"; else bad "draft provider"; fi
  if [ -n "${EXPECT_FALLBACK:-}" ]; then
    check "$(json_bool fallback)" "$EXPECT_FALLBACK" "description fallback=$EXPECT_FALLBACK as expected"
  fi
  request GET "/api/v1/search/assist?q=$WORD+under+rs+1500+in+stock"
  expect 200 "GET /search/assist (AI)"
  PROVIDER="$(json_str provider)"
  if [ "$PROVIDER" = "rules" ] || [ "$PROVIDER" = "llm" ]; then ok "assist provider=$PROVIDER fallback=$(json_bool fallback)"; else bad "assist provider"; fi
  if [ -n "${EXPECT_FALLBACK:-}" ]; then
    check "$(json_bool fallback)" "$EXPECT_FALLBACK" "assist fallback=$EXPECT_FALLBACK as expected"
    check "$(json_num maxPricePaise)" 150000 "the offline rules read 'under rs 1500' as maxPricePaise=150000"
  fi
  grep -q "\"sku\":[[:space:]]*\"$SKU\"" "$TMP" && ok "search assist finds the product" || bad "search assist finds the product"

  # ---- cart (FR-C) ----
  request PUT "/api/v1/cart/items/$SKU" '{"quantity":9}' "$ALICE"
  expect 409 "more than the stock is 409 insufficient-stock"
  request PUT "/api/v1/cart/items/$SKU" '{"quantity":2}' "$ALICE"
  expect 200 "PUT cart item (2 x ₹1,199)"
  check "$(json_num subtotalPaise)" 239800 "subtotal is 239800 paise"
  request PUT /api/v1/cart/coupon '{"code":"EXPIRED5"}' "$ALICE"
  expect 404 "an inactive coupon is 404"
  request PUT /api/v1/cart/coupon '{"code":"SAVE10"}' "$ALICE"
  expect 200 "apply coupon SAVE10"
  check "$(json_num discountPaise)" 20000 "10 % off is capped at ₹200 (20000 paise)"
  check "$(json_num totalPaise)" 219800 "total = 239800 - 20000 + free shipping"

  # ---- checkout + payment (FR-O, FR-P) ----
  request POST /api/v1/checkout "" "$ALICE"
  expect 400 "checkout without an Idempotency-Key is 400"
  KEY="smoke-checkout-$RUN"
  request POST /api/v1/checkout "" "$ALICE" "Idempotency-Key: $KEY"
  expect 201 "checkout creates an order"
  ORDER_ID="$(json_num id)"
  check "$(header Location)" "/api/v1/orders/$ORDER_ID" "Location header"
  check "$(json_str status)" PENDING_PAYMENT "the new order waits for payment"
  PAYMENT_ID="$(grep -o '"id"[[:space:]]*:[[:space:]]*"pay_[^"]*"' "$TMP" | head -1 | sed -E 's/.*"(pay_[^"]*)"$/\1/')"
  [ -n "$PAYMENT_ID" ] && ok "the order carries a payment id" || bad "the order carries a payment id"
  request POST /api/v1/checkout "" "$ALICE" "Idempotency-Key: $KEY"
  expect 200 "the same Idempotency-Key replays the order"
  check "$(header Idempotent-Replayed)" true "Idempotent-Replayed: true"
  check "$(json_num id)" "$ORDER_ID" "the replay returns the same order"
  request GET /api/v1/cart "" "$ALICE"
  grep -q '"items":[[:space:]]*\[\]' "$TMP" && ok "checkout emptied the cart" || bad "checkout emptied the cart"
  request GET "/api/v1/products/$SKU"
  check "$(json_num stock)" 3 "stock was reserved (5 - 2 = 3)"

  BOB_EMAIL="bob-$RUN@smoke.shopflow.test"
  request POST /api/v1/auth/register "{\"email\":\"$BOB_EMAIL\",\"password\":\"$PW\",\"displayName\":\"Smoke Bob\"}"
  request POST /api/v1/auth/login "{\"email\":\"$BOB_EMAIL\",\"password\":\"$PW\"}"
  BOB="$(json_str accessToken)"
  request GET "/api/v1/orders/$ORDER_ID" "" "$BOB"
  expect 404 "someone else's order is 404 (not 403)"

  EVENT="{\"id\":\"evt-smoke-$RUN\",\"type\":\"payment.succeeded\",\"paymentId\":\"$PAYMENT_ID\"}"
  webhook "$EVENT" "sha256=0000000000000000000000000000000000000000000000000000000000000000"
  expect 401 "a webhook with a wrong signature is 401"
  webhook "$EVENT"
  expect 200 "signed payment.succeeded webhook"
  check "$(json_str status)" processed "the webhook was processed"
  webhook "$EVENT"
  check "$(json_str status)" duplicate "the same webhook event again is a duplicate"
  request GET "/api/v1/orders/$ORDER_ID" "" "$ALICE"
  check "$(json_str status)" PAID "the order is PAID"
  request POST "/api/v1/admin/orders/$ORDER_ID/status" '{"status":"SHIPPED"}' "$ALICE"
  expect 403 "a CUSTOMER cannot ship orders"
  request POST "/api/v1/admin/orders/$ORDER_ID/status" '{"status":"SHIPPED"}' "$ADMIN"
  expect 200 "ADMIN ships the order"
  request POST "/api/v1/orders/$ORDER_ID/cancel" "" "$ALICE"
  expect 409 "a SHIPPED order cannot be cancelled"

  request PUT "/api/v1/cart/items/$SKU" '{"quantity":1}' "$ALICE"
  request POST /api/v1/checkout "" "$ALICE" "Idempotency-Key: smoke-second-$RUN"
  expect 201 "second checkout"
  SECOND_ID="$(json_num id)"
  request POST "/api/v1/orders/$SECOND_ID/cancel" "" "$ALICE"
  expect 200 "cancel an unpaid order"
  check "$(json_str status)" CANCELLED "the order is CANCELLED"
  request GET "/api/v1/products/$SKU"
  check "$(json_num stock)" 3 "cancelling restocked the item"

  # ---- outbox (FR-E) ----
  request GET "/api/v1/admin/outbox?pending=true" "" "$ALICE"
  expect 403 "a CUSTOMER cannot read the outbox"
  request GET "/api/v1/admin/outbox?pending=true&limit=50" "" "$ADMIN"
  expect 200 "ADMIN reads the pending outbox"
  grep -q '"order.paid"' "$TMP" && ok "an order.paid event is waiting" || bad "an order.paid event is waiting"
  request POST /api/v1/admin/outbox/publish "" "$ADMIN"
  expect 200 "ADMIN publishes the outbox"
  [ "$(json_num published)" -ge 1 ] 2>/dev/null && ok "published $(json_num published) event(s)" || bad "published >= 1 event"

  request GET "/api/v1/products/$SKU" "" "$ADMIN"
  request PATCH "/api/v1/products/$SKU" "{\"version\":$(json_num version),\"active\":false}" "$ADMIN"
  expect 200 "deactivate the smoke product"
  request GET "/api/v1/products/$SKU"
  expect 404 "an inactive product is 404 for shoppers"
fi

# ---- v1.1 observability + payment window (Phase 7) ----
HEADERS="$(curl -s -o /dev/null -D - -H 'X-Request-ID: smoke-req-1' "$BASE_URL/health" | tr -d '\r')"
[ "$(header X-Request-ID)" = "smoke-req-1" ] && ok "X-Request-ID is echoed (v1.1)" || bad "X-Request-ID is echoed (v1.1)"
HEADERS="$(curl -s -o /dev/null -D - -H 'X-Request-ID: bad id with spaces' "$BASE_URL/health" | tr -d '\r')"
RID="$(header X-Request-ID)"
[ -n "$RID" ] && [ "$RID" != "bad id with spaces" ] && ok "an unsafe X-Request-ID is replaced (v1.1)" || bad "an unsafe X-Request-ID is replaced (v1.1)"
request GET /metrics
expect 200 "GET /metrics (v1.1)"
REGISTERED="$(sed -n 's/^shopflow_users_registered_total \([0-9][0-9]*\)$/\1/p' "$TMP")"
[ "${REGISTERED:-0}" -ge 1 ] 2>/dev/null && ok "shopflow_users_registered_total=$REGISTERED" || bad "shopflow_users_registered_total >= 1"
if [ -n "${ADMIN:-}" ] && [ -n "${SECOND_ID:-}" ]; then
  request GET "/api/v1/orders/$SECOND_ID" "" "$ALICE"
  [ -n "$(json_str expiresAt)" ] && ok "orders carry expiresAt (v1.1)" || bad "orders carry expiresAt (v1.1)"
  request POST /api/v1/admin/orders/expire "" "$ALICE"
  expect 403 "a CUSTOMER cannot run the expiry job (v1.1)"
  request POST /api/v1/admin/orders/expire "" "$ADMIN"
  expect 200 "ADMIN runs the expiry job (v1.1)"
  [ -n "$(json_num expired)" ] && ok "expired $(json_num expired) overdue order(s)" || bad "the expiry job answers {expired: n}"
else
  skip "payment window checks (no admin session)"
fi
# ---- end v1.1 ----

echo
echo "Result: $PASS passed, $FAIL failed, $SKIP skipped"
[ "$FAIL" -eq 0 ]
