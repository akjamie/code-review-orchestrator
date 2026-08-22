package org.akj.reviewer.eval;

import java.util.List;

public record EvalCase(
    String caseId,
    String description,
    String diff,
    List<String> files,
    List<ExpectedFinding> expectedFindings,
    List<SafeRegion> safeRegions
) {
    public EvalCase {
        files = files != null ? files : List.of();
        expectedFindings = expectedFindings != null ? expectedFindings : List.of();
        safeRegions = safeRegions != null ? safeRegions : List.of();
    }

    public record SafeRegion(String file, int startLine, int endLine) {}
}
