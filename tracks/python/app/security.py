"""Passwords (bcrypt), access tokens (JWT, HS256) and webhook signatures (HMAC-SHA256)."""

from __future__ import annotations

import logging
import secrets
from datetime import timedelta

from .clock import Clock
from .models import Principal, User

log = logging.getLogger("shopflow.security")
ISSUER = "shopflow"
ACCESS_TOKEN_TTL = timedelta(minutes=60)
MIN_SECRET_BYTES = 32
SIGNATURE_PREFIX = "sha256="


class PasswordHasher:
    """bcrypt with a configurable cost (10 in production, 4 in tests to keep them fast)."""

    def __init__(self, cost: int = 10):
        if not 4 <= cost <= 15:
            raise ValueError("BCRYPT_COST must be 4-15")
        self._cost = cost
        # Used when the e-mail is unknown, so a login takes the same time either way.
        # TODO(phase-3 M2): self._dummy = self.hash("not-a-real-password") once hash() works.
        self._dummy: str | None = None

    def hash(self, password: str) -> str:
        # TODO(phase-3 M2): bcrypt with the configured cost (4 in tests, 10 in production).
        raise NotImplementedError("TODO(phase-3 M2): PasswordHasher.hash")

    def verify(self, password: str, password_hash: str | None) -> bool:
        # TODO(phase-3 M2): bcrypt check; when password_hash is null (unknown e-mail) verify against
        #   the dummy hash anyway and return false: same timing either way. A malformed hash -> false,
        #   never an exception.
        raise NotImplementedError("TODO(phase-3 M2): PasswordHasher.verify")


def resolve_secret(configured: str | None, name: str = "JWT_SECRET") -> str:
    if configured is None:
        log.warning("%s is not set: using a random secret for this process (dev only)", name)
        return secrets.token_urlsafe(48)
    if len(configured.encode("utf-8")) < MIN_SECRET_BYTES:
        raise ValueError(f"{name} must be at least {MIN_SECRET_BYTES} bytes")
    return configured


class TokenService:
    def __init__(self, secret: str, clock: Clock):
        self._secret = secret
        self._clock = clock

    def issue_access_token(self, user: User) -> str:
        # TODO(phase-3 M2): HS256 JWT with sub (user id as a string), role, iss=ISSUER, iat and exp
        #   = now + ACCESS_TOKEN_TTL (1 hour), using the injected clock.
        raise NotImplementedError("TODO(phase-3 M2): TokenService.issue_access_token")

    def verify_access_token(self, token: str) -> Principal:
        # TODO(phase-3 M2): pin the algorithm to HS256 (reject alg=none / RS256), check the issuer,
        #   check exp against the injected CLOCK, sub must be ASCII digits and role a known role.
        #   Anything wrong -> 401 unauthorized 'invalid or expired token'.
        raise NotImplementedError("TODO(phase-3 M2): TokenService.verify_access_token")


def sign_webhook(secret: str, body: bytes) -> str:
    """The value of the X-Signature header: 'sha256=' + hex HMAC-SHA256 of the RAW request body."""
    # TODO(phase-3 M2): the X-Signature value: 'sha256=' + hex HMAC-SHA256 of the RAW request body
    #   with the webhook secret.
    raise NotImplementedError("TODO(phase-3 M2): sign_webhook")


def verify_webhook(secret: str, body: bytes, header: str | None) -> bool:
    """Constant-time comparison: a byte-by-byte == leaks how many leading characters were right."""
    # TODO(phase-3 M2): missing header or no 'sha256=' prefix -> false; otherwise compare with
    #   sign() in CONSTANT time (never ==).
    raise NotImplementedError("TODO(phase-3 M2): verify_webhook")


def new_payment_id() -> str:
    return "pay_" + secrets.token_hex(12)
