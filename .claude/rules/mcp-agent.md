# MCP Review Agent

## Overview

`McpReviewAgent` is the primary code reviewer. Instead of a fan-out of specialist
agents, it is a **single autonomous agent** that executes a multi-step tool-calling loop:

1. Fetch PR details and diff from GitHub using GitHub MCP tools.
2. Analyze the diff for security, performance, style, and test coverage issues.
3. Query Context7 MCP for up-to-date documentation of libraries detected in the diff.
4. Post a PR review (summary + inline comments) using GitHub MCP tools.

---

## Activation

Enabled by default. Disable to fall back to the classic 4-agent pipeline:

```yaml
review:
  mcp-agent:
    enabled: false   # falls back to Security/Performance/Style/TestCoverage agents
```

---

## MCP Servers

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

Key tools provided:
- `get_pull_request` — fetch PR details
- `get_pull_request_diff` / `get_pull_request_files` — fetch changed files and diff
- `create_pull_request_review` — post review with inline comments

### Context7 MCP (SSE transport)

Connected to the remote context7 service:

```yaml
spring:
  ai:
    mcp:
      client:
        sse:
          connections:
            context7-mcp:
              url: https://mcp.context7.com/mcp
```

Key tools provided:
- `resolve-library-id` — resolve a library name to a Context7 ID
- `get-library-docs` — retrieve version-specific documentation

---

## Tool Registration

`McpConfig` collects all `McpSyncClient` beans auto-configured by Spring AI and
wraps them in a `SyncMcpToolCallbackProvider`. The `McpReviewAgent` injects this
provider and registers the tools on each `ChatClient` call via `.toolCallbacks(mcpTools)`.

```java
ChatClient client = chatClientBuilder
    .defaultSystem(systemPrompt)
    .build();

client.prompt()
    .user(userPrompt)
    .toolCallbacks(mcpTools)   // ← GitHub + Context7 tools
    .options(options)
    .call()
    .content();
```

---

## System Prompt

Located in `resources/prompts/mcp-review-agent.txt`. Edit without recompiling.

The prompt instructs the model to:
- Use GitHub tools to fetch PR data
- Use Context7 tools to validate library/framework API usage
- Post a structured Markdown review with inline line comments

---

## Model Configuration

```yaml
review:
  mcp-agent:
    model: ${MCP_MODEL_NAME:${MODEL_NAME:deepseek-chat}}
    max-tokens: 8192
```

Override with environment variable:
```bash
export MCP_MODEL_NAME=deepseek-chat
```

---

## Polling Monitor

`GitHubApiMonitor` provides a second PR detection path (webhook alternative):

```yaml
review:
  polling:
    enabled: true                 # POLLING_ENABLED=true
    interval-seconds: 60          # POLLING_INTERVAL_SECONDS=60
  monitored-repos: owner/repo1    # MONITORED_REPOS=owner/repo1,owner/repo2
```

The monitor uses `SeenPrTracker` to deduplicate by head commit SHA — a PR is
re-reviewed only when a new commit is pushed to the branch.
