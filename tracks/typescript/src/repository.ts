// Persistence ports with two adapters each: PostgreSQL and in-memory (for unit tests).
//
// The in-memory store behaves like the database where it matters (unique e-mail/SKU/idempotency key,
// version check, guarded stock decrement, all-or-nothing checkout). Full-text search is the exception:
// in memory it is plain word matching, while PostgreSQL also stems ("shoe" finds "shoes").
// The integration tests cover the real thing.
import pg from "pg";
import { DuplicateError, InsufficientStockError } from "./errors.js";
import type {
  CartState,
  CategoryCount,
  Coupon,
  Order,
  OrderDraft,
  OrderLine,
  OrderStatus,
  OutboxEvent,
  Payment,
  Product,
  ProductQuery,
  Role,
  Transition,
  TransitionResult,
  User,
} from "./models.js";

export const PAYMENT_PROVIDER = "fakepay";
export const DEMO_COUPONS: Coupon[] = [
  { code: "SAVE10", kind: "PERCENT", value: 10, minSubtotalPaise: 0, maxDiscountPaise: 20_000, active: true },
  {
    code: "FLAT100",
    kind: "FLAT",
    value: 10_000,
    minSubtotalPaise: 50_000,
    maxDiscountPaise: null,
    active: true,
  },
  { code: "EXPIRED5", kind: "PERCENT", value: 5, minSubtotalPaise: 0, maxDiscountPaise: null, active: false },
];

// ---------------------------------------------------------------------------------------------
// Ports
// ---------------------------------------------------------------------------------------------
export interface UserRepository {
  insertUser(email: string, passwordHash: string, displayName: string, role: Role, at: Date): Promise<User>;
  findUserByEmail(email: string): Promise<User | null>;
  findUser(id: number): Promise<User | null>;
  updateRole(id: number, role: Role): Promise<User | null>;
  ping(): Promise<boolean>;
}

export interface ProductRepository {
  insertProduct(product: Product): Promise<Product>;
  findProduct(sku: string): Promise<Product | null>;
  updateProduct(product: Product, expectedVersion: number): Promise<Product | null>;
  adjustStock(productId: number, delta: number, at: Date): Promise<Product | null>;
  searchProducts(query: ProductQuery): Promise<Product[]>;
  categories(): Promise<CategoryCount[]>;
}

export interface CartRepository {
  cart(userId: number): Promise<CartState>;
  setCartItem(userId: number, productId: number, quantity: number, at: Date): Promise<void>;
  removeCartItem(userId: number, productId: number): Promise<void>;
  setCoupon(userId: number, code: string | null, at: Date): Promise<void>;
  findCoupon(code: string): Promise<Coupon | null>;
}

export interface OrderRepository {
  findOrderByKey(userId: number, key: string): Promise<Order | null>;
  placeOrder(draft: OrderDraft): Promise<Order>;
  findOrder(id: number): Promise<Order | null>;
  ordersForUser(userId: number, limit: number): Promise<Order[]>;
  ordersByStatus(status: OrderStatus | null, limit: number): Promise<Order[]>;
  findPayment(paymentId: string): Promise<Payment | null>;
  hasPaymentEvent(eventId: string): Promise<boolean>;
  applyTransition(t: Transition): Promise<TransitionResult>;
  outbox(pendingOnly: boolean, limit: number): Promise<OutboxEvent[]>;
  markPublished(eventId: number, at: Date): Promise<void>;
}

export const orderEventPayload = (
  userId: number,
  orderId: number,
  status: OrderStatus,
  totalPaise: number,
) => ({
  orderId,
  userId,
  status,
  totalPaise,
});

// ---------------------------------------------------------------------------------------------
// PostgreSQL
// ---------------------------------------------------------------------------------------------
type Row = Record<string, unknown>;
const USER_COLS = "id, email, password_hash, display_name, role, created_at";
const PRODUCT_COLS =
  "id, sku, name, description, category, price_paise, stock, active, version, created_at, updated_at";
