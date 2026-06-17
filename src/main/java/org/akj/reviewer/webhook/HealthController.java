package org.akj.reviewer.webhook;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final Instant startTime = Instant.now();

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "code-review-orchestrator",
            "uptime", java.time.Duration.between(startTime, Instant.now()).toSeconds() + "s"
        ));
    }

    @GetMapping("/")
    public ResponseEntity<Map<String, Object>> root() {
        return ResponseEntity.ok(Map.of(
            "service", "code-review-orchestrator",
            "version", "0.0.1-SNAPSHOT",
            "endpoints", Map.of(
                "health", "GET /health",
                "webhook", "POST /webhook/github"
            )
        ));
    }
}