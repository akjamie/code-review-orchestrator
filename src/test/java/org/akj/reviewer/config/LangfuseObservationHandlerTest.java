package org.akj.reviewer.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LangfuseObservationHandler}.
 *
 * <p>Key scenarios verified:
 * <ul>
 *   <li>gen_ai.completion is copied to langfuse.observation.output when not already set</li>
 *   <li>gen_ai.prompt is copied to langfuse.observation.input when not already set</li>
 *   <li>Existing langfuse.observation.input/output values are NOT overwritten</li>
 *   <li>Non-spring.ai / non-gen_ai observations are skipped</li>
 * </ul>
 */
class LangfuseObservationHandlerTest {

    private LangfuseObservationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new LangfuseObservationHandler();
    }

    private Observation.Context contextWithName(String name) {
        Observation.Context ctx = new Observation.Context();
        ctx.setName(name);
        return ctx;
    }

    @Test
    void onStop_shouldCopyPromptToLangfuseInput() {
        var ctx = contextWithName("gen_ai.client.operation");
        ctx.addHighCardinalityKeyValue(KeyValue.of("gen_ai.prompt", "hello world"));

        handler.onStop(ctx);

        String input = ctx.getHighCardinalityKeyValues().stream()
                .filter(kv -> "langfuse.observation.input".equals(kv.getKey()))
                .map(KeyValue::getValue).findFirst().orElse(null);
        assertThat(input).isEqualTo("hello world");
    }

    @Test
    void onStop_shouldCopyCompletionToLangfuseOutput() {
        var ctx = contextWithName("gen_ai.client.operation");
        ctx.addHighCardinalityKeyValue(KeyValue.of("gen_ai.completion", "review complete"));

        handler.onStop(ctx);

        String output = ctx.getHighCardinalityKeyValues().stream()
                .filter(kv -> "langfuse.observation.output".equals(kv.getKey()))
                .map(KeyValue::getValue).findFirst().orElse(null);
        assertThat(output).isEqualTo("review complete");
    }

    @Test
    void onStop_shouldNotOverwriteExistingLangfuseInput() {
        var ctx = contextWithName("spring.ai.chat");
        ctx.addHighCardinalityKeyValue(KeyValue.of("gen_ai.prompt", "new prompt"));
        ctx.addHighCardinalityKeyValue(KeyValue.of("langfuse.observation.input", "already set"));

        handler.onStop(ctx);

        // The existing value should remain unchanged
        long count = ctx.getHighCardinalityKeyValues().stream()
                .filter(kv -> "langfuse.observation.input".equals(kv.getKey()))
                .count();
        assertThat(count).isEqualTo(1);

        String input = ctx.getHighCardinalityKeyValues().stream()
                .filter(kv -> "langfuse.observation.input".equals(kv.getKey()))
                .map(KeyValue::getValue).findFirst().orElse(null);
        assertThat(input).isEqualTo("already set");
    }

    @Test
    void onStop_shouldNotOverwriteExistingLangfuseOutput() {
        var ctx = contextWithName("spring.ai.chat");
        ctx.addHighCardinalityKeyValue(KeyValue.of("gen_ai.completion", "new completion"));
        ctx.addHighCardinalityKeyValue(KeyValue.of("langfuse.observation.output", "already set"));

        handler.onStop(ctx);

        String output = ctx.getHighCardinalityKeyValues().stream()
                .filter(kv -> "langfuse.observation.output".equals(kv.getKey()))
                .map(KeyValue::getValue).findFirst().orElse(null);
        assertThat(output).isEqualTo("already set");
    }

    @Test
    void supportsContext_shouldAcceptSpringAiAndGenAiObservations() {
        var genAiCtx = contextWithName("gen_ai.client.operation");
        var springAiCtx = contextWithName("spring.ai.chat.client");
        var httpCtx = contextWithName("http.server.requests");

        assertThat(handler.supportsContext(genAiCtx)).isTrue();
        assertThat(handler.supportsContext(springAiCtx)).isTrue();
        assertThat(handler.supportsContext(httpCtx)).isFalse();
    }

    @Test
    void onStop_shouldDoNothingWhenNoGenAiAttributesPresent() {
        var ctx = contextWithName("spring.ai.chat");
        // No gen_ai.prompt or gen_ai.completion set

        handler.onStop(ctx);

        // Should add no new attributes
        assertThat(ctx.getHighCardinalityKeyValues()).isEmpty();
    }
}
