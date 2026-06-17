# Agent System

## Agent interface

```java
public interface ReviewAgent {
    String agentName();
    AgentResult review(AgentContext ctx);
}
```

All agents are Spring `@Component` beans. The orchestrator discovers them via
`List<ReviewAgent>` injection — adding a new specialist agent only requires
creating a new `@Component` class implementing this interface.

---

## Base agent (shared logic)

`BaseReviewAgent` provides common functionality for all specialist agents:
- Loads system prompts from `resources/prompts/<agent>.txt`
- Builds user prompts with diff content, metadata, and **detected languages**
- Calls the AI model via Spring AI `ChatClient`
- Parses JSON responses into `List<Finding>`
- Returns `AgentResult.empty()` on any failure — never throws

---

## Language-aware analysis

The `AgentContext` carries `Set<String> detectedLanguages` detected from file
extensions. This is injected into each agent's user prompt so the model can
apply language-specific patterns (Java JDBC injection vs Python SQLAlchemy
injection, etc.). Agent prompts now include language-specific guidance for
**Java**, **Python**, and general principles for other languages.

---

## Orchestrator pattern (fan-out / fan-in)

`ReviewPipeline` calls `ReviewOrchestrator` with the enabled agent list.
`ReviewOrchestrator` owns the fan-out/fan-in mechanics.

**Executor — inject as a bean, never create inline:**

```java
// In AiConfig
@Bean(destroyMethod = "shutdown")
public ExecutorService virtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
}
```

Creating the executor inside the stream creates a new executor per invocation
and leaks resources. Always inject the shared bean.

**Fan-out with per-agent timeout and resilient failure handling:**

```java
List<CompletableFuture<AgentResult>> futures = agents.stream()
    .map(agent -> CompletableFuture
        .supplyAsync(() -> agent.review(context), virtualThreadExecutor)
        .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .exceptionally(ex -> {
            log.warn("Agent {} failed: {}", agent.agentName(), ex.getMessage());
            return AgentResult.empty(agent.agentName(), ex);
        }))
    .toList();

List<AgentResult> results = futures.stream()
    .map(CompletableFuture::join)
    .toList();
```

> **Why `.exceptionally()` before `.join()`:** `CompletableFuture::join` rethrows
> any unhandled exception as an unchecked `CompletionException`, which would
> abort the entire collect and drop all other results.

---

## Scope boundaries per agent (prevent duplicated findings)

| Agent | Owns | Does NOT flag |
|---|---|---|
| Security | OWASP Top 10, hardcoded secrets, injection, auth bypasses | Style, performance |
| Performance | N+1 queries, O(n²) loops, unnecessary allocations, blocking calls | Security, naming |
| Style | Naming conventions, SOLID violations, design pattern misuse, dead code | Security, perf |
| TestCoverage | Missing tests for new code, untested edge cases, assertion quality | Everything else |
| Synthesizer | Merging + dedup + ranking only | Generating new findings |