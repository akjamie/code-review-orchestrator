package org.akj.reviewer.config;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.modelcontextprotocol.client.McpSyncClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Wires up MCP tool callbacks from all registered MCP clients:
 *  - GitHub MCP server (launched locally via npx stdio)
 *  - Context7 MCP server (remote SSE at https://mcp.context7.com/mcp)
 *
 * Spring AI auto-configures {@link McpSyncClient} beans from application.yml.
 * This config aggregates their tools into a single {@link ToolCallbackProvider}
 * bean that the {@link org.akj.reviewer.agent.McpReviewAgent} injects into its
 * {@link org.springframework.ai.chat.client.ChatClient}.
 *
 * Only active when {@code review.mcp-agent.enabled=true} (the default).
 */
@Configuration
@ConditionalOnProperty(name = "review.mcp-agent.enabled", havingValue = "true", matchIfMissing = true)
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    /**
     * Combines tool callbacks from all available MCP sync clients.
     * Spring AI auto-discovers {@code McpSyncClient} beans registered by the
     * {@code spring-ai-starter-mcp-client} auto-configuration.
     */
    @Bean
    public ToolCallbackProvider mcpToolCallbackProvider(List<McpSyncClient> mcpClients) {
        log.info("Registering MCP tools from {} client(s): {}",
                mcpClients.size(),
                mcpClients.stream().map(c -> c.getClientInfo().name()).toList());
        return new SyncMcpToolCallbackProvider(mcpClients);
    }
}
