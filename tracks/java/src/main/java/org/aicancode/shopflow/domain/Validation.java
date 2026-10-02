package org.aicancode.shopflow.domain;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Input validation. Every method returns the clean value or throws a 400 validation-error. The messages are part
 * of the contract: the tests (and the other two tracks) expect them exactly. Request bodies arrive as plain Jackson
 * values (Map, List, String, Integer, Long, BigInteger, Double, Boolean, null).
 */
public final class Validation {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern SKU = Pattern.compile("^[A-Z0-9][A-Z0-9-]{2,31}$");
    private static final Pattern CATEGORY = Pattern.compile("^[a-z0-9][a-z0-9-]{1,29}$");
    private static final Pattern COUPON = Pattern.compile("^[A-Z0-9]{3,20}$");
    public static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Pattern KEYWORD = Pattern.compile("^[a-z0-9][a-z0-9 -]{0,29}$");
    private static final Pattern CURSOR = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");
    public static final long MAX_PRICE_PAISE = 100_000_000; // ₹10 lakh
    public static final long MAX_STOCK = 1_000_000;
    public static final long MAX_QUANTITY = 10;
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    private Validation() {
    }

    /** Integer or Long only: never a Boolean, a Double (1.0) or a String ("1"). */
    private static Long integer(Object value) {
        return value instanceof Integer || value instanceof Long ? ((Number) value).longValue() : null;
    }

    /** A string of ASCII digits as a number, capped so that it never overflows. */
    public static long digits(String value) {
        // TODO(phase-3 M1): helper: an ASCII-digits-only query string as a long, or null when it is not one.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.digits");
    }