const ORDER_SELECT = `
SELECT o.id, o.user_id, o.status, o.subtotal_paise, o.discount_paise, o.shipping_paise, o.total_paise,
       o.coupon_code, o.idempotency_key, o.created_at, o.updated_at,
       p.id AS payment_id, p.provider, p.status AS payment_status, p.amount_paise,
       p.created_at AS payment_created_at, p.updated_at AS payment_updated_at,
       COALESCE((SELECT json_agg(json_build_object('product_id', i.product_id, 'sku', i.sku, 'name', i.name,
                 'unit_price_paise', i.unit_price_paise, 'quantity', i.quantity) ORDER BY i.line_no)
                 FROM order_items i WHERE i.order_id = o.id), '[]') AS lines
FROM orders o JOIN payments p ON p.order_id = o.id
`;

const num = (v: unknown) => Number(v); // BIGINT arrives as a string: exact up to 2^53, plenty here
const toUser = (r: Row): User => ({
  id: num(r.id),
  email: r.email as string,
  passwordHash: r.password_hash as string,
  displayName: r.display_name as string,
  role: r.role as Role,
  createdAt: r.created_at as Date,
});
const toProduct = (r: Row): Product => ({
  id: num(r.id),
  sku: r.sku as string,
  name: r.name as string,
  description: r.description as string,
  category: r.category as string,
  pricePaise: num(r.price_paise),
  stock: num(r.stock),
  active: r.active as boolean,
  version: num(r.version),
  createdAt: r.created_at as Date,
  updatedAt: r.updated_at as Date,
});
const toPayment = (r: Row): Payment => ({
  id: r.id as string,
  orderId: num(r.order_id),
  provider: r.provider as string,
  status: r.status as Payment["status"],
  amountPaise: num(r.amount_paise),
  createdAt: r.created_at as Date,
  updatedAt: r.updated_at as Date,
});
const toOrder = (r: Row): Order => ({
  id: num(r.id),
  userId: num(r.user_id),
  status: r.status as OrderStatus,
  lines: (r.lines as Row[]).map((l): OrderLine => ({
    productId: num(l.product_id),
    sku: l.sku as string,
    name: l.name as string,
    unitPricePaise: num(l.unit_price_paise),
    quantity: num(l.quantity),
  })),
  subtotalPaise: num(r.subtotal_paise),
  discountPaise: num(r.discount_paise),
  shippingPaise: num(r.shipping_paise),
  totalPaise: num(r.total_paise),
  couponCode: (r.coupon_code as string | null) ?? null,
  idempotencyKey: r.idempotency_key as string,
  payment: {
    id: r.payment_id as string,
    orderId: num(r.id),
    provider: r.provider as string,
    status: r.payment_status as Payment["status"],
    amountPaise: num(r.amount_paise),
    createdAt: r.payment_created_at as Date,
    updatedAt: r.payment_updated_at as Date,
  },
  createdAt: r.created_at as Date,
  updatedAt: r.updated_at as Date,
});
const toEvent = (r: Row): OutboxEvent => ({
  id: num(r.id),
  type: r.type as string,
  orderId: num(r.order_id),
  payload: r.payload as Record<string, unknown>,
  createdAt: r.created_at as Date,
  publishedAt: (r.published_at as Date | null) ?? null,
});

const isUniqueViolation = (err: unknown) => (err as { code?: string }).code === "23505";

class Rollback extends Error {
  constructor(readonly result: TransitionResult) {
    super(result);
  }
}

async function inTransaction<T>(pool: pg.Pool, work: (client: pg.PoolClient) => Promise<T>): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const result = await work(client);
    await client.query("COMMIT");
    return result;
  } catch (err) {
    await client.query("ROLLBACK").catch(() => {});
    throw err;
  } finally {
    client.release();
  }
}

abstract class Pg {
  constructor(protected readonly pool: pg.Pool) {}

