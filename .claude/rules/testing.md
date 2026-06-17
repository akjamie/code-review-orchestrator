# Testing Strategy

| Test type | What it covers | Location |
|---|---|---|
| Unit | Each agent in isolation with a mocked `ChatClient` | `agent/` |
| Unit | `ReviewOrchestrator` timeout and failure isolation | `orchestrator/` |
| Unit | Synthesizer dedup logic | `synthesizer/` |
| Unit | Webhook HMAC signature verification | `webhook/` |
| Integration | Full fan-out flow with real (test) API key | `integration/` |
| Manual | Prompt quality on real PRs | Run app locally, open a draft PR |

Use `@SpringBootTest` sparingly — only for the integration flow test.
Keep unit tests fast and dependency-free.

---

## Loading sample diffs in tests

Put diff files under `src/test/resources/diffs/` so they are on the test
classpath, then load with:

```java
String diff = new ClassPathResource("diffs/sql-injection-vuln.diff")
    .getContentAsString(StandardCharsets.UTF_8);
```

The `testdata/sample-diffs/` directory at the repo root is for human reference
and prompt iteration outside the test suite.