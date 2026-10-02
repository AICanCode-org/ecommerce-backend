// HTTP layer (Fastify). Thin: read the request, call a service, shape the JSON, map errors.
import Fastify, {
  type FastifyInstance,
  type FastifyReply,
  type FastifyRequest,
  type FastifyServerOptions,
} from "fastify";
import type { DescriptionService, SearchAssistService } from "./ai.js";
import { requireRole, type AuthService } from "./auth.js";
import type { CartService } from "./cart.js";
import type { CatalogService } from "./catalog.js";
import { iso, type Clock } from "./clock.js";
import { ApiError, notFound, validationError } from "./errors.js";
import type { CartView, LoginResult, Order, OutboxEvent, Product, SearchFilters, User } from "./models.js";
import { formatInr } from "./money.js";
import type { OrderService, OutboxService, PaymentService } from "./orders.js";
import { ACCESS_TOKEN_TTL_SECONDS } from "./security.js";
import * as v from "./validation.js";

const PROBLEM_JSON = "application/problem+json";
export const ASSIST_RESULTS = 20;

export interface AppDeps {
  auth: AuthService;
  catalog: CatalogService;
  cart: CartService;
  orders: OrderService;
  payments: PaymentService;
  outbox: OutboxService;
  descriptions: DescriptionService;
  assist: SearchAssistService;
  clock: Clock;
}

// ---- JSON shapes (identical in every track, see spec/openapi.yaml) ------------------------------
export const userJson = (u: User) => ({
  id: u.id,
  email: u.email,
  displayName: u.displayName,
  role: u.role,
  createdAt: iso(u.createdAt),
});

export const loginJson = (r: LoginResult) => ({
  accessToken: r.accessToken,
  tokenType: "Bearer",
  expiresIn: ACCESS_TOKEN_TTL_SECONDS,
  user: userJson(r.user),
});

export const productJson = (p: Product) => ({
  id: p.id,
  sku: p.sku,
  name: p.name,
  description: p.description,
  category: p.category,
  pricePaise: p.pricePaise,
  price: formatInr(p.pricePaise),
  stock: p.stock,
  inStock: p.stock > 0,
  active: p.active,
  version: p.version,
  createdAt: iso(p.createdAt),
  updatedAt: iso(p.updatedAt),
});

export const productItemJson = (p: Product) => ({
  sku: p.sku,
  name: p.name,
  category: p.category,
  pricePaise: p.pricePaise,
  price: formatInr(p.pricePaise),
  inStock: p.stock > 0,
});

export const cartJson = (c: CartView) => ({
  items: c.lines.map((l) => ({
    sku: l.product.sku,
    name: l.product.name,
    unitPricePaise: l.product.pricePaise,
    quantity: l.quantity,
    lineTotalPaise: l.product.pricePaise * l.quantity,
    available: l.product.active && l.product.stock >= l.quantity,
  })),
  couponCode: c.couponCode,
  couponApplied: c.pricing.couponApplied,
  subtotalPaise: c.pricing.subtotalPaise,
  discountPaise: c.pricing.discountPaise,
  shippingPaise: c.pricing.shippingPaise,
  totalPaise: c.pricing.totalPaise,
  total: formatInr(c.pricing.totalPaise),
});

export const orderJson = (o: Order) => ({
  id: o.id,
  userId: o.userId,
  status: o.status,
  items: o.lines.map((l) => ({
    sku: l.sku,
    name: l.name,
    unitPricePaise: l.unitPricePaise,
    quantity: l.quantity,
    lineTotalPaise: l.unitPricePaise * l.quantity,
  })),
  subtotalPaise: o.subtotalPaise,
  discountPaise: o.discountPaise,
  shippingPaise: o.shippingPaise,
  totalPaise: o.totalPaise,
  total: formatInr(o.totalPaise),
  couponCode: o.couponCode,
  payment: {
    id: o.payment.id,
    provider: o.payment.provider,
    status: o.payment.status,
    amountPaise: o.payment.amountPaise,
  },
  createdAt: iso(o.createdAt),
  updatedAt: iso(o.updatedAt),
});

export const eventJson = (e: OutboxEvent) => ({
  id: e.id,
  type: e.type,
  orderId: e.orderId,
  payload: e.payload,
  createdAt: iso(e.createdAt),
  publishedAt: iso(e.publishedAt),
});

