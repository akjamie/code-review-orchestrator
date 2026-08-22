package org.akj.reviewer.eval;

public record ExpectedFinding(
    String category,
    String type,
    String file,
    int startLine,
    int endLine,
    String severity
) {}
