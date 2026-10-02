# Post-mortem: the LLM hangs (game day, 2026-10-02)

| | |
|---|---|
| Type | Planned game day (fault injection), local Docker Compose stack, v1.0.0 and the v1.1.0 release candidate |
| Fault | The LLM endpoint accepts connections but never answers (`scripts/game-day.sh`, fake LLM in `MODE=hang`) |
| Tracks | Python, TypeScript, Java (one at a time, one Compose project per tag, `LLM_TIMEOUT_MS=5000`, the default) |
| Status | Resolved. Circuit breaker shipped in v1.1.0, four follow-ups open |
| Format | Blameless: we describe systems and decisions, not people |

All times are UTC. Numbers come from `scripts/game-day.sh <track>`: per phase 10 search-assist requests
(`GET /api/v1/search/assist?q=running shoes under 3000`) one after the other, client-side `curl` timings,
then 50 concurrent assist requests while timing `/health` and an authenticated `GET /api/v1/cart`.
Reproduce it yourself, but expect your own numbers.

## Summary
With the LLM hanging, every track kept answering search-assist requests (`200`, nothing broken) with the
offline rules (`provider: rules`, `fallback: true`), exactly as NFR-6 requires, and checkout never
touched the LLM. But in v1.0.0 **every assist request waited the full 5 s timeout**, because nothing
remembered that the LLM had just failed. In the **Python** track it was worse: 50 concurrent assist
requests tied up the worker thread pool and an unrelated `GET /health` took **4.0 s**. v1.1.0 adds a
circuit breaker in front of the one LLM client: after 3 failures in a row it skips the LLM for 30 s, so
only the first three requests are slow and the rest answer in milliseconds. After the cooldown one trial
call found the LLM healthy and closed the circuit again.

## Impact (v1.0.0, before the fix)
| | Python | TypeScript | Java |
|---|---|---|---|
| Baseline, `AI_PROVIDER=offline` (avg of 10) | 3 ms | 4 ms | 6 ms |
| Healthy fake LLM (avg of 10) | 6 ms | 11 ms | 16 ms |
| **LLM hangs** (avg / max of 10) | **5 011 ms / 5 016 ms** | **5 006 ms / 5 007 ms** | **5 011 ms / 5 022 ms** |
| `GET /health` while 50 assists are in flight | **4.00 s** | 0.002 s | 0.005 s |
| `GET /cart` right after that | 0.010 s | 0.008 s | 0.064 s |
| Status codes / provider during the fault | 10 × `200`, rules, `fallback: true` | same | same |

No wrong data and no lost orders: the rules produce the same kind of filters (validated like user input),
and carts, checkout and payments do not call the LLM. For a shopper, though, a search box that thinks
for 5 seconds is broken, and in Python the slowness leaked into endpoints that never call the LLM.

## Timeline (Python track)
| Time (UTC) | Event | Evidence |
|---|---|---|
| 19:30:00 | Shopper and product created; baseline: 10 assists, avg 3 ms, `provider: rules` | script output |
| 19:30:17 | App restarted with `AI_PROVIDER=llm` against the fake LLM (`MODE=ok`): 10 assists, avg 6 ms, `provider: llm` | script output |
| 19:30:18 | **Fault injected:** fake LLM replaced by `MODE=hang` | |
| 19:30:18–19:31:11 | 10 assists, each ≈ 5.0 s (max 5 016 ms); all `200`, `fallback: true` | `curl -w %{time_total}` |
| 19:31:11 | 50 concurrent assists; `GET /health` answers `200` after **4.00 s**; `GET /cart` right after: 10 ms | script output |
| 19:31:11+ | Logs: one plain-text line per request, `LLM search assist failed, using the rules: timed out` (no request ID) | `docker compose logs app-python` |
| 19:31:22 | **Recovery:** fake LLM back to `MODE=ok`; the next assist uses the LLM again (6 ms) | script output |
| 19:32–19:37 | Same game day on TypeScript and Java: ≈ 5.0 s per assist, `/health` unaffected | script output |
| 19:38:04 | v1.1.0 (circuit breaker) Python: fault injected again | |
| 19:38:23 | 10 assists: avg **1 505 ms** (three ≈ 5 s, seven short-circuited in ms); `shopflow_llm_short_circuits_total 7`, `shopflow_ai_fallback_total 10` | `/metrics` |
| 19:38:23 | 50 concurrent assists, `/health` in **0.003 s**, `/cart` in 0.006 s; short-circuits 57; JSON log line `LLM circuit open: skipping the call` | `/metrics`, logs |
| 19:38:28 | LLM back: assists still use the rules for the rest of the 30 s cooldown (4 ms, `fallback: true`) | script output |
| 19:38:59 | After 31 s: the half-open trial succeeds, `provider: llm` again (avg 7 ms) | script output |

