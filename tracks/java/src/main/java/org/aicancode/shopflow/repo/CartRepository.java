package org.aicancode.shopflow.repo;

import java.time.Instant;
import java.util.Optional;
import org.aicancode.shopflow.domain.Models.CartState;
import org.aicancode.shopflow.domain.Models.Coupon;

/** Persistence port for carts and coupons. */
public interface CartRepository {

    CartState cart(long userId);

    void setCartItem(long userId, long productId, int quantity, Instant at);

    void removeCartItem(long userId, long productId);

    void setCoupon(long userId, String code, Instant at);

    Optional<Coupon> findCoupon(String code);
}
