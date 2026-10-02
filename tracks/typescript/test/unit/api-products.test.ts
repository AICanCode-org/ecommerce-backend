// M3: the catalog endpoints over HTTP.
import { beforeEach, expect, it } from "vitest";
import { Api } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("api-products");
const NEW = { sku: "tee-blk-m", name: "Black Tee (M)", category: "apparel", pricePaise: 59_900, stock: 4 };

describe("catalog endpoints", () => {
  let api: Api;
  beforeEach(async () => {
    api = await Api.create();
  });

  it("creates with 201, a Location and the full product", async () => {
    const r = await api.request("POST", "/api/v1/products", NEW, await api.admin());
    expect(r.statusCode).toBe(201);
    expect(r.headers.location).toBe("/api/v1/products/TEE-BLK-M");
    expect(r.json()).toEqual({
      id: r.json().id,
      sku: "TEE-BLK-M",
      name: "Black Tee (M)",
      description: "",
      category: "apparel",
      pricePaise: 59_900,
      price: "₹599.00",
      stock: 4,
      inStock: true,
      active: true,
      version: 1,
      createdAt: "2026-10-02T09:30:00.000Z",
      updatedAt: "2026-10-02T09:30:00.000Z",
    });
  });

  it("needs an admin for catalog writes", async () => {
    const r = await api.request("POST", "/api/v1/products", NEW);
    expect(r.statusCode).toBe(401);
    expect(r.headers["www-authenticate"]).toBe("Bearer");
    expect((await api.request("POST", "/api/v1/products", NEW, await api.customer())).statusCode).toBe(403);
  });

  it("serves public reads and the list", async () => {
    const p = await api.product({ sku: "TEE-1" });
    await api.product({ sku: "TEE-2", active: false });
    expect((await api.request("GET", "/api/v1/products/tee-1")).json().sku).toBe("TEE-1");
    expect((await api.request("GET", "/api/v1/products/TEE-2")).statusCode).toBe(404);
    expect(
      (await api.request("GET", "/api/v1/products/TEE-2", undefined, await api.admin())).statusCode,
    ).toBe(200);
    expect((await api.request("GET", "/api/v1/products")).json()).toEqual({
      items: [
        {
          sku: "TEE-1",
          name: p.name,
          category: "shoes",
          pricePaise: 249_900,
          price: "₹2,499.00",
          inStock: true,
        },
      ],
      nextCursor: null,
    });
  });

  it("validates the list query parameters", async () => {
    expect((await api.request("GET", "/api/v1/products?limit=0")).statusCode).toBe(400);
    expect((await api.request("GET", "/api/v1/products?cursor=%25%25%25")).statusCode).toBe(400);
    expect((await api.request("GET", "/api/v1/products?inStock=maybe")).json().detail).toBe(
      "inStock must be true or false",
    );
    expect((await api.request("GET", "/api/v1/products?minPricePaise=9&maxPricePaise=1")).statusCode).toBe(
      400,
    );
  });

  it("patches with optimistic locking", async () => {
    const p = await api.product({ sku: "TEE-1" });
    const url = "/api/v1/products/TEE-1";
    const admin = await api.admin();
    const ok = await api.request("PATCH", url, { version: 1, pricePaise: 199_900 }, admin);
    expect(ok.statusCode).toBe(200);
    expect([ok.json().version, ok.json().price]).toEqual([2, "₹1,999.00"]);
    const stale = await api.request("PATCH", url, { version: 1, name: "Lost update" }, admin);
    expect(stale.statusCode).toBe(409);
    expect(stale.json().type.endsWith("/version-conflict")).toBe(true);
    expect((await api.request("GET", url)).json().name).toBe(p.name);
  });

  it("adjusts stock", async () => {
    await api.product({ sku: "TEE-1", stock: 1 });
    const admin = await api.admin();
    const first = await api.request("POST", "/api/v1/products/TEE-1/stock", { delta: -1 }, admin);
    expect(first.json().inStock).toBe(false);
    const r = await api.request("POST", "/api/v1/products/TEE-1/stock", { delta: -1 }, admin);
    expect(r.statusCode).toBe(409);
    expect(r.json().type.endsWith("/insufficient-stock")).toBe(true);
  });

  it("lists categories", async () => {
    await api.product({ sku: "TEE-1", category: "apparel" });
    await api.product({ sku: "TEE-2", category: "apparel" });
    expect((await api.request("GET", "/api/v1/categories")).json()).toEqual({
      items: [{ name: "apparel", products: 2 }],
    });
  });
});
