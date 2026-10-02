"""Input validation. Every function returns the clean value or raises a 400 validation-error.

The messages are part of the contract: the tests (and the other two tracks) expect them exactly.
"""

from __future__ import annotations

import base64
import binascii
import re

from .errors import not_found, validation_error
from .models import ORDER_STATUSES

EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
SKU_RE = re.compile(r"^[A-Z0-9][A-Z0-9-]{2,31}$")
CATEGORY_RE = re.compile(r"^[a-z0-9][a-z0-9-]{1,29}$")
COUPON_RE = re.compile(r"^[A-Z0-9]{3,20}$")
IDEMPOTENCY_KEY_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
KEYWORD_RE = re.compile(r"^[a-z0-9][a-z0-9 -]{0,29}$")
CURSOR_RE = re.compile(r"^[A-Za-z0-9_-]{1,40}$")
DIGITS_RE = re.compile(r"^[0-9]+$")  # ASCII only: str.isdigit() also accepts other scripts
MAX_PRICE_PAISE = 100_000_000  # ₹10 lakh
MAX_STOCK = 1_000_000
MAX_QUANTITY = 10


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)  # True is an int in Python!


def require_object(payload: object) -> dict:
    if not isinstance(payload, dict):
        raise validation_error("request body must be a JSON object")
    return payload


# ---- accounts ----
def email(value: object) -> str:
    if not isinstance(value, str):
        raise validation_error("email is required")
    clean = value.strip().lower()
    if len(clean) > 254 or not EMAIL_RE.match(clean):
        raise validation_error("email is invalid")
    return clean


def password(value: object) -> str:
    if not isinstance(value, str):
        raise validation_error("password is required")
    if len(value) < 8:
        raise validation_error("password must be at least 8 characters")
    if len(value.encode("utf-8")) > 72:
        raise validation_error("password must be at most 72 bytes")
    return value


def display_name(value: object) -> str:
    if not isinstance(value, str):
        raise validation_error("displayName is required")
    clean = value.strip()
    if not 1 <= len(clean) <= 50:
        raise validation_error("displayName must be 1-50 characters")
    return clean


# ---- catalog ----
def sku(value: object) -> str:
    if not isinstance(value, str):
        raise validation_error("sku is required")
    clean = value.strip().upper()
    if not SKU_RE.match(clean):
        raise validation_error("sku must be 3-32 characters: A-Z, 0-9 and -")
    return clean


def product_name(value: object) -> str:
    if not isinstance(value, str):
        raise validation_error("name is required")
    clean = value.strip()
    if not 1 <= len(clean) <= 120:
        raise validation_error("name must be 1-120 characters")
    return clean


def description(value: object) -> str:
    if value is None:
        return ""
    if not isinstance(value, str):
        raise validation_error("description must be a string")
    clean = value.strip()
    if len(clean) > 2000:
        raise validation_error("description must be at most 2000 characters")
    return clean


def category(value: object) -> str:
    if not isinstance(value, str):
        raise validation_error("category is required")
    clean = value.strip().lower()
    if not CATEGORY_RE.match(clean):
        raise validation_error("category must be 2-30 characters: a-z, 0-9 and -")
    return clean


def price_paise(value: object) -> int:
    if not _is_int(value) or not 1 <= value <= MAX_PRICE_PAISE:  # type: ignore[operator]
        raise validation_error(f"pricePaise must be an integer between 1 and {MAX_PRICE_PAISE}")
    return value  # type: ignore[return-value]


def stock(value: object) -> int:
    if value is None:
        return 0
    if not _is_int(value) or not 0 <= value <= MAX_STOCK:  # type: ignore[operator]
        raise validation_error(f"stock must be an integer between 0 and {MAX_STOCK}")
    return value  # type: ignore[return-value]


def stock_delta(value: object) -> int:
    if not _is_int(value) or value == 0 or abs(value) > MAX_STOCK:  # type: ignore[arg-type]
        raise validation_error(f"delta must be a non-zero integer between -{MAX_STOCK} and {MAX_STOCK}")
    return value  # type: ignore[return-value]


def active(value: object) -> bool:
    if not isinstance(value, bool):
        raise validation_error("active must be true or false")
    return value


def version(value: object) -> int:
    if not _is_int(value) or value < 1:  # type: ignore[operator]
        raise validation_error("version must be a positive integer")
    return value  # type: ignore[return-value]


