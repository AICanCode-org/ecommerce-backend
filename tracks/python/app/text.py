"""Small text helpers shared by the AI features (identical rules in every track)."""

from __future__ import annotations

import re

_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")


def collapse(text: str) -> str:
    """Runs of whitespace -> one space, trimmed."""
    return " ".join(text.split())


def shorten(text: str, limit: int) -> str:
    """At most `limit` characters (code points): longer text is cut to limit-3 (no trailing space) + '...'."""
    if len(text) <= limit:
        return text
    return text[: limit - 3].rstrip() + "..."


def first_sentence(text: str) -> str:
    clean = collapse(text)
    return _SENTENCE_END.split(clean, maxsplit=1)[0] if clean else ""
