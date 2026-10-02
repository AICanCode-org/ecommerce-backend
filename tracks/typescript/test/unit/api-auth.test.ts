// M2: the account endpoints over HTTP.
import { beforeEach, expect, it } from "vitest";
import { Api } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("api-auth");

describe("account endpoints", () => {
  let api: Api;
  beforeEach(async () => {
    api = await Api.create();
  });

  it("returns 201 and the public user on register", async () => {
    const r = await api.request("POST", "/api/v1/auth/register", {
      email: "asha@shopflow.test",
      password: "password-123",
      displayName: "Asha",
    });
    expect(r.statusCode).toBe(201);
    expect(r.json()).toEqual({
      id: r.json().id,
      email: "asha@shopflow.test",
      displayName: "Asha",
      role: "CUSTOMER",
      createdAt: "2026-10-02T09:30:00.000Z",
    });
    expect(r.body).not.toContain("passwordHash");
    expect(r.body).not.toContain("password-123");
  });

  it("logs in and answers /me", async () => {
    const session = (await api.customer("Asha")) as unknown as Record<string, unknown>;
    expect(session.tokenType).toBe("Bearer");
    expect(session.expiresIn).toBe(3600);
    expect(Object.keys(session).sort()).toEqual(["accessToken", "expiresIn", "tokenType", "user"]);
    const me = await api.request("GET", "/api/v1/me", undefined, session as never);
    expect(me.statusCode).toBe(200);
    expect(me.json().displayName).toBe("Asha");
  });

  it("needs a valid token for /me", async () => {
    const r = await api.request("GET", "/api/v1/me");
    expect(r.statusCode).toBe(401);
    expect(r.headers["www-authenticate"]).toBe("Bearer");
    expect(r.headers["content-type"]).toMatch(/^application\/problem\+json/);
    expect((await api.request("GET", "/api/v1/me", undefined, "Bearer nope")).statusCode).toBe(401);
  });

  it("answers 401 invalid-credentials for a wrong password", async () => {
    await api.customer();
    const r = await api.request("POST", "/api/v1/auth/login", {
      email: "user1@shopflow.test",
      password: "wrong-password",
    });
    expect(r.statusCode).toBe(401);
    expect(r.json().type.endsWith("/invalid-credentials")).toBe(true);
  });

  it("answers bad JSON with a 400 problem", async () => {
    const r = await api.app.inject({
      method: "POST",
      url: "/api/v1/auth/register",
      payload: "{not json",
      headers: { "content-type": "application/json" },
    });
    expect(r.statusCode).toBe(400);
    expect(r.json().detail).toBe("request body must be a JSON object");
    expect((await api.request("POST", "/api/v1/auth/login", ["a"])).statusCode).toBe(400);
  });

  it("answers 409 for a duplicate email", async () => {
    await api.customer();
    const r = await api.request("POST", "/api/v1/auth/register", {
      email: "USER1@shopflow.test",
      password: "password-123",
      displayName: "X",
    });
    expect(r.statusCode).toBe(409);
    expect(r.json().type.endsWith("/email-taken")).toBe(true);
  });

  it("bootstraps the admin from the environment", async () => {
    const me = (await api.request("GET", "/api/v1/me", undefined, await api.admin())).json();
    expect([me.role, me.email]).toEqual(["ADMIN", "admin@shopflow.test"]);
  });
});
