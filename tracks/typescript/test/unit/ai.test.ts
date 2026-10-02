// M6: AI features - deterministic offline fallbacks, strict validation of LLM answers.
import { expect, it } from "vitest";
import {
  DescriptionService,
  LlmClient,
  LlmParser,
  LlmWriter,
  matchCategory,
  parseFilters,
  RuleParser,
  SearchAssistService,
  TemplateWriter,
  type Chat,
} from "../../src/ai.js";
import type { Product, SearchFilters } from "../../src/models.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("ai");
const CATS = ["bags", "kitchen", "shoes"];
const NOW = new Date("2026-10-02T00:00:00Z");
const quiet = () => {};

const product = (fields: Partial<Product> = {}): Product => ({
  id: 1,
  sku: "SHOE-1",
  name: "Trail Running Shoes",
  description: "Light and grippy. Made for wet trails.",
  category: "shoes",
  pricePaise: 249_900,
  stock: 5,
  active: true,
  version: 1,
  createdAt: NOW,
  updatedAt: NOW,
  ...fields,
});

const filters = (
  terms: string[],
  category: string | null,
  minPricePaise: number | null,
  maxPricePaise: number | null,
  inStock: boolean | null,
): SearchFilters => ({ terms, category, minPricePaise, maxPricePaise, inStock });

class FakeChat implements Chat {
  calls: [string, string][] = [];
  constructor(private readonly answer: string | Error) {}
  async chat(system: string, user: string) {
    this.calls.push([system, user]);
    if (this.answer instanceof Error) throw this.answer;
    return this.answer;
  }
}

const parse = (q: string) => new RuleParser().parse(q, CATS);

describe("search assist: rules", () => {
  it("extracts price, category and stock", async () => {
    expect(await parse("red running shoes under ₹3,000 in stock")).toEqual(
      filters(["red", "running"], "shoes", null, 300_000, true),
    );
    expect(await parse("kitchen stuff between 500 and 1500")).toEqual(
      filters(["stuff"], "kitchen", 50_000, 150_000, null),
    );
    expect(await parse("bag 2k-1k")).toEqual(filters([], "bags", 100_000, 200_000, null));
    expect(await parse("Rs. 999 or more than 200 shoe")).toEqual(filters([], "shoes", 20_000, null, null));
  });

  it("handles many phrasings", async () => {
    expect((await parse("show me a laptop backpack below rs 2500")).maxPricePaise).toBe(250_000);
    expect((await parse("kettle < 1200")).maxPricePaise).toBe(120_000);
    expect((await parse("pan over 1000")).minPricePaise).toBe(100_000);
    expect(await parse("anything at least 300 available")).toEqual(
      filters(["anything"], null, 30_000, null, true),
    );
    expect((await parse("1000 to 2000 inr shoes")).minPricePaise).toBe(100_000);
  });

  it("caps and de-duplicates terms and skips stopwords", async () => {
    const f = await parse("the best new cotton cotton tee for men with pockets and zip and hood");
    expect(f.terms).toEqual(["cotton", "tee", "men", "pockets", "zip"]);
    expect((await parse("12345 678")).terms).toEqual([]);
  });

  it("caps prices", async () => {
    expect((await parse("under 99999999")).maxPricePaise).toBe(100_000_000);
  });

  it("matches categories with plurals", () => {
    expect(matchCategory("shoe", CATS)).toBe("shoes");
    expect(matchCategory("bag", CATS)).toBe("bags");
    expect(matchCategory("kitchens", CATS)).toBe("kitchen");
    expect(matchCategory("hat", CATS)).toBeNull();
  });
});

