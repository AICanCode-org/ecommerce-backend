"""HTTP layer (FastAPI). Thin: read the request, call a service, shape the JSON, map errors."""

from __future__ import annotations

import logging
from collections.abc import Callable
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Body, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, PlainTextResponse
from starlette.concurrency import run_in_threadpool
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import validation as v
from .ai import (
    CircuitBreaker,
    DescriptionService,
    LlmClient,
    LlmParser,
    LlmWriter,
    RuleParser,
    SearchAssistService,
    TemplateWriter,
)
from .auth import AuthService, require_role
from .cart import CartService
from .catalog import CatalogService
from .clock import Clock, iso, utc_now
from .config import Settings
from .db import create_pool, run_migrations
from .errors import ApiError, not_found, validation_error
from .metrics import Metrics
from .models import CartView, LoginResult, Order, OutboxEvent, Product, ProductQuery, SearchFilters, User
from .money import format_inr
from .observability import RequestIdMiddleware, configure_logging
from .orders import EventPublisher, LogPublisher, OrderService, OutboxService, PaymentService
from .repository import (
    CartRepository,
    OrderRepository,
    PostgresCartRepository,
    PostgresOrderRepository,
    PostgresProductRepository,
    PostgresUserRepository,
    ProductRepository,
    UserRepository,
)
from .security import ACCESS_TOKEN_TTL, PasswordHasher, TokenService, new_payment_id, resolve_secret

log = logging.getLogger("shopflow")
PROBLEM_JSON = "application/problem+json"
Auth = Annotated[str | None, Header(alias="Authorization")]
JsonBody = Annotated[object, Body()]
OptionalBody = Annotated[object, Body()]
ASSIST_RESULTS = 20
configure_logging()


def build_ai(settings: Settings, metrics: Metrics | None = None) -> tuple[DescriptionService, SearchAssistService]:
    if settings.ai_provider == "llm":
        counters = metrics or Metrics()
        client = CircuitBreaker(
            LlmClient(settings.llm_base_url, settings.llm_model, settings.llm_api_key, settings.llm_timeout_ms),
            on_short_circuit=lambda: counters.inc("shopflow_llm_short_circuits_total"),
        )
        return (
            DescriptionService(TemplateWriter(), LlmWriter(client)),
            SearchAssistService(RuleParser(), LlmParser(client)),
        )
    return DescriptionService(TemplateWriter()), SearchAssistService(RuleParser())


# ---- JSON shapes (identical in every track, see spec/openapi.yaml) ------------------------------
def user_json(u: User) -> dict:
    return {"id": u.id, "email": u.email, "displayName": u.display_name, "role": u.role, "createdAt": iso(u.created_at)}


def login_json(r: LoginResult) -> dict:
    return {
        "accessToken": r.access_token,
        "tokenType": "Bearer",
        "expiresIn": int(ACCESS_TOKEN_TTL.total_seconds()),
        "user": user_json(r.user),
    }


def product_json(p: Product) -> dict:
    return {
        "id": p.id,
        "sku": p.sku,
        "name": p.name,
        "description": p.description,
        "category": p.category,
        "pricePaise": p.price_paise,
        "price": format_inr(p.price_paise),
        "stock": p.stock,
        "inStock": p.stock > 0,
        "active": p.active,
        "version": p.version,
        "createdAt": iso(p.created_at),
        "updatedAt": iso(p.updated_at),
    }


def product_item_json(p: Product) -> dict:
    return {
        "sku": p.sku,
        "name": p.name,
        "category": p.category,
        "pricePaise": p.price_paise,
        "price": format_inr(p.price_paise),
        "inStock": p.stock > 0,
    }


def cart_json(c: CartView) -> dict:
    p = c.pricing
    return {
        "items": [
            {
                "sku": line.product.sku,
                "name": line.product.name,
                "unitPricePaise": line.product.price_paise,
                "quantity": line.quantity,
                "lineTotalPaise": line.product.price_paise * line.quantity,
                "available": line.product.active and line.product.stock >= line.quantity,
            }
            for line in c.lines
        ],
        "couponCode": c.coupon_code,
        "couponApplied": p.coupon_applied,
        "subtotalPaise": p.subtotal_paise,
        "discountPaise": p.discount_paise,
        "shippingPaise": p.shipping_paise,
        "totalPaise": p.total_paise,
        "total": format_inr(p.total_paise),
    }


