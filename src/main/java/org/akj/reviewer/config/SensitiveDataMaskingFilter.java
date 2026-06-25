package org.akj.reviewer.config;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;

/**
 * Micrometer {@link ObservationFilter} that redacts sensitive tokens and PII
 * from
 * Spring AI observation attributes ({@code gen_ai.prompt},
 * {@code gen_ai.completion},
 * {@code gen_ai.prompt.*}, {@code gen_ai.completion.*}) before they are
 * exported to
 * the OTLP/Langfuse backend.
 *
 * <p>
 * The filter only activates for observations whose name starts with
 * {@code spring.ai.}
 * to avoid unintentionally masking other spans (HTTP, DB, etc.).
 *
 * <p>
 * Masking rules (partial-mask style for easier correlation):
 * <ul>
 * <li>GitHub PATs ({@code ghp_…}) → {@code ghp_***}</li>
 * <li>DeepSeek / generic AI keys ({@code sk-…}) → {@code sk-***}</li>
 * <li>Bearer tokens → {@code Bearer ***}</li>
 * <li>Email addresses → {@code [EMAIL REDACTED]}</li>
 * <li>PEM private keys → {@code [PRIVATE KEY REDACTED]}</li>
 * </ul>
 */
@Component
public class SensitiveDataMaskingFilter implements ObservationFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SensitiveDataMaskingFilter.class);

    // Observation attribute keys that may carry raw prompt/completion text.
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "gen_ai.prompt",
            "gen_ai.completion",
            "spring.ai.tool.call.arguments",
            "spring.ai.tool.call.result",
            "langfuse.observation.input",
            "langfuse.observation.output",
            "langfuse.trace.input",
            "langfuse.trace.output");

    // Prefix filter — only mask observations produced by Spring AI
    private static final String SPRING_AI_PREFIX = "spring.ai.";
    private static final String GEN_AI_PREFIX = "gen_ai.";

    // --- masking patterns (applied in order) ---

    /** GitHub Personal Access Tokens: ghp_<alphanumeric> */
    private static final Pattern GITHUB_PAT = Pattern.compile("ghp_[A-Za-z0-9]+");

    /** DeepSeek / generic AI secret keys: sk-<hex/alphanum 16+> */
    private static final Pattern AI_SECRET_KEY = Pattern.compile("sk-[A-Za-z0-9]{10,}");

    /** HTTP Bearer tokens (JWT, OAuth) */
    private static final Pattern BEARER_TOKEN = Pattern.compile("Bearer\\s+[A-Za-z0-9._\\-/+]{10,}");

    /** Basic auth credentials (base64 encoded): Basic <base64> */
    private static final Pattern BASIC_AUTH = Pattern.compile("Basic\\s+[A-Za-z0-9+/=]{10,}");

    /** RFC 5322-compatible email address */
    private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    /** PEM private key blocks */
    private static final Pattern PEM_PRIVATE_KEY = Pattern
            .compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");

    @Value("${review.observability.mask-sensitive-data:true}")
    private boolean maskEnabled;

    @Override
    public Observation.Context map(Observation.Context context) {
        String name = context.getName();
        if (name == null || (!name.startsWith(SPRING_AI_PREFIX) && !name.startsWith(GEN_AI_PREFIX)
                && !name.equals("review-pipeline"))) {
            return context;
        }

        // 1. If it's a ChatModel observation, extract prompts (and completions if
        // response is present).
        // Note: for streaming calls (DeepSeekChatModel.internalStream),
        // context.getResponse()
        // is null when map() runs because observation.stop() is called via doFinally
        // BEFORE
        // MessageAggregator calls setResponse(). For non-streaming calls, it is present
        // here.
        if (context instanceof ChatModelObservationContext chatModelObservationContext) {
            var prompts = processPrompts(chatModelObservationContext);
            if (!prompts.isEmpty()) {
                String promptText = String.join("\n", prompts);
                setHighCardinalityKeyValue(context, "gen_ai.prompt", promptText);
                setHighCardinalityKeyValue(context, "langfuse.observation.input", promptText);
            }
            var response = chatModelObservationContext.getResponse();
            if (response != null) {
                var completions = processCompletions(chatModelObservationContext);
                if (!completions.isEmpty()) {
                    String completionText = String.join("\n", completions);
                    setHighCardinalityKeyValue(context, "gen_ai.completion", completionText);
                    setHighCardinalityKeyValue(context, "langfuse.observation.output", completionText);
                }
            }
        }

        // 2. Inject user, session, and trace name from current context if available
        ReviewContext reviewCtx = ReviewContextHolder.get();
        if (reviewCtx != null) {
            setHighCardinalityKeyValue(context, "langfuse.user.id", reviewCtx.userId());
            setHighCardinalityKeyValue(context, "langfuse.session.id", reviewCtx.sessionId());
            setHighCardinalityKeyValue(context, "langfuse.trace.name", "review-pipeline");
        }

        // 3. Map tool arguments and results to langfuse input/output
        String toolArgs = getHighCardinalityKeyValue(context, "spring.ai.tool.call.arguments");
        if (toolArgs != null) {
            setHighCardinalityKeyValue(context, "langfuse.observation.input", toolArgs);
        }
        String toolResult = getHighCardinalityKeyValue(context, "spring.ai.tool.call.result");
        if (toolResult != null) {
            setHighCardinalityKeyValue(context, "langfuse.observation.output", toolResult);
        }

        if (!maskEnabled || (!name.startsWith(SPRING_AI_PREFIX) && !name.startsWith(GEN_AI_PREFIX))) {
            return context;
        }

        List<KeyValue> updated = context.getHighCardinalityKeyValues().stream()
                .map(kv -> {
                    if (isSensitiveKey(kv.getKey())) {
                        String masked = mask(kv.getValue());
                        if (!masked.equals(kv.getValue())) {
                            log.trace("Masked sensitive data in observation attribute '{}'", kv.getKey());
                        }
                        return KeyValue.of(kv.getKey(), masked);
                    }
                    return kv;
                })
                .toList();

        // Replace all high-cardinality key-values with the masked set.
        // Snapshot keys first to avoid concurrent-modification if
        // getHighCardinalityKeyValues()
        // returns a live collection view that is modified by
        // removeHighCardinalityKeyValues().
        var keysToRemove = context.getHighCardinalityKeyValues().stream()
                .map(KeyValue::getKey)
                .toList();
        keysToRemove.forEach(context::removeHighCardinalityKeyValues);
        updated.forEach(context::addHighCardinalityKeyValue);

        return context;
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private List<String> processPrompts(ChatModelObservationContext chatModelObservationContext) {
        var request = chatModelObservationContext.getRequest();
        if (request == null)
            return List.of();
        var instructions = request.getInstructions();
        if (CollectionUtils.isEmpty(instructions)) {
            return List.of();
        }
        return instructions.stream()
                .map(this::formatMessage)
                .filter(text -> !text.isBlank())
                .toList();
    }

    private List<String> processCompletions(ChatModelObservationContext chatModelObservationContext) {
        var response = chatModelObservationContext.getResponse();
        if (response == null || response.getResults() == null) {
            return List.of();
        }
        return response.getResults().stream()
                .filter(generation -> generation.getOutput() != null)
                .map(generation -> formatMessage(generation.getOutput()))
                .filter(text -> !text.isBlank())
                .toList();
    }

    private String formatMessage(org.springframework.ai.chat.messages.Message message) {
        if (message == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage toolMessage) {
            if (toolMessage.getResponses() != null) {
                for (var resp : toolMessage.getResponses()) {
                    sb.append("[TOOL: ").append(resp.name()).append(" (id: ").append(resp.id()).append(")]\n")
                            .append(resp.responseData()).append("\n");
                }
            }
        } else {
            String role = message.getMessageType() != null ? message.getMessageType().name() : "UNKNOWN";
            String text = message.getText();
            if (text != null && !text.isBlank()) {
                sb.append("[").append(role).append("]\n").append(text).append("\n");
            }
            if (message instanceof org.springframework.ai.chat.messages.AssistantMessage assistantMessage) {
                if (assistantMessage.getToolCalls() != null && !assistantMessage.getToolCalls().isEmpty()) {
                    for (var tc : assistantMessage.getToolCalls()) {
                        sb.append("[CALL TOOL: ").append(tc.name()).append(" (id: ").append(tc.id()).append(")]\n")
                                .append(tc.arguments()).append("\n");
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    private String getHighCardinalityKeyValue(Observation.Context context, String key) {
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

    private boolean isSensitiveKey(String key) {
        if (key == null)
            return false;
        // Exact match or prefix match (e.g. gen_ai.prompt.0.content)
        return SENSITIVE_KEYS.contains(key) || key.startsWith("gen_ai.prompt.") || key.startsWith("gen_ai.completion.");
    }

    /**
     * Applies all masking rules to {@code value} and returns the sanitised string.
     */
    String mask(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String result = value;
        result = PEM_PRIVATE_KEY.matcher(result).replaceAll("[PRIVATE KEY REDACTED]");
        result = GITHUB_PAT.matcher(result).replaceAll("ghp_***");
        result = AI_SECRET_KEY.matcher(result).replaceAll("sk-***");
        result = BEARER_TOKEN.matcher(result).replaceAll("Bearer ***");
        result = BASIC_AUTH.matcher(result).replaceAll("Basic ***");
        result = EMAIL.matcher(result).replaceAll("[EMAIL REDACTED]");
        return result;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
