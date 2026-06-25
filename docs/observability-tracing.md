# Observability & Trace Collection — Engineering Reference

> **Audience:** Staff engineers and senior contributors who need to understand the exact
> implementation mechanics, call chains, and design rationale — not the "what" but the
> "how" and "why".

---

## 1. Technology Stack & Binding Points

```
Spring Boot 4 / Micrometer Observation API
  └─ spring-boot-starter-opentelemetry  ← bridges Micrometer → OTel SDK
       └─ OTel SDK (BatchSpanProcessor)
            └─ OtlpHttpSpanExporter     ← OTLP/HTTP protobuf to Langfuse
                 └─ Langfuse            ← http://localhost:3000/api/public/otel
```

**No Langfuse SDK is used.** The entire integration is pure OpenTelemetry. Spring Boot's
auto-configuration wires the `MicrometerObservationAutoConfiguration` bridge that converts
Micrometer `Observation` → OTel `Span` before export.

Spring AI automatically creates `Observation` instances for every `ChatClient` call using
`ChatModelObservationContext` — the same context our custom components read and enrich.

---

## 2. Full Call Chain (per LLM invocation)

```
HTTP Request ──► ReviewPipeline.execute()
                  │
                  ├─① ReviewContextHolder.set(userId, sessionId)   ← ThreadLocal
                  │
                  ├─② Observation.start("review-pipeline", registry) ← root span
                  │     └─ Context.root().makeCurrent()              ← isolate from HTTP span
                  │
                  └─③ orchestrator.runAgents(...)  ── Virtual Threads ──►
                          │
                          └─ BaseReviewAgent.review()
                               └─ ChatClient.call()
                                    │
                                    └─[Spring AI internally]
                                       ChatModelObservationContext.start()
                                            │
                                            ▼
                                    ┌──────────────────────────────────────────┐
                                    │      Micrometer Observation Pipeline      │
                                    │                                           │
                                    │  GATE    TracingObservationPredicate      │
                                    │          .test(name, ctx) → true/false    │
                                    │                                           │
                                    │  FILTER  SensitiveDataMaskingFilter       │
                                    │          .map(ctx) → enriched ctx         │
                                    │          (runs before handlers, on stop)  │
                                    │                                           │
                                    │  HANDLER LangfuseObservationHandler       │
                                    │          .onStop(ctx)                     │
                                    │          (runs after all handlers)        │
                                    │                                           │
                                    │  HANDLER OTel bridge                      │
                                    │          Observation → OTel Span          │
                                    │          → BatchSpanProcessor             │
                                    │          → OtlpHttpSpanExporter           │
                                    └──────────────────────────────────────────┘
```

---

## 3. Class-by-Class Implementation Breakdown

### 3.1 `TracingObservationPredicate` — Gate

```
Interface:  io.micrometer.observation.ObservationPredicate
Register:   @Component  (Spring auto-detects ObservationPredicate beans)
Called:     Before any observation is created; if returns false, the observation
            is a no-op — no filter or handler is ever invoked.
```

```java
// Exact predicate logic
public boolean test(String name, Observation.Context context) {
    return name != null &&
        (name.startsWith("spring.ai.") ||   // ChatClient, ChatModel, tool spans
         name.startsWith("gen_ai.")     ||   // OTel GenAI semantic convention names
         name.equals("review-pipeline"));    // our custom root trace
}
```

**Effect:** HTTP server spans, DB spans, scheduler spans are all discarded here. Zero
overhead for non-AI observations.

---

### 3.2 `SensitiveDataMaskingFilter` — Enrichment + Masking

```
Interface:  io.micrometer.observation.ObservationFilter
Register:   @Component + implements Ordered (order = LOWEST_PRECEDENCE)
Called:     ObservationRegistry invokes filter.map(ctx) synchronously when
            observation.stop() is called, BEFORE any ObservationHandler.onStop().
```

**Entry point:** `map(Observation.Context context)`

**Call chain inside `map()`:**

