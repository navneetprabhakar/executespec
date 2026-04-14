package com.navneet.executespec.project.controller;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.dto.CreateRepoRequest;
import com.navneet.executespec.project.dto.ProjectRepoDto;
import com.navneet.executespec.project.dto.ProjectRepoLinkDto;
import com.navneet.executespec.project.dto.UpdateRelationshipsRequest;
import com.navneet.executespec.project.service.ProjectRepoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Repo lifecycle at {@code /api/repos} (scope-owned: ORG or USER), separate
 * from project linking at {@code /api/projects/{id}/repos/{repoId}}.
 */
@RestController
@RequiredArgsConstructor
public class ProjectRepoController {

    private final ProjectRepoService service;

    /* ── Lifecycle ── */

    @PostMapping("/api/repos")
    public ResponseEntity<ProjectRepoDto> createRepo(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @Valid @RequestBody CreateRepoRequest req) {
        ProjectRepoDto dto = service.createRepo(
                req,
                userDetails.getUser().getId(),
                userDetails.getUser().getOrgId(),
                isOrgAdmin(userDetails));
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @GetMapping("/api/repos")
    public ResponseEntity<List<ProjectRepoDto>> listRepos(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestParam(value = "scope", required = false) String scope) {
        return ResponseEntity.ok(service.listAvailable(
                userDetails.getUser().getId(),
                userDetails.getUser().getOrgId(),
                scope));
    }

    @PostMapping("/api/repos/{repoId}/sync")
    public ResponseEntity<ProjectRepoDto> syncRepo(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @PathVariable UUID repoId) {
        return ResponseEntity.ok(service.syncRepo(
                repoId,
                userDetails.getUser().getId(),
                userDetails.getUser().getOrgId(),
                isOrgAdmin(userDetails)));
    }

    @DeleteMapping("/api/repos/{repoId}")
    public ResponseEntity<Void> deleteRepo(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @PathVariable UUID repoId) {
        service.deleteRepo(
                repoId,
                userDetails.getUser().getId(),
                userDetails.getUser().getOrgId(),
                isOrgAdmin(userDetails));
        return ResponseEntity.noContent().build();
    }

    /* ── Project linking ── */

    @GetMapping("/api/projects/{projectId}/repos")
    public ResponseEntity<List<ProjectRepoLinkDto>> listProjectRepos(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @PathVariable String projectId) {
        return ResponseEntity.ok(service.listProjectRepos(
                projectId,
                userDetails.getUser().getId(),
                userDetails.getUser().getOrgId()));
    }

    @PostMapping("/api/projects/{projectId}/repos/{repoId}")
    public ResponseEntity<ProjectRepoLinkDto> linkToProject(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @PathVariable String projectId,
            @PathVariable UUID repoId) {
        ProjectRepoLinkDto dto = service.linkToProject(
                projectId,
                repoId,
                userDetails.getUser().getId(),
                userDetails.getUser().getOrgId());
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @DeleteMapping("/api/projects/{projectId}/repos/{repoId}")
    public ResponseEntity<Void> unlinkFromProject(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @PathVariable String projectId,
            @PathVariable UUID repoId) {
        service.unlinkFromProject(
                projectId,
                repoId,
                userDetails.getUser().getId(),
                userDetails.getUser().getOrgId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/projects/{projectId}/repos/{repoId}/relationships")
    public ResponseEntity<ProjectRepoLinkDto> updateRelationships(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @PathVariable String projectId,
            @PathVariable UUID repoId,
            @RequestBody UpdateRelationshipsRequest req) {
        return ResponseEntity.ok(service.updateRelationships(
                projectId,
                repoId,
                req.relationships(),
                userDetails.getUser().getId(),
                userDetails.getUser().getOrgId()));
    }

    private static boolean isOrgAdmin(AppUserDetails userDetails) {
        return "SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole())
                || (userDetails.getOrgMember() != null
                    && "ORG_ADMIN".equals(userDetails.getOrgMember().getOrgRole()));
    }
}
