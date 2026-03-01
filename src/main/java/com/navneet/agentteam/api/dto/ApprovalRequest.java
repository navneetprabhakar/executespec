package com.navneet.agentteam.api.dto;

/** Body for plan-approval and merge-approval gate endpoints. */
public record ApprovalRequest(boolean approved, String comment) {}
