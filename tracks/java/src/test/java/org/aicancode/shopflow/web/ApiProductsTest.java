package org.aicancode.shopflow.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.aicancode.shopflow.support.TestApp;
import org.aicancode.shopflow.support.TestApp.Res;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M3: the catalog endpoints over HTTP. */
class ApiProductsTest {

    private static final Map<String, Object> NEW = Map.of("sku", "tee-blk-m", "name", "Black Tee (M)", "category",
            "apparel", "pricePaise", 59_900, "stock", 4);
    private final TestApp api = new TestApp();

    @Test
    void createsWith201ALocationAndTheFullProduct() {
        Res r = api.call("POST", "/api/v1/products", NEW, api.admin());
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.header("Location")).isEqualTo("/api/v1/products/TEE-BLK-M");
        assertThat(r.json().toString()).isEqualTo("{\"id\":" + r.json().get("id") + ",\"sku\":\"TEE-BLK-M\","
                + "\"name\":\"Black Tee (M)\",\"description\":\"\",\"category\":\"apparel\",\"pricePaise\":59900,"
                + "\"price\":\"₹599.00\",\"stock\":4,\"inStock\":true,\"active\":true,\"version\":1,"
                + "\"createdAt\":\"2026-10-02T09:30:00.000Z\",\"updatedAt\":\"2026-10-02T09:30:00.000Z\"}");
    }

    @Test
    void needsAnAdminForCatalogWrites() {
        Res r = api.call("POST", "/api/v1/products", NEW, (String) null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.header("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(api.call("POST", "/api/v1/products", NEW, api.customer()).status()).isEqualTo(403);
    }

    @Test
    void servesPublicReadsAndTheList() {
        JsonNode p = api.product(Map.of("sku", "TEE-1"));
        api.product(Map.of("sku", "TEE-2", "active", false));
        assertThat(api.get("/api/v1/products/tee-1").json().get("sku").asString()).isEqualTo("TEE-1");
        assertThat(api.get("/api/v1/products/TEE-2").status()).isEqualTo(404);
        assertThat(api.get("/api/v1/products/TEE-2", api.admin()).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/products").json().toString()).isEqualTo("{\"items\":[{\"sku\":\"TEE-1\",\"name\":\""
                + p.get("name").asString() + "\",\"category\":\"shoes\",\"pricePaise\":249900,\"price\":\"₹2,499.00\","
                + "\"inStock\":true}],\"nextCursor\":null}");
    }

    @Test
    void validatesTheListQueryParameters() {
        assertThat(api.get("/api/v1/products?limit=0").status()).isEqualTo(400);
        assertThat(api.get("/api/v1/products?cursor=%25%25%25").status()).isEqualTo(400);
        assertThat(api.get("/api/v1/products?inStock=maybe").json().get("detail").asString())
                .isEqualTo("inStock must be true or false");
        assertThat(api.get("/api/v1/products?minPricePaise=9&maxPricePaise=1").status()).isEqualTo(400);
        assertThat(api.get("/api/v1/products?limit=1&limit=0").status()).isEqualTo(400); // the last value counts
    }

    @Test
    void patchesWithOptimisticLocking() {
        JsonNode p = api.product(Map.of("sku", "TEE-1"));
        String url = "/api/v1/products/TEE-1";
        Res ok = api.call("PATCH", url, Map.of("version", 1, "pricePaise", 199_900), api.admin());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.json().get("version").asInt()).isEqualTo(2);
        assertThat(ok.json().get("price").asString()).isEqualTo("₹1,999.00");
        Res stale = api.call("PATCH", url, Map.of("version", 1, "name", "Lost update"), api.admin());
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.json().get("type").asString()).endsWith("/version-conflict");
        assertThat(api.get(url).json().get("name").asString()).isEqualTo(p.get("name").asString());
    }

    @Test
    void adjustsStock() {
        api.product(Map.of("sku", "TEE-1", "stock", 1));
        Res first = api.call("POST", "/api/v1/products/TEE-1/stock", Map.of("delta", -1), api.admin());
        assertThat(first.json().get("inStock").asBoolean()).isFalse();
        Res r = api.call("POST", "/api/v1/products/TEE-1/stock", Map.of("delta", -1), api.admin());
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.json().get("type").asString()).endsWith("/insufficient-stock");
    }

    @Test
    void listsCategories() {
        api.product(Map.of("sku", "TEE-1", "category", "apparel"));
        api.product(Map.of("sku", "TEE-2", "category", "apparel"));
        assertThat(api.get("/api/v1/categories").json().toString())
                .isEqualTo("{\"items\":[{\"name\":\"apparel\",\"products\":2}]}");
    }
}
