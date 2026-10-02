// Catalog: products, prices, stock and search. Admins write, everybody reads active products.
import type { Clock } from "./clock.js";
import type { CategoryCount, Principal, Product, ProductQuery } from "./models.js";
import type { ProductRepository } from "./repository.js";

export const EDITABLE = ["name", "description", "category", "pricePaise", "active"] as const;

export function isAdmin(p: Principal | null): boolean {
  // TODO(phase-3 M3): true when there is a principal and its role is ADMIN.
  throw new Error("TODO(phase-3 M3): isAdmin");
}

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
    // TODO(phase-3 M3): ADMIN only; validate sku, name, description, category, pricePaise, stock (active
    //   defaults to true); version 1; DuplicateError -> 409 sku-taken.
    throw new Error("TODO(phase-3 M3): CatalogService.create");
  }

  /** Inactive products are 404 for everyone but admins (they still exist for old orders). */
  async get(principal: Principal | null, sku: string): Promise<Product> {
    // TODO(phase-3 M3): find by the upper-cased sku; missing, or INACTIVE for a non-admin -> 404 not-found
    //   'product' (inactive products still exist for old orders).
    throw new Error("TODO(phase-3 M3): CatalogService.get");
  }

  async update(principal: Principal, sku: string, payload: unknown): Promise<Product> {
    // TODO(phase-3 M3): order matters (tests check it): ADMIN, object, version, nothing-to-update (400), get
    //   (404), validate the fields that are present, save WHERE version = expected; null -> 409 version-
    //   conflict with the CURRENT version (ADR-0003). Stock is not editable here.
    throw new Error("TODO(phase-3 M3): CatalogService.update");
  }

  /** Stock moves by a delta in ONE guarded statement. It does not bump the version (ADR-0003). */
  async adjustStock(principal: Principal, sku: string, payload: unknown): Promise<Product> {
    // TODO(phase-3 M3): ADMIN; validate delta; get (404); ONE guarded statement (stock + delta >= 0) in the
    //   repository; null -> 409 insufficient-stock 'stock cannot go below 0 (current stock N)'. Does not bump
    //   the version.
    throw new Error("TODO(phase-3 M3): CatalogService.adjustStock");
  }

  async list(params: ListParams = {}): Promise<Page<Product>> {
    // TODO(phase-3 M3): validate every query parameter (limit, cursor, q, category, minPricePaise,
    //   maxPricePaise, inStock) into a ProductQuery and call search().
    throw new Error("TODO(phase-3 M3): CatalogService.list");
  }

  async search(query: ProductQuery): Promise<Page<Product>> {
    // TODO(phase-3 M3): min > max -> 400; ask the repository for limit + 1 rows (active only, id > cursor,
    //   ordered by id); an extra row means nextCursor = encode_cursor(last row SHOWN).
    throw new Error("TODO(phase-3 M3): CatalogService.search");
  }

  categories(): Promise<CategoryCount[]> {
    // TODO(phase-3 M3): category counts of active products, sorted by name (straight from the repository).
    throw new Error("TODO(phase-3 M3): CatalogService.categories");
  }
}