```
map(context)
 │
 ├─ instanceof ChatModelObservationContext?
 │    ├─ YES → processPrompts(ctx)
 │    │          └─ ctx.getRequest().getInstructions()
 │    │               └─ stream → formatMessage(msg) per message type:
 │    │                    ├─ UserMessage / SystemMessage / AssistantMessage → "[ROLE]\n<text>"
 │    │                    ├─ AssistantMessage w/ toolCalls → "[CALL TOOL: name (id)]\n<args>"
 │    │                    └─ ToolResponseMessage → "[TOOL: name (id)]\n<responseData>"
 │    │          → setHighCardinalityKeyValue("gen_ai.prompt", joined)
 │    │          → setHighCardinalityKeyValue("langfuse.observation.input", joined)
 │    │
 │    └─ ctx.getResponse() != null?   ← only true on non-streaming path
 │         YES → processCompletions(ctx)
 │                └─ response.getResults() → map(generation.getOutput())
 │                → setHighCardinalityKeyValue("gen_ai.completion", joined)
 │                → setHighCardinalityKeyValue("langfuse.observation.output", joined)
 │
 ├─ ReviewContextHolder.get() != null?
 │    └─ inject: langfuse.user.id, langfuse.session.id, langfuse.trace.name
 │
 ├─ "spring.ai.tool.call.arguments" present?
 │    └─ copy → "langfuse.observation.input"
 │
 ├─ "spring.ai.tool.call.result" present?
 │    └─ copy → "langfuse.observation.output"
 │
 └─ maskEnabled && (spring.ai.* or gen_ai.*)?
      └─ iterate ALL highCardinalityKeyValues
           └─ isSensitiveKey(kv.key)?
                └─ mask(kv.value)    ← apply 6 regex replacements in order
                     ├─ PEM_PRIVATE_KEY  → "[PRIVATE KEY REDACTED]"
                     ├─ GITHUB_PAT       → "ghp_***"
                     ├─ AI_SECRET_KEY    → "sk-***"
                     ├─ BEARER_TOKEN     → "Bearer ***"
                     ├─ BASIC_AUTH       → "Basic ***"
                     └─ EMAIL            → "[EMAIL REDACTED]"
           → snapshot keys, removeAll, addAll (avoids ConcurrentModificationException)
```

**Critical detail — concurrent modification guard:**

`context.getHighCardinalityKeyValues()` returns a live mutable collection in some
Micrometer versions. Naively iterating + mutating it throws `ConcurrentModificationException`.
The fix is to snapshot the keys first:

```java
var keysToRemove = context.getHighCardinalityKeyValues().stream()
        .map(KeyValue::getKey).toList();          // snapshot
keysToRemove.forEach(context::removeHighCardinalityKeyValues);
updated.forEach(context::addHighCardinalityKeyValue);
```

---

### 3.3 `LangfuseObservationHandler` — Last-Resort Output Fallback

```
Interface:  io.micrometer.observation.ObservationHandler<Observation.Context>
Register:   @Component + implements Ordered (order = LOWEST_PRECEDENCE)
Called:     ObservationRegistry calls handler.onStop(ctx) AFTER the filter chain.
            Because order = LOWEST_PRECEDENCE, this runs after Spring AI's own
            handlers (ChatModelCompletionObservationHandler, etc.).
```

**Why this handler exists:**
Spring AI's `ChatModelCompletionObservationHandler` only writes to SLF4J — it does
**not** write a span attribute. Our filter handles the common path, but misses two cases:

| Case | Why filter misses it |
|------|---------------------|
| Streaming response | `observation.stop()` fires before `MessageAggregator.setResponse()` — `getResponse()` is `null` in the filter |
| `finish_reason=TOOL_CALLS` | Model returns no text, only tool-call payloads; `output.getText()` is blank |

**Entry point:** `onStop(Observation.Context context)`

**3-tier fallback in `onStop()`:**

```
onStop(context)
 │
 ├─ [INPUT fallback]
 │   "langfuse.observation.input" blank?
 │    └─ copy "gen_ai.prompt" → "langfuse.observation.input"
 │
 └─ [OUTPUT — 3 tiers]
     Tier 1: "langfuse.observation.output" already set? → return  (filter did it)
     │
     Tier 2: "gen_ai.completion" present?
     │        → copy to "langfuse.observation.output"          → return
     │
     Tier 3: instanceof ChatModelObservationContext?
              → formatResponse(ctx)
                   └─ ctx.getResponse().getResults()
                        └─ per Generation:
                             ├─ output.getText()           → append text
                             └─ instanceof AssistantMessage?
                                  └─ getToolCalls()        → "[CALL TOOL: name]\n<args>"
              → set "langfuse.observation.output"
              → set "gen_ai.completion"                    (so future copiers see it)
```

