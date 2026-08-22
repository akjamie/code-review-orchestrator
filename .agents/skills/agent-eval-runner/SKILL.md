---
name: agent-eval-runner
description: >-
  Evaluation suite execution, benchmark regression analysis, and red-teaming runner.
  Use when creating benchmark cases (input.diff + expected.json), running eval suites,
  computing Recall/Precision/F1 metrics, inspecting build/eval/eval_report.md, and validating OWASP ASI 2026 defenses.
---

# 📊 Agent Evaluation & Red-Teaming Benchmark Runner Skill

This skill manages the continuous evaluation lifecycle, benchmark dataset creation, mathematical fuzzy matching, and regression detection for AI code review agents.

---

## 📥 Input Contract

- `benchmarkSuitePath`: Directory path containing benchmark cases (`src/test/resources/eval/cases`).
- `redTeamSuitePath`: Directory path containing red-teaming adversarial cases (`src/test/resources/eval/prompt-injection`).
- `minRecallThreshold`: Minimum acceptable recall percentage (e.g. `0.70`).
- `minPrecisionThreshold`: Minimum acceptable precision percentage (e.g. `0.60`).

---

## 📤 Output Contract

- Path to generated report: `build/eval/eval_report.md`
- Aggregated metrics: Recall %, Precision %, F1-Score, False Positives in safe regions, Case-by-case hits & misses.

---

## 🔄 Benchmark Case Creation Guide

When adding a new evaluation benchmark case:

1. **Create Directory**: `src/test/resources/eval/cases/<case-id>/`
2. **Add `input.diff`**: Minimal unified diff reproducing the defect.
3. **Add `expected.json`**:
   ```json
   {
     "caseId": "sql-injection-001",
     "description": "Unescaped string concatenation in SQL query",
     "files": ["src/main/java/com/example/service/UserService.java"],
     "expectedFindings": [
       {
         "category": "security",
         "type": "sql_injection",
         "file": "UserService.java",
         "startLine": 20,
         "endLine": 30,
         "severity": "CRITICAL"
       }
     ],
     "safeRegions": []
   }
   ```
4. **Execute Benchmark Test**:
   ```bash
   ./gradlew test --tests "org.akj.reviewer.eval.EvalSuiteTest"
   ```
5. **Inspect Markdown Report**: Check [build/eval/eval_report.md](file:///d:/workbench/ai-agent/code-review-orchestrator/build/eval/eval_report.md).

---

## 🎯 Mathematical Matching Formula

$$\text{Hit} \iff \text{Category Match} \land \text{Normalized File Suffix Match} \land (\text{Expected.StartLine} \le \text{Actual.Line} \le \text{Expected.EndLine})$$

- **Safe Region FP**: If an agent reports a finding located in an explicitly declared `safeRegions` block, it is counted as a **False Positive (FP)**, reducing Precision.
- **Unscored Regions**: Findings reported outside both expected and safe regions are left unscored to avoid penalizing valid unexpected improvements.
