package org.akj.reviewer.orchestrator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.akj.reviewer.agent.AgentContext;
import org.akj.reviewer.agent.AgentResult;
import org.akj.reviewer.agent.Finding;
import org.akj.reviewer.agent.ReviewAgent;
import org.akj.reviewer.agent.Severity;
import org.akj.reviewer.config.AiConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewOrchestratorTest {

    @Mock
    private AiConfig aiConfig;

    private ExecutorService executor;
    private ReviewOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        when(aiConfig.getAgentTimeoutSeconds()).thenReturn(5);
        orchestrator = new ReviewOrchestrator(executor, aiConfig, io.micrometer.observation.ObservationRegistry.NOOP);
    }

    @Test
    void runsAllAgentsAndCollectsResults() {
        ReviewAgent agent1 = mock(ReviewAgent.class);
        ReviewAgent agent2 = mock(ReviewAgent.class);

        AgentContext ctx = new AgentContext("owner/repo", 1, "PR", "", "author",
            "diff", List.of("File.java"), false, Set.of("Java"));

        AgentResult result1 = new AgentResult("agent1",
            List.of(new Finding(Severity.HIGH, "style", "File.java", Optional.of(1),
                "Issue 1", "Fix 1")), "ok");
        AgentResult result2 = new AgentResult("agent2", List.of(), "nothing");

        when(agent1.agentName()).thenReturn("agent1");
        when(agent2.agentName()).thenReturn("agent2");
        when(agent1.review(ctx)).thenReturn(result1);
        when(agent2.review(ctx)).thenReturn(result2);

        List<AgentResult> results = orchestrator.runAgents(List.of(agent1, agent2), ctx);

        assertEquals(2, results.size());
        assertEquals("agent1", results.get(0).agentName());
        assertEquals(1, results.get(0).findings().size());
        assertEquals("agent2", results.get(1).agentName());
        assertTrue(results.get(1).findings().isEmpty());
    }

    @Test
    void oneAgentFailureDoesNotAbortOtherAgents() {
        ReviewAgent goodAgent = mock(ReviewAgent.class);
        ReviewAgent badAgent = mock(ReviewAgent.class);

        AgentContext ctx = new AgentContext("owner/repo", 1, "PR", "", "author",
            "diff", List.of(), false, Set.of());

        AgentResult goodResult = new AgentResult("good",
            List.of(new Finding(Severity.INFO, "style", "File.java", Optional.empty(),
                "Nice", "Keep it")), "ok");

        when(goodAgent.agentName()).thenReturn("good");
        when(badAgent.agentName()).thenReturn("bad");
        when(goodAgent.review(ctx)).thenReturn(goodResult);
        when(badAgent.review(ctx)).thenThrow(new RuntimeException("Agent crashed"));

        List<AgentResult> results = orchestrator.runAgents(List.of(goodAgent, badAgent), ctx);

        assertEquals(2, results.size());
        assertEquals("good", results.get(0).agentName());
        assertEquals(1, results.get(0).findings().size());
        assertEquals("bad", results.get(1).agentName());
        assertTrue(results.get(1).findings().isEmpty());
        assertTrue(results.get(1).rawReasoning().contains("Agent crashed"));
    }
}