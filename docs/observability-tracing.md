# Observability & Trace Collection

This document describes the end-to-end observability architecture of the Code Review
Orchestrator — how every LLM call, tool invocation, and pipeline execution is captured,
enriched, sanitised, and exported to [Langfuse](https://langfuse.com) for analysis.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Infrastructure Stack](#infrastructure-stack)
3. [Signal Flow](#signal-flow)
4. [Component Reference](#component-reference)
   - [TracingObservationPredicate](#1-tracingobservationpredicate)
   - [SensitiveDataMaskingFilter](#2-sensitivedatamaskingfilter)
   - [LangfuseObservationHandler](#3-langfuseobservationhandler)
   - [ReviewContextHolder](#4-reviewcontextholder)
   - [ReviewPipeline Instrumentation](#5-reviewpipeline-instrumentation)
5. [Data Model in Langfuse](#data-model-in-langfuse)
6. [Configuration Reference](#configuration-reference)
7. [Local Setup](#local-setup)
8. [Known Constraints & Design Decisions](#known-constraints--design-decisions)
9. [Sensitive Data Policy](#sensitive-data-policy)

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────┐
│                  Code Review Orchestrator (JVM)               │
│                                                              │
│  ReviewPipeline ──────────────────────────────────────────┐  │
│    │  starts Observation("review-pipeline")               │  │
│    │  sets langfuse.trace.input / .output                 │  │
│    │                                                      │  │
│    ├─ SecurityAgent ──► Spring AI ChatClient              │  │
│    ├─ PerformanceAgent ►   │ Observation("spring.ai.*")   │  │
│    ├─ StyleAgent ──────►   │ + tool spans                 │  │
│    └─ TestCoverageAgent►   │                              │  │
│                            ▼                              │  │
│            ┌────────────────────────────────┐             │  │
│            │   Micrometer Observation API   │             │  │
│            │                                │             │  │
│            │  ObservationPredicate (filter) │             │  │
│            │  ──► TracingObservationPredicate│             │  │
│            │                                │             │  │
│            │  ObservationFilter (enrichment)│             │  │
│            │  ──► SensitiveDataMaskingFilter │             │  │
│            │                                │             │  │
│            │  ObservationHandler (write-out)│             │  │
│            │  ──► LangfuseObservationHandler│             │  │
│            └────────────────────────────────┘             │  │
│                            │                              │  │
│                  OTLP/HTTP protobuf                        │  │
└───────────────────────────┼───────────────────────────────┘  │
                             ▼
          ┌──────────────────────────────────────┐
          │          Langfuse (self-hosted)        │
          │  http://localhost:3000                 │
          │                                        │
          │  PostgreSQL ← metadata & sessions      │
          │  ClickHouse ← time-series observations │
          │  MinIO      ← event payload storage    │
          │  Redis      ← async job queue          │
          └──────────────────────────────────────┘
```

The integration uses the **standard OpenTelemetry (OTLP)** protocol — no Langfuse SDK is
required in the application. Spring Boot 4's `spring-boot-starter-opentelemetry` auto-
configures the bridge between Micrometer's Observation API and OpenTelemetry's span model,
which is then exported via OTLP/HTTP to Langfuse's public ingest endpoint.

---

## Infrastructure Stack

The observability backend is defined in [`docker-compose.langfuse.yml`](../docker-compose.langfuse.yml)
and requires no external SaaS dependencies.

| Service | Image | Port | Role |
|---------|-------|------|------|
| `langfuse-server` | `langfuse/langfuse:3.190` | `3000` | UI + OTLP ingest endpoint |
| `langfuse-worker` | `langfuse/langfuse-worker:latest` | — | Async event processing |
| `postgres` | `postgres:18.4` | `5432` | Project/session metadata |
| `clickhouse` | `clickhouse/clickhouse-server:24.12` | `8123` | Observation time-series |
| `minio` | `minio/minio:latest` | `9091/9092` | Large event payload store |
| `redis` | `redis:7` | — | Worker job queue |

Start the stack:

```bash
docker compose -f docker-compose.langfuse.yml up -d
```

The Langfuse UI is available at `http://localhost:3000` once the containers are healthy.

> **Note on port `9091`:** MinIO's S3 API is mapped to host port `9091` (not the default
> `9000`) to avoid a conflict with ClickHouse's native TCP port.

---

## Signal Flow

Each PR review produces a **trace** in Langfuse containing a nested tree of **spans**:

```
Trace: review-pipeline
  │  input:  "Repository: ...\nPR Number: ...\nTitle: ..."
  │  output: "<full Markdown review>"
  │  user:   <PR author>
  │  session: <repo#PR-number>
  │
  ├─ Span: spring.ai.chat  (SecurityAgent LLM call)
  │    input:  "[SYSTEM]\n...\n[USER]\n<diff>"
  │    output: "[ASSISTANT]\n<findings JSON>"
  │
  ├─ Span: spring.ai.chat  (PerformanceAgent LLM call)
  │    ...
  │
  ├─ Span: spring.ai.tool.call  (MCP tool invocation, if applicable)
  │    input:  {"repo": "...", "pull_number": ...}
  │    output: <tool result>
  │
  └─ Span: spring.ai.chat  (SynthesizerAgent LLM call)
       input:  "[SYSTEM]\n...\n[USER]\n<all agent findings>"
       output: "[ASSISTANT]\n<Markdown review>"
```

### Why two custom components handle output capture

Spring AI's built-in `ChatModelCompletionObservationHandler` **only logs the completion to
SLF4J** — it does not write `gen_ai.completion` as a span attribute. Additionally, for
calls whose `finish_reason` is `TOOL_CALLS` (the model responds only with a function-call
payload and no text), the default handlers produce a blank output attribute.

Two custom components work together to guarantee that every span has meaningful I/O:

| Component | Type | Runs | Responsibility |
|-----------|------|------|----------------|
| `SensitiveDataMaskingFilter` | `ObservationFilter` | **Before** handlers | Extracts prompt/completion from `ChatModelObservationContext`, writes `langfuse.observation.input/output`, masks secrets/PII |
| `LangfuseObservationHandler` | `ObservationHandler` | **After** all filters | 3-tier fallback to ensure `langfuse.observation.output` is always populated |

---

## Component Reference

### 1. `TracingObservationPredicate`

**File:** [`config/TracingObservationPredicate.java`](../src/main/java/org/akj/reviewer/config/TracingObservationPredicate.java)

A Micrometer `ObservationPredicate` that acts as a **gate**: only observations whose name
matches the predicate are tracked, reducing noise from unrelated framework spans (HTTP
server spans, scheduler ticks, etc.).

**Allowed observation names:**

| Pattern | Source |
|---------|--------|
| `spring.ai.*` | Spring AI chat model + chat client spans |
| `gen_ai.*` | OpenTelemetry GenAI semantic conventions |
| `review-pipeline` | Custom root trace created by `ReviewPipeline` |

Everything else (e.g. `http.server.requests`, `spring.data.*`) is silently discarded.

---

### 2. `SensitiveDataMaskingFilter`

**File:** [`config/SensitiveDataMaskingFilter.java`](../src/main/java/org/akj/reviewer/config/SensitiveDataMaskingFilter.java)

Implements `ObservationFilter` (runs synchronously on the observation thread, before any
handler). Its responsibilities are:

**a) Prompt extraction**

For `ChatModelObservationContext` spans, reads the raw `Prompt` from the request and
formats each message into a structured string:

```
[USER]
<message text>

[SYSTEM]
<system prompt>

[CALL TOOL: get_pull_request (id: call_abc123)]
{"owner":"akjamie","repo":"code-review-orchestrator","pull_number":42}

[TOOL: get_pull_request (id: call_abc123)]
<tool response data>
```

The formatted prompt is written to both `gen_ai.prompt` and `langfuse.observation.input`.

**b) Completion extraction**

If the context's response is already available (non-streaming path), completions are
extracted similarly and written to `gen_ai.completion` and `langfuse.observation.output`.

**c) Session context injection**

Reads the `ReviewContext` from `ReviewContextHolder` (thread-local propagated via
`InheritableThreadLocal`) and injects:

| Langfuse attribute | Value |
|--------------------|-------|
| `langfuse.user.id` | PR author login |
| `langfuse.session.id` | `<repo-full-name>#<PR-number>` |
| `langfuse.trace.name` | `"review-pipeline"` |

**d) Tool observation mapping**

For `spring.ai.tool.call.*` spans, copies `spring.ai.tool.call.arguments` →
`langfuse.observation.input` and `spring.ai.tool.call.result` →
`langfuse.observation.output`.

**e) PII & secret masking**

All six masking rules are applied **in order** to every high-cardinality span attribute
that matches the sensitive key set:

| Pattern matched | Replacement |
|----------------|-------------|
| `ghp_[A-Za-z0-9]+` (GitHub PATs) | `ghp_***` |
| `sk-[A-Za-z0-9]{10,}` (AI keys) | `sk-***` |
| `Bearer <token>` | `Bearer ***` |
| `Basic <base64>` | `Basic ***` |
| Email addresses | `[EMAIL REDACTED]` |
| PEM private key blocks | `[PRIVATE KEY REDACTED]` |

> **Toggle:** Set `review.observability.mask-sensitive-data=false` (or env
> `MASK_SENSITIVE_DATA=false`) to disable masking in a trusted local environment. Default
> is `true`.

**Order:** `Ordered.LOWEST_PRECEDENCE` — runs last among filters so all Spring AI
attributes are fully populated first.

---

### 3. `LangfuseObservationHandler`

**File:** [`config/LangfuseObservationHandler.java`](../src/main/java/org/akj/reviewer/config/LangfuseObservationHandler.java)

Implements `ObservationHandler<Observation.Context>` and runs in `onStop()` after all
other handlers, including Spring AI's built-in ones.

**Three-tier output fallback (in order):**

```
1. langfuse.observation.output already set? → done (set by filter on the common path)
2. gen_ai.completion attribute present?     → copy it to langfuse.observation.output
3. Read ChatModelObservationContext directly → format text + tool calls, write both
                                               langfuse.observation.output and
                                               gen_ai.completion
```

The third tier is specifically designed to capture `finish_reason=TOOL_CALLS` responses
where the model produces no text but emits one or more function-call payloads that would
otherwise be invisible in the trace.

**Input fallback:** Similarly ensures `langfuse.observation.input` is populated by copying
`gen_ai.prompt` if the filter did not already set it.

**Order:** `Ordered.LOWEST_PRECEDENCE` — runs after all Spring AI handlers.

---

### 4. `ReviewContextHolder`

**File:** [`config/ReviewContextHolder.java`](../src/main/java/org/akj/reviewer/config/ReviewContextHolder.java)

A static utility class that propagates `ReviewContext` (user ID + session ID) from the
request-handling thread to all child threads spawned for parallel agent execution.

```java
// On the inbound webhook thread (ReviewPipeline.execute)
ReviewContextHolder.set(new ReviewContext(prAuthor, repoName + "#" + prNumber));

// On each Virtual Thread (SecurityAgent, PerformanceAgent, ...)
ReviewContext ctx = ReviewContextHolder.get(); // propagated via InheritableThreadLocal
```

> **Why `InheritableThreadLocal`?** Java Virtual Threads inherit thread-locals from their
> parent thread when created via `Executors.newVirtualThreadPerTaskExecutor()`. This means
> the PR context is automatically available in all spawned agent threads without any
> explicit passing through method parameters or reactive context.

**Lifecycle:** `ReviewContextHolder.clear()` is called in the `finally` block of
`ReviewPipeline.execute()` to prevent leaks across requests.

---

### 5. `ReviewPipeline` Instrumentation

**File:** [`orchestrator/ReviewPipeline.java`](../src/main/java/org/akj/reviewer/orchestrator/ReviewPipeline.java)

The pipeline creates the **root observation** that becomes the top-level trace in Langfuse:

```java
Observation observation = Observation.start("review-pipeline", observationRegistry);
observation.highCardinalityKeyValue("langfuse.trace.input", inputSummary);
// ... run agents, synthesize ...
observation.highCardinalityKeyValue("langfuse.trace.output", result.markdownBody());
observation.stop();
```

**Root context isolation:** The observation is started in an **empty OTel context**
(`Context.root().makeCurrent()`) to ensure it is the root of a new trace tree rather than
a nested child of any inbound HTTP span. This guarantees that Langfuse treats the full
review as a single self-contained trace.

---

## Data Model in Langfuse

### Trace

| Field | Value |
|-------|-------|
| Name | `review-pipeline` |
| User ID | PR author GitHub login |
| Session ID | `<owner/repo>#<PR-number>` |
| Input | `Repository`, `PR Number`, `Title`, `Files` (plain text) |
| Output | Full Markdown review comment |

### Spans (Observations)

Each Spring AI `ChatClient` call produces one span. Common attributes:

| Attribute | Description |
|-----------|-------------|
| `gen_ai.system` | `deepseek` |
| `gen_ai.request.model` | Model name (e.g. `deepseek-v4-flash`) |
| `gen_ai.usage.input_tokens` | Prompt token count |
| `gen_ai.usage.output_tokens` | Completion token count |
| `langfuse.observation.input` | Formatted prompt (masked if enabled) |
| `langfuse.observation.output` | Formatted completion or tool-call payload (masked) |
| `langfuse.user.id` | PR author (propagated via `ReviewContextHolder`) |
| `langfuse.session.id` | `repo#pr` identifier |
| `langfuse.trace.name` | `"review-pipeline"` |

### Tool Call Spans

MCP tool invocations emit spans named `spring.ai.tool.call`:

| Attribute | Description |
|-----------|-------------|
| `spring.ai.tool.name` | Tool name (e.g. `get_pull_request`) |
| `langfuse.observation.input` | Tool arguments (JSON, masked) |
| `langfuse.observation.output` | Tool result (masked) |

---

## Configuration Reference

### `application.yml`

```yaml
spring:
  ai:
    chat:
      observations:
        log-prompt: true          # Log prompts to SLF4J (not to Langfuse)
        log-completion: true      # Log completions to SLF4J (not to Langfuse)
    observability:
      tool:
        call:
          arguments:
            enabled: true         # Capture tool call arguments as span attributes
          result:
            enabled: true         # Capture tool call results as span attributes
    tools:
      observations:
        include-content: true     # Include tool I/O content in observations

review:
  observability:
    mask-sensitive-data: ${MASK_SENSITIVE_DATA:true}   # Toggle PII masking

management:
  tracing:
    sampling:
      probability: 1.0            # Sample every request (adjust for high traffic)
```

### Environment Variables (OTLP Export)

Set in `.env` (see `.env.template`):

| Variable | Description | Example |
|----------|-------------|---------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Langfuse OTLP ingest URL | `http://localhost:3000/api/public/otel` |
| `OTEL_EXPORTER_OTLP_HEADERS` | Auth header (Base64 encoded key pair) | `Authorization=Basic <base64(pk:sk)>,x-langfuse-ingestion-version=4` |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | Must be `http/protobuf` (gRPC not supported) | `http/protobuf` |
| `MASK_SENSITIVE_DATA` | Set to `false` to disable masking | `true` |

**Generating the OTLP auth header:**

```bash
# Replace pk-lf-xxxx and sk-lf-xxxx with your Langfuse project keys
echo -n "pk-lf-xxxx:sk-lf-xxxx" | base64
# Output: cGstbGYteHh4eDpzay1sZi14eHh4
```

Then in `.env`:

```
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic cGstbGYteHh4eDpzay1sZi14eHh4,x-langfuse-ingestion-version=4
```

---

## Local Setup

### Step 1 — Start the Langfuse backend

```bash
docker compose -f docker-compose.langfuse.yml up -d
```

Wait for all containers to reach a healthy state (typically 30–60 seconds):

```bash
docker compose -f docker-compose.langfuse.yml ps
```

### Step 2 — Create a Langfuse project and API keys

1. Open `http://localhost:3000` in your browser
2. Create an account and sign in
3. Navigate to **Settings → API Keys** and create a new key pair (`pk-lf-*` and `sk-lf-*`)

### Step 3 — Configure the application

Copy `.env.template` to `.env` and fill in:

```bash
# .env (excerpt)
DEEPSEEK_API_KEY=sk-your-key
GITHUB_TOKEN=ghp_your-token
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:3000/api/public/otel
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic <your-base64-key>,x-langfuse-ingestion-version=4
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
```

### Step 4 — Run the application

```powershell
.\run.ps1
```

### Step 5 — Trigger a review and inspect the trace

```bash
curl -X POST http://localhost:8080/review/local \
  -H "Content-Type: application/json" \
  -d '{"diff": "...", "files": ["App.java"], "title": "Test"}'
```

Open `http://localhost:3000` → **Traces** to see the full trace tree with I/O for every
span.

---

## Known Constraints & Design Decisions

### Streaming vs. Non-Streaming

For **streaming** responses, Micrometer's `ObservationFilter.map()` is called when
`observation.stop()` fires, which happens **before** the `MessageAggregator` sets the
final response on the context. This means `SensitiveDataMaskingFilter` cannot capture
completions from streaming calls via the filter path.

`LangfuseObservationHandler` compensates by reading the context's response in `onStop()`,
which is called after the full streaming aggregation is complete.

### Tool-Call-Only Responses (`finish_reason=TOOL_CALLS`)

When the model's response consists solely of a function call (no text content),
`output.getText()` returns `null` or `""`. The default Spring AI handlers do not write a
completion attribute in this case. `LangfuseObservationHandler`'s third-tier fallback
explicitly iterates over `AssistantMessage.getToolCalls()` and formats them as:

```
[CALL TOOL: get_pull_request (id: call_abc123)]
{"owner":"akjamie","repo":"...", ...}
```

### Root Trace Isolation

`ReviewPipeline` resets the OTel context to `Context.root()` before creating the
`"review-pipeline"` observation. Without this, the root trace would appear as a nested
child of the HTTP request span, making the Langfuse view difficult to navigate. This
ensures each PR review appears as its own top-level trace.

### Virtual Thread Context Propagation

`ReviewContextHolder` uses `InheritableThreadLocal` rather than a plain `ThreadLocal`.
Java's `Executors.newVirtualThreadPerTaskExecutor()` copies `InheritableThreadLocal`
values to child virtual threads at creation time, so all parallel agent threads
automatically receive the PR author and session ID without any framework-level context
propagation bridge.

---

## Sensitive Data Policy

The following data is **never exported** to Langfuse traces in a raw form when masking is
enabled (the default):

- GitHub Personal Access Tokens (`ghp_*`)
- AI provider API keys (`sk-*`)
- Bearer / Basic auth tokens in any attribute value
- Email addresses appearing in code diffs or tool results
- PEM-encoded private key blocks

These patterns are detected by `SensitiveDataMaskingFilter.mask()` and replaced with
fixed placeholder strings (e.g. `ghp_***`, `[EMAIL REDACTED]`) before the observation is
written to any exporter.

To disable masking in a local development environment where traces contain only test data:

```bash
# In .env
MASK_SENSITIVE_DATA=false
```

> **Caution:** Never disable masking in production or staging environments where real API
> keys and credentials may flow through the review pipeline.
