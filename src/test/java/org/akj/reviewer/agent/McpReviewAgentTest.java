package org.akj.reviewer.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

@ExtendWith(MockitoExtension.class)
class McpReviewAgentTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClientRequestSpec chatClientRequestSpec;

    @Mock
    private CallResponseSpec callResponseSpec;

    @Mock
    private ToolCallbackProvider mcpTools;

    @Mock
    private org.akj.reviewer.github.GitHubDiffFetcher diffFetcher;

    @Test
    void testReviewPrSuccess() throws Exception {
        // Mock ToolCallbackProvider
        when(mcpTools.getToolCallbacks()).thenReturn(new ToolCallback[0]);

        // Mock GitHubDiffFetcher
        org.akj.reviewer.github.GitHubDiffFetcher.PrDetails prDetails = 
            new org.akj.reviewer.github.GitHubDiffFetcher.PrDetails("Title", "Body", "author");
        when(diffFetcher.fetchPrDetails(anyString(), anyInt())).thenReturn(prDetails);

        // Mock ChatClient.Builder fluent chain
        when(chatClientBuilder.defaultSystem(anyString())).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(chatClient);

        // Mock ChatClient fluent chain
        when(chatClient.prompt()).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.user(anyString())).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.tools(any(Object[].class))).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.options(any())).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("Review posted successfully");

        McpReviewAgent agent = new McpReviewAgent(
            chatClientBuilder, 
            mcpTools, 
            diffFetcher, 
            io.micrometer.observation.ObservationRegistry.NOOP, 
            "deepseek-chat", 
            8192
        );
        
        // Act & Assert (should complete without throwing exceptions)
        assertDoesNotThrow(() -> agent.reviewPr("owner/repo", 42));

        // Verify that build was called and the prompt chain was executed
        verify(chatClientBuilder).build();
        verify(chatClient).prompt();
        verify(chatClientRequestSpec).user(contains("pull request #42"));
        verify(callResponseSpec).content();
    }
}
