package org.akj.reviewer.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class TestCoverageAgent extends BaseReviewAgent {

    public TestCoverageAgent(ChatClient chatClient, ObjectMapper objectMapper,
                             @Qualifier("review.model") String model,
                             @Qualifier("review.agents.max-tokens") int maxTokens) {
        super(chatClient, objectMapper, model, maxTokens);
    }

    @Override
    public String agentName() {
        return "test-coverage";
    }

    @Override
    protected String systemPromptFilename() {
        return "testcoverage-agent.txt";
    }
}