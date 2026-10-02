"""Persistence ports (Protocols) with two adapters each: PostgreSQL and in-memory (for unit tests).

The in-memory store behaves like the database where it matters (unique e-mail/SKU/idempotency key,
version check, guarded stock decrement, all-or-nothing checkout). Full-text search is the exception:
in memory it is plain word matching, while PostgreSQL also stems ("shoe" finds "shoes").
The integration tests cover the real thing.
"""

from __future__ import annotations

import json
import re
import threading
from dataclasses import replace
from datetime import datetime
from typing import Protocol

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from .errors import DuplicateError, InsufficientStockError
from .models import (
    CartLine,
    CartState,
    CategoryCount,
    Coupon,
    Order,
    OrderDraft,
    OrderLine,
    OutboxEvent,
    Payment,
    Product,
    ProductQuery,
    Transition,
    User,
)

PAYMENT_PROVIDER = "fakepay"
DEMO_COUPONS = (
    Coupon("SAVE10", "PERCENT", 10, 0, 20_000, True),
    Coupon("FLAT100", "FLAT", 10_000, 50_000, None, True),
    Coupon("EXPIRED5", "PERCENT", 5, 0, None, False),
)


# ---------------------------------------------------------------------------------------------
# Ports
# ---------------------------------------------------------------------------------------------
class UserRepository(Protocol):
    def insert_user(self, email: str, password_hash: str, display_name: str, role: str, at: datetime) -> User: ...
    def find_user_by_email(self, email: str) -> User | None: ...
    def find_user(self, user_id: int) -> User | None: ...
    def update_role(self, user_id: int, role: str) -> User | None: ...
    def ping(self) -> bool: ...


class ProductRepository(Protocol):
    def insert_product(self, product: Product) -> Product: ...
    def find_product(self, sku: str) -> Product | None: ...
    def update_product(self, product: Product, expected_version: int) -> Product | None: ...
    def adjust_stock(self, product_id: int, delta: int, at: datetime) -> Product | None: ...
    def search_products(self, query: ProductQuery) -> list[Product]: ...
    def categories(self) -> list[CategoryCount]: ...


class CartRepository(Protocol):
    def cart(self, user_id: int) -> CartState: ...
    def set_cart_item(self, user_id: int, product_id: int, quantity: int, at: datetime) -> None: ...
    def remove_cart_item(self, user_id: int, product_id: int) -> None: ...
    def set_coupon(self, user_id: int, code: str | None, at: datetime) -> None: ...
    def find_coupon(self, code: str) -> Coupon | None: ...


class OrderRepository(Protocol):
    def find_order_by_key(self, user_id: int, key: str) -> Order | None: ...
    def place_order(self, draft: OrderDraft) -> Order: ...
    def find_order(self, order_id: int) -> Order | None: ...
    def orders_for_user(self, user_id: int, limit: int) -> list[Order]: ...
    def orders_by_status(self, status: str | None, limit: int) -> list[Order]: ...
    def find_payment(self, payment_id: str) -> Payment | None: ...
    def has_payment_event(self, event_id: str) -> bool: ...
    def apply_transition(self, t: Transition) -> str: ...  # "ok" | "duplicate" | "conflict"
    def outbox(self, pending_only: bool, limit: int) -> list[OutboxEvent]: ...
    def mark_published(self, event_id: int, at: datetime) -> None: ...


def order_event_payload(order: Order | OrderDraft, order_id: int, status: str) -> dict:
    return {"orderId": order_id, "userId": order.user_id, "status": status, "totalPaise": _total(order)}


def _total(order: Order | OrderDraft) -> int:
    return order.total_paise if isinstance(order, Order) else order.pricing.total_paise


# ---------------------------------------------------------------------------------------------
# PostgreSQL
# ---------------------------------------------------------------------------------------------
_USER_COLS = "id, email, password_hash, display_name, role, created_at"
_PRODUCT_COLS = "id, sku, name, description, category, price_paise, stock, active, version, created_at, updated_at"
_ORDER_SELECT = """
SELECT o.id, o.user_id, o.status, o.subtotal_paise, o.discount_paise, o.shipping_paise, o.total_paise,
       o.coupon_code, o.idempotency_key, o.created_at, o.updated_at,
       p.id AS payment_id, p.provider, p.status AS payment_status, p.amount_paise,
       p.created_at AS payment_created_at, p.updated_at AS payment_updated_at,
       COALESCE((SELECT json_agg(json_build_object('product_id', i.product_id, 'sku', i.sku, 'name', i.name,
                 'unit_price_paise', i.unit_price_paise, 'quantity', i.quantity) ORDER BY i.line_no)
                 FROM order_items i WHERE i.order_id = o.id), '[]') AS lines
FROM orders o JOIN payments p ON p.order_id = o.id
"""


