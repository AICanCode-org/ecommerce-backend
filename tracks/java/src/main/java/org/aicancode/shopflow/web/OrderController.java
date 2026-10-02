package org.aicancode.shopflow.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.domain.Validation;
import org.aicancode.shopflow.service.AuthService;
import org.aicancode.shopflow.service.OrderService;
import org.aicancode.shopflow.service.OutboxService;
import org.aicancode.shopflow.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Checkout, orders, the admin order/outbox endpoints and the payment webhook. */
@RestController
public class OrderController {

    private final AuthService auth;
    private final OrderService orders;
    private final PaymentService payments;
    private final OutboxService outbox;

    public OrderController(AuthService auth, OrderService orders, PaymentService payments, OutboxService outbox) {
        this.auth = auth;
        this.orders = orders;
        this.payments = payments;
        this.outbox = outbox;
    }

    @PostMapping("/api/v1/checkout")
    public ResponseEntity<Map<String, Object>> checkout(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Principal who = auth.authenticate(authorization);
        OrderService.Checkout result = orders.checkout(who, key);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.replayed() ? 200 : 201)
                .header("Location", "/api/v1/orders/" + result.order().id());
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(Json.order(result.order()));
    }

    @GetMapping("/api/v1/orders")
    public Map<String, Object> mine(HttpServletRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return Json.orders(orders.mine(auth.authenticate(authorization), ProductController.param(request, "limit")));
    }

    @GetMapping("/api/v1/orders/{id}")
    public Map<String, Object> get(@PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Principal who = auth.authenticate(authorization);
        return Json.order(orders.get(who, Validation.pathId(id, "order")));
    }

    @PostMapping("/api/v1/orders/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Principal who = auth.authenticate(authorization);
        return Json.order(orders.cancel(who, Validation.pathId(id, "order")));
    }

    @GetMapping("/api/v1/admin/orders")
    public Map<String, Object> adminList(HttpServletRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return Json.orders(orders.adminList(auth.authenticate(authorization), ProductController.param(request, "status"),
                ProductController.param(request, "limit")));
    }

    @PostMapping("/api/v1/admin/orders/{id}/status")
    public Map<String, Object> adminSetStatus(@PathVariable String id, @RequestBody(required = false) Object body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Principal who = auth.authenticate(authorization);
        return Json.order(orders.adminSetStatus(who, Validation.pathId(id, "order"), body));
    }

    /** The signature is over the RAW bytes, so the body is read as bytes, never re-serialised. */
    @PostMapping(value = "/api/v1/payments/webhook", consumes = "*/*")
    public Map<String, Object> webhook(@RequestBody(required = false) byte[] body,
            @RequestHeader(value = "X-Signature", required = false) String signature) {
        return payments.handleWebhook(body == null ? new byte[0] : body, signature);
    }

    @GetMapping("/api/v1/admin/outbox")
    public Map<String, Object> outbox(HttpServletRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return Map.of("items", outbox.list(auth.authenticate(authorization), ProductController.param(request, "pending"),
                ProductController.param(request, "limit")).stream().map(Json::event).toList());
    }

    @PostMapping("/api/v1/admin/outbox/publish")
    public Map<String, Object> publish(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return Map.of("published", outbox.publishPending(auth.authenticate(authorization)));
    }
}
