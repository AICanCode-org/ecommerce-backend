package org.aicancode.shopflow.web;

import java.util.Map;
import org.aicancode.shopflow.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness + database health. 503 when the database is down. */
@RestController
public class HealthController {

    private final AuthService auth;

    public HealthController(AuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean dbUp = auth.users().ping();
        return ResponseEntity.status(dbUp ? 200 : 503).body(Json.health(dbUp));
    }
}