def order_json(o: Order) -> dict:
    return {
        "id": o.id,
        "userId": o.user_id,
        "status": o.status,
        "items": [
            {
                "sku": line.sku,
                "name": line.name,
                "unitPricePaise": line.unit_price_paise,
                "quantity": line.quantity,
                "lineTotalPaise": line.unit_price_paise * line.quantity,
            }
            for line in o.lines
        ],
        "subtotalPaise": o.subtotal_paise,
        "discountPaise": o.discount_paise,
        "shippingPaise": o.shipping_paise,
        "totalPaise": o.total_paise,
        "total": format_inr(o.total_paise),
        "couponCode": o.coupon_code,
        "payment": {
            "id": o.payment.id,
            "provider": o.payment.provider,
            "status": o.payment.status,
            "amountPaise": o.payment.amount_paise,
        },
        "createdAt": iso(o.created_at),
        "updatedAt": iso(o.updated_at),
        "expiresAt": iso(o.expires_at),
    }


def event_json(e: OutboxEvent) -> dict:
    return {
        "id": e.id,
        "type": e.type,
        "orderId": e.order_id,
        "payload": e.payload,
        "createdAt": iso(e.created_at),
        "publishedAt": iso(e.published_at),
    }


def filters_json(f: SearchFilters) -> dict:
    return {
        "terms": f.terms,
        "category": f.category,
        "minPricePaise": f.min_price_paise,
        "maxPricePaise": f.max_price_paise,
        "inStock": f.in_stock,
    }


