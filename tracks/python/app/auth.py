"""Accounts, login and roles. Framework-free: the HTTP layer only parses and formats."""

from __future__ import annotations

import logging

from .clock import Clock
from .models import LoginResult, Principal, User
from .repository import UserRepository
from .security import PasswordHasher, TokenService


def require_role(principal: Principal, *roles: str) -> None:
    # TODO(phase-3 M2): 403 forbidden 'requires role X' unless principal.role is one of the given
    #   roles.
    raise NotImplementedError("TODO(phase-3 M2): require_role")


class AuthService:
    def __init__(self, users: UserRepository, hasher: PasswordHasher, tokens: TokenService, clock: Clock):
        self.users = users
        self._hasher = hasher
        self._tokens = tokens
        self._clock = clock

    def register(self, payload: object) -> User:
        # TODO(phase-3 M2): validate email, password, displayName; ALWAYS create a CUSTOMER (ignore
        #   any 'role' in the payload: mass assignment!); hash the password; DuplicateError -> 409
        #   email-taken.
        raise NotImplementedError("TODO(phase-3 M2): AuthService.register")

    def login(self, payload: object) -> LoginResult:
        # TODO(phase-3 M2): email and password must be strings (400); the SAME 401 invalid-
        #   credentials for an unknown e-mail and a wrong password, and still run one bcrypt verify
        #   for an unknown e-mail (timing). Passwords over 72 bytes are a 401 too. Returns the access
        #   token + the user.
        raise NotImplementedError("TODO(phase-3 M2): AuthService.login")

    def me(self, principal: Principal) -> User:
        # TODO(phase-3 M2): the current user; 401 unauthorized 'account no longer exists' if it was
        #   removed.
        raise NotImplementedError("TODO(phase-3 M2): AuthService.me")

    def ensure_admin(self, email: str, password: str) -> User:
        """Start-up bootstrap from ADMIN_EMAIL / ADMIN_PASSWORD (there is no other way to get an ADMIN)."""
        # TODO(phase-3 M2): create ADMIN_EMAIL as an ADMIN, or promote it if it exists. Until you
        #   build it the app starts without an admin.
        logging.getLogger("shopflow").warning("TODO(phase-3 M2): ensure_admin - starting without an admin")
        return None  # type: ignore[return-value]

    def authenticate(self, authorization: str | None) -> Principal:
        # TODO(phase-3 M2): optional(header), but null -> 401 unauthorized (the HTTP layer adds WWW-
        #   Authenticate).
        raise NotImplementedError("TODO(phase-3 M2): AuthService.authenticate")

    def optional(self, authorization: str | None) -> Principal | None:
        """No header -> anonymous. A header that is present but wrong is always a 401, never anonymous."""
        # TODO(phase-3 M2): no header -> null (anonymous). 'Bearer <jwt>' -> verify it. A header
        #   that is present but bad is ALWAYS a 401, never silently anonymous.
        raise NotImplementedError("TODO(phase-3 M2): AuthService.optional")
