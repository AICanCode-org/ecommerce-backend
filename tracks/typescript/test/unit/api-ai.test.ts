// M6: the AI endpoints over HTTP (offline providers).
import { beforeEach, expect, it } from "vitest";
import { Api } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("api-ai");

describe("AI endpoints", () => {
  let api: Api;
  beforeEach(async () => {
    api = await Api.create();
  });

  it("drafts descriptions for admins only and never saves them", async () => {
    const p = await api.product({ sku: "SHOE-1" });
    const url = "/api/v1/products/SHOE-1/description-draft";
    const admin = await api.admin();
    const r = await api.request("POST", url, { keywords: ["Vegan"] }, admin);
    expect(r.statusCode).toBe(200);
    expect(r.json()).toEqual({
      sku: "SHOE-1",
      provider: "template",
      fallback: false,
      description:
        "Trail Running Shoes from our shoes range. Light and grippy. Highlights: vegan. In stock and ready to ship.",
      generatedAt: "2026-10-02T09:30:00.000Z",
    });
    expect((await api.request("GET", "/api/v1/products/SHOE-1")).json().description).toBe(p.description);
    expect((await api.request("POST", url, undefined, admin)).statusCode).toBe(200); // the body is optional
    expect((await api.request("POST", url, undefined, await api.customer())).statusCode).toBe(403);
    expect((await api.request("POST", url)).statusCode).toBe(401);
    expect((await api.request("POST", url, { keywords: "x" }, admin)).statusCode).toBe(400);
    expect(
      (await api.request("POST", "/api/v1/products/NOPE-1/description-draft", undefined, admin)).statusCode,
    ).toBe(404);
  });

  it("turns a sentence into catalog filters", async () => {
    await api.product({ sku: "SHOE-1", name: "Trail Running Shoes", pricePaise: 249_900 });
    await api.product({ sku: "SHOE-2", name: "Road Running Shoes", pricePaise: 349_900 });
    await api.product({ sku: "BAG-1", name: "Running Belt", category: "bags", pricePaise: 99_900 });
    const r = await api.request(
      "GET",
      "/api/v1/search/assist?q=" + encodeURIComponent("running shoes under 3000"),
    );
    expect(r.statusCode).toBe(200);
    const body = r.json();
    expect(body.query).toBe("running shoes under 3000");
    expect([body.provider, body.fallback]).toEqual(["rules", false]);
    expect(body.filters).toEqual({
      terms: ["running"],
      category: "shoes",
      minPricePaise: null,
      maxPricePaise: 300_000,
      inStock: null,
    });
    expect(body.items.map((p: { sku: string }) => p.sku)).toEqual(["SHOE-1"]);
    expect((await api.request("GET", "/api/v1/search/assist")).statusCode).toBe(400);
    expect((await api.request("GET", "/api/v1/search/assist?q=" + "x".repeat(201))).statusCode).toBe(400);
  });
});
