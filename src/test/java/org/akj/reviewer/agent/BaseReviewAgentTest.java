package org.akj.reviewer.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

@ExtendWith(MockitoExtension.class)
class BaseReviewAgentTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private ObjectMapper objectMapper;
    private TestAgent agent;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        agent = new TestAgent(chatClient, objectMapper);
    }

    @Test
    void reviewReturnsFindingsWhenAiResponds() {
        String jsonResponse = """
            [
              {
                "severity": "HIGH",
                "category": "style",
                "filePath": "src/main/java/Test.java",
                "lineNumber": 42,
                "message": "Test finding",
                "suggestion": "Fix it"
              }
            ]
            """;

        mockFluentChain(jsonResponse);

        AgentContext ctx = new AgentContext("owner/repo", 1, "Test PR", "",
            "diff", List.of("Test.java"), false, Set.of("Java"));

        AgentResult result = agent.review(ctx);

        assertEquals("test-agent", result.agentName());
        assertEquals(1, result.findings().size());
        assertEquals("Test finding", result.findings().get(0).message());
        assertEquals(Severity.HIGH, result.findings().get(0).severity());
        assertEquals(Optional.of(42), result.findings().get(0).lineNumber());
    }

    @Test
    void reviewHandlesMarkdownWrappedJson() {
        String wrappedJson = """
            Here is the requested analysis:
            ```json
            [
              {
                "severity": "MEDIUM",
                "category": "security",
                "filePath": "src/main/java/App.java",
                "lineNumber": 10,
                "message": "Potential issue",
                "suggestion": "Fix"
              }
            ]
            ```
            Hope this helps!
            """;

        mockFluentChain(wrappedJson);

        AgentContext ctx = new AgentContext("owner/repo", 1, "Test PR", "",
            "diff", List.of("App.java"), false, Set.of("Java"));

        AgentResult result = agent.review(ctx);

        assertEquals("test-agent", result.agentName());
        assertEquals(1, result.findings().size());
        assertEquals("Potential issue", result.findings().get(0).message());
        assertEquals(Severity.MEDIUM, result.findings().get(0).severity());
        assertEquals(Optional.of(10), result.findings().get(0).lineNumber());
    }

    @Test
    void reviewReturnsEmptyWhenParsingFails() {
        mockFluentChain("not valid json");

        AgentContext ctx = new AgentContext("owner/repo", 1, "Test", "",
            "diff", List.of(), false, Set.of());

        AgentResult result = agent.review(ctx);

        assertEquals("test-agent", result.agentName());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void reviewReturnsEmptyOnException() {
        when(chatClient.prompt()).thenThrow(new RuntimeException("API error"));

        AgentContext ctx = new AgentContext("owner/repo", 1, "Test", "",
            "diff", List.of(), false, Set.of());

        AgentResult result = agent.review(ctx);

        assertEquals("test-agent", result.agentName());
        assertTrue(result.findings().isEmpty());
        assertTrue(result.rawReasoning().contains("API error"));
    }

    private void mockFluentChain(String responseText) {
        var promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.options(any())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(responseText);
    }

    static class TestAgent extends BaseReviewAgent {
        TestAgent(ChatClient chatClient, ObjectMapper mapper) {
            super(chatClient, mapper, "deepseek-chat", 1024);
        }

        @Override
        public String agentName() { return "test-agent"; }

        @Override
        protected String systemPromptFilename() { return "security-agent.txt"; }
    }
}