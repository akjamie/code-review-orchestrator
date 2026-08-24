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
import org.akj.reviewer.orchestrator.ReviewPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import org.akj.reviewer.github.GitHubDiffFetcher;
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
    private final ReviewPipeline pipeline;
    private final GitHubDiffFetcher diffFetcher;
    private final Set<String> monitoredRepos;
    private final boolean allowAllRepos;
    private final boolean skipOnUnresolved;

    public GitHubWebhookController(org.akj.reviewer.config.GitHubConfig gitHubConfig,
                                   ObjectMapper objectMapper,
                                   ReviewPipeline pipeline,
                                   GitHubDiffFetcher diffFetcher,
                                   @Value("${review.monitored-repos:}") String monitoredReposCsv,
                                   @Value("${review.skip-on-unresolved-comments:true}") boolean skipOnUnresolved) {
        this.webhookSecret = gitHubConfig.getWebhookSecret();
        this.objectMapper = objectMapper;
        this.pipeline = pipeline;
        this.diffFetcher = diffFetcher;
        this.skipOnUnresolved = skipOnUnresolved;

        if (monitoredReposCsv == null || monitoredReposCsv.isBlank()) {
            this.monitoredRepos = Set.of();
            this.allowAllRepos = true;
        } else {
            this.monitoredRepos = Set.of(monitoredReposCsv.split("\\s*,\\s*"));
            this.allowAllRepos = false;
        }
    }

    @PostMapping("/webhook/github")
    @Operation(summary = "GitHub webhook event receiver",
               description = "Verifies the HMAC-SHA256 signature and handles pull request events. "
                   + "Queues a review run asynchronously if the action is opened, synchronize, or reopened.")
    public ResponseEntity<String> handleWebhook(
            @RequestHeader("X-Hub-Signature-256") String signatureHeader,
            @RequestHeader("X-GitHub-Event") String eventType,
            byte[] rawBody) {

        if (!verifySignature(rawBody, signatureHeader)) {
            log.warn("HMAC signature verification failed");
            return ResponseEntity.status(401).body("Invalid signature");
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (IOException e) {
            log.error("Failed to parse webhook payload", e);
            return ResponseEntity.badRequest().body("Invalid payload");
        }

        String repoFullName = extractRepoFullName(payload);
        if (repoFullName == null) {
            return ResponseEntity.ok("Ignored: could not determine repository");
        }

        if (!allowAllRepos && !monitoredRepos.contains(repoFullName)) {
            log.info("Ignored PR from non-monitored repo: {}", repoFullName);
            return ResponseEntity.ok("Ignored: repo not in monitored list");
        }

        String action = payload.has("action") ? payload.get("action").asText("") : "";

        if (eventType.equals("pull_request")) {
            if (!VALID_ACTIONS.contains(action)) {
                return ResponseEntity.ok("Ignored action: " + action);
            }
        } else {
            return ResponseEntity.ok("Ignored event type: " + eventType);
        }

        int prNumber = payload.path("number").asInt();
        log.info("Processing PR #{} from {} (action={})", prNumber, repoFullName, action);

        CompletableFuture.runAsync(() -> triggerReview(repoFullName, prNumber));

        return ResponseEntity.ok("Review queued");
    }

    private void triggerReview(String repoFullName, int prNumber) {
        if (skipOnUnresolved && diffFetcher.hasUnresolvedThreads(repoFullName, prNumber)) {
            log.info("Skipping review for {}/pull/{} — unresolved review comments present",
                repoFullName, prNumber);
            return;
        }
        pipeline.reviewPr(repoFullName, prNumber);
    }

    private String extractRepoFullName(JsonNode payload) {
        if (payload.has("repository") && payload.get("repository").has("full_name")) {
            return payload.get("repository").get("full_name").asText();
        }
        if (payload.has("organization") && payload.has("repository")) {
            String org = payload.get("organization").get("login").asText();
            String repo = payload.get("repository").get("name").asText();
            return org + "/" + repo;
        }
        return null;
    }

    private boolean verifySignature(byte[] rawBody, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            String expected = "sha256=" + bytesToHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));
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