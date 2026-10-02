package org.aicancode.shopflow.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.aicancode.shopflow.ai.DescriptionService;
import org.aicancode.shopflow.ai.RuleParser;
import org.aicancode.shopflow.ai.SearchAssistService;
import org.aicancode.shopflow.ai.TemplateWriter;
import org.aicancode.shopflow.metrics.Metrics;
import org.aicancode.shopflow.security.PasswordHasher;
import org.aicancode.shopflow.security.TokenService;
import org.aicancode.shopflow.security.Webhooks;
import org.aicancode.shopflow.service.AuthService;
import org.aicancode.shopflow.service.CartService;
import org.aicancode.shopflow.service.CatalogService;
import org.aicancode.shopflow.service.OrderService;
import org.aicancode.shopflow.service.OutboxService;
import org.aicancode.shopflow.service.PaymentService;
import org.aicancode.shopflow.web.AuthController;
import org.aicancode.shopflow.web.CartController;
import org.aicancode.shopflow.web.HealthController;
import org.aicancode.shopflow.web.MetricsController;
import org.aicancode.shopflow.web.OrderController;
import org.aicancode.shopflow.web.ProblemHandler;
import org.aicancode.shopflow.web.ProductController;
import org.aicancode.shopflow.web.RequestIdFilter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real controllers on in-memory adapters (no database, no network, no Spring context), plus helpers that read
 * like the user stories. The same helpers exist in the Python (conftest.py) and TypeScript (helpers.ts) tracks.
 */
public class TestApp {

    public static final String TEST_SECRET = "test-secret-that-is-at-least-32-bytes-long";
    public static final String WEBHOOK_SECRET = "test-webhook-secret-at-least-32-bytes";
    public static final JsonMapper JSON = JsonMapper.builder().build();

    public final InMemoryStore store = new InMemoryStore();
    public final MutableClock clock = new MutableClock();
    public final MockMvc mvc;
    public final Metrics metrics;
    private int n;
    private JsonNode adminSession;

    public TestApp() {
        this(offlineDescriptions(), offlineAssist(), new Metrics());
    }

