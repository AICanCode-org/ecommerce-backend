"""Plain data classes. No framework types leak into the domain."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime

ROLES = ("CUSTOMER", "ADMIN")
ORDER_STATUSES = ("PENDING_PAYMENT", "PAID", "SHIPPED", "DELIVERED", "CANCELLED")
PAYMENT_STATUSES = ("PENDING", "SUCCEEDED", "FAILED")


@dataclass
class User:
    id: int
    email: str
    password_hash: str
    display_name: str
    role: str
    created_at: datetime


@dataclass(frozen=True)
class Principal:
    """Who is calling, taken from a verified access token."""

    user_id: int
    role: str


@dataclass(frozen=True)
class LoginResult:
    access_token: str
    user: User


@dataclass
class Product:
    id: int
    sku: str
    name: str
    description: str
    category: str
    price_paise: int
    stock: int
    active: bool
    version: int
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True)
class ProductQuery:
    """Catalog filters. Only active products are ever listed. Ordered by id; after_id is the keyset cursor."""

    limit: int
    after_id: int | None = None
    q: str | None = None
    category: str | None = None
    min_price_paise: int | None = None
    max_price_paise: int | None = None
    in_stock: bool | None = None


@dataclass(frozen=True)
class CategoryCount:
    name: str
    products: int


@dataclass(frozen=True)
class Coupon:
    code: str
    kind: str  # PERCENT | FLAT
    value: int
    min_subtotal_paise: int
    max_discount_paise: int | None
    active: bool


@dataclass(frozen=True)
class CartLine:
    product: Product
    quantity: int


@dataclass(frozen=True)
class CartState:
    lines: list[CartLine]
    coupon_code: str | None


@dataclass(frozen=True)
class Pricing:
    subtotal_paise: int
    discount_paise: int
    shipping_paise: int
    total_paise: int
    coupon_applied: bool


@dataclass(frozen=True)
class CartView:
    lines: list[CartLine]
    coupon_code: str | None
    pricing: Pricing


@dataclass(frozen=True)
class OrderLine:
    product_id: int
    sku: str
    name: str
    unit_price_paise: int
    quantity: int


@dataclass
class Payment:
    id: str
    order_id: int
    provider: str
    status: str
    amount_paise: int
    created_at: datetime
    updated_at: datetime


@dataclass
class Order:
    id: int
    user_id: int
    status: str
    lines: list[OrderLine]
    subtotal_paise: int
    discount_paise: int
    shipping_paise: int
    total_paise: int
    coupon_code: str | None
    idempotency_key: str
    payment: Payment
    created_at: datetime
    updated_at: datetime
    expires_at: datetime | None = None  # v1.1: end of the payment window


@dataclass(frozen=True)
class OrderDraft:
    """Everything place_order needs to write in ONE transaction."""

    user_id: int
    idempotency_key: str
    lines: list[OrderLine]
    pricing: Pricing
    coupon_code: str | None
    payment_id: str
    at: datetime
    expires_at: datetime | None = None  # v1.1: at + RESERVATION_TTL


@dataclass(frozen=True)
class NewEvent:
    type: str
    payload: dict


@dataclass(frozen=True)
class Transition:
    """One atomic state change: order status (compare-and-set), stock, payment, webhook event, outbox."""

    order_id: int
    from_status: str
    to_status: str | None  # None: the order itself does not change
    at: datetime
    restock: bool = False
    events: list[NewEvent] = field(default_factory=list)
    payment_id: str | None = None
    payment_status: str | None = None
    webhook_event_id: str | None = None
    webhook_event_type: str | None = None


@dataclass
class OutboxEvent:
    id: int
    type: str
    order_id: int
    payload: dict
    created_at: datetime
    published_at: datetime | None


@dataclass(frozen=True)
class SearchFilters:
    terms: list[str]
    category: str | None = None
    min_price_paise: int | None = None
    max_price_paise: int | None = None
    in_stock: bool | None = None