    private static boolean isDigits(String value) {
        return DIGITS.matcher(value).matches();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> requireObject(Object payload) {
        if (!(payload instanceof Map)) {
            throw ApiException.validation("request body must be a JSON object");
        }
        return (Map<String, Object>) payload;
    }

    // ---- accounts ----
    public static String email(Object value) {
        // TODO(phase-3 M1): a string (400 'email is required'); trim + lower-case; must match EMAIL_RE and be
        //   at most 254 characters (400 'email is invalid').
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.email");
    }

    public static String password(Object value) {
        // TODO(phase-3 M1): a string of at least 8 characters and at most 72 BYTES in UTF-8 (bcrypt ignores
        //   the rest): 400 otherwise.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.password");
    }

    public static String displayName(Object value) {
        // TODO(phase-3 M1): a string, trimmed, 1-50 characters (count code points, not UTF-16 units).
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.displayName");
    }

    // ---- catalog ----
    public static String sku(Object value) {
        // TODO(phase-3 M1): a string, trimmed and UPPER-cased, matching SKU_RE (3-32 characters: A-Z, 0-9 and
        //   -).
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.sku");
    }

    public static String productName(Object value) {
        // TODO(phase-3 M1): a string, trimmed, 1-120 characters.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.productName");
    }

    public static String description(Object value) {
        // TODO(phase-3 M1): optional: null/missing -> empty string; otherwise a string (trimmed) of at most
        //   2000 characters.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.description");
    }

    public static String category(Object value) {
        // TODO(phase-3 M1): a string, trimmed and lower-cased, matching CATEGORY_RE (2-30 characters: a-z,
        //   0-9 and -).
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.category");
    }

    public static long pricePaise(Object value) {
        // TODO(phase-3 M1): an INTEGER (not a float, not a boolean, not a string) between 1 and
        //   MAX_PRICE_PAISE. Money is always integer paise (ADR-0001).
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.pricePaise");
    }

    public static int stock(Object value) {
        // TODO(phase-3 M1): an integer between 0 and MAX_STOCK (booleans are not integers).
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.stock");
    }

    public static int stockDelta(Object value) {
        // TODO(phase-3 M1): a non-zero integer between -MAX_STOCK and MAX_STOCK.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.stockDelta");
    }

    public static boolean active(Object value) {
        // TODO(phase-3 M1): a real boolean: true or false, nothing else.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.active");
    }

    public static int version(Object value) {
        // TODO(phase-3 M1): a positive integer (the version you read; optimistic locking, ADR-0003).
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.version");
    }

    /** Optional hints for an AI description draft: at most 5, each 1-30 characters, de-duplicated. */
    public static List<String> keywords(Object value) {
        // TODO(phase-3 M1): optional list (missing -> []); at most 5 strings, each lower-cased + trimmed and
        //   matching KEYWORD_RE; drop duplicates, keep the order.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.keywords");
    }

    // ---- cart + checkout ----
    public static int quantity(Object value) {
        // TODO(phase-3 M1): an integer between 1 and MAX_QUANTITY (10).
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.quantity");
    }

    public static String couponCode(Object value) {
        // TODO(phase-3 M1): a string (400 'code is required'), trimmed + UPPER-cased, matching COUPON_RE (400
        //   'code is invalid').
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.couponCode");
    }

    public static String idempotencyKey(String value) {
        // TODO(phase-3 M1): the Idempotency-Key header: missing/blank -> 400 'Idempotency-Key header is
        //   required'; must match IDEMPOTENCY_KEY_RE (8-64 characters).
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.idempotencyKey");
    }

    public static String orderStatusTarget(Object value) {
        // TODO(phase-3 M1): only SHIPPED or DELIVERED can be set by an admin (cancel has its own endpoint,
        //   PAID comes from the webhook).
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.orderStatusTarget");
    }

    // ---- query strings ----
    public static int limit(String value) {
        // TODO(phase-3 M1): a query string: missing -> the default (20); ASCII digits only, 1..maximum (50);
        //   anything else 400.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.limit");
    }

    public static Long priceFilter(String value, String name) {
        // TODO(phase-3 M1): a query string: missing -> null; ASCII digits only, 0..MAX_PRICE_PAISE, else 400
        //   naming the parameter.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.priceFilter");
    }

    public static Boolean boolFilter(String value, String name) {
        // TODO(phase-3 M1): a query string: missing -> null; exactly 'true' or 'false', else 400 naming the
        //   parameter.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.boolFilter");
    }

    public static String searchQuery(String value) {
        // TODO(phase-3 M1): missing or blank -> null; trimmed, at most 100 characters.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.searchQuery");
    }

    public static String assistQuery(String value) {
        // TODO(phase-3 M1): required (400 'q is required'), trimmed, at most 200 characters.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.assistQuery");
    }

    public static String categoryFilter(String value) {
        // TODO(phase-3 M1): missing -> null; lower-cased, must match CATEGORY_RE.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.categoryFilter");
    }

    public static String orderStatusFilter(String value) {
        // TODO(phase-3 M1): missing -> null; upper-cased, one of ORDER_STATUSES.
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.orderStatusFilter");
    }

    /** An id that cannot exist is a 404, not a 400: /orders/abc is simply not there. */
    public static long pathId(String value, String what) {
        // TODO(phase-3 M1): a path segment: ASCII digits only and a positive id that fits in 63 bits;
        //   anything else is 404 for 'what' (not 400: /orders/abc simply does not exist).
        throw new UnsupportedOperationException("TODO(phase-3 M1): Validation.pathId");
    }

    // ---- keyset cursor (opaque to clients) ----
    public static String encodeCursor(long lastId) {
        // TODO(phase-3 M3): opaque cursor: base64url (no padding) of 'p' + the last id shown.
        throw new UnsupportedOperationException("TODO(phase-3 M3): Validation.encodeCursor");
    }

    public static Long decodeCursor(String value) {
        // TODO(phase-3 M3): missing -> null; must match CURSOR_RE, decode base64url, be 'p' + digits with a
        //   positive id; anything else 400 'cursor is invalid'.
        throw new UnsupportedOperationException("TODO(phase-3 M3): Validation.decodeCursor");
    }
}
