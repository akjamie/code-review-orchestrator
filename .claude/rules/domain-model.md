# Core Domain Model

All domain types are Java 25 records — immutable, boilerplate-free.

## AgentContext

Everything an agent needs to know about a PR.

```java
public record AgentContext(
    String repoFullName,      // "owner/repo"
    int prNumber,
    String prTitle,
    String prDescription,
    String diffContent,       // raw unified diff (may be truncated — see isPartial)
    List<String> changedFiles,
    boolean isPartial         // true when diff was truncated due to size; agents must acknowledge this
) {}
```

## AgentResult

What an agent returns after review.

```java
public record AgentResult(
    String agentName,
    List<Finding> findings,
    String rawReasoning        // keep for debugging prompts
) {
    /** Return this when an agent times out or throws — never propagate the exception. */
    public static AgentResult empty(String agentName, Throwable cause) {
        return new AgentResult(agentName, List.of(),
            "Agent failed: " + cause.getMessage());
    }
}
```

## Finding

A single review issue found by an agent.

```java
public record Finding(
    Severity severity,          // CRITICAL, HIGH, MEDIUM, LOW, INFO
    String category,            // "security" | "performance" | "style" | "test-coverage"
    String filePath,
    Optional<Integer> lineNumber, // empty when finding is file-level, not line-level
    String message,
    String suggestion           // concrete fix suggestion
) {}
```

## Severity

```java
public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }
```

---

## `Optional` in records

Java serialisation libraries (Jackson, etc.) need `@JsonSerialize` / a custom
module to handle `Optional` fields correctly. Register `Jdk8Module` on your
`ObjectMapper` bean. If you find this awkward, an alternative is
`@Nullable Integer lineNumber` with a `withNoLine()` factory — pick one pattern
and use it everywhere.