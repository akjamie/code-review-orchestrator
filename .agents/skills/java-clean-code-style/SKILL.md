---
name: java-clean-code-style
description: >-
  Clean Code, SOLID design, and Modern Java 21+ architecture reviewer.
  Use when analyzing code diffs for naming clarity, exception swallowing,
  immutability (Records vs POJOs), pattern matching, API design, and maintainability.
---

# 🎨 Java Clean Code & Architecture Style Skill

This skill analyzes Java code diffs to ensure adherence to clean architecture principles, modern Java idioms, and maintainability standards.

---

## 📥 Input Contract

- `diffContent`: Unified diff string.
- `changedFiles`: List of changed files.

---

## 📤 Output Contract (JSON Array)

```json
[
  {
    "severity": "HIGH",
    "category": "style",
    "filePath": "src/main/java/com/example/payment/PaymentProcessor.java",
    "lineNumber": 28,
    "message": "Empty catch block silently swallows critical payment exception without logging or recovery.",
    "suggestion": "Log the exception with transaction context or rethrow a custom PaymentProcessingException:\n`log.error(\"Payment failed for tx {}: {}\", txId, e.getMessage(), e);`"
  }
]
```

---

## 🔍 Style & Clean Code Review Checklist

### 1. Robust Exception Handling
- **Rule**: Never swallow exceptions with empty `catch` blocks or bare `e.printStackTrace()`.
- **Action**: Always log with context variables or propagate typed runtime exceptions.

### 2. Immutability & Modern Data Carriers (Java 21+)
- ❌ **Anti-Pattern**:
  ```java
  public class UserDto {
      private Long id;
      private String username;
      public Long getId() { return id; }
      public void setId(Long id) { this.id = id; }
  }
  ```
- ✅ **Clean Code**:
  ```java
  public record UserDto(Long id, String username) {}
  ```

### 3. SOLID & Separation of Concerns
- **Single Responsibility Principle (SRP)**: Methods should do one thing well. Break methods with > 4 levels of nesting or > 50 lines into well-named private helper methods.
- **Dependency Inversion**: Inject interfaces or Spring Beans via constructor injection, never via `@Autowired` field injection or static singletons.

### 4. Null Safety & Optional
- Avoid returning `null` collections (return `List.of()` instead).
- Never call `.get()` on an `Optional` without checking `.isPresent()` or using `.orElseThrow()`.
