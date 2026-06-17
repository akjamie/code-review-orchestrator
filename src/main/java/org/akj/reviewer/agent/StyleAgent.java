package org.akj.reviewer.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class StyleAgent extends BaseReviewAgent {

    public StyleAgent(ChatClient chatClient, ObjectMapper objectMapper,
                      @Qualifier("review.model") String model,
                      @Qualifier("review.agents.max-tokens") int maxTokens) {
        super(chatClient, objectMapper, model, maxTokens);
    }

    @Override
    public String agentName() {
        return "style";
    }

    @Override
    protected String systemPromptFilename() {
        return "style-agent.txt";
    }
}