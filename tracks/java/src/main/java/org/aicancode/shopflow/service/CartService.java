package org.aicancode.shopflow.service;

import java.time.Clock;
import java.util.List;
import org.aicancode.shopflow.domain.Models.CartLine;
import org.aicancode.shopflow.domain.Models.CartView;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.repo.CartRepository;
import org.aicancode.shopflow.repo.ProductRepository;

/** The cart: one per signed-in user, stored in the database, priced live with current prices. */
public class CartService {

    public static final int MAX_LINES = 20;
    private final CartRepository carts;
    private final ProductRepository products;
    private final Clock clock;

    public CartService(CartRepository carts, ProductRepository products, Clock clock) {
        this.carts = carts;
        this.products = products;
        this.clock = clock;
    }

    static List<long[]> priced(List<CartLine> lines) {
        // TODO(phase-3 M4): helper: the cart lines as [unit price in paise, quantity] pairs for
        //   Money.priceCart (OrderService uses it too).
        throw new UnsupportedOperationException("TODO(phase-3 M4): CartService.priced");
    }

    public CartView view(Principal principal) {
        // TODO(phase-3 M4): the cart lines + coupon code, priced with price_cart (the coupon may no longer
        //   apply: then couponApplied is false).
        throw new UnsupportedOperationException("TODO(phase-3 M4): CartService.view");
    }

    public CartView setItem(Principal principal, String sku, Object payload) {
        // TODO(phase-3 M4): validate quantity; the product must exist and be active (404); quantity > stock
        //   -> 409 insufficient-stock; a NEW line when the cart already has MAX_LINES lines -> 400; upsert the
        //   line (it sets the quantity, it does not add) and return the view.
        throw new UnsupportedOperationException("TODO(phase-3 M4): CartService.setItem");
    }

    public CartView removeItem(Principal principal, String sku) {
        // TODO(phase-3 M4): unknown sku -> 404; removing a line that is not in the cart is fine (idempotent);
        //   return the view.
        throw new UnsupportedOperationException("TODO(phase-3 M4): CartService.removeItem");
    }

    public CartView applyCoupon(Principal principal, Object payload) {
        // TODO(phase-3 M4): validate the code; unknown or inactive coupon -> 404 not-found 'coupon'; store it
        //   on the cart and return the view.
        throw new UnsupportedOperationException("TODO(phase-3 M4): CartService.applyCoupon");
    }

    public CartView removeCoupon(Principal principal) {
        // TODO(phase-3 M4): clear the cart's coupon and return the view.
        throw new UnsupportedOperationException("TODO(phase-3 M4): CartService.removeCoupon");
    }
}
