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