  protected async one(sql: string, params: unknown[]): Promise<Row | null> {
    const { rows } = await this.pool.query(sql, params);
    return rows[0] ?? null;
  }

  protected async all(sql: string, params: unknown[]): Promise<Row[]> {
    return (await this.pool.query(sql, params)).rows;
  }
}

export class PgUserRepository extends Pg implements UserRepository {
  async insertUser(email: string, passwordHash: string, displayName: string, role: Role, at: Date) {
    try {
      const row = await this.one(
        `INSERT INTO users (email, password_hash, display_name, role, created_at) VALUES ($1, $2, $3, $4, $5) RETURNING ${USER_COLS}`,
        [email, passwordHash, displayName, role, at],
      );
      return toUser(row!);
    } catch (err) {
      if (isUniqueViolation(err)) throw new DuplicateError("email");
      throw err;
    }
  }

  async findUserByEmail(email: string) {
    const row = await this.one(`SELECT ${USER_COLS} FROM users WHERE email = $1`, [email]);
    return row ? toUser(row) : null;
  }

  async findUser(id: number) {
    const row = await this.one(`SELECT ${USER_COLS} FROM users WHERE id = $1`, [id]);
    return row ? toUser(row) : null;
  }

  async updateRole(id: number, role: Role) {
    const row = await this.one(`UPDATE users SET role = $1 WHERE id = $2 RETURNING ${USER_COLS}`, [role, id]);
    return row ? toUser(row) : null;
  }

  async ping() {
    try {
      await this.pool.query("SELECT 1");
      return true;
    } catch {
      return false; // health checks must never throw
    }
  }
}

export class PgProductRepository extends Pg implements ProductRepository {
  async insertProduct(p: Product) {
    try {
      const row = await this.one(
        `INSERT INTO products (sku, name, description, category, price_paise, stock, active, created_at, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) RETURNING ${PRODUCT_COLS}`,
        [p.sku, p.name, p.description, p.category, p.pricePaise, p.stock, p.active, p.createdAt, p.updatedAt],
      );
      return toProduct(row!);
    } catch (err) {
      if (isUniqueViolation(err)) throw new DuplicateError("sku");
      throw err;
    }
  }

  async findProduct(sku: string) {
    const row = await this.one(`SELECT ${PRODUCT_COLS} FROM products WHERE sku = $1`, [sku]);
    return row ? toProduct(row) : null;
  }

  async updateProduct(p: Product, expectedVersion: number) {
    // Optimistic locking: the WHERE clause only matches if nobody saved in between (ADR-0003).
    const row = await this.one(
      `UPDATE products SET name = $1, description = $2, category = $3, price_paise = $4, active = $5,
         updated_at = $6, version = version + 1 WHERE id = $7 AND version = $8 RETURNING ${PRODUCT_COLS}`,
      [p.name, p.description, p.category, p.pricePaise, p.active, p.updatedAt, p.id, expectedVersion],
    );
    return row ? toProduct(row) : null;
  }

  async adjustStock(productId: number, delta: number, at: Date) {
    // One statement, guarded: never read-modify-write stock in application code (ADR-0002).
    const row = await this.one(
      `UPDATE products SET stock = stock + $1, updated_at = $2 WHERE id = $3 AND stock + $1 >= 0 RETURNING ${PRODUCT_COLS}`,
      [delta, at, productId],
    );
    return row ? toProduct(row) : null;
  }

  async searchProducts(q: ProductQuery) {
    const where = ["active"];
    const params: unknown[] = [];
    const add = (clause: string, value: unknown) => {
      params.push(value);
      where.push(clause.replace("?", `$${params.length}`));
    };
    if (q.afterId != null) add("id > ?", q.afterId); // keyset pagination: no OFFSET scan
    if (q.q) add("search_vector @@ plainto_tsquery('english', ?)", q.q);
    if (q.category) add("category = ?", q.category);
    if (q.minPricePaise != null) add("price_paise >= ?", q.minPricePaise);
    if (q.maxPricePaise != null) add("price_paise <= ?", q.maxPricePaise);
    if (q.inStock != null) where.push(q.inStock ? "stock > 0" : "stock = 0");
    params.push(q.limit);
    const rows = await this.all(
      `SELECT ${PRODUCT_COLS} FROM products WHERE ${where.join(" AND ")} ORDER BY id LIMIT $${params.length}`,
      params,
    );
    return rows.map(toProduct);
  }

