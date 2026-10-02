package org.aicancode.shopflow.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aicancode.shopflow.domain.Models.CartView;
import org.aicancode.shopflow.domain.Models.CategoryCount;
import org.aicancode.shopflow.domain.Models.LoginResult;
import org.aicancode.shopflow.domain.Models.Order;
import org.aicancode.shopflow.domain.Models.OutboxEvent;
import org.aicancode.shopflow.domain.Models.Product;
import org.aicancode.shopflow.domain.Models.SearchFilters;
import org.aicancode.shopflow.domain.Models.User;
import org.aicancode.shopflow.domain.Money;
import org.aicancode.shopflow.domain.Times;
import org.aicancode.shopflow.security.TokenService;

/** JSON shapes (identical in every track, see spec/openapi.yaml). LinkedHashMap keeps the documented order. */
final class Json {

    private Json() {
    }

    static Map<String, Object> user(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("email", u.email());
        m.put("displayName", u.displayName());
        m.put("role", u.role());
        m.put("createdAt", Times.iso(u.createdAt()));
        return m;
    }

    static Map<String, Object> login(LoginResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accessToken", r.accessToken());
        m.put("tokenType", "Bearer");
        m.put("expiresIn", TokenService.ACCESS_TOKEN_TTL.toSeconds());
        m.put("user", user(r.user()));
        return m;
    }

    static Map<String, Object> product(Product p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("sku", p.sku());
        m.put("name", p.name());
        m.put("description", p.description());
        m.put("category", p.category());
        m.put("pricePaise", p.pricePaise());
        m.put("price", Money.formatInr(p.pricePaise()));
        m.put("stock", p.stock());
        m.put("inStock", p.stock() > 0);
        m.put("active", p.active());
        m.put("version", p.version());
        m.put("createdAt", Times.iso(p.createdAt()));
        m.put("updatedAt", Times.iso(p.updatedAt()));
        return m;
    }

    static Map<String, Object> productItem(Product p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sku", p.sku());
        m.put("name", p.name());
        m.put("category", p.category());
        m.put("pricePaise", p.pricePaise());
        m.put("price", Money.formatInr(p.pricePaise()));
        m.put("inStock", p.stock() > 0);
        return m;
    }

    static List<Map<String, Object>> productItems(List<Product> products) {
        return products.stream().map(Json::productItem).toList();
    }

    static Map<String, Object> category(CategoryCount c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.name());
        m.put("products", c.products());
        return m;
    }

    static Map<String, Object> cart(CartView c) {
        List<Map<String, Object>> items = c.lines().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sku", l.product().sku());
            m.put("name", l.product().name());
            m.put("unitPricePaise", l.product().pricePaise());
            m.put("quantity", l.quantity());
            m.put("lineTotalPaise", l.product().pricePaise() * l.quantity());
            m.put("available", l.product().active() && l.product().stock() >= l.quantity());
            return m;
        }).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("couponCode", c.couponCode());
        m.put("couponApplied", c.pricing().couponApplied());
        m.put("subtotalPaise", c.pricing().subtotalPaise());
        m.put("discountPaise", c.pricing().discountPaise());
        m.put("shippingPaise", c.pricing().shippingPaise());
        m.put("totalPaise", c.pricing().totalPaise());
        m.put("total", Money.formatInr(c.pricing().totalPaise()));
        return m;
    }

    static Map<String, Object> order(Order o) {
        List<Map<String, Object>> items = o.lines().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sku", l.sku());
            m.put("name", l.name());
            m.put("unitPricePaise", l.unitPricePaise());
            m.put("quantity", l.quantity());
            m.put("lineTotalPaise", l.unitPricePaise() * l.quantity());
            return m;
        }).toList();
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("id", o.payment().id());
        payment.put("provider", o.payment().provider());
        payment.put("status", o.payment().status());
        payment.put("amountPaise", o.payment().amountPaise());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id());
        m.put("userId", o.userId());
        m.put("status", o.status());
        m.put("items", items);
        m.put("subtotalPaise", o.subtotalPaise());
        m.put("discountPaise", o.discountPaise());
        m.put("shippingPaise", o.shippingPaise());
        m.put("totalPaise", o.totalPaise());
        m.put("total", Money.formatInr(o.totalPaise()));
        m.put("couponCode", o.couponCode());
        m.put("payment", payment);
        m.put("createdAt", Times.iso(o.createdAt()));
        m.put("updatedAt", Times.iso(o.updatedAt()));
        return m;
    }

    static Map<String, Object> orders(List<Order> orders) {
        return Map.of("items", orders.stream().map(Json::order).toList());
    }

    static Map<String, Object> event(OutboxEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("type", e.type());
        m.put("orderId", e.orderId());
        m.put("payload", e.payload());
        m.put("createdAt", Times.iso(e.createdAt()));
        m.put("publishedAt", Times.iso(e.publishedAt()));
        return m;
    }

    static Map<String, Object> filters(SearchFilters f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("terms", f.terms());
        m.put("category", f.category());
        m.put("minPricePaise", f.minPricePaise());
        m.put("maxPricePaise", f.maxPricePaise());
        m.put("inStock", f.inStock());
        return m;
    }

    static Map<String, Object> health(boolean dbUp) {
        String db = dbUp ? "UP" : "DOWN";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", db);
        m.put("checks", Map.of("database", db));
        return m;
    }
}