v1.1.0 on the other tracks: TypeScript avg 1 504 ms and Java avg 1 507 ms for the 10 assists during the
fault (max ≈ 5.0 s), both with 7 short-circuits (57 after the burst) and `/health` in under 5 ms; both
were back on `provider: llm` after the cooldown.

## Root cause
1. **The LLM call is on the request path and has no memory.** Each assist request calls the LLM
   synchronously with a 5 s timeout. v1.0.0 handled each failure correctly but independently, so a
   hanging provider cost every request the full timeout.
2. **Our failure tests simulated the easy failure.** The unit tests make the LLM fail *immediately* (a
   fake client that throws in Python and TypeScript, connection refused on `127.0.0.1:9` in Java), and the
   Phase 6 fallback smoke run used an unroutable address with a 1.5 s timeout: all fast or bounded. That
   proves the fallback is *correct*, not that it is *fast*. A hanging endpoint is the realistic failure of
   an overloaded hosted model or a stuck local Ollama.
3. **Python: one shared thread pool.** FastAPI runs plain `def` endpoints in AnyIO's worker thread pool
   (40 threads by default). 50 assists each holding a thread for 5 s left `/health` (also a `def`
   endpoint) waiting for a free thread; it got one when the first assists timed out, which is why
   `/cart` right after it was fast again. A real checkout arriving at that moment would have waited the
   same way. Tomcat (200 threads) and Node's event loop did not hit this limit at 50 concurrent
   requests, but would at higher load.

## What went well
- Graceful degradation worked in all tracks: no `5xx`, every assist got an answer, checkout unaffected.
- The design decision to keep the LLM off the money path (architecture, "allowed to fail") paid off: the
  worst case was a slow search box, not a lost order.
- The same game day across three stacks quickly separated a shared design problem (no breaker) from a
  track-specific one (Python's thread pool).
- The fake LLM (a few lines of Python in a throw-away container) made the failure reproducible in seconds,
  with no model download and no API key.
- After the fix, `/metrics` made the breaker visible (`shopflow_llm_short_circuits_total`,
  `shopflow_ai_fallback_total`), and the JSON logs gave one searchable line per short-circuit.

## What went wrong
- No latency budget for degraded mode was written down, so nothing tested for it.
- v1.0.0 had no metrics, so during the fault the only evidence was client-side timings and plain-text log
  lines without request IDs. The v1.1 observability work was needed to see the fix working.

## Action items
| # | Action | Type | Status |
|---|---|---|---|
| 1 | Circuit breaker around the shared LLM client (3 failures → open 30 s → one half-open trial), counter `shopflow_llm_short_circuits_total`, fake-clock unit tests in every track | fix | **Done in v1.1.0** |
| 2 | Add NFR-9: *"With the LLM unavailable or hanging, p95 search-assist latency stays < 500 ms, excluding at most 3 requests per 30 s"* | requirement | Open |
| 3 | Add a hanging-LLM case (a server that never answers) to the integration tests and run `scripts/game-day.sh` in the CI Docker job with a latency assertion | test | Open |
| 4 | Python: keep slow I/O from starving unrelated endpoints (async HTTP client for the LLM, or a small bulkhead limiting concurrent LLM calls), then re-run the game day while a checkout is in flight | fix | Open |
| 5 | Show "smart search unavailable, showing simple results" in the UI when `fallback: true`, so shoppers know why results look different | product | Open |
| 6 | Lower `LLM_TIMEOUT_MS` to 1–2 s. Rejected for now: a local Ollama on a laptop CPU often needs 2–4 s, and the breaker already bounds the cost | idea | Won't do |

## Lessons
- **"Down" is easy, "hangs" is hard.** A refused connection fails in milliseconds; a hanging one costs the full timeout, every time.
- **A fallback is only graceful if the failure is fast.** Correctness and latency are separate properties; test both.
- **Shared pools spread failures.** One slow dependency can slow down endpoints that never call it, including checkout.
