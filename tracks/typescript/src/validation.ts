// Input validation. Every function returns the clean value or throws a 400 validation-error.
// The messages are part of the contract: the tests (and the other two tracks) expect them exactly.
import { notFound, validationError } from "./errors.js";
import { ORDER_STATUSES, type OrderStatus } from "./models.js";
import { codePoints, collapse } from "./text.js";

const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const SKU_RE = /^[A-Z0-9][A-Z0-9-]{2,31}$/;
const CATEGORY_RE = /^[a-z0-9][a-z0-9-]{1,29}$/;
const COUPON_RE = /^[A-Z0-9]{3,20}$/;
export const IDEMPOTENCY_KEY_RE = /^[A-Za-z0-9_-]{8,64}$/;
const KEYWORD_RE = /^[a-z0-9][a-z0-9 -]{0,29}$/;
const CURSOR_RE = /^[A-Za-z0-9_-]{1,40}$/;
const DIGITS_RE = /^[0-9]+$/;
export const MAX_PRICE_PAISE = 100_000_000; // ₹10 lakh
export const MAX_STOCK = 1_000_000;
export const MAX_QUANTITY = 10;

export type Json = Record<string, unknown>;
const isInt = (v: unknown): v is number => typeof v === "number" && Number.isSafeInteger(v);

export function requireObject(payload: unknown): Json {
  if (typeof payload !== "object" || payload === null || Array.isArray(payload)) {
    throw validationError("request body must be a JSON object");
  }
  return payload as Json;
}

// ---- accounts ----
export function email(value: unknown): string {
  if (typeof value !== "string") throw validationError("email is required");
  const clean = value.trim().toLowerCase();
  if (clean.length > 254 || !EMAIL_RE.test(clean)) throw validationError("email is invalid");
  return clean;
}

export function password(value: unknown): string {
  if (typeof value !== "string") throw validationError("password is required");
  if (codePoints(value) < 8) throw validationError("password must be at least 8 characters");
  if (Buffer.byteLength(value, "utf8") > 72) throw validationError("password must be at most 72 bytes");
  return value;
}

export function displayName(value: unknown): string {
  if (typeof value !== "string") throw validationError("displayName is required");
  const clean = value.trim();
  const n = codePoints(clean);
  if (n < 1 || n > 50) throw validationError("displayName must be 1-50 characters");
  return clean;
}

// ---- catalog ----
export function sku(value: unknown): string {
  if (typeof value !== "string") throw validationError("sku is required");
  const clean = value.trim().toUpperCase();
  if (!SKU_RE.test(clean)) throw validationError("sku must be 3-32 characters: A-Z, 0-9 and -");
  return clean;
}

export function productName(value: unknown): string {
  if (typeof value !== "string") throw validationError("name is required");
  const clean = value.trim();
  const n = codePoints(clean);
  if (n < 1 || n > 120) throw validationError("name must be 1-120 characters");
  return clean;
}

export function description(value: unknown): string {
  if (value === null || value === undefined) return "";
  if (typeof value !== "string") throw validationError("description must be a string");
  const clean = value.trim();
  if (codePoints(clean) > 2000) throw validationError("description must be at most 2000 characters");
  return clean;
}

export function category(value: unknown): string {
  if (typeof value !== "string") throw validationError("category is required");
  const clean = value.trim().toLowerCase();
  if (!CATEGORY_RE.test(clean)) throw validationError("category must be 2-30 characters: a-z, 0-9 and -");
  return clean;
}

export function pricePaise(value: unknown): number {
  if (!isInt(value) || value < 1 || value > MAX_PRICE_PAISE) {
    throw validationError(`pricePaise must be an integer between 1 and ${MAX_PRICE_PAISE}`);
  }
  return value;
}

export function stock(value: unknown): number {
  if (value === null || value === undefined) return 0;
  if (!isInt(value) || value < 0 || value > MAX_STOCK) {
    throw validationError(`stock must be an integer between 0 and ${MAX_STOCK}`);
  }
  return value;
}

export function stockDelta(value: unknown): number {
  if (!isInt(value) || value === 0 || Math.abs(value) > MAX_STOCK) {
    throw validationError(`delta must be a non-zero integer between -${MAX_STOCK} and ${MAX_STOCK}`);
  }
  return value;
}

export function active(value: unknown): boolean {
  if (typeof value !== "boolean") throw validationError("active must be true or false");
  return value;
}

export function version(value: unknown): number {
  if (!isInt(value) || value < 1) throw validationError("version must be a positive integer");
  return value;
}

