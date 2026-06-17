package org.akj.reviewer.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

record FindingDto(
    @JsonProperty("severity") Severity severity,
    @JsonProperty("category") String category,
    @JsonProperty("filePath") String filePath,
    @JsonProperty("lineNumber") Integer lineNumber,
    @JsonProperty("message") String message,
    @JsonProperty("suggestion") String suggestion
) {}