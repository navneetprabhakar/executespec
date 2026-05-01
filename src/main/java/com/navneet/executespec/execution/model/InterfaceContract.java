package com.navneet.executespec.execution.model;

import java.util.List;

/**
 * Extracted interface contract from a completed solution — consumed by downstream solutions as
 * handover context.
 */
public record InterfaceContract(
    List<String> restEndpoints,
    List<String> publicTypes,
    List<String> configChanges,
    String rawMarkdown) {}