export const filtersJson = (f: SearchFilters) => ({
  terms: f.terms,
  category: f.category,
  minPricePaise: f.minPricePaise,
  maxPricePaise: f.maxPricePaise,
  inStock: f.inStock,
});

type Params = Record<string, string>;
/** A repeated query parameter (?limit=1&limit=2) counts as its last value, like in the other tracks. */
function queryParam(req: FastifyRequest, name: string): string | undefined {
  const value = (req.query as Record<string, string | string[] | undefined>)[name];
  return Array.isArray(value) ? value[value.length - 1] : value;
}

function header(req: FastifyRequest, name: string): string | undefined {
  const value = req.headers[name];
  return Array.isArray(value) ? value[0] : value;
}

function sendProblem(reply: FastifyReply, err: ApiError) {
  if (err.slug === "unauthorized") reply.header("www-authenticate", "Bearer");
  return reply.code(err.status).type(PROBLEM_JSON).send(err.toProblem());
}

export function buildApp(deps: AppDeps, opts: FastifyServerOptions = {}): FastifyInstance {
  const app = Fastify(opts);
  const { auth, catalog, cart, orders, payments, outbox, descriptions, assist, clock } = deps;
  const principal = (req: FastifyRequest) => auth.authenticate(req.headers.authorization);

  // ---- error mapping ---------------------------------------------------
  app.setErrorHandler((err, request, reply) => {
    if (err instanceof ApiError) return sendProblem(reply, err);
    const status = (err as { statusCode?: number }).statusCode;
    if (status && status >= 400 && status < 500) {
      // Malformed JSON, empty body, unsupported media type...
      return sendProblem(reply, validationError("request body must be a JSON object"));
    }
    request.log.error(err);
    return sendProblem(reply, new ApiError(500, "internal-error", "Internal server error"));
  });
  app.setNotFoundHandler((_req, reply) => sendProblem(reply, notFound("route")));

  // ---- ops -----------------------------------------------------------------
  app.get("/health", async (_req, reply) => {
    const dbUp = await auth.users.ping();
    return reply
      .code(dbUp ? 200 : 503)
      .send({ status: dbUp ? "UP" : "DOWN", checks: { database: dbUp ? "UP" : "DOWN" } });
  });

  // ---- accounts ------------------------------------------------------------
  app.post("/api/v1/auth/register", async (req, reply) =>
    reply.code(201).send(userJson(await auth.register(req.body))),
  );
  app.post("/api/v1/auth/login", async (req) => loginJson(await auth.login(req.body)));
  app.get("/api/v1/me", async (req) => userJson(await auth.me(await principal(req))));

  // ---- catalog -------------------------------------------------------------
  app.get("/api/v1/products", async (req) => {
    const page = await catalog.list({
      q: queryParam(req, "q"),
      category: queryParam(req, "category"),
      minPrice: queryParam(req, "minPricePaise"),
      maxPrice: queryParam(req, "maxPricePaise"),
      inStock: queryParam(req, "inStock"),
      limit: queryParam(req, "limit"),
      cursor: queryParam(req, "cursor"),
    });
    return { items: page.items.map(productItemJson), nextCursor: page.nextCursor };
  });
  app.post("/api/v1/products", async (req, reply) => {
    const product = await catalog.create(await principal(req), req.body);
    return reply.code(201).header("location", `/api/v1/products/${product.sku}`).send(productJson(product));
  });
  app.get("/api/v1/products/:sku", async (req) =>
    productJson(
      await catalog.get(await auth.optional(req.headers.authorization), (req.params as Params).sku!),
    ),
  );
  app.patch("/api/v1/products/:sku", async (req) =>
    productJson(await catalog.update(await principal(req), (req.params as Params).sku!, req.body)),
  );
  app.post("/api/v1/products/:sku/stock", async (req) =>
    productJson(await catalog.adjustStock(await principal(req), (req.params as Params).sku!, req.body)),
  );
  app.get("/api/v1/categories", async () => ({ items: await catalog.categories() }));

  // ---- AI ------------------------------------------------------------------
  app.post("/api/v1/products/:sku/description-draft", async (req) => {
    const who = await principal(req);
    requireRole(who, "ADMIN");
    const body = req.body;
    const words = body === undefined || body === null ? [] : v.keywords(v.requireObject(body).keywords);
    const product = await catalog.get(who, (req.params as Params).sku!);
    const result = await descriptions.draft(product, words);
    return {
      sku: product.sku,
      provider: result.provider,
      fallback: result.fallback,
      description: result.description,
      generatedAt: iso(clock()),
    };
  });
  app.get("/api/v1/search/assist", async (req) => {
    const query = v.assistQuery(queryParam(req, "q"));
    const known = (await catalog.categories()).map((c) => c.name);
    const result = await assist.interpret(query, known);
    const f = result.filters;
    const page = await catalog.search({
      limit: ASSIST_RESULTS,
      q: f.terms.join(" ") || null,
      category: f.category,
      minPricePaise: f.minPricePaise,
      maxPricePaise: f.maxPricePaise,
      inStock: f.inStock,
    });
    return {
      query,
      provider: result.provider,
      fallback: result.fallback,
      filters: filtersJson(f),
      items: page.items.map(productItemJson),
    };
  });

  // ---- cart ----------------------------------------------------------------
  app.get("/api/v1/cart", async (req) => cartJson(await cart.view(await principal(req))));
  app.put("/api/v1/cart/items/:sku", async (req) =>
    cartJson(await cart.setItem(await principal(req), (req.params as Params).sku!, req.body)),
  );
  app.delete("/api/v1/cart/items/:sku", async (req) =>
    cartJson(await cart.removeItem(await principal(req), (req.params as Params).sku!)),
  );
  app.put("/api/v1/cart/coupon", async (req) =>
    cartJson(await cart.applyCoupon(await principal(req), req.body)),
  );
  app.delete("/api/v1/cart/coupon", async (req) => cartJson(await cart.removeCoupon(await principal(req))));

  // ---- checkout + orders ---------------------------------------------------
  app.post("/api/v1/checkout", async (req, reply) => {
    const who = await principal(req);
    const { order, replayed } = await orders.checkout(who, header(req, "idempotency-key"));
    reply.header("location", `/api/v1/orders/${order.id}`);
    if (replayed) reply.header("idempotent-replayed", "true");
    return reply.code(replayed ? 200 : 201).send(orderJson(order));
  });
  app.get("/api/v1/orders", async (req) => ({
    items: (await orders.mine(await principal(req), queryParam(req, "limit"))).map(orderJson),
  }));
  app.get("/api/v1/orders/:id", async (req) => {
    const who = await principal(req);
    return orderJson(await orders.get(who, v.pathId((req.params as Params).id!, "order")));
  });
  app.post("/api/v1/orders/:id/cancel", async (req) => {
    const who = await principal(req);
    return orderJson(await orders.cancel(who, v.pathId((req.params as Params).id!, "order")));
  });
  app.get("/api/v1/admin/orders", async (req) => ({
    items: (
      await orders.adminList(await principal(req), queryParam(req, "status"), queryParam(req, "limit"))
    ).map(orderJson),
  }));
  app.post("/api/v1/admin/orders/:id/status", async (req) => {
    const who = await principal(req);
    return orderJson(
      await orders.adminSetStatus(who, v.pathId((req.params as Params).id!, "order"), req.body),
    );
  });

  // ---- payments + outbox ---------------------------------------------------
  // The webhook needs the RAW bytes (the signature is over exactly these), so this route gets its own
  // body parser in an encapsulated scope: every other route keeps Fastify's JSON parser.
  app.register(async (scope) => {
    scope.removeAllContentTypeParsers();
    scope.addContentTypeParser("*", { parseAs: "buffer" }, (_req, body, done) => done(null, body));
    scope.post("/api/v1/payments/webhook", async (req) => {
      const body = Buffer.isBuffer(req.body) ? req.body : Buffer.alloc(0);
      return payments.handleWebhook(body, header(req, "x-signature"));
    });
  });
  app.get("/api/v1/admin/outbox", async (req) => ({
    items: (
      await outbox.list(await principal(req), queryParam(req, "pending"), queryParam(req, "limit"))
    ).map(eventJson),
  }));
  app.post("/api/v1/admin/outbox/publish", async (req) => ({
    published: await outbox.publishPending(await principal(req)),
  }));

  return app;
}
