package org.akj.reviewer.eval;

import java.util.ArrayList;
import java.util.List;
import org.akj.reviewer.agent.Finding;

public class FindingMatcher {

    public record MatchResult(
        List<ExpectedFinding> hits,
        List<ExpectedFinding> misses,
        List<Finding> falsePositives
    ) {}

    public static MatchResult match(List<Finding> actualFindings, EvalCase evalCase) {
        List<ExpectedFinding> hits = new ArrayList<>();
        List<ExpectedFinding> misses = new ArrayList<>(evalCase.expectedFindings());
        List<Finding> falsePositives = new ArrayList<>();

        for (Finding actual : actualFindings) {
            int line = actual.lineNumber().orElse(-1);

            ExpectedFinding matchedExpected = misses.stream()
                .filter(expected -> sameFile(expected.file(), actual.filePath()))
                .filter(expected -> categoryMatches(expected.category(), actual.category()))
                .filter(expected -> line == -1 || overlaps(expected.startLine(), expected.endLine(), line))
                .findFirst()
                .orElse(null);

            if (matchedExpected != null) {
                hits.add(matchedExpected);
                misses.remove(matchedExpected);
            } else if (isInSafeRegion(actual, evalCase.safeRegions())) {
                falsePositives.add(actual);
            }
        }
        return new MatchResult(hits, misses, falsePositives);
    }

    public static boolean overlaps(int start, int end, int line) {
        return line >= start && line <= end;
    }

    public static boolean sameFile(String expectedFile, String actualFile) {
        if (expectedFile == null || actualFile == null) return false;
        String normalizedExpected = expectedFile.replace('\\', '/').trim();
        String normalizedActual = actualFile.replace('\\', '/').trim();
        return normalizedActual.endsWith(normalizedExpected) || normalizedExpected.endsWith(normalizedActual);
    }

    public static boolean categoryMatches(String expected, String actual) {
        if (expected == null || actual == null) return false;
        String e = expected.trim().toLowerCase();
        String a = actual.trim().toLowerCase();
        if (e.equals(a)) return true;
        // Normalize common category name variations (e.g. test-coverage vs test_coverage vs tests)
        String eNorm = e.replace("-", "").replace("_", "");
        String aNorm = a.replace("-", "").replace("_", "");
        return eNorm.equals(aNorm);
    }

    public static boolean isInSafeRegion(Finding finding, List<EvalCase.SafeRegion> regions) {
        if (regions == null || regions.isEmpty() || finding.lineNumber().isEmpty()) {
            return false;
        }
        int line = finding.lineNumber().get();
        return regions.stream().anyMatch(r ->
            sameFile(r.file(), finding.filePath()) && overlaps(r.startLine(), r.endLine(), line)
        );
    }
}
