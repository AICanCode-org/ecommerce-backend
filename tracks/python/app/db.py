"""Connection pool + a tiny, transparent SQL migrator shared in spirit by all tracks.

Why not Alembic/Flyway? The three tracks share the SAME .sql files in spec/db/migrations,
so a 30-line migrator keeps them in lock-step. It records applied versions in
schema_migrations and uses a Postgres advisory lock so two app instances can't race.
"""

from __future__ import annotations

import logging
import re
from pathlib import Path

from psycopg_pool import ConnectionPool

log = logging.getLogger("shopflow.db")
MIGRATION_LOCK_ID = 737_2026
_FILE_PATTERN = re.compile(r"^V(\d+)__.+\.sql$")


def create_pool(database_url: str) -> ConnectionPool:
    # timeout=5: when the database is down a request fails after 5 s instead of hanging for 30 s.
    pool = ConnectionPool(database_url, min_size=1, max_size=10, open=False, timeout=5, kwargs={"autocommit": True})
    pool.open(wait=True, timeout=30)
    return pool


def pending_migrations(directory: str) -> list[tuple[int, Path]]:
    files = []
    for path in Path(directory).glob("*.sql"):
        match = _FILE_PATTERN.match(path.name)
        if match:
            files.append((int(match.group(1)), path))
    return sorted(files)


def run_migrations(pool: ConnectionPool, directory: str) -> list[int]:
    applied_now: list[int] = []
    with pool.connection() as conn:
        conn.execute("SELECT pg_advisory_lock(%s)", (MIGRATION_LOCK_ID,))
        try:
            conn.execute(
                "CREATE TABLE IF NOT EXISTS schema_migrations ("
                " version INTEGER PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())"
            )
            done = {row[0] for row in conn.execute("SELECT version FROM schema_migrations").fetchall()}
            for version, path in pending_migrations(directory):
                if version in done:
                    continue
                log.info("applying migration %s", path.name)
                with conn.transaction():
                    conn.execute(path.read_text(encoding="utf-8"))
                    conn.execute("INSERT INTO schema_migrations (version) VALUES (%s)", (version,))
                applied_now.append(version)
        finally:
            conn.execute("SELECT pg_advisory_unlock(%s)", (MIGRATION_LOCK_ID,))
    return applied_now
