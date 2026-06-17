package org.akj.reviewer.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.core.io.ClassPathResource;

public abstract class BaseReviewAgent implements ReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(BaseReviewAgent.class);

    protected final ChatClient chatClient;
    protected final ObjectMapper objectMapper;
    protected final String model;
    protected final int maxTokens;

    protected BaseReviewAgent(ChatClient chatClient, ObjectMapper objectMapper, String model, int maxTokens) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    protected abstract String systemPromptFilename();

    @Override
    public AgentResult review(AgentContext ctx) {
        try {
            String systemPrompt = loadSystemPrompt();
            String userPrompt = buildUserPrompt(ctx);
            String rawResponse = callAiModel(systemPrompt, userPrompt);
            List<Finding> findings = parseFindings(rawResponse);
            return new AgentResult(agentName(), findings, rawResponse);
        } catch (Exception e) {
            log.warn("Agent {} failed: {}", agentName(), e.getMessage());
            return AgentResult.empty(agentName(), e);
        }
    }

    protected String loadSystemPrompt() {
        try {
            var resource = new ClassPathResource("prompts/" + systemPromptFilename());
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load system prompt: " + systemPromptFilename(), e);
        }
    }

    protected String buildUserPrompt(AgentContext ctx) {
        var sb = new StringBuilder();
        sb.append("Review the following pull request diff.\n\n");
        sb.append("Repository: ").append(ctx.repoFullName()).append("\n");
        sb.append("PR #").append(ctx.prNumber()).append(": ").append(ctx.prTitle()).append("\n");
        sb.append("Changed files: ").append(String.join(", ", ctx.changedFiles())).append("\n");

        if (!ctx.detectedLanguages().isEmpty()) {
            sb.append("Languages detected: ").append(String.join(", ", ctx.detectedLanguages())).append("\n");
        }

        sb.append("\n```diff\n");
        sb.append(ctx.diffContent());
        sb.append("\n```\n");

        if (ctx.isPartial()) {
            sb.append("\n**NOTE:** This diff has been truncated. Flag any findings as potentially ");
            sb.append("incomplete and do not draw conclusions about unchanged files.\n");
        }

        return sb.toString();
    }

    private String callAiModel(String systemPrompt, String userPrompt) {
        var opts = DeepSeekChatOptions.builder()
            .model(model)
            .maxTokens(maxTokens);
        return chatClient.prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .options(opts)
            .call()
            .content();
    }

    private List<Finding> parseFindings(String rawResponse) {
        String cleaned = cleanJson(rawResponse);
        try {
            var findingsDto = objectMapper.readValue(cleaned, new TypeReference<List<FindingDto>>() {});
            return findingsDto.stream()
                .map(dto -> new Finding(
                    dto.severity(),
                    dto.category(),
                    dto.filePath(),
                    Optional.ofNullable(dto.lineNumber()),
                    dto.message(),
                    dto.suggestion()
                ))
                .toList();
        } catch (JsonProcessingException e) {
            log.warn("Agent {} failed to parse response: {}", agentName(), e.getMessage());
            log.debug("Raw response was: {}", rawResponse);
            return List.of();
        }
    }

    private String cleanJson(String rawResponse) {
        if (rawResponse == null) {
            return "[]";
        }
        String cleaned = rawResponse.trim();

        // Extract the JSON array content between the first '[' and last ']' if present
        int firstBracket = cleaned.indexOf('[');
        int lastBracket = cleaned.lastIndexOf(']');
        if (firstBracket != -1 && lastBracket != -1 && lastBracket > firstBracket) {
            return cleaned.substring(firstBracket, lastBracket + 1).trim();
        }

        // Fallback: strip markdown blocks
        if (cleaned.startsWith("```")) {
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.substring(7);
            } else {
                int firstNewline = cleaned.indexOf('\n');
                if (firstNewline != -1) {
                    cleaned = cleaned.substring(firstNewline + 1);
                } else {
                    cleaned = cleaned.substring(3);
                }
            }
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }
}