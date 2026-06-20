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
}
