"""M2: passwords, access tokens and webhook signatures."""

from datetime import UTC, datetime

import jwt
import pytest

from app.errors import ApiError
from app.models import User
from app.security import (
    PasswordHasher,
    TokenService,
    new_payment_id,
    resolve_secret,
    sign_webhook,
    verify_webhook,
)
from tests.conftest import TEST_SECRET, FakeClock

USER = User(7, "asha@shopflow.test", "x", "Asha", "CUSTOMER", datetime(2026, 1, 1, tzinfo=UTC))


def test_bcrypt_hashes_are_salted_and_verify():
    hasher = PasswordHasher(4)
    a, b = hasher.hash("password-123"), hasher.hash("password-123")
    assert a != b and a.startswith("$2")
    assert hasher.verify("password-123", a)
    assert not hasher.verify("wrong-password", a)
    assert not hasher.verify("password-123", None)  # unknown user: still runs bcrypt, still False
    assert not hasher.verify("password-123", "not-a-bcrypt-hash")


def test_bcrypt_cost_is_bounded():
    with pytest.raises(ValueError):
        PasswordHasher(3)
    with pytest.raises(ValueError):
        PasswordHasher(16)


def test_secret_must_be_long_enough():
    assert resolve_secret(TEST_SECRET) == TEST_SECRET
    assert len(resolve_secret(None)) >= 32
    with pytest.raises(ValueError):
        resolve_secret("too-short")


def test_access_token_round_trip_and_claims():
    clock = FakeClock()
    tokens = TokenService(TEST_SECRET, clock)
    token = tokens.issue_access_token(USER)
    claims = jwt.decode(token, TEST_SECRET, algorithms=["HS256"], options={"verify_exp": False})
    assert claims["sub"] == "7" and claims["role"] == "CUSTOMER" and claims["iss"] == "shopflow"
    assert claims["exp"] - claims["iat"] == 3600
    principal = tokens.verify_access_token(token)
    assert (principal.user_id, principal.role) == (7, "CUSTOMER")


def test_expired_token_is_rejected_using_the_injected_clock():
    clock = FakeClock()
    tokens = TokenService(TEST_SECRET, clock)
    token = tokens.issue_access_token(USER)
    clock.advance(minutes=59)
    tokens.verify_access_token(token)
    clock.advance(minutes=1)
    with pytest.raises(ApiError) as e:
        tokens.verify_access_token(token)
    assert e.value.status == 401


@pytest.mark.parametrize(
    "token",
    [
        jwt.encode(
            {"sub": "7", "role": "ADMIN", "iss": "shopflow", "exp": 4_000_000_000}, "another-secret-32-bytes-long-xx"
        ),
        jwt.encode({"sub": "7", "role": "ADMIN", "iss": "evil", "exp": 4_000_000_000}, TEST_SECRET),
        jwt.encode({"sub": "7", "role": "ROOT", "iss": "shopflow", "exp": 4_000_000_000}, TEST_SECRET),
        jwt.encode({"sub": "x", "role": "ADMIN", "iss": "shopflow", "exp": 4_000_000_000}, TEST_SECRET),
        jwt.encode({"sub": "7", "role": "ADMIN", "iss": "shopflow"}, TEST_SECRET),
        jwt.encode({"sub": "7", "role": "ADMIN", "iss": "shopflow", "exp": 4_000_000_000}, None, algorithm="none"),
        "not.a.jwt",
    ],
)
def test_forged_or_malformed_tokens_are_401(token):
    tokens = TokenService(TEST_SECRET, FakeClock())
    with pytest.raises(ApiError) as e:
        tokens.verify_access_token(token)
    assert e.value.status == 401 and e.value.detail == "invalid or expired token"


def test_webhook_signature_is_hmac_sha256_of_the_raw_body():
    body = b'{"id":"evt_1"}'
    signature = sign_webhook("s" * 32, body)
    assert signature.startswith("sha256=") and len(signature) == 7 + 64
    assert verify_webhook("s" * 32, body, signature)
    assert not verify_webhook("s" * 32, body + b" ", signature)  # one extra byte: different body
    assert not verify_webhook("t" * 32, body, signature)
    assert not verify_webhook("s" * 32, body, None)
    assert not verify_webhook("s" * 32, body, signature[7:])  # missing the sha256= prefix


def test_known_signature_vector():
    # Same vector in every track: HMAC-SHA256(key="key", "The quick brown fox jumps over the lazy dog")
    body = b"The quick brown fox jumps over the lazy dog"
    assert sign_webhook("key", body) == "sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"


def test_payment_ids_are_random():
    a, b = new_payment_id(), new_payment_id()
    assert a != b and a.startswith("pay_") and len(a) == 28
