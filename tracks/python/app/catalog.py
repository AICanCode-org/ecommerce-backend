"""Catalog: products, prices, stock and search. Admins write, everybody reads active products."""

from __future__ import annotations

from dataclasses import replace

from . import validation as v
from .auth import require_role
from .clock import Clock
from .errors import DuplicateError, insufficient_stock, not_found, sku_taken, validation_error, version_conflict
from .models import CategoryCount, Principal, Product, ProductQuery
from .repository import ProductRepository

EDITABLE = ("name", "description", "category", "pricePaise", "active")


def is_admin(principal: Principal | None) -> bool:
    return principal is not None and principal.role == "ADMIN"


class CatalogService:
    def __init__(self, products: ProductRepository, clock: Clock):
        self.products = products
        self._clock = clock

    def create(self, principal: Principal, payload: object) -> Product:
        require_role(principal, "ADMIN")
        data = v.require_object(payload)
        now = self._clock()
        product = Product(
            id=0,
            sku=v.sku(data.get("sku")),
            name=v.product_name(data.get("name")),
            description=v.description(data.get("description")),
            category=v.category(data.get("category")),
            price_paise=v.price_paise(data.get("pricePaise")),
            stock=v.stock(data.get("stock")),
            active=v.active(data["active"]) if data.get("active") is not None else True,
            version=1,
            created_at=now,
            updated_at=now,
        )
        try:
            return self.products.insert_product(product)
        except DuplicateError as exc:
            raise sku_taken() from exc

    def get(self, principal: Principal | None, sku: str) -> Product:
        """Inactive products are 404 for everyone but admins (they still exist for old orders)."""
        product = self.products.find_product(sku.strip().upper())
        if product is None or (not product.active and not is_admin(principal)):
            raise not_found("product")
        return product

    def update(self, principal: Principal, sku: str, payload: object) -> Product:
        require_role(principal, "ADMIN")
        data = v.require_object(payload)
        expected = v.version(data.get("version"))
        if not any(k in data for k in EDITABLE):
            raise validation_error(f"nothing to update: send at least one of {', '.join(EDITABLE)}")
        current = self.get(principal, sku)
        changed = replace(
            current,
            name=v.product_name(data["name"]) if "name" in data else current.name,
            description=v.description(data["description"]) if "description" in data else current.description,
            category=v.category(data["category"]) if "category" in data else current.category,
            price_paise=v.price_paise(data["pricePaise"]) if "pricePaise" in data else current.price_paise,
            active=v.active(data["active"]) if "active" in data else current.active,
            updated_at=self._clock(),
        )
        saved = self.products.update_product(changed, expected)
        if saved is None:
            latest = self.products.find_product(current.sku)
            raise version_conflict(latest.version if latest else current.version)
        return saved

    def adjust_stock(self, principal: Principal, sku: str, payload: object) -> Product:
        """Stock moves by a delta in ONE guarded statement. It does not bump the version (ADR-0003)."""
        require_role(principal, "ADMIN")
        delta = v.stock_delta(v.require_object(payload).get("delta"))
        current = self.get(principal, sku)
        saved = self.products.adjust_stock(current.id, delta, self._clock())
        if saved is None:
            latest = self.products.find_product(current.sku)
            have = latest.stock if latest else current.stock
            raise insufficient_stock(f"stock cannot go below 0 (current stock {have})")
        return saved

    def list(
        self,
        *,
        q: str | None = None,
        category: str | None = None,
        min_price: str | None = None,
        max_price: str | None = None,
        in_stock: str | None = None,
        limit: str | None = None,
        cursor: str | None = None,
    ) -> tuple[list[Product], str | None]:
        query = ProductQuery(
            limit=v.limit(limit),
            after_id=v.decode_cursor(cursor),
            q=v.search_query(q),
            category=v.category_filter(category),
            min_price_paise=v.price_filter(min_price, "minPricePaise"),
            max_price_paise=v.price_filter(max_price, "maxPricePaise"),
            in_stock=v.bool_filter(in_stock, "inStock"),
        )
        return self.search(query)

    def search(self, query: ProductQuery) -> tuple[list[Product], str | None]:
        lo, hi = query.min_price_paise, query.max_price_paise
        if lo is not None and hi is not None and lo > hi:
            raise validation_error("minPricePaise must not be greater than maxPricePaise")
        # Ask for one extra row: if it exists there is a next page (no COUNT(*) needed).
        rows = self.products.search_products(replace(query, limit=query.limit + 1))
        page = rows[: query.limit]
        next_cursor = v.encode_cursor(page[-1].id) if len(rows) > query.limit else None
        return page, next_cursor

    def categories(self) -> list[CategoryCount]:
        return self.products.categories()
