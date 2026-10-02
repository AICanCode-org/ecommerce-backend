package org.aicancode.shopflow.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aicancode.shopflow.ai.DescriptionService;
import org.aicancode.shopflow.ai.SearchAssistService;
import org.aicancode.shopflow.domain.Models.CategoryCount;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.domain.Models.Product;
import org.aicancode.shopflow.domain.Models.ProductQuery;
import org.aicancode.shopflow.domain.Models.SearchFilters;
import org.aicancode.shopflow.domain.Times;
import org.aicancode.shopflow.domain.Validation;
import org.aicancode.shopflow.metrics.Metrics;
import org.aicancode.shopflow.service.AuthService;
import org.aicancode.shopflow.service.CatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Catalog, categories and the two AI endpoints (description drafts, search assist). */
@RestController
public class ProductController {

    public static final int ASSIST_RESULTS = 20;
    private final AuthService auth;
    private final CatalogService catalog;
    private final DescriptionService descriptions;
    private final SearchAssistService assist;
    private final Clock clock;
    private final Metrics metrics;

    public ProductController(AuthService auth, CatalogService catalog, DescriptionService descriptions,
                             SearchAssistService assist, Clock clock, Metrics metrics) {
        this.metrics = metrics;
        this.auth = auth;
        this.catalog = catalog;
        this.descriptions = descriptions;
        this.assist = assist;
        this.clock = clock;
    }

    /** A repeated query parameter (?limit=1&limit=2) counts as its last value, like in the other tracks. */
    static String param(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values == null || values.length == 0 ? null : values[values.length - 1];
    }

    @GetMapping("/api/v1/products")
    public Map<String, Object> list(HttpServletRequest request) {
        CatalogService.Page page = catalog.list(param(request, "q"), param(request, "category"),
                param(request, "minPricePaise"), param(request, "maxPricePaise"), param(request, "inStock"),
                param(request, "limit"), param(request, "cursor"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", Json.productItems(page.items()));
        m.put("nextCursor", page.nextCursor());
        return m;
    }

    @PostMapping("/api/v1/products")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Object body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Product product = catalog.create(auth.authenticate(authorization), body);
        return ResponseEntity.status(201).header("Location", "/api/v1/products/" + product.sku())
                .body(Json.product(product));
    }

    @GetMapping("/api/v1/products/{sku}")
    public Map<String, Object> get(@PathVariable String sku,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return Json.product(catalog.get(auth.optional(authorization), sku));
    }

    @PatchMapping("/api/v1/products/{sku}")
    public Map<String, Object> update(@PathVariable String sku, @RequestBody(required = false) Object body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return Json.product(catalog.update(auth.authenticate(authorization), sku, body));
    }

    @PostMapping("/api/v1/products/{sku}/stock")
    public Map<String, Object> stock(@PathVariable String sku, @RequestBody(required = false) Object body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return Json.product(catalog.adjustStock(auth.authenticate(authorization), sku, body));
    }

    @GetMapping("/api/v1/categories")
    public Map<String, Object> categories() {
        return Map.of("items", catalog.categories().stream().map(Json::category).toList());
    }

    @PostMapping("/api/v1/products/{sku}/description-draft")
    public Map<String, Object> descriptionDraft(@PathVariable String sku, @RequestBody(required = false) Object body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Principal who = auth.authenticate(authorization);
        AuthService.requireRole(who, "ADMIN");
        List<String> words = body == null ? List.of() : Validation.keywords(Validation.requireObject(body).get("keywords"));
        Product product = catalog.get(who, sku);
        DescriptionService.Draft result = descriptions.draft(product, words);
        if (result.fallback()) {
            metrics.inc(Metrics.AI_FALLBACK);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sku", product.sku());
        m.put("provider", result.provider());
        m.put("fallback", result.fallback());
        m.put("description", result.description());
        m.put("generatedAt", Times.iso(Times.now(clock)));
        return m;
    }

    @GetMapping("/api/v1/search/assist")
    public Map<String, Object> searchAssist(HttpServletRequest request) {
        String query = Validation.assistQuery(param(request, "q"));
        List<String> known = catalog.categories().stream().map(CategoryCount::name).toList();
        SearchAssistService.Assist result = assist.interpret(query, known);
        if (result.fallback()) {
            metrics.inc(Metrics.AI_FALLBACK);
        }
        SearchFilters f = result.filters();
        String terms = String.join(" ", f.terms());
        CatalogService.Page page = catalog.search(new ProductQuery(ASSIST_RESULTS, null, terms.isEmpty() ? null : terms,
                f.category(), f.minPricePaise(), f.maxPricePaise(), f.inStock()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("query", query);
        m.put("provider", result.provider());
        m.put("fallback", result.fallback());
        m.put("filters", Json.filters(f));
        m.put("items", Json.productItems(page.items()));
        return m;
    }
}
