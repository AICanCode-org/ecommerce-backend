"""Catalog: products, prices, stock and search. Admins write, everybody reads active products."""

from __future__ import annotations

from .clock import Clock
from .models import CategoryCount, Principal, Product, ProductQuery
from .repository import ProductRepository

EDITABLE = ("name", "description", "category", "pricePaise", "active")


def is_admin(principal: Principal | None) -> bool:
    # TODO(phase-3 M3): true when there is a principal and its role is ADMIN.
    raise NotImplementedError("TODO(phase-3 M3): is_admin")


class CatalogService:
    def __init__(self, products: ProductRepository, clock: Clock):
        self.products = products
        self._clock = clock

    def create(self, principal: Principal, payload: object) -> Product:
        # TODO(phase-3 M3): ADMIN only; validate sku, name, description, category, pricePaise, stock
        #   (active defaults to true); version 1; DuplicateError -> 409 sku-taken.
        raise NotImplementedError("TODO(phase-3 M3): CatalogService.create")

    def get(self, principal: Principal | None, sku: str) -> Product:
        """Inactive products are 404 for everyone but admins (they still exist for old orders)."""
        # TODO(phase-3 M3): find by the upper-cased sku; missing, or INACTIVE for a non-admin -> 404
        #   not-found 'product' (inactive products still exist for old orders).
        raise NotImplementedError("TODO(phase-3 M3): CatalogService.get")

    def update(self, principal: Principal, sku: str, payload: object) -> Product:
        # TODO(phase-3 M3): order matters (tests check it): ADMIN, object, version, nothing-to-
        #   update (400), get (404), validate the fields that are present, save WHERE version =
        #   expected; null -> 409 version-conflict with the CURRENT version (ADR-0003). Stock is not
        #   editable here.
        raise NotImplementedError("TODO(phase-3 M3): CatalogService.update")

    def adjust_stock(self, principal: Principal, sku: str, payload: object) -> Product:
        """Stock moves by a delta in ONE guarded statement. It does not bump the version (ADR-0003)."""
        # TODO(phase-3 M3): ADMIN; validate delta; get (404); ONE guarded statement (stock + delta
        #   >= 0) in the repository; null -> 409 insufficient-stock 'stock cannot go below 0 (current
        #   stock N)'. Does not bump the version.
        raise NotImplementedError("TODO(phase-3 M3): CatalogService.adjust_stock")

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
        # TODO(phase-3 M3): validate every query parameter (limit, cursor, q, category,
        #   minPricePaise, maxPricePaise, inStock) into a ProductQuery and call search().
        raise NotImplementedError("TODO(phase-3 M3): CatalogService.list")

    def search(self, query: ProductQuery) -> tuple[list[Product], str | None]:
        # TODO(phase-3 M3): min > max -> 400; ask the repository for limit + 1 rows (active only, id
        #   > cursor, ordered by id); an extra row means nextCursor = encode_cursor(last row SHOWN).
        raise NotImplementedError("TODO(phase-3 M3): CatalogService.search")

    def categories(self) -> list[CategoryCount]:
        # TODO(phase-3 M3): category counts of active products, sorted by name (straight from the
        #   repository).
        raise NotImplementedError("TODO(phase-3 M3): CatalogService.categories")
