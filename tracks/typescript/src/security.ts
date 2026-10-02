// Passwords (bcrypt), access tokens (JWT, HS256) and webhook signatures (HMAC-SHA256).
import { randomBytes } from "node:crypto";
import bcrypt from "bcryptjs";
import type { Clock } from "./clock.js";
import { type Principal, type User } from "./models.js";

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
    // TODO(phase-3 M2): bcrypt with the configured cost (4 in tests, 10 in production).
    throw new Error("TODO(phase-3 M2): PasswordHasher.hash");
  }

  async verify(password: string, passwordHash: string | null): Promise<boolean> {
    // TODO(phase-3 M2): bcrypt check; when password_hash is null (unknown e-mail) verify against the dummy
    //   hash anyway and return false: same timing either way. A malformed hash -> false, never an exception.
    throw new Error("TODO(phase-3 M2): PasswordHasher.verify");
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
    // TODO(phase-3 M2): HS256 JWT with sub (user id as a string), role, iss=ISSUER, iat and exp = now +
    //   ACCESS_TOKEN_TTL (1 hour), using the injected clock.
    throw new Error("TODO(phase-3 M2): TokenService.issueAccessToken");
  }

  async verifyAccessToken(token: string): Promise<Principal> {
    // TODO(phase-3 M2): pin the algorithm to HS256 (reject alg=none / RS256), check the issuer, check exp
    //   against the injected CLOCK, sub must be ASCII digits and role a known role. Anything wrong -> 401
    //   unauthorized 'invalid or expired token'.
    throw new Error("TODO(phase-3 M2): TokenService.verifyAccessToken");
  }
}

/** The value of the X-Signature header: 'sha256=' + hex HMAC-SHA256 of the RAW request body. */
export function signWebhook(secret: string, body: Buffer | string): string {
  // TODO(phase-3 M2): the X-Signature value: 'sha256=' + hex HMAC-SHA256 of the RAW request body with the
  //   webhook secret.
  throw new Error("TODO(phase-3 M2): signWebhook");
}

/** Constant-time comparison: a byte-by-byte === leaks how many leading characters were right. */
export function verifyWebhook(secret: string, body: Buffer | string, header: string | undefined): boolean {
  // TODO(phase-3 M2): missing header or no 'sha256=' prefix -> false; otherwise compare with sign() in
  //   CONSTANT time (never ==).
  throw new Error("TODO(phase-3 M2): verifyWebhook");
}

export const newPaymentId = () => "pay_" + randomBytes(12).toString("hex");
