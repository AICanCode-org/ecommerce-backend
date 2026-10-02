package org.aicancode.shopflow.web;

import java.util.Map;
import org.aicancode.shopflow.domain.ApiException;
import org.aicancode.shopflow.metrics.Metrics;
import org.aicancode.shopflow.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * /auth and /me. The Authorization header is read explicitly and verified by AuthService: one design for all three
 * tracks, no hidden filter chain.
 */
@RestController
public class AuthController {

    private final AuthService auth;
    private final Metrics metrics;

    public AuthController(AuthService auth, Metrics metrics) {
        this.auth = auth;
        this.metrics = metrics;
    }

    @PostMapping("/api/v1/auth/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) Object body) {
        Map<String, Object> user = Json.user(auth.register(body));
        metrics.inc(Metrics.USERS_REGISTERED);
        return ResponseEntity.status(201).body(user);
    }

    @PostMapping("/api/v1/auth/login")
    public Map<String, Object> login(@RequestBody(required = false) Object body) {
        try {
            return Json.login(auth.login(body));
        } catch (ApiException e) {
            if (e.status() == 401) {
                metrics.inc(Metrics.LOGINS_FAILED);
            }
            throw e;
        }
    }

    @GetMapping("/api/v1/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return Json.user(auth.me(auth.authenticate(authorization)));
    }
}
