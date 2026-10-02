package org.aicancode.shopflow.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** Domain errors that map 1:1 to RFC 9457 problem responses (identical in every track). */
public class ApiException extends RuntimeException {

    public static final String PROBLEM_BASE = "https://shopflow.example/problems/";

    private final int status;
    private final String slug;
    private final String title;
    private final String detail;

    public ApiException(int status, String slug, String title, String detail) {
        super(detail == null ? title : detail);
        this.status = status;
        this.slug = slug;
        this.title = title;
        this.detail = detail;
    }

    public static ApiException validation(String detail) {
        return new ApiException(400, "validation-error", "Validation failed", detail);
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(401, "unauthorized", "Unauthorized", detail);
    }

    public static ApiException unauthorized() {
        return unauthorized("authentication required");
    }

    public static ApiException invalidCredentials() {
        return new ApiException(401, "invalid-credentials", "Invalid credentials", "wrong e-mail or password");
    }

    public static ApiException invalidSignature() {
        return new ApiException(401, "invalid-signature", "Invalid signature", "webhook signature is invalid");
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(403, "forbidden", "Forbidden", detail);
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "not-found", "Not found", what + " not found");
    }

    public static ApiException emailTaken() {
        return new ApiException(409, "email-taken", "E-mail already registered", "an account with this e-mail exists");
    }

    public static ApiException skuTaken() {
        return new ApiException(409, "sku-taken", "SKU already exists", "a product with this SKU exists");
    }

    public static ApiException versionConflict(int current) {
        return new ApiException(409, "version-conflict", "Version conflict",
                "the product was changed by someone else (current version " + current + ")");
    }

    public static ApiException insufficientStock(String detail) {
        return new ApiException(409, "insufficient-stock", "Insufficient stock", detail);
    }

    public static ApiException cartEmpty() {
        return new ApiException(409, "cart-empty", "Cart is empty", "add something to your cart before checking out");
    }

    public static ApiException invalidTransition(String detail) {
        return new ApiException(409, "invalid-transition", "Invalid order transition", detail);
    }

    public int status() {
        return status;
    }

    public String slug() {
        return slug;
    }

    public String detail() {
        return detail;
    }

    public Map<String, Object> toProblem() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", PROBLEM_BASE + slug);
        body.put("title", title);
        body.put("status", status);
        if (detail != null) {
            body.put("detail", detail);
        }
        return body;
    }
}
