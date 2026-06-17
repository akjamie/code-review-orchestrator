package org.akj.reviewer.synthesizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.akj.reviewer.agent.AgentContext;
import org.akj.reviewer.agent.AgentResult;
import org.akj.reviewer.agent.Finding;
import org.akj.reviewer.agent.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class SynthesizerAgent {

    private static final Logger log = LoggerFactory.getLogger(SynthesizerAgent.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int maxTokens;

    public SynthesizerAgent(ChatClient chatClient,
                            ObjectMapper objectMapper,
                            @Qualifier("review.model") String model,
                            @Qualifier("review.synthesizer.max-tokens") int maxTokens) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    public ReviewResult synthesize(List<AgentResult> results, AgentContext context) {
        List<Finding> allFindings = flattenAndSort(results);

        try {
            String systemPrompt = loadSystemPrompt();
            String userPrompt = buildUserPrompt(results, context);

            var opts = DeepSeekChatOptions.builder()
                    .model(model)
                    .maxTokens(maxTokens);
            String markdownResult = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .options(opts)
                .call()
                .content();

            String body = markdownResult != null ? markdownResult : buildFallbackReview(allFindings);
            return new ReviewResult(body, allFindings);

        } catch (Exception e) {
            log.error("Synthesizer failed", e);
            return new ReviewResult(buildFallbackReview(allFindings), allFindings);
        }
    }

    private List<Finding> flattenAndSort(List<AgentResult> results) {
        // Dedup: same file + line + message -> keep higher severity
        return results.stream()
            .flatMap(r -> r.findings().stream())
            .collect(Collectors.toMap(
                f -> dedupKey(f),
                f -> f,
                (a, b) -> a.severity().compareTo(b.severity()) <= 0 ? a : b
            ))
            .values()
            .stream()
            .sorted(Comparator
                .comparingInt((Finding f) -> severityRank(f.severity()))
                .thenComparing(Finding::filePath))
            .toList();
    }

    private String dedupKey(Finding f) {
        return f.filePath() + ":" + f.lineNumber().map(Object::toString).orElse("0") + ":" + f.message();
    }

    private int severityRank(Severity s) {
        return switch (s) {
            case CRITICAL -> 0;
            case HIGH -> 1;
            case MEDIUM -> 2;
            case LOW -> 3;
            case INFO -> 4;
        };
    }

    private String loadSystemPrompt() {
        try {
            var resource = new ClassPathResource("prompts/synthesizer-agent.txt");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load synthesizer prompt", e);
        }
    }

    private String buildUserPrompt(List<AgentResult> results, AgentContext context) {
        var sb = new StringBuilder();
        sb.append("Synthesize a review for PR #").append(context.prNumber());
        sb.append(" in ").append(context.repoFullName()).append(".\n\n");
        if (!context.detectedLanguages().isEmpty()) {
            sb.append("Languages detected: ").append(String.join(", ", context.detectedLanguages())).append("\n\n");
        }
        sb.append("Changed files: ").append(String.join(", ", context.changedFiles())).append("\n\n");

        for (AgentResult result : results) {
            sb.append("### ").append(result.agentName()).append(" findings\n");
            if (result.findings().isEmpty()) {
                sb.append("No findings.\n\n");
                continue;
            }
            try {
                String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result.findings());
                sb.append("```json\n").append(json).append("\n```\n\n");
            } catch (Exception e) {
                sb.append("(failed to serialize findings)\n\n");
            }
        }

        return sb.toString();
    }

    // Package-private for testing
    String buildFallbackReview(List<Finding> allFindings) {
        var sb = new StringBuilder();
        sb.append("## AI Code Review\n\n");
        sb.append("### Summary\n");

        long critical = allFindings.stream().filter(f -> f.severity() == Severity.CRITICAL).count();
        long high = allFindings.stream().filter(f -> f.severity() == Severity.HIGH).count();
        long medium = allFindings.stream().filter(f -> f.severity() == Severity.MEDIUM).count();

        if (allFindings.isEmpty()) {
            sb.append("No issues found in this diff.\n\n");
        } else {
            sb.append("Found ").append(allFindings.size()).append(" issue(s): ");
            if (critical > 0) sb.append(critical).append(" CRITICAL, ");
            if (high > 0) sb.append(high).append(" HIGH, ");
            if (medium > 0) sb.append(medium).append(" MEDIUM, ");
            sb.append("and other findings.\n\n");
        }

        sb.append("### Findings\n\n");
        sb.append("| Severity | File | Line | Category | Issue |\n");
        sb.append("|---|---|---|---|---|\n");

        for (Finding f : allFindings) {
            String emoji = switch (f.severity()) {
                case CRITICAL -> "🔴"; // red circle
                case HIGH -> "🟠";     // orange circle
                case MEDIUM -> "🟡";   // yellow circle
                case LOW -> "🔵";      // blue circle
                case INFO -> "⚪";            // white circle
            };
            String line = f.lineNumber().map(Object::toString).orElse("-");
            sb.append("| ").append(emoji).append(" ").append(f.severity())
              .append(" | ").append(f.filePath())
              .append(" | ").append(line)
              .append(" | ").append(f.category())
              .append(" | ").append(f.message())
              .append(" |\n");
        }

        sb.append("\n### Suggestions\n\n");
        Map<String, List<Finding>> byFile = allFindings.stream()
            .collect(Collectors.groupingBy(Finding::filePath));
        for (var entry : byFile.entrySet()) {
            sb.append("**").append(entry.getKey()).append("**\n\n");
            for (Finding f : entry.getValue()) {
                sb.append("- ").append(f.suggestion()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("---\n");
        sb.append("*Review generated by Code Review Orchestrator*\n");
        sb.append("*Agents: security · performance · style · test-coverage*\n");

        return sb.toString();
    }
}