package org.aicancode.shopflow.domain;

/** Thrown by a repository when a unique constraint (e-mail, SKU, idempotency key) is violated. */
public class DuplicateException extends RuntimeException {

    public DuplicateException(String what) {
        super(what);
    }
}
