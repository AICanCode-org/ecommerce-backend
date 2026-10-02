// Input validation. Every function returns the clean value or throws a 400 validation-error.
// The messages are part of the contract: the tests (and the other two tracks) expect them exactly.
import { validationError } from "./errors.js";
import { type OrderStatus } from "./models.js";

export const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
export const SKU_RE = /^[A-Z0-9][A-Z0-9-]{2,31}$/;
export const CATEGORY_RE = /^[a-z0-9][a-z0-9-]{1,29}$/;
export const COUPON_RE = /^[A-Z0-9]{3,20}$/;
export const IDEMPOTENCY_KEY_RE = /^[A-Za-z0-9_-]{8,64}$/;
export const KEYWORD_RE = /^[a-z0-9][a-z0-9 -]{0,29}$/;
export const CURSOR_RE = /^[A-Za-z0-9_-]{1,40}$/;
export const DIGITS_RE = /^[0-9]+$/;
export const MAX_PRICE_PAISE = 100_000_000; // ₹10 lakh
export const MAX_STOCK = 1_000_000;
export const MAX_QUANTITY = 10;

export type Json = Record<string, unknown>;
export const isInt = (v: unknown): v is number => typeof v === "number" && Number.isSafeInteger(v);

export function requireObject(payload: unknown): Json {
  if (typeof payload !== "object" || payload === null || Array.isArray(payload)) {
    throw validationError("request body must be a JSON object");
  }
  return payload as Json;
}

// ---- accounts ----
export function email(value: unknown): string {
  // TODO(phase-3 M1): a string (400 'email is required'); trim + lower-case; must match EMAIL_RE and be at
  //   most 254 characters (400 'email is invalid').
  throw new Error("TODO(phase-3 M1): email");
}

export function password(value: unknown): string {
  // TODO(phase-3 M1): a string of at least 8 characters and at most 72 BYTES in UTF-8 (bcrypt ignores the
  //   rest): 400 otherwise.
  throw new Error("TODO(phase-3 M1): password");
}

export function displayName(value: unknown): string {
  // TODO(phase-3 M1): a string, trimmed, 1-50 characters (count code points, not UTF-16 units).
  throw new Error("TODO(phase-3 M1): displayName");
}

// ---- catalog ----
export function sku(value: unknown): string {
  // TODO(phase-3 M1): a string, trimmed and UPPER-cased, matching SKU_RE (3-32 characters: A-Z, 0-9 and -).
  throw new Error("TODO(phase-3 M1): sku");
}

export function productName(value: unknown): string {
  // TODO(phase-3 M1): a string, trimmed, 1-120 characters.
  throw new Error("TODO(phase-3 M1): productName");
}

export function description(value: unknown): string {
  // TODO(phase-3 M1): optional: null/missing -> empty string; otherwise a string (trimmed) of at most 2000
  //   characters.
  throw new Error("TODO(phase-3 M1): description");
}

export function category(value: unknown): string {
  // TODO(phase-3 M1): a string, trimmed and lower-cased, matching CATEGORY_RE (2-30 characters: a-z, 0-9 and
  //   -).
  throw new Error("TODO(phase-3 M1): category");
}

export function pricePaise(value: unknown): number {
  // TODO(phase-3 M1): an INTEGER (not a float, not a boolean, not a string) between 1 and MAX_PRICE_PAISE.
  //   Money is always integer paise (ADR-0001).
  throw new Error("TODO(phase-3 M1): pricePaise");
}

export function stock(value: unknown): number {
  // TODO(phase-3 M1): an integer between 0 and MAX_STOCK (booleans are not integers).
  throw new Error("TODO(phase-3 M1): stock");
}

export function stockDelta(value: unknown): number {
  // TODO(phase-3 M1): a non-zero integer between -MAX_STOCK and MAX_STOCK.
  throw new Error("TODO(phase-3 M1): stockDelta");
}

export function active(value: unknown): boolean {
  // TODO(phase-3 M1): a real boolean: true or false, nothing else.
  throw new Error("TODO(phase-3 M1): active");
}

export function version(value: unknown): number {
  // TODO(phase-3 M1): a positive integer (the version you read; optimistic locking, ADR-0003).
  throw new Error("TODO(phase-3 M1): version");
}

