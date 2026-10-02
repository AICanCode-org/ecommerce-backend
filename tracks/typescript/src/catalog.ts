// Catalog: products, prices, stock and search. Admins write, everybody reads active products.
import { requireRole } from "./auth.js";
import type { Clock } from "./clock.js";
import {
  DuplicateError,
  insufficientStock,
  notFound,
  skuTaken,
  validationError,
  versionConflict,
} from "./errors.js";
import type { CategoryCount, Principal, Product, ProductQuery } from "./models.js";
import type { ProductRepository } from "./repository.js";
import * as v from "./validation.js";

export const EDITABLE = ["name", "description", "category", "pricePaise", "active"] as const;

export const isAdmin = (p: Principal | null) => p !== null && p.role === "ADMIN";

export interface ListParams {
  q?: string;
  category?: string;
  minPrice?: string;
  maxPrice?: string;
  inStock?: string;
  limit?: string;
  cursor?: string;
}

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

export class CatalogService {
  constructor(
    readonly products: ProductRepository,
    private readonly clock: Clock,
  ) {}

  async create(principal: Principal, payload: unknown): Promise<Product> {
    requireRole(principal, "ADMIN");
    const data = v.requireObject(payload);
    const now = this.clock();
    const product: Product = {
      id: 0,
      sku: v.sku(data.sku),
      name: v.productName(data.name),
      description: v.description(data.description),
      category: v.category(data.category),
      pricePaise: v.pricePaise(data.pricePaise),
      stock: v.stock(data.stock),
      active: data.active === null || data.active === undefined ? true : v.active(data.active),
      version: 1,
      createdAt: now,
      updatedAt: now,
    };
    try {
      return await this.products.insertProduct(product);
    } catch (err) {
      if (err instanceof DuplicateError) throw skuTaken();
      throw err;
    }
  }

  /** Inactive products are 404 for everyone but admins (they still exist for old orders). */
  async get(principal: Principal | null, sku: string): Promise<Product> {
    const product = await this.products.findProduct(sku.trim().toUpperCase());
    if (!product || (!product.active && !isAdmin(principal))) throw notFound("product");
    return product;
  }

  async update(principal: Principal, sku: string, payload: unknown): Promise<Product> {
    requireRole(principal, "ADMIN");
    const data = v.requireObject(payload);
    const expected = v.version(data.version);
    if (!EDITABLE.some((k) => k in data)) {
      throw validationError(`nothing to update: send at least one of ${EDITABLE.join(", ")}`);
    }
    const current = await this.get(principal, sku);
    const changed: Product = {
      ...current,
      name: "name" in data ? v.productName(data.name) : current.name,
      description: "description" in data ? v.description(data.description) : current.description,
      category: "category" in data ? v.category(data.category) : current.category,
      pricePaise: "pricePaise" in data ? v.pricePaise(data.pricePaise) : current.pricePaise,
      active: "active" in data ? v.active(data.active) : current.active,
      updatedAt: this.clock(),
    };
    const saved = await this.products.updateProduct(changed, expected);
    if (!saved) {
      const latest = await this.products.findProduct(current.sku);
      throw versionConflict(latest ? latest.version : current.version);
    }
    return saved;
  }

  /** Stock moves by a delta in ONE guarded statement. It does not bump the version (ADR-0003). */
  async adjustStock(principal: Principal, sku: string, payload: unknown): Promise<Product> {
    requireRole(principal, "ADMIN");
    const delta = v.stockDelta(v.requireObject(payload).delta);
    const current = await this.get(principal, sku);
    const saved = await this.products.adjustStock(current.id, delta, this.clock());
    if (!saved) {
      const latest = await this.products.findProduct(current.sku);
      throw insufficientStock(
        `stock cannot go below 0 (current stock ${latest ? latest.stock : current.stock})`,
      );
    }
    return saved;
  }

  async list(params: ListParams = {}): Promise<Page<Product>> {
    return this.search({
      limit: v.limit(params.limit),
      afterId: v.decodeCursor(params.cursor),
      q: v.searchQuery(params.q),
      category: v.categoryFilter(params.category),
      minPricePaise: v.priceFilter(params.minPrice, "minPricePaise"),
      maxPricePaise: v.priceFilter(params.maxPrice, "maxPricePaise"),
      inStock: v.boolFilter(params.inStock, "inStock"),
    });
  }

  async search(query: ProductQuery): Promise<Page<Product>> {
    const lo = query.minPricePaise ?? null;
    const hi = query.maxPricePaise ?? null;
    if (lo !== null && hi !== null && lo > hi) {
      throw validationError("minPricePaise must not be greater than maxPricePaise");
    }
    // Ask for one extra row: if it exists there is a next page (no COUNT(*) needed).
    const rows = await this.products.searchProducts({ ...query, limit: query.limit + 1 });
    const items = rows.slice(0, query.limit);
    const nextCursor = rows.length > query.limit ? v.encodeCursor(items[items.length - 1]!.id) : null;
    return { items, nextCursor };
  }

  categories(): Promise<CategoryCount[]> {
    return this.products.categories();
  }
}
