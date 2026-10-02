"""Works from day one: the starter already serves /health (no business logic needed)."""

from fastapi.testclient import TestClient

from app.main import create_app
from app.repository import InMemoryStore
from tests.conftest import test_settings


def test_health_reports_the_database():
    store = InMemoryStore()
    app = create_app(test_settings(), users=store, products=store, carts=store, orders=store)
    with TestClient(app) as client:
        assert client.get("/health").json() == {"status": "UP", "checks": {"database": "UP"}}
        store.healthy = False
        r = client.get("/health")
    assert r.status_code == 503
    assert r.json() == {"status": "DOWN", "checks": {"database": "DOWN"}}


def test_unknown_routes_are_problem_json():
    store = InMemoryStore()
    app = create_app(test_settings(), users=store, products=store, carts=store, orders=store)
    with TestClient(app) as client:
        r = client.get("/api/v1/nope")
    assert r.status_code == 404 and r.headers["content-type"] == "application/problem+json"
    assert r.json() == {
        "type": "https://shopflow.example/problems/not-found",
        "title": "Not found",
        "status": 404,
        "detail": "route not found",
    }
