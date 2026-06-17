# Code Review Orchestrator — Claude Code Instructions

Multi-agent PR code review system (Spring Boot 4.1 + Java 25 + Spring AI 2.0).
Integrates GitHub MCP (stdio) and Context7 MCP (SSE) for autonomous tool-calling reviews.
Detailed guidance is split into rule files below.

## Quick links

| Rule file | Covers |
|---|---|
| [Project overview](rules/project-overview.md) | Overview, tech stack, config, env vars |
| [Architecture](rules/architecture.md) | System context, flow diagrams, project structure, design decisions |
| [Domain model](rules/domain-model.md) | AgentContext, AgentResult, Finding, Severity records |
| [Agent system](rules/agent-system.md) | Agent interface, orchestrator fan-out pattern, scope boundaries |
| [MCP agent](rules/mcp-agent.md) | Autonomous MCP review agent, tool registration, system prompt |
| [Prompts & SDK](rules/prompts-sdk.md) | Spring AI usage, prompt design rules, prompt iteration |
| [GitHub integration](rules/github-integration.md) | Webhook, HMAC verification, diff size guard, review comment format |
| [Testing](rules/testing.md) | Testing strategy, sample diffs |
| [Development workflow](rules/development.md) | Dev workflow, Java re-entry notes, definition of done |

## Rules of thumb

- DeepSeek via Spring AI OpenAI adapter (`spring.ai.openai.base-url=https://api.deepseek.com/`)
- **McpReviewAgent is the primary review path** — it uses GitHub MCP tools to fetch PR data and Context7 to look up library docs.
- When `review.mcp-agent.enabled=false`, the classic 4-agent fan-out pipeline is used as a fallback.
- The polling monitor (`GitHubApiMonitor`) is disabled by default — set `POLLING_ENABLED=true` to activate.
- One agent failure never aborts a review — all failures are caught and logged.
- Prompts live in `resources/prompts/*.txt` — edit without recompiling.
- Domain types are Java 25 records (immutable, no boilerplate).
- All secrets via environment variables — never hardcoded.