---
name: java-review-orchestrator
description: >-
  Master code review orchestrator for Java and Spring Boot pull requests.
  Use when conducting end-to-end pull request reviews, coordinating multi-agent
  specialists (Security, Performance, Style, Test Coverage), deduplicating findings,
  and compiling production-grade GitHub PR review reports.
---

# 🎼 Java Code Review Orchestrator Skill

This skill provides the end-to-end orchestration, fan-out delegation, deduplication, and synthesis workflow for reviewing Java and Spring Boot pull requests.

---

## 📥 Input Contract

```yaml
Input:
  prNumber: Integer (e.g. 101)
  prTitle: String (e.g. "feat: add user authentication and payment processing")
  prAuthor: String (e.g. "developer-a")
  changedFiles: List<String> (e.g. ["src/main/java/.../UserService.java"])
  diffContent: String (Unified diff content)
  isPartial: Boolean (True if diff was truncated due to budget limit)
```

---

## 📤 Output Contract

```markdown
## 🤖 AI Code Review

### Summary
[1-2 sentences summarizing overall code quality, risk assessment, and readiness to merge.]

### Findings Table
| Severity | File | Line | Category | Issue |
|---|---|:---:|---|---|
| 🔴 CRITICAL | `UserService.java` | 25 | security | SQL Injection via raw string concatenation |
| 🟠 HIGH | `OrderService.java` | 32 | performance | N+1 database queries inside stream loop |

### Actionable Suggestions
**`src/main/java/.../UserService.java`**
- **Line 25**: Replace string concatenation with parameterized query:
```java
// Corrected implementation
```
```

---

## 🔄 End-to-End Execution Process Flow

```
┌────────────────────────────────────────────────────────┐
│ Step 1: Ingestion & Language/Module Triage             │
│ • Detect languages, file extensions (.java, .sql, etc.)│
│ • Check diff size: if > 80k chars, set isPartial=true  │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│ Step 2: Specialist Fan-Out (Parallel Delegation)       │
│ • Invoke java-security-reviewer                        │
│ • Invoke java-performance-profiler                     │
│ • Invoke java-clean-code-style                         │
│ • Invoke java-test-coverage-expert                     │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│ Step 3: Synthesis, Deduplication & Ranking             │
│ • Deduplicate overlapping line findings across domains │
│ • Preserve highest severity when conflicts occur       │
│ • Sort: CRITICAL > HIGH > MEDIUM > LOW > INFO          │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│ Step 4: Markdown Compilation & Inline Annotation       │
│ • Render GitHub-flavored markdown summary table        │
│ • Associate each finding with concrete modified line   │
└────────────────────────────────────────────────────────┘
```

---

## 🎯 Conflict Resolution & Deduplication Rules

1. **Category Ownership**: If both Security and Style flag the same line for an SQL issue, Security owns the finding with `CRITICAL/HIGH` severity; the Style finding is merged into the Security suggestion.
2. **Line Resolution**: Findings MUST point to added/modified lines in diff hunks (`+` lines). Unmodified lines are noted in the general summary.
3. **Empty Diff / Clean Code**: If no findings meet `MEDIUM` or higher threshold, explicitly output: `"✅ No critical or high-severity issues found. Code is clean and adheres to standards."`
