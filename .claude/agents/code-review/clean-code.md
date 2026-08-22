# Clean Code & Java Best Practices Checkpoints

Load this file during **Step 4 (Implementation pass)** of the review workflow.

---

## Clean Code

### Naming

- [ ] Names reveal **intent**. `d` is meaningless; `daysSinceLastLogin` tells a story.
- [ ] No disinformation. A name like `accountList` must actually be a `List`; otherwise use `accounts`.
- [ ] No encoding schemes or Hungarian notation (`strName`, `intCount`). Modern IDEs show types.
- [ ] Method names are verbs or verb phrases (`calculateTotal`, `isValid`). Class names are nouns. Boolean accessors use `is`/`has`/`can` (`isConnected`, `hasPermission`).
- [ ] No single-letter names except loop indices in very short scopes (`i`, `j` in a 3-line loop).
- [ ] No abbreviations that aren't domain-standard. `Url` is fine; `Cfg` is not.
- [ ] Names are consistent across the codebase. If the codebase says `fetch`, don't introduce `get` for the same concept.

### Functions

- [ ] Functions are **small**. A function longer than ~20-30 lines is a candidate for extraction. A function longer than 50 lines is a red flag.
- [ ] Functions do **one thing**. Can you describe it without using "and"?
- [ ] Few arguments. 0-2 is ideal, 3 is acceptable, 4+ needs justification. Boolean flag arguments usually mean the function does two things - split it.
- [ ] No side effects. A function named `checkPassword` should not also initialize a session as a side effect.
- [ ] No output arguments. If a function modifies its argument, it should return a new value instead (favor immutability).
- [ ] Command-query separation. A function either **does** something (command) or **answers** something (query), not both.
- [ ] Nesting depth <= 3. Deep nesting is a sign the logic should be extracted or restructured (guard clauses, early returns).

### Comments

- [ ] Code is **self-documenting**. A comment that explains *what* the code does is a failure - the code should explain itself. Rename the variable or extract a method instead.
- [ ] Comments that explain *why* (business rationale, workaround for a framework bug, non-obvious algorithm choice) are valuable and encouraged.
- [ ] No dead comments - commented-out code, TODO without owner/date, or stale comments that no longer match the code.
- [ ] No redundant Javadoc that just restates the method name (`/** Gets the name. */ String getName()`). Javadoc should add information: parameter constraints, return value semantics, exceptions thrown, thread-safety guarantees.

### General

- [ ] No dead code - unreachable methods, unused imports, unused private fields, commented-out blocks.
- [ ] No magic numbers. Extract named constants (`MAX_RETRY_ATTEMPTS = 3`, not `if (attempts < 3)`).
- [ ] No magic strings. Use enums or constants for repeated string literals.
- [ ] DRY is respected - but don't over-abstract. Three lines duplicated twice is not a DRY violation; it's coincidence. Only extract when the duplication represents a shared concept.
- [ ] KISS - the simplest correct solution is preferred. Don't add design patterns speculatively.
- [ ] Boy Scout rule - the code you touch is cleaner than what you found.

---

## Java Best Practices

### Immutability

- [ ] Use `record` for immutable data carriers (Java 16+). Prefer records over classes with final fields + constructor + accessors.
- [ ] Use immutable collections: `List.of()`, `Set.of()`, `Map.of()`, `Collections.unmodifiableList()`. Never expose a mutable internal collection via a getter.
- [ ] Don't return mutable fields from getters. Return defensive copies for arrays and mutable types.
- [ ] Favor immutability for thread safety. Immutable objects are inherently thread-safe.

### Null handling

