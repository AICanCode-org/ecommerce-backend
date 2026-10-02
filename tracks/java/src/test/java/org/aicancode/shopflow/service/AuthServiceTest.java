package org.aicancode.shopflow.service;

import static org.aicancode.shopflow.support.Problems.statusOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.aicancode.shopflow.domain.ApiException;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.domain.Models.User;
import org.aicancode.shopflow.support.InMemoryStore;
import org.aicancode.shopflow.support.MutableClock;
import org.aicancode.shopflow.support.TestApp;
import org.junit.jupiter.api.Test;

/** M2: AuthService (framework-free). */
class AuthServiceTest {

    private static final Map<String, Object> REG = Map.of("email", "Asha@ShopFlow.test", "password", "password-123",
            "displayName", " Asha ");
    private final InMemoryStore store = new InMemoryStore();
    private final AuthService svc = TestApp.auth(store, new MutableClock());

    private static Map<String, Object> reg(String key, Object value) {
        Map<String, Object> m = new HashMap<>(REG);
        m.put(key, value);
        return m;
    }

    @Test
    void registersACustomerAndIgnoresARequestedRole() {
        User user = svc.register(reg("role", "ADMIN"));
        assertThat(List.of(user.email(), user.displayName(), user.role()))
                .containsExactly("asha@shopflow.test", "Asha", "CUSTOMER");
        assertThat(store.users.get(user.id()).passwordHash()).startsWith("$2").doesNotContain("password-123");
    }

    @Test
    void answers409ForTheSameEmailTwiceEvenWithDifferentCase() {
        svc.register(REG);
        assertThat(statusOf(() -> svc.register(reg("email", "ASHA@shopflow.test")))).containsExactly(409, "email-taken");
    }

    @Test
    void validatesTheRegistration() {
        assertThat(statusOf(() -> svc.register(Map.of("email", "x", "password", "password-123", "displayName", "A"))))
                .containsExactly(400, "validation-error");
        assertThat(statusOf(() -> svc.register("not an object"))).containsExactly(400, "validation-error");
    }

    @Test
    void returnsATokenForTheUserOnLogin() {
        svc.register(REG);
        var result = svc.login(Map.of("email", " ASHA@shopflow.test ", "password", "password-123"));
        assertThat(result.user().email()).isEqualTo("asha@shopflow.test");
        assertThat(result.accessToken().split("\\.")).hasSize(3);
    }

    @Test
    void makesEveryLoginFailureLookTheSame() {
        svc.register(REG);
        var wrong = statusOf(() -> svc.login(Map.of("email", "asha@shopflow.test", "password", "wrong-password")));
        var unknown = statusOf(() -> svc.login(Map.of("email", "nobody@shopflow.test", "password", "password-123")));
        var tooLong = statusOf(() -> svc.login(Map.of("email", "asha@shopflow.test", "password", "x".repeat(73))));
        assertThat(List.of(wrong, unknown, tooLong)).allSatisfy(s -> assertThat(s).containsExactly(401, "invalid-credentials"));
        assertThat(statusOf(() -> svc.login(Map.of("email", "asha@shopflow.test")))).containsExactly(400, "validation-error");
    }

    @Test
    void authenticatesBearerTokensOptionally() {
        svc.register(REG);
        String token = svc.login(Map.of("email", "asha@shopflow.test", "password", "password-123")).accessToken();
        assertThat(svc.authenticate("Bearer " + token).role()).isEqualTo("CUSTOMER");
        assertThat(svc.authenticate("bearer   " + token).role()).isEqualTo("CUSTOMER");
        assertThat(svc.optional(null)).isNull();
        for (String bad : List.of("Basic abc", "Bearer ", "Bearer x.y.z", token)) {
            assertThat(statusOf(() -> svc.optional(bad))).containsExactly(401, "unauthorized");
        }
        assertThat(statusOf(() -> svc.authenticate(null))).containsExactly(401, "unauthorized");
    }

    @Test
    void answers401ForADeletedAccount() {
        User user = svc.register(REG);
        assertThat(svc.me(new Principal(user.id(), "CUSTOMER")).id()).isEqualTo(user.id());
        store.users.remove(user.id());
        assertThat(statusOf(() -> svc.me(new Principal(user.id(), "CUSTOMER")))).containsExactly(401, "unauthorized");
    }

    @Test
    void createsOrPromotesTheBootstrapAdmin() {
        User admin = svc.ensureAdmin("Boss@ShopFlow.test", "admin-password");
        assertThat(List.of(admin.role(), admin.email())).containsExactly("ADMIN", "boss@shopflow.test");
        assertThat(svc.ensureAdmin("boss@shopflow.test", "ignored-now").id()).isEqualTo(admin.id());
        User user = svc.register(REG);
        assertThat(svc.ensureAdmin("asha@shopflow.test", "whatever-123").role()).isEqualTo("ADMIN");
        assertThat(store.users.get(user.id()).role()).isEqualTo("ADMIN");
    }

    @Test
    void requiresARole() {
        AuthService.requireRole(new Principal(1, "ADMIN"), "ADMIN");
        assertThatThrownBy(() -> AuthService.requireRole(new Principal(1, "CUSTOMER"), "ADMIN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status()).isEqualTo(403);
                    assertThat(((ApiException) e).detail()).isEqualTo("requires role ADMIN");
                });
    }
}
