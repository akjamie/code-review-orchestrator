package org.akj.reviewer.orchestrator;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.akj.reviewer.agent.AgentContext;
import org.akj.reviewer.agent.AgentResult;
import org.akj.reviewer.agent.ReviewAgent;
import org.akj.reviewer.config.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ReviewOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReviewOrchestrator.class);

    private final ExecutorService executor;
    private final int timeoutSeconds;
    private final io.micrometer.observation.ObservationRegistry observationRegistry;

    public ReviewOrchestrator(ExecutorService virtualThreadExecutor,
                              AiConfig aiConfig,
                              io.micrometer.observation.ObservationRegistry observationRegistry) {
        this.executor = virtualThreadExecutor;
        this.timeoutSeconds = aiConfig.getAgentTimeoutSeconds();
        this.observationRegistry = observationRegistry;
    }

    public List<AgentResult> runAgents(List<ReviewAgent> agents, AgentContext context) {
        final io.micrometer.observation.Observation parent = observationRegistry.getCurrentObservation();
        List<CompletableFuture<AgentResult>> futures = agents.stream()
            .map(agent -> CompletableFuture
                .supplyAsync(() -> {
                    if (parent != null) {
                        try (io.micrometer.observation.Observation.Scope scope = parent.openScope()) {
                            return agent.review(context);
                        }
                    }
                    return agent.review(context);
                }, executor)
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("Agent {} failed: {}", agent.agentName(), ex.getMessage());
                    return AgentResult.empty(agent.agentName(), ex);
                }))
            .toList();

        return futures.stream()
            .map(CompletableFuture::join)
            .toList();
    }
}