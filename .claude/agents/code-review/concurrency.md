# Concurrency Checkpoints

Load this file during **Step 5 (Cross-cutting concerns)** when the change touches shared state, threading, `CompletableFuture`, executors, or reactive code.

---

## Thread safety

- [ ] Shared mutable state is either avoided (immutability, thread-local) or properly synchronized.
- [ ] `synchronized` blocks are as small as possible. Don't synchronize entire methods when only a few lines need it.
- [ ] Use `java.util.concurrent` collections (`ConcurrentHashMap`, `CopyOnWriteArrayList`, `BlockingQueue`) instead of `Collections.synchronized*` wrappers.
- [ ] Use `AtomicInteger`, `AtomicLong`, `AtomicReference` for atomic counters/references instead of `synchronized`.
- [ ] `volatile` is used correctly (visibility, not atomicity). `volatile` does not make `i++` thread-safe.
- [ ] No race conditions: check-then-act sequences (`if (map.get(key) == null) map.put(key, value)`) must be atomic. Use `computeIfAbsent` or synchronization.

## Deadlocks

- [ ] Lock ordering is consistent across all code paths. If A locks then B, no path locks B then A.
- [ ] No holding locks while making external calls (HTTP, DB, file I/O). This is both a deadlock and performance risk.
- [ ] `tryLock()` with timeout is preferred over indefinite `lock()` where deadlock is possible.

## Virtual threads (Java 21+)

- [ ] Virtual threads are used for I/O-bound concurrency, not CPU-bound work.
- [ ] No `synchronized` blocks that hold a virtual thread pinned to a carrier thread for long durations. Use `ReentrantLock` instead of `synchronized` in hot paths under virtual threads.
- [ ] No `ThreadLocal` abuse with virtual threads - millions of virtual threads with `ThreadLocal` cause memory issues. Prefer `ScopedValue` (Java 21+ preview).
- [ ] Virtual thread executors are created as shared beans with `destroyMethod = "shutdown"`, not created per-request.

## CompletableFuture

- [ ] `.join()` is safe - either preceded by `.exceptionally()` (which converts failures to a result) or wrapped in try/catch for `CompletionException`.
- [ ] Timeouts are set with `.orTimeout()` or `.completeOnTimeout()`. No indefinite waits.
- [ ] No blocking `.join()` / `.get()` inside a `supplyAsync` lambda (nested blocking defeats parallelism).
- [ ] Thread pools are injected, not created inline. CPU-bound tasks use a sized pool; I/O-bound tasks use virtual threads.

## Reactive (if applicable)

- [ ] No mixing blocking calls inside reactive chains (`.map(x -> blockingCall(x))` blocks the reactive thread).
- [ ] Subscriptions are not leaked. `Disposable` references are disposed when no longer needed.
- [ ] Backpressure is handled for streaming sources.
