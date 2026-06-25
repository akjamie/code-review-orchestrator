package org.akj.reviewer.webhook;

import java.util.List;
import java.util.Set;
import org.akj.reviewer.agent.AgentContext;
import org.akj.reviewer.github.GitHubDiffFetcher;
import org.akj.reviewer.orchestrator.ReviewPipeline;
import org.akj.reviewer.synthesizer.ReviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Local-only endpoint for E2E testing without GitHub webhooks.
 * Accepts a diff payload directly and runs the review pipeline,
 * returning the result as JSON. Useful for prompt iteration and
 * local development.
 */
@RestController
@Tag(name = "Local Review", description = "Endpoints for local testing without external webhooks or GitHub APIs")
public class LocalReviewController {

    private static final Logger log = LoggerFactory.getLogger(LocalReviewController.class);

    private final ReviewPipeline pipeline;
    private final GitHubDiffFetcher diffFetcher;

    public LocalReviewController(ReviewPipeline pipeline, GitHubDiffFetcher diffFetcher) {
        this.pipeline = pipeline;
        this.diffFetcher = diffFetcher;
    }

    /**
     * Trigger a review from a raw diff string.
     *
     * Example:
     *   curl -X POST "http://localhost:8080/review/local" \
     *     -H "Content-Type: application/json" \
     *     -d '{
     *       "diff": "diff --git a/App.java b/App.java\n...",
     *       "files": ["src/main/App.java"],
     *       "title": "Local test PR"
     *     }'
     */
    @PostMapping("/review/local")
    @Operation(summary = "Review raw diff locally", description = "Accepts a raw Git diff payload, runs the classic 4-agent review pipeline, and returns the unified review findings as JSON without modifying or posting to GitHub.")
    public ResponseEntity<ReviewResult> reviewLocal(@RequestBody LocalReviewRequest request) {
        log.info("Running local review: {}", request.title());

        Set<String> languages = diffFetcher.detectLanguages(request.files());

        var ctx = new AgentContext(
            "local/test",
            0,
            request.title() != null ? request.title() : "Local review",
            "",
            "local-user",
            request.diff(),
            request.files() != null ? request.files() : List.of(),
            false,
            languages
        );

        ReviewResult result = pipeline.execute(ctx);

        log.info("Review complete: {} findings", result.findings().size());
        log.info("--- Markdown output ---\n{}", result.markdownBody());

        return ResponseEntity.ok(result);
    }

    /**
     * Request body for local review.
     */
    public record LocalReviewRequest(
        String diff,
        List<String> files,
        String title
    ) {}
}