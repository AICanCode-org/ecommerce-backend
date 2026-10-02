package org.aicancode.shopflow.service;

import java.time.Clock;
import java.util.List;
import org.aicancode.shopflow.domain.Models.CategoryCount;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.domain.Models.Product;
import org.aicancode.shopflow.domain.Models.ProductQuery;
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
        // TODO(phase-3 M3): true when there is a principal and its role is ADMIN.
        throw new UnsupportedOperationException("TODO(phase-3 M3): CatalogService.isAdmin");
    }

    public Product create(Principal principal, Object payload) {
        // TODO(phase-3 M3): ADMIN only; validate sku, name, description, category, pricePaise, stock (active
        //   defaults to true); version 1; DuplicateError -> 409 sku-taken.
        throw new UnsupportedOperationException("TODO(phase-3 M3): CatalogService.create");
    }

    /** Inactive products are 404 for everyone but admins (they still exist for old orders). */
    public Product get(Principal principal, String sku) {
        // TODO(phase-3 M3): find by the upper-cased sku; missing, or INACTIVE for a non-admin -> 404 not-
        //   found 'product' (inactive products still exist for old orders).
        throw new UnsupportedOperationException("TODO(phase-3 M3): CatalogService.get");
    }

    public Product update(Principal principal, String sku, Object payload) {
        // TODO(phase-3 M3): order matters (tests check it): ADMIN, object, version, nothing-to-update (400),
        //   get (404), validate the fields that are present, save WHERE version = expected; null -> 409
        //   version-conflict with the CURRENT version (ADR-0003). Stock is not editable here.
        throw new UnsupportedOperationException("TODO(phase-3 M3): CatalogService.update");
    }

    /** Stock moves by a delta in ONE guarded statement. It does not bump the version (ADR-0003). */
    public Product adjustStock(Principal principal, String sku, Object payload) {
        // TODO(phase-3 M3): ADMIN; validate delta; get (404); ONE guarded statement (stock + delta >= 0) in
        //   the repository; null -> 409 insufficient-stock 'stock cannot go below 0 (current stock N)'. Does
        //   not bump the version.
        throw new UnsupportedOperationException("TODO(phase-3 M3): CatalogService.adjustStock");
    }

    public Page list(String q, String category, String minPrice, String maxPrice, String inStock, String limit,
                     String cursor) {
        // TODO(phase-3 M3): validate every query parameter (limit, cursor, q, category, minPricePaise,
        //   maxPricePaise, inStock) into a ProductQuery and call search().
        throw new UnsupportedOperationException("TODO(phase-3 M3): CatalogService.list");
    }

    public Page search(ProductQuery query) {
        // TODO(phase-3 M3): min > max -> 400; ask the repository for limit + 1 rows (active only, id >
        //   cursor, ordered by id); an extra row means nextCursor = encode_cursor(last row SHOWN).
        throw new UnsupportedOperationException("TODO(phase-3 M3): CatalogService.search");
    }

    public List<CategoryCount> categories() {
        // TODO(phase-3 M3): category counts of active products, sorted by name (straight from the
        //   repository).
        throw new UnsupportedOperationException("TODO(phase-3 M3): CatalogService.categories");
    }
}
