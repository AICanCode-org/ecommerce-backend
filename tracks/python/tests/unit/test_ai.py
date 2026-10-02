"""M6: AI features - deterministic offline fallbacks, strict validation of LLM answers."""

import json
from datetime import UTC, datetime

import httpx
import pytest

from app.ai import (
    DescriptionService,
    LlmClient,
    LlmParser,
    LlmWriter,
    RuleParser,
    SearchAssistService,
    TemplateWriter,
    match_category,
    parse_filters,
)
from app.models import Product, SearchFilters

CATS = ["bags", "kitchen", "shoes"]
NOW = datetime(2026, 10, 2, tzinfo=UTC)


def product(**kw) -> Product:
    base = dict(id=1, sku="SHOE-1", name="Trail Running Shoes", description="Light and grippy. Made for wet trails.",
                category="shoes", price_paise=249_900, stock=5, active=True, version=1, created_at=NOW,
                updated_at=NOW)  # fmt: skip
    return Product(**{**base, **kw})


class FakeChat:
    def __init__(self, answer: str | Exception):
        self.answer = answer
        self.calls: list[tuple[str, str]] = []

    def chat(self, system, user, max_tokens):
        self.calls.append((system, user))
        if isinstance(self.answer, Exception):
            raise self.answer
        return self.answer


def parse(q: str) -> SearchFilters:
    return RuleParser().parse(q, CATS)


# ---- search assist: rules ----
def test_rules_extract_price_category_and_stock():
    assert parse("red running shoes under ₹3,000 in stock") == SearchFilters(
        ["red", "running"], "shoes", None, 300_000, True
    )
    assert parse("kitchen stuff between 500 and 1500") == SearchFilters(["stuff"], "kitchen", 50_000, 150_000, None)
    assert parse("bag 2k-1k") == SearchFilters([], "bags", 100_000, 200_000, None)
    assert parse("Rs. 999 or more than 200 shoe") == SearchFilters([], "shoes", 20_000, None, None)


def test_rules_handle_many_phrasings():
    assert parse("show me a laptop backpack below rs 2500").max_price_paise == 250_000
    assert parse("kettle < 1200").max_price_paise == 120_000
    assert parse("pan over 1000").min_price_paise == 100_000
    assert parse("anything at least 300 available") == SearchFilters(["anything"], None, 30_000, None, True)
    assert parse("1000 to 2000 inr shoes").min_price_paise == 100_000


def test_rules_terms_are_capped_deduplicated_and_skip_stopwords():
    f = parse("the best new cotton cotton tee for men with pockets and zip and hood")
    assert f.terms == ["cotton", "tee", "men", "pockets", "zip"]
    assert parse("12345 678").terms == []


def test_rules_cap_prices():
    assert parse("under 99999999").max_price_paise == 100_000_000


def test_category_matching_handles_plurals():
    assert match_category("shoe", CATS) == "shoes"
    assert match_category("bag", CATS) == "bags"
    assert match_category("kitchens", CATS) == "kitchen"
    assert match_category("hat", CATS) is None


# ---- search assist: LLM answers are untrusted input ----
def test_parse_filters_accepts_a_clean_answer():
    raw = (
        'Sure! {"terms": ["Red", "red", "running"], "category": "shoes", "minPrice": null, "maxPrice": 3000, '
        '"inStock": true}'
    )
    assert parse_filters(raw, CATS) == SearchFilters(["red", "running"], "shoes", None, 300_000, True)
    assert parse_filters('{"terms": [], "inStock": false}', CATS) == SearchFilters([], None, None, None, None)


@pytest.mark.parametrize(
    "raw",
    [
        "no json here",
        '["a"]',
        '{"terms": "shoes"}',
        '{"terms": ["a", "b", "c", "d", "e", "f"]}',
        '{"terms": ["DROP TABLE products;"]}',
        '{"category": "weapons"}',
        '{"maxPrice": -1}',
        '{"maxPrice": 1.5}',
        '{"maxPrice": true}',
        '{"minPrice": 500, "maxPrice": 100}',
        '{"inStock": "yes"}',
    ],
)
def test_parse_filters_rejects_anything_unexpected(raw):
    with pytest.raises((ValueError, json.JSONDecodeError)):
        parse_filters(raw, CATS)


