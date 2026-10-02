package org.aicancode.shopflow.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.aicancode.shopflow.support.TestApp;
import org.aicancode.shopflow.support.TestApp.Res;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M2: the account endpoints over HTTP. */
class ApiAuthTest {

    private final TestApp api = new TestApp();

    @Test
    void returns201AndThePublicUserOnRegister() {
        Res r = api.call("POST", "/api/v1/auth/register",
                Map.of("email", "asha@shopflow.test", "password", "password-123", "displayName", "Asha"), (String) null);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json().toString()).isEqualTo("{\"id\":" + r.json().get("id") + ",\"email\":\"asha@shopflow.test\","
                + "\"displayName\":\"Asha\",\"role\":\"CUSTOMER\",\"createdAt\":\"2026-10-02T09:30:00.000Z\"}");
        assertThat(r.json().toString()).doesNotContain("passwordHash").doesNotContain("password-123");
    }

    @Test
    void logsInAndAnswersMe() {
        JsonNode session = api.customer("Asha");
        assertThat(session.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(session.get("expiresIn").asInt()).isEqualTo(3600);
        assertThat(session.propertyNames()).containsExactlyInAnyOrder("accessToken", "expiresIn", "tokenType", "user");
        Res me = api.get("/api/v1/me", session);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().get("displayName").asString()).isEqualTo("Asha");
    }

    @Test
    void needsAValidTokenForMe() {
        Res r = api.get("/api/v1/me");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.header("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(r.header("Content-Type")).startsWith("application/problem+json");
        assertThat(api.call("GET", "/api/v1/me", null, "Bearer nope").status()).isEqualTo(401);
    }

    @Test
    void answers401InvalidCredentialsForAWrongPassword() {
        api.customer();
        Res r = api.call("POST", "/api/v1/auth/login", Map.of("email", "user1@shopflow.test", "password", "wrong-password"),
                (String) null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.json().get("type").asString()).endsWith("/invalid-credentials");
    }

    @Test
    void answersBadJsonWithA400Problem() {
        Res r = api.call("POST", "/api/v1/auth/register", "{not json", (String) null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().get("detail").asString()).isEqualTo("request body must be a JSON object");
        assertThat(api.call("POST", "/api/v1/auth/login", List.of("a"), (String) null).status()).isEqualTo(400);
    }

    @Test
    void answers409ForADuplicateEmail() {
        api.customer();
        Res r = api.call("POST", "/api/v1/auth/register",
                Map.of("email", "USER1@shopflow.test", "password", "password-123", "displayName", "X"), (String) null);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.json().get("type").asString()).endsWith("/email-taken");
    }

    @Test
    void bootstrapsTheAdminFromTheEnvironment() {
        JsonNode me = api.get("/api/v1/me", api.admin()).json();
        assertThat(List.of(me.get("role").asString(), me.get("email").asString()))
                .containsExactly("ADMIN", "admin@shopflow.test");
    }
}
