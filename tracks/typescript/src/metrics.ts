// Hand-rolled Prometheus counters (text exposition format 0.0.4). v1.1.
// No client library on purpose: /metrics is just text. Names are identical in all three tracks.

export const COUNTERS = {
  shopflow_users_registered_total: "Accounts created with POST /auth/register.",
  shopflow_logins_failed_total: "Login attempts answered with 401.",
  shopflow_orders_placed_total: "Orders created by checkout (idempotent replays not counted).",
  shopflow_checkout_replays_total: "Checkouts answered from an earlier order with the same Idempotency-Key.",
  shopflow_webhooks_duplicate_total: "Payment webhook events ignored because they were already processed.",
  shopflow_orders_expired_total: "Unpaid orders cancelled because their payment window closed.",
  shopflow_ai_fallback_total: "Description drafts or search assists that fell back to the offline path.",
  shopflow_llm_short_circuits_total: "LLM calls skipped because the circuit breaker was open.",
} as const;

export type CounterName = keyof typeof COUNTERS;

export class Metrics {
  private values = new Map<CounterName, number>(
    (Object.keys(COUNTERS) as CounterName[]).map((name) => [name, 0]),
  );

  inc(name: CounterName, amount = 1) {
    this.values.set(name, (this.values.get(name) ?? 0) + amount);
  }

  value(name: CounterName) {
    return this.values.get(name) ?? 0;
  }

  render(): string {
    const lines: string[] = [];
    for (const [name, help] of Object.entries(COUNTERS) as [CounterName, string][]) {
      lines.push(`# HELP ${name} ${help}`, `# TYPE ${name} counter`, `${name} ${this.value(name)}`);
    }
    return lines.join("\n") + "\n";
  }
}