    /** The real controllers on in-memory adapters; pass other AI services to test fallbacks (v1.1). */
    public TestApp(DescriptionService descriptions, SearchAssistService assist, Metrics metrics) {
        this.metrics = metrics;
        AuthService auth = auth(store, clock);
        auth.ensureAdmin("admin@shopflow.test", "admin-password");
        CatalogService catalog = new CatalogService(store, clock);
        OrderService orders = new OrderService(store, store, clock, paymentIds());
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth, metrics),
                        new ProductController(auth, catalog, descriptions, assist, clock, metrics),
                        new CartController(auth, new CartService(store, store, clock)),
                        new OrderController(auth, orders, new PaymentService(store, WEBHOOK_SECRET, clock),
                                new OutboxService(store, event -> { }, clock), metrics),
                        new HealthController(auth), new MetricsController(metrics))
                .setControllerAdvice(new ProblemHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    public static AuthService auth(InMemoryStore store, Clock clock) {
        return new AuthService(store, new PasswordHasher(4), new TokenService(TEST_SECRET, clock), clock);
    }

    public static DescriptionService offlineDescriptions() {
        return new DescriptionService(new TemplateWriter(), null);
    }

    public static SearchAssistService offlineAssist() {
        return new SearchAssistService(new RuleParser(), null);
    }

    /** Predictable payment ids: pay_test_1, pay_test_2, ... */
    public static Supplier<String> paymentIds() {
        int[] next = {0};
        return () -> "pay_test_" + ++next[0];
    }

    /** A response: status, headers and the parsed JSON body (null when there is no body). */
    public record Res(int status, MockHttpServletResponse raw, JsonNode json) {
        public String header(String name) {
            return raw.getHeader(name);
        }
    }

    public Res call(String method, String url, Object body, String authorization, Map<String, String> headers) {
        try {
            MockHttpServletRequestBuilder b = request(HttpMethod.valueOf(method), url);
            if (body instanceof String raw) {
                b.contentType(MediaType.APPLICATION_JSON).content(raw);
            } else if (body != null) {
                b.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
            }
            if (authorization != null) {
                b.header("Authorization", authorization);
            }
            headers.forEach(b::header);
            MockHttpServletResponse r = mvc.perform(b).andReturn().getResponse();
            String text = r.getContentAsString(StandardCharsets.UTF_8);
            boolean json = r.getContentType() != null && r.getContentType().contains("json");
            return new Res(r.getStatus(), r, text.isEmpty() || !json ? null : JSON.readTree(text));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Res call(String method, String url, Object body, String authorization) {
        return call(method, url, body, authorization, Map.of());
    }

    public Res call(String method, String url, Object body, JsonNode session) {
        return call(method, url, body, bearer(session), Map.of());
    }

    private static String bearer(JsonNode session) {
        return session == null ? null : "Bearer " + session.get("accessToken").asString();
    }

    public Res get(String url) {
        return call("GET", url, null, (String) null);
    }

    public Res get(String url, JsonNode session) {
        return call("GET", url, null, session);
    }

    /** Register + log in a new customer. */
    public JsonNode customer(String name) {
        n++;
        String email = "user" + n + "@shopflow.test";
        Res r = call("POST", "/api/v1/auth/register",
                Map.of("email", email, "password", "password-123", "displayName", name == null ? "User " + n : name),
                (String) null);
        assertThat(r.status()).as(String.valueOf(r.json())).isEqualTo(201);
        return login(email, "password-123");
    }

    public JsonNode customer() {
        return customer(null);
    }

    public JsonNode admin() {
        if (adminSession == null) {
            adminSession = login("admin@shopflow.test", "admin-password");
        }
        return adminSession;
    }

    public JsonNode login(String email, String password) {
        Res r = call("POST", "/api/v1/auth/login", Map.of("email", email, "password", password), (String) null);
        assertThat(r.status()).as(String.valueOf(r.json())).isEqualTo(200);
        return r.json();
    }

    public JsonNode product(Map<String, Object> fields) {
        n++;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", String.format("SKU-%03d", n));
        body.put("name", "Trail Running Shoes");
        body.put("category", "shoes");
        body.put("pricePaise", 249_900);
        body.put("stock", 5);
        body.put("description", "Light and grippy. Made for wet trails.");
        body.putAll(fields);
        Res r = call("POST", "/api/v1/products", body, admin());
        assertThat(r.status()).as(String.valueOf(r.json())).isEqualTo(201);
        return r.json();
    }

    public JsonNode product() {
        return product(Map.of());
    }

    public JsonNode add(JsonNode session, String sku, int quantity) {
        Res r = call("PUT", "/api/v1/cart/items/" + sku, Map.of("quantity", quantity), session);
        assertThat(r.status()).as(String.valueOf(r.json())).isEqualTo(200);
        return r.json();
    }

    public Res checkout(JsonNode session, String key) {
        return call("POST", "/api/v1/checkout", null, bearer(session), Map.of("Idempotency-Key", key));
    }

    public JsonNode order(JsonNode session, String key, Map<String, Object> productFields) {
        JsonNode product = product(productFields);
        add(session, product.get("sku").asString(), 1);
        Res r = checkout(session, key);
        assertThat(r.status()).as(String.valueOf(r.json())).isEqualTo(201);
        return r.json();
    }

    public JsonNode order(JsonNode session) {
        return order(session, "checkout-key-0001", Map.of());
    }

    public Res webhook(Map<String, Object> event, String secret, String signature) {
        String body = JSON.writeValueAsString(event);
        String sig = signature != null ? signature : Webhooks.sign(secret, body.getBytes(StandardCharsets.UTF_8));
        return call("POST", "/api/v1/payments/webhook", body, null, Map.of("X-Signature", sig));
    }

    public Res pay(JsonNode order, String eventId, String kind) {
        return webhook(Map.of("id", eventId, "type", kind, "paymentId", order.at("/payment/id").asString()),
                WEBHOOK_SECRET, null);
    }

    public Res pay(JsonNode order) {
        return pay(order, "evt_test_0001", "payment.succeeded");
    }

    public static long id(JsonNode node) {
        return node.get("id").asLong();
    }

    public static String sku(JsonNode node) {
        return node.get("sku").asString();
    }
}
