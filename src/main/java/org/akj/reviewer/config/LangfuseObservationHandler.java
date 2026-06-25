package org.akj.reviewer.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Micrometer {@link ObservationHandler} that runs <em>after</em> all Spring AI
 * observation handlers (including {@code ChatModelCompletionObservationHandler})
 * and ensures that the Langfuse output attribute is populated for every LLM call,
 * including calls whose {@code finish_reason} is {@code TOOL_CALLS}.
 *
 * <p><strong>Why this handler is needed:</strong><br>
 * Spring AI's {@code ChatModelCompletionObservationHandler} only <em>logs</em>
 * the completion to SLF4J — it does <em>not</em> write {@code gen_ai.completion}
 * as a span attribute. Our {@link SensitiveDataMaskingFilter} (an
 * {@code ObservationFilter}) handles the common case, but runs <em>before</em>
 * handlers and may miss the response in certain edge cases (streaming,
 * tool-call-only responses where text is empty).
 *
 * <p>This handler therefore acts as the last-resort fallback:
 * <ol>
 *   <li>If {@code langfuse.observation.output} is already populated (by the filter),
 *       nothing more is done.</li>
 *   <li>Otherwise it reads {@code gen_ai.completion} (set by our filter for the
 *       common case) and copies it.</li>
 *   <li>If that is also absent, it directly reads the response from
 *       {@link ChatModelObservationContext} and formats it — capturing both
 *       text content <em>and</em> tool-call payloads.</li>
 * </ol>
 */
@Component
public class LangfuseObservationHandler implements ObservationHandler<Observation.Context>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(LangfuseObservationHandler.class);

    private static final String SPRING_AI_PREFIX = "spring.ai.";
    private static final String GEN_AI_PREFIX = "gen_ai.";

    @Override
    public boolean supportsContext(Observation.Context context) {
        String name = context.getName();
        return name != null && (name.startsWith(SPRING_AI_PREFIX) || name.startsWith(GEN_AI_PREFIX));
    }

    @Override
    public void onStop(Observation.Context context) {
        // --- INPUT ---
        // Mirror gen_ai.prompt -> langfuse.observation.input (if not already set by filter)
        String existingInput = getHighCardinalityValue(context, "langfuse.observation.input");
        if (existingInput == null || existingInput.isBlank()) {
            String prompt = getHighCardinalityValue(context, "gen_ai.prompt");
            if (prompt != null && !prompt.isBlank()) {
                log.trace("Copying gen_ai.prompt to langfuse.observation.input");
                setHighCardinalityKeyValue(context, "langfuse.observation.input", prompt);
            }
        }

        // --- OUTPUT ---
        // 1. Check if langfuse.observation.output is already set (by SensitiveDataMaskingFilter)
        String existingOutput = getHighCardinalityValue(context, "langfuse.observation.output");
        if (existingOutput != null && !existingOutput.isBlank()) {
            log.trace("langfuse.observation.output already set by filter, skipping");
            return;
        }

        // 2. Try to copy gen_ai.completion attribute (set by filter for non-streaming calls)
        String completion = getHighCardinalityValue(context, "gen_ai.completion");
        if (completion != null && !completion.isBlank()) {
            log.trace("Copying gen_ai.completion to langfuse.observation.output");
            setHighCardinalityKeyValue(context, "langfuse.observation.output", completion);
            return;
        }

        // 3. Fallback: read directly from ChatModelObservationContext (handles tool-call responses
        //    and any case where the filter did not capture the output)
        if (context instanceof ChatModelObservationContext chatCtx) {
            String formatted = formatResponse(chatCtx);
            if (formatted != null && !formatted.isBlank()) {
                log.trace("Setting langfuse.observation.output from ChatModelObservationContext response");
                setHighCardinalityKeyValue(context, "langfuse.observation.output", formatted);
                setHighCardinalityKeyValue(context, "gen_ai.completion", formatted);
            } else {
                log.trace("ChatModelObservationContext response is null or blank — output will be empty");
            }
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /**
     * Formats the LLM response (text + tool calls) from a
     * {@link ChatModelObservationContext} into a human-readable string.
     * Returns {@code null} if no response is available.
     */
    private String formatResponse(ChatModelObservationContext ctx) {
        var response = ctx.getResponse();
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return null;
        }
        var sb = new StringBuilder();
        for (var generation : response.getResults()) {
            var output = generation.getOutput();
            if (output == null) continue;

            // Text content
            String text = output.getText();
            if (StringUtils.hasText(text)) {
                sb.append(text).append("\n");
            }

            // Tool calls (AssistantMessage when finish_reason=TOOL_CALLS)
            if (output instanceof org.springframework.ai.chat.messages.AssistantMessage assistantMsg) {
                var toolCalls = assistantMsg.getToolCalls();
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    for (var tc : toolCalls) {
                        sb.append("[CALL TOOL: ").append(tc.name())
                          .append(" (id: ").append(tc.id()).append(")]\n")
                          .append(tc.arguments()).append("\n");
                    }
                }
            }
        }
        String result = sb.toString().trim();
        return result.isBlank() ? null : result;
    }

    private String getHighCardinalityValue(Observation.Context context, String key) {
        return context.getHighCardinalityKeyValues().stream()
                .filter(kv -> key.equals(kv.getKey()))
                .map(KeyValue::getValue)
                .findFirst()
                .orElse(null);
    }

    private void setHighCardinalityKeyValue(Observation.Context context, String key, String value) {
        if (value != null) {
            context.removeHighCardinalityKeyValues(key);
            context.addHighCardinalityKeyValue(KeyValue.of(key, value));
        }
    }

    /**
     * Run last among all ObservationHandlers so that Spring AI's built-in handlers
     * (e.g. {@code ChatModelCompletionObservationHandler}) have already run
     * before we try to read/copy the completion.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
