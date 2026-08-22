package org.akj.reviewer.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.akj.reviewer.agent.AgentContext;
import org.akj.reviewer.agent.Finding;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
@Tag("eval")
public class EvalSuiteTest {

    private static final Logger log = LoggerFactory.getLogger(EvalSuiteTest.class);

    @Autowired
    private ReviewPipeline pipeline;

    private final ObjectMapper objectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private record EvalCaseDto(
        String caseId,
        String description,
        List<String> files,
        List<ExpectedFinding> expectedFindings,
        List<EvalCase.SafeRegion> safeRegions
    ) {}

    @Test
    @DisplayName("Run evaluation benchmark suite and generate report")
    void runBenchmarkSuite() throws Exception {
        List<EvalCase> cases = new ArrayList<>();
        cases.addAll(loadCases(Paths.get("src/test/resources/eval/cases")));
        cases.addAll(loadCases(Paths.get("src/test/resources/eval/owasp-agentic-2026")));
        log.info("Loaded {} total eval benchmark and OWASP ASI cases", cases.size());

        List<EvalReport.CaseEvaluation> evaluations = new ArrayList<>();

        for (EvalCase evalCase : cases) {
            log.info("Evaluating case: [{}] - {}", evalCase.caseId(), evalCase.description());

            AgentContext ctx = new AgentContext(
                "benchmark/eval-repo",
                100,
                "Eval Case: " + evalCase.caseId(),
                evalCase.description() != null ? evalCase.description() : "",
                "eval-runner",
                evalCase.diff(),
                evalCase.files(),
                false,
                Set.of("Java")
            );

            ReviewResult reviewResult = pipeline.execute(ctx);
            assertNotNull(reviewResult, "Pipeline must return non-null ReviewResult");

            List<Finding> actualFindings = reviewResult.findings() != null ? reviewResult.findings() : List.of();
            FindingMatcher.MatchResult matchResult = FindingMatcher.match(actualFindings, evalCase);

            log.info("Case [{}] Result => Hits: {}, Misses: {}, False Positives: {}",
                evalCase.caseId(),
                matchResult.hits().size(),
                matchResult.misses().size(),
                matchResult.falsePositives().size());

            evaluations.add(new EvalReport.CaseEvaluation(
                evalCase.caseId(),
                evalCase.description(),
                matchResult
            ));
        }

        EvalReport report = new EvalReport(evaluations);
        Path reportOut = Paths.get("build/eval/eval_report.md");
        report.writeMarkdownReport(reportOut);
        log.info("Evaluation report generated at {}", reportOut.toAbsolutePath());
        System.out.println("\n" + report.toMarkdown());

        // Note: For CI with live API keys, you can assert threshold:
        // report.assertThresholds(0.70, 0.60);
    }

    private List<EvalCase> loadCases(Path rootDir) throws IOException {
        try (Stream<Path> stream = Files.list(rootDir)) {
            return stream
                .filter(Files::isDirectory)
                .map(this::loadSingleCase)
                .filter(c -> c != null)
                .toList();
        }
    }

    private EvalCase loadSingleCase(Path dir) {
        try {
            Path diffPath = dir.resolve("input.diff");
            Path jsonPath = dir.resolve("expected.json");
            if (!Files.exists(diffPath) || !Files.exists(jsonPath)) {
                log.warn("Skipping directory {}: missing input.diff or expected.json", dir);
                return null;
            }
            String diff = Files.readString(diffPath, StandardCharsets.UTF_8);
            EvalCaseDto dto = objectMapper.readValue(jsonPath.toFile(), EvalCaseDto.class);
            return new EvalCase(
                dto.caseId() != null ? dto.caseId() : dir.getFileName().toString(),
                dto.description(),
                diff,
                dto.files(),
                dto.expectedFindings(),
                dto.safeRegions()
            );
        } catch (Exception e) {
            log.error("Failed to load eval case from directory {}", dir, e);
            throw new RuntimeException("Failed to load eval case from " + dir, e);
        }
    }
}