def _order(row: dict) -> Order:
    payment = Payment(
        row["payment_id"], row["id"], row["provider"], row["payment_status"], row["amount_paise"],
        row["payment_created_at"], row["payment_updated_at"],
    )  # fmt: skip
    lines = row["lines"] if isinstance(row["lines"], list) else json.loads(row["lines"])
    return Order(
        id=row["id"], user_id=row["user_id"], status=row["status"], lines=[OrderLine(**line) for line in lines],
        subtotal_paise=row["subtotal_paise"], discount_paise=row["discount_paise"],
        shipping_paise=row["shipping_paise"], total_paise=row["total_paise"], coupon_code=row["coupon_code"],
        idempotency_key=row["idempotency_key"], payment=payment, created_at=row["created_at"],
        updated_at=row["updated_at"],
    )  # fmt: skip


class _Duplicate(Exception):
    pass


class _Conflict(Exception):
    pass


class _Pg:
    def __init__(self, pool: ConnectionPool):
        self._pool = pool

    def _one(self, sql: str, params: tuple) -> dict | None:
        with self._pool.connection() as conn, conn.cursor(row_factory=dict_row) as cur:
            cur.execute(sql, params)
            return cur.fetchone()

    def _all(self, sql: str, params: tuple | list) -> list[dict]:
        with self._pool.connection() as conn, conn.cursor(row_factory=dict_row) as cur:
            cur.execute(sql, params)
            return cur.fetchall()


class PostgresUserRepository(_Pg):
    def insert_user(self, email, password_hash, display_name, role, at) -> User:
        # TODO(phase-3 M2): INSERT ... RETURNING; a unique violation on email (SQLSTATE 23505) ->
        #   DuplicateError.
        raise NotImplementedError("TODO(phase-3 M2): PostgresUserRepository.insert_user")

    def find_user_by_email(self, email) -> User | None:
        # TODO(phase-3 M2): SELECT by email (already lower-cased).
        raise NotImplementedError("TODO(phase-3 M2): PostgresUserRepository.find_user_by_email")

    def find_user(self, user_id) -> User | None:
        # TODO(phase-3 M2): SELECT by id.
        raise NotImplementedError("TODO(phase-3 M2): PostgresUserRepository.find_user")

    def update_role(self, user_id, role) -> User | None:
        # TODO(phase-3 M2): UPDATE ... RETURNING; null when the user does not exist.
        raise NotImplementedError("TODO(phase-3 M2): PostgresUserRepository.update_role")

    def ping(self) -> bool:
        try:
            with self._pool.connection(timeout=2) as conn:
                conn.execute("SELECT 1")
            return True
        except Exception:  # noqa: BLE001 - health checks must never raise
            return False


class PostgresProductRepository(_Pg):
    def insert_product(self, p: Product) -> Product:
        # TODO(phase-3 M3): INSERT ... RETURNING; unique violation on sku -> DuplicateError.
        raise NotImplementedError("TODO(phase-3 M3): PostgresProductRepository.insert_product")

    def find_product(self, sku) -> Product | None:
        # TODO(phase-3 M3): SELECT by sku (active or not: the service decides who may see it).
        raise NotImplementedError("TODO(phase-3 M3): PostgresProductRepository.find_product")

    def update_product(self, p: Product, expected_version: int) -> Product | None:
        # Optimistic locking: the WHERE clause only matches if nobody saved in between (ADR-0003).
        # TODO(phase-3 M3): UPDATE ... SET ..., version = version + 1 WHERE id = ? AND version = ?
        #   RETURNING; 0 rows -> null (conflict).
        raise NotImplementedError("TODO(phase-3 M3): PostgresProductRepository.update_product")

    def adjust_stock(self, product_id, delta, at) -> Product | None:
        # One statement, guarded: never read-modify-write stock in application code (ADR-0002).
        # TODO(phase-3 M3): ONE statement: UPDATE products SET stock = stock + delta WHERE id = ?
        #   AND stock + delta >= 0 RETURNING; 0 rows -> null. Never read-then-write (ADR-0002).
        raise NotImplementedError("TODO(phase-3 M3): PostgresProductRepository.adjust_stock")

    def search_products(self, query: ProductQuery) -> list[Product]:
        # TODO(phase-3 M3): active products WHERE id > after_id [AND search_vector @@
        #   plainto_tsquery('english', q)] [AND category] [AND price range] [AND stock > 0 / stock = 0
        #   for inStock true/false] ORDER BY id LIMIT n.
        raise NotImplementedError("TODO(phase-3 M3): PostgresProductRepository.search_products")

    def categories(self) -> list[CategoryCount]:
        # TODO(phase-3 M3): SELECT category, COUNT(*) FROM products WHERE active GROUP BY category
        #   ORDER BY category.
        raise NotImplementedError("TODO(phase-3 M3): PostgresProductRepository.categories")


