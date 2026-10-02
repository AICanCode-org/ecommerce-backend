"""Passwords (bcrypt), access tokens (JWT, HS256) and webhook signatures (HMAC-SHA256)."""

from __future__ import annotations

import hashlib
import hmac
import logging
import secrets
from datetime import timedelta

import bcrypt
import jwt

from .clock import Clock
from .errors import unauthorized
from .models import ROLES, Principal, User

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
        self._dummy = self.hash("not-a-real-password")

    def hash(self, password: str) -> str:
        return bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt(self._cost)).decode("ascii")

    def verify(self, password: str, password_hash: str | None) -> bool:
        target = (password_hash or self._dummy).encode("ascii")
        try:
            ok = bcrypt.checkpw(password.encode("utf-8"), target)
        except ValueError:
            return False
        return ok and password_hash is not None


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
        now = self._clock()
        claims = {
            "sub": str(user.id),
            "role": user.role,
            "iss": ISSUER,
            "iat": int(now.timestamp()),
            "exp": int((now + ACCESS_TOKEN_TTL).timestamp()),
        }
        return jwt.encode(claims, self._secret, algorithm="HS256")

    def verify_access_token(self, token: str) -> Principal:
        try:
            # algorithms=["HS256"] pins the algorithm: "alg": "none" or RS256 tokens are rejected.
            # Expiry is checked below against the injected clock, so tests can travel in time.
            claims = jwt.decode(
                token,
                self._secret,
                algorithms=["HS256"],
                issuer=ISSUER,
                options={"verify_exp": False, "verify_iat": False},
            )
        except jwt.PyJWTError as exc:
            raise unauthorized("invalid or expired token") from exc
        exp, sub, role = claims.get("exp"), claims.get("sub"), claims.get("role")
        if not isinstance(exp, int) or exp <= self._clock().timestamp():
            raise unauthorized("invalid or expired token")
        if not isinstance(sub, str) or not sub.isascii() or not sub.isdigit() or role not in ROLES:
            raise unauthorized("invalid or expired token")
        return Principal(user_id=int(sub), role=role)


def sign_webhook(secret: str, body: bytes) -> str:
    """The value of the X-Signature header: 'sha256=' + hex HMAC-SHA256 of the RAW request body."""
    return SIGNATURE_PREFIX + hmac.new(secret.encode("utf-8"), body, hashlib.sha256).hexdigest()


def verify_webhook(secret: str, body: bytes, header: str | None) -> bool:
    """Constant-time comparison: a byte-by-byte == leaks how many leading characters were right."""
    if not header or not header.startswith(SIGNATURE_PREFIX):
        return False
    return hmac.compare_digest(sign_webhook(secret, body).encode("ascii"), header.strip().encode("utf-8"))


def new_payment_id() -> str:
    return "pay_" + secrets.token_hex(12)