  async categories() {
    const rows = await this.all(
      "SELECT category, COUNT(*) AS n FROM products WHERE active GROUP BY category ORDER BY category",
      [],
    );
    return rows.map((r) => ({ name: r.category as string, products: num(r.n) }));
  }
}

export class PgCartRepository extends Pg implements CartRepository {
  async cart(userId: number) {
    const cols = PRODUCT_COLS.split(", ")
      .map((c) => `p.${c}`)
      .join(", ");
    const rows = await this.all(
      `SELECT ${cols}, ci.quantity FROM cart_items ci JOIN products p ON p.id = ci.product_id
       WHERE ci.user_id = $1 ORDER BY ci.added_at, p.id`,
      [userId],
    );
    const coupon = await this.one("SELECT coupon_code FROM carts WHERE user_id = $1", [userId]);
    return {
      lines: rows.map((r) => ({ product: toProduct(r), quantity: num(r.quantity) })),
      couponCode: (coupon?.coupon_code as string | null) ?? null,
    };
  }

  async setCartItem(userId: number, productId: number, quantity: number, at: Date) {
    await this.pool.query(
      `INSERT INTO cart_items (user_id, product_id, quantity, added_at) VALUES ($1, $2, $3, $4)
       ON CONFLICT (user_id, product_id) DO UPDATE SET quantity = EXCLUDED.quantity`,
      [userId, productId, quantity, at],
    );
  }

  async removeCartItem(userId: number, productId: number) {
    await this.pool.query("DELETE FROM cart_items WHERE user_id = $1 AND product_id = $2", [
      userId,
      productId,
    ]);
  }

  async setCoupon(userId: number, code: string | null, at: Date) {
    await this.pool.query(
      `INSERT INTO carts (user_id, coupon_code, updated_at) VALUES ($1, $2, $3)
       ON CONFLICT (user_id) DO UPDATE SET coupon_code = EXCLUDED.coupon_code, updated_at = EXCLUDED.updated_at`,
      [userId, code, at],
    );
  }

  async findCoupon(code: string) {
    const r = await this.one(
      "SELECT code, kind, value, min_subtotal_paise, max_discount_paise, active FROM coupons WHERE code = $1",
      [code],
    );
    if (!r) return null;
    return {
      code: r.code as string,
      kind: r.kind as Coupon["kind"],
      value: num(r.value),
      minSubtotalPaise: num(r.min_subtotal_paise),
      maxDiscountPaise: r.max_discount_paise === null ? null : num(r.max_discount_paise),
      active: r.active as boolean,
    };
  }
}

export class PgOrderRepository extends Pg implements OrderRepository {
  async findOrderByKey(userId: number, key: string) {
    const row = await this.one(`${ORDER_SELECT} WHERE o.user_id = $1 AND o.idempotency_key = $2`, [
      userId,
      key,
    ]);
    return row ? toOrder(row) : null;
  }

