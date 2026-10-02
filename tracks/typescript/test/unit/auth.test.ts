// M2: AuthService (framework-free).
import { beforeEach, expect, it } from "vitest";
import { AuthService, requireRole } from "../../src/auth.js";
import { ApiError } from "../../src/errors.js";
import { InMemoryStore } from "../../src/repository.js";
import { PasswordHasher, TokenService } from "../../src/security.js";
import { FakeClock, statusOf, TEST_SECRET } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("auth");
const REG = { email: "Asha@ShopFlow.test", password: "password-123", displayName: " Asha " };

describe("auth service", () => {
  let store: InMemoryStore;
  let svc: AuthService;
  beforeEach(() => {
    store = new InMemoryStore();
    const clock = new FakeClock();
    svc = new AuthService(store, new PasswordHasher(4), new TokenService(TEST_SECRET, clock.fn), clock.fn);
  });

  it("registers a customer and ignores a requested role", async () => {
    const user = await svc.register({ ...REG, role: "ADMIN" });
    expect([user.email, user.displayName, user.role]).toEqual(["asha@shopflow.test", "Asha", "CUSTOMER"]);
    expect(store.users.get(user.id)!.passwordHash.startsWith("$2")).toBe(true);
    expect(store.users.get(user.id)!.passwordHash).not.toContain("password-123");
  });

  it("answers 409 for the same email twice, even with different case", async () => {
    await svc.register(REG);
    expect(await statusOf(svc.register({ ...REG, email: "ASHA@shopflow.test" }))).toEqual([
      409,
      "email-taken",
    ]);
  });

  it("validates the registration", async () => {
    expect(await statusOf(svc.register({ email: "x", password: "password-123", displayName: "A" }))).toEqual([
      400,
      "validation-error",
    ]);
    expect(await statusOf(svc.register("not an object"))).toEqual([400, "validation-error"]);
  });

  it("returns a token for the user on login", async () => {
    await svc.register(REG);
    const result = await svc.login({ email: " ASHA@shopflow.test ", password: "password-123" });
    expect(result.user.email).toBe("asha@shopflow.test");
    expect(result.accessToken.split(".")).toHaveLength(3);
  });

  it("makes every login failure look the same", async () => {
    await svc.register(REG);
    const wrong = await statusOf(svc.login({ email: "asha@shopflow.test", password: "wrong-password" }));
    const unknown = await statusOf(svc.login({ email: "nobody@shopflow.test", password: "password-123" }));
    const tooLong = await statusOf(svc.login({ email: "asha@shopflow.test", password: "x".repeat(73) }));
    expect([wrong, unknown, tooLong]).toEqual(Array(3).fill([401, "invalid-credentials"]));
    expect(await statusOf(svc.login({ email: "asha@shopflow.test" }))).toEqual([400, "validation-error"]);
  });

  it("authenticates bearer tokens, optionally", async () => {
    await svc.register(REG);
    const token = (await svc.login({ email: "asha@shopflow.test", password: "password-123" })).accessToken;
    expect((await svc.authenticate(`Bearer ${token}`)).role).toBe("CUSTOMER");
    expect((await svc.authenticate(`bearer   ${token}`)).role).toBe("CUSTOMER");
    expect(await svc.optional(undefined)).toBeNull();
    for (const bad of ["Basic abc", "Bearer ", "Bearer x.y.z", token]) {
      expect(await statusOf(svc.optional(bad))).toEqual([401, "unauthorized"]);
    }
    expect(await statusOf(svc.authenticate(undefined))).toEqual([401, "unauthorized"]);
  });

  it("answers 401 for a deleted account", async () => {
    const user = await svc.register(REG);
    expect((await svc.me({ userId: user.id, role: "CUSTOMER" })).id).toBe(user.id);
    store.users.delete(user.id);
    expect(await statusOf(svc.me({ userId: user.id, role: "CUSTOMER" }))).toEqual([401, "unauthorized"]);
  });

  it("creates or promotes the bootstrap admin", async () => {
    const admin = await svc.ensureAdmin("Boss@ShopFlow.test", "admin-password");
    expect([admin.role, admin.email]).toEqual(["ADMIN", "boss@shopflow.test"]);
    expect((await svc.ensureAdmin("boss@shopflow.test", "ignored-now")).id).toBe(admin.id);
    const user = await svc.register(REG);
    expect((await svc.ensureAdmin("asha@shopflow.test", "whatever-123")).role).toBe("ADMIN");
    expect(store.users.get(user.id)!.role).toBe("ADMIN");
  });

  it("requires a role", () => {
    requireRole({ userId: 1, role: "ADMIN" }, "ADMIN");
    try {
      requireRole({ userId: 1, role: "CUSTOMER" }, "ADMIN");
      throw new Error("expected 403");
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
      expect([(err as ApiError).status, (err as ApiError).detail]).toEqual([403, "requires role ADMIN"]);
    }
  });
});
