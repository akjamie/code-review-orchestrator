package org.akj.reviewer.agent;

import java.util.List;

public record AgentResult(
    String agentName,
    List<Finding> findings,
    String rawReasoning
) {
    public static AgentResult empty(String agentName, Throwable cause) {
        return new AgentResult(agentName, List.of(),
            "Agent failed: " + cause.getMessage());
    }
}