  /** ONE transaction: stock, order, lines, payment, outbox, empty cart. Any failure -> nothing written. */
  async placeOrder(d: OrderDraft) {
    try {
      return await inTransaction(this.pool, async (db) => {
        // Lock rows in a fixed order (product id) so two checkouts can never deadlock.
        for (const line of [...d.lines].sort((a, b) => a.productId - b.productId)) {
          const r = await db.query(
            "UPDATE products SET stock = stock - $1, updated_at = $2 WHERE id = $3 AND active AND stock >= $1",
            [line.quantity, d.at, line.productId],
          );
          if (r.rowCount !== 1) throw new InsufficientStockError(line.sku);
        }
        const p = d.pricing;
        const { rows } = await db.query(
          `INSERT INTO orders (user_id, status, subtotal_paise, discount_paise, shipping_paise, total_paise,
             coupon_code, idempotency_key, created_at, updated_at)
           VALUES ($1, 'PENDING_PAYMENT', $2, $3, $4, $5, $6, $7, $8, $8) RETURNING id`,
          [
            d.userId,
            p.subtotalPaise,
            p.discountPaise,
            p.shippingPaise,
            p.totalPaise,
            d.couponCode,
            d.idempotencyKey,
            d.at,
          ],
        );
        const orderId = num(rows[0].id);
        for (const [i, line] of d.lines.entries()) {
          await db.query(
            `INSERT INTO order_items (order_id, line_no, product_id, sku, name, unit_price_paise, quantity)
             VALUES ($1, $2, $3, $4, $5, $6, $7)`,
            [orderId, i + 1, line.productId, line.sku, line.name, line.unitPricePaise, line.quantity],
          );
        }
        await db.query(
          `INSERT INTO payments (id, order_id, provider, status, amount_paise, created_at, updated_at)
           VALUES ($1, $2, $3, 'PENDING', $4, $5, $5)`,
          [d.paymentId, orderId, PAYMENT_PROVIDER, p.totalPaise, d.at],
        );
        await db.query(
          "INSERT INTO outbox_events (type, order_id, payload, created_at) VALUES ('order.placed', $1, $2, $3)",
          [orderId, orderEventPayload(d.userId, orderId, "PENDING_PAYMENT", p.totalPaise), d.at],
        );
        await db.query("DELETE FROM cart_items WHERE user_id = $1", [d.userId]);
        await db.query("UPDATE carts SET coupon_code = NULL, updated_at = $1 WHERE user_id = $2", [
          d.at,
          d.userId,
        ]);
        const order = await db.query(`${ORDER_SELECT} WHERE o.id = $1`, [orderId]);
        return toOrder(order.rows[0]);
      });
    } catch (err) {
      if (isUniqueViolation(err)) throw new DuplicateError("idempotency_key");
      throw err;
    }
  }

  async findOrder(id: number) {
    const row = await this.one(`${ORDER_SELECT} WHERE o.id = $1`, [id]);
    return row ? toOrder(row) : null;
  }

  async ordersForUser(userId: number, limit: number) {
    return (
      await this.all(`${ORDER_SELECT} WHERE o.user_id = $1 ORDER BY o.id DESC LIMIT $2`, [userId, limit])
    ).map(toOrder);
  }

  async ordersByStatus(status: OrderStatus | null, limit: number) {
    const rows =
      status === null
        ? await this.all(`${ORDER_SELECT} ORDER BY o.id DESC LIMIT $1`, [limit])
        : await this.all(`${ORDER_SELECT} WHERE o.status = $1 ORDER BY o.id DESC LIMIT $2`, [status, limit]);
    return rows.map(toOrder);
  }

  async findPayment(paymentId: string) {
    const row = await this.one(
      "SELECT id, order_id, provider, status, amount_paise, created_at, updated_at FROM payments WHERE id = $1",
      [paymentId],
    );
    return row ? toPayment(row) : null;
  }

  async hasPaymentEvent(eventId: string) {
    return (await this.one("SELECT 1 AS x FROM payment_events WHERE event_id = $1", [eventId])) !== null;
  }

