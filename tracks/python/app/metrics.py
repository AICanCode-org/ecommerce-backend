"""Hand-rolled Prometheus counters (text exposition format 0.0.4). v1.1.

No client library on purpose: students see that /metrics is just text. The metric names are
identical in all three tracks (see spec/openapi.yaml, GET /metrics).
"""

from __future__ import annotations

import threading

COUNTERS: dict[str, str] = {
    "shopflow_users_registered_total": "Accounts created with POST /auth/register.",
    "shopflow_logins_failed_total": "Login attempts answered with 401.",
    "shopflow_orders_placed_total": "Orders created by checkout (idempotent replays not counted).",
    "shopflow_checkout_replays_total": "Checkouts answered from an earlier order with the same Idempotency-Key.",
    "shopflow_webhooks_duplicate_total": "Payment webhook events ignored because they were already processed.",
    "shopflow_orders_expired_total": "Unpaid orders cancelled because their payment window closed.",
    "shopflow_ai_fallback_total": "Description drafts or search assists that fell back to the offline path.",
    "shopflow_llm_short_circuits_total": "LLM calls skipped because the circuit breaker was open.",
}


class Metrics:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._values = dict.fromkeys(COUNTERS, 0)

    def inc(self, name: str, amount: int = 1) -> None:
        with self._lock:
            self._values[name] += amount

    def value(self, name: str) -> int:
        return self._values[name]

    def render(self) -> str:
        lines: list[str] = []
        for name, help_text in COUNTERS.items():
            lines += [f"# HELP {name} {help_text}", f"# TYPE {name} counter", f"{name} {self._values[name]}"]
        return "\n".join(lines) + "\n"