def create_app(
    settings: Settings | None = None,
    *,
    users: UserRepository | None = None,
    products: ProductRepository | None = None,
    carts: CartRepository | None = None,
    orders: OrderRepository | None = None,
    clock: Clock = utc_now,
    ai: tuple[DescriptionService, SearchAssistService] | None = None,
    payment_ids: Callable[[], str] = new_payment_id,
    publisher: EventPublisher | None = None,
    metrics: Metrics | None = None,
) -> FastAPI:
    """Real adapters by default; tests pass in-memory repositories, a fake clock and fake AI."""
    settings = settings or Settings.from_env()
    metrics = metrics or Metrics()
    state: dict = {}

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        pool = None
        repos = (users, products, carts, orders)
        if any(r is None for r in repos):
            pool = create_pool(settings.database_url)
            applied = run_migrations(pool, settings.migrations_dir)
            log.info("migrations applied this start: %s", applied or "none")
            repos = (
                PostgresUserRepository(pool),
                PostgresProductRepository(pool),
                PostgresCartRepository(pool),
                PostgresOrderRepository(pool),
            )
        user_repo, product_repo, cart_repo, order_repo = repos
        descriptions, assist = ai or build_ai(settings, metrics)
        tokens = TokenService(resolve_secret(settings.jwt_secret), clock)
        auth = AuthService(user_repo, PasswordHasher(settings.bcrypt_cost), tokens, clock)  # type: ignore[arg-type]
        if settings.admin_email and settings.admin_password:
            auth.ensure_admin(settings.admin_email, settings.admin_password)
            log.info("admin account ensured for %s", settings.admin_email)
        webhook_secret = resolve_secret(settings.payment_webhook_secret, "PAYMENT_WEBHOOK_SECRET")
        state.update(
            auth=auth,
            catalog=CatalogService(product_repo, clock),  # type: ignore[arg-type]
            cart=CartService(cart_repo, product_repo, clock),  # type: ignore[arg-type]
            orders=OrderService(order_repo, cart_repo, clock, payment_ids),  # type: ignore[arg-type]
            payments=PaymentService(order_repo, webhook_secret, clock),  # type: ignore[arg-type]
            outbox=OutboxService(order_repo, publisher or LogPublisher(), clock),  # type: ignore[arg-type]
            descriptions=descriptions,
            assist=assist,
            clock=clock,
        )
        yield
        if pool is not None:
            pool.close()

    app = FastAPI(title="ShopFlow (Python track)", version="1.1.0", lifespan=lifespan)
    app.add_middleware(RequestIdMiddleware)

    def auth() -> AuthService:
        return state["auth"]

    def catalog() -> CatalogService:
        return state["catalog"]

    def cart() -> CartService:
        return state["cart"]

    def orders_() -> OrderService:
        return state["orders"]

    # ---- error mapping ---------------------------------------------------
    @app.exception_handler(ApiError)
    async def api_error_handler(_req: Request, exc: ApiError):
        headers = {"WWW-Authenticate": "Bearer"} if exc.slug == "unauthorized" else None
        return JSONResponse(exc.to_problem(), status_code=exc.status, media_type=PROBLEM_JSON, headers=headers)

    @app.exception_handler(RequestValidationError)
    async def request_validation_handler(_req: Request, _exc: RequestValidationError):
        problem = validation_error("request body must be a JSON object").to_problem()
        return JSONResponse(problem, status_code=400, media_type=PROBLEM_JSON)

    @app.exception_handler(StarletteHTTPException)
    async def http_error_handler(_req: Request, exc: StarletteHTTPException):
        # Unknown routes (and wrong methods) answer with problem+json too, never FastAPI's {"detail": ...}.
        error = (
            not_found("route") if exc.status_code == 404 else ApiError(exc.status_code, "http-error", str(exc.detail))
        )
        return JSONResponse(error.to_problem(), status_code=error.status, media_type=PROBLEM_JSON)

    @app.exception_handler(Exception)
    async def unexpected_handler(_req: Request, exc: Exception):
        log.exception("unhandled error", exc_info=exc)
        problem = ApiError(500, "internal-error", "Internal server error").to_problem()
        return JSONResponse(problem, status_code=500, media_type=PROBLEM_JSON)

    # ---- ops -----------------------------------------------------------------
    @app.get("/health")
    def health():
        db_up = auth().users.ping()
        body = {"status": "UP" if db_up else "DOWN", "checks": {"database": "UP" if db_up else "DOWN"}}
        return JSONResponse(body, status_code=200 if db_up else 503)

    @app.get("/metrics")
    def get_metrics():
        return PlainTextResponse(metrics.render(), media_type="text/plain; version=0.0.4")

    # ---- accounts ------------------------------------------------------------
    @app.post("/api/v1/auth/register", status_code=201)
    def register(payload: JsonBody):
        user = auth().register(payload)
        metrics.inc("shopflow_users_registered_total")
        return JSONResponse(user_json(user), status_code=201)

    @app.post("/api/v1/auth/login")
    def login(payload: JsonBody):
        try:
            return login_json(auth().login(payload))
        except ApiError as exc:
            if exc.status == 401:
                metrics.inc("shopflow_logins_failed_total")
            raise

    @app.get("/api/v1/me")
    def me(authorization: Auth = None):
        return user_json(auth().me(auth().authenticate(authorization)))

    # ---- catalog -------------------------------------------------------------
    @app.get("/api/v1/products")
    def list_products(
        q: str | None = None,
        category: str | None = None,
        minPricePaise: str | None = None,  # noqa: N803 - query names are camelCase in the contract
        maxPricePaise: str | None = None,  # noqa: N803
        inStock: str | None = None,  # noqa: N803
        limit: str | None = None,
        cursor: str | None = None,
    ):
        page, next_cursor = catalog().list(
            q=q, category=category, min_price=minPricePaise, max_price=maxPricePaise, in_stock=inStock,
            limit=limit, cursor=cursor,
        )  # fmt: skip
        return {"items": [product_item_json(p) for p in page], "nextCursor": next_cursor}

    @app.post("/api/v1/products", status_code=201)
    def create_product(payload: JsonBody, authorization: Auth = None):
        product = catalog().create(auth().authenticate(authorization), payload)
        headers = {"Location": f"/api/v1/products/{product.sku}"}
        return JSONResponse(product_json(product), status_code=201, headers=headers)

    @app.get("/api/v1/products/{sku}")
    def get_product(sku: str, authorization: Auth = None):
        return product_json(catalog().get(auth().optional(authorization), sku))

    @app.patch("/api/v1/products/{sku}")
    def update_product(sku: str, payload: JsonBody, authorization: Auth = None):
        return product_json(catalog().update(auth().authenticate(authorization), sku, payload))

    @app.post("/api/v1/products/{sku}/stock")
    def adjust_stock(sku: str, payload: JsonBody, authorization: Auth = None):
        return product_json(catalog().adjust_stock(auth().authenticate(authorization), sku, payload))

    @app.get("/api/v1/categories")
    def categories():
        return {"items": [{"name": c.name, "products": c.products} for c in catalog().categories()]}

    # ---- AI ------------------------------------------------------------------
    @app.post("/api/v1/products/{sku}/description-draft")
    def description_draft(sku: str, authorization: Auth = None, payload: OptionalBody = None):
        principal = auth().authenticate(authorization)
        require_role(principal, "ADMIN")
        words = v.keywords(v.require_object(payload).get("keywords")) if payload is not None else []
        product = catalog().get(principal, sku)
        result = state["descriptions"].draft(product, words)
        if result.fallback:
            metrics.inc("shopflow_ai_fallback_total")
        return {
            "sku": product.sku,
            "provider": result.provider,
            "fallback": result.fallback,
            "description": result.description,
            "generatedAt": iso(state["clock"]()),
        }

    @app.get("/api/v1/search/assist")
    def search_assist(q: str | None = None):
        query = v.assist_query(q)
        known = [c.name for c in catalog().categories()]
        result = state["assist"].interpret(query, known)
        if result.fallback:
            metrics.inc("shopflow_ai_fallback_total")
        f = result.filters
        page, _ = catalog().search(
            ProductQuery(
                limit=ASSIST_RESULTS, q=" ".join(f.terms) or None, category=f.category,
                min_price_paise=f.min_price_paise, max_price_paise=f.max_price_paise, in_stock=f.in_stock,
            )
        )  # fmt: skip
        return {
            "query": query,
            "provider": result.provider,
            "fallback": result.fallback,
            "filters": filters_json(f),
            "items": [product_item_json(p) for p in page],
        }

    # ---- cart ----------------------------------------------------------------
    @app.get("/api/v1/cart")
    def get_cart(authorization: Auth = None):
        return cart_json(cart().view(auth().authenticate(authorization)))

    @app.put("/api/v1/cart/items/{sku}")
    def put_cart_item(sku: str, payload: JsonBody, authorization: Auth = None):
        return cart_json(cart().set_item(auth().authenticate(authorization), sku, payload))

    @app.delete("/api/v1/cart/items/{sku}")
    def delete_cart_item(sku: str, authorization: Auth = None):
        return cart_json(cart().remove_item(auth().authenticate(authorization), sku))

    @app.put("/api/v1/cart/coupon")
    def put_coupon(payload: JsonBody, authorization: Auth = None):
        return cart_json(cart().apply_coupon(auth().authenticate(authorization), payload))

    @app.delete("/api/v1/cart/coupon")
    def delete_coupon(authorization: Auth = None):
        return cart_json(cart().remove_coupon(auth().authenticate(authorization)))

    # ---- checkout + orders ---------------------------------------------------
    @app.post("/api/v1/checkout")
    def checkout(
        authorization: Auth = None,
        idempotency_key: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
    ):
        order, replayed = orders_().checkout(auth().authenticate(authorization), idempotency_key)
        headers = {"Location": f"/api/v1/orders/{order.id}"}
        if replayed:
            headers["Idempotent-Replayed"] = "true"
        metrics.inc("shopflow_checkout_replays_total" if replayed else "shopflow_orders_placed_total")
        return JSONResponse(order_json(order), status_code=200 if replayed else 201, headers=headers)

    @app.get("/api/v1/orders")
    def my_orders(limit: str | None = None, authorization: Auth = None):
        return {"items": [order_json(o) for o in orders_().mine(auth().authenticate(authorization), limit)]}

    @app.get("/api/v1/orders/{order_id}")
    def get_order(order_id: str, authorization: Auth = None):
        principal = auth().authenticate(authorization)
        return order_json(orders_().get(principal, v.path_id(order_id, "order")))

    @app.post("/api/v1/orders/{order_id}/cancel")
    def cancel_order(order_id: str, authorization: Auth = None):
        principal = auth().authenticate(authorization)
        return order_json(orders_().cancel(principal, v.path_id(order_id, "order")))

    @app.get("/api/v1/admin/orders")
    def admin_orders(status: str | None = None, limit: str | None = None, authorization: Auth = None):
        found = orders_().admin_list(auth().authenticate(authorization), status, limit)
        return {"items": [order_json(o) for o in found]}

    @app.post("/api/v1/admin/orders/expire")
    def admin_expire_orders(authorization: Auth = None):
        expired = orders_().expire_overdue(auth().authenticate(authorization))
        metrics.inc("shopflow_orders_expired_total", expired)
        return {"expired": expired}

    @app.post("/api/v1/admin/orders/{order_id}/status")
    def admin_order_status(order_id: str, payload: JsonBody, authorization: Auth = None):
        principal = auth().authenticate(authorization)
        return order_json(orders_().admin_set_status(principal, v.path_id(order_id, "order"), payload))

    # ---- payments + outbox ---------------------------------------------------
    @app.post("/api/v1/payments/webhook")
    async def payment_webhook(request: Request):
        body = await request.body()  # the RAW bytes: the signature is over exactly these
        signature = request.headers.get("X-Signature")
        result = await run_in_threadpool(state["payments"].handle_webhook, body, signature)
        if result["status"] == "duplicate":
            metrics.inc("shopflow_webhooks_duplicate_total")
        return result

    @app.get("/api/v1/admin/outbox")
    def admin_outbox(pending: str | None = None, limit: str | None = None, authorization: Auth = None):
        events = state["outbox"].list(auth().authenticate(authorization), pending, limit)
        return {"items": [event_json(e) for e in events]}

    @app.post("/api/v1/admin/outbox/publish")
    def admin_outbox_publish(authorization: Auth = None):
        return {"published": state["outbox"].publish_pending(auth().authenticate(authorization))}

    return app