  /** ONE transaction. Throwing inside it rolls everything back. */
  async applyTransition(t: Transition): Promise<TransitionResult> {
    try {
      await inTransaction(this.pool, async (db) => {
        if (t.webhookEventId) {
          const r = await db.query(
            `INSERT INTO payment_events (event_id, payment_id, type, received_at) VALUES ($1, $2, $3, $4)
             ON CONFLICT (event_id) DO NOTHING`,
            [t.webhookEventId, t.paymentId, t.webhookEventType, t.at],
          );
          if (r.rowCount === 0) throw new Rollback("duplicate");
        }
        if (t.paymentStatus) {
          const r = await db.query(
            "UPDATE payments SET status = $1, updated_at = $2 WHERE id = $3 AND status = 'PENDING'",
            [t.paymentStatus, t.at, t.paymentId],
          );
          if (r.rowCount !== 1) throw new Rollback("conflict");
        }
        if (t.toStatus) {
          // Compare-and-set on the status: two admins (or an admin and a webhook) cannot both win.
          const r = await db.query(
            "UPDATE orders SET status = $1, updated_at = $2 WHERE id = $3 AND status = $4",
            [t.toStatus, t.at, t.orderId, t.fromStatus],
          );
          if (r.rowCount !== 1) throw new Rollback("conflict");
        }
        if (t.restock) {
          await db.query(
            `UPDATE products p SET stock = p.stock + i.quantity, updated_at = $1
             FROM order_items i WHERE i.order_id = $2 AND p.id = i.product_id`,
            [t.at, t.orderId],
          );
        }
        for (const e of t.events ?? []) {
          await db.query(
            "INSERT INTO outbox_events (type, order_id, payload, created_at) VALUES ($1, $2, $3, $4)",
            [e.type, t.orderId, e.payload, t.at],
          );
        }
      });
    } catch (err) {
      if (err instanceof Rollback) return err.result;
      throw err;
    }
    return "ok";
  }

  async outbox(pendingOnly: boolean, limit: number) {
    const where = pendingOnly ? "WHERE published_at IS NULL " : "";
    const rows = await this.all(
      `SELECT id, type, order_id, payload, created_at, published_at FROM outbox_events ${where}ORDER BY id LIMIT $1`,
      [limit],
    );
    return rows.map(toEvent);
  }

  async markPublished(eventId: number, at: Date) {
    await this.pool.query(
      "UPDATE outbox_events SET published_at = $1 WHERE id = $2 AND published_at IS NULL",
      [at, eventId],
    );
  }
}

// ---------------------------------------------------------------------------------------------
// In-memory (unit tests): one object implements every port, like one database holds all tables.
// JavaScript is single-threaded and nothing below awaits between its checks and its writes, so each
// method is atomic, like a transaction.
// ---------------------------------------------------------------------------------------------
const words = (text: string) => new Set(text.toLowerCase().match(/[a-z0-9]+/g) ?? []);
const copy = <T>(x: T): T => structuredClone(x);

export class InMemoryStore implements UserRepository, ProductRepository, CartRepository, OrderRepository {
  readonly users = new Map<number, User>();
  readonly products = new Map<number, Product>();
  readonly coupons = new Map<string, Coupon>(DEMO_COUPONS.map((c) => [c.code, { ...c }]));
  readonly cartItems = new Map<number, Map<number, number>>(); // user -> product -> quantity (insertion order)
  readonly cartCoupons = new Map<number, string | null>();
  readonly orders = new Map<number, Order>();
  readonly payments = new Map<string, Payment>();
  readonly paymentEvents = new Set<string>();
  readonly events: OutboxEvent[] = [];
  private readonly ids = { user: 0, product: 0, order: 0, event: 0 };
  healthy = true;

  private next(kind: keyof InMemoryStore["ids"]): number {
    return ++this.ids[kind];
  }

  // ---- users ----
  async insertUser(email: string, passwordHash: string, displayName: string, role: Role, at: Date) {
    if ([...this.users.values()].some((u) => u.email === email)) throw new DuplicateError("email");
    const user: User = { id: this.next("user"), email, passwordHash, displayName, role, createdAt: at };
    this.users.set(user.id, user);
    return copy(user);
  }

  async findUserByEmail(email: string) {
    const found = [...this.users.values()].find((u) => u.email === email);
    return found ? copy(found) : null;
  }

  async findUser(id: number) {
    const found = this.users.get(id);
    return found ? copy(found) : null;
  }

