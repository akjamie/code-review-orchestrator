package org.akj.reviewer.synthesizer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.akj.reviewer.agent.Finding;
import org.akj.reviewer.agent.Severity;
import org.junit.jupiter.api.Test;

class SynthesizerAgentTest {

    @Test
    void fallbackReviewContainsAllFindings() {
        var findings = List.of(
            new Finding(Severity.CRITICAL, "security", "Auth.java", Optional.of(10),
                "SQL injection", "Use prepared statements"),
            new Finding(Severity.HIGH, "security", "Auth.java", Optional.of(25),
                "Hardcoded secret", "Move to env var"),
            new Finding(Severity.MEDIUM, "performance", "Loop.java", Optional.of(5),
                "N+1 query", "Add batch fetching")
        );

        var synth = new SynthesizerAgent(null, null, null, 0);
        var review = synth.buildFallbackReview(findings);

        assertTrue(review.contains("SQL injection"));
        assertTrue(review.contains("CRITICAL"));
        assertTrue(review.contains("N+1 query"));
        assertTrue(review.contains("AI Code Review"));
        assertTrue(review.contains("Code Review Orchestrator"));
    }

    @Test
    void fallbackReviewHandlesEmptyFindings() {
        var synth = new SynthesizerAgent(null, null, null, 0);
        var review = synth.buildFallbackReview(List.of());

        assertTrue(review.contains("No issues found"));
        assertTrue(review.contains("AI Code Review"));
    }

    @Test
    void fallbackReviewGroupsByFile() {
        var findings = List.of(
            new Finding(Severity.HIGH, "security", "App.java", Optional.of(1),
                "Issue A", "Fix A"),
            new Finding(Severity.LOW, "style", "App.java", Optional.of(2),
                "Issue B", "Fix B"),
            new Finding(Severity.MEDIUM, "performance", "Db.java", Optional.empty(),
                "Issue C", "Fix C")
        );

        var synth = new SynthesizerAgent(null, null, null, 0);
        var review = synth.buildFallbackReview(findings);

        assertTrue(review.contains("App.java"));
        assertTrue(review.contains("Db.java"));
        // Both suggestions appear
        assertTrue(review.contains("Fix A"));
        assertTrue(review.contains("Fix B"));
        assertTrue(review.contains("Fix C"));
    }
}