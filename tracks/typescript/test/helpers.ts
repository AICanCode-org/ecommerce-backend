import { expect } from "vitest";
import type { FastifyInstance } from "fastify";
import { DescriptionService, RuleParser, SearchAssistService, TemplateWriter } from "../src/ai.js";
import { buildApp } from "../src/app.js";
import type { Clock } from "../src/clock.js";
import { loadSettings, type Settings } from "../src/config.js";
import { ApiError } from "../src/errors.js";
import type { Metrics } from "../src/metrics.js";
import type { Principal, Role } from "../src/models.js";
import { LogPublisher } from "../src/orders.js";
import { InMemoryStore } from "../src/repository.js";
import { signWebhook } from "../src/security.js";
import { wire, type Ai } from "../src/wiring.js";

export const TEST_SECRET = "test-secret-that-is-at-least-32-bytes-long";
export const WEBHOOK_SECRET = "test-webhook-secret-at-least-32-bytes";

/** A clock you can move. Tests should never depend on the real time. */
export class FakeClock {
  now = new Date("2026-10-02T09:30:00.000Z");
  readonly fn: Clock = () => this.now;
  advance({ days = 0, hours = 0, minutes = 0, seconds = 0 }) {
    this.now = new Date(this.now.getTime() + ((days * 24 + hours) * 60 + minutes) * 60_000 + seconds * 1000);
  }
}

/** Predictable payment ids: pay_test_1, pay_test_2, ... */
export function paymentIds(): () => string {
  let n = 0;
  return () => `pay_test_${++n}`;
}

export function testSettings(overrides: Partial<Settings> = {}): Settings {
  return {
    ...loadSettings({}),
    jwtSecret: TEST_SECRET,
    bcryptCost: 4,
    adminEmail: null,
    adminPassword: null,
    paymentWebhookSecret: WEBHOOK_SECRET,
    aiProvider: "offline",
    ...overrides,
  };
}

export const offlineAi = () => ({
  descriptions: new DescriptionService(new TemplateWriter()),
  assist: new SearchAssistService(new RuleParser()),
});

const repos = (store: InMemoryStore) => ({ users: store, products: store, carts: store, orders: store });

/** The real Fastify app on in-memory adapters, for tests that need a custom setup. */
export async function makeApp(store: InMemoryStore, settings: Settings = testSettings()) {
  const deps = await wire(settings, repos(store), { ai: offlineAi(), warn: () => {} });
  return buildApp(deps);
}

/** Expects `promise` to reject with an ApiError and returns [status, slug]. */
export async function statusOf(promise: Promise<unknown>): Promise<[number, string]> {
  try {
    await promise;
  } catch (err) {
    expect(err).toBeInstanceOf(ApiError);
    return [(err as ApiError).status, (err as ApiError).slug];
  }
  throw new Error("expected an ApiError");
}

/** Expects a 400 validation-error and returns its detail message. */
export function message(fn: () => unknown): string {
  try {
    fn();
  } catch (err) {
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(400);
    expect((err as ApiError).slug).toBe("validation-error");
    return (err as ApiError).detail ?? "";
  }
  throw new Error("expected a validation error");
}

export interface Session {
  accessToken: string;
  user: { id: number; email: string; role: string; displayName: string };
}

type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Json = any;

/** The real Fastify app on in-memory adapters (no database, no network) + helpers that read like user stories. */
export class Api {
  private n = 0;
  private adminSession: Session | null = null;
  constructor(
    readonly app: FastifyInstance,
    readonly store: InMemoryStore,
    readonly clock: FakeClock,
  ) {}

  static async create(opts: { settings?: Partial<Settings>; ai?: Ai; metrics?: Metrics } = {}): Promise<Api> {
    const store = new InMemoryStore();
    const clock = new FakeClock();
    const settings = testSettings({
      adminEmail: "admin@shopflow.test",
      adminPassword: "admin-password",
      ...opts.settings,
    });
    const ai = opts.ai ?? (settings.aiProvider === "llm" ? undefined : offlineAi());
    const deps = await wire(settings, repos(store), {
      clock: clock.fn,
      ai,
      metrics: opts.metrics,
      warn: () => {},
      paymentIds: paymentIds(),
      publisher: new LogPublisher(() => {}),
    });
    return new Api(buildApp(deps), store, clock);
  }

  request(method: Method, url: string, payload?: unknown, session?: Session | string, headers = {}) {
    const all: Record<string, string> = { ...headers };
    if (typeof session === "string") all.authorization = session;
    else if (session) all.authorization = `Bearer ${session.accessToken}`;
    return this.app.inject({ method, url, payload: payload as never, headers: all });
  }

  /** Register + log in a new customer. */
  async customer(name?: string): Promise<Session> {
    this.n += 1;
    const email = `user${this.n}@shopflow.test`;
    const r = await this.request("POST", "/api/v1/auth/register", {
      email,
      password: "password-123",
      displayName: name ?? `User ${this.n}`,
    });
    expect(r.statusCode, r.body).toBe(201);
    return this.login(email, "password-123");
  }

  async admin(): Promise<Session> {
    this.adminSession ??= await this.login("admin@shopflow.test", "admin-password");
    return this.adminSession;
  }

  async login(email: string, password: string): Promise<Session> {
    const r = await this.request("POST", "/api/v1/auth/login", { email, password });
    expect(r.statusCode, r.body).toBe(200);
    return r.json();
  }

  async product(fields: Record<string, unknown> = {}): Promise<Json> {
    this.n += 1;
    const body = {
      sku: `SKU-${String(this.n).padStart(3, "0")}`,
      name: "Trail Running Shoes",
      category: "shoes",
      pricePaise: 249_900,
      stock: 5,
      description: "Light and grippy. Made for wet trails.",
      ...fields,
    };
    const r = await this.request("POST", "/api/v1/products", body, await this.admin());
    expect(r.statusCode, r.body).toBe(201);
    return r.json();
  }

  async add(session: Session, sku: string, quantity = 1): Promise<Json> {
    const r = await this.request("PUT", `/api/v1/cart/items/${sku}`, { quantity }, session);
    expect(r.statusCode, r.body).toBe(200);
    return r.json();
  }

  checkout(session: Session, key = "checkout-key-0001") {
    return this.request("POST", "/api/v1/checkout", undefined, session, { "idempotency-key": key });
  }

  async order(session: Session, key = "checkout-key-0001", productFields: Record<string, unknown> = {}) {
    const product = await this.product(productFields);
    await this.add(session, product.sku);
    const r = await this.checkout(session, key);
    expect(r.statusCode, r.body).toBe(201);
    return r.json();
  }

  webhook(event: object, secret = WEBHOOK_SECRET, signature?: string) {
    const body = JSON.stringify(event);
    const sig = signature ?? signWebhook(secret, body);
    return this.app.inject({
      method: "POST",
      url: "/api/v1/payments/webhook",
      payload: body,
      headers: { "content-type": "application/json", "x-signature": sig },
    });
  }

  pay(order: Json, eventId = "evt_test_0001", kind = "payment.succeeded") {
    return this.webhook({ id: eventId, type: kind, paymentId: order.payment.id });
  }
}

/** Users created directly in the store (service-level tests): {asha: {userId, role}, ...}. */
export async function makePeople(store: InMemoryStore, clock: FakeClock, list: [string, Role][]) {
  const people: Record<string, Principal> = {};
  for (const [name, role] of list) {
    const title = name[0]!.toUpperCase() + name.slice(1);
    const user = await store.insertUser(`${name}@shopflow.test`, "x", title, role, clock.now);
    people[name] = { userId: user.id, role };
  }
  return people;
}
