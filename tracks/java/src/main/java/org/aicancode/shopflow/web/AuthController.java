package org.aicancode.shopflow.web;

import java.util.Map;
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

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/api/v1/auth/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) Object body) {
        return ResponseEntity.status(201).body(Json.user(auth.register(body)));
    }

    @PostMapping("/api/v1/auth/login")
    public Map<String, Object> login(@RequestBody(required = false) Object body) {
        return Json.login(auth.login(body));
    }

    @GetMapping("/api/v1/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return Json.user(auth.me(auth.authenticate(authorization)));
    }
}
