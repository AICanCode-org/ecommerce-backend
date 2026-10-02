package org.aicancode.shopflow.service;

import static org.aicancode.shopflow.support.Problems.codeOf;
import static org.aicancode.shopflow.support.Problems.problem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.aicancode.shopflow.domain.ApiException;
import org.aicancode.shopflow.domain.Models.Order;
import org.aicancode.shopflow.domain.Models.OutboxEvent;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.support.InMemoryStore;
import org.aicancode.shopflow.support.MutableClock;
import org.aicancode.shopflow.support.TestApp;
import org.junit.jupiter.api.Test;

/** M5: OrderService - idempotent checkout, all-or-nothing stock, the order state machine. */
class OrderServiceTest {

    private static final Principal ADMIN = new Principal(1, "ADMIN");
    private static final Principal ASHA = new Principal(2, "CUSTOMER");
    private static final Principal RAVI = new Principal(3, "CUSTOMER");
    private static final String KEY = "order-key-0001";
    private final InMemoryStore store = new InMemoryStore();
    private final MutableClock clock = new MutableClock();
    private final CatalogService catalog = new CatalogService(store, clock);
    private final CartService cart = new CartService(store, store, clock);
    private final OrderService svc = new OrderService(store, store, clock, TestApp.paymentIds());

    OrderServiceTest() {
        catalog.create(ADMIN, Map.of("sku", "TEE-1", "name", "Tee", "category", "apparel", "pricePaise", 29_900,
                "stock", 3));
        catalog.create(ADMIN, Map.of("sku", "MUG-1", "name", "Mug", "category", "kitchen", "pricePaise", 45_000,
                "stock", 1));
    }

    private int stock(String sku) {
        return catalog.get(ADMIN, sku).stock();
    }

    private List<String> types() {
        return store.events.stream().map(OutboxEvent::type).toList();
    }

    private void add(Principal who, String sku, int quantity) {
        cart.setItem(who, sku, Map.of("quantity", quantity));
    }

