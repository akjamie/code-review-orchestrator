package org.akj.reviewer.agent;

import java.util.Optional;

public record Finding(
    Severity severity,
    String category,
    String filePath,
    Optional<Integer> lineNumber,
    String message,
    String suggestion
) {
    public Finding {
        lineNumber = lineNumber != null ? lineNumber : Optional.empty();
    }
}