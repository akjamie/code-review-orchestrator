package org.akj.reviewer.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.akj.reviewer.github.GitHubDiffFetcher;
import org.akj.reviewer.github.GitHubReviewPoster;
import org.akj.reviewer.orchestrator.ReviewPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import org.akj.reviewer.agent.McpReviewAgent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "GitHub Webhook", description = "Endpoints to receive and process automated webhooks from GitHub")
public class GitHubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static final Set<String> VALID_ACTIONS = Set.of("opened", "synchronize", "reopened");

    private final String webhookSecret;
    private final ObjectMapper objectMapper;
    private final GitHubDiffFetcher diffFetcher;
    private final ReviewPipeline pipeline;
    private final GitHubReviewPoster reviewPoster;
    private final Set<String> monitoredRepos;
    private final boolean allowAllRepos;
    private final McpReviewAgent mcpReviewAgent; // null when review.mcp-agent.enabled=false

    public GitHubWebhookController(org.akj.reviewer.config.GitHubConfig gitHubConfig,
                                   ObjectMapper objectMapper,
                                   GitHubDiffFetcher diffFetcher,
                                   ReviewPipeline pipeline,
                                   GitHubReviewPoster reviewPoster,
                                   @Value("${review.monitored-repos:}") String monitoredReposCsv,
                                   @Autowired(required = false) McpReviewAgent mcpReviewAgent) {
        this.webhookSecret = gitHubConfig.getWebhookSecret();
        this.objectMapper = objectMapper;
        this.diffFetcher = diffFetcher;
        this.pipeline = pipeline;
        this.reviewPoster = reviewPoster;
        this.mcpReviewAgent = mcpReviewAgent;

        if (monitoredReposCsv == null || monitoredReposCsv.isBlank()) {
            this.monitoredRepos = Set.of();
            this.allowAllRepos = true;
        } else {
            this.monitoredRepos = Set.of(monitoredReposCsv.split("\\s*,\\s*"));
            this.allowAllRepos = false;
        }
    }

    @PostMapping("/webhook/github")
    @Operation(summary = "GitHub webhook event receiver", description = "Verifies the HMAC-SHA256 signature and handles pull request events. Queues a review run asynchronously if the action is opened, synchronize, or reopened.")
    public ResponseEntity<String> handleWebhook(
            @RequestHeader("X-Hub-Signature-256") String signatureHeader,
            @RequestHeader("X-GitHub-Event") String eventType,
            byte[] rawBody) {

        // 1. Verify HMAC signature
        if (!verifySignature(rawBody, signatureHeader)) {
            log.warn("HMAC signature verification failed");
            return ResponseEntity.status(401).body("Invalid signature");
        }

        // 2. Parse event payload
        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (IOException e) {
            log.error("Failed to parse webhook payload", e);
            return ResponseEntity.badRequest().body("Invalid payload");
        }

        // 3. Extract repo/PR info (handles both repo and org webhooks)
        String repoFullName = extractRepoFullName(payload);
        if (repoFullName == null) {
            return ResponseEntity.ok("Ignored: could not determine repository");
        }

        // 4. Check if repo is monitored
        if (!allowAllRepos && !monitoredRepos.contains(repoFullName)) {
            log.info("Ignored PR from non-monitored repo: {}", repoFullName);
            return ResponseEntity.ok("Ignored: repo not in monitored list");
        }

        // 5. Handle different event types
        //    - Repository webhooks: payload has "action" field
        //    - Org webhooks: need to extract from nested "pull_request" object
        String action = payload.has("action") ? payload.get("action").asText("") : "";

        if (eventType.equals("pull_request")) {
            if (!VALID_ACTIONS.contains(action)) {
                return ResponseEntity.ok("Ignored action: " + action);
            }
        } else if (eventType.equals("push")) {
            // Ignore push events
            return ResponseEntity.ok("Ignored push event");
        } else {
            return ResponseEntity.ok("Ignored event type: " + eventType);
        }

        // 6. Return 200 immediately — process async
        String prTitle = payload.path("pull_request").path("title").asText("");
        String prDescription = payload.path("pull_request").path("body").asText("");
        int prNumber = payload.path("number").asInt();

        log.info("Processing PR #{} from {} (action={})", prNumber, repoFullName, action);

        CompletableFuture.runAsync(() ->
            processReview(repoFullName, prNumber, prTitle, prDescription));

        return ResponseEntity.ok("Review queued");
    }

    private void processReview(String repoFullName, int prNumber, String prTitle, String prDescription) {
        if (mcpReviewAgent != null) {
            log.info("Routing to MCP review agent for {}/pull/{}", repoFullName, prNumber);
            try {
                mcpReviewAgent.reviewPr(repoFullName, prNumber);
            } catch (Exception e) {
                log.error("MCP review failed for {}/pull/{}", repoFullName, prNumber, e);
            }
        } else {
            log.info("Routing to classic pipeline for {}/pull/{}", repoFullName, prNumber);
            try {
                var context = diffFetcher.fetchContext(repoFullName, prNumber, prTitle, prDescription);
                var result = pipeline.execute(context);
                reviewPoster.postReview(repoFullName, prNumber, result.markdownBody(), result.findings());
            } catch (Exception e) {
                log.error("Classic review failed for {}/pull/{}", repoFullName, prNumber, e);
            }
        }
    }

    /**
     * Extract the full repo name (owner/repo) from either a repo-level or
     * org-level webhook payload.
     */
    private String extractRepoFullName(JsonNode payload) {
        // Repo webhook: payload.repository.full_name
        if (payload.has("repository") && payload.get("repository").has("full_name")) {
            return payload.get("repository").get("full_name").asText();
        }
        // Org webhook: payload.organization.login + payload.repository.name
        if (payload.has("organization") && payload.has("repository")) {
            String org = payload.get("organization").get("login").asText();
            String repo = payload.get("repository").get("name").asText();
            return org + "/" + repo;
        }
        return null;
    }

    /**
     * Extract the repo name from an org-level webhook payload.
     */
    private String extractRepoName(JsonNode payload) {
        if (payload.has("repository")) {
            return payload.get("repository").get("name").asText();
        }
        return null;
    }

    private boolean verifySignature(byte[] rawBody, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);

            byte[] expectedBytes = mac.doFinal(rawBody);
            String expected = "sha256=" + bytesToHex(expectedBytes);
            String received = signatureHeader;

            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                                          received.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("HMAC verification error", e);
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        var hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}