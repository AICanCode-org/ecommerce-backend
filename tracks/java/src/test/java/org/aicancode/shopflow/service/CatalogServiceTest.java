package org.aicancode.shopflow.service;

import static org.aicancode.shopflow.support.Problems.codeOf;
import static org.aicancode.shopflow.support.Problems.message;
import static org.aicancode.shopflow.support.Problems.problem;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.aicancode.shopflow.domain.Models.CategoryCount;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.domain.Models.Product;
import org.aicancode.shopflow.support.InMemoryStore;
import org.aicancode.shopflow.support.MutableClock;
import org.junit.jupiter.api.Test;

/** M3: CatalogService - products, optimistic locking, stock, search and keyset pagination. */
class CatalogServiceTest {

    private static final Principal ADMIN = new Principal(1, "ADMIN");
    private static final Principal CUSTOMER = new Principal(2, "CUSTOMER");
    private static final Map<String, Object> KETTLE = Map.of("sku", "kit-001", "name", "Steel Kettle", "category",
            "Kitchen", "pricePaise", 129_900, "stock", 3);
    private final MutableClock clock = new MutableClock();
    private final CatalogService svc = new CatalogService(new InMemoryStore(), clock);

    private static Map<String, Object> kettle(String key, Object value) {
        Map<String, Object> m = new HashMap<>(KETTLE);
        m.put(key, value);
        return m;
    }

    private void seed() {
        Object[][] rows = {
            {"SHOE-1", "Trail Running Shoes", "shoes", 249_900, 5, "Grippy soles for wet trails."},
            {"SHOE-2", "Canvas Sneakers", "shoes", 99_900, 0, "Everyday white sneakers."},
            {"KIT-1", "Steel Kettle", "kitchen", 129_900, 2, "Boils water fast."},
            {"KIT-2", "Cast Iron Pan", "kitchen", 189_900, 1, "Pre-seasoned pan."},
            {"BAG-1", "Laptop Backpack", "bags", 179_900, 9, "Water resistant, fits 15 inch laptops."},
        };
        for (Object[] r : rows) {
            svc.create(ADMIN, Map.of("sku", r[0], "name", r[1], "category", r[2], "pricePaise", r[3], "stock", r[4],
                    "description", r[5]));
        }
        svc.create(ADMIN, Map.of("sku", "OLD-1", "name", "Old Shoes", "category", "shoes", "pricePaise", 100,
                "active", false));
    }

    private List<String> skus(String q, String category, String min, String max, String inStock) {
        return svc.list(q, category, min, max, inStock, null, null).items().stream().map(Product::sku).toList();
    }

    @Test
    void normalisesAndDefaultsOnCreate() {
        Product p = svc.create(ADMIN, KETTLE);
        assertThat(List.of(p.sku(), p.category(), p.description(), p.active(), p.version()))
                .containsExactly("KIT-001", "kitchen", "", true, 1);
        assertThat(p.createdAt()).isEqualTo(p.updatedAt());
    }

    @Test
    void letsOnlyAdminsCreateAndSkusAreUnique() {
        assertThat(problem(() -> svc.create(CUSTOMER, KETTLE)).subList(0, 2)).containsExactly(403, "forbidden");
        svc.create(ADMIN, KETTLE);
        assertThat(problem(() -> svc.create(ADMIN, kettle("sku", "KIT-001"))))
                .containsExactly(409, "sku-taken", "a product with this SKU exists");
    }

    @Test
    void validatesMoneyAsIntegerPaise() {
        assertThat(codeOf(() -> svc.create(ADMIN, kettle("pricePaise", 1299.5)))).isEqualTo(400);
        assertThat(message(() -> svc.create(ADMIN, kettle("active", "yes")))).isEqualTo("active must be true or false");
    }

    @Test
    void hidesInactiveProductsFromEveryoneButAdmins() {
        svc.create(ADMIN, kettle("active", false));
        assertThat(svc.get(ADMIN, "kit-001").sku()).isEqualTo("KIT-001");
        assertThat(problem(() -> svc.get(CUSTOMER, "KIT-001"))).containsExactly(404, "not-found", "product not found");
        assertThat(codeOf(() -> svc.get(null, "KIT-001"))).isEqualTo(404);
        assertThat(codeOf(() -> svc.get(null, "NOPE-1"))).isEqualTo(404);
    }

