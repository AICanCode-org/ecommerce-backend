package org.aicancode.shopflow.ai;

import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * v1.1 (after the game day): stop calling an LLM that keeps failing.
 * CLOSED: calls go through; {@code threshold} failures in a row open the circuit.
 * OPEN: calls fail immediately (no timeout per search) for {@code cooldownMs}.
 * HALF-OPEN: after the cooldown one trial call goes through; success closes, failure re-opens.
 */
public class CircuitBreaker implements LlmClient {

    private static final Logger LOG = LoggerFactory.getLogger(CircuitBreaker.class);

    /** Thrown instead of calling the LLM while the circuit is open. */
    public static class OpenException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public OpenException() {
            super("LLM circuit open: skipping the call");
        }
    }

    private final LlmClient client;
    private final int threshold;
    private final long cooldownMs;
    private final LongSupplier nowMs;
    private final Runnable onShortCircuit;
    private int failures;
    private Long openedAt;
    private boolean trialRunning;

    public CircuitBreaker(LlmClient client, int threshold, long cooldownMs, LongSupplier nowMs, Runnable onShortCircuit) {
        this.client = client;
        this.threshold = threshold;
        this.cooldownMs = cooldownMs;
        this.nowMs = nowMs;
        this.onShortCircuit = onShortCircuit;
    }

    public CircuitBreaker(LlmClient client, Runnable onShortCircuit) {
        this(client, 3, 30_000, System::currentTimeMillis, onShortCircuit);
    }

    public synchronized String state() {
        if (openedAt == null) {
            return "closed";
        }
        return nowMs.getAsLong() - openedAt >= cooldownMs ? "half-open" : "open";
    }

    @Override
    public String chat(String system, String user, int maxTokens) throws Exception {
        synchronized (this) {
            String state = state();
            if ("open".equals(state) || ("half-open".equals(state) && trialRunning)) {
                onShortCircuit.run();
                throw new OpenException();
            }
            trialRunning = "half-open".equals(state);
        }
        String answer;
        try {
            answer = client.chat(system, user, maxTokens);
        } catch (Exception e) {
            synchronized (this) {
                trialRunning = false;
                failures++;
                if (openedAt != null || failures >= threshold) {
                    openedAt = nowMs.getAsLong();
                    LOG.warn("LLM circuit opened after {} failure(s)", failures);
                }
            }
            throw e;
        }
        synchronized (this) {
            trialRunning = false;
            failures = 0;
            openedAt = null;
        }
        return answer;
    }
}
