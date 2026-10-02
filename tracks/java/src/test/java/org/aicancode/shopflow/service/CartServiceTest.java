package org.aicancode.shopflow.service;

import static org.aicancode.shopflow.support.Problems.codeOf;
import static org.aicancode.shopflow.support.Problems.message;
import static org.aicancode.shopflow.support.Problems.problem;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.aicancode.shopflow.domain.Models.CartView;
import org.aicancode.shopflow.domain.Models.Pricing;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.support.InMemoryStore;
import org.aicancode.shopflow.support.MutableClock;
import org.junit.jupiter.api.Test;

/** M4: CartService - quantities, stock checks, coupons and live pricing. */
class CartServiceTest {

    private static final Principal ADMIN = new Principal(1, "ADMIN");
    private static final Principal ASHA = new Principal(2, "CUSTOMER");
    private static final Principal RAVI = new Principal(3, "CUSTOMER");
    private final InMemoryStore store = new InMemoryStore();
    private final MutableClock clock = new MutableClock();
    private final CatalogService catalog = new CatalogService(store, clock);
    private final CartService svc = new CartService(store, store, clock);

    CartServiceTest() {
        catalog.create(ADMIN, Map.of("sku", "TEE-1", "name", "Tee", "category", "apparel", "pricePaise", 29_900,
                "stock", 3));
        catalog.create(ADMIN, Map.of("sku", "MUG-1", "name", "Mug", "category", "kitchen", "pricePaise", 45_000,
                "stock", 10));
    }

    private static List<Long> totals(Pricing p) {
        return List.of(p.subtotalPaise(), p.shippingPaise(), p.totalPaise());
    }

    @Test
    void startsEmpty() {
        CartView view = svc.view(ASHA);
        assertThat(view.lines()).isEmpty();
        assertThat(view.couponCode()).isNull();
        assertThat(totals(view.pricing())).containsExactly(0L, 0L, 0L);
    }

    @Test
    void setsTheQuantityItDoesNotAdd() {
        svc.setItem(ASHA, "tee-1", Map.of("quantity", 2));
        CartView view = svc.setItem(ASHA, "TEE-1", Map.of("quantity", 1));
        assertThat(view.lines()).singleElement().satisfies(l -> {
            assertThat(l.product().sku()).isEqualTo("TEE-1");
            assertThat(l.quantity()).isEqualTo(1);
        });
        assertThat(totals(view.pricing())).containsExactly(29_900L, 4_900L, 34_800L);
    }

    @Test
    void keepsOneCartPerUser() {
        svc.setItem(ASHA, "TEE-1", Map.of("quantity", 1));
        assertThat(svc.view(RAVI).lines()).isEmpty();
    }

    @Test
    void checksTheQuantityAgainstStock() {
        assertThat(problem(() -> svc.setItem(ASHA, "TEE-1", Map.of("quantity", 4))))
                .containsExactly(409, "insufficient-stock", "only 3 left of TEE-1");
        assertThat(codeOf(() -> svc.setItem(ASHA, "TEE-1", Map.of("quantity", 0)))).isEqualTo(400);
        assertThat(problem(() -> svc.setItem(ASHA, "NOPE-1", Map.of("quantity", 1))))
                .containsExactly(404, "not-found", "product not found");
    }

    @Test
    void refusesInactiveProducts() {
        catalog.update(ADMIN, "MUG-1", Map.of("version", 1, "active", false));
        assertThat(codeOf(() -> svc.setItem(ASHA, "MUG-1", Map.of("quantity", 1)))).isEqualTo(404);
    }

    @Test
    void removesIdempotently() {
        svc.setItem(ASHA, "TEE-1", Map.of("quantity", 1));
        assertThat(svc.removeItem(ASHA, "TEE-1").lines()).isEmpty();
        assertThat(svc.removeItem(ASHA, "TEE-1").lines()).isEmpty();
        assertThat(codeOf(() -> svc.removeItem(ASHA, "NOPE-1"))).isEqualTo(404);
    }

    @Test
    void holdsAtMost20Products() {
        for (int n = 0; n < 20; n++) {
            String sku = String.format("P-%03d", n);
            catalog.create(ADMIN, Map.of("sku", sku, "name", "P", "category", "misc", "pricePaise", 100, "stock", 1));
            svc.setItem(ASHA, sku, Map.of("quantity", 1));
        }
        assertThat(message(() -> svc.setItem(ASHA, "TEE-1", Map.of("quantity", 1))))
                .isEqualTo("a cart holds at most 20 different products");
        svc.setItem(ASHA, "P-000", Map.of("quantity", 1)); // changing an existing line is still fine
    }

    @Test
    void appliesCoupons() {
        svc.setItem(ASHA, "MUG-1", Map.of("quantity", 2)); // ₹900
        CartView view = svc.applyCoupon(ASHA, Map.of("code", " save10 "));
        Pricing p = view.pricing();
        assertThat(view.couponCode()).isEqualTo("SAVE10");
        assertThat(p.couponApplied()).isTrue();
        assertThat(List.of(p.subtotalPaise(), p.discountPaise(), p.shippingPaise(), p.totalPaise()))
                .containsExactly(90_000L, 9_000L, 4_900L, 85_900L);
        assertThat(problem(() -> svc.applyCoupon(ASHA, Map.of("code", "EXPIRED5"))))
                .containsExactly(404, "not-found", "coupon not found");
        assertThat(codeOf(() -> svc.applyCoupon(ASHA, Map.of("code", "NOPE99")))).isEqualTo(404);
        assertThat(svc.removeCoupon(ASHA).pricing().discountPaise()).isZero();
    }

    @Test
    void keepsACouponWhoseMinimumIsNotMetWithoutApplyingIt() {
        svc.setItem(ASHA, "TEE-1", Map.of("quantity", 1)); // ₹299 < ₹500
        CartView view = svc.applyCoupon(ASHA, Map.of("code", "FLAT100"));
        assertThat(view.couponCode()).isEqualTo("FLAT100");
        assertThat(view.pricing().couponApplied()).isFalse();
        assertThat(view.pricing().discountPaise()).isZero();
        view = svc.setItem(ASHA, "TEE-1", Map.of("quantity", 2)); // ₹598
        assertThat(view.pricing().couponApplied()).isTrue();
        assertThat(view.pricing().discountPaise()).isEqualTo(10_000);
    }

    @Test
    void keepsPricesLiveUntilCheckout() {
        svc.setItem(ASHA, "TEE-1", Map.of("quantity", 1));
        catalog.update(ADMIN, "TEE-1", Map.of("version", 1, "pricePaise", 19_900));
        assertThat(svc.view(ASHA).pricing().subtotalPaise()).isEqualTo(19_900);
    }
}
