package org.akj.reviewer.synthesizer;

import java.util.List;
import org.akj.reviewer.agent.Finding;

public record ReviewResult(
    String markdownBody,
    List<Finding> findings
) {}