def keywords(value: object) -> list[str]:
    """Optional hints for an AI description draft: at most 5, each 1-30 characters, de-duplicated."""
    message = "keywords must be a list of at most 5 words (1-30 characters each)"
    if value is None:
        return []
    if not isinstance(value, list):
        raise validation_error(message)
    out: list[str] = []
    for item in value:
        if not isinstance(item, str):
            raise validation_error(message)
        clean = " ".join(item.lower().split())
        if not KEYWORD_RE.match(clean):
            raise validation_error(message)
        if clean not in out:
            out.append(clean)
    if len(out) > 5:
        raise validation_error(message)
    return out


# ---- cart + checkout ----
def quantity(value: object) -> int:
    if not _is_int(value) or not 1 <= value <= MAX_QUANTITY:  # type: ignore[operator]
        raise validation_error(f"quantity must be an integer between 1 and {MAX_QUANTITY}")
    return value  # type: ignore[return-value]


def coupon_code(value: object) -> str:
    if not isinstance(value, str):
        raise validation_error("code is required")
    clean = value.strip().upper()
    if not COUPON_RE.match(clean):
        raise validation_error("code is invalid")
    return clean


def idempotency_key(value: str | None) -> str:
    if not value:
        raise validation_error("Idempotency-Key header is required")
    if not IDEMPOTENCY_KEY_RE.match(value):
        raise validation_error("Idempotency-Key must be 8-64 characters: A-Z, a-z, 0-9, _ and -")
    return value


def order_status_target(value: object) -> str:
    if value not in ("SHIPPED", "DELIVERED"):
        raise validation_error("status must be SHIPPED or DELIVERED")
    return value  # type: ignore[return-value]


# ---- query strings ----
def limit(value: str | None, default: int = 20, maximum: int = 50) -> int:
    if value is None:
        return default
    if not DIGITS_RE.match(value) or not 1 <= int(value) <= maximum:
        raise validation_error(f"limit must be an integer between 1 and {maximum}")
    return int(value)


def price_filter(value: str | None, name: str) -> int | None:
    if value is None or value == "":
        return None
    if not DIGITS_RE.match(value) or int(value) > MAX_PRICE_PAISE:
        raise validation_error(f"{name} must be an integer between 0 and {MAX_PRICE_PAISE} (paise)")
    return int(value)


def bool_filter(value: str | None, name: str) -> bool | None:
    if value is None or value == "":
        return None
    if value not in ("true", "false"):
        raise validation_error(f"{name} must be true or false")
    return value == "true"


def search_query(value: str | None) -> str | None:
    if value is None:
        return None
    clean = value.strip()
    if not clean:
        return None
    if len(clean) > 100:
        raise validation_error("q must be at most 100 characters")
    return clean


def assist_query(value: str | None) -> str:
    clean = (value or "").strip()
    if not clean:
        raise validation_error("q is required")
    if len(clean) > 200:
        raise validation_error("q must be at most 200 characters")
    return clean


def category_filter(value: str | None) -> str | None:
    if value is None or value == "":
        return None
    clean = value.lower()
    if not CATEGORY_RE.match(clean):
        raise validation_error("category is invalid")
    return clean


def order_status_filter(value: str | None) -> str | None:
    if value is None or value == "":
        return None
    if value not in ORDER_STATUSES:
        raise validation_error(f"status must be one of {', '.join(ORDER_STATUSES)}")
    return value


def path_id(value: str, what: str) -> int:
    """An id that cannot exist is a 404, not a 400: /orders/abc is simply not there."""
    if not DIGITS_RE.match(value) or len(value) > 18:
        raise not_found(what)
    return int(value)


# ---- keyset cursor (opaque to clients) ----
def encode_cursor(last_id: int) -> str:
    return base64.urlsafe_b64encode(f"p{last_id}".encode("ascii")).decode("ascii").rstrip("=")


def decode_cursor(value: str | None) -> int | None:
    if value is None or value == "":
        return None
    if not CURSOR_RE.match(value):
        raise validation_error("cursor is invalid")
    try:
        raw = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4)).decode("ascii")
    except (binascii.Error, UnicodeDecodeError, ValueError) as exc:
        raise validation_error("cursor is invalid") from exc
    if not raw.startswith("p") or not DIGITS_RE.match(raw[1:]) or len(raw) > 19:
        raise validation_error("cursor is invalid")
    return int(raw[1:])
