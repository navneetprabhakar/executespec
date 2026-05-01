package com.navneet.executespec.optimiser;

import java.util.List;
import java.util.Map;

public record PromptPatterns(

    /** Patterns found, grouped by the prompt file they implicate */
    Map<String, List<String>> patternsByPrompt,

    /** Run IDs that contributed signal */
    List<String> sourceRunIds,

    /** True if any patterns found worth optimising */
    boolean hasPatterns,

    /** Human-readable summary for audit log */
    String summary) {}
