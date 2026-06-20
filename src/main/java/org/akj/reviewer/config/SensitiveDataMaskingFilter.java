package org.akj.reviewer.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Micrometer {@link ObservationFilter} that redacts sensitive tokens and PII from
 * Spring AI observation attributes ({@code gen_ai.prompt}, {@code gen_ai.completion},
 * {@code gen_ai.prompt.*}, {@code gen_ai.completion.*}) before they are exported to
 * the OTLP/Langfuse backend.
 *
 * <p>The filter only activates for observations whose name starts with {@code spring.ai.}
 * to avoid unintentionally masking other spans (HTTP, DB, etc.).
 *
 * <p>Masking rules (partial-mask style for easier correlation):
 * <ul>
 *   <li>GitHub PATs ({@code ghp_…})  → {@code ghp_***}</li>
 *   <li>DeepSeek / generic AI keys ({@code sk-…}) → {@code sk-***}</li>
 *   <li>Bearer tokens → {@code Bearer ***}</li>
 *   <li>Email addresses → {@code [EMAIL REDACTED]}</li>
 *   <li>PEM private keys → {@code [PRIVATE KEY REDACTED]}</li>
 * </ul>
 */
@Component
public class SensitiveDataMaskingFilter implements ObservationFilter {

    private static final Logger log = LoggerFactory.getLogger(SensitiveDataMaskingFilter.class);

    // Observation attribute keys that may carry raw prompt/completion text.
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "gen_ai.prompt",
            "gen_ai.completion"
    );

    // Prefix filter — only mask observations produced by Spring AI
    private static final String SPRING_AI_PREFIX = "spring.ai.";

    // --- masking patterns (applied in order) ---

    /** GitHub Personal Access Tokens: ghp_<alphanumeric> */
    private static final Pattern GITHUB_PAT =
            Pattern.compile("ghp_[A-Za-z0-9]+");

    /** DeepSeek / generic AI secret keys: sk-<hex/alphanum 16+> */
    private static final Pattern AI_SECRET_KEY =
            Pattern.compile("sk-[A-Za-z0-9]{10,}");

    /** HTTP Bearer tokens (JWT, OAuth) */
    private static final Pattern BEARER_TOKEN =
            Pattern.compile("Bearer\\s+[A-Za-z0-9._\\-/+]{10,}");

    /** Basic auth credentials (base64 encoded): Basic <base64> */
    private static final Pattern BASIC_AUTH =
            Pattern.compile("Basic\\s+[A-Za-z0-9+/=]{10,}");

    /** RFC 5322-compatible email address */
    private static final Pattern EMAIL =
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    /** PEM private key blocks */
    private static final Pattern PEM_PRIVATE_KEY =
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");

    @Value("${review.observability.mask-sensitive-data:true}")
    private boolean maskEnabled;

    @Override
    public Observation.Context map(Observation.Context context) {
        String name = context.getName();
        if (name == null || (!name.startsWith(SPRING_AI_PREFIX) && !name.equals("review-pipeline"))) {
            return context;
        }

        // Inject user and session ID from current context if available
        ReviewContext reviewCtx = ReviewContextHolder.get();
        if (reviewCtx != null) {
            context.addHighCardinalityKeyValue(KeyValue.of("langfuse.user.id", reviewCtx.userId()));
            context.addHighCardinalityKeyValue(KeyValue.of("langfuse.session.id", reviewCtx.sessionId()));
        }

        if (!maskEnabled || !name.startsWith(SPRING_AI_PREFIX)) {
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

        // Replace all high-cardinality key-values with the masked set
        context.getHighCardinalityKeyValues().forEach(kv -> context.removeHighCardinalityKeyValues(kv.getKey()));
        updated.forEach(context::addHighCardinalityKeyValue);

        return context;
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private boolean isSensitiveKey(String key) {
        if (key == null) return false;
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
}