def test_assist_service_uses_the_llm_and_falls_back():
    llm = FakeChat('{"terms": ["sneakers"], "category": "shoes", "maxPrice": 2000}')
    result = SearchAssistService(RuleParser(), LlmParser(llm)).interpret("sneakers under 2000", CATS)
    assert (result.provider, result.fallback, result.filters.terms) == ("llm", False, ["sneakers"])
    assert "Allowed categories: bags, kitchen, shoes" in llm.calls[0][0]
    assert llm.calls[0][1] == "<query>\nsneakers under 2000\n</query>"
    broken = SearchAssistService(RuleParser(), LlmParser(FakeChat('{"category": "weapons"}')))
    result = broken.interpret("sneakers under 2000", CATS)
    assert (result.provider, result.fallback, result.filters.max_price_paise) == ("rules", True, 200_000)
    offline = SearchAssistService(RuleParser()).interpret("bags", CATS)
    assert (offline.provider, offline.fallback) == ("rules", False)


# ---- description drafts ----
def test_template_writer_is_deterministic():
    assert TemplateWriter().write(product(), ["breathable", "vegan"]) == (
        "Trail Running Shoes from our shoes range. Light and grippy. Highlights: breathable, vegan. "
        "In stock and ready to ship."
    )
    assert TemplateWriter().write(product(description="", stock=0), []) == (
        "Trail Running Shoes from our shoes range. Currently out of stock."
    )
    long = TemplateWriter().write(product(description="x" * 2000), [])
    assert len(long) == 600 and long.endswith("...")


def test_description_service_llm_and_fallback():
    llm = FakeChat("  A light trail shoe.\n\nGrips wet rock.  ")
    result = DescriptionService(TemplateWriter(), LlmWriter(llm)).draft(product(), ["vegan"])
    assert (result.provider, result.fallback, result.description) == (
        "llm",
        False,
        "A light trail shoe. Grips wet rock.",
    )
    assert "Keywords: vegan" in llm.calls[0][1] and llm.calls[0][1].startswith("<product>")
    failing = DescriptionService(TemplateWriter(), LlmWriter(FakeChat(TimeoutError("slow"))))
    result = failing.draft(product(), [])
    assert (result.provider, result.fallback) == ("template", True)
    empty = DescriptionService(TemplateWriter(), LlmWriter(FakeChat("   "))).draft(product(), [])
    assert empty.fallback


# ---- the HTTP client (OpenAI-compatible) ----
def test_llm_client_posts_chat_completions():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["auth"] = request.headers.get("authorization")
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"choices": [{"message": {"content": " hi "}}]})

    client = LlmClient("http://llm.test/v1", "tiny", "k-123", 1000, transport=httpx.MockTransport(handler))
    assert client.chat("sys", "user", 50) == "hi"
    assert seen["url"] == "http://llm.test/v1/chat/completions" and seen["auth"] == "Bearer k-123"
    assert seen["body"]["model"] == "tiny" and seen["body"]["temperature"] == 0 and seen["body"]["max_tokens"] == 50


def test_llm_client_raises_on_errors_and_empty_answers():
    def bad(request):
        return httpx.Response(500)

    with pytest.raises(httpx.HTTPStatusError):
        LlmClient("http://llm.test/v1", "m", None, 1000, transport=httpx.MockTransport(bad)).chat("s", "u", 5)

    def empty(request):
        return httpx.Response(200, json={"choices": [{"message": {"content": "  "}}]})

    with pytest.raises(ValueError):
        LlmClient("http://llm.test/v1", "m", None, 1000, transport=httpx.MockTransport(empty)).chat("s", "u", 5)