  async updateRole(id: number, role: Role) {
    const user = this.users.get(id);
    if (!user) return null;
    user.role = role;
    return copy(user);
  }

  async ping() {
    return this.healthy;
  }

  // ---- products ----
  async insertProduct(product: Product) {
    if ([...this.products.values()].some((p) => p.sku === product.sku)) throw new DuplicateError("sku");
    const saved = { ...product, id: this.next("product"), version: 1 };
    this.products.set(saved.id, saved);
    return copy(saved);
  }

  async findProduct(sku: string) {
    const found = [...this.products.values()].find((p) => p.sku === sku);
    return found ? copy(found) : null;
  }

  async updateProduct(product: Product, expectedVersion: number) {
    const current = this.products.get(product.id);
    if (!current || current.version !== expectedVersion) return null;
    const saved = { ...product, stock: current.stock, version: current.version + 1 };
    this.products.set(product.id, saved);
    return copy(saved);
  }

  async adjustStock(productId: number, delta: number, at: Date) {
    const current = this.products.get(productId);
    if (!current || current.stock + delta < 0) return null;
    current.stock += delta;
    current.updatedAt = at;
    return copy(current);
  }

  async searchProducts(q: ProductQuery) {
    const wanted = q.q ? words(q.q) : new Set<string>();
    const out: Product[] = [];
    for (const p of [...this.products.values()].sort((a, b) => a.id - b.id)) {
      if (!p.active || (q.afterId != null && p.id <= q.afterId)) continue;
      const have = words(`${p.name} ${p.description}`);
      if ([...wanted].some((w) => !have.has(w))) continue;
      if (q.category && p.category !== q.category) continue;
      if (q.minPricePaise != null && p.pricePaise < q.minPricePaise) continue;
      if (q.maxPricePaise != null && p.pricePaise > q.maxPricePaise) continue;
      if (q.inStock != null && p.stock > 0 !== q.inStock) continue;
      out.push(copy(p));
      if (out.length === q.limit) break;
    }
    return out;
  }

  async categories() {
    const counts = new Map<string, number>();
    for (const p of this.products.values())
      if (p.active) counts.set(p.category, (counts.get(p.category) ?? 0) + 1);
    return [...counts.keys()].sort().map((name) => ({ name, products: counts.get(name)! }));
  }

  // ---- carts ----
  async cart(userId: number) {
    const items = this.cartItems.get(userId) ?? new Map<number, number>();
    return {
      lines: [...items.entries()].map(([pid, quantity]) => ({
        product: copy(this.products.get(pid)!),
        quantity,
      })),
      couponCode: this.cartCoupons.get(userId) ?? null,
    };
  }

  async setCartItem(userId: number, productId: number, quantity: number) {
    if (!this.cartItems.has(userId)) this.cartItems.set(userId, new Map());
    this.cartItems.get(userId)!.set(productId, quantity);
  }

  async removeCartItem(userId: number, productId: number) {
    this.cartItems.get(userId)?.delete(productId);
  }

  async setCoupon(userId: number, code: string | null) {
    this.cartCoupons.set(userId, code);
  }

  async findCoupon(code: string) {
    const found = this.coupons.get(code);
    return found ? { ...found } : null;
  }

  // ---- orders, payments, outbox ----
  private copyOrder(order: Order): Order {
    return { ...copy(order), payment: copy(this.payments.get(order.payment.id)!) };
  }

  async findOrderByKey(userId: number, key: string) {
    const found = [...this.orders.values()].find((o) => o.userId === userId && o.idempotencyKey === key);
    return found ? this.copyOrder(found) : null;
  }

