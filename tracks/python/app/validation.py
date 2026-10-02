"""Input validation. Every function returns the clean value or raises a 400 validation-error.

The messages are part of the contract: the tests (and the other two tracks) expect them exactly.
"""

from __future__ import annotations

import re

from .errors import validation_error

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
    # TODO(phase-3 M1): a string (400 'email is required'); trim + lower-case; must match EMAIL_RE
    #   and be at most 254 characters (400 'email is invalid').
    raise NotImplementedError("TODO(phase-3 M1): email")


def password(value: object) -> str:
    # TODO(phase-3 M1): a string of at least 8 characters and at most 72 BYTES in UTF-8 (bcrypt
    #   ignores the rest): 400 otherwise.
    raise NotImplementedError("TODO(phase-3 M1): password")


def display_name(value: object) -> str:
    # TODO(phase-3 M1): a string, trimmed, 1-50 characters (count code points, not UTF-16 units).
    raise NotImplementedError("TODO(phase-3 M1): display_name")


# ---- catalog ----
def sku(value: object) -> str:
    # TODO(phase-3 M1): a string, trimmed and UPPER-cased, matching SKU_RE (3-32 characters: A-Z,
    #   0-9 and -).
    raise NotImplementedError("TODO(phase-3 M1): sku")


def product_name(value: object) -> str:
    # TODO(phase-3 M1): a string, trimmed, 1-120 characters.
    raise NotImplementedError("TODO(phase-3 M1): product_name")


def description(value: object) -> str:
    # TODO(phase-3 M1): optional: null/missing -> empty string; otherwise a string (trimmed) of at
    #   most 2000 characters.
    raise NotImplementedError("TODO(phase-3 M1): description")


def category(value: object) -> str:
    # TODO(phase-3 M1): a string, trimmed and lower-cased, matching CATEGORY_RE (2-30 characters:
    #   a-z, 0-9 and -).
    raise NotImplementedError("TODO(phase-3 M1): category")


def price_paise(value: object) -> int:
    # TODO(phase-3 M1): an INTEGER (not a float, not a boolean, not a string) between 1 and
    #   MAX_PRICE_PAISE. Money is always integer paise (ADR-0001).
    raise NotImplementedError("TODO(phase-3 M1): price_paise")


def stock(value: object) -> int:
    # TODO(phase-3 M1): an integer between 0 and MAX_STOCK (booleans are not integers).
    raise NotImplementedError("TODO(phase-3 M1): stock")


def stock_delta(value: object) -> int:
    # TODO(phase-3 M1): a non-zero integer between -MAX_STOCK and MAX_STOCK.
    raise NotImplementedError("TODO(phase-3 M1): stock_delta")


def active(value: object) -> bool:
    # TODO(phase-3 M1): a real boolean: true or false, nothing else.
    raise NotImplementedError("TODO(phase-3 M1): active")


def version(value: object) -> int:
    # TODO(phase-3 M1): a positive integer (the version you read; optimistic locking, ADR-0003).
    raise NotImplementedError("TODO(phase-3 M1): version")


def keywords(value: object) -> list[str]:
    """Optional hints for an AI description draft: at most 5, each 1-30 characters, de-duplicated."""
    # TODO(phase-3 M1): optional list (missing -> []); at most 5 strings, each lower-cased + trimmed
    #   and matching KEYWORD_RE; drop duplicates, keep the order.
    raise NotImplementedError("TODO(phase-3 M1): keywords")


# ---- cart + checkout ----
def quantity(value: object) -> int:
    # TODO(phase-3 M1): an integer between 1 and MAX_QUANTITY (10).
    raise NotImplementedError("TODO(phase-3 M1): quantity")


def coupon_code(value: object) -> str:
    # TODO(phase-3 M1): a string (400 'code is required'), trimmed + UPPER-cased, matching COUPON_RE
    #   (400 'code is invalid').
    raise NotImplementedError("TODO(phase-3 M1): coupon_code")


def idempotency_key(value: str | None) -> str:
    # TODO(phase-3 M1): the Idempotency-Key header: missing/blank -> 400 'Idempotency-Key header is
    #   required'; must match IDEMPOTENCY_KEY_RE (8-64 characters).
    raise NotImplementedError("TODO(phase-3 M1): idempotency_key")


def order_status_target(value: object) -> str:
    # TODO(phase-3 M1): only SHIPPED or DELIVERED can be set by an admin (cancel has its own
    #   endpoint, PAID comes from the webhook).
    raise NotImplementedError("TODO(phase-3 M1): order_status_target")


# ---- query strings ----
def limit(value: str | None, default: int = 20, maximum: int = 50) -> int:
    # TODO(phase-3 M1): a query string: missing -> the default (20); ASCII digits only, 1..maximum
    #   (50); anything else 400.
    raise NotImplementedError("TODO(phase-3 M1): limit")


def price_filter(value: str | None, name: str) -> int | None:
    # TODO(phase-3 M1): a query string: missing -> null; ASCII digits only, 0..MAX_PRICE_PAISE, else
    #   400 naming the parameter.
    raise NotImplementedError("TODO(phase-3 M1): price_filter")


def bool_filter(value: str | None, name: str) -> bool | None:
    # TODO(phase-3 M1): a query string: missing -> null; exactly 'true' or 'false', else 400 naming
    #   the parameter.
    raise NotImplementedError("TODO(phase-3 M1): bool_filter")


def search_query(value: str | None) -> str | None:
    # TODO(phase-3 M1): missing or blank -> null; trimmed, at most 100 characters.
    raise NotImplementedError("TODO(phase-3 M1): search_query")


def assist_query(value: str | None) -> str:
    # TODO(phase-3 M1): required (400 'q is required'), trimmed, at most 200 characters.
    raise NotImplementedError("TODO(phase-3 M1): assist_query")


def category_filter(value: str | None) -> str | None:
    # TODO(phase-3 M1): missing -> null; lower-cased, must match CATEGORY_RE.
    raise NotImplementedError("TODO(phase-3 M1): category_filter")


def order_status_filter(value: str | None) -> str | None:
    # TODO(phase-3 M1): missing -> null; upper-cased, one of ORDER_STATUSES.
    raise NotImplementedError("TODO(phase-3 M1): order_status_filter")


def path_id(value: str, what: str) -> int:
    """An id that cannot exist is a 404, not a 400: /orders/abc is simply not there."""
    # TODO(phase-3 M1): a path segment: ASCII digits only and a positive id that fits in 63 bits;
    #   anything else is 404 for 'what' (not 400: /orders/abc simply does not exist).
    raise NotImplementedError("TODO(phase-3 M1): path_id")


# ---- keyset cursor (opaque to clients) ----
def encode_cursor(last_id: int) -> str:
    # TODO(phase-3 M3): opaque cursor: base64url (no padding) of 'p' + the last id shown.
    raise NotImplementedError("TODO(phase-3 M3): encode_cursor")


def decode_cursor(value: str | None) -> int | None:
    # TODO(phase-3 M3): missing -> null; must match CURSOR_RE, decode base64url, be 'p' + digits
    #   with a positive id; anything else 400 'cursor is invalid'.
    raise NotImplementedError("TODO(phase-3 M3): decode_cursor")