    @Test
    void usesOptimisticLockingForUpdates() {
        svc.create(ADMIN, KETTLE);
        clock.advance(Duration.ofMinutes(5));
        Product p = svc.update(ADMIN, "KIT-001", Map.of("version", 1, "pricePaise", 119_900, "name", " Steel Kettle 1.5L "));
        assertThat(List.of(p.pricePaise(), p.name(), p.version(), p.stock()))
                .containsExactly(119_900L, "Steel Kettle 1.5L", 2, 3);
        assertThat(p.updatedAt()).isAfter(p.createdAt());
        assertThat(problem(() -> svc.update(ADMIN, "KIT-001", Map.of("version", 1, "pricePaise", 1))))
                .containsExactly(409, "version-conflict", "the product was changed by someone else (current version 2)");
    }

    @Test
    void checksUpdatesInAFixedOrder() {
        svc.create(ADMIN, KETTLE);
        assertThat(codeOf(() -> svc.update(CUSTOMER, "KIT-001", Map.of("version", 1, "name", "x")))).isEqualTo(403);
        assertThat(message(() -> svc.update(ADMIN, "KIT-001", Map.of("name", "x"))))
                .isEqualTo("version must be a positive integer");
        assertThat(message(() -> svc.update(ADMIN, "KIT-001", Map.of("version", 1, "stock", 5))))
                .isEqualTo("nothing to update: send at least one of name, description, category, pricePaise, active");
        assertThat(codeOf(() -> svc.update(ADMIN, "NOPE-1", Map.of("version", 1, "name", "x")))).isEqualTo(404);
        assertThat(codeOf(() -> svc.update(ADMIN, "KIT-001", Map.of("version", 1, "name", "")))).isEqualTo(400);
    }

    @Test
    void movesStockByADeltaAndNeverBelowZero() {
        svc.create(ADMIN, KETTLE);
        assertThat(svc.adjustStock(ADMIN, "KIT-001", Map.of("delta", 7)).stock()).isEqualTo(10);
        Product p = svc.adjustStock(ADMIN, "KIT-001", Map.of("delta", -10));
        assertThat(List.of(p.stock(), p.version())).containsExactly(0, 1); // stock does not bump the version (ADR-0003)
        assertThat(problem(() -> svc.adjustStock(ADMIN, "KIT-001", Map.of("delta", -1))))
                .containsExactly(409, "insufficient-stock", "stock cannot go below 0 (current stock 0)");
        assertThat(codeOf(() -> svc.adjustStock(CUSTOMER, "KIT-001", Map.of("delta", 1)))).isEqualTo(403);
        assertThat(codeOf(() -> svc.adjustStock(ADMIN, "KIT-001", Map.of("delta", 0)))).isEqualTo(400);
    }

    @Test
    void filtersTheList() {
        seed();
        assertThat(skus(null, null, null, null, null)).containsExactly("SHOE-1", "SHOE-2", "KIT-1", "KIT-2", "BAG-1");
        assertThat(skus(null, "Shoes", null, null, null)).containsExactly("SHOE-1", "SHOE-2");
        assertThat(skus(null, null, "130000", null, null)).containsExactly("SHOE-1", "KIT-2", "BAG-1");
        assertThat(skus(null, null, null, "129900", null)).containsExactly("SHOE-2", "KIT-1");
        assertThat(skus(null, "shoes", null, null, "true")).containsExactly("SHOE-1");
        assertThat(skus(null, null, null, null, "false")).containsExactly("SHOE-2");
        assertThat(skus("water", null, null, null, null)).containsExactly("KIT-1", "BAG-1");
        assertThat(skus("laptop backpack", null, null, null, null)).containsExactly("BAG-1");
        assertThat(skus("steel kettle", null, null, null, null)).containsExactly("KIT-1");
    }

    @Test
    void rejectsAnImpossiblePriceRange() {
        assertThat(message(() -> svc.list(null, null, "500", "100", null, null, null)))
                .isEqualTo("minPricePaise must not be greater than maxPricePaise");
    }

    @Test
    void walksEveryProductOnceWithKeysetPagination() {
        seed();
        CatalogService.Page page1 = svc.list(null, null, null, null, null, "2", null);
        assertThat(page1.items().stream().map(Product::sku)).containsExactly("SHOE-1", "SHOE-2");
        assertThat(page1.nextCursor()).isNotBlank();
        CatalogService.Page page2 = svc.list(null, null, null, null, null, "2", page1.nextCursor());
        assertThat(page2.items().stream().map(Product::sku)).containsExactly("KIT-1", "KIT-2");
        CatalogService.Page page3 = svc.list(null, null, null, null, null, "2", page2.nextCursor());
        assertThat(page3.items().stream().map(Product::sku)).containsExactly("BAG-1");
        assertThat(page3.nextCursor()).isNull();
    }

    @Test
    void countsActiveProductsPerCategory() {
        seed();
        assertThat(svc.categories()).containsExactly(new CategoryCount("bags", 1), new CategoryCount("kitchen", 2),
                new CategoryCount("shoes", 2));
    }
}
