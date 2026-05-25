package com.navneet.executespec.auth.dto;

/**
 * Project settings PATCH body. RL-2026-05-038 — the settings form sends name / description /
 * targetBranch / branchNamingPattern too; they were silently dropped because this record carried
 * only aiProvider + githubOwner (Jackson ignores unknown JSON properties). All fields optional —
 * null means "not provided, leave unchanged".
 */
public record UpdateProjectSettingsRequest(
    String aiProvider,
    String githubOwner,
    String name,
    String description,
    String targetBranch,
    String branchNamingPattern) {}
