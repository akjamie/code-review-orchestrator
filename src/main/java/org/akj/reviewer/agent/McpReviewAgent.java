package org.akj.reviewer.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Autonomous code review agent that uses GitHub MCP and Context7 MCP tools
 * to perform a full end-to-end PR review without pre-fetched diff context.
 *
 * <p>The agent is given only the repository name and PR number. It uses:
 * <ul>
 *   <li>GitHub MCP tools to fetch the PR diff, files, and post review comments.</li>
 *   <li>Context7 MCP tools to look up up-to-date library documentation for
 *       frameworks detected in the changed files.</li>
 * </ul>
 *
 * <p>Enable via {@code review.mcp-agent.enabled=true} (default: true).
 */
@Component
@ConditionalOnProperty(name = "review.mcp-agent.enabled", havingValue = "true", matchIfMissing = true)
public class McpReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(McpReviewAgent.class);
    private static final String PROMPT_FILE = "prompts/mcp-review-agent.txt";

    private final ChatClient.Builder chatClientBuilder;
    private final ToolCallbackProvider mcpTools;
    private final String model;
    private final int maxTokens;

    public McpReviewAgent(
            ChatClient.Builder chatClientBuilder,
            ToolCallbackProvider mcpToolCallbackProvider,
            @Value("${review.mcp-agent.model:${spring.ai.deepseek.chat.model:deepseek-chat}}") String model,
            @Value("${review.mcp-agent.max-tokens:8192}") int maxTokens) {
        this.chatClientBuilder = chatClientBuilder;
        this.mcpTools = mcpToolCallbackProvider;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    /**
     * Triggers an autonomous code review for the given PR.
     *
     * <p>The agent autonomously:
     * <ol>
     *   <li>Fetches the PR details and diff using GitHub MCP tools.</li>
     *   <li>Analyzes the changes for security, performance, style, and test issues.</li>
     *   <li>Queries Context7 for documentation of frameworks/libraries in the diff.</li>
     *   <li>Posts a review comment (with inline annotations) back to the PR.</li>
     * </ol>
     *
     * @param repoFullName the GitHub repository in {@code owner/repo} format
     * @param prNumber     the pull request number
     */
    public void reviewPr(String repoFullName, int prNumber) {
        log.info("MCP agent starting review for {}/pull/{}", repoFullName, prNumber);

        try {
            String systemPrompt = loadSystemPrompt();

            String userPrompt = buildUserPrompt(repoFullName, prNumber);

            var options = DeepSeekChatOptions.builder()
                    .model(model)
                    .maxTokens(maxTokens);

            // Build a ChatClient with MCP tools registered for this request
            ChatClient client = chatClientBuilder
                    .defaultSystem(systemPrompt)
                    .build();

            String result = client.prompt()
                    .user(userPrompt)
                    .tools((Object[]) mcpTools.getToolCallbacks())
                    .options(options)
                    .call()
                    .content();

            log.info("MCP agent completed review for {}/pull/{}: {}",
                    repoFullName, prNumber,
                    result != null ? result.substring(0, Math.min(200, result.length())) + "..." : "(null)");

        } catch (Exception e) {
            log.error("MCP agent review failed for {}/pull/{}", repoFullName, prNumber, e);
        }
    }

    private String buildUserPrompt(String repoFullName, int prNumber) {
        return """
                Please review pull request #%d in the repository %s.

                Steps to follow:
                1. Use GitHub tools to fetch the PR details, diff, and changed files.
                2. Analyze the changes thoroughly (security, performance, style, test coverage).
                3. If external libraries or frameworks are modified, use Context7 tools to look up \
                up-to-date documentation for those specific libraries and versions.
                4. Post a complete PR review using GitHub tools — include an overall summary \
                comment (markdown) and inline comments for specific findings.

                Repository: %s
                PR number: %d
                """.formatted(prNumber, repoFullName, repoFullName, prNumber);
    }

    private String loadSystemPrompt() {
        try {
            var resource = new ClassPathResource(PROMPT_FILE);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load MCP agent system prompt: " + PROMPT_FILE, e);
        }
    }
}
