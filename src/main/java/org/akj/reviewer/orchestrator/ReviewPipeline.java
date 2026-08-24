package org.akj.reviewer.orchestrator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.akj.reviewer.agent.AgentContext;
import org.akj.reviewer.agent.AgentResult;
import org.akj.reviewer.agent.ReviewAgent;
import org.akj.reviewer.config.AiConfig;
import org.akj.reviewer.github.GitHubDiffFetcher;
import org.akj.reviewer.github.GitHubReviewPoster;
import org.akj.reviewer.synthesizer.ReviewResult;
import org.akj.reviewer.synthesizer.SynthesizerAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Unified PR review pipeline.
 *
 * <p>Entry point for all review triggers (webhook, polling, manual URL trigger).
 * Executes four steps:
 * <ol>
 *   <li>Fetch PR context — via GitHub MCP tools (preferred) or REST fallback.</li>
 *   <li>Parallel analysis — fan-out to enabled specialist agents via {@link ReviewOrchestrator}.</li>
 *   <li>Synthesize — merge and rank findings via {@link SynthesizerAgent}.</li>
 *   <li>Post — publish review comment via {@link GitHubReviewPoster}.</li>
 * </ol>
 */
@Component
public class ReviewPipeline {

    private static final Logger log = LoggerFactory.getLogger(ReviewPipeline.class);
    private static final String MCP_FETCH_PROMPT = "prompts/mcp-fetch-pr.txt";

    private final ReviewOrchestrator orchestrator;
    private final SynthesizerAgent synthesizer;
    private final GitHubDiffFetcher diffFetcher;
    private final GitHubReviewPoster reviewPoster;
    private final List<ReviewAgent> allAgents;
    private final ChatClient.Builder chatClientBuilder;
    private final ToolCallbackProvider mcpTools;   // null when MCP is not configured
    private final io.micrometer.observation.ObservationRegistry observationRegistry;
    private final String mcpModel;
    private final int mcpMaxTokens;

    @Value("${review.agents.enabled.security:true}")
    private boolean securityEnabled;

    @Value("${review.agents.enabled.performance:true}")
    private boolean performanceEnabled;

    @Value("${review.agents.enabled.style:true}")
    private boolean styleEnabled;

    @Value("${review.agents.enabled.test-coverage:true}")
    private boolean testCoverageEnabled;

    public ReviewPipeline(ReviewOrchestrator orchestrator,
                          SynthesizerAgent synthesizer,
                          GitHubDiffFetcher diffFetcher,
                          GitHubReviewPoster reviewPoster,
                          List<ReviewAgent> allAgents,
                          ChatClient.Builder chatClientBuilder,
                          @Autowired(required = false) ToolCallbackProvider mcpToolCallbackProvider,
                          io.micrometer.observation.ObservationRegistry observationRegistry,
                          @Value("${review.mcp-agent.model:${spring.ai.deepseek.chat.model:deepseek-chat}}") String mcpModel,
                          @Value("${review.mcp-agent.max-tokens:8192}") int mcpMaxTokens) {
        this.orchestrator = orchestrator;
        this.synthesizer = synthesizer;
        this.diffFetcher = diffFetcher;
        this.reviewPoster = reviewPoster;
        this.allAgents = allAgents;
        this.chatClientBuilder = chatClientBuilder;
        this.mcpTools = mcpToolCallbackProvider;
        this.observationRegistry = observationRegistry;
        this.mcpModel = mcpModel;
        this.mcpMaxTokens = mcpMaxTokens;
    }

    /**
     * Runs a full end-to-end code review for a pull request.
     *
     * @param repoFullName the GitHub repository in {@code owner/repo} format
     * @param prNumber     the pull request number
     */
    public void reviewPr(String repoFullName, int prNumber) {
        log.info("Starting review for {}/pull/{}", repoFullName, prNumber);

        io.micrometer.observation.Observation observation = startObservation(repoFullName, prNumber);

        try (io.micrometer.observation.Observation.Scope scope = observation.openScope()) {
            // Step 1 — Fetch PR context (MCP preferred, REST fallback)
            AgentContext context = fetchContext(repoFullName, prNumber);

            observation.highCardinalityKeyValue("langfuse.trace.input",
                "Repository: %s\nPR: #%d\nTitle: %s\nFiles: %s"
                    .formatted(repoFullName, prNumber, context.prTitle(), context.changedFiles()));

            // Step 2 & 3 — Parallel agents + Synthesize
            ReviewResult result = execute(context);

            if (result != null) {
                observation.highCardinalityKeyValue("langfuse.trace.output", result.markdownBody());
                // Step 4 — Post review
                reviewPoster.postReview(repoFullName, prNumber, result.markdownBody(), result.findings());
            }

        } catch (Exception e) {
            observation.error(e);
            log.error("Review failed for {}/pull/{}", repoFullName, prNumber, e);
        } finally {
            observation.stop();
        }
    }

    /**
     * Executes the review pipeline (parallel agents + synthesis) on a pre-built {@link AgentContext}.
     * Useful for local testing and direct context invocation.
     *
     * @param context the PR or local diff context
     * @return the synthesized {@link ReviewResult}
     */
    public ReviewResult execute(AgentContext context) {
        List<ReviewAgent> enabled = filterEnabledAgents();
        if (enabled.isEmpty()) {
            log.warn("No agents enabled — skipping execution for PR #{}", context.prNumber());
            return new ReviewResult("No agents enabled", List.of());
        }
        log.info("Running {} agent(s) in parallel on PR #{} (languages: {})",
            enabled.size(), context.prNumber(), context.detectedLanguages());

        List<AgentResult> results = orchestrator.runAgents(enabled, context);
        return synthesizer.synthesize(results, context);
    }

