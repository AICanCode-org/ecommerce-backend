"""M2: AuthService (framework-free)."""

import pytest

from app.auth import AuthService, require_role
from app.errors import ApiError
from app.models import Principal
from app.repository import InMemoryStore
from app.security import PasswordHasher, TokenService
from tests.conftest import TEST_SECRET, FakeClock


@pytest.fixture
def svc(store: InMemoryStore, clock: FakeClock) -> AuthService:
    return AuthService(store, PasswordHasher(4), TokenService(TEST_SECRET, clock), clock)


def status(fn, *args) -> tuple[int, str]:
    with pytest.raises(ApiError) as e:
        fn(*args)
    return e.value.status, e.value.slug


REG = {"email": "Asha@ShopFlow.test", "password": "password-123", "displayName": " Asha "}


def test_register_creates_a_customer_and_ignores_a_requested_role(svc, store):
    user = svc.register({**REG, "role": "ADMIN"})
    assert (user.email, user.display_name, user.role) == ("asha@shopflow.test", "Asha", "CUSTOMER")
    assert store.users[user.id].password_hash.startswith("$2")
    assert "password-123" not in store.users[user.id].password_hash


def test_register_twice_is_409_even_with_different_case(svc):
    svc.register(REG)
    assert status(svc.register, {**REG, "email": "ASHA@shopflow.test"}) == (409, "email-taken")


def test_register_validates(svc):
    assert status(svc.register, {"email": "x", "password": "password-123", "displayName": "A"}) == (
        400,
        "validation-error",
    )
    assert status(svc.register, "not an object") == (400, "validation-error")


def test_login_returns_a_token_for_the_user(svc):
    svc.register(REG)
    result = svc.login({"email": " ASHA@shopflow.test ", "password": "password-123"})
    assert result.user.email == "asha@shopflow.test" and result.access_token.count(".") == 2


def test_login_failures_look_the_same(svc):
    svc.register(REG)
    wrong = status(svc.login, {"email": "asha@shopflow.test", "password": "wrong-password"})
    unknown = status(svc.login, {"email": "nobody@shopflow.test", "password": "password-123"})
    too_long = status(svc.login, {"email": "asha@shopflow.test", "password": "x" * 73})
    assert wrong == unknown == too_long == (401, "invalid-credentials")
    assert status(svc.login, {"email": "asha@shopflow.test"}) == (400, "validation-error")


def test_authenticate_and_optional(svc):
    svc.register(REG)
    token = svc.login({"email": "asha@shopflow.test", "password": "password-123"}).access_token
    assert svc.authenticate(f"Bearer {token}").role == "CUSTOMER"
    assert svc.authenticate(f"bearer   {token}").role == "CUSTOMER"
    assert svc.optional(None) is None
    for bad in ["Basic abc", "Bearer ", "Bearer x.y.z", token]:
        assert status(svc.optional, bad) == (401, "unauthorized")
    assert status(svc.authenticate, None) == (401, "unauthorized")


def test_me_and_deleted_accounts(svc, store):
    user = svc.register(REG)
    assert svc.me(Principal(user.id, "CUSTOMER")).id == user.id
    del store.users[user.id]
    assert status(svc.me, Principal(user.id, "CUSTOMER")) == (401, "unauthorized")


def test_ensure_admin_creates_or_promotes(svc, store):
    admin = svc.ensure_admin("Boss@ShopFlow.test", "admin-password")
    assert admin.role == "ADMIN" and admin.email == "boss@shopflow.test"
    assert svc.ensure_admin("boss@shopflow.test", "ignored-now").id == admin.id
    user = svc.register(REG)
    assert svc.ensure_admin("asha@shopflow.test", "whatever-123").role == "ADMIN"
    assert store.users[user.id].role == "ADMIN"


def test_require_role():
    require_role(Principal(1, "ADMIN"), "ADMIN")
    with pytest.raises(ApiError) as e:
        require_role(Principal(1, "CUSTOMER"), "ADMIN")
    assert e.value.status == 403 and e.value.detail == "requires role ADMIN"
