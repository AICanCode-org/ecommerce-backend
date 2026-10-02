package org.aicancode.shopflow.web;

import java.util.Map;
import org.aicancode.shopflow.service.AuthService;
import org.aicancode.shopflow.service.CartService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in customer's cart. Every response is the whole priced cart. */
@RestController
public class CartController {

    private final AuthService auth;
    private final CartService cart;

    public CartController(AuthService auth, CartService cart) {
        this.auth = auth;
        this.cart = cart;
    }

    @GetMapping("/api/v1/cart")
    public Map<String, Object> view(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return Json.cart(cart.view(auth.authenticate(authorization)));
    }

    @PutMapping("/api/v1/cart/items/{sku}")
    public Map<String, Object> setItem(@PathVariable String sku, @RequestBody(required = false) Object body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return Json.cart(cart.setItem(auth.authenticate(authorization), sku, body));
    }

    @DeleteMapping("/api/v1/cart/items/{sku}")
    public Map<String, Object> removeItem(@PathVariable String sku,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return Json.cart(cart.removeItem(auth.authenticate(authorization), sku));
    }

    @PutMapping("/api/v1/cart/coupon")
    public Map<String, Object> applyCoupon(@RequestBody(required = false) Object body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return Json.cart(cart.applyCoupon(auth.authenticate(authorization), body));
    }

    @DeleteMapping("/api/v1/cart/coupon")
    public Map<String, Object> removeCoupon(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return Json.cart(cart.removeCoupon(auth.authenticate(authorization)));
    }
}
