package org.aicancode.shopflow.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.aicancode.shopflow.ai.SearchAssistTest.FakeChat;
import org.aicancode.shopflow.domain.Models.Product;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** M6: product description drafts and the OpenAI-compatible HTTP client. */
class DescriptionTest {

    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

    private static Product product(String description, int stock) {
        return new Product(1, "SHOE-1", "Trail Running Shoes", description, "shoes", 249_900, stock, true, 1, NOW, NOW);
    }

    private static Product product() {
        return product("Light and grippy. Made for wet trails.", 5);
    }

    @Test
    void isDeterministicWithTheTemplate() {
        assertThat(new TemplateWriter().write(product(), List.of("breathable", "vegan"))).isEqualTo(
                "Trail Running Shoes from our shoes range. Light and grippy. Highlights: breathable, vegan. "
                        + "In stock and ready to ship.");
        assertThat(new TemplateWriter().write(product("", 0), List.of()))
                .isEqualTo("Trail Running Shoes from our shoes range. Currently out of stock.");
        String text = new TemplateWriter().write(product("x".repeat(2000), 5), List.of());
        assertThat(text).hasSize(600).endsWith("...");
    }

    @Test
    void usesTheLlmAndFallsBack() {
        FakeChat llm = new FakeChat("  A light trail shoe.\n\nGrips wet rock.  ");
        DescriptionService.Draft result = new DescriptionService(new TemplateWriter(), new LlmWriter(llm))
                .draft(product(), List.of("vegan"));
        assertThat(List.of(result.provider(), result.fallback(), result.description()))
                .containsExactly("llm", false, "A light trail shoe. Grips wet rock.");
        assertThat(llm.calls.get(0)[1]).contains("Keywords: vegan").startsWith("<product>");
        result = new DescriptionService(new TemplateWriter(), new LlmWriter(new FakeChat(null))).draft(product(), List.of());
        assertThat(List.of(result.provider(), result.fallback())).containsExactly("template", true);
        result = new DescriptionService(new TemplateWriter(), new LlmWriter(new FakeChat("   "))).draft(product(), List.of());
        assertThat(result.fallback()).isTrue();
    }

    @Test
    void postsChatCompletions() throws Exception {
        List<Map<?, ?>> seen = new ArrayList<>();
        List<String> auth = new ArrayList<>();
        HttpServer server = fakeLlm(200, " hi ", seen, auth);
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            assertThat(new HttpLlmClient(base, "tiny", "k-123", 2000).chat("sys", "user", 50)).isEqualTo("hi");
            assertThat(auth).containsExactly("Bearer k-123");
            assertThat(List.of(seen.get(0).get("model"), seen.get(0).get("temperature"), seen.get(0).get("max_tokens")))
                    .containsExactly("tiny", 0, 50);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void throwsOnErrorsAndEmptyAnswers() throws Exception {
        HttpServer broken = fakeLlm(500, "ok", new ArrayList<>(), new ArrayList<>());
        HttpServer empty = fakeLlm(200, "  ", new ArrayList<>(), new ArrayList<>());
        try {
            for (HttpServer s : List.of(broken, empty)) {
                String base = "http://127.0.0.1:" + s.getAddress().getPort() + "/v1";
                assertThatThrownBy(() -> new HttpLlmClient(base, "m", null, 2000).chat("s", "u", 5))
                        .isInstanceOf(Exception.class);
            }
            // nothing listens on port 9
            assertThatThrownBy(() -> new HttpLlmClient("http://127.0.0.1:9/v1", "m", null, 1000).chat("s", "u", 5))
                    .isInstanceOf(Exception.class);
        } finally {
            broken.stop(0);
            empty.stop(0);
        }
    }

    /** A tiny OpenAI-compatible server that answers every request with `content` and records the requests. */
    static HttpServer fakeLlm(int status, String content, List<Map<?, ?>> seen, List<String> auth) throws IOException {
        JsonMapper json = JsonMapper.builder().build();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            seen.add(json.readValue(exchange.getRequestBody().readAllBytes(), Map.class));
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = json.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", content)))))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }
}
