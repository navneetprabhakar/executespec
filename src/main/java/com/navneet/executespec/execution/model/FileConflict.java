package com.navneet.executespec.execution.model;

import java.util.List;

public record FileConflict(
    String file, String repoName, List<Integer> solutions, String suggestedResolution) {}
