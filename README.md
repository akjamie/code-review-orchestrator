# Code Review Orchestrator

A multi-agent PR code review system that uses DeepSeek (via Spring AI) to automatically review GitHub pull requests across four specialized domains: security, performance, style, and test coverage.

**Stack:** Spring Boot 4.1 • Java 25 • Spring AI (DeepSeek) • Virtual Threads

## Overview

When a GitHub PR is opened or updated, this system:

1. **Receives** the webhook from GitHub
2. **Fetches** the full diff and metadata
3. **Fans out** to 4 specialist agents running in parallel (using Virtual Threads), OR delegates to a unified **MCP Review Agent** if configured.
4. **Collects** findings from each agent
5. **Synthesizes** the results into a single Markdown review
6. **Posts** the review back to the PR as a GitHub comment

Each agent focuses on a single concern:
- **Security Agent** — OWASP vulnerabilities, hardcoded secrets, injection attacks
- **Performance Agent** — N+1 queries, algorithmic complexity, resource leaks
- **Style Agent** — SOLID violations, naming conventions, dead code
- **Test Coverage Agent** — Missing tests, untested edge cases

## Quick Start

### Prerequisites
- Java 25 LTS
- Node.js ≥ 18 + `npx` (required to launch the local GitHub MCP server subprocess)
- GitHub PAT with `repo` and `pull_requests` scopes (used by the review poster and GitHub MCP server)
- DeepSeek API key
- (Optional) Context7 MCP server integration for enterprise knowledge

### Environment Setup

```bash
# Required for AI review agents
export DEEPSEEK_API_KEY="sk-..."

# Required for GitHub integration and GitHub MCP server
export GITHUB_TOKEN="ghp_..."

# Required for Webhook endpoint HMAC verification
export GITHUB_WEBHOOK_SECRET="your-webhook-secret"

# Optional: Enable background API polling monitor (disabled by default)
export POLLING_ENABLED="true"
export MONITORED_REPOS="owner/repo1,owner/repo2"
```


### Build & Run

```bash
./gradlew clean build
./gradlew bootRun
```

The app listens on:

| Endpoint | Method | Purpose |
|---|---|---|
| `http://localhost:8080/webhook/github` | POST | GitHub webhook receiver |
| `http://localhost:8080/review/local` | POST | Local E2E testing (see below) |
| `http://localhost:8080/review/url` | POST | Trigger a review by PR URL |
| `http://localhost:8080/health` | GET | Health check |
| `http://localhost:8080/swagger-ui/index.html` | GET | Interactive Swagger UI API documentation |


---

## Local E2E Testing (no GitHub needed)

You can test the full pipeline locally with a single curl command — no GitHub
webhook or ngrok required. The `POST /review/local` endpoint accepts a diff
directly and returns the review result as JSON.

### Prerequisite for local testing

Set the `DEEPSEEK_API_KEY` environment variable so the agents can call the AI model.

### Quick test with a sample diff

```bash
# Read a sample diff and send it to the local review endpoint
DIFF=$(cat src/test/resources/diffs/sql-injection-vuln.diff | python -c "import json,sys; print(json.dumps(sys.stdin.read()))")

curl -X POST "http://localhost:8080/review/local" \
  -H "Content-Type: application/json" \
  -d '{
    "diff": '"$DIFF"',
    "files": ["src/main/AuthService.java", "src/main/UserRepository.java"],
    "title": "Local E2E test"
  }'
```

The response includes:
- `markdownBody` — the full Markdown review comment
- `findings` — structured list of findings with severity, file, line, message, suggestion

### Test without an API key (mocked pipeline)

```bash
# Run the in-memory integration test that uses mocked AI calls
./gradlew test --tests ReviewFlowIntegrationTest
```

### Test with real API calls (requires DEEPSEEK_API_KEY)

```bash
export DEEPSEEK_API_KEY="sk-..."
./gradlew bootRun
```

In another terminal:

```bash
curl -X POST "http://localhost:8080/review/local" \
  -H "Content-Type: application/json" \
  -d '{
    "diff": "diff --git a/src/main.py b/src/main.py\nnew file mode 100644\n--- /dev/null\n+++ b/src/main.py\n@@ -0,0 +1,8 @@\n+import os\n+\n+def run_cmd(user_input):\n+    os.system(user_input)\n+\n+def get_config():\n+    key = \"sk-live-abc123\"\n+    return {\"api_key\": key}\n",
    "files": ["src/main.py"],
    "title": "Python security test"
  }'
```

This runs the full pipeline — 4 agents in parallel calling DeepSeek, synthesizer
merging results — and returns the review as JSON. A true smoke test of the
entire system.

### Trigger review by PR URL

To manually trigger a review for an existing GitHub pull request and automatically post the review comments back to GitHub:

```bash
curl -X POST "http://localhost:8080/review/url" \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://github.com/owner/repo/pull/123"
  }'
```

### Configure Webhook in GitHub

