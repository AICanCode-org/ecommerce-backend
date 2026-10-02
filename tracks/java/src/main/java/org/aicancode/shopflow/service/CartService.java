package org.aicancode.shopflow.service;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import org.aicancode.shopflow.domain.ApiException;
import org.aicancode.shopflow.domain.Models.CartLine;
import org.aicancode.shopflow.domain.Models.CartState;
import org.aicancode.shopflow.domain.Models.CartView;
import org.aicancode.shopflow.domain.Models.Coupon;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.domain.Models.Product;
import org.aicancode.shopflow.domain.Money;
import org.aicancode.shopflow.domain.Times;
import org.aicancode.shopflow.domain.Validation;
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
        return lines.stream().map(l -> new long[] {l.product().pricePaise(), l.quantity()}).toList();
    }

    public CartView view(Principal principal) {
        CartState state = carts.cart(principal.userId());
        Coupon coupon = state.couponCode() == null ? null : carts.findCoupon(state.couponCode()).orElse(null);
        return new CartView(state.lines(), state.couponCode(), Money.priceCart(priced(state.lines()), coupon));
    }

    public CartView setItem(Principal principal, String sku, Object payload) {
        int qty = Validation.quantity(Validation.requireObject(payload).get("quantity"));
        Product product = products.findProduct(sku.strip().toUpperCase(Locale.ROOT)).orElse(null);
        if (product == null || !product.active()) {
            throw ApiException.notFound("product");
        }
        if (qty > product.stock()) {
            throw ApiException.insufficientStock("only " + product.stock() + " left of " + product.sku());
        }
        List<CartLine> lines = carts.cart(principal.userId()).lines();
        if (lines.size() >= MAX_LINES && lines.stream().noneMatch(l -> l.product().id() == product.id())) {
            throw ApiException.validation("a cart holds at most " + MAX_LINES + " different products");
        }
        carts.setCartItem(principal.userId(), product.id(), qty, Times.now(clock));
        return view(principal);
    }

    public CartView removeItem(Principal principal, String sku) {
        Product product = products.findProduct(sku.strip().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.notFound("product"));
        carts.removeCartItem(principal.userId(), product.id()); // idempotent: removing twice is fine
        return view(principal);
    }

    public CartView applyCoupon(Principal principal, Object payload) {
        String code = Validation.couponCode(Validation.requireObject(payload).get("code"));
        Coupon coupon = carts.findCoupon(code).orElse(null);
        if (coupon == null || !coupon.active()) {
            throw ApiException.notFound("coupon");
        }
        carts.setCoupon(principal.userId(), code, Times.now(clock));
        return view(principal);
    }

    public CartView removeCoupon(Principal principal) {
        carts.setCoupon(principal.userId(), null, Times.now(clock));
        return view(principal);
    }
}
