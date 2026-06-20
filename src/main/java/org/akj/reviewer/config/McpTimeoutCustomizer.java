package org.akj.reviewer.config;

import java.time.Duration;

import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.client.McpClient;

/**
 * Ensures MCP client request timeout is applied regardless of YAML parsing.
 */
@Component
public class McpTimeoutCustomizer implements McpClientCustomizer<McpClient.SyncSpec> {

    private final Duration timeout;

    public McpTimeoutCustomizer(
            @Value("${spring.ai.mcp.client.request-timeout:PT45S}") Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public void customize(String name, McpClient.SyncSpec spec) {
        spec.requestTimeout(timeout);
        spec.initializationTimeout(timeout);
    }
}
