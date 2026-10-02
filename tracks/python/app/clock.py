"""Time. Services never call datetime.now() directly: they get a clock, so tests can travel in time."""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime

Clock = Callable[[], datetime]


def truncate_ms(dt: datetime) -> datetime:
    """Millisecond precision everywhere: the API, the tests and the database agree."""
    return dt.replace(microsecond=dt.microsecond // 1000 * 1000)


def utc_now() -> datetime:
    return truncate_ms(datetime.now(UTC))


def iso(dt: datetime | None) -> str | None:
    """ISO-8601 UTC with millisecond precision and a 'Z' suffix, identical in every track."""
    if dt is None:
        return None
    return dt.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