describe("search assist: LLM answers are untrusted input", () => {
  it("accepts a clean answer", () => {
    const raw =
      'Sure! {"terms": ["Red", "red", "running"], "category": "shoes", "minPrice": null, "maxPrice": 3000, ' +
      '"inStock": true}';
    expect(parseFilters(raw, CATS)).toEqual(filters(["red", "running"], "shoes", null, 300_000, true));
    expect(parseFilters('{"terms": [], "inStock": false}', CATS)).toEqual(
      filters([], null, null, null, null),
    );
  });

  it.each([
    "no json here",
    '["a"]',
    '{"terms": "shoes"}',
    '{"terms": ["a", "b", "c", "d", "e", "f"]}',
    '{"terms": ["DROP TABLE products;"]}',
    '{"category": "weapons"}',
    '{"maxPrice": -1}',
    '{"maxPrice": 1.5}',
    '{"maxPrice": true}',
    '{"minPrice": 500, "maxPrice": 100}',
    '{"inStock": "yes"}',
  ])("rejects anything unexpected: %s", (raw) => {
    expect(() => parseFilters(raw, CATS)).toThrow();
  });

  it("uses the LLM and falls back", async () => {
    const llm = new FakeChat('{"terms": ["sneakers"], "category": "shoes", "maxPrice": 2000}');
    let result = await new SearchAssistService(new RuleParser(), new LlmParser(llm), quiet).interpret(
      "sneakers under 2000",
      CATS,
    );
    expect([result.provider, result.fallback, result.filters.terms]).toEqual(["llm", false, ["sneakers"]]);
    expect(llm.calls[0]![0]).toContain("Allowed categories: bags, kitchen, shoes");
    expect(llm.calls[0]![1]).toBe("<query>\nsneakers under 2000\n</query>");
    const broken = new SearchAssistService(
      new RuleParser(),
      new LlmParser(new FakeChat('{"category": "weapons"}')),
      quiet,
    );
    result = await broken.interpret("sneakers under 2000", CATS);
    expect([result.provider, result.fallback, result.filters.maxPricePaise]).toEqual([
      "rules",
      true,
      200_000,
    ]);
    const offline = await new SearchAssistService(new RuleParser()).interpret("bags", CATS);
    expect([offline.provider, offline.fallback]).toEqual(["rules", false]);
  });
});

describe("description drafts", () => {
  it("is deterministic with the template", async () => {
    expect(await new TemplateWriter().write(product(), ["breathable", "vegan"])).toBe(
      "Trail Running Shoes from our shoes range. Light and grippy. Highlights: breathable, vegan. " +
        "In stock and ready to ship.",
    );
    expect(await new TemplateWriter().write(product({ description: "", stock: 0 }), [])).toBe(
      "Trail Running Shoes from our shoes range. Currently out of stock.",
    );
    const long = await new TemplateWriter().write(product({ description: "x".repeat(2000) }), []);
    expect(long).toHaveLength(600);
    expect(long.endsWith("...")).toBe(true);
  });

  it("uses the LLM and falls back", async () => {
    const llm = new FakeChat("  A light trail shoe.\n\nGrips wet rock.  ");
    let result = await new DescriptionService(new TemplateWriter(), new LlmWriter(llm), quiet).draft(
      product(),
      ["vegan"],
    );
    expect([result.provider, result.fallback, result.description]).toEqual([
      "llm",
      false,
      "A light trail shoe. Grips wet rock.",
    ]);
    expect(llm.calls[0]![1]).toContain("Keywords: vegan");
    expect(llm.calls[0]![1].startsWith("<product>")).toBe(true);
    const failing = new DescriptionService(
      new TemplateWriter(),
      new LlmWriter(new FakeChat(new Error("slow"))),
      quiet,
    );
    result = await failing.draft(product(), []);
    expect([result.provider, result.fallback]).toEqual(["template", true]);
    const empty = await new DescriptionService(
      new TemplateWriter(),
      new LlmWriter(new FakeChat("   ")),
      quiet,
    ).draft(product(), []);
    expect(empty.fallback).toBe(true);
  });
});

describe("the HTTP client (OpenAI-compatible)", () => {
  it("posts chat completions", async () => {
    const seen: { url?: string; auth?: string | null; body?: Record<string, unknown> } = {};
    const fake = (async (url: string, init: RequestInit) => {
      seen.url = url;
      seen.auth = new Headers(init.headers).get("authorization");
      seen.body = JSON.parse(init.body as string);
      return new Response(JSON.stringify({ choices: [{ message: { content: " hi " } }] }), { status: 200 });
    }) as unknown as typeof fetch;
    const client = new LlmClient("http://llm.test/v1", "tiny", "k-123", 1000, fake);
    expect(await client.chat("sys", "user", 50)).toBe("hi");
    expect([seen.url, seen.auth]).toEqual(["http://llm.test/v1/chat/completions", "Bearer k-123"]);
    expect([seen.body!.model, seen.body!.temperature, seen.body!.max_tokens]).toEqual(["tiny", 0, 50]);
  });

  it("throws on errors and empty answers", async () => {
    const answer = (status: number, body: unknown) =>
      (async () => new Response(JSON.stringify(body), { status })) as unknown as typeof fetch;
    await expect(
      new LlmClient("http://llm.test/v1", "m", null, 1000, answer(500, {})).chat("s", "u", 5),
    ).rejects.toThrow();
    const empty = answer(200, { choices: [{ message: { content: "  " } }] });
    await expect(
      new LlmClient("http://llm.test/v1", "m", null, 1000, empty).chat("s", "u", 5),
    ).rejects.toThrow();
  });
});
