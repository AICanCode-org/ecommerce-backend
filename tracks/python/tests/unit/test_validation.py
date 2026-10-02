"""M1: input validation. The messages are part of the contract (all three tracks use the same ones)."""

import pytest

from app import validation as v
from app.errors import ApiError


def message(fn, *args) -> str:
    with pytest.raises(ApiError) as e:
        fn(*args)
    assert e.value.status == 400 and e.value.slug == "validation-error"
    return e.value.detail


def test_require_object():
    assert v.require_object({"a": 1}) == {"a": 1}
    assert message(v.require_object, [1]) == "request body must be a JSON object"
    assert message(v.require_object, None) == "request body must be a JSON object"


def test_email_is_trimmed_and_lower_cased():
    assert v.email("  Asha@Example.COM ") == "asha@example.com"
    assert message(v.email, None) == "email is required"
    assert message(v.email, "no-at-sign") == "email is invalid"
    assert message(v.email, "a@" + "b" * 250 + ".in") == "email is invalid"


def test_password_rules_count_bytes_not_characters():
    assert v.password(" spaces kept ") == " spaces kept "
    assert message(v.password, 12345678) == "password is required"
    assert message(v.password, "short") == "password must be at least 8 characters"
    assert message(v.password, "é" * 37) == "password must be at most 72 bytes"  # 74 bytes in UTF-8
    assert v.password("é" * 36) == "é" * 36


def test_display_name():
    assert v.display_name("  Asha ") == "Asha"
    assert message(v.display_name, None) == "displayName is required"
    assert message(v.display_name, "   ") == "displayName must be 1-50 characters"
    assert message(v.display_name, "x" * 51) == "displayName must be 1-50 characters"


def test_sku_is_upper_cased():
    assert v.sku(" tee-blk-m ") == "TEE-BLK-M"
    assert message(v.sku, None) == "sku is required"
    for bad in ["AB", "-ABC", "AB_C", "A" * 33, "ÄBC"]:
        assert message(v.sku, bad) == "sku must be 3-32 characters: A-Z, 0-9 and -"


def test_product_name_description_category():
    assert v.product_name("  Kettle ") == "Kettle"
    assert message(v.product_name, "") == "name must be 1-120 characters"
    assert message(v.product_name, 5) == "name is required"
    assert v.description(None) == ""
    assert v.description("  Steel.  ") == "Steel."
    assert message(v.description, 5) == "description must be a string"
    assert message(v.description, "x" * 2001) == "description must be at most 2000 characters"
    assert v.category(" Kitchen ") == "kitchen"
    assert message(v.category, None) == "category is required"
    assert message(v.category, "k") == "category must be 2-30 characters: a-z, 0-9 and -"


def test_money_and_stock_are_integers_not_booleans_or_floats():
    assert v.price_paise(1) == 1 and v.price_paise(100_000_000) == 100_000_000
    for bad in [0, -5, 100_000_001, 99.5, True, "100", None]:
        assert message(v.price_paise, bad) == "pricePaise must be an integer between 1 and 100000000"
    assert v.stock(None) == 0 and v.stock(0) == 0 and v.stock(1_000_000) == 1_000_000
    for bad in [-1, 1_000_001, True, 1.0]:
        assert message(v.stock, bad) == "stock must be an integer between 0 and 1000000"
    assert v.stock_delta(-3) == -3
    for bad in [0, None, True, 1_000_001]:
        assert message(v.stock_delta, bad) == "delta must be a non-zero integer between -1000000 and 1000000"


def test_active_version_quantity():
    assert v.active(False) is False
    assert message(v.active, "yes") == "active must be true or false"
    assert v.version(3) == 3
    for bad in [0, None, True, "1"]:
        assert message(v.version, bad) == "version must be a positive integer"
    assert v.quantity(10) == 10
    for bad in [0, 11, True, 2.0]:
        assert message(v.quantity, bad) == "quantity must be an integer between 1 and 10"


