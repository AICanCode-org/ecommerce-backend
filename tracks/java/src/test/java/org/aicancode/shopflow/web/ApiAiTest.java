package org.aicancode.shopflow.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.aicancode.shopflow.support.TestApp;
import org.aicancode.shopflow.support.TestApp.Res;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M6: the AI endpoints over HTTP (offline providers). */
class ApiAiTest {

    private final TestApp api = new TestApp();

    @Test
    void draftsDescriptionsForAdminsOnlyAndNeverSavesThem() {
        JsonNode p = api.product(Map.of("sku", "SHOE-1"));
        String url = "/api/v1/products/SHOE-1/description-draft";
        Res r = api.call("POST", url, Map.of("keywords", List.of("Vegan")), api.admin());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().toString()).isEqualTo("{\"sku\":\"SHOE-1\",\"provider\":\"template\",\"fallback\":false,"
                + "\"description\":\"Trail Running Shoes from our shoes range. Light and grippy. Highlights: vegan. "
                + "In stock and ready to ship.\",\"generatedAt\":\"2026-10-02T09:30:00.000Z\"}");
        assertThat(api.get("/api/v1/products/SHOE-1").json().get("description")).isEqualTo(p.get("description"));
        assertThat(api.call("POST", url, null, api.admin()).status()).isEqualTo(200); // the body is optional
        assertThat(api.call("POST", url, null, api.customer()).status()).isEqualTo(403);
        assertThat(api.call("POST", url, null, (String) null).status()).isEqualTo(401);
        assertThat(api.call("POST", url, Map.of("keywords", "x"), api.admin()).status()).isEqualTo(400);
        assertThat(api.call("POST", "/api/v1/products/NOPE-1/description-draft", null, api.admin()).status())
                .isEqualTo(404);
    }

    @Test
    void turnsASentenceIntoCatalogFilters() {
        api.product(Map.of("sku", "SHOE-1", "name", "Trail Running Shoes", "pricePaise", 249_900));
        api.product(Map.of("sku", "SHOE-2", "name", "Road Running Shoes", "pricePaise", 349_900));
        api.product(Map.of("sku", "BAG-1", "name", "Running Belt", "category", "bags", "pricePaise", 99_900));
        Res r = api.get("/api/v1/search/assist?q=running shoes under 3000"); // MockMvc encodes the URL
        assertThat(r.status()).isEqualTo(200);
        JsonNode body = r.json();
        assertThat(body.get("query").asString()).isEqualTo("running shoes under 3000");
        assertThat(body.get("provider").asString()).isEqualTo("rules");
        assertThat(body.get("fallback").asBoolean()).isFalse();
        assertThat(body.get("filters").toString()).isEqualTo("{\"terms\":[\"running\"],\"category\":\"shoes\","
                + "\"minPricePaise\":null,\"maxPricePaise\":300000,\"inStock\":null}");
        List<String> skus = new ArrayList<>();
        body.get("items").forEach(p -> skus.add(p.get("sku").asString()));
        assertThat(skus).containsExactly("SHOE-1");
        assertThat(api.get("/api/v1/search/assist").status()).isEqualTo(400);
        assertThat(api.get("/api/v1/search/assist?q=" + "x".repeat(201)).status()).isEqualTo(400);
    }
}
