package org.aicancode.shopflow.domain;

/** Thrown by OrderRepository.placeOrder when a guarded stock decrement fails (nothing is written). */
public class InsufficientStockException extends RuntimeException {

    private final String sku;

    public InsufficientStockException(String sku) {
        super(sku);
        this.sku = sku;
    }

    public String sku() {
        return sku;
    }
}
