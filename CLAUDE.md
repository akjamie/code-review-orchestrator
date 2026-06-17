# Code Review Orchestrator — CLAUDE.md

Guidelines for building, running, and testing the Code Review Orchestrator.

## Prerequisites
- Java 25 LTS
- Node.js ≥ 18 + npx (required to launch the GitHub MCP server)
- DeepSeek API key
- GitHub PAT with `repo` and `pull_requests` scopes

## Commands

### Build & Run
- Build project: `./gradlew build`
- Clean and build: `./gradlew clean build`
- Run local development server: `./gradlew bootRun`
- Run with custom port: `./gradlew bootRun --args="--server.port=8081"`

### Testing
- Run all tests: `./gradlew test`
- Run specific test class: `./gradlew test --tests org.akj.reviewer.agent.BaseReviewAgentTest`
- Run integration tests: `./gradlew test --tests ReviewFlowIntegrationTest`
- Run with verbose info: `./gradlew test --info`

## Key Environment Variables
| Variable | Purpose |
|---|---|
| `DEEPSEEK_API_KEY` | DeepSeek API key (used by all agents) |
| `GITHUB_TOKEN` | GitHub PAT — also used by GitHub MCP server subprocess |
| `GITHUB_WEBHOOK_SECRET` | HMAC secret for webhook validation |
| `POLLING_ENABLED` | Set to `true` to enable background API polling monitor |
| `POLLING_INTERVAL_SECONDS` | Polling interval in seconds (default: 60) |
| `MONITORED_REPOS` | Comma-separated `owner/repo` list for the polling monitor |
| `MCP_MODEL_NAME` | Override the model for the MCP review agent |
| `MODEL_NAME` | Override the default model for all agents |

## Style & Architecture Guidelines
- **Java Version**: Java 25 (virtual threads, records, pattern matching, switch expressions).
- **Core Stack**: Spring Boot 4.1, Spring AI 2.0, Virtual Threads.
- **Two PR detection paths** (both converge on McpReviewAgent when enabled):
  - `GitHubWebhookController` — receives webhook events from GitHub
  - `GitHubApiMonitor` — background `@Scheduled` polling (disabled by default)
- **McpReviewAgent** — single autonomous agent using GitHub MCP + Context7 MCP tools.
  Falls back to the classic 4-agent pipeline when `review.mcp-agent.enabled=false`.
- **Error handling**: Agent failures must never abort the review. Use `try/catch` → log and continue.
- **Secrets**: All secrets via environment variables. Never hardcode in source.
- **Prompts**: Edit `src/main/resources/prompts/*.txt` without recompiling.

