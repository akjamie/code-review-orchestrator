package org.akj.reviewer.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.akj.reviewer.agent.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GitHubReviewPoster {

    private static final Logger log = LoggerFactory.getLogger(GitHubReviewPoster.class);

    private final HttpClient httpClient;
    private final String token;
    private final ObjectMapper objectMapper;

    public GitHubReviewPoster(org.akj.reviewer.config.GitHubConfig gitHubConfig) {
        this.httpClient = HttpClient.newHttpClient();
        this.token = gitHubConfig.getToken();
        this.objectMapper = new ObjectMapper();
    }

    public void postReview(String repoFullName, int prNumber, String reviewBody, List<Finding> findings) {
        String url = "https://api.github.com/repos/" + repoFullName + "/pulls/" + prNumber + "/reviews";

        String jsonPayload = buildReviewPayload(reviewBody, findings);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github.v3+json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Review posted to {}/pull/{} (HTTP {})", repoFullName, prNumber, response.statusCode());
            } else {
                log.warn("Failed to post review: HTTP {} - {}", response.statusCode(),
                    response.body().substring(0, Math.min(500, response.body().length())));
            }
        } catch (IOException | InterruptedException e) {
            log.error("Failed to post review to {}/pull/{}", repoFullName, prNumber, e);
        }
    }

    private String buildReviewPayload(String reviewBody, List<Finding> findings) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("body", reviewBody);
        root.put("event", "COMMENT");

        ArrayNode comments = objectMapper.createArrayNode();
        for (Finding f : findings) {
            // Only include line-level comments (those with a known line number)
            if (f.lineNumber().isPresent() && !f.filePath().isBlank()) {
                ObjectNode comment = objectMapper.createObjectNode();
                comment.put("path", f.filePath());
                comment.put("line", f.lineNumber().get());
                comment.put("body", formatInlineComment(f));
                comments.add(comment);
            }
        }

        if (!comments.isEmpty()) {
            root.set("comments", comments);
        }

        return root.toPrettyString();
    }

    private String formatInlineComment(Finding f) {
        String emoji = switch (f.severity()) {
            case CRITICAL -> "🔴";
            case HIGH -> "🟠";
            case MEDIUM -> "🟡";
            case LOW -> "🔵";
            case INFO -> "⚪";
        };
        return String.format("**%s %s**: %s%n%n💡 %s",
            emoji, f.severity(), f.message(), f.suggestion());
    }
}