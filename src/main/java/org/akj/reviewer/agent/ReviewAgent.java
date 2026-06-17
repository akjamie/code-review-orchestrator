package org.akj.reviewer.agent;

public interface ReviewAgent {
    String agentName();
    AgentResult review(AgentContext ctx);
}