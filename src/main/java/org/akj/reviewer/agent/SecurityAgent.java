package org.akj.reviewer.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class SecurityAgent extends BaseReviewAgent {

    public SecurityAgent(ChatClient chatClient, ObjectMapper objectMapper,
                         @Qualifier("review.model") String model,
                         @Qualifier("review.agents.max-tokens") int maxTokens) {
        super(chatClient, objectMapper, model, maxTokens);
    }

    @Override
    public String agentName() {
        return "security";
    }

    @Override
    protected String systemPromptFilename() {
        return "security-agent.txt";
    }
}