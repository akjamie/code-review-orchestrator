# Testing & Error Handling Checkpoints

Load this file during **Step 5 (Cross-cutting concerns)** when reviewing test code or error handling paths.

---

## Testing

### Coverage & structure

- [ ] Every public method has tests. Every branch (if/else, exception paths) has at least one test.
- [ ] Tests are organized to mirror the source structure (`src/main/java/com/x/Foo.java` -> `src/test/java/com/x/FooTest.java`).
- [ ] Unit tests are fast (< 100ms each) and do not hit external systems (no real DB, no real HTTP).
- [ ] Integration tests are separate from unit tests and tagged/suites appropriately.
- [ ] Test coverage is meaningful, not just line coverage. 100% line coverage with no assertion is worthless.

### Test quality

- [ ] Tests follow AAA: **Arrange** (set up), **Act** (call), **Assert** (verify). This structure is visible in the test.
- [ ] One logical assertion per test. If a test verifies three unrelated things, it should be three tests.
- [ ] Test names describe the scenario: `shouldReturnEmptyWhenUserNotFound`, not `test1` or `testGetUser`.
- [ ] Tests are independent - no shared mutable state, no test ordering dependency. Each test sets up and tears down its own state.
- [ ] Tests are deterministic. No `Thread.sleep()`, no reliance on current date/time (inject a `Clock`), no flaky parallelism.
- [ ] Assertions are specific. `assertNotNull(result)` is weak; `assertEquals(expectedName, result.name())` is strong.

### Mock usage

- [ ] Mocks are used for external dependencies (DB, HTTP, message broker), not for simple value objects or domain logic.
- [ ] Mocks verify interactions only when the interaction itself is the contract. Don't over-specify (verifying every call when only the result matters).
- [ ] No mocking of classes you don't own (use wrappers/adapters instead). Mock interfaces, not concrete classes, where possible.
- [ ] Test data is realistic and minimal. Use builders or factory methods for complex test objects, not 20-argument constructors.

### Edge cases

- [ ] Null inputs tested.
- [ ] Empty collections / empty strings tested.
- [ ] Boundary values tested (0, -1, MAX_VALUE, off-by-one boundaries).
- [ ] Concurrent access tested for shared state.
- [ ] Error/exception paths tested - not just the happy path.

---

## Error Handling

- [ ] Exceptions are caught at the **right level**. Low-level infrastructure exceptions (SQLException, IOException) are wrapped in domain-appropriate exceptions at the service boundary.
- [ ] Error messages help debugging: include context (what was being attempted, what values were involved).
- [ ] No `catch (Exception e) { throw new RuntimeException(e) }` without adding context. Wrap with a meaningful message: `throw new UserNotFoundException("No user with ID " + id, e)`.
- [ ] Logging happens at the catch boundary, not re-logged at every re-throw level (log once at the point where the exception is handled, not propagated).
- [ ] Fail fast: validate preconditions at method entry (`Objects.requireNonNull`, `checkArgument`, `if (x < 0) throw`).
- [ ] No control flow by exception. If you're catching an exception to decide a branch, restructure the code.
- [ ] Finally blocks don't throw. If a `finally` block can throw, it masks the original exception. Use try-with-resources instead.
- [ ] Custom exceptions form a meaningful hierarchy, not a flat list of unrelated exception classes.
