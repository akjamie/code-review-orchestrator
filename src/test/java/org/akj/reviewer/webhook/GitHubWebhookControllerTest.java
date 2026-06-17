package org.akj.reviewer.webhook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.akj.reviewer.github.GitHubDiffFetcher;
import org.akj.reviewer.github.GitHubReviewPoster;
import org.akj.reviewer.config.GitHubConfig;
import org.akj.reviewer.orchestrator.ReviewPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class GitHubWebhookControllerTest {

    @Mock
    private GitHubConfig gitHubConfig;

    @Mock
    private GitHubDiffFetcher diffFetcher;

    @Mock
    private ReviewPipeline pipeline;

    @Mock
    private GitHubReviewPoster reviewPoster;

    private ObjectMapper objectMapper;
    private GitHubWebhookController controller;

    private static final String WEBHOOK_SECRET = "test-secret";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        when(gitHubConfig.getWebhookSecret()).thenReturn(WEBHOOK_SECRET);
        // Allow all repos by default
        controller = new GitHubWebhookController(gitHubConfig, objectMapper,
            diffFetcher, pipeline, reviewPoster, "", null);
    }

    @Test
    void rejectsRequestWithInvalidSignature() throws Exception {
        byte[] body = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = controller.handleWebhook(
            "sha256=invalid",
            "pull_request",
            body
        );

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void acceptsRequestWithValidSignature() throws Exception {
        String payload = """
            {
              "action": "opened",
              "number": 1,
              "repository": {"full_name": "owner/repo"},
              "pull_request": {"title": "Test PR", "body": "Description"}
            }
            """;
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String signature = computeHmac(body);

        ResponseEntity<String> response = controller.handleWebhook(
            signature,
            "pull_request",
            body
        );

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Review queued"));
    }

    @Test
    void ignoresUnrelatedEvents() throws Exception {
        String payload = """
            {
              "action": "labeled",
              "number": 1,
              "repository": {"full_name": "owner/repo"},
              "pull_request": {"title": "Test"}
            }
            """;
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String signature = computeHmac(body);

        ResponseEntity<String> response = controller.handleWebhook(
            signature,
            "pull_request",
            body
        );

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Ignored action"));
    }

    @Test
    void ignoresNonMonitoredRepo() throws Exception {
        String payload = """
            {
              "action": "opened",
              "number": 1,
              "repository": {"full_name": "other/repo"},
              "pull_request": {"title": "Test"}
            }
            """;
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String signature = computeHmac(body);

        // Create controller with specific repo filter
        var filteredController = new GitHubWebhookController(gitHubConfig, objectMapper,
            diffFetcher, pipeline, reviewPoster, "my-org/my-repo", null);

        ResponseEntity<String> response = filteredController.handleWebhook(
            signature,
            "pull_request",
            body
        );

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Ignored: repo not in monitored list"));
    }

    @Test
    void handlesOrgLevelWebhook() throws Exception {
        String payload = """
            {
              "action": "opened",
              "number": 1,
              "organization": {"login": "my-org"},
              "repository": {"full_name": "my-org/my-repo", "name": "my-repo"},
              "pull_request": {"title": "Test PR", "body": "Description"}
            }
            """;
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String signature = computeHmac(body);

        ResponseEntity<String> response = controller.handleWebhook(
            signature,
            "pull_request",
            body
        );

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Review queued"));
    }

    private String computeHmac(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(
            WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] hmac = mac.doFinal(body);
        return "sha256=" + bytesToHex(hmac);
    }

    private String bytesToHex(byte[] bytes) {
        var hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}