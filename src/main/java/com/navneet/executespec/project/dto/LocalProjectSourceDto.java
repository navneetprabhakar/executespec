package com.navneet.executespec.project.dto;

/** Result of an idempotent local-source registration/lease renewal. */
public record LocalProjectSourceDto(boolean created, ProjectRepoLinkDto link) {}
