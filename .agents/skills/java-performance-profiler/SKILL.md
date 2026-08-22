---
name: java-performance-profiler
description: >-
  Specialist Java & JVM performance optimization and resource leak reviewer.
  Use when analyzing code diffs for database N+1 queries, unclosed I/O streams,
  virtual thread carrier pinning, excessive memory allocations in loops, and algorithmic complexity.
---

# ⚡ Java Performance Profiler & Resource Optimization Skill

This skill analyzes Java code diffs for database throughput bottlenecks, I/O leaks, memory churn, and concurrency contention.

---

## 📥 Input Contract

- `diffContent`: Unified diff string.
- `changedFiles`: List of changed files.
- `detectedLanguages`: Languages detected.

---

## 📤 Output Contract (JSON Array)

```json
[
  {
    "severity": "HIGH",
    "category": "performance",
    "filePath": "src/main/java/com/example/service/OrderService.java",
    "lineNumber": 32,
    "message": "N+1 database query: executing repository.findById() inside a stream loop causes excessive network roundtrips.",
    "suggestion": "Batch fetch all records outside the loop using `repository.findAllById(customerIds)` and map in-memory."
  }
]
```

---

## 🔍 Performance Checklist & Anti-Patterns

### 1. Database & ORM Bottlenecks (N+1 Queries)
- ❌ **Anti-Pattern**:
  ```java
  List<Order> orders = orderRepo.findAll();
  orders.forEach(o -> customerRepo.findById(o.getCustomerId())); // N+1 queries
  ```
- ✅ **Optimized**:
  ```java
  List<Long> customerIds = orders.stream().map(Order::getCustomerId).toList();
  Map<Long, Customer> customerMap = customerRepo.findAllById(customerIds).stream()
      .collect(Collectors.toMap(Customer::getId, Function.identity()));
  ```

### 2. Resource Management & Stream Leaks
- ❌ **Anti-Pattern**:
  ```java
  FileInputStream fis = new FileInputStream(file);
  // Unclosed stream if exception thrown
  ```
- ✅ **Optimized**:
  ```java
  try (var fis = new FileInputStream(file)) {
      // Safely auto-closed
  }
  ```

### 3. Loop Concatenation & Garbage Churn
- ❌ **Anti-Pattern**: `String s = ""; for (var item : list) s += item;` (Creates $O(N^2)$ byte array allocations).
- ✅ **Optimized**: Use `StringBuilder` or `String.join(",", list)`.

### 4. Virtual Thread Lock Pinning (Java 21+)
- ❌ **Anti-Pattern**: `synchronized (this) { httpClient.send(...); }` (Pins underlying carrier thread during blocking I/O).
- ✅ **Optimized**: Use `ReentrantLock` or non-blocking constructs.
