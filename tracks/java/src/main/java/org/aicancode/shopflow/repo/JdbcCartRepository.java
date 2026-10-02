package org.aicancode.shopflow.repo;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.aicancode.shopflow.domain.Models.CartLine;
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
        List<CartLine> lines = jdbc.sql("SELECT " + PRODUCT_COLS + ", ci.quantity FROM cart_items ci "
                        + "JOIN products p ON p.id = ci.product_id WHERE ci.user_id = ? ORDER BY ci.added_at, p.id")
                .param(userId)
                .query((rs, row) -> new CartLine(JdbcProductRepository.product(rs, row), rs.getInt("quantity"))).list();
        Optional<String> coupon = jdbc.sql("SELECT coupon_code FROM carts WHERE user_id = ?").param(userId)
                .query((rs, row) -> Optional.ofNullable(rs.getString("coupon_code"))).optional().flatMap(c -> c);
        return new CartState(lines, coupon.orElse(null));
    }

    @Override
    public void setCartItem(long userId, long productId, int quantity, Instant at) {
        jdbc.sql("INSERT INTO cart_items (user_id, product_id, quantity, added_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (user_id, product_id) DO UPDATE SET quantity = EXCLUDED.quantity")
                .params(userId, productId, quantity, Jdbc.ts(at)).update();
    }

    @Override
    public void removeCartItem(long userId, long productId) {
        jdbc.sql("DELETE FROM cart_items WHERE user_id = ? AND product_id = ?").params(userId, productId).update();
    }

    @Override
    public void setCoupon(long userId, String code, Instant at) {
        jdbc.sql("INSERT INTO carts (user_id, coupon_code, updated_at) VALUES (?, ?, ?) ON CONFLICT (user_id) "
                        + "DO UPDATE SET coupon_code = EXCLUDED.coupon_code, updated_at = EXCLUDED.updated_at")
                .params(userId, code, Jdbc.ts(at)).update();
    }

    @Override
    public Optional<Coupon> findCoupon(String code) {
        return jdbc.sql("SELECT code, kind, value, min_subtotal_paise, max_discount_paise, active FROM coupons "
                        + "WHERE code = ?").param(code)
                .query((rs, row) -> {
                    long max = rs.getLong("max_discount_paise");
                    Long cap = rs.wasNull() ? null : max;
                    return new Coupon(rs.getString("code"), rs.getString("kind"), rs.getLong("value"),
                            rs.getLong("min_subtotal_paise"), cap, rs.getBoolean("active"));
                }).optional();
    }
}
