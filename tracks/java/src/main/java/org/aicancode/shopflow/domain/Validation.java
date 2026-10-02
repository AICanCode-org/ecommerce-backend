package org.aicancode.shopflow.domain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
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
        return new BigInteger(value).min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
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
        if (!(value instanceof String s)) {
            throw ApiException.validation("email is required");
        }
        String clean = s.strip().toLowerCase(Locale.ROOT);
        if (Text.chars(clean) > 254 || !EMAIL.matcher(clean).matches()) {
            throw ApiException.validation("email is invalid");
        }
        return clean;
    }

    public static String password(Object value) {
        if (!(value instanceof String s)) {
            throw ApiException.validation("password is required");
        }
        if (Text.chars(s) < 8) {
            throw ApiException.validation("password must be at least 8 characters");
        }
        if (s.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.validation("password must be at most 72 bytes");
        }
        return s;
    }

    public static String displayName(Object value) {
        if (!(value instanceof String s)) {
            throw ApiException.validation("displayName is required");
        }
        String clean = s.strip();
        if (clean.isEmpty() || Text.chars(clean) > 50) {
            throw ApiException.validation("displayName must be 1-50 characters");
        }
        return clean;
    }

    // ---- catalog ----
    public static String sku(Object value) {
        if (!(value instanceof String s)) {
            throw ApiException.validation("sku is required");
        }
        String clean = s.strip().toUpperCase(Locale.ROOT);
        if (!SKU.matcher(clean).matches()) {
            throw ApiException.validation("sku must be 3-32 characters: A-Z, 0-9 and -");
        }
        return clean;
    }

    public static String productName(Object value) {
        if (!(value instanceof String s)) {
            throw ApiException.validation("name is required");
        }
        String clean = s.strip();
        if (clean.isEmpty() || Text.chars(clean) > 120) {
            throw ApiException.validation("name must be 1-120 characters");
        }
        return clean;
    }

    public static String description(Object value) {
        if (value == null) {
            return "";
        }
        if (!(value instanceof String s)) {
            throw ApiException.validation("description must be a string");
        }
        String clean = s.strip();
        if (Text.chars(clean) > 2000) {
            throw ApiException.validation("description must be at most 2000 characters");
        }
        return clean;
    }

    public static String category(Object value) {
        if (!(value instanceof String s)) {
            throw ApiException.validation("category is required");
        }
        String clean = s.strip().toLowerCase(Locale.ROOT);
        if (!CATEGORY.matcher(clean).matches()) {
            throw ApiException.validation("category must be 2-30 characters: a-z, 0-9 and -");
        }
        return clean;
    }

    public static long pricePaise(Object value) {
        Long n = integer(value);
        if (n == null || n < 1 || n > MAX_PRICE_PAISE) {
            throw ApiException.validation("pricePaise must be an integer between 1 and " + MAX_PRICE_PAISE);
        }
        return n;
    }

    public static int stock(Object value) {
        if (value == null) {
            return 0;
        }
        Long n = integer(value);
        if (n == null || n < 0 || n > MAX_STOCK) {
            throw ApiException.validation("stock must be an integer between 0 and " + MAX_STOCK);
        }
        return n.intValue();
    }

    public static int stockDelta(Object value) {
        Long n = integer(value);
        if (n == null || n == 0 || Math.abs(n) > MAX_STOCK) {
            throw ApiException.validation("delta must be a non-zero integer between -" + MAX_STOCK + " and " + MAX_STOCK);
        }
        return n.intValue();
    }

    public static boolean active(Object value) {
        if (!(value instanceof Boolean b)) {
            throw ApiException.validation("active must be true or false");
        }
        return b;
    }

    public static int version(Object value) {
        Long n = integer(value);
        if (n == null || n < 1 || n > Integer.MAX_VALUE) {
            throw ApiException.validation("version must be a positive integer");
        }
        return n.intValue();
    }

    /** Optional hints for an AI description draft: at most 5, each 1-30 characters, de-duplicated. */
    public static List<String> keywords(Object value) {
        String message = "keywords must be a list of at most 5 words (1-30 characters each)";
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw ApiException.validation(message);
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String s)) {
                throw ApiException.validation(message);
            }
            String clean = Text.collapse(s.toLowerCase(Locale.ROOT));
            if (!KEYWORD.matcher(clean).matches()) {
                throw ApiException.validation(message);
            }
            if (!out.contains(clean)) {
                out.add(clean);
            }
        }
        if (out.size() > 5) {
            throw ApiException.validation(message);
        }
        return List.copyOf(out);
    }

    // ---- cart + checkout ----
    public static int quantity(Object value) {
        Long n = integer(value);
        if (n == null || n < 1 || n > MAX_QUANTITY) {
            throw ApiException.validation("quantity must be an integer between 1 and " + MAX_QUANTITY);
        }
        return n.intValue();
    }

    public static String couponCode(Object value) {
        if (!(value instanceof String s)) {
            throw ApiException.validation("code is required");
        }
        String clean = s.strip().toUpperCase(Locale.ROOT);
        if (!COUPON.matcher(clean).matches()) {
            throw ApiException.validation("code is invalid");
        }
        return clean;
    }

    public static String idempotencyKey(String value) {
        if (value == null || value.isEmpty()) {
            throw ApiException.validation("Idempotency-Key header is required");
        }
        if (!IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw ApiException.validation("Idempotency-Key must be 8-64 characters: A-Z, a-z, 0-9, _ and -");
        }
        return value;
    }

    public static String orderStatusTarget(Object value) {
        if (!"SHIPPED".equals(value) && !"DELIVERED".equals(value)) {
            throw ApiException.validation("status must be SHIPPED or DELIVERED");
        }
        return (String) value;
    }

    // ---- query strings ----
    public static int limit(String value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        if (!isDigits(value) || digits(value) < 1 || digits(value) > MAX_LIMIT) {
            throw ApiException.validation("limit must be an integer between 1 and " + MAX_LIMIT);
        }
        return (int) digits(value);
    }

    public static Long priceFilter(String value, String name) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (!isDigits(value) || digits(value) > MAX_PRICE_PAISE) {
            throw ApiException.validation(name + " must be an integer between 0 and " + MAX_PRICE_PAISE + " (paise)");
        }
        return digits(value);
    }

    public static Boolean boolFilter(String value, String name) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (!"true".equals(value) && !"false".equals(value)) {
            throw ApiException.validation(name + " must be true or false");
        }
        return "true".equals(value);
    }

    public static String searchQuery(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.strip();
        if (clean.isEmpty()) {
            return null;
        }
        if (Text.chars(clean) > 100) {
            throw ApiException.validation("q must be at most 100 characters");
        }
        return clean;
    }

    public static String assistQuery(String value) {
        String clean = value == null ? "" : value.strip();
        if (clean.isEmpty()) {
            throw ApiException.validation("q is required");
        }
        if (Text.chars(clean) > 200) {
            throw ApiException.validation("q must be at most 200 characters");
        }
        return clean;
    }

    public static String categoryFilter(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String clean = value.toLowerCase(Locale.ROOT);
        if (!CATEGORY.matcher(clean).matches()) {
            throw ApiException.validation("category is invalid");
        }
        return clean;
    }

    public static String orderStatusFilter(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (!Models.ORDER_STATUSES.contains(value)) {
            throw ApiException.validation("status must be one of " + String.join(", ", Models.ORDER_STATUSES));
        }
        return value;
    }

    /** An id that cannot exist is a 404, not a 400: /orders/abc is simply not there. */
    public static long pathId(String value, String what) {
        if (value == null || !isDigits(value) || value.length() > 18) {
            throw ApiException.notFound(what);
        }
        return Long.parseLong(value);
    }

    // ---- keyset cursor (opaque to clients) ----
    public static String encodeCursor(long lastId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("p" + lastId).getBytes(StandardCharsets.US_ASCII));
    }

    public static Long decodeCursor(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (!CURSOR.matcher(value).matches()) {
            throw ApiException.validation("cursor is invalid");
        }
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(value + "=".repeat((4 - value.length() % 4) % 4));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("cursor is invalid");
        }
        for (byte b : raw) {
            if (b < 0) {
                throw ApiException.validation("cursor is invalid"); // not ASCII
            }
        }
        String text = new String(raw, StandardCharsets.US_ASCII);
        if (!text.startsWith("p") || !isDigits(text.substring(1)) || text.length() > 19) {
            throw ApiException.validation("cursor is invalid");
        }
        return Long.parseLong(text.substring(1));
    }
}