def test_keywords():
    assert v.keywords(None) == []
    assert v.keywords([" Breathable ", "breathable", "light  weight"]) == ["breathable", "light weight"]
    msg = "keywords must be a list of at most 5 words (1-30 characters each)"
    assert message(v.keywords, "x") == msg
    assert message(v.keywords, [1]) == msg
    assert message(v.keywords, [""]) == msg
    assert message(v.keywords, ["a", "b", "c", "d", "e", "f"]) == msg


def test_coupon_code_and_idempotency_key():
    assert v.coupon_code(" save10 ") == "SAVE10"
    assert message(v.coupon_code, None) == "code is required"
    assert message(v.coupon_code, "SAVE-10") == "code is invalid"
    assert v.idempotency_key("a1b2c3d4-e5f6_X") == "a1b2c3d4-e5f6_X"
    assert message(v.idempotency_key, None) == "Idempotency-Key header is required"
    assert message(v.idempotency_key, "") == "Idempotency-Key header is required"
    for bad in ["short", "x" * 65, "has space 123"]:
        assert message(v.idempotency_key, bad) == "Idempotency-Key must be 8-64 characters: A-Z, a-z, 0-9, _ and -"


def test_query_string_filters():
    assert v.limit(None) == 20 and v.limit("50") == 50
    for bad in ["0", "51", "-1", "1e2", "٣"]:  # the last one is an Arabic-Indic digit
        assert message(v.limit, bad) == "limit must be an integer between 1 and 50"
    assert v.price_filter(None, "minPricePaise") is None and v.price_filter("", "x") is None
    assert v.price_filter("0", "minPricePaise") == 0
    assert message(v.price_filter, "-1", "minPricePaise") == (
        "minPricePaise must be an integer between 0 and 100000000 (paise)"
    )
    assert v.bool_filter("true", "inStock") is True and v.bool_filter("false", "inStock") is False
    assert v.bool_filter(None, "inStock") is None
    assert message(v.bool_filter, "yes", "inStock") == "inStock must be true or false"
    assert v.search_query("  ") is None and v.search_query(" kettle ") == "kettle"
    assert message(v.search_query, "x" * 101) == "q must be at most 100 characters"
    assert v.category_filter("Shoes") == "shoes" and v.category_filter(None) is None
    assert message(v.category_filter, "s!") == "category is invalid"
    assert v.order_status_filter("PAID") == "PAID" and v.order_status_filter("") is None
    assert message(v.order_status_filter, "paid") == (
        "status must be one of PENDING_PAYMENT, PAID, SHIPPED, DELIVERED, CANCELLED"
    )
    assert v.order_status_target("SHIPPED") == "SHIPPED"
    assert message(v.order_status_target, "PAID") == "status must be SHIPPED or DELIVERED"


def test_assist_query():
    assert v.assist_query("  shoes ") == "shoes"
    assert message(v.assist_query, None) == "q is required"
    assert message(v.assist_query, "   ") == "q is required"
    assert message(v.assist_query, "x" * 201) == "q must be at most 200 characters"


def test_path_id_that_cannot_exist_is_404():
    assert v.path_id("42", "order") == 42
    for bad in ["abc", "-1", "1" * 19, "٣"]:
        with pytest.raises(ApiError) as e:
            v.path_id(bad, "order")
        assert e.value.status == 404 and e.value.detail == "order not found"


def test_cursor_round_trip_and_garbage():
    assert v.encode_cursor(42) == "cDQy"  # base64url("p42"), no padding
    assert v.decode_cursor(v.encode_cursor(123456)) == 123456
    assert v.decode_cursor(None) is None and v.decode_cursor("") is None
    for bad in ["!!!", "cA", "eDQy", "cDQyYQ", "a" * 41]:
        assert message(v.decode_cursor, bad) == "cursor is invalid"
