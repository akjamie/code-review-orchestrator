package org.akj.reviewer.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new Jdk8Module());
        return mapper;
    }

    @Value("${spring.ai.deepseek.chat.model}")
    private String model;

    @Value("${review.agents.max-tokens:4096}")
    private int agentMaxTokens;

    @Value("${review.agents.timeout-seconds:120}")
    private int agentTimeoutSeconds;

    @Value("${review.synthesizer.max-tokens:8192}")
    private int synthesizerMaxTokens;

    @Bean(name = "review.model")
    public String reviewModel() {
        return model;
    }

    @Bean(name = "review.agents.max-tokens")
    public int reviewAgentMaxTokens() {
        return agentMaxTokens;
    }

    @Bean(name = "review.synthesizer.max-tokens")
    public int reviewSynthesizerMaxTokens() {
        return synthesizerMaxTokens;
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
            .defaultAdvisors(new org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor())
            .build();
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    public int getAgentTimeoutSeconds() {
        return agentTimeoutSeconds;
    }
}