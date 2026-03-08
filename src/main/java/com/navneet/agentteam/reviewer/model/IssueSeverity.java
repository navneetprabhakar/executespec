package com.navneet.agentteam.reviewer.model;

public enum IssueSeverity {
    CRITICAL,  // auto-fix attempted via worker
    MINOR      // report only, human decides
}
