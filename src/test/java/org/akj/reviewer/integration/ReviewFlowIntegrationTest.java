package org.akj.reviewer.integration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Tag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.akj.reviewer.agent.AgentContext;
import org.akj.reviewer.orchestrator.ReviewPipeline;
import org.akj.reviewer.synthesizer.ReviewResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end integration test that loads a real sample diff and runs
 * the full review pipeline.
 *
 * NOTE: This test requires a valid DEEPSEEK_API_KEY environment variable
 * to make real AI calls. If the key is not set, the test verifies the
 * pipeline structure but will have empty findings.
 *
 * For a quick smoke test without an API key, see {@link #pipelineProducesReviewResult()}.
 */
@SpringBootTest
@Tag("integration")
@ActiveProfiles("test")
class ReviewFlowIntegrationTest {

    @Autowired
    private ReviewPipeline pipeline;

    @Test
    void pipelineProducesReviewResult() throws IOException {
        String diff = loadDiff("diffs/sql-injection-vuln.diff");
        var files = List.of(
            "src/main/java/com/example/app/repository/UserRepository.java",
            "src/main/java/com/example/app/service/AuthService.java"
        );

        var ctx = new AgentContext(
            "owner/repo", 1, "Test PR", "Test description", "author",
            diff, files, false, Set.of("Java")
        );

        ReviewResult result = pipeline.execute(ctx);

        // The pipeline should always produce a non-null result
        assertNotNull(result);
        assertNotNull(result.markdownBody());
        assertNotNull(result.findings());

        // The markdown should contain the review header
        assertTrue(result.markdownBody().contains("AI Code Review"),
            "Markdown should contain review header");

        // Log the result for manual inspection
        System.out.println("=== MARKDOWN OUTPUT ===");
        System.out.println(result.markdownBody());
        System.out.println("=== FINDINGS (" + result.findings().size() + " total) ===");
        result.findings().forEach(f ->
            System.out.println("  [" + f.severity() + "] " + f.filePath()
                + f.lineNumber().map(l -> ":" + l).orElse("")
                + " — " + f.message()));
    }

    @Test
    void pipelineHandlesTruncatedDiff() throws IOException {
        // Load a diff and truncate it
        String originalDiff = loadDiff("diffs/sql-injection-vuln.diff");
        String truncatedDiff = originalDiff.length() > 100
            ? originalDiff.substring(0, 100)
            : originalDiff;

        var ctx = new AgentContext(
            "owner/repo", 2, "Truncated PR", "", "author",
            truncatedDiff, List.of("AuthService.java"), true, Set.of("Java")
        );

        ReviewResult result = pipeline.execute(ctx);

        assertNotNull(result);
        assertNotNull(result.markdownBody());
    }

    @Test
    void pipelineHandlesEmptyDiff() {
        var ctx = new AgentContext(
            "owner/repo", 3, "Empty PR", "", "author",
            "", List.of(), false, Set.of()
        );

        ReviewResult result = pipeline.execute(ctx);

        assertNotNull(result);
        assertNotNull(result.markdownBody());
    }

    private String loadDiff(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}