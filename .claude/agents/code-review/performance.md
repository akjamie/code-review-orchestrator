# Performance Checkpoints

Load this file during **Step 5 (Cross-cutting concerns)** when the change touches database access, loops over collections, I/O, or hot paths.

---

## Algorithmic complexity

- [ ] Check the Big-O of hot paths. An O(n^2) loop inside a request handler will not scale.
- [ ] Nested loops over collections - verify the inner loop can't be replaced with a `Map`/`Set` lookup (O(1) vs O(n)).
- [ ] `List.contains()` in a loop is O(n*m). Use a `Set` for membership checks.

## Database

- [ ] No N+1 queries. If you fetch a list of entities and then access a lazy relation in a loop, that's N+1. Use `JOIN FETCH` or batch loading.
- [ ] Queries are paginated. No `SELECT *` without `LIMIT` on unbounded tables.
- [ ] Indexes exist for columns used in `WHERE`, `JOIN`, and `ORDER BY` clauses.
- [ ] Transactions are as short as possible. Don't hold a DB transaction open during an HTTP call or external API call.
- [ ] Batch inserts/updates for bulk operations. Don't insert 1000 rows one at a time.

## Memory

- [ ] No unnecessary object creation in hot paths. Don't create new objects inside tight loops if they can be reused.
- [ ] No loading entire collections into memory when streaming would suffice. Use `Stream<T>` or pagination.
- [ ] No retaining references that prevent GC (memory leaks in caches, static collections, listeners not removed).
- [ ] Large byte arrays / strings are not duplicated unnecessarily. Use streaming APIs.

## I/O

- [ ] Use buffered I/O (`BufferedReader`, `BufferedInputStream`) for file/network reads. Unbuffered byte-at-a-time reads are catastrophically slow.
- [ ] No blocking I/O calls inside request-handling threads without a timeout. A hung external call should not hang the thread forever.
- [ ] HTTP clients have connection pooling enabled. Don't create a new connection per request.
- [ ] External calls have reasonable timeouts (connect, read). No infinite waits.

## Stream API

- [ ] No `parallelStream()` on small collections or I/O-bound tasks. Parallel streams use the common ForkJoinPool - blocking I/O there starves other parallel operations.
- [ ] No side effects in stream lambdas (mutating external state, I/O). Streams should be pure transformations.
- [ ] No deeply nested stream chains that are impossible to debug. Extract intermediate operations into named methods if a chain exceeds ~5 operations.
