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

/** Starter helper: marks code you still have to write (returns `never`, so types still check). */
const todo = (what: string): never => {
  throw new Error(what);
};

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
export const USER_COLS = "id, email, password_hash, display_name, role, created_at";
export const PRODUCT_COLS =
  "id, sku, name, description, category, price_paise, stock, active, version, created_at, updated_at";
export const ORDER_SELECT = `
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
export const toUser = (r: Row): User => ({
  id: num(r.id),
  email: r.email as string,
  passwordHash: r.password_hash as string,
  displayName: r.display_name as string,
  role: r.role as Role,
  createdAt: r.created_at as Date,
});
export const toProduct = (r: Row): Product => ({
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
export const toPayment = (r: Row): Payment => ({
  id: r.id as string,
  orderId: num(r.order_id),
  provider: r.provider as string,
  status: r.status as Payment["status"],
  amountPaise: num(r.amount_paise),
  createdAt: r.created_at as Date,
  updatedAt: r.updated_at as Date,
});
export const toOrder = (r: Row): Order => ({
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
export const toEvent = (r: Row): OutboxEvent => ({
  id: num(r.id),
  type: r.type as string,
  orderId: num(r.order_id),
  payload: r.payload as Record<string, unknown>,
  createdAt: r.created_at as Date,
  publishedAt: (r.published_at as Date | null) ?? null,
});

export const isUniqueViolation = (err: unknown) => (err as { code?: string }).code === "23505";

export class Rollback extends Error {
  constructor(readonly result: TransitionResult) {
    super(result);
  }
}

export async function inTransaction<T>(
  pool: pg.Pool,
  work: (client: pg.PoolClient) => Promise<T>,
): Promise<T> {
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
    // TODO(phase-3 M2): INSERT ... RETURNING; a unique violation on email (SQLSTATE 23505) -> DuplicateError.
    return todo("TODO(phase-3 M2): PgUserRepository.insertUser");
  }

  async findUserByEmail(email: string) {
    // TODO(phase-3 M2): SELECT by email (already lower-cased).
    return todo("TODO(phase-3 M2): PgUserRepository.findUserByEmail");
  }

  async findUser(id: number) {
    // TODO(phase-3 M2): SELECT by id.
    return todo("TODO(phase-3 M2): PgUserRepository.findUser");
  }

  async updateRole(id: number, role: Role) {
    // TODO(phase-3 M2): UPDATE ... RETURNING; null when the user does not exist.
    return todo("TODO(phase-3 M2): PgUserRepository.updateRole");
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
    // TODO(phase-3 M3): INSERT ... RETURNING; unique violation on sku -> DuplicateError.
    return todo("TODO(phase-3 M3): PgProductRepository.insertProduct");
  }

  async findProduct(sku: string) {
    // TODO(phase-3 M3): SELECT by sku (active or not: the service decides who may see it).
    return todo("TODO(phase-3 M3): PgProductRepository.findProduct");
  }

  async updateProduct(p: Product, expectedVersion: number) {
    // TODO(phase-3 M3): UPDATE ... SET ..., version = version + 1 WHERE id = ? AND version = ? RETURNING; 0
    //   rows -> null (conflict).
    return todo("TODO(phase-3 M3): PgProductRepository.updateProduct");
  }

  async adjustStock(productId: number, delta: number, at: Date) {
    // TODO(phase-3 M3): ONE statement: UPDATE products SET stock = stock + delta WHERE id = ? AND stock +
    //   delta >= 0 RETURNING; 0 rows -> null. Never read-then-write (ADR-0002).
    return todo("TODO(phase-3 M3): PgProductRepository.adjustStock");
  }

  async searchProducts(q: ProductQuery) {
    // TODO(phase-3 M3): active products WHERE id > after_id [AND search_vector @@ plainto_tsquery('english',
    //   q)] [AND category] [AND price range] [AND stock > 0 / stock = 0 for inStock true/false] ORDER BY id
    //   LIMIT n.
    return todo("TODO(phase-3 M3): PgProductRepository.searchProducts");
  }

  async categories() {
    // TODO(phase-3 M3): SELECT category, COUNT(*) FROM products WHERE active GROUP BY category ORDER BY
    //   category.
    return todo("TODO(phase-3 M3): PgProductRepository.categories");
  }
}

export class PgCartRepository extends Pg implements CartRepository {
  async cart(userId: number) {
    // TODO(phase-3 M4): the user's cart lines joined with their products (ORDER BY added_at, product id) and
    //   the cart's coupon code; no cart row -> empty.
    return todo("TODO(phase-3 M4): PgCartRepository.cart");
  }

  async setCartItem(userId: number, productId: number, quantity: number, at: Date) {
    // TODO(phase-3 M4): upsert the cart row, then INSERT ... ON CONFLICT (user_id, product_id) DO UPDATE SET
    //   quantity = EXCLUDED.quantity.
    return todo("TODO(phase-3 M4): PgCartRepository.setCartItem");
  }

  async removeCartItem(userId: number, productId: number) {
    // TODO(phase-3 M4): DELETE the line (no error when it is not there).
    return todo("TODO(phase-3 M4): PgCartRepository.removeCartItem");
  }

  async setCoupon(userId: number, code: string | null, at: Date) {
    // TODO(phase-3 M4): upsert the cart row with the coupon code (null clears it).
    return todo("TODO(phase-3 M4): PgCartRepository.setCoupon");
  }

  async findCoupon(code: string) {
    // TODO(phase-3 M4): SELECT the coupon by code (the service checks active).
    return todo("TODO(phase-3 M4): PgCartRepository.findCoupon");
  }
}

export class PgOrderRepository extends Pg implements OrderRepository {
  async findOrderByKey(userId: number, key: string) {
    // TODO(phase-3 M5): the order with this (user id, idempotency key), or null.
    return todo("TODO(phase-3 M5): PgOrderRepository.findOrderByKey");
  }

  /** ONE transaction: stock, order, lines, payment, outbox, empty cart. Any failure -> nothing written. */
  async placeOrder(d: OrderDraft) {
    // TODO(phase-3 M5): ONE transaction: for every line a GUARDED stock decrement (WHERE id = ? AND active
    //   AND stock >= qty; 0 rows -> InsufficientStock + rollback), INSERT the order (unique (user, key) ->
    //   DuplicateError), its lines, the PENDING payment, an order.placed outbox event, and empty the cart.
    return todo("TODO(phase-3 M5): PgOrderRepository.placeOrder");
  }

  async findOrder(id: number) {
    // TODO(phase-3 M5): the order by id with its lines, or null.
    return todo("TODO(phase-3 M5): PgOrderRepository.findOrder");
  }

  async ordersForUser(userId: number, limit: number) {
    // TODO(phase-3 M5): the user's orders, newest first (ORDER BY id DESC), LIMIT n.
    return todo("TODO(phase-3 M5): PgOrderRepository.ordersForUser");
  }

  async ordersByStatus(status: OrderStatus | null, limit: number) {
    // TODO(phase-3 M5): all orders [with this status], newest first, LIMIT n.
    return todo("TODO(phase-3 M5): PgOrderRepository.ordersByStatus");
  }

  async findPayment(paymentId: string) {
    // TODO(phase-3 M5): the payment by its provider id, or null.
    return todo("TODO(phase-3 M5): PgOrderRepository.findPayment");
  }

  async hasPaymentEvent(eventId: string) {
    // TODO(phase-3 M5): true when this webhook event id was already recorded.
    return todo("TODO(phase-3 M5): PgOrderRepository.hasPaymentEvent");
  }

  /** ONE transaction. Throwing inside it rolls everything back. */
  async applyTransition(t: Transition): Promise<TransitionResult> {
    // TODO(phase-3 M5): ONE transaction: record the webhook event if any (unique id -> 'duplicate'), compare-
    //   and-set the order status (UPDATE ... WHERE id AND status = from; 0 rows -> 'conflict' + rollback),
    //   update the payment, restock, insert the outbox events. 'ok' when it all committed.
    throw new Error("TODO(phase-3 M5): PgOrderRepository.applyTransition");
  }

  async outbox(pendingOnly: boolean, limit: number) {
    // TODO(phase-3 M5): outbox events [only unpublished] ORDER BY id LIMIT n.
    return todo("TODO(phase-3 M5): PgOrderRepository.outbox");
  }

  async markPublished(eventId: number, at: Date) {
    // TODO(phase-3 M5): set published_at on the event (only if it is still null).
    return todo("TODO(phase-3 M5): PgOrderRepository.markPublished");
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