  async placeOrder(d: OrderDraft) {
    if (
      [...this.orders.values()].some((o) => o.userId === d.userId && o.idempotencyKey === d.idempotencyKey)
    ) {
      throw new DuplicateError("idempotency_key");
    }
    for (const line of d.lines) {
      // check everything first: all or nothing, like a transaction
      const p = this.products.get(line.productId);
      if (!p || !p.active || p.stock < line.quantity) throw new InsufficientStockError(line.sku);
    }
    for (const line of d.lines) {
      const p = this.products.get(line.productId)!;
      p.stock -= line.quantity;
      p.updatedAt = d.at;
    }
    const id = this.next("order");
    const p = d.pricing;
    const payment: Payment = {
      id: d.paymentId,
      orderId: id,
      provider: PAYMENT_PROVIDER,
      status: "PENDING",
      amountPaise: p.totalPaise,
      createdAt: d.at,
      updatedAt: d.at,
    };
    const order: Order = {
      id,
      userId: d.userId,
      status: "PENDING_PAYMENT",
      lines: copy(d.lines),
      subtotalPaise: p.subtotalPaise,
      discountPaise: p.discountPaise,
      shippingPaise: p.shippingPaise,
      totalPaise: p.totalPaise,
      couponCode: d.couponCode,
      idempotencyKey: d.idempotencyKey,
      payment,
      createdAt: d.at,
      updatedAt: d.at,
    };
    this.payments.set(payment.id, payment);
    this.orders.set(id, order);
    this.event("order.placed", id, orderEventPayload(d.userId, id, "PENDING_PAYMENT", p.totalPaise), d.at);
    this.cartItems.delete(d.userId);
    this.cartCoupons.delete(d.userId);
    return this.copyOrder(order);
  }

  private event(type: string, orderId: number, payload: Record<string, unknown>, at: Date) {
    this.events.push({
      id: this.next("event"),
      type,
      orderId,
      payload: copy(payload),
      createdAt: at,
      publishedAt: null,
    });
  }

  async findOrder(id: number) {
    const found = this.orders.get(id);
    return found ? this.copyOrder(found) : null;
  }

  async ordersForUser(userId: number, limit: number) {
    return [...this.orders.values()]
      .filter((o) => o.userId === userId)
      .sort((a, b) => b.id - a.id)
      .slice(0, limit)
      .map((o) => this.copyOrder(o));
  }

  async ordersByStatus(status: OrderStatus | null, limit: number) {
    return [...this.orders.values()]
      .filter((o) => status === null || o.status === status)
      .sort((a, b) => b.id - a.id)
      .slice(0, limit)
      .map((o) => this.copyOrder(o));
  }

  async findPayment(paymentId: string) {
    const found = this.payments.get(paymentId);
    return found ? copy(found) : null;
  }

  async hasPaymentEvent(eventId: string) {
    return this.paymentEvents.has(eventId);
  }

  async applyTransition(t: Transition): Promise<TransitionResult> {
    if (t.webhookEventId && this.paymentEvents.has(t.webhookEventId)) return "duplicate";
    const order = this.orders.get(t.orderId)!;
    if (t.paymentStatus && this.payments.get(t.paymentId!)!.status !== "PENDING") return "conflict";
    if (t.toStatus && order.status !== t.fromStatus) return "conflict";
    // every check passed: now write (all or nothing)
    if (t.webhookEventId) this.paymentEvents.add(t.webhookEventId);
    if (t.paymentStatus) {
      const payment = this.payments.get(t.paymentId!)!;
      payment.status = t.paymentStatus;
      payment.updatedAt = t.at;
    }
    if (t.toStatus) {
      order.status = t.toStatus;
      order.updatedAt = t.at;
    }
    if (t.restock) for (const line of order.lines) this.products.get(line.productId)!.stock += line.quantity;
    for (const e of t.events ?? []) this.event(e.type, t.orderId, e.payload, t.at);
    return "ok";
  }

  async outbox(pendingOnly: boolean, limit: number) {
    return this.events
      .filter((e) => !pendingOnly || e.publishedAt === null)
      .slice(0, limit)
      .map(copy);
  }

  async markPublished(eventId: number, at: Date) {
    const e = this.events.find((x) => x.id === eventId);
    if (e && e.publishedAt === null) e.publishedAt = at;
  }
}
