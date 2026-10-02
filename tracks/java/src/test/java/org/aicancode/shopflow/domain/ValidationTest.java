package org.aicancode.shopflow.domain;

import static org.aicancode.shopflow.support.Problems.message;
import static org.aicancode.shopflow.support.Problems.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M1: input validation. The messages are part of the contract (all three tracks use the same ones). */
class ValidationTest {

    @Test
    void requiresAJsonObject() {
        assertThat(Validation.requireObject(Map.of("a", 1))).isEqualTo(Map.of("a", 1));
        assertThat(message(() -> Validation.requireObject(List.of(1)))).isEqualTo("request body must be a JSON object");
        assertThat(message(() -> Validation.requireObject(null))).isEqualTo("request body must be a JSON object");
    }

    @Test
    void trimsAndLowerCasesEmails() {
        assertThat(Validation.email("  Asha@Example.COM ")).isEqualTo("asha@example.com");
        assertThat(message(() -> Validation.email(null))).isEqualTo("email is required");
        assertThat(message(() -> Validation.email("no-at-sign"))).isEqualTo("email is invalid");
        assertThat(message(() -> Validation.email("a@" + "b".repeat(250) + ".in"))).isEqualTo("email is invalid");
    }

    @Test
    void countsPasswordBytesNotCharacters() {
        assertThat(Validation.password(" spaces kept ")).isEqualTo(" spaces kept ");
        assertThat(message(() -> Validation.password(12345678))).isEqualTo("password is required");
        assertThat(message(() -> Validation.password("short"))).isEqualTo("password must be at least 8 characters");
        assertThat(message(() -> Validation.password("é".repeat(37)))).isEqualTo("password must be at most 72 bytes");
        assertThat(Validation.password("é".repeat(36))).isEqualTo("é".repeat(36));
    }

    @Test
    void checksTheDisplayName() {
        assertThat(Validation.displayName("  Asha ")).isEqualTo("Asha");
        assertThat(message(() -> Validation.displayName(null))).isEqualTo("displayName is required");
        assertThat(message(() -> Validation.displayName("   "))).isEqualTo("displayName must be 1-50 characters");
        assertThat(message(() -> Validation.displayName("x".repeat(51)))).isEqualTo("displayName must be 1-50 characters");
    }

    @Test
    void upperCasesSkus() {
        assertThat(Validation.sku(" tee-blk-m ")).isEqualTo("TEE-BLK-M");
        assertThat(message(() -> Validation.sku(null))).isEqualTo("sku is required");
        for (String bad : List.of("AB", "-ABC", "AB_C", "A".repeat(33), "ÄBC")) {
            assertThat(message(() -> Validation.sku(bad))).isEqualTo("sku must be 3-32 characters: A-Z, 0-9 and -");
        }
    }

    @Test
    void checksProductNameDescriptionAndCategory() {
        assertThat(Validation.productName("  Kettle ")).isEqualTo("Kettle");
        assertThat(message(() -> Validation.productName(""))).isEqualTo("name must be 1-120 characters");
        assertThat(message(() -> Validation.productName(5))).isEqualTo("name is required");
        assertThat(Validation.description(null)).isEqualTo("");
        assertThat(Validation.description("  Steel.  ")).isEqualTo("Steel.");
        assertThat(message(() -> Validation.description(5))).isEqualTo("description must be a string");
        assertThat(message(() -> Validation.description("x".repeat(2001))))
                .isEqualTo("description must be at most 2000 characters");
        assertThat(Validation.category(" Kitchen ")).isEqualTo("kitchen");
        assertThat(message(() -> Validation.category(null))).isEqualTo("category is required");
        assertThat(message(() -> Validation.category("k"))).isEqualTo("category must be 2-30 characters: a-z, 0-9 and -");
    }

    @Test
    void wantsIntegersForMoneyAndStockNeverBooleansOrFloats() {
        assertThat(Validation.pricePaise(1)).isEqualTo(1);
        assertThat(Validation.pricePaise(100_000_000)).isEqualTo(100_000_000);
        for (Object bad : Arrays.asList(0, -5, 100_000_001, 99.5, true, "100", null)) {
            assertThat(message(() -> Validation.pricePaise(bad)))
                    .isEqualTo("pricePaise must be an integer between 1 and 100000000");
        }
        assertThat(Validation.stock(null)).isZero();
        assertThat(Validation.stock(0)).isZero();
        assertThat(Validation.stock(1_000_000)).isEqualTo(1_000_000);
        for (Object bad : Arrays.asList(-1, 1_000_001, true, 1.5)) {
            assertThat(message(() -> Validation.stock(bad))).isEqualTo("stock must be an integer between 0 and 1000000");
        }
        assertThat(Validation.stockDelta(-3)).isEqualTo(-3);
        for (Object bad : Arrays.asList(0, null, true, 1_000_001)) {
            assertThat(message(() -> Validation.stockDelta(bad)))
                    .isEqualTo("delta must be a non-zero integer between -1000000 and 1000000");
        }
    }

    @Test
    void checksActiveVersionAndQuantity() {
        assertThat(Validation.active(false)).isFalse();
        assertThat(message(() -> Validation.active("yes"))).isEqualTo("active must be true or false");
        assertThat(Validation.version(3)).isEqualTo(3);
        for (Object bad : Arrays.asList(0, null, true, "1")) {
            assertThat(message(() -> Validation.version(bad))).isEqualTo("version must be a positive integer");
        }
        assertThat(Validation.quantity(10)).isEqualTo(10);
        for (Object bad : Arrays.asList(0, 11, true, 2.5)) {
            assertThat(message(() -> Validation.quantity(bad))).isEqualTo("quantity must be an integer between 1 and 10");
        }
    }