    // -------------------------------------------------------------------------
    // Step 1 — Fetch
    // -------------------------------------------------------------------------

    /**
     * Fetches PR context. Uses GitHub MCP tools when available (richer context:
     * PR comments, review history). Falls back to direct REST via
     * {@link GitHubDiffFetcher} when MCP is not configured or fails.
     */
    private AgentContext fetchContext(String repoFullName, int prNumber) {
        if (mcpTools != null) {
            try {
                return fetchContextViaMcp(repoFullName, prNumber);
            } catch (Exception e) {
                log.warn("MCP fetch failed for {}/pull/{} — falling back to REST: {}",
                    repoFullName, prNumber, e.getMessage());
            }
        }
        return diffFetcher.fetchContext(repoFullName, prNumber);
    }

    /**
     * Uses a focused GitHub MCP tool-calling LLM call to fetch PR metadata and diff,
     * then returns it as an {@link AgentContext}. The MCP path captures additional
     * context (PR comments, prior review threads) beyond what the REST endpoint exposes.
     */
    private AgentContext fetchContextViaMcp(String repoFullName, int prNumber) throws IOException {
        log.debug("Fetching PR context via GitHub MCP for {}/pull/{}", repoFullName, prNumber);

        String systemPrompt = new ClassPathResource(MCP_FETCH_PROMPT)
            .getContentAsString(StandardCharsets.UTF_8);

        String userPrompt = """
            Fetch all relevant details for pull request #%d in repository %s.
            Use GitHub tools to retrieve: PR title, description, author, diff, and changed files.
            Return the result as a JSON object with these exact fields:
              { "prTitle": "", "prDescription": "", "prAuthor": "", "diffContent": "", "changedFiles": [] }
            """.formatted(prNumber, repoFullName);

        var options = DeepSeekChatOptions.builder()
            .model(mcpModel)
            .maxTokens(mcpMaxTokens);

        String json = chatClientBuilder.build()
            .prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .tools((Object[]) mcpTools.getToolCallbacks())
            .options(options)
            .call()
            .content();

        return parseMcpFetchResponse(json, repoFullName, prNumber);
    }

    /**
     * Parses the JSON response from the MCP fetch step into an {@link AgentContext}.
     * Falls back to direct REST fetch if the response cannot be parsed.
     */
    private AgentContext parseMcpFetchResponse(String json, String repoFullName, int prNumber) {
        try {
            // Extract fields from the LLM JSON response
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(extractJson(json));
            String prTitle = node.path("prTitle").asText("");
            String prDescription = node.path("prDescription").asText("");
            String prAuthor = node.path("prAuthor").asText("unknown");
            String diffContent = node.path("diffContent").asText("");

            java.util.List<String> changedFiles = new java.util.ArrayList<>();
            node.path("changedFiles").forEach(f -> changedFiles.add(f.asText()));

            if (diffContent.isBlank()) {
                log.warn("MCP fetch returned empty diff for {}/pull/{} — falling back to REST", repoFullName, prNumber);
                return diffFetcher.fetchContext(repoFullName, prNumber, prTitle, prDescription, prAuthor);
            }

            // Reuse GitHubDiffFetcher's language detection logic
            java.util.Set<String> languages = diffFetcher.detectLanguages(changedFiles);
            boolean isPartial = diffContent.length() > 80_000;
            String diff = isPartial ? diffContent.substring(0, 80_000) : diffContent;

            return new AgentContext(repoFullName, prNumber, prTitle, prDescription,
                prAuthor, diff, changedFiles, isPartial, languages);

        } catch (Exception e) {
            log.warn("Failed to parse MCP fetch response for {}/pull/{}: {} — falling back to REST",
                repoFullName, prNumber, e.getMessage());
            return diffFetcher.fetchContext(repoFullName, prNumber);
        }
    }

    /** Extracts the JSON object/array from an LLM response that may have prose around it. */
    private String extractJson(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start != -1 && end > start) ? raw.substring(start, end + 1) : raw;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<ReviewAgent> filterEnabledAgents() {
        return allAgents.stream()
            .filter(agent -> switch (agent.agentName()) {
                case "security"      -> securityEnabled;
                case "performance"   -> performanceEnabled;
                case "style"         -> styleEnabled;
                case "test-coverage" -> testCoverageEnabled;
                default -> {
                    log.warn("Unknown agent: {} — enabling by default", agent.agentName());
                    yield true;
                }
            })
            .toList();
    }

    private io.micrometer.observation.Observation startObservation(String repoFullName, int prNumber) {
        io.micrometer.observation.Observation.Scope originalScope = observationRegistry.getCurrentObservationScope();
        observationRegistry.setCurrentObservationScope(null);
        try (io.opentelemetry.context.Scope otelScope = io.opentelemetry.context.Context.root().makeCurrent()) {
            return io.micrometer.observation.Observation.start("review-pipeline", observationRegistry);
        } finally {
            observationRegistry.setCurrentObservationScope(originalScope);
        }
    }
}