/** Optional hints for an AI description draft: at most 5, each 1-30 characters, de-duplicated. */
export function keywords(value: unknown): string[] {
  // TODO(phase-3 M1): optional list (missing -> []); at most 5 strings, each lower-cased + trimmed and
  //   matching KEYWORD_RE; drop duplicates, keep the order.
  throw new Error("TODO(phase-3 M1): keywords");
}

// ---- cart + checkout ----
export function quantity(value: unknown): number {
  // TODO(phase-3 M1): an integer between 1 and MAX_QUANTITY (10).
  throw new Error("TODO(phase-3 M1): quantity");
}

export function couponCode(value: unknown): string {
  // TODO(phase-3 M1): a string (400 'code is required'), trimmed + UPPER-cased, matching COUPON_RE (400 'code
  //   is invalid').
  throw new Error("TODO(phase-3 M1): couponCode");
}

export function idempotencyKey(value: string | undefined | null): string {
  // TODO(phase-3 M1): the Idempotency-Key header: missing/blank -> 400 'Idempotency-Key header is required';
  //   must match IDEMPOTENCY_KEY_RE (8-64 characters).
  throw new Error("TODO(phase-3 M1): idempotencyKey");
}

export function orderStatusTarget(value: unknown): "SHIPPED" | "DELIVERED" {
  // TODO(phase-3 M1): only SHIPPED or DELIVERED can be set by an admin (cancel has its own endpoint, PAID
  //   comes from the webhook).
  throw new Error("TODO(phase-3 M1): orderStatusTarget");
}

// ---- query strings ----
export function limit(value: string | undefined, fallback = 20, maximum = 50): number {
  // TODO(phase-3 M1): a query string: missing -> the default (20); ASCII digits only, 1..maximum (50);
  //   anything else 400.
  throw new Error("TODO(phase-3 M1): limit");
}

export function priceFilter(value: string | undefined, name: string): number | null {
  // TODO(phase-3 M1): a query string: missing -> null; ASCII digits only, 0..MAX_PRICE_PAISE, else 400 naming
  //   the parameter.
  throw new Error("TODO(phase-3 M1): priceFilter");
}

export function boolFilter(value: string | undefined, name: string): boolean | null {
  // TODO(phase-3 M1): a query string: missing -> null; exactly 'true' or 'false', else 400 naming the
  //   parameter.
  throw new Error("TODO(phase-3 M1): boolFilter");
}

export function searchQuery(value: string | undefined): string | null {
  // TODO(phase-3 M1): missing or blank -> null; trimmed, at most 100 characters.
  throw new Error("TODO(phase-3 M1): searchQuery");
}

export function assistQuery(value: string | undefined): string {
  // TODO(phase-3 M1): required (400 'q is required'), trimmed, at most 200 characters.
  throw new Error("TODO(phase-3 M1): assistQuery");
}

export function categoryFilter(value: string | undefined): string | null {
  // TODO(phase-3 M1): missing -> null; lower-cased, must match CATEGORY_RE.
  throw new Error("TODO(phase-3 M1): categoryFilter");
}

export function orderStatusFilter(value: string | undefined): OrderStatus | null {
  // TODO(phase-3 M1): missing -> null; upper-cased, one of ORDER_STATUSES.
  throw new Error("TODO(phase-3 M1): orderStatusFilter");
}

/** An id that cannot exist is a 404, not a 400: /orders/abc is simply not there. */
export function pathId(value: string, what: string): number {
  // TODO(phase-3 M1): a path segment: ASCII digits only and a positive id that fits in 63 bits; anything else
  //   is 404 for 'what' (not 400: /orders/abc simply does not exist).
  throw new Error("TODO(phase-3 M1): pathId");
}

// ---- keyset cursor (opaque to clients) ----
export function encodeCursor(lastId: number): string {
  // TODO(phase-3 M3): opaque cursor: base64url (no padding) of 'p' + the last id shown.
  throw new Error("TODO(phase-3 M3): encodeCursor");
}

export function decodeCursor(value: string | undefined): number | null {
  // TODO(phase-3 M3): missing -> null; must match CURSOR_RE, decode base64url, be 'p' + digits with a
  //   positive id; anything else 400 'cursor is invalid'.
  throw new Error("TODO(phase-3 M3): decodeCursor");
}
