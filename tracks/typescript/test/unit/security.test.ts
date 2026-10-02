// M2: passwords, access tokens and webhook signatures.
import { decodeJwt, SignJWT } from "jose";
import { expect, it } from "vitest";
import { ApiError } from "../../src/errors.js";
import type { User } from "../../src/models.js";
import {
  newPaymentId,
  PasswordHasher,
  resolveSecret,
  signWebhook,
  TokenService,
  verifyWebhook,
} from "../../src/security.js";
import { FakeClock, TEST_SECRET } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("security");
const USER: User = {
  id: 7,
  email: "asha@shopflow.test",
  passwordHash: "x",
  displayName: "Asha",
  role: "CUSTOMER",
  createdAt: new Date("2026-01-01T00:00:00Z"),
};
const enc = (o: object) => Buffer.from(JSON.stringify(o)).toString("base64url");
const sign = (claims: Record<string, unknown>, secret: string) =>
  new SignJWT(claims).setProtectedHeader({ alg: "HS256" }).sign(new TextEncoder().encode(secret));

async function expect401(promise: Promise<unknown>) {
  const err = await promise.then(
    () => null,
    (e: unknown) => e,
  );
  expect(err).toBeInstanceOf(ApiError);
  expect((err as ApiError).status).toBe(401);
  expect((err as ApiError).detail).toBe("invalid or expired token");
}

describe("security", () => {
  it("salts bcrypt hashes and verifies them", async () => {
    const hasher = new PasswordHasher(4);
    const [a, b] = [await hasher.hash("password-123"), await hasher.hash("password-123")];
    expect(a).not.toBe(b);
    expect(a.startsWith("$2")).toBe(true);
    expect(await hasher.verify("password-123", a)).toBe(true);
    expect(await hasher.verify("wrong-password", a)).toBe(false);
    expect(await hasher.verify("password-123", null)).toBe(false); // unknown user: still runs bcrypt
    expect(await hasher.verify("password-123", "not-a-bcrypt-hash")).toBe(false);
  });

  it("bounds the bcrypt cost", () => {
    expect(() => new PasswordHasher(3)).toThrow();
    expect(() => new PasswordHasher(16)).toThrow();
  });

  it("wants a long enough secret", () => {
    expect(resolveSecret(TEST_SECRET)).toBe(TEST_SECRET);
    expect(resolveSecret(null, () => {}).length).toBeGreaterThanOrEqual(32);
    expect(() => resolveSecret("too-short")).toThrow();
  });

  it("round-trips an access token with the right claims", async () => {
    const tokens = new TokenService(TEST_SECRET, new FakeClock().fn);
    const token = await tokens.issueAccessToken(USER);
    const claims = decodeJwt(token);
    expect([claims.sub, claims.role, claims.iss]).toEqual(["7", "CUSTOMER", "shopflow"]);
    expect(claims.exp! - claims.iat!).toBe(3600);
    expect(await tokens.verifyAccessToken(token)).toEqual({ userId: 7, role: "CUSTOMER" });
  });

  it("rejects an expired token using the injected clock", async () => {
    const clock = new FakeClock();
    const tokens = new TokenService(TEST_SECRET, clock.fn);
    const token = await tokens.issueAccessToken(USER);
    clock.advance({ minutes: 59 });
    await tokens.verifyAccessToken(token);
    clock.advance({ minutes: 1 });
    await expect401(tokens.verifyAccessToken(token));
  });

  it.each([
    [
      "another secret",
      () => sign({ sub: "7", role: "ADMIN", iss: "shopflow", exp: 4e9 }, "another-secret-32-bytes-long-xx"),
    ],
    ["wrong issuer", () => sign({ sub: "7", role: "ADMIN", iss: "evil", exp: 4e9 }, TEST_SECRET)],
    ["unknown role", () => sign({ sub: "7", role: "ROOT", iss: "shopflow", exp: 4e9 }, TEST_SECRET)],
    ["bad subject", () => sign({ sub: "x", role: "ADMIN", iss: "shopflow", exp: 4e9 }, TEST_SECRET)],
    ["no expiry", () => sign({ sub: "7", role: "ADMIN", iss: "shopflow" }, TEST_SECRET)],
    [
      "alg none",
      async () =>
        `${enc({ alg: "none", typ: "JWT" })}.${enc({ sub: "7", role: "ADMIN", iss: "shopflow", exp: 4e9 })}.`,
    ],
    ["not a jwt", async () => "not.a.jwt"],
  ])("rejects a forged or malformed token: %s", async (_name, make) => {
    await expect401(new TokenService(TEST_SECRET, new FakeClock().fn).verifyAccessToken(await make()));
  });

  it("signs webhooks with HMAC-SHA256 of the raw body", () => {
    const body = Buffer.from('{"id":"evt_1"}');
    const signature = signWebhook("s".repeat(32), body);
    expect(signature.startsWith("sha256=")).toBe(true);
    expect(signature).toHaveLength(7 + 64);
    expect(verifyWebhook("s".repeat(32), body, signature)).toBe(true);
    expect(verifyWebhook("s".repeat(32), Buffer.concat([body, Buffer.from(" ")]), signature)).toBe(false);
    expect(verifyWebhook("t".repeat(32), body, signature)).toBe(false);
    expect(verifyWebhook("s".repeat(32), body, undefined)).toBe(false);
    expect(verifyWebhook("s".repeat(32), body, signature.slice(7))).toBe(false); // missing the prefix
  });

  it("matches the known signature vector", () => {
    // Same vector in every track: HMAC-SHA256(key="key", "The quick brown fox jumps over the lazy dog")
    expect(signWebhook("key", "The quick brown fox jumps over the lazy dog")).toBe(
      "sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
    );
  });

  it("makes random payment ids", () => {
    const [a, b] = [newPaymentId(), newPaymentId()];
    expect(a).not.toBe(b);
    expect(a.startsWith("pay_")).toBe(true);
    expect(a).toHaveLength(28);
  });
});
