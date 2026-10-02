"""M2: the account endpoints over HTTP."""


def test_register_returns_201_and_the_public_user(client):
    r = client.post(
        "/api/v1/auth/register", json={"email": "asha@shopflow.test", "password": "password-123", "displayName": "Asha"}
    )
    assert r.status_code == 201
    assert r.json() == {
        "id": r.json()["id"],
        "email": "asha@shopflow.test",
        "displayName": "Asha",
        "role": "CUSTOMER",
        "createdAt": "2026-10-02T09:30:00.000Z",
    }
    assert "passwordHash" not in r.text and "password-123" not in r.text


def test_login_and_me(api, client):
    session = api.customer(name="Asha")
    assert session["tokenType"] == "Bearer" and session["expiresIn"] == 3600
    assert set(session) == {"accessToken", "tokenType", "expiresIn", "user"}
    me = client.get("/api/v1/me", headers=api.h(session))
    assert me.status_code == 200 and me.json()["displayName"] == "Asha"


def test_me_needs_a_valid_token(client):
    r = client.get("/api/v1/me")
    assert r.status_code == 401 and r.headers["www-authenticate"] == "Bearer"
    assert r.headers["content-type"] == "application/problem+json"
    assert client.get("/api/v1/me", headers={"Authorization": "Bearer nope"}).status_code == 401


def test_wrong_password_is_401_invalid_credentials(api, client):
    api.customer()
    r = client.post("/api/v1/auth/login", json={"email": "user1@shopflow.test", "password": "wrong-password"})
    assert r.status_code == 401 and r.json()["type"].endswith("/invalid-credentials")


def test_bad_json_is_a_400_problem(client):
    r = client.post("/api/v1/auth/register", content=b"{not json", headers={"Content-Type": "application/json"})
    assert r.status_code == 400 and r.json()["detail"] == "request body must be a JSON object"
    r = client.post("/api/v1/auth/login", json=["a"])
    assert r.status_code == 400


def test_duplicate_email_is_409(api, client):
    api.customer()
    r = client.post(
        "/api/v1/auth/register", json={"email": "USER1@shopflow.test", "password": "password-123", "displayName": "X"}
    )
    assert r.status_code == 409 and r.json()["type"].endswith("/email-taken")


def test_admin_is_bootstrapped_from_the_environment(api, client):
    me = client.get("/api/v1/me", headers=api.h(api.admin())).json()
    assert me["role"] == "ADMIN" and me["email"] == "admin@shopflow.test"
