# Development Workflow

## Day-by-day use

- Keep `testdata/sample-diffs/` stocked with real diffs — use them as fixtures
  for iterating on agent prompts without hitting the API.

---

## Java re-entry notes (coming back from Python)

Things that trip up Python-first thinking:

- **No duck typing** — `ReviewAgent` is an interface; every agent must
  explicitly implement it.
- **Records are your friend** — use them for `AgentContext`, `AgentResult`,
  `Finding`. They are Python dataclasses but immutable and boilerplate-free.
- **`Optional` not `None`** — use `Optional<T>` for values that might be absent
  (e.g. `lineNumber`). Don't return null from methods.
- **`CompletableFuture` not `asyncio`** — the fan-out pattern uses
  `CompletableFuture.supplyAsync()`. With Virtual Threads you don't need to
  think about thread pools the way you used to.
- **Checked exceptions** — Spring AI wraps API exceptions; wrap them in
  `try/catch` inside each agent's `review()` method and return empty
  results gracefully.
- **Gradle Kotlin DSL** — dependencies use `implementation("group:artifact:version")`
  syntax.
- **`application.yml` > environment-specific properties** — Spring Boot 4 still
  supports profiles (`application-dev.yml`), use them for local vs prod config.

---

## Definition of done (end of 2 weeks)

- [ ] Webhook receives GitHub PR events and verifies HMAC signature (constant-time compare)
- [ ] Diff size guard truncates at 80k chars and sets `isPartial` on context
- [ ] All four specialist agents implemented and tested in isolation
- [ ] Fan-out runs agents in parallel via shared `virtualThreadExecutor` bean
- [ ] Per-agent `.orTimeout().exceptionally()` — one agent failure never aborts the review
- [ ] Synthesizer produces a clean, deduplicated Markdown review (3000 token budget)
- [ ] Review posted as GitHub PR Review comment with inline line references
- [ ] Agents can be individually toggled via config
- [ ] Prompt quality validated against 5+ real PR diffs
- [ ] README with setup instructions and architecture diagram