    @Test
    void snapshotsPricesReservesStockAndEmptiesTheCartOnCheckout() {
        add(ASHA, "TEE-1", 2);
        add(ASHA, "MUG-1", 1);
        cart.applyCoupon(ASHA, Map.of("code", "SAVE10"));
        OrderService.Checkout result = svc.checkout(ASHA, KEY);
        Order order = result.order();
        assertThat(result.replayed()).isFalse();
        assertThat(order.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.userId()).isEqualTo(2);
        assertThat(order.lines()).extracting(l -> List.of(l.sku(), l.unitPricePaise(), l.quantity()))
                .containsExactly(List.of("TEE-1", 29_900L, 2), List.of("MUG-1", 45_000L, 1));
        assertThat(List.of(order.subtotalPaise(), order.discountPaise(), order.shippingPaise(), order.totalPaise()))
                .containsExactly(104_800L, 10_480L, 4_900L, 99_220L);
        assertThat(order.couponCode()).isEqualTo("SAVE10");
        assertThat(List.of(order.payment().id(), order.payment().status(), order.payment().amountPaise()))
                .containsExactly("pay_test_1", "PENDING", 99_220L);
        assertThat(List.of(stock("TEE-1"), stock("MUG-1"))).containsExactly(1, 0);
        assertThat(cart.view(ASHA).lines()).isEmpty();
        assertThat(cart.view(ASHA).couponCode()).isNull();
        assertThat(store.events).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo("order.placed");
            assertThat(e.payload()).isEqualTo(Map.of("orderId", order.id(), "userId", 2L, "status", "PENDING_PAYMENT",
                    "totalPaise", 99_220L));
        });
        catalog.update(ADMIN, "TEE-1", Map.of("version", 1, "pricePaise", 1));
        assertThat(svc.get(ASHA, order.id()).lines().get(0).unitPricePaise()).isEqualTo(29_900); // never changes
    }

    @Test
    void returnsTheSameOrderForTheSameIdempotencyKey() {
        add(ASHA, "TEE-1", 1);
        Order first = svc.checkout(ASHA, KEY).order();
        OrderService.Checkout again = svc.checkout(ASHA, KEY);
        assertThat(again.replayed()).isTrue();
        assertThat(again.order().id()).isEqualTo(first.id());
        assertThat(stock("TEE-1")).isEqualTo(2);
        // the key is per customer: Ravi's identical key is a different checkout
        assertThat(problem(() -> svc.checkout(RAVI, KEY)).subList(0, 2)).containsExactly(409, "cart-empty");
    }

    @Test
    void needsAKeyAndACart() {
        assertThat(problem(() -> svc.checkout(ASHA, null)))
                .containsExactly(400, "validation-error", "Idempotency-Key header is required");
        assertThat(problem(() -> svc.checkout(ASHA, KEY)))
                .containsExactly(409, "cart-empty", "add something to your cart before checking out");
    }

    @Test
    void makesOversellingImpossibleAndNeverHalfWrites() {
        add(ASHA, "TEE-1", 1);
        add(ASHA, "MUG-1", 1);
        add(RAVI, "MUG-1", 1);
        svc.checkout(RAVI, "ravi-key-0001"); // Ravi buys the last mug first
        assertThat(problem(() -> svc.checkout(ASHA, KEY)))
                .containsExactly(409, "insufficient-stock", "not enough stock for MUG-1");
        assertThat(stock("TEE-1")).isEqualTo(3); // the tee was NOT decremented: all or nothing
        assertThat(cart.view(ASHA).lines()).hasSize(2);
        assertThat(store.orders).hasSize(1);
    }

    @Test
    void blocksCheckoutForADeactivatedProduct() {
        add(ASHA, "TEE-1", 1);
        catalog.update(ADMIN, "TEE-1", Map.of("version", 1, "active", false));
        assertThat(problem(() -> svc.checkout(ASHA, KEY)))
                .containsExactly(409, "insufficient-stock", "TEE-1 is no longer available");
    }

    @Test
    void answers404ForOtherPeoplesOrders() {
        add(ASHA, "TEE-1", 1);
        Order order = svc.checkout(ASHA, KEY).order();
        assertThat(svc.get(ADMIN, order.id()).id()).isEqualTo(order.id());
        assertThat(problem(() -> svc.get(RAVI, order.id()))).containsExactly(404, "not-found", "order not found");
        assertThat(codeOf(() -> svc.get(ASHA, 999))).isEqualTo(404);
        assertThat(svc.mine(ASHA, null)).extracting(Order::id).containsExactly(order.id());
        assertThat(svc.mine(RAVI, null)).isEmpty();
    }

    @Test
    void restocksOnCancelAndRecordsAnEvent() {
        add(ASHA, "TEE-1", 2);
        Order order = svc.checkout(ASHA, KEY).order();
        assertThat(svc.cancel(ASHA, order.id()).status()).isEqualTo("CANCELLED");
        assertThat(stock("TEE-1")).isEqualTo(3);
        assertThat(types()).containsExactly("order.placed", "order.cancelled");
        assertThat(problem(() -> svc.cancel(ASHA, order.id())))
                .containsExactly(409, "invalid-transition", "cannot cancel an order that is CANCELLED");
    }

    @Test
    void letsAnAdminShipAndDeliverPaidOrders() {
        add(ASHA, "TEE-1", 1);
        Order order = svc.checkout(ASHA, KEY).order();
        Map<String, Object> shipped = Map.of("status", "SHIPPED");
        assertThat(problem(() -> svc.adminSetStatus(ADMIN, order.id(), shipped)).get(2))
                .isEqualTo("cannot ship an order that is PENDING_PAYMENT");
        store.setOrderStatus(order.id(), "PAID");
        assertThat(codeOf(() -> svc.adminSetStatus(ASHA, order.id(), shipped))).isEqualTo(403);
        assertThat(svc.adminSetStatus(ADMIN, order.id(), shipped).status()).isEqualTo("SHIPPED");
        assertThat(problem(() -> svc.cancel(ASHA, order.id())).get(2)).isEqualTo("cannot cancel an order that is SHIPPED");
        assertThat(svc.adminSetStatus(ADMIN, order.id(), Map.of("status", "DELIVERED")).status()).isEqualTo("DELIVERED");
        assertThat(types().subList(types().size() - 2, types().size()))
                .containsExactly("order.shipped", "order.delivered");
    }

    @Test
    void requestsARefundWhenAPaidOrderIsCancelled() {
        add(ASHA, "TEE-1", 1);
        Order order = svc.checkout(ASHA, KEY).order();
        store.setOrderStatus(order.id(), "PAID");
        svc.cancel(ASHA, order.id());
        assertThat(types()).containsExactly("order.placed", "order.cancelled", "refund.requested");
    }

    @Test
    void filtersTheAdminListByStatus() {
        add(ASHA, "TEE-1", 1);
        Order a = svc.checkout(ASHA, KEY).order();
        add(RAVI, "TEE-1", 1);
        Order b = svc.checkout(RAVI, KEY).order();
        svc.cancel(RAVI, b.id());
        assertThat(svc.adminList(ADMIN, null, null)).extracting(Order::id).containsExactly(b.id(), a.id()); // newest first
        assertThat(svc.adminList(ADMIN, "CANCELLED", null)).extracting(Order::id).containsExactly(b.id());
        assertThat(codeOf(() -> svc.adminList(ASHA, null, null))).isEqualTo(403);
    }

    @Test
    void answers409ForAConcurrentChange() {
        add(ASHA, "TEE-1", 1);
        Order order = svc.checkout(ASHA, KEY).order();
        Order stale = svc.get(ASHA, order.id());
        store.setOrderStatus(order.id(), "PAID"); // a webhook won the race
        List<Object> p = problem(() -> svc.apply(stale, "cancel"));
        assertThat(p.get(0)).isEqualTo(409);
        assertThat((String) p.get(2)).contains("changed while we were updating it");
    }

    @Test
    void followsTheStateMachineTable() {
        assertThat(Orders.nextStatus("PENDING_PAYMENT", "pay")).isEqualTo("PAID");
        assertThat(Orders.nextStatus("PENDING_PAYMENT", "fail")).isEqualTo("CANCELLED");
        assertThat(Orders.nextStatus("PAID", "ship")).isEqualTo("SHIPPED");
        assertThat(Orders.nextStatus("SHIPPED", "deliver")).isEqualTo("DELIVERED");
        for (String[] bad : new String[][] {{"DELIVERED", "cancel"}, {"CANCELLED", "pay"}, {"PAID", "pay"},
            {"PAID", "deliver"}}) {
            assertThatThrownBy(() -> Orders.nextStatus(bad[0], bad[1])).isInstanceOf(ApiException.class);
        }
    }
}
