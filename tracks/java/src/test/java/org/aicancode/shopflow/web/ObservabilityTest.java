package org.aicancode.shopflow.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.aicancode.shopflow.ai.CircuitBreaker;
import org.aicancode.shopflow.ai.DescriptionService;
import org.aicancode.shopflow.ai.HttpLlmClient;
import org.aicancode.shopflow.ai.LlmClient;
import org.aicancode.shopflow.ai.LlmParser;
import org.aicancode.shopflow.ai.LlmWriter;
import org.aicancode.shopflow.ai.RuleParser;
import org.aicancode.shopflow.ai.SearchAssistService;
import org.aicancode.shopflow.ai.TemplateWriter;
import org.aicancode.shopflow.metrics.Metrics;
import org.aicancode.shopflow.support.TestApp;
import org.aicancode.shopflow.support.TestApp.Res;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** v1.1 (Phase 7): request IDs, /metrics and the LLM circuit breaker. */
class ObservabilityTest {

    private static long metric(TestApp app, String name) {
        return rawBody(app).lines().filter(l -> l.startsWith(name + " ")).map(l -> Long.parseLong(l.split(" ")[1]))
                .findFirst().orElseThrow();
    }

    private static String rawBody(TestApp app) {
        try {
            return app.get("/metrics").raw().getContentAsString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aSafeRequestIdIsEchoedAndAMissingOneIsMinted() {
        TestApp app = new TestApp();
        Res r = app.call("GET", "/health", null, null, Map.of("X-Request-ID", "abc-123"));
        assertThat(r.header("X-Request-ID")).isEqualTo("abc-123");
        assertThat(app.get("/health").header("X-Request-ID")).matches("[0-9a-f-]{36}");
    }

    @Test
    void errorsCarryTheRequestIdToo() {
        TestApp app = new TestApp();
        Res r = app.call("GET", "/api/v1/orders/1", null, null, Map.of("X-Request-ID", "trace-401"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.header("X-Request-ID")).isEqualTo("trace-401");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "evil\u00e9", "a;b",
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void unsafeRequestIdsAreReplaced(String value) {
        assertThat(RequestIdFilter.incomingOrNewId(value)).isNotEqualTo(value);
    }

    @Test
    void metricsListEveryCounter() {
        TestApp app = new TestApp();
        Res r = app.get("/metrics");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("Content-Type")).startsWith("text/plain");
        String body = rawBody(app);
        for (String name : Metrics.HELP.keySet()) {
            assertThat(body).contains("# TYPE " + name + " counter").contains("\n" + name + " 0");
        }
    }

    @Test
    void businessCounters() {
        TestApp app = new TestApp();
        JsonNode asha = app.customer();
        app.customer();
        app.call("POST", "/api/v1/auth/login", Map.of("email", "user1@shopflow.test", "password", "wrong-password"),
                (String) null);
        JsonNode first = app.order(asha);
        assertThat(app.checkout(asha, "checkout-key-0001").status()).isEqualTo(200); // replay of the same key
        JsonNode second = app.order(asha, "checkout-key-0002", Map.of());
        app.pay(first);
        app.pay(first); // the provider retries: duplicate
        app.clock.advance(Duration.ofMinutes(16));
        Res expire = app.call("POST", "/api/v1/admin/orders/expire", null, app.admin());
        assertThat(expire.json().get("expired").asInt()).isEqualTo(1);
        assertThat(app.get("/api/v1/orders/" + TestApp.id(second), asha).json().get("status").asString())
                .isEqualTo("CANCELLED");
        assertThat(metric(app, Metrics.USERS_REGISTERED)).isEqualTo(2);
        assertThat(metric(app, Metrics.LOGINS_FAILED)).isEqualTo(1);
        assertThat(metric(app, Metrics.ORDERS_PLACED)).isEqualTo(2);
        assertThat(metric(app, Metrics.CHECKOUT_REPLAYS)).isEqualTo(1);
        assertThat(metric(app, Metrics.WEBHOOKS_DUPLICATE)).isEqualTo(1);
        assertThat(metric(app, Metrics.ORDERS_EXPIRED)).isEqualTo(1);
        assertThat(metric(app, Metrics.AI_FALLBACK)).isZero();
    }

    @Test
    void fallbacksAreCounted() {
        LlmClient broken = (s, u, n) -> {
            throw new HttpTimeoutException("LLM timed out");
        };
        TestApp app = new TestApp(new DescriptionService(new TemplateWriter(), new LlmWriter(broken)),
                new SearchAssistService(new RuleParser(), new LlmParser(broken)), new Metrics());
        JsonNode product = app.product();
        Res draft = app.call("POST", "/api/v1/products/" + TestApp.sku(product) + "/description-draft", null,
                app.admin());
        assertThat(draft.json().get("fallback").asBoolean()).isTrue();
        assertThat(app.get("/api/v1/search/assist?q=shoes under 3000").json().get("fallback").asBoolean()).isTrue();
        assertThat(app.metrics.value(Metrics.AI_FALLBACK)).isEqualTo(2);
    }

    @Test
    void unknownMetricNamesAreRejected() {
        assertThatThrownBy(() -> new Metrics().inc("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- circuit breaker ----
    private static final class FakeLlm implements LlmClient {
        int calls;
        boolean fail = true;

        @Override
        public String chat(String system, String user, int maxTokens) throws Exception {
            calls++;
            if (fail) {
                throw new HttpTimeoutException("timed out");
            }
            return "ok";
        }
    }

    private final FakeLlm llm = new FakeLlm();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final AtomicInteger skipped = new AtomicInteger();
    private final CircuitBreaker breaker = new CircuitBreaker(llm, 3, 30_000, now::get, skipped::incrementAndGet);

    private String call() throws Exception {
        return breaker.chat("s", "u", 10);
    }

    @Test
    void breakerOpensAfterThreeFailuresInARowAndSkipsCalls() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(this::call).hasMessage("timed out");
        }
        assertThat(breaker.state()).isEqualTo("open");
        assertThatThrownBy(this::call).isInstanceOf(CircuitBreaker.OpenException.class);
        assertThat(llm.calls).isEqualTo(3);
        assertThat(skipped.get()).isEqualTo(1);
    }

    @Test
    void aSuccessResetsTheFailureCount() throws Exception {
        List<String> answers = new ArrayList<>();
        for (boolean fail : new boolean[] {true, true, false, true, true}) {
            llm.fail = fail;
            try {
                answers.add(call());
            } catch (HttpTimeoutException expected) {
                answers.add("failed");
            }
        }
        assertThat(answers).containsExactly("failed", "failed", "ok", "failed", "failed");
        assertThat(breaker.state()).isEqualTo("closed");
    }

    @Test
    void halfOpenTrialSuccessClosesAndFailureReopens() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(this::call).hasMessage("timed out");
        }
        now.addAndGet(30_000);
        assertThat(breaker.state()).isEqualTo("half-open");
        assertThatThrownBy(this::call).hasMessage("timed out");
        assertThat(breaker.state()).isEqualTo("open");
        assertThat(llm.calls).isEqualTo(4);
        now.addAndGet(30_000);
        llm.fail = false;
        assertThat(call()).isEqualTo("ok");
        assertThat(breaker.state()).isEqualTo("closed");
    }

    @Test
    void theRealWiringShortCircuitsAnUnreachableLlm() {
        Metrics metrics = new Metrics();
        LlmClient http = new HttpLlmClient("http://127.0.0.1:9/v1", "m", "", 500);
        LlmClient guarded = new CircuitBreaker(http, () -> metrics.inc(Metrics.LLM_SHORT_CIRCUITS));
        TestApp app = new TestApp(new DescriptionService(new TemplateWriter(), null),
                new SearchAssistService(new RuleParser(), new LlmParser(guarded)), metrics);
        for (int i = 0; i < 4; i++) {
            Res r = app.get("/api/v1/search/assist?q=running shoes under 3000");
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.json().get("fallback").asBoolean()).isTrue();
        }
        assertThat(metrics.value(Metrics.LLM_SHORT_CIRCUITS)).isEqualTo(1);
        assertThat(metrics.value(Metrics.AI_FALLBACK)).isEqualTo(4);
    }
}