class PostgresCartRepository(_Pg):
    def cart(self, user_id) -> CartState:
        # TODO(phase-3 M4): the user's cart lines joined with their products (ORDER BY added_at,
        #   product id) and the cart's coupon code; no cart row -> empty.
        raise NotImplementedError("TODO(phase-3 M4): PostgresCartRepository.cart")

    def set_cart_item(self, user_id, product_id, quantity, at) -> None:
        # TODO(phase-3 M4): upsert the cart row, then INSERT ... ON CONFLICT (user_id, product_id)
        #   DO UPDATE SET quantity = EXCLUDED.quantity.
        raise NotImplementedError("TODO(phase-3 M4): PostgresCartRepository.set_cart_item")

    def remove_cart_item(self, user_id, product_id) -> None:
        # TODO(phase-3 M4): DELETE the line (no error when it is not there).
        raise NotImplementedError("TODO(phase-3 M4): PostgresCartRepository.remove_cart_item")

    def set_coupon(self, user_id, code, at) -> None:
        # TODO(phase-3 M4): upsert the cart row with the coupon code (null clears it).
        raise NotImplementedError("TODO(phase-3 M4): PostgresCartRepository.set_coupon")

    def find_coupon(self, code) -> Coupon | None:
        # TODO(phase-3 M4): SELECT the coupon by code (the service checks active).
        raise NotImplementedError("TODO(phase-3 M4): PostgresCartRepository.find_coupon")


class PostgresOrderRepository(_Pg):
    def find_order_by_key(self, user_id, key) -> Order | None:
        # TODO(phase-3 M5): the order with this (user id, idempotency key), or null.
        raise NotImplementedError("TODO(phase-3 M5): PostgresOrderRepository.find_order_by_key")

    def place_order(self, d: OrderDraft) -> Order:
        """ONE transaction: stock, order, lines, payment, outbox, empty cart. Any failure -> nothing written."""
        # TODO(phase-3 M5): ONE transaction: for every line a GUARDED stock decrement (WHERE id = ?
        #   AND active AND stock >= qty; 0 rows -> InsufficientStock + rollback), INSERT the order
        #   (unique (user, key) -> DuplicateError), its lines, the PENDING payment, an order.placed
        #   outbox event, and empty the cart.
        raise NotImplementedError("TODO(phase-3 M5): PostgresOrderRepository.place_order")

    def find_order(self, order_id) -> Order | None:
        # TODO(phase-3 M5): the order by id with its lines, or null.
        raise NotImplementedError("TODO(phase-3 M5): PostgresOrderRepository.find_order")

    def orders_for_user(self, user_id, limit) -> list[Order]:
        # TODO(phase-3 M5): the user's orders, newest first (ORDER BY id DESC), LIMIT n.
        raise NotImplementedError("TODO(phase-3 M5): PostgresOrderRepository.orders_for_user")

    def orders_by_status(self, status, limit) -> list[Order]:
        # TODO(phase-3 M5): all orders [with this status], newest first, LIMIT n.
        raise NotImplementedError("TODO(phase-3 M5): PostgresOrderRepository.orders_by_status")

    def find_payment(self, payment_id) -> Payment | None:
        # TODO(phase-3 M5): the payment by its provider id, or null.
        raise NotImplementedError("TODO(phase-3 M5): PostgresOrderRepository.find_payment")

    def has_payment_event(self, event_id) -> bool:
        # TODO(phase-3 M5): true when this webhook event id was already recorded.
        raise NotImplementedError("TODO(phase-3 M5): PostgresOrderRepository.has_payment_event")

    def apply_transition(self, t: Transition) -> str:
        """ONE transaction. Raising inside `conn.transaction()` rolls everything back."""
        # TODO(phase-3 M5): ONE transaction: record the webhook event if any (unique id ->
        #   'duplicate'), compare-and-set the order status (UPDATE ... WHERE id AND status = from; 0
        #   rows -> 'conflict' + rollback), update the payment, restock, insert the outbox events.
        #   'ok' when it all committed.
        raise NotImplementedError("TODO(phase-3 M5): PostgresOrderRepository.apply_transition")

    def outbox(self, pending_only, limit) -> list[OutboxEvent]:
        # TODO(phase-3 M5): outbox events [only unpublished] ORDER BY id LIMIT n.
        raise NotImplementedError("TODO(phase-3 M5): PostgresOrderRepository.outbox")

    def mark_published(self, event_id, at) -> None:
        # TODO(phase-3 M5): set published_at on the event (only if it is still null).
        raise NotImplementedError("TODO(phase-3 M5): PostgresOrderRepository.mark_published")


