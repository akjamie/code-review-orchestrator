package org.akj.reviewer.github;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory tracker that records which PRs (and their latest commit SHAs)
 * have already been reviewed by the polling monitor.
 *
 * <p>This prevents the {@link GitHubApiMonitor} from triggering duplicate reviews
 * when the same PR remains open across multiple polling cycles.
 *
 * <p><strong>Note:</strong> This state is not persisted across restarts. On restart,
 * open PRs may be re-reviewed once if they have not been updated since the last run.
 * This is an acceptable trade-off for the current in-memory implementation.
 */
@Component
public class SeenPrTracker {

    private static final Logger log = LoggerFactory.getLogger(SeenPrTracker.class);

    /**
     * Map of "{owner}/{repo}#{prNumber}" → last-seen head commit SHA.
     */
    private final Map<String, String> seenPrs = new ConcurrentHashMap<>();

    /**
     * Returns {@code true} if this PR's head commit SHA has already been processed.
     */
    public boolean hasBeenSeen(String repoFullName, int prNumber, String headSha) {
        String key = buildKey(repoFullName, prNumber);
        return headSha.equals(seenPrs.get(key));
    }

    /**
     * Marks a PR's head commit SHA as processed.
     */
    public void markSeen(String repoFullName, int prNumber, String headSha) {
        String key = buildKey(repoFullName, prNumber);
        seenPrs.put(key, headSha);
        log.debug("Marked as seen: {} (sha={})", key, headSha);
    }

    /**
     * Removes a PR from the seen set (e.g. if review failed and should be retried).
     */
    public void unmark(String repoFullName, int prNumber) {
        String key = buildKey(repoFullName, prNumber);
        seenPrs.remove(key);
        log.debug("Unmarked: {}", key);
    }

    /**
     * Returns the number of PRs currently tracked.
     */
    public int size() {
        return seenPrs.size();
    }

    private static String buildKey(String repoFullName, int prNumber) {
        return repoFullName + "#" + prNumber;
    }
}