- [ ] Never return `null` for collections - return empty collections (`List.of()`, `Collections.emptyList()`).
- [ ] Use `Optional<T>` as a return type to signal "value may be absent." Never use `Optional` as a field type or method parameter.
- [ ] No `Optional.get()` without `isPresent()` check. Prefer `orElse()`, `orElseThrow()`, `orElseGet()`, `map()`, `ifPresent()`.
- [ ] Don't pass `null` as an argument. If a parameter is optional, use overloading or `Optional`.
- [ ] Validate non-null parameters early with `Objects.requireNonNull(param, "param must not be null")` - fail fast.
- [ ] Annotate intent with `@Nullable` / `@NonNull` (JSR-305, JSpecify, or Spring's annotations) when the type system alone is ambiguous.

### Exception handling

- [ ] Catch the **most specific** exception type. `catch (Exception e)` or `catch (Throwable t)` is a code smell unless it's a top-level boundary (e.g., a task executor).
- [ ] Never swallow exceptions (`catch (Exception e) {}`). At minimum, log it.
- [ ] Don't catch and ignore - if you can't handle it, let it propagate or wrap it in a domain-appropriate exception.
- [ ] Use try-with-resources for all `AutoCloseable` resources (streams, connections, readers, writers). Never manual `finally { close() }`.
- [ ] Exception messages are informative: include the values that caused the failure (`"Invalid user ID: " + userId`, not just `"Invalid input"`).
- [ ] Custom exceptions extend the right base class: `RuntimeException` for programming errors, checked exceptions for recoverable conditions (and prefer unchecked in modern Java).
- [ ] Don't use exceptions for control flow. Don't catch `NumberFormatException` to check if a string is numeric - use a validation method.
- [ ] Preserve the cause chain when wrapping: `throw new DomainException("msg", originalException)`.

### Resource management

- [ ] All I/O resources (InputStream, OutputStream, Reader, Writer, Connection, PreparedStatement, ResultSet) are closed via try-with-resources.
- [ ] No resource leaks in error paths. Try-with-resources handles this; manual cleanup often doesn't.
- [ ] HTTP clients, database connections, and thread pools are created once and reused, not per-request.

### Collections

- [ ] Use the right collection type: `List` for ordered, `Set` for unique, `Map` for key-value. Don't use `List` when you need uniqueness.
- [ ] Specify initial capacity for large collections when the size is known (`new ArrayList<>(expectedSize)`).
- [ ] Use `EnumSet` / `EnumMap` for enum-based collections (more efficient than `HashSet`/`HashMap`).
- [ ] Don't use raw types (`List` instead of `List<String>`). Use `List<?>` if the type is unknown.
- [ ] Use `Collections.unmodifiable*` or immutable factory methods when returning collections that shouldn't be modified by the caller.

### String handling

- [ ] Use `StringBuilder` (or `StringJoiner`) for string concatenation in loops. `+` in a loop creates N intermediate strings.
- [ ] Use `String.format()` or text blocks for readability with complex strings.
- [ ] Use `String.isEmpty()` or `String.isBlank()` instead of `str.length() == 0` or `str.trim().isEmpty()`.
- [ ] Compare strings with `equals()`, never `==`. For case-insensitive, use `equalsIgnoreCase()`.
- [ ] Use `Locale` with `String.format()`, `toLowerCase()`, `toUpperCase()` to avoid locale-specific bugs in server environments.

### Equals / hashCode / compareTo

- [ ] If you override `equals`, you must override `hashCode`. Records do this automatically.
- [ ] `equals` is symmetric, transitive, reflexive, and consistent.
- [ ] `compareTo` is consistent with `equals` (or document the inconsistency).
- [ ] Use `Objects.equals()` and `Objects.hash()` to reduce boilerplate and null-safety bugs.

### Modern Java features (17-25)

- [ ] **Records** for immutable data carriers instead of Lombok `@Value` or hand-written immutable classes.
- [ ] **Sealed classes/interfaces** for closed type hierarchies where all subtypes are known. Enables exhaustive pattern matching.
- [ ] **Pattern matching for `instanceof`**: `if (obj instanceof String s)` instead of cast.
- [ ] **Pattern matching for `switch`**: use type patterns and exhaustive `case` labels with `when` guards.
- [ ] **Switch expressions** (`->` syntax) instead of switch statements with fall-through and `break`.
- [ ] **Text blocks** (`"""..."""`) for multi-line strings, SQL, JSON templates.
- [ ] **`var`** for local variables where the type is obvious. Use explicit types where it aids readability.
- [ ] **Virtual threads** (`Thread.ofVirtual()`, `Executors.newVirtualThreadPerTaskExecutor()`) for I/O-bound concurrency instead of platform thread pools. Don't use virtual threads for CPU-bound work or inside `synchronized` blocks (pinning).
- [ ] **Stream API** used idiomatically: no side effects in lambdas, no massive chains that are hard to debug, prefer `collect` over manual accumulation.
