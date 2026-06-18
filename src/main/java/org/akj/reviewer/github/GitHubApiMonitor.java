package org.akj.reviewer.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.akj.reviewer.agent.McpReviewAgent;
import org.akj.reviewer.config.GitHubConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background polling monitor that periodically checks GitHub for open pull requests
 * in the configured repositories and triggers the {@link McpReviewAgent} when a
 * new or updated PR is detected.
 *
 * <p>Enabled via {@code review.polling.enabled=true} in {@code application.yml}
 * or via the {@code POLLING_ENABLED=true} environment variable.
 *
 * <p>Uses {@link SeenPrTracker} to avoid duplicate reviews — a PR is reviewed only
 * when its head commit SHA changes (i.e. a new push was made to the PR branch).
 */
@Component
@ConditionalOnProperty(name = "review.polling.enabled", havingValue = "true")
public class GitHubApiMonitor {

    private static final Logger log = LoggerFactory.getLogger(GitHubApiMonitor.class);
    private static final String GITHUB_API = "https://api.github.com";

    private final String token;
    private final Set<String> monitoredRepos;
    private final boolean allowAllRepos;
    private final SeenPrTracker seenPrTracker;
    private final McpReviewAgent mcpReviewAgent;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GitHubDiffFetcher diffFetcher;
    private final boolean skipOnUnresolved;

    public GitHubApiMonitor(
            GitHubConfig gitHubConfig,
            SeenPrTracker seenPrTracker,
            McpReviewAgent mcpReviewAgent,
            ObjectMapper objectMapper,
            GitHubDiffFetcher diffFetcher,
            @Value("${review.monitored-repos:}") String monitoredReposCsv,
            @Value("${review.skip-on-unresolved-comments:true}") boolean skipOnUnresolved) {
        this(gitHubConfig, seenPrTracker, mcpReviewAgent, objectMapper, diffFetcher, HttpClient.newHttpClient(), monitoredReposCsv, skipOnUnresolved);
    }

    // Package-private constructor for testing
    GitHubApiMonitor(
            GitHubConfig gitHubConfig,
            SeenPrTracker seenPrTracker,
            McpReviewAgent mcpReviewAgent,
            ObjectMapper objectMapper,
            GitHubDiffFetcher diffFetcher,
            HttpClient httpClient,
            String monitoredReposCsv,
            boolean skipOnUnresolved) {
        this.token = gitHubConfig.getToken();
        this.seenPrTracker = seenPrTracker;
        this.mcpReviewAgent = mcpReviewAgent;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.diffFetcher = diffFetcher;
        this.skipOnUnresolved = skipOnUnresolved;

        if (monitoredReposCsv == null || monitoredReposCsv.isBlank()) {
            this.monitoredRepos = Set.of();
            this.allowAllRepos = true;
            log.warn("GitHubApiMonitor: no monitored-repos configured — polling is enabled but will do nothing. "
                    + "Set review.monitored-repos (e.g. MONITORED_REPOS=owner/repo1).");
        } else {
            this.monitoredRepos = Set.of(monitoredReposCsv.split("\\s*,\\s*"));
            this.allowAllRepos = false;
            log.info("GitHubApiMonitor started — watching {} repo(s): {}", monitoredRepos.size(), monitoredRepos);
        }
    }

    /**
     * Polls GitHub every {@code review.polling.interval-seconds} seconds
     * (default: 60s) for open pull requests.
     *
     * <p>Spring {@code @Scheduled} uses the fixed-delay variant so slow reviews
     * don't cause overlapping poll cycles.
     */
    @Scheduled(fixedDelayString = "${review.polling.interval-seconds:60}000")
    public void poll() {
        if (allowAllRepos) {
            // Cannot poll "all repos" — skip.
            return;
        }

        log.debug("Polling {} repo(s) for open PRs…", monitoredRepos.size());

        for (String repo : monitoredRepos) {
            try {
                pollRepo(repo);
            } catch (Exception e) {
                log.error("Failed to poll repo {}", repo, e);
            }
        }
    }

    // -- private helpers --

    private void pollRepo(String repoFullName) throws IOException, InterruptedException {
        List<PrInfo> openPrs = fetchOpenPrs(repoFullName);
        log.debug("  {}: found {} open PR(s)", repoFullName, openPrs.size());

        for (PrInfo pr : openPrs) {
            if (seenPrTracker.hasBeenSeen(repoFullName, pr.number(), pr.headSha())) {
                log.debug("  Skipping already-reviewed PR #{} (sha={})", pr.number(), pr.headSha());
                continue;
            }

            if (skipOnUnresolved && diffFetcher.hasUnresolvedThreads(repoFullName, pr.number())) {
                log.info("  Skipping PR #{} in {} because there are unresolved review comments", pr.number(), repoFullName);
                continue;
            }

            log.info("New/updated PR detected: {}/pull/{} — '{}' (sha={})",
                    repoFullName, pr.number(), pr.title(), pr.headSha());

            // Mark as seen immediately to avoid double-queuing if review is slow
            seenPrTracker.markSeen(repoFullName, pr.number(), pr.headSha());

            final String repo = repoFullName;
            final int prNumber = pr.number();

            // Trigger review asynchronously so we don't block the poll loop
            Thread.ofVirtual().name("mcp-review-" + repo + "#" + prNumber).start(() -> {
                try {
                    mcpReviewAgent.reviewPr(repo, prNumber);
                } catch (Exception ex) {
                    log.error("Review failed for {}/pull/{}, removing from seen set for retry",
                            repo, prNumber, ex);
                    seenPrTracker.unmark(repo, prNumber);
                }
            });
        }
    }

    private List<PrInfo> fetchOpenPrs(String repoFullName) throws IOException, InterruptedException {
        String url = GITHUB_API + "/repos/" + repoFullName + "/pulls?state=open&per_page=50";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("GitHub API returned " + response.statusCode()
                    + " when listing PRs for " + repoFullName + ": " + response.body());
        }

        return parsePrs(response.body());
    }

    private List<PrInfo> parsePrs(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<PrInfo> results = new ArrayList<>();
            for (JsonNode pr : root) {
                int number = pr.path("number").asInt();
                String title = pr.path("title").asText("");
                String headSha = pr.path("head").path("sha").asText("");
                if (number > 0 && !headSha.isBlank()) {
                    results.add(new PrInfo(number, title, headSha));
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("Failed to parse PRs JSON", e);
            return List.of();
        }
    }

    /** Lightweight immutable DTO for a PR returned by the listing API. */
    private record PrInfo(int number, String title, String headSha) {}
}
