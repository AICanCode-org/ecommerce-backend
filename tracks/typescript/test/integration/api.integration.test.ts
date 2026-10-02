// Integration tests: the real app against a real PostgreSQL (docker compose up -d postgres).
// Run with:  make test-integration
// Uses TEST_DATABASE_URL so your dev data is never touched.
import type { FastifyInstance } from "fastify";
import pg from "pg";
import { afterAll, beforeAll, beforeEach, expect, it } from "vitest";
import { buildApp } from "../../src/app.js";
import { loadSettings } from "../../src/config.js";
import { createPool, runMigrations } from "../../src/db.js";
import { LogPublisher } from "../../src/orders.js";
import {
  PgCartRepository,
  PgOrderRepository,
  PgProductRepository,
  PgUserRepository,
} from "../../src/repository.js";
import { signWebhook } from "../../src/security.js";
import { wire } from "../../src/wiring.js";
import { offlineAi, testSettings, WEBHOOK_SECRET, type Session } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("integration");
const DB_URL = process.env.TEST_DATABASE_URL ?? "postgresql://shopflow:shopflow@localhost:5432/shopflow_test";
const ADMIN = { email: "admin@shopflow.test", password: "admin-password" };
const TABLES =
  "users, products, carts, cart_items, orders, order_items, payments, payment_events, outbox_events";
type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

