# ☕ Modern Java (21 / 25 LTS) & Clean Architecture Standards

This rule establishes the modern Java coding standard and design benchmarks for evaluating code quality and writing production-ready Spring Boot applications.

---

## 💎 Modern Java Idioms (Java 21 / 25)

1. **Immutable Data Carriers**:
   - Use `record` for all DTOs, events, command carriers, and value objects instead of mutable classes or boilerplate Lombok `@Data`.
   - Use Compact Constructors in records for invariant validation:
     ```java
     public record AgentContext(String repo, int prNumber, String diff) {
         public AgentContext {
             Objects.requireNonNull(repo, "repo must not be null");
             diff = diff != null ? diff : "";
         }
     }
     ```

2. **Pattern Matching & Exhaustive Switches**:
   - Prefer Pattern Matching for `switch` and `instanceof` over clumsy casting chains:
     ```java
     return switch (agent.agentName()) {
         case "security" -> securityEnabled;
         case "performance" -> performanceEnabled;
         case "style", "test-coverage" -> true;
         default -> throw new IllegalArgumentException("Unknown agent: " + agent.agentName());
     };
     ```

3. **Virtual Threads & Concurrency**:
   - For I/O-bound fan-out pipelines, use Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).
   - **Avoid Synchronized Blocks Pinning**: Never hold a `synchronized` monitor block around blocking I/O calls or HTTP calls in virtual threads. Use `java.util.concurrent.locks.ReentrantLock` instead.

4. **Sequenced Collections & Modern Collections**:
   - Prefer immutable factories: `List.of()`, `Set.of()`, `Map.of()`.
   - Use `.toList()` instead of `.collect(Collectors.toList())`.

---

## 🏛️ Clean Architecture & Error Handling Rules

1. **No Exception Swallowing**:
   - NEVER catch an exception with an empty block or simply `e.printStackTrace()`.
   - Either handle the exception gracefully, log with context (`log.warn("Failed to process {}: {}", id, e.getMessage())`), or translate it to a typed domain exception.

2. **Resource Management**:
   - All I/O streams, database connections, sockets, and native buffers MUST use `try-with-resources`.

3. **SOLID & Single Responsibility**:
   - Avoid "God Classes". Controllers only route and validate; Services contain business logic; Repositories handle persistence; Agents only analyze their assigned domain.
