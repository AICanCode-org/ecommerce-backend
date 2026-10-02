// M1: input validation. The messages are part of the contract (all three tracks use the same ones).
import { expect, it } from "vitest";
import { ApiError } from "../../src/errors.js";
import * as v from "../../src/validation.js";
import { message } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("validation");

describe("validation", () => {
  it("requires a JSON object", () => {
    expect(v.requireObject({ a: 1 })).toEqual({ a: 1 });
    expect(message(() => v.requireObject([1]))).toBe("request body must be a JSON object");
    expect(message(() => v.requireObject(null))).toBe("request body must be a JSON object");
  });

  it("trims and lower-cases emails", () => {
    expect(v.email("  Asha@Example.COM ")).toBe("asha@example.com");
    expect(message(() => v.email(null))).toBe("email is required");
    expect(message(() => v.email("no-at-sign"))).toBe("email is invalid");
    expect(message(() => v.email("a@" + "b".repeat(250) + ".in"))).toBe("email is invalid");
  });

  it("counts password bytes, not characters", () => {
    expect(v.password(" spaces kept ")).toBe(" spaces kept ");
    expect(message(() => v.password(12345678))).toBe("password is required");
    expect(message(() => v.password("short"))).toBe("password must be at least 8 characters");
    expect(message(() => v.password("é".repeat(37)))).toBe("password must be at most 72 bytes"); // 74 bytes
    expect(v.password("é".repeat(36))).toBe("é".repeat(36));
  });

  it("checks the display name", () => {
    expect(v.displayName("  Asha ")).toBe("Asha");
    expect(message(() => v.displayName(null))).toBe("displayName is required");
    expect(message(() => v.displayName("   "))).toBe("displayName must be 1-50 characters");
    expect(message(() => v.displayName("x".repeat(51)))).toBe("displayName must be 1-50 characters");
  });

  it("upper-cases SKUs", () => {
    expect(v.sku(" tee-blk-m ")).toBe("TEE-BLK-M");
    expect(message(() => v.sku(null))).toBe("sku is required");
    for (const bad of ["AB", "-ABC", "AB_C", "A".repeat(33), "ÄBC"]) {
      expect(message(() => v.sku(bad))).toBe("sku must be 3-32 characters: A-Z, 0-9 and -");
    }
  });

  it("checks product name, description and category", () => {
    expect(v.productName("  Kettle ")).toBe("Kettle");
    expect(message(() => v.productName(""))).toBe("name must be 1-120 characters");
    expect(message(() => v.productName(5))).toBe("name is required");
    expect(v.description(null)).toBe("");
    expect(v.description("  Steel.  ")).toBe("Steel.");
    expect(message(() => v.description(5))).toBe("description must be a string");
    expect(message(() => v.description("x".repeat(2001)))).toBe(
      "description must be at most 2000 characters",
    );
    expect(v.category(" Kitchen ")).toBe("kitchen");
    expect(message(() => v.category(null))).toBe("category is required");
    expect(message(() => v.category("k"))).toBe("category must be 2-30 characters: a-z, 0-9 and -");
  });

  it("wants integers for money and stock, never booleans or floats", () => {
    expect(v.pricePaise(1)).toBe(1);
    expect(v.pricePaise(100_000_000)).toBe(100_000_000);
    for (const bad of [0, -5, 100_000_001, 99.5, true, "100", null]) {
      expect(message(() => v.pricePaise(bad))).toBe("pricePaise must be an integer between 1 and 100000000");
    }
    expect(v.stock(null)).toBe(0);
    expect(v.stock(0)).toBe(0);
    expect(v.stock(1_000_000)).toBe(1_000_000);
    for (const bad of [-1, 1_000_001, true, 1.5]) {
      expect(message(() => v.stock(bad))).toBe("stock must be an integer between 0 and 1000000");
    }
    expect(v.stockDelta(-3)).toBe(-3);
    for (const bad of [0, null, true, 1_000_001]) {
      expect(message(() => v.stockDelta(bad))).toBe(
        "delta must be a non-zero integer between -1000000 and 1000000",
      );
    }
  });

  it("checks active, version and quantity", () => {
    expect(v.active(false)).toBe(false);
    expect(message(() => v.active("yes"))).toBe("active must be true or false");
    expect(v.version(3)).toBe(3);
    for (const bad of [0, null, true, "1"]) {
      expect(message(() => v.version(bad))).toBe("version must be a positive integer");
    }
    expect(v.quantity(10)).toBe(10);
    for (const bad of [0, 11, true, 2.5]) {
      expect(message(() => v.quantity(bad))).toBe("quantity must be an integer between 1 and 10");
    }
  });

  it("normalises keywords", () => {
    expect(v.keywords(null)).toEqual([]);
    expect(v.keywords([" Breathable ", "breathable", "light  weight"])).toEqual([
      "breathable",
      "light weight",
    ]);
    const msg = "keywords must be a list of at most 5 words (1-30 characters each)";
    expect(message(() => v.keywords("x"))).toBe(msg);
    expect(message(() => v.keywords([1]))).toBe(msg);
    expect(message(() => v.keywords([""]))).toBe(msg);
    expect(message(() => v.keywords(["a", "b", "c", "d", "e", "f"]))).toBe(msg);
  });

  it("checks coupon codes and idempotency keys", () => {
    expect(v.couponCode(" save10 ")).toBe("SAVE10");
    expect(message(() => v.couponCode(null))).toBe("code is required");
    expect(message(() => v.couponCode("SAVE-10"))).toBe("code is invalid");
    expect(v.idempotencyKey("a1b2c3d4-e5f6_X")).toBe("a1b2c3d4-e5f6_X");
    expect(message(() => v.idempotencyKey(null))).toBe("Idempotency-Key header is required");
    expect(message(() => v.idempotencyKey(""))).toBe("Idempotency-Key header is required");
    for (const bad of ["short", "x".repeat(65), "has space 123"]) {
      expect(message(() => v.idempotencyKey(bad))).toBe(
        "Idempotency-Key must be 8-64 characters: A-Z, a-z, 0-9, _ and -",
      );
    }
  });

  it("parses query-string filters", () => {
    expect(v.limit(undefined)).toBe(20);
    expect(v.limit("50")).toBe(50);
    for (const bad of ["0", "51", "-1", "1e2", "٣"]) {
      expect(message(() => v.limit(bad))).toBe("limit must be an integer between 1 and 50");
    }
    expect(v.priceFilter(undefined, "minPricePaise")).toBeNull();
    expect(v.priceFilter("", "x")).toBeNull();
    expect(v.priceFilter("0", "minPricePaise")).toBe(0);
    expect(message(() => v.priceFilter("-1", "minPricePaise"))).toBe(
      "minPricePaise must be an integer between 0 and 100000000 (paise)",
    );
    expect(v.boolFilter("true", "inStock")).toBe(true);
    expect(v.boolFilter("false", "inStock")).toBe(false);
    expect(v.boolFilter(undefined, "inStock")).toBeNull();
    expect(message(() => v.boolFilter("yes", "inStock"))).toBe("inStock must be true or false");
    expect(v.searchQuery("  ")).toBeNull();
    expect(v.searchQuery(" kettle ")).toBe("kettle");
    expect(message(() => v.searchQuery("x".repeat(101)))).toBe("q must be at most 100 characters");
    expect(v.categoryFilter("Shoes")).toBe("shoes");
    expect(v.categoryFilter(undefined)).toBeNull();
    expect(message(() => v.categoryFilter("s!"))).toBe("category is invalid");
    expect(v.orderStatusFilter("PAID")).toBe("PAID");
    expect(v.orderStatusFilter("")).toBeNull();
    expect(message(() => v.orderStatusFilter("paid"))).toBe(
      "status must be one of PENDING_PAYMENT, PAID, SHIPPED, DELIVERED, CANCELLED",
    );
    expect(v.orderStatusTarget("SHIPPED")).toBe("SHIPPED");
    expect(message(() => v.orderStatusTarget("PAID"))).toBe("status must be SHIPPED or DELIVERED");
  });

  it("checks the assist query", () => {
    expect(v.assistQuery("  shoes ")).toBe("shoes");
    expect(message(() => v.assistQuery(undefined))).toBe("q is required");
    expect(message(() => v.assistQuery("   "))).toBe("q is required");
    expect(message(() => v.assistQuery("x".repeat(201)))).toBe("q must be at most 200 characters");
  });

  it("answers 404 for a path id that cannot exist", () => {
    expect(v.pathId("42", "order")).toBe(42);
    for (const bad of ["abc", "-1", "1".repeat(19), "٣"]) {
      try {
        v.pathId(bad, "order");
        throw new Error("expected 404");
      } catch (err) {
        expect(err).toBeInstanceOf(ApiError);
        expect((err as ApiError).status).toBe(404);
        expect((err as ApiError).detail).toBe("order not found");
      }
    }
  });

  it("round-trips cursors and rejects garbage", () => {
    expect(v.encodeCursor(42)).toBe("cDQy"); // base64url("p42"), no padding
    expect(v.decodeCursor(v.encodeCursor(123456))).toBe(123456);
    expect(v.decodeCursor(undefined)).toBeNull();
    expect(v.decodeCursor("")).toBeNull();
    for (const bad of ["!!!", "cA", "eDQy", "cDQyYQ", "a".repeat(41)]) {
      expect(message(() => v.decodeCursor(bad))).toBe("cursor is invalid");
    }
  });
});
