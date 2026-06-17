package org.akj.reviewer.webhook;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.akj.reviewer.agent.McpReviewAgent;
import org.akj.reviewer.github.GitHubDiffFetcher;
import org.akj.reviewer.github.GitHubReviewPoster;
import org.akj.reviewer.orchestrator.ReviewPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST endpoint to trigger PR reviews using a direct GitHub pull request URL.
 */
@RestController
@Tag(name = "Review Trigger", description = "Endpoints to trigger code reviews manually")
public class ReviewTriggerController {

    private static final Logger log = LoggerFactory.getLogger(ReviewTriggerController.class);

    // Matches standard GitHub PR URLs: https://github.com/owner/repo/pull/num
    private static final Pattern PR_URL_PATTERN = Pattern.compile(
        "https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+)/pull/(\\d+)"
    );

    private final GitHubDiffFetcher diffFetcher;
    private final ReviewPipeline pipeline;
    private final GitHubReviewPoster reviewPoster;
    private final McpReviewAgent mcpReviewAgent;

    public ReviewTriggerController(
            GitHubDiffFetcher diffFetcher,
            ReviewPipeline pipeline,
            GitHubReviewPoster reviewPoster,
            @Autowired(required = false) McpReviewAgent mcpReviewAgent) {
        this.diffFetcher = diffFetcher;
        this.pipeline = pipeline;
        this.reviewPoster = reviewPoster;
        this.mcpReviewAgent = mcpReviewAgent;
    }

    /**
     * Trigger a code review for a given GitHub Pull Request URL.
     * Extracts owner, repo, and PR number from the URL, and executes the review asynchronously.
     */
    @PostMapping("/review/url")
    @Operation(summary = "Trigger review by GitHub PR URL", description = "Extracts repository parameters from a GitHub Pull Request URL, fetches context, and executes code review asynchronously (running MCP agents or classic pipeline).")
    public ResponseEntity<String> reviewPrUrl(@RequestBody UrlReviewRequest request) {
        if (request.url() == null || request.url().isBlank()) {
            return ResponseEntity.badRequest().body("URL is required");
        }

        Matcher matcher = PR_URL_PATTERN.matcher(request.url().trim());
        if (!matcher.find()) {
            return ResponseEntity.badRequest().body("Invalid GitHub PR URL format. Expected: https://github.com/owner/repo/pull/num");
        }

        String owner = matcher.group(1);
        String repo = matcher.group(2);
        String repoFullName = owner + "/" + repo;
        int prNumber;
        try {
            prNumber = Integer.parseInt(matcher.group(3));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body("Invalid PR number in URL");
        }

        log.info("Manual review trigger via URL for PR {}/pull/{}", repoFullName, prNumber);

        // Run asynchronously so the REST call returns immediately
        Thread.ofVirtual().name("url-review-" + repoFullName + "#" + prNumber).start(() -> {
            try {
                if (mcpReviewAgent != null) {
                    log.info("Routing URL review to MCP agent for {}/pull/{}", repoFullName, prNumber);
                    mcpReviewAgent.reviewPr(repoFullName, prNumber);
                } else {
                    log.info("Routing URL review to classic pipeline for {}/pull/{}", repoFullName, prNumber);
                    var context = diffFetcher.fetchContext(repoFullName, prNumber);
                    var result = pipeline.execute(context);
                    reviewPoster.postReview(repoFullName, prNumber, result.markdownBody(), result.findings());
                }
            } catch (Exception e) {
                log.error("Failed to complete manual URL review for {}/pull/{}", repoFullName, prNumber, e);
            }
        });

        return ResponseEntity.ok("Review triggered successfully for " + repoFullName + " PR #" + prNumber);
    }

    /**
     * Request DTO for PR URL review endpoint.
     */
    public record UrlReviewRequest(String url) {}
}
