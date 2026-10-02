package org.aicancode.shopflow.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.aicancode.shopflow.db.DatabaseUrl;
import org.aicancode.shopflow.repo.JdbcUserRepository;
import org.aicancode.shopflow.security.Webhooks;
import org.aicancode.shopflow.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration tests: the real app on a random port against a real PostgreSQL (docker compose up -d postgres).
 * Run with: mvn verify -Pintegration. Uses TEST_DATABASE_URL so your dev data is never touched.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {

    static final String DB_URL = env("TEST_DATABASE_URL", "postgresql://shopflow:shopflow@localhost:5432/shopflow_test");
    static final String ADMIN_EMAIL = "admin@shopflow.test";
    static final String ADMIN_PASSWORD = "admin-password";
    static final String WEBHOOK_SECRET = "integration-webhook-secret-at-least-32-bytes";
    static final String TABLES = "users, products, carts, cart_items, orders, order_items, payments, payment_events, "
            + "outbox_events";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", () -> DB_URL);
        registry.add("JWT_SECRET", () -> "integration-test-secret-that-is-long-enough-123");
        registry.add("BCRYPT_COST", () -> "4");
        registry.add("ADMIN_EMAIL", () -> ADMIN_EMAIL);
        registry.add("ADMIN_PASSWORD", () -> ADMIN_PASSWORD);
        registry.add("PAYMENT_WEBHOOK_SECRET", () -> WEBHOOK_SECRET);
        registry.add("AI_PROVIDER", () -> "offline");
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    AuthService auth;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        jdbcClient.sql("TRUNCATE " + TABLES + " RESTART IDENTITY CASCADE").update();
        auth.ensureAdmin(ADMIN_EMAIL, ADMIN_PASSWORD); // what the app does at startup
    }

    record Res(int status, JsonNode json) {
    }

    private Res send(String method, String path, Object body, JsonNode session, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (session != null) {
            b.header("Authorization", "Bearer " + session.get("accessToken").asString());
        }
        headers.forEach(b::header);
        if (body instanceof byte[] raw) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(raw));
        } else if (body != null) {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body().isEmpty() ? null : JSON.readTree(r.body()));
    }

    private Res send(String method, String path, Object body, JsonNode session) throws Exception {
        return send(method, path, body, session, Map.of());
    }

    private JsonNode login(String email, String password) throws Exception {
        Res r = send("POST", "/api/v1/auth/login", Map.of("email", email, "password", password), null);
        assertThat(r.status()).isEqualTo(200);
        return r.json();
    }

    private JsonNode admin() throws Exception {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private JsonNode customer(String email) throws Exception {
        Res r = send("POST", "/api/v1/auth/register",
                Map.of("email", email, "password", "password-123", "displayName", "C"), null);
        assertThat(r.status()).isEqualTo(201);
        return login(email, "password-123");
    }

    private JsonNode product(String sku, Map<String, Object> fields) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", sku);
        body.put("name", "Trail Running Shoes");
        body.put("category", "shoes");
        body.put("pricePaise", 249_900);
        body.put("stock", 5);
        body.put("description", "Grippy soles for wet trails.");
        body.putAll(fields);
        Res r = send("POST", "/api/v1/products", body, admin());
        assertThat(r.status()).as(String.valueOf(r.json())).isEqualTo(201);
        return r.json();
    }

    private Res add(JsonNode session, String sku, int quantity) throws Exception {
        return send("PUT", "/api/v1/cart/items/" + sku, Map.of("quantity", quantity), session);
    }

    private Res checkout(JsonNode session, String key) throws Exception {
        return send("POST", "/api/v1/checkout", null, session, Map.of("Idempotency-Key", key));
    }

    private Res webhook(Map<String, Object> event) throws Exception {
        byte[] body = JSON.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        return send("POST", "/api/v1/payments/webhook", body, null,
                Map.of("X-Signature", Webhooks.sign(WEBHOOK_SECRET, body)));
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    private static List<String> skus(JsonNode items) {
        List<String> out = new ArrayList<>();
        items.forEach(p -> out.add(p.get("sku").asString()));
        return out;
    }

    private <T> List<T> concurrently(int n, Callable<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Callable<T>> calls = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                calls.add(call);
            }
            List<T> out = new ArrayList<>();
            for (Future<T> f : pool.invokeAll(calls)) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void migratesReportsHealthAndSeedsTheDemoCoupons() throws Exception {
        assertThat(send("GET", "/health", null, null).json().toString())
                .isEqualTo("{\"status\":\"UP\",\"checks\":{\"database\":\"UP\"}}");
        assertThat(jdbcClient.sql("SELECT version FROM schema_migrations ORDER BY version").query(Integer.class).list())
                .startsWith(1);
        assertThat(jdbcClient.sql("SELECT code FROM coupons WHERE active ORDER BY code").query(String.class).list())
                .containsExactly("FLAT100", "SAVE10");
    }

    @Test
    void hashesPasswordsAndKeepsEmailsUnique() throws Exception {
        customer("asha@shopflow.test");
        String hash = jdbcClient.sql("SELECT password_hash FROM users WHERE email = 'asha@shopflow.test'")
                .query(String.class).single();
        assertThat(hash).startsWith("$2").doesNotContain("password-123");
        assertThat(send("POST", "/api/v1/auth/register",
                Map.of("email", "ASHA@shopflow.test", "password", "password-123", "displayName", "D"), null).status())
                .isEqualTo(409);
    }

    @Test
    void enforcesSkuUniquenessAndOptimisticLockingInTheDatabase() throws Exception {
        product("SHOE-1", Map.of());
        Res dup = send("POST", "/api/v1/products", Map.of("sku", "shoe-1", "name", "x", "category", "shoes",
                "pricePaise", 1), admin());
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.json().get("type").asString()).endsWith("/sku-taken");
        Res a = send("PATCH", "/api/v1/products/SHOE-1", Map.of("version", 1, "pricePaise", 1000), admin());
        Res b = send("PATCH", "/api/v1/products/SHOE-1", Map.of("version", 1, "pricePaise", 2000), admin());
        assertThat(List.of(a.status(), b.status())).containsExactly(200, 409);
        Map<String, Object> row = jdbcClient.sql("SELECT price_paise, version FROM products WHERE sku = 'SHOE-1'")
                .query().singleRow();
        assertThat(row).containsEntry("price_paise", 1000L).containsEntry("version", 2);
    }

    @Test
    void stemsFullTextSearchAndFilters() throws Exception {
        product("SHOE-1", Map.of("name", "Trail Running Shoes", "pricePaise", 249_900));
        product("BAG-1", Map.of("name", "Laptop Backpack", "category", "bags", "pricePaise", 179_900,
                "description", "Waterproof."));
        product("OLD-1", Map.of("name", "Old Running Shoes", "active", false));
        // stemming: "shoe" finds "Shoes", "run" finds "Running"
        assertThat(skus(send("GET", "/api/v1/products?q=shoe%20run", null, null).json().get("items")))
                .containsExactly("SHOE-1");
        assertThat(skus(send("GET", "/api/v1/products?maxPricePaise=200000", null, null).json().get("items")))
                .containsExactly("BAG-1");
        String q = URLEncoder.encode("running shoe under 3000", StandardCharsets.UTF_8);
        JsonNode assist = send("GET", "/api/v1/search/assist?q=" + q, null, null).json();
        assertThat(assist.at("/filters/category").asString()).isEqualTo("shoes");
        assertThat(skus(assist.get("items"))).containsExactly("SHOE-1");
    }

    @Test
    void paginatesOverTheRealIndex() throws Exception {
        for (int n = 0; n < 5; n++) {
            product("P-" + n, Map.of());
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = send("GET", "/api/v1/products?limit=2" + (cursor == null ? "" : "&cursor=" + cursor), null,
                    null).json();
            seen.addAll(skus(page.get("items")));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
        } while (cursor != null);
        assertThat(seen).containsExactly("P-0", "P-1", "P-2", "P-3", "P-4");
    }

    @Test
    void refusesNegativeStockInTheDatabase() throws Exception {
        product("SHOE-1", Map.of("stock", 1));
        assertThatThrownBy(() -> jdbcClient.sql("UPDATE products SET stock = stock - 2 WHERE sku = 'SHOE-1'").update())
                .isInstanceOf(DataIntegrityViolationException.class); // CHECK (stock >= 0)
        Res r = send("POST", "/api/v1/products/SHOE-1/stock", Map.of("delta", -2), admin());
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.json().get("detail").asString()).isEqualTo("stock cannot go below 0 (current stock 1)");
    }

    @Test
    void checksOutInOneTransaction() throws Exception {
        JsonNode asha = customer("asha@shopflow.test");
        product("SHOE-1", Map.of("stock", 3));
        product("BAG-1", Map.of("stock", 1, "category", "bags", "pricePaise", 60_000));
        add(asha, "SHOE-1", 2);
        add(asha, "BAG-1", 1);
        send("PUT", "/api/v1/cart/coupon", Map.of("code", "SAVE10"), asha);
        Res r = checkout(asha, "asha-key-0001");
        assertThat(r.status()).as(String.valueOf(r.json())).isEqualTo(201);
        JsonNode order = r.json();
        assertThat(List.of(order.get("subtotalPaise").asLong(), order.get("discountPaise").asLong(),
                order.get("shippingPaise").asLong())).containsExactly(559_800L, 20_000L, 0L);
        assertThat(jdbcClient.sql("SELECT sku || '=' || stock FROM products ORDER BY sku").query(String.class).list())
                .containsExactly("BAG-1=0", "SHOE-1=1");
        assertThat(count("SELECT count(*) FROM cart_items")).isZero();
        assertThat(jdbcClient.sql("SELECT type FROM outbox_events").query(String.class).list())
                .containsExactly("order.placed");
        assertThat(jdbcClient.sql("SELECT status || '=' || amount_paise FROM payments").query(String.class).list())
                .containsExactly("PENDING=" + order.get("totalPaise").asLong());
        // a failing line rolls back everything: no order, no stock change, cart intact
        JsonNode ravi = customer("ravi@shopflow.test");
        add(ravi, "SHOE-1", 1);
        jdbcClient.sql("UPDATE products SET stock = 0 WHERE sku = 'SHOE-1'").update();
        assertThat(checkout(ravi, "ravi-key-0001").status()).isEqualTo(409);
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM cart_items")).isEqualTo(1);
    }

    @Test
    void neverOversellsUnderConcurrentCheckouts() throws Exception {
        product("LAST-1", Map.of("stock", 1));
        List<JsonNode> sessions = new ArrayList<>();
        for (int n = 0; n < 5; n++) {
            JsonNode s = customer("buyer" + n + "@shopflow.test");
            add(s, "LAST-1", 1);
            sessions.add(s);
        }
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (JsonNode s : sessions) {
                calls.add(() -> checkout(s, "same-key-for-all").status());
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                codes.add(f.get());
            }
            assertThat(codes).containsExactlyInAnyOrder(201, 409, 409, 409, 409);
        } finally {
            pool.shutdown();
        }
        assertThat(count("SELECT stock FROM products WHERE sku = 'LAST-1'")).isZero();
    }

    @Test
    void createsOneOrderForConcurrentRetriesWithOneKey() throws Exception {
        JsonNode asha = customer("asha@shopflow.test");
        product("SHOE-1", Map.of("stock", 10));
        add(asha, "SHOE-1", 1);
        List<Res> results = concurrently(4, () -> checkout(asha, "double-click-0001"));
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(1);
        Set<Long> ids = new HashSet<>();
        results.forEach(r -> {
            if (r.json() != null && r.json().has("id")) {
                ids.add(r.json().get("id").asLong());
            }
        });
        assertThat(ids).hasSize(1);
        assertThat(count("SELECT stock FROM products")).isEqualTo(9);
    }

    @Test
    void processesAWebhookDeliveredTwiceAtOnceExactlyOnce() throws Exception {
        JsonNode asha = customer("asha@shopflow.test");
        product("SHOE-1", Map.of("stock", 2));
        add(asha, "SHOE-1", 1);
        JsonNode order = checkout(asha, "asha-key-0001").json();
        Map<String, Object> event = Map.of("id", "evt_integration_1", "type", "payment.succeeded",
                "paymentId", order.at("/payment/id").asString());
        List<String> statuses = concurrently(3, () -> {
            Res r = webhook(event);
            return r.json() != null && r.json().has("eventId") ? r.json().get("status").asString() : "" + r.status();
        });
        assertThat(statuses).containsOnlyOnce("processed");
        assertThat(jdbcClient.sql("SELECT status FROM orders").query(String.class).list()).containsExactly("PAID");
        assertThat(jdbcClient.sql("SELECT type FROM outbox_events ORDER BY id").query(String.class).list())
                .containsExactly("order.placed", "order.paid");
        assertThat(count("SELECT count(*) FROM payment_events")).isEqualTo(1);
    }

    @Test
    void restocksOnCancelAndTheRelayMarksEventsPublished() throws Exception {
        JsonNode asha = customer("asha@shopflow.test");
        product("SHOE-1", Map.of("stock", 2));
        add(asha, "SHOE-1", 2);
        JsonNode order = checkout(asha, "asha-key-0001").json();
        long id = order.get("id").asLong();
        assertThat(send("POST", "/api/v1/orders/" + id + "/cancel", null, asha).json().get("status").asString())
                .isEqualTo("CANCELLED");
        assertThat(count("SELECT stock FROM products")).isEqualTo(2);
        assertThat(send("POST", "/api/v1/admin/outbox/publish", null, admin()).json().toString())
                .isEqualTo("{\"published\":2}");
        assertThat(count("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")).isZero();
        String payload = jdbcClient.sql("SELECT payload::text FROM outbox_events WHERE type = 'order.cancelled'")
                .query(String.class).single();
        assertThat(JSON.readTree(payload)).isEqualTo(JSON.readTree("{\"orderId\":" + id + ",\"userId\":"
                + asha.at("/user/id").asLong() + ",\"status\":\"CANCELLED\",\"totalPaise\":"
                + order.get("totalPaise").asLong() + "}"));
    }

    /** v1.1: V2 adds orders.expires_at; the expiry job cancels overdue unpaid orders and restocks them. */
    @Test
    void expiresOverdueUnpaidOrdersInTheDatabase() throws Exception {
        JsonNode asha = customer("asha@shopflow.test");
        product("EXP-1", Map.of("stock", 2));
        add(asha, "EXP-1", 2);
        JsonNode order = checkout(asha, "expiry-int-0001").json();
        long id = order.get("id").asLong();
        assertThat(order.get("expiresAt").isNull()).isFalse();
        assertThat(jdbcClient.sql("SELECT version FROM schema_migrations ORDER BY version").query(Integer.class).list())
                .containsExactly(1, 2);
        String url = "/api/v1/admin/orders/expire";
        assertThat(send("POST", url, null, admin()).json().get("expired").asInt()).isZero();
        jdbcClient.sql("UPDATE orders SET expires_at = now() - interval '1 second' WHERE id = ?").param(id).update();
        assertThat(send("POST", url, null, admin()).json().get("expired").asInt()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT status FROM orders WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo("CANCELLED");
        assertThat(count("SELECT stock FROM products WHERE sku = 'EXP-1'")).isEqualTo(2);
        assertThat(send("POST", url, null, admin()).json().get("expired").asInt()).isZero();
    }

    @Test
    void bootstrapsTheAdminFromTheEnvironment() {
        assertThat(jdbcClient.sql("SELECT role FROM users WHERE email = ?").param(ADMIN_EMAIL).query(String.class).single())
                .isEqualTo("ADMIN");
    }

    @Test
    void reportsTheDatabaseAsDownWhenItIsUnreachable() {
        DatabaseUrl url = DatabaseUrl.parse(DB_URL);
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url.jdbcUrl());
        ds.setUsername(url.username());
        ds.setPassword(url.password());
        ds.setConnectionTimeout(1000);
        JdbcUserRepository repo = new JdbcUserRepository(JdbcClient.create(ds));
        assertThat(repo.ping()).isTrue();
        ds.close(); // simulate the database going away: every new connection now fails
        assertThat(repo.ping()).isFalse();
    }
}
