# Unified Review Pipeline Architecture

## Overview

The code review orchestrator uses a single, unified pipeline (`ReviewPipeline`) for all code reviews (whether triggered via GitHub Webhook, background API polling, or manual URL trigger).

The pipeline combines the strengths of GitHub MCP tools (for rich context fetching) with parallel specialist subagents and robust REST fallback:

1. **Step 1: Fetch PR Context** — Attempts to fetch PR title, description, author, diff, and changed files using **GitHub MCP tools** via `src/main/resources/prompts/mcp-fetch-pr.txt`. If MCP tools are unavailable or fail, it automatically falls back to direct REST fetch via `GitHubDiffFetcher`.
2. **Step 2: Parallel Specialist Analysis** — Executes 4 subagents in parallel using Java Virtual Threads via `ReviewOrchestrator`:
   - `SecurityAgent` (`prompts/security-agent.txt`)
   - `PerformanceAgent` (`prompts/performance-agent.txt`)
   - `StyleAgent` (`prompts/style-agent.txt`)
   - `TestCoverageAgent` (`prompts/testcoverage-agent.txt`)
3. **Step 3: Synthesis** — `SynthesizerAgent` deduplicates findings across subagents, ranks them by severity (`CRITICAL` > `HIGH` > `MEDIUM` > `LOW` > `INFO`), and compiles the final GitHub-flavored markdown review.
4. **Step 4: Post Review** — `GitHubReviewPoster` posts the overall summary and inline comments to GitHub via direct REST API with automatic HTTP 422 fallback.

---

## MCP Tool Configuration

### GitHub MCP (stdio transport)

Launched by Spring AI as a subprocess via `npx`:

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          connections:
            github-mcp:
              command: npx
              args: ["-y", "@modelcontextprotocol/server-github"]
              env:
                GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_TOKEN}
```

**Prerequisite**: Node.js ≥ 18 + `npx` must be installed on the host.

### Tool Registration

`McpConfig` collects all `McpSyncClient` beans auto-configured by Spring AI and
wraps them in a `SyncMcpToolCallbackProvider`. The `ReviewPipeline` injects this
provider and uses the tools during the Step 1 fetch phase.

---

## Agent Enablement Flags

Individual specialist subagents can be toggled via `application.yml` or environment variables:

```yaml
review:
  agents:
    enabled:
      security: true
      performance: true
      style: true
      test-coverage: true
```

---

## Polling Monitor

`GitHubApiMonitor` periodically checks configured repositories for open pull requests:

```yaml
review:
  polling:
    enabled: true                 # POLLING_ENABLED=true
    interval-seconds: 60          # POLLING_INTERVAL_SECONDS=60
  monitored-repos: owner/repo1    # MONITORED_REPOS=owner/repo1,owner/repo2
```

The monitor uses `SeenPrTracker` to deduplicate by head commit SHA — a PR is
re-reviewed only when a new commit is pushed to the branch.