describe("API against PostgreSQL", () => {
  let pool: pg.Pool;
  let app: FastifyInstance;
  const sql = async (text: string, params: unknown[] = []) => (await pool.query(text, params)).rows;

  beforeAll(async () => {
    pool = createPool(DB_URL);
    await runMigrations(pool, loadSettings({}).migrationsDir);
  });
  afterAll(async () => {
    await app?.close();
    await pool?.end();
  });
  beforeEach(async () => {
    await sql(`TRUNCATE ${TABLES} RESTART IDENTITY CASCADE`);
    const repos = {
      users: new PgUserRepository(pool),
      products: new PgProductRepository(pool),
      carts: new PgCartRepository(pool),
      orders: new PgOrderRepository(pool),
    };
    const settings = testSettings({ adminEmail: ADMIN.email, adminPassword: ADMIN.password });
    await app?.close();
    app = buildApp(
      await wire(settings, repos, { ai: offlineAi(), warn: () => {}, publisher: new LogPublisher(() => {}) }),
    );
  });

  const req = (
    method: Method,
    url: string,
    payload?: unknown,
    s?: Session,
    headers: Record<string, string> = {},
  ) =>
    app.inject({
      method,
      url,
      payload: payload as never,
      headers: s ? { authorization: `Bearer ${s.accessToken}`, ...headers } : headers,
    });

  async function login(email: string, password = "password-123"): Promise<Session> {
    const r = await req("POST", "/api/v1/auth/login", { email, password });
    expect(r.statusCode, r.body).toBe(200);
    return r.json();
  }
  async function customer(email: string): Promise<Session> {
    const r = await req("POST", "/api/v1/auth/register", {
      email,
      password: "password-123",
      displayName: "C",
    });
    expect(r.statusCode, r.body).toBe(201);
    return login(email);
  }
  const admin = () => login(ADMIN.email, ADMIN.password);
  async function product(sku: string, fields: Record<string, unknown> = {}) {
    const body = {
      sku,
      name: "Trail Running Shoes",
      category: "shoes",
      pricePaise: 249_900,
      stock: 5,
      description: "Grippy soles for wet trails.",
      ...fields,
    };
    const r = await req("POST", "/api/v1/products", body, await admin());
    expect(r.statusCode, r.body).toBe(201);
    return r.json();
  }
  const add = (s: Session, sku: string, quantity: number) =>
    req("PUT", `/api/v1/cart/items/${sku}`, { quantity }, s);
  const checkout = (s: Session, key: string) =>
    req("POST", "/api/v1/checkout", undefined, s, { "idempotency-key": key });
  const webhook = (event: object) => {
    const body = JSON.stringify(event);
    return app.inject({
      method: "POST",
      url: "/api/v1/payments/webhook",
      payload: body,
      headers: { "content-type": "application/json", "x-signature": signWebhook(WEBHOOK_SECRET, body) },
    });
  };
  const skus = (items: { sku: string }[]) => items.map((p) => p.sku);

  it("migrates, reports health and seeds the demo coupons", async () => {
    expect((await req("GET", "/health")).json()).toEqual({ status: "UP", checks: { database: "UP" } });
    expect(
      (await sql("SELECT version FROM schema_migrations ORDER BY version")).map((r) => r.version)[0],
    ).toBe(1);
    expect((await sql("SELECT code FROM coupons WHERE active ORDER BY code")).map((r) => r.code)).toEqual([
      "FLAT100",
      "SAVE10",
    ]);
  });

  it("hashes passwords and keeps emails unique", async () => {
    await customer("asha@shopflow.test");
    const [{ password_hash: hash }] = await sql(
      "SELECT password_hash FROM users WHERE email = 'asha@shopflow.test'",
    );
    expect(hash.startsWith("$2")).toBe(true);
    expect(hash).not.toContain("password-123");
    const r = await req("POST", "/api/v1/auth/register", {
      email: "ASHA@shopflow.test",
      password: "password-123",
      displayName: "D",
    });
    expect(r.statusCode).toBe(409);
  });

  it("enforces SKU uniqueness and optimistic locking in the database", async () => {
    await product("SHOE-1");
    const dup = { sku: "shoe-1", name: "x", category: "shoes", pricePaise: 1 };
    const r = await req("POST", "/api/v1/products", dup, await admin());
    expect(r.statusCode).toBe(409);
    expect(r.json().type.endsWith("/sku-taken")).toBe(true);
    const a = await req("PATCH", "/api/v1/products/SHOE-1", { version: 1, pricePaise: 1000 }, await admin());
    const b = await req("PATCH", "/api/v1/products/SHOE-1", { version: 1, pricePaise: 2000 }, await admin());
    expect([a.statusCode, b.statusCode]).toEqual([200, 409]);
    expect(
      await sql("SELECT price_paise::int AS price_paise, version FROM products WHERE sku = 'SHOE-1'"),
    ).toEqual([{ price_paise: 1000, version: 2 }]);
  });

  it("stems full-text search and filters", async () => {
    await product("SHOE-1", { name: "Trail Running Shoes", pricePaise: 249_900 });
    await product("BAG-1", {
      name: "Laptop Backpack",
      category: "bags",
      pricePaise: 179_900,
      description: "Waterproof.",
    });
    await product("OLD-1", { name: "Old Running Shoes", active: false });
    const found = (await req("GET", "/api/v1/products?q=shoe%20run")).json().items;
    expect(skus(found)).toEqual(["SHOE-1"]); // stemming: "shoe" finds "Shoes", "run" finds "Running"
    const cheap = (await req("GET", "/api/v1/products?maxPricePaise=200000")).json().items;
    expect(skus(cheap)).toEqual(["BAG-1"]);
    const assist = (
      await req("GET", "/api/v1/search/assist?q=" + encodeURIComponent("running shoe under 3000"))
    ).json();
    expect(assist.filters.category).toBe("shoes");
    expect(skus(assist.items)).toEqual(["SHOE-1"]);
  });

  it("paginates over the real index", async () => {
    for (let n = 0; n < 5; n++) await product(`P-${n}`);
    const seen: string[] = [];
    let cursor: string | null = null;
    do {
      const url: string = `/api/v1/products?limit=2${cursor ? `&cursor=${cursor}` : ""}`;
      const page = (await req("GET", url)).json();
      seen.push(...skus(page.items));
      cursor = page.nextCursor;
    } while (cursor);
    expect(seen).toEqual(["P-0", "P-1", "P-2", "P-3", "P-4"]);
  });

  it("refuses negative stock in the database", async () => {
    await product("SHOE-1", { stock: 1 });
    await expect(sql("UPDATE products SET stock = stock - 2 WHERE sku = 'SHOE-1'")).rejects.toMatchObject({
      code: "23514", // check_violation
    });
    const r = await req("POST", "/api/v1/products/SHOE-1/stock", { delta: -2 }, await admin());
    expect(r.statusCode).toBe(409);
    expect(r.json().detail).toBe("stock cannot go below 0 (current stock 1)");
  });

  it("checks out in one transaction", async () => {
    const asha = await customer("asha@shopflow.test");
    await product("SHOE-1", { stock: 3 });
    await product("BAG-1", { stock: 1, category: "bags", pricePaise: 60_000 });
    await add(asha, "SHOE-1", 2);
    await add(asha, "BAG-1", 1);
    await req("PUT", "/api/v1/cart/coupon", { code: "SAVE10" }, asha);
    const r = await checkout(asha, "asha-key-0001");
    expect(r.statusCode, r.body).toBe(201);
    const order = r.json();
    expect([order.subtotalPaise, order.discountPaise, order.shippingPaise]).toEqual([559_800, 20_000, 0]);
    expect(await sql("SELECT sku, stock FROM products ORDER BY sku")).toEqual([
      { sku: "BAG-1", stock: 0 },
      { sku: "SHOE-1", stock: 1 },
    ]);
    expect(await sql("SELECT count(*)::int AS n FROM cart_items")).toEqual([{ n: 0 }]);
    expect(await sql("SELECT type FROM outbox_events")).toEqual([{ type: "order.placed" }]);
    expect(await sql("SELECT status, amount_paise::int AS amount_paise FROM payments")).toEqual([
      { status: "PENDING", amount_paise: order.totalPaise },
    ]);
    // a failing line rolls back everything: no order, no stock change, cart intact
    const ravi = await customer("ravi@shopflow.test");
    await add(ravi, "SHOE-1", 1);
    await sql("UPDATE products SET stock = 0 WHERE sku = 'SHOE-1'");
    expect((await checkout(ravi, "ravi-key-0001")).statusCode).toBe(409);
    expect(await sql("SELECT count(*)::int AS n FROM orders")).toEqual([{ n: 1 }]);
    expect(await sql("SELECT count(*)::int AS n FROM cart_items")).toEqual([{ n: 1 }]);
  });

  it("never oversells under concurrent checkouts", async () => {
    await product("LAST-1", { stock: 1 });
    const sessions: Session[] = [];
    for (let n = 0; n < 5; n++) sessions.push(await customer(`buyer${n}@shopflow.test`));
    for (const s of sessions) await add(s, "LAST-1", 1);
    const codes = (await Promise.all(sessions.map((s) => checkout(s, "same-key-for-all")))).map(
      (r) => r.statusCode,
    );
    expect(codes.sort()).toEqual([201, 409, 409, 409, 409]);
    expect(await sql("SELECT stock FROM products WHERE sku = 'LAST-1'")).toEqual([{ stock: 0 }]);
  });

  it("creates one order for concurrent retries with one key", async () => {
    const asha = await customer("asha@shopflow.test");
    await product("SHOE-1", { stock: 10 });
    await add(asha, "SHOE-1", 1);
    const results = await Promise.all([1, 2, 3, 4].map(() => checkout(asha, "double-click-0001")));
    expect(await sql("SELECT count(*)::int AS n FROM orders")).toEqual([{ n: 1 }]);
    expect(new Set(results.map((r) => r.json().id).filter(Boolean)).size).toBe(1);
    expect(await sql("SELECT stock FROM products")).toEqual([{ stock: 9 }]);
  });

  it("processes a webhook delivered twice at once exactly once", async () => {
    const asha = await customer("asha@shopflow.test");
    await product("SHOE-1", { stock: 2 });
    await add(asha, "SHOE-1", 1);
    const order = (await checkout(asha, "asha-key-0001")).json();
    const event = { id: "evt_integration_1", type: "payment.succeeded", paymentId: order.payment.id };
    const statuses = (await Promise.all([1, 2, 3].map(() => webhook(event)))).map(
      (r) => r.json().status ?? String(r.statusCode),
    );
    expect(statuses.filter((s) => s === "processed")).toHaveLength(1);
    expect(await sql("SELECT status FROM orders")).toEqual([{ status: "PAID" }]);
    expect(await sql("SELECT type FROM outbox_events ORDER BY id")).toEqual([
      { type: "order.placed" },
      { type: "order.paid" },
    ]);
    expect(await sql("SELECT count(*)::int AS n FROM payment_events")).toEqual([{ n: 1 }]);
  });

  it("restocks on cancel and the relay marks events published", async () => {
    const asha = await customer("asha@shopflow.test");
    await product("SHOE-1", { stock: 2 });
    await add(asha, "SHOE-1", 2);
    const order = (await checkout(asha, "asha-key-0001")).json();
    expect((await req("POST", `/api/v1/orders/${order.id}/cancel`, undefined, asha)).json().status).toBe(
      "CANCELLED",
    );
    expect(await sql("SELECT stock FROM products")).toEqual([{ stock: 2 }]);
    expect((await req("POST", "/api/v1/admin/outbox/publish", undefined, await admin())).json()).toEqual({
      published: 2,
    });
    expect(await sql("SELECT count(*)::int AS n FROM outbox_events WHERE published_at IS NULL")).toEqual([
      { n: 0 },
    ]);
    const [{ payload }] = await sql("SELECT payload FROM outbox_events WHERE type = 'order.cancelled'");
    expect(payload).toEqual({
      orderId: order.id,
      userId: asha.user.id,
      status: "CANCELLED",
      totalPaise: order.totalPaise,
    });
  });

  it("bootstraps the admin from the environment", async () => {
    expect(await sql("SELECT role FROM users WHERE email = $1", [ADMIN.email])).toEqual([{ role: "ADMIN" }]);
  });

  it("reports the database as down when it is unreachable", async () => {
    const other = createPool(DB_URL);
    const repo = new PgUserRepository(other);
    expect(await repo.ping()).toBe(true);
    await other.end(); // simulate the database going away: every new query now fails
    expect(await repo.ping()).toBe(false);
  });
});
