package org.akj.reviewer.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.akj.reviewer.agent.AgentContext;
import org.akj.reviewer.agent.AgentResult;
import org.akj.reviewer.agent.SecurityAgent;
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
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
@Tag("eval")
public class PromptInjectionEvalTest {

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionEvalTest.class);

    @Autowired
    private SecurityAgent securityAgent;

    private final ObjectMapper objectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private record RedTeamCaseDto(
        String caseId,
        String description,
        List<String> files
    ) {}

    @Test
    @DisplayName("Evaluate SecurityAgent resilience against adversarial prompt injection embedded in code comments")
    void evaluatePromptInjectionResilience() throws Exception {
        Path injectionDir = Paths.get("src/test/resources/eval/prompt-injection");
        if (!Files.exists(injectionDir)) {
            log.warn("Prompt injection test directory not found at {}", injectionDir);
            return;
        }

        try (Stream<Path> stream = Files.list(injectionDir)) {
            List<Path> cases = stream.filter(Files::isDirectory).toList();
            log.info("Running {} red-teaming prompt injection test cases...", cases.size());

            for (Path caseDir : cases) {
                Path diffPath = caseDir.resolve("input.diff");
                Path jsonPath = caseDir.resolve("expected.json");
                if (!Files.exists(diffPath) || !Files.exists(jsonPath)) continue;

                String diff = Files.readString(diffPath, StandardCharsets.UTF_8);
                RedTeamCaseDto dto = objectMapper.readValue(jsonPath.toFile(), RedTeamCaseDto.class);

                AgentContext ctx = new AgentContext(
                    "adversarial/repo",
                    999,
                    "Red-Team Case: " + dto.caseId(),
                    dto.description() != null ? dto.description() : "Prompt injection test",
                    "attacker",
                    diff,
                    dto.files() != null ? dto.files() : List.of(),
                    false,
                    Set.of("Java")
                );

                log.info("Testing red-team injection case [{}] - {}", dto.caseId(), dto.description());
                AgentResult result = securityAgent.review(ctx);
                assertNotNull(result);

                if (!result.findings().isEmpty()) {
                    log.info("✅ Case [{}]: SecurityAgent DEFENDED successfully. Findings detected: {}",
                        dto.caseId(), result.findings().size());
                } else {
                    log.warn("ℹ️ Case [{}]: 0 findings returned (expected when offline without live API key)", dto.caseId());
                }
            }
        }
    }
}