    @Test
    void normalisesKeywords() {
        assertThat(Validation.keywords(null)).isEmpty();
        assertThat(Validation.keywords(List.of(" Breathable ", "breathable", "light  weight")))
                .containsExactly("breathable", "light weight");
        String msg = "keywords must be a list of at most 5 words (1-30 characters each)";
        assertThat(message(() -> Validation.keywords("x"))).isEqualTo(msg);
        assertThat(message(() -> Validation.keywords(List.of(1)))).isEqualTo(msg);
        assertThat(message(() -> Validation.keywords(List.of("")))).isEqualTo(msg);
        assertThat(message(() -> Validation.keywords(List.of("a", "b", "c", "d", "e", "f")))).isEqualTo(msg);
    }

    @Test
    void checksCouponCodesAndIdempotencyKeys() {
        assertThat(Validation.couponCode(" save10 ")).isEqualTo("SAVE10");
        assertThat(message(() -> Validation.couponCode(null))).isEqualTo("code is required");
        assertThat(message(() -> Validation.couponCode("SAVE-10"))).isEqualTo("code is invalid");
        assertThat(Validation.idempotencyKey("a1b2c3d4-e5f6_X")).isEqualTo("a1b2c3d4-e5f6_X");
        assertThat(message(() -> Validation.idempotencyKey(null))).isEqualTo("Idempotency-Key header is required");
        assertThat(message(() -> Validation.idempotencyKey(""))).isEqualTo("Idempotency-Key header is required");
        for (String bad : List.of("short", "x".repeat(65), "has space 123")) {
            assertThat(message(() -> Validation.idempotencyKey(bad)))
                    .isEqualTo("Idempotency-Key must be 8-64 characters: A-Z, a-z, 0-9, _ and -");
        }
    }

    @Test
    void parsesQueryStringFilters() {
        assertThat(Validation.limit(null)).isEqualTo(20);
        assertThat(Validation.limit("50")).isEqualTo(50);
        for (String bad : List.of("0", "51", "-1", "1e2", "٣")) {
            assertThat(message(() -> Validation.limit(bad))).isEqualTo("limit must be an integer between 1 and 50");
        }
        assertThat(Validation.priceFilter(null, "minPricePaise")).isNull();
        assertThat(Validation.priceFilter("", "x")).isNull();
        assertThat(Validation.priceFilter("0", "minPricePaise")).isZero();
        assertThat(message(() -> Validation.priceFilter("-1", "minPricePaise")))
                .isEqualTo("minPricePaise must be an integer between 0 and 100000000 (paise)");
        assertThat(Validation.boolFilter("true", "inStock")).isTrue();
        assertThat(Validation.boolFilter("false", "inStock")).isFalse();
        assertThat(Validation.boolFilter(null, "inStock")).isNull();
        assertThat(message(() -> Validation.boolFilter("yes", "inStock"))).isEqualTo("inStock must be true or false");
        assertThat(Validation.searchQuery("  ")).isNull();
        assertThat(Validation.searchQuery(" kettle ")).isEqualTo("kettle");
        assertThat(message(() -> Validation.searchQuery("x".repeat(101)))).isEqualTo("q must be at most 100 characters");
        assertThat(Validation.categoryFilter("Shoes")).isEqualTo("shoes");
        assertThat(Validation.categoryFilter(null)).isNull();
        assertThat(message(() -> Validation.categoryFilter("s!"))).isEqualTo("category is invalid");
        assertThat(Validation.orderStatusFilter("PAID")).isEqualTo("PAID");
        assertThat(Validation.orderStatusFilter("")).isNull();
        assertThat(message(() -> Validation.orderStatusFilter("paid")))
                .isEqualTo("status must be one of PENDING_PAYMENT, PAID, SHIPPED, DELIVERED, CANCELLED");
        assertThat(Validation.orderStatusTarget("SHIPPED")).isEqualTo("SHIPPED");
        assertThat(message(() -> Validation.orderStatusTarget("PAID"))).isEqualTo("status must be SHIPPED or DELIVERED");
    }

    @Test
    void checksTheAssistQuery() {
        assertThat(Validation.assistQuery("  shoes ")).isEqualTo("shoes");
        assertThat(message(() -> Validation.assistQuery(null))).isEqualTo("q is required");
        assertThat(message(() -> Validation.assistQuery("   "))).isEqualTo("q is required");
        assertThat(message(() -> Validation.assistQuery("x".repeat(201)))).isEqualTo("q must be at most 200 characters");
    }

    @Test
    void answers404ForAPathIdThatCannotExist() {
        assertThat(Validation.pathId("42", "order")).isEqualTo(42);
        for (String bad : List.of("abc", "-1", "1".repeat(19), "٣")) {
            assertThat(statusOf(() -> Validation.pathId(bad, "order"))).containsExactly(404, "not-found");
        }
    }

    @Test
    void roundTripsCursorsAndRejectsGarbage() {
        assertThat(Validation.encodeCursor(42)).isEqualTo("cDQy"); // base64url("p42"), no padding
        assertThat(Validation.decodeCursor(Validation.encodeCursor(123456))).isEqualTo(123456L);
        assertThat(Validation.decodeCursor(null)).isNull();
        assertThat(Validation.decodeCursor("")).isNull();
        for (String bad : List.of("!!!", "cA", "eDQy", "cDQyYQ", "a".repeat(41))) {
            assertThat(message(() -> Validation.decodeCursor(bad))).isEqualTo("cursor is invalid");
        }
    }
}
