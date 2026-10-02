package org.aicancode.shopflow.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v1.1: hand-rolled Prometheus counters (text exposition format 0.0.4).
 * No client library on purpose: /metrics is just text. Names are identical in all three tracks.
 */
public class Metrics {

    public static final String USERS_REGISTERED = "shopflow_users_registered_total";
    public static final String LOGINS_FAILED = "shopflow_logins_failed_total";
    public static final String ORDERS_PLACED = "shopflow_orders_placed_total";
    public static final String CHECKOUT_REPLAYS = "shopflow_checkout_replays_total";
    public static final String WEBHOOKS_DUPLICATE = "shopflow_webhooks_duplicate_total";
    public static final String ORDERS_EXPIRED = "shopflow_orders_expired_total";
    public static final String AI_FALLBACK = "shopflow_ai_fallback_total";
    public static final String LLM_SHORT_CIRCUITS = "shopflow_llm_short_circuits_total";

    public static final Map<String, String> HELP = new LinkedHashMap<>();

    static {
        HELP.put(USERS_REGISTERED, "Accounts created with POST /auth/register.");
        HELP.put(LOGINS_FAILED, "Login attempts answered with 401.");
        HELP.put(ORDERS_PLACED, "Orders created by checkout (idempotent replays not counted).");
        HELP.put(CHECKOUT_REPLAYS, "Checkouts answered from an earlier order with the same Idempotency-Key.");
        HELP.put(WEBHOOKS_DUPLICATE, "Payment webhook events ignored because they were already processed.");
        HELP.put(ORDERS_EXPIRED, "Unpaid orders cancelled because their payment window closed.");
        HELP.put(AI_FALLBACK, "Description drafts or search assists that fell back to the offline path.");
        HELP.put(LLM_SHORT_CIRCUITS, "LLM calls skipped because the circuit breaker was open.");
    }

    private final Map<String, AtomicLong> values = new LinkedHashMap<>();

    public Metrics() {
        HELP.keySet().forEach(name -> values.put(name, new AtomicLong()));
    }

    public void inc(String name) {
        inc(name, 1);
    }

    public void inc(String name, long amount) {
        AtomicLong counter = values.get(name);
        if (counter == null) {
            throw new IllegalArgumentException("unknown metric " + name);
        }
        counter.addAndGet(amount);
    }

    public long value(String name) {
        return values.get(name).get();
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        HELP.forEach((name, help) -> sb.append("# HELP ").append(name).append(' ').append(help).append('\n')
                .append("# TYPE ").append(name).append(" counter\n")
                .append(name).append(' ').append(values.get(name).get()).append('\n'));
        return sb.toString();
    }
}
