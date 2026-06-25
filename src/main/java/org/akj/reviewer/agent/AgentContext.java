package org.akj.reviewer.agent;

import java.util.List;
import java.util.Set;

public record AgentContext(
    String repoFullName,
    int prNumber,
    String prTitle,
    String prDescription,
    String prAuthor,
    String diffContent,
    List<String> changedFiles,
    boolean isPartial,
    Set<String> detectedLanguages
) {}