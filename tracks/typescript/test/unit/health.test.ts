// Works from day one: the starter already serves /health (no business logic needed).
import { describe, expect, it } from "vitest";
import { InMemoryStore } from "../../src/repository.js";
import { makeApp } from "../helpers.js";

describe("health", () => {
  it("reports the database", async () => {
    const store = new InMemoryStore();
    const app = await makeApp(store);
    expect((await app.inject({ method: "GET", url: "/health" })).json()).toEqual({
      status: "UP",
      checks: { database: "UP" },
    });
    store.healthy = false;
    const r = await app.inject({ method: "GET", url: "/health" });
    expect(r.statusCode).toBe(503);
    expect(r.json()).toEqual({ status: "DOWN", checks: { database: "DOWN" } });
  });

  it("answers unknown routes with problem+json", async () => {
    const app = await makeApp(new InMemoryStore());
    const r = await app.inject({ method: "GET", url: "/api/v1/nope" });
    expect(r.statusCode).toBe(404);
    expect(r.headers["content-type"]).toMatch(/^application\/problem\+json/);
    expect(r.json()).toEqual({
      type: "https://shopflow.example/problems/not-found",
      title: "Not found",
      status: 404,
      detail: "route not found",
    });
  });
});
