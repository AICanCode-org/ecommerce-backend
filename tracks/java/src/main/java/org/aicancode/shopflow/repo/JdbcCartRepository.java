package org.aicancode.shopflow.repo;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import org.aicancode.shopflow.domain.Models.CartState;
import org.aicancode.shopflow.domain.Models.Coupon;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL adapter for carts and coupons. */
public class JdbcCartRepository implements CartRepository {

    private static final String PRODUCT_COLS = Arrays.stream(JdbcProductRepository.COLS.split(","))
            .map(c -> "p." + c.strip()).collect(Collectors.joining(", "));
    private final JdbcClient jdbc;

    public JdbcCartRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CartState cart(long userId) {
        // TODO(phase-3 M4): the user's cart lines joined with their products (ORDER BY added_at, product id)
        //   and the cart's coupon code; no cart row -> empty.
        throw new UnsupportedOperationException("TODO(phase-3 M4): JdbcCartRepository.cart");
    }

    @Override
    public void setCartItem(long userId, long productId, int quantity, Instant at) {
        // TODO(phase-3 M4): upsert the cart row, then INSERT ... ON CONFLICT (user_id, product_id) DO UPDATE
        //   SET quantity = EXCLUDED.quantity.
        throw new UnsupportedOperationException("TODO(phase-3 M4): JdbcCartRepository.setCartItem");
    }

    @Override
    public void removeCartItem(long userId, long productId) {
        // TODO(phase-3 M4): DELETE the line (no error when it is not there).
        throw new UnsupportedOperationException("TODO(phase-3 M4): JdbcCartRepository.removeCartItem");
    }

    @Override
    public void setCoupon(long userId, String code, Instant at) {
        // TODO(phase-3 M4): upsert the cart row with the coupon code (null clears it).
        throw new UnsupportedOperationException("TODO(phase-3 M4): JdbcCartRepository.setCoupon");
    }

    @Override
    public Optional<Coupon> findCoupon(String code) {
        // TODO(phase-3 M4): SELECT the coupon by code (the service checks active).
        throw new UnsupportedOperationException("TODO(phase-3 M4): JdbcCartRepository.findCoupon");
    }
}
