"""Configuration from environment variables (12-factor). Same names in every track."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _default_migrations_dir() -> str:
    """tracks/python/app/config.py -> <repo>/spec/db/migrations (only used outside Docker)."""
    parents = Path(__file__).resolve().parents
    return str(parents[3] / "spec" / "db" / "migrations") if len(parents) > 3 else "migrations"


@dataclass(frozen=True)
class Settings:
    database_url: str
    port: int
    migrations_dir: str
    jwt_secret: str | None  # None -> a random per-process secret (dev only, logged as a warning)
    bcrypt_cost: int
    admin_email: str | None
    admin_password: str | None
    payment_webhook_secret: str | None  # None -> a random per-process secret (dev only)
    ai_provider: str  # "offline" | "llm"
    llm_base_url: str
    llm_api_key: str | None
    llm_model: str
    llm_timeout_ms: int

    @staticmethod
    def from_env() -> Settings:
        return Settings(
            database_url=os.getenv("DATABASE_URL", "postgresql://shopflow:shopflow@localhost:5432/shopflow"),
            port=int(os.getenv("PORT", "8080")),
            migrations_dir=os.getenv("MIGRATIONS_DIR") or _default_migrations_dir(),
            jwt_secret=os.getenv("JWT_SECRET") or None,
            bcrypt_cost=int(os.getenv("BCRYPT_COST", "10")),
            admin_email=os.getenv("ADMIN_EMAIL") or None,
            admin_password=os.getenv("ADMIN_PASSWORD") or None,
            payment_webhook_secret=os.getenv("PAYMENT_WEBHOOK_SECRET") or None,
            ai_provider=os.getenv("AI_PROVIDER", "offline").lower(),
            llm_base_url=os.getenv("LLM_BASE_URL", "http://localhost:11434/v1").rstrip("/"),
            llm_api_key=os.getenv("LLM_API_KEY") or None,
            llm_model=os.getenv("LLM_MODEL", "llama3.2:1b"),
            llm_timeout_ms=int(os.getenv("LLM_TIMEOUT_MS", "5000")),
        )