**Repo-level webhook:**
1. Go to your repository → Settings → Webhooks → Add webhook
2. **Payload URL:** `https://your-domain.com/webhook/github`
3. **Content type:** `application/json`
4. **Secret:** (same as `GITHUB_WEBHOOK_SECRET`)
5. **Events:** Pull Requests (opened, synchronize, reopened)
6. Save

**Org-level webhook:**
1. Go to your GitHub organization → Settings → Webhooks → Add webhook
2. Same URL and secret as above
3. The controller auto-detects org payload structure

**Limit which repos are reviewed:**
Set `review.monitored-repos` in `application.yml`:
```yaml
review:
  monitored-repos: "my-org/repo-a,my-org/repo-b"
```
Leave empty to allow all repos.

## Architecture

### Fan-Out / Fan-In Pattern

```
GitHub Webhook
      ↓
ReviewPipeline (validate, fetch diff, guard size)
      ↓
ReviewOrchestrator (fan-out to 4 agents via Virtual Threads)
      ├── SecurityAgent
      ├── PerformanceAgent
      ├── StyleAgent
      └── TestCoverageAgent
           ↓ (CompletableFuture.join all)
SynthesizerAgent (dedup, rank, format Markdown)
      ↓
GitHubReviewPoster (POST /pulls/{pr}/reviews)
      ↓
GitHub PR ← review comment posted
```

### MCP Integration (Context7 & GitHub)

The system supports the **Model Context Protocol (MCP)**, allowing agents to use external tools dynamically.
- **GitHub MCP**: Enables the agent to read PR files, fetch comments, and directly push reviews.
- **Context7 MCP**: (Optional) Provides web search and enterprise context retrieval, significantly enhancing the review process by allowing the agent to verify library usage, look up CVEs, or check internal docs.

When `review.mcp-agent.enabled=true`, the webhook can route the PR URL directly to the `McpReviewAgent`, which autonomously uses these MCP tools to perform the review instead of relying solely on the 4 static agents.

### Key Design Decisions

| Decision | Choice | Reason |
|---|---|---|
| AI provider | DeepSeek via Spring AI (OpenAI adapter) | OpenAI-compatible API, Spring-native ChatClient |
| Concurrency | Virtual Threads + `CompletableFuture` | Readable, no reactive learning curve |
| Agent failure | `.exceptionally()` → `AgentResult.empty()` | One agent never aborts the review |
| Prompts | `.txt` files in `resources/prompts/` | Edit without recompiling |
| Token budgets | Agents 1024 / Synthesizer 3000 | Synthesizer needs headroom for all findings |
| Diff truncation | 80k chars max, `isPartial` flag | Prevents silent context overflow |

## Project Structure

```
code-review-orchestrator/
├── build.gradle.kts
├── CLAUDE.md
├── .claude/rules/                # Project rules split by topic
├── src/main/java/org/akj/reviewer/
│   ├── ReviewerApplication.java
│   ├── agent/
│   │   ├── ReviewAgent.java          # Interface
│   │   ├── BaseReviewAgent.java      # Shared logic (Spring AI ChatClient)
│   │   ├── AgentContext.java         # PR data record
│   │   ├── AgentResult.java          # Findings record
│   │   ├── Finding.java / Severity.java / FindingDto.java
│   │   ├── SecurityAgent.java
│   │   ├── PerformanceAgent.java
│   │   ├── StyleAgent.java
│   │   └── TestCoverageAgent.java
│   ├── orchestrator/
│   │   ├── ReviewOrchestrator.java   # Fan-out/fan-in
│   │   └── ReviewPipeline.java       # Entry point + agent config
│   ├── synthesizer/
│   │   └── SynthesizerAgent.java     # Merges + deduplicates findings
│   ├── github/
│   │   ├── GitHubDiffFetcher.java    # Fetches PR diff (80k truncation)
│   │   └── GitHubReviewPoster.java   # Posts review comment
│   ├── webhook/
│   │   └── GitHubWebhookController.java
│   └── config/
│       ├── AiConfig.java             # Model, tokens, executor
│       └── GitHubConfig.java
├── src/main/resources/
│   ├── application.yml
│   └── prompts/                      # Agent system prompts (.txt)
│       ├── security-agent.txt
│       ├── performance-agent.txt
│       ├── style-agent.txt
│       ├── testcoverage-agent.txt
│       └── synthesizer-agent.txt
└── src/test/java/org/akj/reviewer/
    ├── agent/BaseReviewAgentTest.java
    ├── orchestrator/ReviewOrchestratorTest.java
    ├── synthesizer/SynthesizerAgentTest.java
    └── webhook/GitHubWebhookControllerTest.java
```

## Testing

```bash
# Run all tests
./gradlew test

# Run a specific test class
./gradlew test --tests BaseReviewAgentTest

# Run integration tests (requires DEEPSEEK_API_KEY for real AI calls)
./gradlew test --tests ReviewFlowIntegrationTest

# Run with verbose output
./gradlew test --info
```

### Test Matrix

