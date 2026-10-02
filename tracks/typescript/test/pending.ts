import { describe } from "vitest";

// ---------------------------------------------------------------------------
// PENDING topics describe behaviour you have not built yet, so their tests are
// skipped (vitest reports them as "skipped"). When you start a milestone, delete
// its line here, watch the tests fail (red), implement until they pass (green).
// See docs/phase-3.
// ---------------------------------------------------------------------------
export const PENDING = new Set<string>([
  "validation", // Phase 3 M1 - validation
  "money", // Phase 3 M1 - money and pricing
  "security", // Phase 3 M2 - passwords, tokens, webhook signatures
  "auth", // Phase 3 M2 - AuthService
  "api-auth", // Phase 3 M2 - auth endpoints
  "catalog", // Phase 3 M3 - CatalogService
  "api-products", // Phase 3 M3 - product endpoints
  "ai", // Phase 3 M3 - AI descriptions and search assist
  "api-ai", // Phase 3 M3 - AI endpoints
  "cart", // Phase 3 M4 - CartService and coupons
  "api-cart", // Phase 3 M4 - cart endpoints
  "orders", // Phase 3 M5 - checkout and orders
  "payments", // Phase 3 M5 - payment webhook
  "api-orders", // Phase 3 M5 - order, webhook and outbox endpoints
  "integration", // Phase 4 - integration tests
]);

/** `const describe = pendingDescribe("codes")` - a describe that is skipped while the topic is pending. */
export const pendingDescribe = (topic: string) => describe.skipIf(PENDING.has(topic));
