package org.akj.reviewer.orchestrator;

import java.util.List;
import org.akj.reviewer.agent.AgentContext;
import org.akj.reviewer.agent.AgentResult;
import org.akj.reviewer.agent.ReviewAgent;
import org.akj.reviewer.synthesizer.ReviewResult;
import org.akj.reviewer.synthesizer.SynthesizerAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReviewPipeline {

    private static final Logger log = LoggerFactory.getLogger(ReviewPipeline.class);

    private final ReviewOrchestrator orchestrator;
    private final SynthesizerAgent synthesizer;
    private final List<ReviewAgent> allAgents;
    private final io.micrometer.observation.ObservationRegistry observationRegistry;

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
                          List<ReviewAgent> allAgents,
                          io.micrometer.observation.ObservationRegistry observationRegistry) {
        this.orchestrator = orchestrator;
        this.synthesizer = synthesizer;
        this.allAgents = allAgents;
        this.observationRegistry = observationRegistry;
    }

    public ReviewResult execute(AgentContext context) {
        String userId = context.prAuthor() != null ? context.prAuthor() : "unknown";
        String sessionId = context.repoFullName() + "#" + context.prNumber();

        org.akj.reviewer.config.ReviewContextHolder.set(new org.akj.reviewer.config.ReviewContext(userId, sessionId));

        io.micrometer.observation.Observation.Scope originalScope = observationRegistry.getCurrentObservationScope();
        observationRegistry.setCurrentObservationScope(null);
        io.micrometer.observation.Observation observation;
        try (io.opentelemetry.context.Scope otelScope = io.opentelemetry.context.Context.root().makeCurrent()) {
            observation = io.micrometer.observation.Observation.start("review-pipeline", observationRegistry);
        } finally {
            observationRegistry.setCurrentObservationScope(originalScope);
        }

        // Build a readable summary of the PR / files being reviewed as input
        String inputSummary = String.format("Repository: %s\nPR Number: %d\nTitle: %s\nFiles: %s",
            context.repoFullName(), context.prNumber(), context.prTitle(), context.changedFiles());
        observation.highCardinalityKeyValue("langfuse.trace.input", inputSummary);

        try (io.micrometer.observation.Observation.Scope scope = observation.openScope()) {
            List<ReviewAgent> enabledAgents = filterEnabledAgents();

            if (enabledAgents.isEmpty()) {
                log.warn("No agents enabled for review");
                ReviewResult emptyResult = new ReviewResult(
                    "## AI Code Review\n\nNo review agents are currently enabled.",
                    List.of());
                observation.highCardinalityKeyValue("langfuse.trace.output", emptyResult.markdownBody());
                return emptyResult;
            }

            log.info("Running {} agents on PR #{} — languages: {}",
                enabledAgents.size(), context.prNumber(), context.detectedLanguages());

            List<AgentResult> results = orchestrator.runAgents(enabledAgents, context);

            long failedCount = results.stream()
                .filter(r -> r.findings().isEmpty() && !r.rawReasoning().isBlank()
                    && !r.rawReasoning().startsWith("Agent failed"))
                .count();

            if (failedCount > 0) {
                log.warn("{} agent(s) returned no findings", failedCount);
            }

            ReviewResult result = synthesizer.synthesize(results, context);
            observation.highCardinalityKeyValue("langfuse.trace.output", result.markdownBody());
            return result;
        } catch (Exception e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
            org.akj.reviewer.config.ReviewContextHolder.clear();
        }
    }

    private List<ReviewAgent> filterEnabledAgents() {
        return allAgents.stream()
            .filter(this::isAgentEnabled)
            .toList();
    }

    private boolean isAgentEnabled(ReviewAgent agent) {
        return switch (agent.agentName()) {
            case "security" -> securityEnabled;
            case "performance" -> performanceEnabled;
            case "style" -> styleEnabled;
            case "test-coverage" -> testCoverageEnabled;
            default -> {
                log.warn("Unknown agent: {}, enabling by default", agent.agentName());
                yield true;
            }
        };
    }
}