**Ordering guarantee:**
Both `SensitiveDataMaskingFilter` (a filter) and `LangfuseObservationHandler` (a handler)
declare `LOWEST_PRECEDENCE`. Filters and handlers are separate pipelines — filters **always**
run before handlers regardless of `@Order`, so there is no ordering conflict between them.
Within the handler pipeline, `LOWEST_PRECEDENCE` ensures Spring AI's handlers complete first.

---

### 3.4 `ReviewContextHolder` — Cross-Thread Context Propagation

```
Type:  Static utility with InheritableThreadLocal<ReviewContext>
Set:   ReviewPipeline.execute()  ← inbound request thread
Get:   SensitiveDataMaskingFilter.map()  ← called on each agent's virtual thread
Clear: ReviewPipeline.execute() finally block
```

**Why `InheritableThreadLocal` works with Virtual Threads:**

`Executors.newVirtualThreadPerTaskExecutor()` creates virtual threads that **inherit**
`InheritableThreadLocal` values from their parent at creation time. The parent is the
inbound HTTP thread that called `ReviewPipeline.execute()` and already set the context.
Each agent's virtual thread therefore sees the correct `userId` and `sessionId` without
any explicit passing.

```java
// ReviewPipeline.execute()  — parent thread
ReviewContextHolder.set(new ReviewContext(prAuthor, repo + "#" + prNumber));

// CompletableFuture.supplyAsync(..., virtualThreadExecutor)  — child virtual thread
//   ↳ SensitiveDataMaskingFilter.map()
ReviewContext ctx = ReviewContextHolder.get();  // ← inherited, not null
```

---

### 3.5 `ReviewPipeline` — Root Trace Construction

```
Entry point:  ReviewPipeline.execute(AgentContext)
Called from:  GitHubWebhookController (async), LocalReviewController, UrlReviewController
```

**Root span isolation pattern:**

```java
// Problem: if we just call Observation.start(), Micrometer inherits the current
// OTel span context (the inbound HTTP span), making this a child span.
// In Langfuse, the entire review would be nested under an HTTP span.
//
// Solution: temporarily clear the OTel context before starting the root observation.

Observation.Scope originalScope = registry.getCurrentObservationScope();
registry.setCurrentObservationScope(null);
Observation observation;
try (io.opentelemetry.context.Scope otelScope = Context.root().makeCurrent()) {
    observation = Observation.start("review-pipeline", registry);
} finally {
    registry.setCurrentObservationScope(originalScope);  // restore HTTP context
}
```

This makes `"review-pipeline"` the **root** of a new OTel trace, not a child of any
HTTP span. Langfuse will display it as a top-level trace with its own trace ID.

**Attribute writes:**

```java
observation.highCardinalityKeyValue("langfuse.trace.input",  inputSummary);  // PR metadata
// ... run agents, synthesize ...
observation.highCardinalityKeyValue("langfuse.trace.output", result.markdownBody());
observation.stop();  // triggers filter → handlers → OTLP export
```

---

## 4. Observation Lifecycle — Exact Invocation Sequence

For a single `ChatClient.call()` within an agent:

```
t=0  ChatModelObservationContext.start()
       ├─ TracingObservationPredicate.test("spring.ai.chat", ctx)
       │    → true  (name starts with "spring.ai.")
       └─ Observation is live; HTTP call to DeepSeek proceeds

t=1  DeepSeek API returns response (or streaming aggregation completes)
       └─ ChatModelObservationContext.setResponse(response)

t=2  observation.stop() called by Spring AI (in doFinally / afterReceive)
       │
       ├─ SensitiveDataMaskingFilter.map(ctx)      ← FILTER PHASE
       │    ├─ extract prompt → langfuse.observation.input
       │    ├─ extract completion (if response != null) → langfuse.observation.output
       │    ├─ inject user/session from ReviewContextHolder
       │    └─ apply masking regexes to all sensitive keys
       │
       ├─ [Spring AI built-in handlers run]        ← HANDLER PHASE (in order)
       │    └─ ChatModelCompletionObservationHandler.onStop() → logs to SLF4J only
       │
       └─ LangfuseObservationHandler.onStop(ctx)   ← LAST HANDLER
            ├─ input fallback: fill langfuse.observation.input if blank
            └─ output 3-tier fallback (Tier 1 usually wins on non-streaming path)

t=3  OTel bridge converts Observation → OTel Span
       └─ All highCardinalityKeyValues become span attributes

t=4  BatchSpanProcessor buffers the span

t=5  (async) OtlpHttpSpanExporter POSTs to
       http://localhost:3000/api/public/otel
       Headers: Authorization=Basic <pk:sk base64>, x-langfuse-ingestion-version=4
```