| Test | What it covers | AI calls | GitHub calls |
|---|---|---|---|
| `BaseReviewAgentTest` | ChatClient fluent chain, JSON parsing, error handling | Mocked | None |
| `ReviewOrchestratorTest` | Fan-out collects results, failure isolation | None | None |
| `SynthesizerAgentTest` | Fallback Markdown rendering, dedup, ranking | None | None |
| `GitHubWebhookControllerTest` | HMAC verify, action filtering, org webhooks, repo filtering | None | None |
| `GitHubDiffFetcherTest` | Language detection from file extensions | None | None |
| `ReviewFlowIntegrationTest` | Full pipeline: diff→AgentContext→agents→synthesizer→ReviewResult | Real or empty | None |

### Local E2E (Manual)

#### Post a raw diff directly:
```bash
# Start the app, then POST a diff to /review/local
curl -X POST "http://localhost:8080/review/local" \
  -H "Content-Type: application/json" \
  -d '{
    "diff": "diff --git a/App.java b/App.java\nindex abc..def\n--- a/App.java\n+++ b/App.java\n@@ -1,3 +1,4 @@\n public class App {\n+    String s = System.getenv(\"SECRET\");\n     public void run() {}\n }",
    "files": ["src/main/App.java"],
    "title": "Quick smoke test"
  }'
```

#### Post a GitHub PR URL to trigger a full review:
```bash
# Start the app, then POST the PR URL to /review/url
curl -X POST "http://localhost:8080/review/url" \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://github.com/spring-projects/spring-boot/pull/12345"
  }'
```
This will autonomously fetch the PR, run the review (routing to the MCP agent if enabled), and post the comments directly to the GitHub PR.


## Development

### Tuning Agent Prompts

1. Edit `src/main/resources/prompts/<agent>.txt`
2. Run the unit test for that agent
3. Check `rawReasoning` in the result to understand model behavior
4. Repeat

### Adding a New Agent

1. Create `NewAgent.java` extending `BaseReviewAgent`
2. Mark as `@Component` — the orchestrator auto-discovers via `List<ReviewAgent>` injection
3. Create `src/main/resources/prompts/new-agent.txt` with the system prompt
4. Add unit tests

### Agent Scope Boundaries

| Agent | Owns | Does NOT flag |
|---|---|---|
| Security | OWASP Top 10, secrets, injection, auth bypasses | Style, performance |
| Performance | N+1, O(n²) loops, unnecessary allocations | Security, naming |
| Style | Naming, SOLID violations, design patterns, dead code | Security, perf |
| TestCoverage | Missing tests, untested edge cases | Everything else |
| Synthesizer | Merging + dedup + ranking only | Generating new findings |

## Webhook Security

The webhook endpoint verifies the `X-Hub-Signature-256` header using constant-time comparison (`MessageDigest.isEqual`). Only processes PR events where `action` is `opened`, `synchronize`, or `reopened`. Returns 200 immediately and processes the review asynchronously.

**Supported webhook types:**
- **Repo webhooks** — standard payload structure
- **Org webhooks** — auto-detected from `organization` field in payload

**Repo filtering:** Use `review.monitored-repos` in config to restrict which
repos trigger reviews (supports CSV format, e.g. `"owner/repo1,owner/repo2"`).
Leave empty to allow all repos.

**Health endpoint:** `GET /health` returns service status and uptime.

## Review Comment Format

The review is posted as a GitHub PR Review with:
- **Summary body** — Markdown with severity table (emoji-coded), file-grouped suggestions
- **Inline comments** — Per-finding annotations on specific lines in the diff

Example output:

```
## AI Code Review

### Findings

| Severity | File | Line | Category | Issue |
|---|---|---|---|---|
| 🔴 CRITICAL | src/api/Auth.java | 42 | Security | SQL injection |

### Suggestions
**src/api/Auth.java**
- Use prepared statements instead of string concatenation

---
*Review generated by Code Review Orchestrator*
*Agents: security · performance · style · test-coverage*
```

Additionally, inline comments are posted on the specific lines (e.g. a 🔴 CRITICAL
comment appears directly on line 42 of Auth.java in the PR diff view).

## Environment Variables

| Variable | Purpose | Default / Example |
|---|---|---|
| `DEEPSEEK_API_KEY` | DeepSeek API key | (Required) |
| `GITHUB_TOKEN` | GitHub PAT (repo + pull_requests scopes) | (Required) |
| `GITHUB_WEBHOOK_SECRET`| Webhook HMAC secret | (Optional for local testing) |
| `POLLING_ENABLED` | Set to `true` to enable background API polling monitor | `false` |
| `POLLING_INTERVAL_SECONDS`| Polling interval for background monitor in seconds | `60` |
| `MONITORED_REPOS` | Comma-separated `owner/repo` list for webhook/polling | (Empty = allow all) |
| `MCP_MODEL_NAME` | Override the model for the MCP review agent | `deepseek-chat` |
| `MODEL_NAME` | Override the default model for all agents | `deepseek-chat` |

## License

MIT