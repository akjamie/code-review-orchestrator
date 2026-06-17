# Prompts & Spring AI SDK

## Spring AI usage (OpenAI adapter → DeepSeek)

DeepSeek offers an OpenAI-compatible API. Spring AI's OpenAI adapter talks to
it transparently — just point `spring.ai.openai.base-url` at DeepSeek's endpoint.

Auto-configuration (from `spring-ai-openai-spring-boot-starter`) provides a
`ChatClient` bean. Each agent injects it to make calls:

```java
String response = chatClient.prompt()
    .system(loadSystemPrompt())    // loaded from resources/prompts/
    .user(buildUserPrompt(ctx))
    .options(OpenAiChatOptions.builder()
        .withModel("deepseek-chat")
        .withMaxTokens(1024)
        .build())
    .call()
    .content();
```

System prompts live in `resources/prompts/` as plain `.txt` files so they can
be edited and tuned without touching Java code. Always load them with
`ClassPathResource` — never `ResourceUtils.getFile()`, which fails in fat-jar
deployments because there is no filesystem path inside a JAR:

```java
String loadSystemPrompt(String filename) {
    var resource = new ClassPathResource("prompts/" + filename);
    return resource.getContentAsString(StandardCharsets.UTF_8);
}
```

---

## Prompt design rules

- Each agent's system prompt states its role in one sentence, then lists
  exactly what it should and should not flag (scope boundaries prevent overlap).
- Every system prompt ends with this JSON instruction — the schema must exactly
  match the `Finding` record fields (keep these in sync if the record changes):

  ```
  Return ONLY a valid JSON array, no prose, no markdown fences. Each element:
  {
    "severity": "CRITICAL|HIGH|MEDIUM|LOW|INFO",
    "category": "security|performance|style|test-coverage",
    "filePath": "path/to/File.java",
    "lineNumber": 42,        // use null if finding is not line-specific
    "message": "concise description of the issue",
    "suggestion": "concrete fix or recommended change"
  }
  If you find nothing, return an empty array: []
  ```

- Parse the JSON response in each agent with Jackson; if parsing fails, log the
  raw response and return `AgentResult.empty(...)`.
- The synthesizer prompt receives all agent findings as a JSON array and returns
  a single merged Markdown review comment (not JSON).

---

## Prompt iteration loop

1. Edit the `.txt` file in `resources/prompts/`.
2. Run the unit test for that agent against a sample diff.
3. Inspect `rawReasoning` in the result to understand what the model did.
4. Repeat until quality is satisfying.