"""Accounts, login and roles. Framework-free: the HTTP layer only parses and formats."""

from __future__ import annotations

from . import validation as v
from .clock import Clock
from .errors import DuplicateError, email_taken, forbidden, invalid_credentials, unauthorized, validation_error
from .models import LoginResult, Principal, User
from .repository import UserRepository
from .security import PasswordHasher, TokenService


def require_role(principal: Principal, *roles: str) -> None:
    if principal.role not in roles:
        raise forbidden(f"requires role {' or '.join(roles)}")


class AuthService:
    def __init__(self, users: UserRepository, hasher: PasswordHasher, tokens: TokenService, clock: Clock):
        self.users = users
        self._hasher = hasher
        self._tokens = tokens
        self._clock = clock

    def register(self, payload: object) -> User:
        data = v.require_object(payload)
        email = v.email(data.get("email"))
        password = v.password(data.get("password"))
        name = v.display_name(data.get("displayName"))
        # Never read "role" from the request: everyone starts as CUSTOMER (mass-assignment defence).
        try:
            return self.users.insert_user(email, self._hasher.hash(password), name, "CUSTOMER", self._clock())
        except DuplicateError as exc:
            raise email_taken() from exc

    def login(self, payload: object) -> LoginResult:
        data = v.require_object(payload)
        email, password = data.get("email"), data.get("password")
        if not isinstance(email, str) or not isinstance(password, str):
            raise validation_error("email and password are required")
        user = self.users.find_user_by_email(email.strip().lower())
        # verify() also runs for unknown e-mails (against a dummy hash): same answer, same timing.
        if len(password.encode("utf-8")) > 72 or not self._hasher.verify(
            password, user.password_hash if user else None
        ):
            raise invalid_credentials()
        assert user is not None
        return LoginResult(self._tokens.issue_access_token(user), user)

    def me(self, principal: Principal) -> User:
        user = self.users.find_user(principal.user_id)
        if user is None:
            raise unauthorized("account no longer exists")
        return user

    def ensure_admin(self, email: str, password: str) -> User:
        """Start-up bootstrap from ADMIN_EMAIL / ADMIN_PASSWORD (there is no other way to get an ADMIN)."""
        email = v.email(email)
        existing = self.users.find_user_by_email(email)
        if existing is not None:
            return existing if existing.role == "ADMIN" else self.users.update_role(existing.id, "ADMIN")  # type: ignore[return-value]
        return self.users.insert_user(email, self._hasher.hash(v.password(password)), "Admin", "ADMIN", self._clock())

    def authenticate(self, authorization: str | None) -> Principal:
        principal = self.optional(authorization)
        if principal is None:
            raise unauthorized()
        return principal

    def optional(self, authorization: str | None) -> Principal | None:
        """No header -> anonymous. A header that is present but wrong is always a 401, never anonymous."""
        if authorization is None:
            return None
        scheme, _, token = authorization.partition(" ")
        if scheme.lower() != "bearer" or not token.strip():
            raise unauthorized("use Authorization: Bearer <token>")
        return self._tokens.verify_access_token(token.strip())
