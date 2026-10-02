package org.aicancode.shopflow.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.aicancode.shopflow.domain.ApiException;
import org.aicancode.shopflow.domain.DuplicateException;
import org.aicancode.shopflow.domain.Models.CategoryCount;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.domain.Models.Product;
import org.aicancode.shopflow.domain.Models.ProductQuery;
import org.aicancode.shopflow.domain.Times;
import org.aicancode.shopflow.domain.Validation;
import org.aicancode.shopflow.repo.ProductRepository;

/** Catalog: products, prices, stock and search. Admins write, everybody reads active products. */
public class CatalogService {

    public static final List<String> EDITABLE = List.of("name", "description", "category", "pricePaise", "active");
    private final ProductRepository products;
    private final Clock clock;

    public CatalogService(ProductRepository products, Clock clock) {
        this.products = products;
        this.clock = clock;
    }

    /** A page of results plus the opaque cursor of the next page (null on the last page). */
    public record Page(List<Product> items, String nextCursor) {
    }

    public static boolean isAdmin(Principal principal) {
        return principal != null && principal.isAdmin();
    }

    public Product create(Principal principal, Object payload) {
        AuthService.requireRole(principal, "ADMIN");
        Map<String, Object> data = Validation.requireObject(payload);
        Instant now = Times.now(clock);
        Product product = new Product(0, Validation.sku(data.get("sku")), Validation.productName(data.get("name")),
                Validation.description(data.get("description")), Validation.category(data.get("category")),
                Validation.pricePaise(data.get("pricePaise")), Validation.stock(data.get("stock")),
                data.get("active") == null || Validation.active(data.get("active")), 1, now, now);
        try {
            return products.insertProduct(product);
        } catch (DuplicateException e) {
            throw ApiException.skuTaken();
        }
    }

    /** Inactive products are 404 for everyone but admins (they still exist for old orders). */
    public Product get(Principal principal, String sku) {
        Product product = products.findProduct(sku.strip().toUpperCase(Locale.ROOT)).orElse(null);
        if (product == null || (!product.active() && !isAdmin(principal))) {
            throw ApiException.notFound("product");
        }
        return product;
    }

    public Product update(Principal principal, String sku, Object payload) {
        AuthService.requireRole(principal, "ADMIN");
        Map<String, Object> data = Validation.requireObject(payload);
        int expected = Validation.version(data.get("version"));
        if (EDITABLE.stream().noneMatch(data::containsKey)) {
            throw ApiException.validation("nothing to update: send at least one of " + String.join(", ", EDITABLE));
        }
        Product current = get(principal, sku);
        Product changed = new Product(current.id(), current.sku(),
                data.containsKey("name") ? Validation.productName(data.get("name")) : current.name(),
                data.containsKey("description") ? Validation.description(data.get("description")) : current.description(),
                data.containsKey("category") ? Validation.category(data.get("category")) : current.category(),
                data.containsKey("pricePaise") ? Validation.pricePaise(data.get("pricePaise")) : current.pricePaise(),
                current.stock(),
                data.containsKey("active") ? Validation.active(data.get("active")) : current.active(),
                current.version(), current.createdAt(), Times.now(clock));
        return products.updateProduct(changed, expected).orElseThrow(() -> ApiException.versionConflict(
                products.findProduct(current.sku()).map(Product::version).orElse(current.version())));
    }

    /** Stock moves by a delta in ONE guarded statement. It does not bump the version (ADR-0003). */
    public Product adjustStock(Principal principal, String sku, Object payload) {
        AuthService.requireRole(principal, "ADMIN");
        int delta = Validation.stockDelta(Validation.requireObject(payload).get("delta"));
        Product current = get(principal, sku);
        return products.adjustStock(current.id(), delta, Times.now(clock)).orElseThrow(() -> {
            int have = products.findProduct(current.sku()).map(Product::stock).orElse(current.stock());
            return ApiException.insufficientStock("stock cannot go below 0 (current stock " + have + ")");
        });
    }

    public Page list(String q, String category, String minPrice, String maxPrice, String inStock, String limit,
                     String cursor) {
        return search(new ProductQuery(Validation.limit(limit), Validation.decodeCursor(cursor), Validation.searchQuery(q),
                Validation.categoryFilter(category), Validation.priceFilter(minPrice, "minPricePaise"),
                Validation.priceFilter(maxPrice, "maxPricePaise"), Validation.boolFilter(inStock, "inStock")));
    }

    public Page search(ProductQuery query) {
        Long lo = query.minPricePaise();
        Long hi = query.maxPricePaise();
        if (lo != null && hi != null && lo > hi) {
            throw ApiException.validation("minPricePaise must not be greater than maxPricePaise");
        }
        // Ask for one extra row: if it exists there is a next page (no COUNT(*) needed).
        List<Product> rows = products.searchProducts(query.withLimit(query.limit() + 1));
        List<Product> page = rows.subList(0, Math.min(rows.size(), query.limit()));
        String next = rows.size() > query.limit() ? Validation.encodeCursor(page.get(page.size() - 1).id()) : null;
        return new Page(List.copyOf(page), next);
    }

    public List<CategoryCount> categories() {
        return products.categories();
    }
}