/** Optional hints for an AI description draft: at most 5, each 1-30 characters, de-duplicated. */
export function keywords(value: unknown): string[] {
  const message = "keywords must be a list of at most 5 words (1-30 characters each)";
  if (value === null || value === undefined) return [];
  if (!Array.isArray(value)) throw validationError(message);
  const out: string[] = [];
  for (const item of value) {
    if (typeof item !== "string") throw validationError(message);
    const clean = collapse(item.toLowerCase());
    if (!KEYWORD_RE.test(clean)) throw validationError(message);
    if (!out.includes(clean)) out.push(clean);
  }
  if (out.length > 5) throw validationError(message);
  return out;
}

// ---- cart + checkout ----
export function quantity(value: unknown): number {
  if (!isInt(value) || value < 1 || value > MAX_QUANTITY) {
    throw validationError(`quantity must be an integer between 1 and ${MAX_QUANTITY}`);
  }
  return value;
}

export function couponCode(value: unknown): string {
  if (typeof value !== "string") throw validationError("code is required");
  const clean = value.trim().toUpperCase();
  if (!COUPON_RE.test(clean)) throw validationError("code is invalid");
  return clean;
}

export function idempotencyKey(value: string | undefined | null): string {
  if (!value) throw validationError("Idempotency-Key header is required");
  if (!IDEMPOTENCY_KEY_RE.test(value)) {
    throw validationError("Idempotency-Key must be 8-64 characters: A-Z, a-z, 0-9, _ and -");
  }
  return value;
}

export function orderStatusTarget(value: unknown): "SHIPPED" | "DELIVERED" {
  if (value !== "SHIPPED" && value !== "DELIVERED")
    throw validationError("status must be SHIPPED or DELIVERED");
  return value;
}

// ---- query strings ----
export function limit(value: string | undefined, fallback = 20, maximum = 50): number {
  if (value === undefined) return fallback;
  if (!DIGITS_RE.test(value) || Number(value) < 1 || Number(value) > maximum) {
    throw validationError(`limit must be an integer between 1 and ${maximum}`);
  }
  return Number(value);
}

export function priceFilter(value: string | undefined, name: string): number | null {
  if (value === undefined || value === "") return null;
  if (!DIGITS_RE.test(value) || Number(value) > MAX_PRICE_PAISE) {
    throw validationError(`${name} must be an integer between 0 and ${MAX_PRICE_PAISE} (paise)`);
  }
  return Number(value);
}

export function boolFilter(value: string | undefined, name: string): boolean | null {
  if (value === undefined || value === "") return null;
  if (value !== "true" && value !== "false") throw validationError(`${name} must be true or false`);
  return value === "true";
}

export function searchQuery(value: string | undefined): string | null {
  if (value === undefined) return null;
  const clean = value.trim();
  if (!clean) return null;
  if (codePoints(clean) > 100) throw validationError("q must be at most 100 characters");
  return clean;
}

export function assistQuery(value: string | undefined): string {
  const clean = (value ?? "").trim();
  if (!clean) throw validationError("q is required");
  if (codePoints(clean) > 200) throw validationError("q must be at most 200 characters");
  return clean;
}

export function categoryFilter(value: string | undefined): string | null {
  if (value === undefined || value === "") return null;
  const clean = value.toLowerCase();
  if (!CATEGORY_RE.test(clean)) throw validationError("category is invalid");
  return clean;
}

export function orderStatusFilter(value: string | undefined): OrderStatus | null {
  if (value === undefined || value === "") return null;
  if (!(ORDER_STATUSES as readonly string[]).includes(value)) {
    throw validationError(`status must be one of ${ORDER_STATUSES.join(", ")}`);
  }
  return value as OrderStatus;
}

/** An id that cannot exist is a 404, not a 400: /orders/abc is simply not there. */
export function pathId(value: string, what: string): number {
  if (!DIGITS_RE.test(value) || value.length > 18) throw notFound(what);
  return Number(value);
}

// ---- keyset cursor (opaque to clients) ----
export const encodeCursor = (lastId: number) => Buffer.from(`p${lastId}`, "ascii").toString("base64url");

export function decodeCursor(value: string | undefined): number | null {
  if (value === undefined || value === "") return null;
  if (!CURSOR_RE.test(value) || value.length % 4 === 1) throw validationError("cursor is invalid");
  const raw = Buffer.from(value, "base64url").toString("latin1");
  if (!raw.startsWith("p") || !DIGITS_RE.test(raw.slice(1)) || raw.length > 19) {
    throw validationError("cursor is invalid");
  }
  return Number(raw.slice(1));
}
