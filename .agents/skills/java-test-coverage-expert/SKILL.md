---
name: java-test-coverage-expert
description: >-
  Java unit test coverage and test quality reviewer.
  Use when evaluating code diffs for missing unit tests, weak assertions,
  untested exception paths, Mockito interaction verification, and generating JUnit 5 parameterized test fixtures.
---

# 🧪 Java Test Coverage & Test Quality Expert Skill

This skill analyzes pull requests for test completeness, assertion strength, and generates idiomatic JUnit 5 + Mockito test suites for new logic.

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
    "category": "test-coverage",
    "filePath": "src/main/java/com/example/billing/TieredDiscountCalculator.java",
    "lineNumber": 5,
    "message": "Brand new business class introduced without corresponding unit tests covering tier boundaries and zero/negative inputs.",
    "suggestion": "Create `TieredDiscountCalculatorTest.java` using JUnit 5 `@ParameterizedTest` to cover boundary cases (0, 2, 5+ loyalty years)."
  }
]
```

---

## 🔍 Test Quality & Coverage Checklist

### 1. Missing Test Classes
- **Rule**: If a new service, calculator, or business domain class is introduced in `src/main/java/`, a corresponding test class in `src/test/java/` MUST be part of the PR diff.

### 2. Assertion Quality ("Don't test just the happy path")
- ❌ **Weak Test**:
  ```java
  @Test
  void testCalculate() {
      var res = service.calculate(100);
      assertNotNull(res); // Doesn't verify calculation correctness
  }
  ```
- ✅ **Strong Test**:
  ```java
  @ParameterizedTest
  @CsvSource({
      "100.0, 5, 20.0",
      "100.0, 2, 10.0",
      "100.0, 0, 0.0",
      "0.0,   5, 0.0"
  })
  void testTieredDiscounts(BigDecimal amount, int years, BigDecimal expected) {
      assertEquals(expected, calculator.calculateDiscount(amount, years));
  }
  ```

### 3. Exception & Failure Path Testing
- Ensure error scenarios (e.g. invalid inputs, database downtime, external API timeouts) are explicitly tested using `assertThrows(ExpectedException.class, () -> ...)`.

### 4. Mockito Verification
- Verify critical side-effects (e.g. audit logs, event publishers, notification senders) using `verify(publisher, times(1)).publishEvent(...)`.
