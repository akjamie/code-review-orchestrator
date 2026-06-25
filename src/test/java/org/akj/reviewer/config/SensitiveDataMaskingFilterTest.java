package org.akj.reviewer.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskingFilterTest {

    private SensitiveDataMaskingFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new SensitiveDataMaskingFilter();
        // Inject maskEnabled = true via reflection (no Spring context needed)
        var field = SensitiveDataMaskingFilter.class.getDeclaredField("maskEnabled");
        field.setAccessible(true);
        field.set(filter, true);
    }

    // ---------------------------------------------------------------
    // mask() unit tests — no Spring context needed
    // ---------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "GitHub PAT         | ghp_1234567890abcdef                                | ghp_***",
            "DeepSeek key       | sk-77d56d472d1e46e99aa075f4a0f92867                   | sk-***",
            "Bearer token       | Bearer eyJhbGciOiJSUzI1NiJ9.payload                   | Bearer ***",
            "Basic auth         | Basic dXNlcjpwYXNzd29yZA==                            | Basic ***",
            "Email              | user@example.com                                       | [EMAIL REDACTED]",
            "PEM key            | -----BEGIN RSA PRIVATE KEY-----\\nMIIEo...\\n-----END RSA PRIVATE KEY----- | [PRIVATE KEY REDACTED]",
    })
    void mask_shouldRedactSensitivePattern(String description, String input, String expectedSubstring) {
        String result = filter.mask(input.replace("\\n", "\n"));
        assertThat(result)
                .as("Expected '%s' to contain '%s' after masking: %s", result, expectedSubstring, description)
                .contains(expectedSubstring);
        // Must NOT contain the original sensitive value
        assertThat(result).doesNotContain(input.replace("\\n", "\n").substring(0, Math.min(16, input.length())).trim());
    }

    @Test
    void mask_shouldLeaveNonSensitiveTextUnchanged() {
        String plain = "This is a normal review comment with no secrets.";
        assertThat(filter.mask(plain)).isEqualTo(plain);
    }

    @Test
    void mask_shouldMaskInsideJsonSerializedPrompt() {
        String json = "[{\"role\":\"user\",\"content\":\"My token is ghp_1234567890abcdef - please help\"}]";
        String result = filter.mask(json);
        assertThat(result).contains("ghp_***");
        assertThat(result).doesNotContain("ghp_1234567890abcdef");
    }

    @Test
    void mask_shouldHandleMultiplePatternsInSameString() {
        String mixed = "Token: sk-abcdefghij123456 and email user@corp.com and key ghp_ABCDEF123456";
        String result = filter.mask(mixed);
        assertThat(result).contains("sk-***");
        assertThat(result).contains("[EMAIL REDACTED]");
        assertThat(result).contains("ghp_***");
    }

    @Test
    void mask_shouldReturnNullUnchanged() {
        assertThat(filter.mask(null)).isNull();
    }

    @Test
    void mask_shouldReturnBlankUnchanged() {
        assertThat(filter.mask("   ")).isEqualTo("   ");
    }

    // ---------------------------------------------------------------
    // map() integration tests — exercises the ObservationFilter path
    // ---------------------------------------------------------------

    @Test
    void map_shouldMaskGenAiPromptAttribute() {
        Observation.Context ctx = new Observation.Context();
        ctx.setName("spring.ai.chat.client");
        ctx.addHighCardinalityKeyValue(KeyValue.of("gen_ai.prompt",
                "System: use key sk-abcdef1234567890. User: review this code."));
        ctx.addHighCardinalityKeyValue(KeyValue.of("other.attr", "safe-value"));

        Observation.Context result = filter.map(ctx);

        String maskedPrompt = result.getHighCardinalityKeyValues().stream()
                .filter(kv -> "gen_ai.prompt".equals(kv.getKey()))
                .map(KeyValue::getValue)
                .findFirst().orElseThrow();

        assertThat(maskedPrompt).contains("sk-***");
        assertThat(maskedPrompt).doesNotContain("sk-abcdef1234567890");

        // Non-sensitive attribute must remain untouched
        String otherVal = result.getHighCardinalityKeyValues().stream()
                .filter(kv -> "other.attr".equals(kv.getKey()))
                .map(KeyValue::getValue)
                .findFirst().orElseThrow();
        assertThat(otherVal).isEqualTo("safe-value");
    }

    @Test
    void map_shouldSkipNonSpringAiObservations() {
        Observation.Context ctx = new Observation.Context();
        ctx.setName("http.server.requests");
        ctx.addHighCardinalityKeyValue(KeyValue.of("gen_ai.prompt",
                "sk-77d56d472d1e46e99aa075f4a0f92867"));

        Observation.Context result = filter.map(ctx);

        // HTTP observation — must NOT be modified
        String rawPrompt = result.getHighCardinalityKeyValues().stream()
                .filter(kv -> "gen_ai.prompt".equals(kv.getKey()))
                .map(KeyValue::getValue)
                .findFirst().orElseThrow();
        assertThat(rawPrompt).contains("sk-77d56d472d1e46e99aa075f4a0f92867");
    }

    @Test
    void map_shouldExtractPromptsFromChatModelObservationContext() {
        var prompt = org.mockito.Mockito.mock(org.springframework.ai.chat.prompt.Prompt.class);
        var message = org.mockito.Mockito.mock(org.springframework.ai.chat.messages.Message.class);
        org.mockito.Mockito.when(message.getText()).thenReturn("Test prompt user instruction");
        org.mockito.Mockito.when(message.getMessageType()).thenReturn(org.springframework.ai.chat.messages.MessageType.USER);
        org.mockito.Mockito.when(prompt.getInstructions()).thenReturn(java.util.List.of(message));

        // Instantiate real ChatModelObservationContext using its builder
        var context = org.springframework.ai.chat.observation.ChatModelObservationContext.builder()
                .prompt(prompt)
                .provider("deepseek")
                .build();
        context.setName("spring.ai.chat");
        // Note: response is NOT set here, simulating the streaming race condition where
        // observation.stop() fires before MessageAggregator.setResponse().

        // Run the filter map
        var result = filter.map(context);

        var promptKeyValue = result.getHighCardinalityKeyValues().stream()
                .filter(kv -> "gen_ai.prompt".equals(kv.getKey()))
                .map(io.micrometer.common.KeyValue::getValue)
                .findFirst().orElseThrow();
        assertThat(promptKeyValue).isEqualTo("[USER]\nTest prompt user instruction");

        var langfuseInput = result.getHighCardinalityKeyValues().stream()
                .filter(kv -> "langfuse.observation.input".equals(kv.getKey()))
                .map(io.micrometer.common.KeyValue::getValue)
                .findFirst().orElseThrow();
        assertThat(langfuseInput).isEqualTo("[USER]\nTest prompt user instruction");

        // Completion is NOT set by the filter (handled by LangfuseObservationHandler instead)
        var completionPresent = result.getHighCardinalityKeyValues().stream()
                .anyMatch(kv -> "langfuse.observation.output".equals(kv.getKey()));
        assertThat(completionPresent).isFalse();
    }

    @Test
    void map_shouldInjectTraceNameUserAndSessionIds() {
        Observation.Context ctx = new Observation.Context();
        ctx.setName("review-pipeline");

        ReviewContextHolder.set(new ReviewContext("test-user", "test-session"));
        try {
            var result = filter.map(ctx);

            String traceName = result.getHighCardinalityKeyValues().stream()
                    .filter(kv -> "langfuse.trace.name".equals(kv.getKey()))
                    .map(io.micrometer.common.KeyValue::getValue)
                    .findFirst().orElseThrow();
            assertThat(traceName).isEqualTo("review-pipeline");

            String userId = result.getHighCardinalityKeyValues().stream()
                    .filter(kv -> "langfuse.user.id".equals(kv.getKey()))
                    .map(io.micrometer.common.KeyValue::getValue)
                    .findFirst().orElseThrow();
            assertThat(userId).isEqualTo("test-user");

            String sessionId = result.getHighCardinalityKeyValues().stream()
                    .filter(kv -> "langfuse.session.id".equals(kv.getKey()))
                    .map(io.micrometer.common.KeyValue::getValue)
                    .findFirst().orElseThrow();
            assertThat(sessionId).isEqualTo("test-session");
        } finally {
            ReviewContextHolder.clear();
        }
    }

    @Test
    void map_shouldMapAndMaskToolArgumentsAndResults() {
        Observation.Context ctx = new Observation.Context();
        ctx.setName("spring.ai.tool.call");
        ctx.addHighCardinalityKeyValue(io.micrometer.common.KeyValue.of("spring.ai.tool.call.arguments",
                "{\"repo\":\"claw-agent\",\"token\":\"ghp_secretToken123\"}"));
        ctx.addHighCardinalityKeyValue(io.micrometer.common.KeyValue.of("spring.ai.tool.call.result",
                "Error using sk-abcdef1234567890 API key"));

        var result = filter.map(ctx);

        String input = result.getHighCardinalityKeyValues().stream()
                .filter(kv -> "langfuse.observation.input".equals(kv.getKey()))
                .map(io.micrometer.common.KeyValue::getValue)
                .findFirst().orElseThrow();
        assertThat(input).contains("ghp_***");
        assertThat(input).doesNotContain("ghp_secretToken123");

        String output = result.getHighCardinalityKeyValues().stream()
                .filter(kv -> "langfuse.observation.output".equals(kv.getKey()))
                .map(io.micrometer.common.KeyValue::getValue)
                .findFirst().orElseThrow();
        assertThat(output).contains("sk-***");
        assertThat(output).doesNotContain("sk-abcdef1234567890");
    }

    @Test
    void map_shouldMaskGenAiObservations() {
        Observation.Context ctx = new Observation.Context();
        ctx.setName("gen_ai.client.operation");
        ctx.addHighCardinalityKeyValue(KeyValue.of("gen_ai.prompt", "Secret key: sk-abcdef1234567890"));

        Observation.Context result = filter.map(ctx);

        String maskedPrompt = result.getHighCardinalityKeyValues().stream()
                .filter(kv -> "gen_ai.prompt".equals(kv.getKey()))
                .map(KeyValue::getValue)
                .findFirst().orElseThrow();

        assertThat(maskedPrompt).contains("sk-***");
        assertThat(maskedPrompt).doesNotContain("sk-abcdef1234567890");
    }

    @Test
    void map_shouldExtractCompletionsWhenResponseIsPresent() {
        var prompt = org.mockito.Mockito.mock(org.springframework.ai.chat.prompt.Prompt.class);
        var message = org.mockito.Mockito.mock(org.springframework.ai.chat.messages.Message.class);
        org.mockito.Mockito.when(message.getText()).thenReturn("Test prompt");
        org.mockito.Mockito.when(message.getMessageType()).thenReturn(org.springframework.ai.chat.messages.MessageType.USER);
        org.mockito.Mockito.when(prompt.getInstructions()).thenReturn(java.util.List.of(message));

        var response = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatResponse.class);
        var generation = org.mockito.Mockito.mock(org.springframework.ai.chat.model.Generation.class);
        var assistantMessage = org.mockito.Mockito.mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        org.mockito.Mockito.when(assistantMessage.getText()).thenReturn("Test completion output");
        org.mockito.Mockito.when(assistantMessage.getMessageType()).thenReturn(org.springframework.ai.chat.messages.MessageType.ASSISTANT);
        org.mockito.Mockito.when(generation.getOutput()).thenReturn(assistantMessage);
        org.mockito.Mockito.when(response.getResults()).thenReturn(java.util.List.of(generation));

        var context = org.springframework.ai.chat.observation.ChatModelObservationContext.builder()
                .prompt(prompt)
                .provider("deepseek")
                .build();
        context.setName("spring.ai.chat");
        context.setResponse(response);

        var result = filter.map(context);

        var completionKeyValue = result.getHighCardinalityKeyValues().stream()
                .filter(kv -> "gen_ai.completion".equals(kv.getKey()))
                .map(io.micrometer.common.KeyValue::getValue)
                .findFirst().orElseThrow();
        assertThat(completionKeyValue).isEqualTo("[ASSISTANT]\nTest completion output");

        var langfuseOutput = result.getHighCardinalityKeyValues().stream()
                .filter(kv -> "langfuse.observation.output".equals(kv.getKey()))
                .map(io.micrometer.common.KeyValue::getValue)
                .findFirst().orElseThrow();
        assertThat(langfuseOutput).isEqualTo("[ASSISTANT]\nTest completion output");
    }
}

