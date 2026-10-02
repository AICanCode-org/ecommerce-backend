// M3: CatalogService - products, optimistic locking, stock, search and keyset pagination.
import { beforeEach, expect, it } from "vitest";
import { CatalogService, type ListParams } from "../../src/catalog.js";
import { ApiError } from "../../src/errors.js";
import type { Principal } from "../../src/models.js";
import { InMemoryStore } from "../../src/repository.js";
import { FakeClock } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("catalog");
const ADMIN: Principal = { userId: 1, role: "ADMIN" };
const CUSTOMER: Principal = { userId: 2, role: "CUSTOMER" };
const KETTLE = { sku: "kit-001", name: "Steel Kettle", category: "Kitchen", pricePaise: 129_900, stock: 3 };

async function status(promise: Promise<unknown>): Promise<[number, string, string]> {
  try {
    await promise;
  } catch (err) {
    expect(err).toBeInstanceOf(ApiError);
    const e = err as ApiError;
    return [e.status, e.slug, e.detail ?? ""];
  }
  throw new Error("expected an ApiError");
}

describe("catalog service", () => {
  let clock: FakeClock;
  let svc: CatalogService;
  beforeEach(() => {
    clock = new FakeClock();
    svc = new CatalogService(new InMemoryStore(), clock.fn);
  });

  async function seed() {
    const rows: [string, string, string, number, number, string][] = [
      ["SHOE-1", "Trail Running Shoes", "shoes", 249_900, 5, "Grippy soles for wet trails."],
      ["SHOE-2", "Canvas Sneakers", "shoes", 99_900, 0, "Everyday white sneakers."],
      ["KIT-1", "Steel Kettle", "kitchen", 129_900, 2, "Boils water fast."],
      ["KIT-2", "Cast Iron Pan", "kitchen", 189_900, 1, "Pre-seasoned pan."],
      ["BAG-1", "Laptop Backpack", "bags", 179_900, 9, "Water resistant, fits 15 inch laptops."],
    ];
    for (const [sku, name, category, pricePaise, stock, description] of rows) {
      await svc.create(ADMIN, { sku, name, category, pricePaise, stock, description });
    }
    await svc.create(ADMIN, {
      sku: "OLD-1",
      name: "Old Shoes",
      category: "shoes",
      pricePaise: 100,
      active: false,
    });
  }
  const skus = async (params: ListParams = {}) => (await svc.list(params)).items.map((p) => p.sku);

  it("normalises and defaults on create", async () => {
    const p = await svc.create(ADMIN, KETTLE);
    expect([p.sku, p.category, p.description, p.active, p.version]).toEqual([
      "KIT-001",
      "kitchen",
      "",
      true,
      1,
    ]);
    expect(p.createdAt).toEqual(p.updatedAt);
  });

  it("lets only admins create, and SKUs are unique", async () => {
    expect((await status(svc.create(CUSTOMER, KETTLE))).slice(0, 2)).toEqual([403, "forbidden"]);
    await svc.create(ADMIN, KETTLE);
    expect(await status(svc.create(ADMIN, { ...KETTLE, sku: "KIT-001" }))).toEqual([
      409,
      "sku-taken",
      "a product with this SKU exists",
    ]);
  });

  it("validates money as integer paise", async () => {
    expect((await status(svc.create(ADMIN, { ...KETTLE, pricePaise: 1299.5 })))[0]).toBe(400);
    expect((await status(svc.create(ADMIN, { ...KETTLE, active: "yes" })))[2]).toBe(
      "active must be true or false",
    );
  });

  it("hides inactive products from everyone but admins", async () => {
    await svc.create(ADMIN, { ...KETTLE, active: false });
    expect((await svc.get(ADMIN, "kit-001")).sku).toBe("KIT-001");
    expect(await status(svc.get(CUSTOMER, "KIT-001"))).toEqual([404, "not-found", "product not found"]);
    expect((await status(svc.get(null, "KIT-001")))[0]).toBe(404);
    expect((await status(svc.get(null, "NOPE-1")))[0]).toBe(404);
  });

  it("uses optimistic locking for updates", async () => {
    await svc.create(ADMIN, KETTLE);
    clock.advance({ minutes: 5 });
    const p = await svc.update(ADMIN, "KIT-001", {
      version: 1,
      pricePaise: 119_900,
      name: " Steel Kettle 1.5L ",
    });
    expect([p.pricePaise, p.name, p.version, p.stock]).toEqual([119_900, "Steel Kettle 1.5L", 2, 3]);
    expect(p.updatedAt.getTime()).toBeGreaterThan(p.createdAt.getTime());
    expect(await status(svc.update(ADMIN, "KIT-001", { version: 1, pricePaise: 1 }))).toEqual([
      409,
      "version-conflict",
      "the product was changed by someone else (current version 2)",
    ]);
  });

  it("checks updates in a fixed order", async () => {
    await svc.create(ADMIN, KETTLE);
    expect((await status(svc.update(CUSTOMER, "KIT-001", { version: 1, name: "x" })))[0]).toBe(403);
    expect((await status(svc.update(ADMIN, "KIT-001", { name: "x" })))[2]).toBe(
      "version must be a positive integer",
    );
    expect((await status(svc.update(ADMIN, "KIT-001", { version: 1, stock: 5 })))[2]).toBe(
      "nothing to update: send at least one of name, description, category, pricePaise, active",
    );
    expect((await status(svc.update(ADMIN, "NOPE-1", { version: 1, name: "x" })))[0]).toBe(404);
    expect((await status(svc.update(ADMIN, "KIT-001", { version: 1, name: "" })))[0]).toBe(400);
  });

  it("moves stock by a delta and never below zero", async () => {
    await svc.create(ADMIN, KETTLE);
    expect((await svc.adjustStock(ADMIN, "KIT-001", { delta: 7 })).stock).toBe(10);
    const p = await svc.adjustStock(ADMIN, "KIT-001", { delta: -10 });
    expect([p.stock, p.version]).toEqual([0, 1]); // stock changes do not bump the version (ADR-0003)
    expect(await status(svc.adjustStock(ADMIN, "KIT-001", { delta: -1 }))).toEqual([
      409,
      "insufficient-stock",
      "stock cannot go below 0 (current stock 0)",
    ]);
    expect((await status(svc.adjustStock(CUSTOMER, "KIT-001", { delta: 1 })))[0]).toBe(403);
    expect((await status(svc.adjustStock(ADMIN, "KIT-001", { delta: 0 })))[0]).toBe(400);
  });

  it("filters the list", async () => {
    await seed();
    expect(await skus()).toEqual(["SHOE-1", "SHOE-2", "KIT-1", "KIT-2", "BAG-1"]); // active only, id order
    expect(await skus({ category: "Shoes" })).toEqual(["SHOE-1", "SHOE-2"]);
    expect(await skus({ minPrice: "130000" })).toEqual(["SHOE-1", "KIT-2", "BAG-1"]);
    expect(await skus({ maxPrice: "129900" })).toEqual(["SHOE-2", "KIT-1"]);
    expect(await skus({ inStock: "true", category: "shoes" })).toEqual(["SHOE-1"]);
    expect(await skus({ inStock: "false" })).toEqual(["SHOE-2"]);
    expect(await skus({ q: "water" })).toEqual(["KIT-1", "BAG-1"]);
    expect(await skus({ q: "laptop backpack" })).toEqual(["BAG-1"]);
    expect(await skus({ q: "steel kettle" })).toEqual(["KIT-1"]);
  });

  it("rejects an impossible price range", async () => {
    expect((await status(svc.list({ minPrice: "500", maxPrice: "100" })))[2]).toBe(
      "minPricePaise must not be greater than maxPricePaise",
    );
  });

  it("walks every product once with keyset pagination", async () => {
    await seed();
    const page1 = await svc.list({ limit: "2" });
    expect(page1.items.map((p) => p.sku)).toEqual(["SHOE-1", "SHOE-2"]);
    expect(page1.nextCursor).toBeTruthy();
    const page2 = await svc.list({ limit: "2", cursor: page1.nextCursor! });
    expect(page2.items.map((p) => p.sku)).toEqual(["KIT-1", "KIT-2"]);
    const page3 = await svc.list({ limit: "2", cursor: page2.nextCursor! });
    expect(page3.items.map((p) => p.sku)).toEqual(["BAG-1"]);
    expect(page3.nextCursor).toBeNull();
  });

  it("counts active products per category", async () => {
    await seed();
    expect(await svc.categories()).toEqual([
      { name: "bags", products: 1 },
      { name: "kitchen", products: 2 },
      { name: "shoes", products: 2 },
    ]);
  });
});