---

## 5. Span Attribute Mapping to Langfuse Fields

Langfuse reads specific OTel span attributes and maps them to its UI fields:

| OTel Span Attribute | Langfuse Field | Set by |
|---------------------|----------------|--------|
| `langfuse.observation.input` | Span **Input** | `SensitiveDataMaskingFilter` / `LangfuseObservationHandler` |
| `langfuse.observation.output` | Span **Output** | `SensitiveDataMaskingFilter` / `LangfuseObservationHandler` |
| `langfuse.trace.input` | Trace **Input** | `ReviewPipeline` |
| `langfuse.trace.output` | Trace **Output** | `ReviewPipeline` |
| `langfuse.trace.name` | Trace **Name** | `SensitiveDataMaskingFilter` (copies `"review-pipeline"`) |
| `langfuse.user.id` | Trace **User** | `SensitiveDataMaskingFilter` |
| `langfuse.session.id` | Trace **Session** | `SensitiveDataMaskingFilter` |
| `gen_ai.request.model` | Span model label | Spring AI (auto) |
| `gen_ai.usage.input_tokens` | Token counts | Spring AI (auto) |
| `gen_ai.usage.output_tokens` | Token counts | Spring AI (auto) |

---

## 6. Sensitive Key Set (Masking Scope)

The filter only masks attributes whose key is in this exact set:

```java
private static final Set<String> SENSITIVE_KEYS = Set.of(
    "gen_ai.prompt",
    "gen_ai.completion",
    "spring.ai.tool.call.arguments",
    "spring.ai.tool.call.result",
    "langfuse.observation.input",
    "langfuse.observation.output",
    "langfuse.trace.input",
    "langfuse.trace.output"
);
// + any key starting with "gen_ai.prompt." or "gen_ai.completion." (index variants)
```

Low-cardinality attributes (model name, HTTP status, etc.) are **never** masked.

---

## 7. Infrastructure & OTLP Transport

```yaml
# application.yml — no explicit OTLP config needed here.
# Spring Boot reads standard OTel environment variables from the process environment.

management:
  tracing:
    sampling:
      probability: 1.0   # sample every span
```

```bash
# .env — loaded by run.ps1 before starting the JVM
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:3000/api/public/otel
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic <base64(pk:sk)>,x-langfuse-ingestion-version=4
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf   # Langfuse does NOT support gRPC OTLP
```

`spring-boot-starter-opentelemetry` reads these via `OtlpAutoConfiguration` at startup —
no code-level configuration required.

The Langfuse self-hosted backend (`docker-compose.langfuse.yml`):

| Service | Port | Role |
|---------|------|------|
| `langfuse-server` | `3000` | OTLP ingest + UI |
| `langfuse-worker` | — | Async event processing (ClickHouse writes) |
| `postgres` | `5432` | Project/session metadata |
| `clickhouse` | `8123` | Observation time-series storage |
| `minio` | `9091` | Large payload object store (S3-compatible) |
| `redis` | — | Worker job queue |

---

## 8. Key Design Decisions — Rationale

| Decision | Alternative considered | Why this choice |
|----------|----------------------|-----------------|
| `ObservationFilter` for prompt extraction | `ObservationHandler` | Filters run before handlers and have write access to the context; handlers see the final state |
| `InheritableThreadLocal` for context propagation | Passing `ReviewContext` as method arg | Virtual threads inherit it automatically; no plumbing changes needed across agent call chain |
| Root trace isolation via `Context.root().makeCurrent()` | Letting the HTTP span be the parent | Makes each PR review a top-level trace in Langfuse, not buried inside an HTTP span tree |
| 3-tier fallback in handler | Single write point in filter | Filter misses streaming completions; handler's `onStop` fires after stream aggregation is complete |
| `LOWEST_PRECEDENCE` on both filter and handler | Custom numeric order | Filter and handler pipelines are independent; `LOWEST_PRECEDENCE` in each ensures Spring AI built-ins run first in both pipelines |
| OTLP/HTTP protobuf, no Langfuse SDK | Langfuse Python/JS SDK, or OpenLLMetry | Zero SDK dependency; standard OTel is stable, protocol-agnostic, and portable |

