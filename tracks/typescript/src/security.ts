// Passwords (bcrypt), access tokens (JWT, HS256) and webhook signatures (HMAC-SHA256).
import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import bcrypt from "bcryptjs";
import { jwtVerify, SignJWT } from "jose";
import type { Clock } from "./clock.js";
import { unauthorized } from "./errors.js";
import { ROLES, type Principal, type Role, type User } from "./models.js";

export const ISSUER = "shopflow";
export const ACCESS_TOKEN_TTL_SECONDS = 60 * 60;
export const MIN_SECRET_BYTES = 32;
export const SIGNATURE_PREFIX = "sha256=";

/** bcrypt with a configurable cost (10 in production, 4 in tests to keep them fast). */
export class PasswordHasher {
  private readonly dummy: string;

  constructor(private readonly cost = 10) {
    if (!Number.isInteger(cost) || cost < 4 || cost > 15) throw new Error("BCRYPT_COST must be 4-15");
    // Used when the e-mail is unknown, so a login takes the same time either way.
    this.dummy = bcrypt.hashSync("not-a-real-password", cost);
  }

  hash(password: string): Promise<string> {
    return bcrypt.hash(password, this.cost);
  }

  async verify(password: string, passwordHash: string | null): Promise<boolean> {
    let ok: boolean;
    try {
      ok = await bcrypt.compare(password, passwordHash ?? this.dummy);
    } catch {
      return false;
    }
    return ok && passwordHash !== null;
  }
}

export function resolveSecret(
  configured: string | null,
  warn: (msg: string) => void = console.warn,
  name = "JWT_SECRET",
): string {
  if (configured === null) {
    warn(`${name} is not set: using a random secret for this process (dev only)`);
    return randomBytes(48).toString("base64url");
  }
  if (Buffer.byteLength(configured, "utf8") < MIN_SECRET_BYTES) {
    throw new Error(`${name} must be at least ${MIN_SECRET_BYTES} bytes`);
  }
  return configured;
}

export class TokenService {
  private readonly key: Uint8Array;

  constructor(
    secret: string,
    private readonly clock: Clock,
  ) {
    this.key = new TextEncoder().encode(secret);
  }

  issueAccessToken(user: User): Promise<string> {
    const now = Math.floor(this.clock().getTime() / 1000);
    return new SignJWT({ role: user.role })
      .setProtectedHeader({ alg: "HS256", typ: "JWT" })
      .setSubject(String(user.id))
      .setIssuer(ISSUER)
      .setIssuedAt(now)
      .setExpirationTime(now + ACCESS_TOKEN_TTL_SECONDS)
      .sign(this.key);
  }

  async verifyAccessToken(token: string): Promise<Principal> {
    let claims;
    try {
      // algorithms: ["HS256"] pins the algorithm: "alg": "none" or RS256 tokens are rejected.
      // currentDate makes jose check exp against the injected clock, so tests can travel in time.
      ({ payload: claims } = await jwtVerify(token, this.key, {
        algorithms: ["HS256"],
        issuer: ISSUER,
        currentDate: this.clock(),
        requiredClaims: ["exp", "sub"],
      }));
    } catch {
      throw unauthorized("invalid or expired token");
    }
    const { exp, sub, role } = claims;
    if (typeof exp !== "number" || exp * 1000 <= this.clock().getTime()) {
      throw unauthorized("invalid or expired token");
    }
    if (typeof sub !== "string" || !/^[0-9]+$/.test(sub) || !ROLES.includes(role as Role)) {
      throw unauthorized("invalid or expired token");
    }
    return { userId: Number(sub), role: role as Role };
  }
}

/** The value of the X-Signature header: 'sha256=' + hex HMAC-SHA256 of the RAW request body. */
export const signWebhook = (secret: string, body: Buffer | string) =>
  SIGNATURE_PREFIX + createHmac("sha256", secret).update(body).digest("hex");

/** Constant-time comparison: a byte-by-byte === leaks how many leading characters were right. */
export function verifyWebhook(secret: string, body: Buffer | string, header: string | undefined): boolean {
  if (!header || !header.startsWith(SIGNATURE_PREFIX)) return false;
  const expected = Buffer.from(signWebhook(secret, body), "utf8");
  const given = Buffer.from(header.trim(), "utf8");
  return expected.length === given.length && timingSafeEqual(expected, given);
}

export const newPaymentId = () => "pay_" + randomBytes(12).toString("hex");
