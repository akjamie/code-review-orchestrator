package org.akj.reviewer.eval;

import org.akj.reviewer.agent.Finding;
import org.akj.reviewer.agent.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FindingMatcherTest {

    @Test
    @DisplayName("FindingMatcher correctly matches findings within line range and category")
    void testFindingMatcherHits() {
        ExpectedFinding expected = new ExpectedFinding(
            "security", "sql_injection", "UserService.java", 20, 30, "CRITICAL"
        );
        EvalCase evalCase = new EvalCase(
            "test-001", "desc", "diff", List.of("UserService.java"),
            List.of(expected), List.of()
        );

        Finding actual = new Finding(
            Severity.CRITICAL, "security", "src/main/java/com/example/service/UserService.java",
            Optional.of(25), "SQL injection vulnerability detected", "Use parameterized queries"
        );

        FindingMatcher.MatchResult match = FindingMatcher.match(List.of(actual), evalCase);

        assertEquals(1, match.hits().size(), "Should have 1 hit");
        assertEquals(0, match.misses().size(), "Should have 0 misses");
        assertEquals(0, match.falsePositives().size(), "Should have 0 false positives");
    }

    @Test
    @DisplayName("FindingMatcher flags findings in SafeRegion as False Positives")
    void testFindingMatcherSafeRegionFalsePositive() {
        EvalCase.SafeRegion safeRegion = new EvalCase.SafeRegion("StringUtils.java", 10, 30);
        EvalCase evalCase = new EvalCase(
            "safe-001", "safe test", "diff", List.of("StringUtils.java"),
            List.of(), List.of(safeRegion)
        );

        Finding falseAlarm = new Finding(
            Severity.HIGH, "performance", "StringUtils.java",
            Optional.of(15), "False alarm message", "No fix needed"
        );

        FindingMatcher.MatchResult match = FindingMatcher.match(List.of(falseAlarm), evalCase);

        assertEquals(0, match.hits().size());
        assertEquals(0, match.misses().size());
        assertEquals(1, match.falsePositives().size(), "Should flag finding in safe region as False Positive");
    }

    @Test
    @DisplayName("EvalReport correctly computes recall, precision, and F1 score")
    void testEvalReportMetrics() {
        ExpectedFinding expected1 = new ExpectedFinding("security", "vuln", "A.java", 1, 10, "HIGH");
        ExpectedFinding expected2 = new ExpectedFinding("performance", "n+1", "B.java", 1, 10, "HIGH");

        // Case 1: 1 hit, 0 misses, 0 FP
        FindingMatcher.MatchResult res1 = new FindingMatcher.MatchResult(
            List.of(expected1), List.of(), List.of()
        );
        // Case 2: 0 hits, 1 miss, 1 FP
        Finding actualFp = new Finding(Severity.LOW, "style", "B.java", Optional.of(5), "msg", "sug");
        FindingMatcher.MatchResult res2 = new FindingMatcher.MatchResult(
            List.of(), List.of(expected2), List.of(actualFp)
        );

        EvalReport report = new EvalReport(List.of(
            new EvalReport.CaseEvaluation("c1", "d1", res1),
            new EvalReport.CaseEvaluation("c2", "d2", res2)
        ));

        // Recall = 1 hit / 2 expected = 0.50
        assertEquals(0.50, report.calculateRecall(), 0.001);
        // Precision = 1 hit / (1 hit + 1 FP) = 0.50
        assertEquals(0.50, report.calculatePrecision(), 0.001);
        // F1 = 0.50
        assertEquals(0.50, report.calculateF1(), 0.001);

        String md = report.toMarkdown();
        assertTrue(md.contains("Recall (召回率)"));
        assertTrue(md.contains("`c1`"));
        assertTrue(md.contains("`c2`"));
    }
}
