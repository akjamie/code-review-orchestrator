# Project Overview

A multi-agent PR code review system built on Spring Boot 4 + Java 25.
When a GitHub PR is opened or updated, an orchestrator agent fans out to four
specialist agents (security, performance, style, test-coverage) running in
parallel, then a synthesizer agent merges the findings and posts a structured
review comment back to the PR.

The primary goal of this project is to learn and practice multi-agent
orchestration patterns using Spring AI with DeepSeek models. Favour clarity of
agent boundaries over premature optimisation.

---

## Tech stack

| Layer | Choice | Notes |
|---|---|---|
| Language | Java 25 LTS | Use records, sealed classes, pattern matching freely |
| Framework | Spring Boot 4.1.x | Built on Spring Framework 7 |
| Concurrency | Virtual Threads (Project Loom) | `spring.threads.virtual.enabled=true` in config |
| AI SDK | Spring AI (OpenAI adapter → DeepSeek) | DeepSeek API is OpenAI-compatible; use `deepseek-chat` model |
| GitHub integration | `org.kohsuke:github-api` + direct HTTP | REST client for runtime |
| Build tool | Gradle (Kotlin DSL) | `build.gradle.kts` |
| Testing | JUnit 5 + Mockito + Testcontainers | Unit per agent, integration per flow |
| State | In-memory only | `ConcurrentHashMap` for in-flight deduplication; no database |
| Config | `application.yml` + env vars | Secrets always via env, never hardcoded |

---

## Environment variables (never hardcode)

```
DEEPSEEK_API_KEY       — DeepSeek API key
GITHUB_TOKEN           — GitHub PAT with repo + pull_requests scopes
GITHUB_WEBHOOK_SECRET  — used to verify webhook HMAC signature
```

---

## Configuration (`application.yml`)

```yaml
spring:
  threads:
    virtual:
      enabled: true
  ai:
    openai:
      base-url: https://api.deepseek.com/v1
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        options:
          model: deepseek-v4-flash

review:
  agents:
    max-tokens: 4096
    timeout-seconds: 120
    enabled:
      security: true
      performance: true
      style: true
      test-coverage: true
  synthesizer:
    max-tokens: 8192
```

> **Why different token limits:** A specialist agent returns a focused JSON array
> of 3–10 findings — 1024 tokens is generous. The synthesizer receives all four
> agents' findings combined plus must produce formatted Markdown; 1024 tokens
> will silently truncate the output. Set synthesizer to 3000 minimum.