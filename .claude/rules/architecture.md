# Architecture

## System context (external boundaries)

```
  Developer
     │  opens / pushes PR
     ▼
┌─────────────┐     webhook POST      ┌──────────────────────────────────┐
│   GitHub    │ ──────────────────▶  │   Code Review Orchestrator App   │
│  (PR + API) │ ◀──────────────────  │       (Spring Boot 4 / Java 25)  │
└─────────────┘   POST /pulls/{n}    └──────────────────────────────────┘
                  /reviews                          │
                                                    │ HTTPS
                                                    ▼
                                          ┌──────────────────┐
                                          │  DeepSeek API    │
                                          │ (deepseek-chat)  │
                                          └──────────────────┘
```

## Internal agent flow (fan-out / fan-in)

```
GitHub Webhook
      │
      ▼
GitHubWebhookController
  • validate HMAC-SHA256
  • filter: opened / synchronize / reopened only
  • return 200 immediately
      │
      │ async
      ▼
ReviewPipeline
  • fetch diff via GitHubDiffFetcher
  • guard: truncate diff > 80k chars, set isPartial
  • build AgentContext
      │
      ▼
ReviewOrchestrator  ──── CompletableFuture.supplyAsync × 4 (Virtual Threads) ────▶
      │                                                                           │
      │          ┌──────────────────┬──────────────────┬──────────────────┐      │
      │          ▼                  ▼                  ▼                  ▼      │
      │    SecurityAgent    PerformanceAgent      StyleAgent    TestCoverageAgent │
      │    (OWASP, secrets) (N+1, complexity)  (SOLID, naming)  (gaps, edge cases)│
      │          │                  │                  │                  │      │
      │          └──────────────────┴──────────────────┴──────────────────┘      │
      │                             │  List<AgentResult>                         │
      │◀────────────────────────────┘ (fan-in: all futures joined, failures safe)│
      │
      ▼
SynthesizerAgent
  • deduplicate overlapping findings
  • rank by Severity (CRITICAL → INFO)
  • format as Markdown review comment
      │
      ▼
GitHubReviewPoster
  • POST /repos/{owner}/{repo}/pulls/{pr}/reviews
  • attach line-level comments where lineNumber is present
      │
      ▼
GitHub PR ← review comment posted
```

## Per-agent internal structure

Each specialist agent follows the same internal pattern:

```
agent.review(AgentContext)
      │
      ├─▶ loadSystemPrompt()          reads resources/prompts/<agent>.txt
      ├─▶ buildUserPrompt(ctx)        injects diffContent + metadata
      │
      ▼
ChatClient.prompt().system().user()
  .options(OpenAiChatOptions)         deepseek-chat
      │
      ▼
parse JSON response → List<Finding>
      │
      ├── success → AgentResult(agentName, findings, rawReasoning)
      └── parse failure → AgentResult.empty(agentName, ex)   [never throws]
```

## Data flow summary

```
GitHub PR diff (raw text)
  → AgentContext (record, immutable)
    → [×4 parallel] AgentResult (record, list of Finding records)
      → SynthesizerAgent
        → Markdown string
          → GitHub PR Review comment
```

## Key design decisions

| Decision | Choice | Reason |
|---|---|---|
| AI provider | DeepSeek via Spring AI (OpenAI adapter) | OpenAI-compatible API, Spring-native integration |
| Concurrency model | Virtual Threads + `CompletableFuture` | Readable; no reactive learning curve |
| Agent failure mode | `exceptionally()` → `AgentResult.empty()` | One failure never aborts the review |
| Prompt storage | `.txt` files in `resources/prompts/` | Edit prompts without recompiling |
| State | None (in-memory per request) | Simplicity; no DB dependency |
| Token budgets | Agents 1024 / Synthesizer 3000 | Synthesizer receives all findings combined |
| Diff truncation | 80k chars, `isPartial` flag | Prevents silent context window overflow |

---

## Project structure

```
code-review-orchestrator/
├── build.gradle.kts
├── settings.gradle.kts
├── CLAUDE.md
├── src/
│   ├── main/
│   │   ├── java/org/akj/reviewer/
│   │   │   ├── ReviewerApplication.java
│   │   │   ├── webhook/
│   │   │   │   └── GitHubWebhookController.java
│   │   │   ├── orchestrator/
│   │   │   │   ├── ReviewOrchestrator.java
│   │   │   │   └── ReviewPipeline.java
│   │   │   ├── agent/
│   │   │   │   ├── ReviewAgent.java
│   │   │   │   ├── BaseReviewAgent.java
│   │   │   │   ├── AgentContext.java
│   │   │   │   ├── AgentResult.java
│   │   │   │   ├── Finding.java
│   │   │   │   ├── FindingDto.java
│   │   │   │   ├── Severity.java
│   │   │   │   ├── SecurityAgent.java
│   │   │   │   ├── PerformanceAgent.java
│   │   │   │   ├── StyleAgent.java
│   │   │   │   └── TestCoverageAgent.java
│   │   │   ├── synthesizer/
│   │   │   │   └── SynthesizerAgent.java
│   │   │   ├── github/
│   │   │   │   ├── GitHubDiffFetcher.java
│   │   │   │   └── GitHubReviewPoster.java
│   │   │   └── config/
│   │   │       ├── AiConfig.java
│   │   │       └── GitHubConfig.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── prompts/
│   │           ├── security-agent.txt
│   │           ├── performance-agent.txt
│   │           ├── style-agent.txt
│   │           ├── testcoverage-agent.txt
│   │           └── synthesizer-agent.txt
│   └── test/
│       └── java/org/akj/reviewer/
│           ├── agent/
│           ├── orchestrator/
│           ├── synthesizer/
│           ├── webhook/
│           └── integration/
└── testdata/
    └── sample-diffs/
```