---

## 9. Engineering Insights, Gotchas & Forward-Looking Notes

### 9.1 The Streaming Output Gap — What Actually Happens

The doc says the 3-tier handler fallback covers streaming. It is worth being precise about
**why** the race condition exists and **what the fallback actually observes**.

Spring AI's streaming path (e.g. `DeepSeekChatModel.internalStream`) is implemented as a
reactive `Flux`. The observation lifecycle is:

```
Flux.create(...)
  .doOnNext(chunk → aggregate into MessageAggregator)
  .doFinally(_ → observation.stop())     ← fires on last item / error / cancel
```

`MessageAggregator.setResponse()` is called **inside** the subscriber's `onNext` chain
**before** `doFinally`. So by the time `observation.stop()` fires:

1. `setResponse()` has already been called on the context ✓
2. `SensitiveDataMaskingFilter.map()` runs — `ctx.getResponse()` **is not null** ✓

This means the streaming gap that existed in earlier Spring AI versions **may be fully
closed** in the current version. The 3-tier handler is a safety net, not an active fix
for a current failure mode. If you observe empty outputs in Langfuse for streaming calls,
**verify `setResponse()` ordering** before blaming the handler — it may indicate a Spring
AI version regression, not an application bug.

> **Diagnostic:** Enable `logging.level.io.micrometer.observation=TRACE` and look for
> `"langfuse.observation.output already set by filter"` vs `"Setting langfuse.observation.output
> from ChatModelObservationContext"` to determine which tier is firing.

---

### 9.2 OTLP Payload Size & Diff-Length Risk

This application truncates diffs to 80,000 characters before sending them to agents.
Those diffs flow through as prompt text into `langfuse.observation.input`. The full
formatted prompt (system prompt + diff + prior messages) can approach **100 KB per span
attribute**.

The OTel SDK's `BatchSpanProcessor` defaults:
- Max queue size: **2,048 spans**
- Max export batch size: **512 spans**
- Export interval: **5 seconds**
- Export timeout: **30 seconds**

A single review triggers ~5–6 spans (4 agents + synthesizer + root). At 100 KB per span,
one review generates ~500 KB of OTLP payload — well within limits for development.

**Where this breaks at scale:**
- Langfuse's OTLP ingestion has a **4 MB hard limit per HTTP request**. A batch of 512
  spans at 100 KB each = 51 MB — the exporter will silently drop oversized batches.
- The `BatchSpanProcessor` queue is in-memory. Under bursty load (many PRs in parallel),
  the queue fills and new spans are dropped with a `"DROPPED"` log at WARN level.

**Mitigation if this becomes a problem:**
```java
// Truncate langfuse.observation.input/output at the attribute level
// before it reaches the OTel bridge, e.g. in LangfuseObservationHandler:
private static final int MAX_ATTR_LENGTH = 32_768; // 32 KB

private String truncate(String value) {
    if (value == null || value.length() <= MAX_ATTR_LENGTH) return value;
    return value.substring(0, MAX_ATTR_LENGTH) + "\n...[TRUNCATED]";
}
```

No such truncation exists today — worth adding before production deployment.

---

### 9.3 `InheritableThreadLocal` on Java 25 — Technical Debt

`ReviewContextHolder` uses `InheritableThreadLocal`, which works for the current
`Executors.newVirtualThreadPerTaskExecutor()` pattern. However:

- **Java 21+ deprecation trajectory:** `ThreadLocal` (and by extension
  `InheritableThreadLocal`) is being phased out for virtual thread workloads. JEP 481
  (`ScopedValue`) was finalized in Java 23 and is the JDK's recommended replacement.
- **Inheritance is copy-on-create:** The value is copied when the child virtual thread is
  created. If the parent thread modifies the holder after thread creation (unlikely here,
  but possible if the design evolves), the child sees a stale value.
