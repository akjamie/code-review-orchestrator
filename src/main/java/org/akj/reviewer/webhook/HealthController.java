package org.akj.reviewer.webhook;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Health & Info", description = "Endpoints for monitoring and system information")
public class HealthController {

    private final Instant startTime = Instant.now();

    @GetMapping("/health")
    @Operation(summary = "Check service health", description = "Returns the service status, service name, and current uptime.")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "code-review-orchestrator",
            "uptime", java.time.Duration.between(startTime, Instant.now()).toSeconds() + "s"
        ));
    }

    @GetMapping("/")
    @Operation(summary = "Service metadata", description = "Returns service information, version, and key endpoint mappings.")
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