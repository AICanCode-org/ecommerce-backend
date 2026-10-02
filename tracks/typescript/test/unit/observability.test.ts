// v1.1 (Phase 7): request IDs, /metrics and the LLM circuit breaker.
import { describe, expect, it } from "vitest";
import {
  CircuitBreaker,
  CircuitOpenError,
  DescriptionService,
  RuleParser,
  SearchAssistService,
  TemplateWriter,
} from "../../src/ai.js";
import { COUNTERS, Metrics } from "../../src/metrics.js";
import { incomingOrNewId } from "../../src/observability.js";
import { Api } from "../helpers.js";

async function metric(api: Api, name: string): Promise<number> {
  const body = (await api.request("GET", "/metrics")).body;
  const line = body.split("\n").find((l) => l.startsWith(`${name} `));
  return Number(line!.split(" ")[1]);
}

describe("request IDs", () => {
  it("echoes a safe ID and mints one when missing", async () => {
    const api = await Api.create();
    const r = await api.request("GET", "/health", undefined, undefined, { "x-request-id": "abc-123" });
    expect(r.headers["x-request-id"]).toBe("abc-123");
    const minted = (await api.request("GET", "/health")).headers["x-request-id"] as string;
    expect(minted).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("puts the ID on errors too", async () => {
    const api = await Api.create();
    const r = await api.request("GET", "/api/v1/nope", undefined, undefined, { "x-request-id": "trace-404" });
    expect([r.statusCode, r.headers["x-request-id"]]).toEqual([404, "trace-404"]);
  });

  it.each(["", "has space", "x".repeat(65), "evilé", "a;b"])("replaces the unsafe ID %j", (value) => {
    expect(incomingOrNewId(value)).not.toBe(value);
  });
});

describe("/metrics", () => {
  it("lists every counter as Prometheus text", async () => {
    const api = await Api.create();
    const r = await api.request("GET", "/metrics");
    expect(r.statusCode).toBe(200);
    expect(r.headers["content-type"]).toMatch(/^text\/plain/);
    for (const name of Object.keys(COUNTERS)) {
      expect(r.body).toContain(`# TYPE ${name} counter`);
      expect(r.body).toContain(`\n${name} 0`);
    }
  });

  it("counts the business events", async () => {
    const api = await Api.create();
    const asha = await api.customer();
    await api.customer();
    await api.request("POST", "/api/v1/auth/login", {
      email: "user1@shopflow.test",
      password: "wrong-password",
    });
    const first = await api.order(asha);
    expect((await api.checkout(asha)).statusCode).toBe(200); // replay of the same key
    const second = await api.order(asha, "checkout-key-0002");
    await api.pay(first);
    await api.pay(first); // the provider retries: duplicate
    api.clock.advance({ minutes: 16 });
    const expire = await api.request("POST", "/api/v1/admin/orders/expire", undefined, await api.admin());
    expect(expire.json()).toEqual({ expired: 1 });
    expect((await api.request("GET", `/api/v1/orders/${second.id}`, undefined, asha)).json().status).toBe(
      "CANCELLED",
    );
    expect(await metric(api, "shopflow_users_registered_total")).toBe(2);
    expect(await metric(api, "shopflow_logins_failed_total")).toBe(1);
    expect(await metric(api, "shopflow_orders_placed_total")).toBe(2);
    expect(await metric(api, "shopflow_checkout_replays_total")).toBe(1);
    expect(await metric(api, "shopflow_webhooks_duplicate_total")).toBe(1);
    expect(await metric(api, "shopflow_orders_expired_total")).toBe(1);
    expect(await metric(api, "shopflow_ai_fallback_total")).toBe(0);
  });

  it("counts fallbacks", async () => {
    const broken = {
      write: async () => Promise.reject(new Error("LLM timed out")),
      parse: async () => Promise.reject(new Error("LLM timed out")),
    };
    const quiet = () => {};
    const api = await Api.create({
      ai: {
        descriptions: new DescriptionService(new TemplateWriter(), broken, quiet),
        assist: new SearchAssistService(new RuleParser(), broken, quiet),
      },
    });
    const product = await api.product();
    const draft = await api.request(
      "POST",
      `/api/v1/products/${product.sku}/description-draft`,
      undefined,
      await api.admin(),
    );
    expect(draft.json().fallback).toBe(true);
    const assist = await api.request("GET", "/api/v1/search/assist?q=shoes%20under%203000");
    expect(assist.json().fallback).toBe(true);
    expect(await metric(api, "shopflow_ai_fallback_total")).toBe(2);
  });

  it("renders the text format", () => {
    const m = new Metrics();
    m.inc("shopflow_orders_expired_total", 3);
    expect(m.value("shopflow_orders_expired_total")).toBe(3);
    expect(m.render()).toContain("shopflow_orders_expired_total 3\n");
  });
});

describe("circuit breaker", () => {
  function setup() {
    const llm = {
      calls: 0,
      fail: true,
      async chat() {
        this.calls += 1;
        if (this.fail) throw new Error("timed out");
        return "ok";
      },
    };
    const clock = { t: 1_000_000 };
    let skipped = 0;
    const breaker = new CircuitBreaker(llm, {
      threshold: 3,
      cooldownMs: 30_000,
      now: () => clock.t,
      onShortCircuit: () => (skipped += 1),
      warn: () => {},
    });
    const call = () => breaker.chat("s", "u", 10);
    return { llm, clock, breaker, call, skipped: () => skipped };
  }

  it("opens after three failures in a row and skips calls", async () => {
    const { llm, breaker, call, skipped } = setup();
    for (let i = 0; i < 3; i++) await expect(call()).rejects.toThrow("timed out");
    expect(breaker.state).toBe("open");
    await expect(call()).rejects.toBeInstanceOf(CircuitOpenError);
    expect(llm.calls).toBe(3);
    expect(skipped()).toBe(1);
  });

  it("a success resets the failure count", async () => {
    const { llm, breaker, call } = setup();
    for (const fail of [true, true, false, true, true]) {
      llm.fail = fail;
      if (fail) await expect(call()).rejects.toThrow();
      else expect(await call()).toBe("ok");
    }
    expect(breaker.state).toBe("closed");
  });

  it("half-open: a trial success closes, a trial failure re-opens", async () => {
    const { llm, clock, breaker, call } = setup();
    for (let i = 0; i < 3; i++) await expect(call()).rejects.toThrow();
    clock.t += 30_000;
    expect(breaker.state).toBe("half-open");
    await expect(call()).rejects.toThrow("timed out");
    expect(breaker.state).toBe("open");
    expect(llm.calls).toBe(4);
    clock.t += 30_000;
    llm.fail = false;
    expect(await call()).toBe("ok");
    expect(breaker.state).toBe("closed");
  });

  it("the real wiring short-circuits an unreachable LLM", async () => {
    const metrics = new Metrics();
    const api = await Api.create({
      settings: { aiProvider: "llm", llmBaseUrl: "http://127.0.0.1:9/v1", llmTimeoutMs: 500 },
      metrics,
    });
    for (let i = 0; i < 4; i++) {
      const r = await api.request("GET", "/api/v1/search/assist?q=running%20shoes%20under%203000");
      expect(r.statusCode).toBe(200);
      expect(r.json().fallback).toBe(true);
    }
    expect(metrics.value("shopflow_llm_short_circuits_total")).toBe(1);
    expect(metrics.value("shopflow_ai_fallback_total")).toBe(4);
  });
});