# ---------------------------------------------------------------------------------------------
# In-memory (unit tests): one object implements every port, like one database holds all tables.
# ---------------------------------------------------------------------------------------------
_WORD = re.compile(r"[a-z0-9]+")


def _words(text: str) -> set[str]:
    return set(_WORD.findall(text.lower()))


class InMemoryStore:
    def __init__(self):
        self._lock = threading.RLock()
        self.users: dict[int, User] = {}
        self.products: dict[int, Product] = {}
        self.coupons: dict[str, Coupon] = {c.code: c for c in DEMO_COUPONS}
        self.cart_items: dict[int, dict[int, int]] = {}  # user id -> product id -> quantity (insertion order)
        self.cart_coupons: dict[int, str | None] = {}
        self.orders: dict[int, Order] = {}
        self.payments: dict[str, Payment] = {}
        self.payment_events: set[str] = set()
        self.events: list[OutboxEvent] = []
        self._ids = {"user": 0, "product": 0, "order": 0, "event": 0}
        self.healthy = True

    def _next(self, kind: str) -> int:
        self._ids[kind] += 1
        return self._ids[kind]

    # ---- users ----
    def insert_user(self, email, password_hash, display_name, role, at) -> User:
        with self._lock:
            if any(u.email == email for u in self.users.values()):
                raise DuplicateError("email")
            user = User(self._next("user"), email, password_hash, display_name, role, at)
            self.users[user.id] = user
            return replace(user)

    def find_user_by_email(self, email) -> User | None:
        found = next((u for u in self.users.values() if u.email == email), None)
        return replace(found) if found else None

    def find_user(self, user_id) -> User | None:
        found = self.users.get(user_id)
        return replace(found) if found else None

    def update_role(self, user_id, role) -> User | None:
        with self._lock:
            if user_id not in self.users:
                return None
            self.users[user_id].role = role
            return replace(self.users[user_id])

    def ping(self) -> bool:
        return self.healthy

    # ---- products ----
    def insert_product(self, product: Product) -> Product:
        with self._lock:
            if any(p.sku == product.sku for p in self.products.values()):
                raise DuplicateError("sku")
            saved = replace(product, id=self._next("product"), version=1)
            self.products[saved.id] = saved
            return replace(saved)

    def find_product(self, sku) -> Product | None:
        found = next((p for p in self.products.values() if p.sku == sku), None)
        return replace(found) if found else None

    def update_product(self, product: Product, expected_version: int) -> Product | None:
        with self._lock:
            current = self.products.get(product.id)
            if current is None or current.version != expected_version:
                return None
            saved = replace(product, stock=current.stock, version=current.version + 1)
            self.products[product.id] = saved
            return replace(saved)

    def adjust_stock(self, product_id, delta, at) -> Product | None:
        with self._lock:
            current = self.products.get(product_id)
            if current is None or current.stock + delta < 0:
                return None
            current.stock += delta
            current.updated_at = at
            return replace(current)

    def search_products(self, query: ProductQuery) -> list[Product]:
        wanted = _words(query.q) if query.q else set()
        out = []
        for p in sorted(self.products.values(), key=lambda x: x.id):
            if not p.active or (query.after_id is not None and p.id <= query.after_id):
                continue
            if wanted and not wanted <= _words(p.name + " " + p.description):
                continue
            if query.category and p.category != query.category:
                continue
            if query.min_price_paise is not None and p.price_paise < query.min_price_paise:
                continue
            if query.max_price_paise is not None and p.price_paise > query.max_price_paise:
                continue
            if query.in_stock is not None and (p.stock > 0) != query.in_stock:
                continue
            out.append(replace(p))
            if len(out) == query.limit:
                break
        return out

    def categories(self) -> list[CategoryCount]:
        counts: dict[str, int] = {}
        for p in self.products.values():
            if p.active:
                counts[p.category] = counts.get(p.category, 0) + 1
        return [CategoryCount(name, counts[name]) for name in sorted(counts)]

    # ---- carts ----
    def cart(self, user_id) -> CartState:
        items = self.cart_items.get(user_id, {})
        lines = [CartLine(replace(self.products[pid]), qty) for pid, qty in items.items()]
        return CartState(lines, self.cart_coupons.get(user_id))

    def set_cart_item(self, user_id, product_id, quantity, at) -> None:
        with self._lock:
            self.cart_items.setdefault(user_id, {})[product_id] = quantity

    def remove_cart_item(self, user_id, product_id) -> None:
        with self._lock:
            self.cart_items.get(user_id, {}).pop(product_id, None)

    def set_coupon(self, user_id, code, at) -> None:
        with self._lock:
            self.cart_coupons[user_id] = code

    def find_coupon(self, code) -> Coupon | None:
        return self.coupons.get(code)

    # ---- orders, payments, outbox ----
    def _copy(self, order: Order) -> Order:
        return replace(order, lines=list(order.lines), payment=replace(self.payments[order.payment.id]))

    def find_order_by_key(self, user_id, key) -> Order | None:
        found = next((o for o in self.orders.values() if o.user_id == user_id and o.idempotency_key == key), None)
        return self._copy(found) if found else None

    def place_order(self, d: OrderDraft) -> Order:
        with self._lock:
            if self.find_order_by_key(d.user_id, d.idempotency_key) is not None:
                raise DuplicateError("idempotency_key")
            for line in d.lines:  # check everything first: all or nothing, like a transaction
                product = self.products.get(line.product_id)
                if product is None or not product.active or product.stock < line.quantity:
                    raise InsufficientStockError(line.sku)
            for line in d.lines:
                self.products[line.product_id].stock -= line.quantity
                self.products[line.product_id].updated_at = d.at
            order_id = self._next("order")
            payment = Payment(d.payment_id, order_id, PAYMENT_PROVIDER, "PENDING", d.pricing.total_paise, d.at, d.at)
            p = d.pricing
            order = Order(
                order_id, d.user_id, "PENDING_PAYMENT", list(d.lines), p.subtotal_paise, p.discount_paise,
                p.shipping_paise, p.total_paise, d.coupon_code, d.idempotency_key, payment, d.at, d.at,
            )  # fmt: skip
            self.payments[payment.id] = payment
            self.orders[order_id] = order
            self._event("order.placed", order_id, order_event_payload(d, order_id, "PENDING_PAYMENT"), d.at)
            self.cart_items.pop(d.user_id, None)
            self.cart_coupons.pop(d.user_id, None)
            return self._copy(order)

    def _event(self, type_: str, order_id: int, payload: dict, at: datetime) -> None:
        self.events.append(OutboxEvent(self._next("event"), type_, order_id, dict(payload), at, None))

    def find_order(self, order_id) -> Order | None:
        found = self.orders.get(order_id)
        return self._copy(found) if found else None

    def orders_for_user(self, user_id, limit) -> list[Order]:
        mine = [o for o in self.orders.values() if o.user_id == user_id]
        return [self._copy(o) for o in sorted(mine, key=lambda o: -o.id)[:limit]]

    def orders_by_status(self, status, limit) -> list[Order]:
        found = [o for o in self.orders.values() if status is None or o.status == status]
        return [self._copy(o) for o in sorted(found, key=lambda o: -o.id)[:limit]]

    def find_payment(self, payment_id) -> Payment | None:
        found = self.payments.get(payment_id)
        return replace(found) if found else None

    def has_payment_event(self, event_id) -> bool:
        return event_id in self.payment_events

    def apply_transition(self, t: Transition) -> str:
        with self._lock:
            if t.webhook_event_id is not None and t.webhook_event_id in self.payment_events:
                return "duplicate"
            order = self.orders[t.order_id]
            if t.payment_status is not None and self.payments[t.payment_id].status != "PENDING":  # type: ignore[index]
                return "conflict"
            if t.to_status is not None and order.status != t.from_status:
                return "conflict"
            # every check passed: now write (all or nothing)
            if t.webhook_event_id is not None:
                self.payment_events.add(t.webhook_event_id)
            if t.payment_status is not None:
                payment = self.payments[t.payment_id]  # type: ignore[index]
                payment.status, payment.updated_at = t.payment_status, t.at
            if t.to_status is not None:
                order.status, order.updated_at = t.to_status, t.at
            if t.restock:
                for line in order.lines:
                    self.products[line.product_id].stock += line.quantity
            for e in t.events:
                self._event(e.type, t.order_id, e.payload, t.at)
            return "ok"

    def outbox(self, pending_only, limit) -> list[OutboxEvent]:
        found = [e for e in self.events if not pending_only or e.published_at is None]
        return [replace(e) for e in found[:limit]]

    def mark_published(self, event_id, at) -> None:
        with self._lock:
            for e in self.events:
                if e.id == event_id and e.published_at is None:
                    e.published_at = at
