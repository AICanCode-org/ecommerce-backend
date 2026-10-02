#!/usr/bin/env bash
# Game day (Phase 7): the LLM provider hangs while shoppers use search assist. Measure the impact on one track.
# Usage:  scripts/game-day.sh [python|typescript|java]      (needs Docker Compose; run from the repo root)
# It starts a tiny fake OpenAI-compatible LLM container ("llm-fake") on the Compose network that either
# answers instantly (MODE=ok) or accepts the connection and never answers (MODE=hang), and points the
# app at it with AI_PROVIDER=llm. Phases: offline baseline -> healthy LLM -> LLM hangs -> LLM back.
# Env:    N=10 (searches per phase)  N_PAR=50 (concurrent searches while probing /health)
#         LLM_TIMEOUT_MS=5000  COOLDOWN_WAIT=31 (seconds; the v1.1 circuit breaker cools down after 30 s)
#         BASE_URL=http://localhost:8080  DC="docker compose"  DOCKER=docker
#         SMOKE_ADMIN_EMAIL / SMOKE_ADMIN_PASSWORD (default: the local Compose admin)
# With sudo, keep the environment: DC="sudo -E docker compose" DOCKER="sudo docker".
set -u
TRACK=${1:-python}
N=${N:-10}
N_PAR=${N_PAR:-50}
B=${BASE_URL:-http://localhost:8080}
export LLM_TIMEOUT_MS=${LLM_TIMEOUT_MS:-5000}
read -r -a DC <<< "${DC:-docker compose}"
read -r -a DOCKER <<< "${DOCKER:-docker}"
ADMIN_EMAIL="${SMOKE_ADMIN_EMAIL:-admin@shopflow.local}"
ADMIN_PASSWORD="${SMOKE_ADMIN_PASSWORD:-admin-password-123}"
FAKE=shopflow-llm-fake
QUERY="running%20shoes%20under%203000%20in%20stock"
ts() { date -u '+%H:%M:%SZ'; }
post() { curl -s -X POST "$B$1" -H 'Content-Type: application/json' ${3:+-H "Authorization: Bearer $3"} -d "$2"; }
field() { grep -o "\"$1\":[[:space:]]*\"[^\"]*\"" | head -1 | sed -E 's/.*"([^"]*)"$/\1/'; }

# A fake LLM in 20 lines of Python: MODE=ok answers like /v1/chat/completions, MODE=hang never answers.
FAKE_LLM='
import json, os, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
class H(BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if os.environ["MODE"] == "hang":
            time.sleep(3600)
        content = json.dumps({"terms": ["running"], "category": None, "minPrice": None, "maxPrice": 3000, "inStock": True})
        body = json.dumps({"choices": [{"message": {"role": "assistant", "content": content}}]}).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_message(self, *args):
        pass
ThreadingHTTPServer(("0.0.0.0", 8000), H).serve_forever()
'

app_up() { # $1 = offline | llm
  if [ "$1" = llm ]; then
    AI_PROVIDER=llm LLM_BASE_URL=http://$FAKE:8000/v1 LLM_API_KEY='' "${DC[@]}" --profile "$TRACK" up -d --wait "app-$TRACK" >/dev/null 2>&1
  else
    AI_PROVIDER=offline "${DC[@]}" --profile "$TRACK" up -d --wait "app-$TRACK" >/dev/null 2>&1
  fi || { echo "could not start app-$TRACK"; exit 1; }
}
fake_llm() { # $1 = ok | hang | off
  "${DOCKER[@]}" rm -f "$FAKE" >/dev/null 2>&1
  [ "$1" = off ] && return 0
  "${DOCKER[@]}" run -d --name "$FAKE" --network "$NETWORK" -e MODE="$1" python:3.12-slim python -c "$FAKE_LLM" >/dev/null
  sleep 2
}
burst() { # $1 = label, $2 = number of searches
  local total=0 max=0 r code t statuses="" providers="" ms
  for _ in $(seq "$2"); do
    r=$(curl -s -o /tmp/gd-assist.json -w '%{http_code} %{time_total}' "$B/api/v1/search/assist?q=$QUERY")
    code=${r% *}; t=${r#* }
    statuses="$statuses $code"
    providers="$providers $(field provider < /tmp/gd-assist.json)/fallback=$(grep -oE '"fallback":(true|false)' /tmp/gd-assist.json | cut -d: -f2)"
    ms=$(awk -v t="$t" 'BEGIN { printf "%d", t * 1000 }'); [ "$ms" -gt "$max" ] && max=$ms
    total=$(awk -v a="$total" -v b="$t" 'BEGIN { print a + b }')
  done
  echo "$(ts) $1: $2 searches, statuses:$(echo "$statuses" | tr ' ' '\n' | sed '/^$/d' | sort | uniq -c | tr -s ' \n' ' ')avg $(awk -v a="$total" -v n="$2" 'BEGIN { printf "%.0f", a / n * 1000 }') ms, max $max ms"
  echo "  search assist:$(echo "$providers" | tr ' ' '\n' | sed '/^$/d' | sort | uniq -c | tr -s ' \n' ' ')"
}
health_under_load() { # N_PAR concurrent searches, then time /health and a cart read while they are in flight
  local pids=()
  for _ in $(seq "$N_PAR"); do
    curl -s -o /dev/null "$B/api/v1/search/assist?q=$QUERY" & pids+=($!)
  done
  sleep 1
  echo "  /health while $N_PAR searches are in flight: $(curl -s -o /dev/null -w '%{http_code} in %{time_total}s' --max-time 60 "$B/health")"
  echo "  GET /cart (checkout path) meanwhile:     $(curl -s -o /dev/null -w '%{http_code} in %{time_total}s' --max-time 60 -H "Authorization: Bearer $SHOPPER" "$B/api/v1/cart")"
  wait "${pids[@]}"
}
report() {
  local m; m=$(curl -s "$B/metrics" | grep -E '^shopflow_(ai_fallback|llm_short_circuits)_total' | tr '\n' ' ')
  echo "  metrics: ${m:-(no /metrics before v1.1)}"
}

echo "$(ts) game day: LLM provider hangs, track=$TRACK, LLM_TIMEOUT_MS=$LLM_TIMEOUT_MS"
app_up offline
NETWORK=$("${DOCKER[@]}" inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$("${DC[@]}" ps -q "app-$TRACK")" | awk '{print $1}')
RUN="$(date +%s)$RANDOM"; PW="game-day-password-$RUN"
ADMIN=$(post /api/v1/auth/login "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | field accessToken)
[ -n "$ADMIN" ] || { echo "admin login failed (set SMOKE_ADMIN_EMAIL / SMOKE_ADMIN_PASSWORD)"; exit 1; }
SKU="GD-$RUN"
post /api/v1/products "{\"sku\":\"$SKU\",\"name\":\"Game Day Running Shoes\",\"category\":\"shoes\",\"pricePaise\":249900,\"stock\":5,\"description\":\"Light running shoes.\"}" "$ADMIN" >/dev/null
post /api/v1/auth/register "{\"email\":\"gd-shopper-$RUN@shopflow.test\",\"password\":\"$PW\",\"displayName\":\"GD Shopper\"}" >/dev/null
SHOPPER=$(post /api/v1/auth/login "{\"email\":\"gd-shopper-$RUN@shopflow.test\",\"password\":\"$PW\"}" | field accessToken)
echo "$(ts) product $SKU, network $NETWORK"

burst "baseline (AI_PROVIDER=offline)" "$N"; report
fake_llm ok; app_up llm
echo "$(ts) app restarted with AI_PROVIDER=llm -> $FAKE (MODE=ok)"
burst "healthy LLM" "$N"; report
echo "$(ts) FAULT: the LLM accepts connections but never answers (MODE=hang)"; fake_llm hang
burst "LLM hangs" "$N"; report
health_under_load; report
echo "  recent AI warnings:"
"${DC[@]}" logs --no-color --since 5m "app-$TRACK" 2>/dev/null | grep -iE 'llm|circuit|fallback' | tail -3 | cut -c1-240
echo "$(ts) RECOVERY: the LLM answers again (MODE=ok)"; fake_llm ok
burst "LLM back, immediately" 3; report
echo "$(ts) waiting ${COOLDOWN_WAIT:-31} s"; sleep "${COOLDOWN_WAIT:-31}"
burst "LLM back, after ${COOLDOWN_WAIT:-31} s" 3; report
VERSION=$(curl -s "$B/api/v1/products/$SKU" -H "Authorization: Bearer $ADMIN" | grep -oE '"version":[0-9]+' | cut -d: -f2)
curl -s -o /dev/null -X PATCH "$B/api/v1/products/$SKU" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN" -d "{\"version\":${VERSION:-1},\"active\":false}"
fake_llm off; app_up offline
echo "$(ts) done (app back on AI_PROVIDER=offline). Write the numbers into docs/postmortem-<date>-llm-hang.md"