- **`clear()` is non-trivial in nested scenarios:** If a virtual thread creates further
  child threads (e.g. async tool calls spawning more Virtual Threads), those grandchildren
  inherit the value correctly, but `clear()` on the parent does not propagate — the
  grandchildren keep the stale reference until their own GC cycle.

**Recommended migration to `ScopedValue` (Java 23+):**

```java
// Replace:
private static final InheritableThreadLocal<ReviewContext> CONTEXT = new InheritableThreadLocal<>();

// With:
public static final ScopedValue<ReviewContext> CONTEXT = ScopedValue.newInstance();

// Usage in ReviewPipeline.execute():
ScopedValue.where(ReviewContextHolder.CONTEXT, new ReviewContext(userId, sessionId))
           .run(() -> {
               // all code here, including virtual thread spawning, sees the value
           });
// No explicit clear() needed — ScopedValue is automatically unbound at the end of run()
```

`ScopedValue` provides structured, bounded scope — the value is never visible outside the
`run()` block, eliminating the leak risk entirely.

---

### 9.4 OTLP Export Failure — Silent Data Loss

If Langfuse is unreachable, the `BatchSpanProcessor` logs errors and drops spans after
the export timeout expires. **The application itself is unaffected** — observability
failure does not propagate back to the request path.

What you will see in logs:
```
WARN  io.opentelemetry.sdk.trace.export.BatchSpanProcessor - Exporter failed, Spans that were previously queued are discarded.
```

What you will **not** see: any retry, any dead-letter queue, any alerting.

**Debugging OTLP connectivity:**
```bash
# Verify the endpoint is reachable and auth header is correct
curl -v -X POST http://localhost:3000/api/public/otel/v1/traces \
  -H "Authorization: Basic $(echo -n 'pk-lf-xxx:sk-lf-xxx' | base64)" \
  -H "x-langfuse-ingestion-version: 4" \
  -H "Content-Type: application/x-protobuf" \
  --data-binary @/dev/null
# Expect HTTP 200; 401 = wrong key; 404 = wrong URL path; connection refused = Langfuse down
```

**The `x-langfuse-ingestion-version: 4` header is mandatory for Langfuse 3.x.** Omitting
it causes Langfuse to silently accept the request with HTTP 200 but discard the payload.
This is the most common "traces not appearing" root cause.

---

### 9.5 `sampling.probability: 1.0` — Production Consideration

Currently every span is sampled (`probability: 1.0`). For development this is correct.
For production with high PR volume:

- Each PR review generates ~6 spans
- At 100 PRs/day = 600 spans/day — entirely manageable for ClickHouse
- At 10,000 PRs/day = 60,000 spans/day — still fine; ClickHouse is designed for this

Sampling reduction is unlikely to be needed unless the LLM call latency causes span
attribute accumulation to outpace ClickHouse write throughput. Leave at `1.0` unless
you observe ClickHouse CPU/memory pressure at scale.

---

### 9.6 Attribute Write Ordering — Why Tier 1 Is the Common Path

The filter runs during `observation.stop()`. At that moment, for a **non-streaming**
`ChatClient.call()`, the response is synchronously available. The filter writes
`langfuse.observation.output` directly from the response object.

When the OTel bridge runs (after all handlers), it reads the final state of the context's
`highCardinalityKeyValues` map and converts each entry to an OTel span attribute. The
bridge does not differentiate between attributes written by the filter vs. the handler —
it sees only the final map state. This means:

- If the filter writes `langfuse.observation.output` (Tier 1 path), the handler's Tier 1
  check short-circuits and does nothing — **zero duplication risk**.
- If the handler writes it (Tier 2 or 3), it calls `removeHighCardinalityKeyValues(key)`
  before `addHighCardinalityKeyValue(key, value)`, ensuring no duplicate keys exist.

The `setHighCardinalityKeyValue` helper in both components enforces this:

```java
private void setHighCardinalityKeyValue(Observation.Context context, String key, String value) {
    if (value != null) {
        context.removeHighCardinalityKeyValues(key);   // idempotent remove
        context.addHighCardinalityKeyValue(KeyValue.of(key, value));
    }
}
```

This is the correct pattern when writing to Micrometer's observation context outside the
initial observation construction — always remove before add.
