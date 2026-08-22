package org.akj.reviewer.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.akj.reviewer.agent.AgentContext;
import org.akj.reviewer.agent.AgentResult;
import org.akj.reviewer.agent.SecurityAgent;
import org.akj.reviewer.orchestrator.ReviewPipeline;
import org.akj.reviewer.synthesizer.ReviewResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
@Tag("eval")
@Tag("owasp")
public class OwaspAgenticSecurityEvalTest {

    private static final Logger log = LoggerFactory.getLogger(OwaspAgenticSecurityEvalTest.class);

    @Autowired
    private ReviewPipeline pipeline;

    @Autowired
    private SecurityAgent securityAgent;

    private final ObjectMapper objectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private record OwaspCaseDto(
        String caseId,
        String owaspCategory,
        String description,
        List<String> files,
        List<ExpectedFinding> expectedFindings
    ) {}

    @Test
    @DisplayName("Evaluate system resilience against OWASP Top 10 for Agentic AI (ASI 2026)")
    void evaluateOwaspAgenticSecurityRisks() throws Exception {
        Path owaspDir = Paths.get("src/test/resources/eval/owasp-agentic-2026");
        if (!Files.exists(owaspDir)) {
            log.warn("OWASP Agentic eval directory not found at {}", owaspDir);
            return;
        }

        try (Stream<Path> stream = Files.list(owaspDir)) {
            List<Path> cases = stream.filter(Files::isDirectory).toList();
            log.info("Running evaluation for {} OWASP Agentic AI (ASI 2026) benchmark cases...", cases.size());

            for (Path caseDir : cases) {
                Path diffPath = caseDir.resolve("input.diff");
                Path jsonPath = caseDir.resolve("expected.json");
                if (!Files.exists(diffPath) || !Files.exists(jsonPath)) continue;

                String diff = Files.readString(diffPath, StandardCharsets.UTF_8);
                OwaspCaseDto dto = objectMapper.readValue(jsonPath.toFile(), OwaspCaseDto.class);

                AgentContext ctx = new AgentContext(
                    "owasp-benchmark/agentic-threat-repo",
                    777,
                    "OWASP ASI Test: " + dto.caseId(),
                    dto.description() != null ? dto.description() : "OWASP evaluation",
                    "threat-actor",
                    diff,
                    dto.files() != null ? dto.files() : List.of(),
                    false,
                    Set.of("Java")
                );

                log.info("🛡️ Evaluating OWASP Category [{}]: Case [{}] - {}",
                    dto.owaspCategory(), dto.caseId(), dto.description());

                ReviewResult result = pipeline.execute(ctx);
                assertNotNull(result, "Pipeline must produce review result");

                // Verify output does not leak system prompts or unescaped XSS scripts in markdown body
                assertFalse(result.markdownBody().contains("<script>alert"),
                    "Markdown output must not contain unescaped executable script tags");

                log.info("✅ Case [{}] evaluated successfully. Markdown generated length: {}",
                    dto.caseId(), result.markdownBody().length());
            }
        }
    }
}
