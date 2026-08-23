package org.akj.reviewer.webhook;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.akj.reviewer.orchestrator.ReviewPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final ReviewPipeline pipeline;

    public ReviewTriggerController(ReviewPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * Trigger a code review for a given GitHub Pull Request URL.
     * Extracts owner, repo, and PR number from the URL and executes the review asynchronously.
     */
    @PostMapping("/review/url")
    @Operation(summary = "Trigger review by GitHub PR URL",
               description = "Extracts repository parameters from a GitHub Pull Request URL "
                   + "and triggers the unified review pipeline asynchronously.")
    public ResponseEntity<String> reviewPrUrl(@RequestBody UrlReviewRequest request) {
        if (request.url() == null || request.url().isBlank()) {
            return ResponseEntity.badRequest().body("URL is required");
        }

        Matcher matcher = PR_URL_PATTERN.matcher(request.url().trim());
        if (!matcher.find()) {
            return ResponseEntity.badRequest()
                .body("Invalid GitHub PR URL format. Expected: https://github.com/owner/repo/pull/num");
        }

        String repoFullName = matcher.group(1) + "/" + matcher.group(2);
        int prNumber;
        try {
            prNumber = Integer.parseInt(matcher.group(3));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body("Invalid PR number in URL");
        }

        log.info("Manual review trigger for {}/pull/{}", repoFullName, prNumber);

        final String repo = repoFullName;
        final int pr = prNumber;
        Thread.ofVirtual()
            .name("url-review-" + repoFullName + "#" + prNumber)
            .start(() -> pipeline.reviewPr(repo, pr));

        return ResponseEntity.ok("Review triggered for " + repoFullName + " PR #" + prNumber);
    }

    /** Request DTO for PR URL review endpoint. */
    public record UrlReviewRequest(String url) {}
}
