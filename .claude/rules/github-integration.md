# GitHub Integration

## Diff size guard

Large PRs can exceed the model's context window silently. Guard at the
`GitHubDiffFetcher` level before building `AgentContext`:

```java
private static final int MAX_DIFF_CHARS = 80_000;

AgentContext buildContext(..., String rawDiff) {
    boolean isPartial = rawDiff.length() > MAX_DIFF_CHARS;
    String diff = isPartial ? rawDiff.substring(0, MAX_DIFF_CHARS) : rawDiff;
    return new AgentContext(..., diff, changedFiles, isPartial, languages);
}
```

When `isPartial` is true, append to every agent's user prompt:
`"NOTE: This diff has been truncated. Flag any findings as potentially
incomplete and do not draw conclusions about unchanged files."`

## Language detection

`GitHubDiffFetcher` detects languages from file extensions before building the
context. Detected languages are injected into both the `AgentContext` and each
agent's user prompt, enabling language-specific analysis (e.g. Java N+1 via
Hibernate vs Python N+1 via Django ORM).

See the extension→language map in `GitHubDiffFetcher.EXTENSION_MAP`.

---

## GitHub webhook setup

1. The webhook endpoint is `POST /webhook/github`.
2. **Supports both repo-level and org-level webhooks.** The controller
   auto-detects the payload structure.
3. **HMAC signature verification** using constant-time `MessageDigest.isEqual`.
4. Only processes PR events where `action` is `opened`, `synchronize`, or
   `reopened`. All other events return 200 OK immediately.
5. **Repo filtering.** Set `review.monitored-repos` in config to restrict which
   repos trigger reviews. Leave empty to allow all repos.
6. Returns 200 immediately, processes the review asynchronously via
   `CompletableFuture.runAsync`.

---

## GitHub review comment format

Post as a PR Review using the `/pulls/{pr}/reviews` endpoint. The poster
includes:

- **Summary body** — Markdown from the synthesizer
- **Inline comments** — Per-finding annotations on specific lines (uses the
  `comments` array in the GitHub API)

Example payload:

```json
{
  "body": "## AI Code Review\n\n...",
  "event": "COMMENT",
  "comments": [
    {
      "path": "src/main/Auth.java",
      "line": 42,
      "body": "🔴 CRITICAL: SQL injection vulnerability\n\n💡 Use prepared statements"
    }
  ]
}
```