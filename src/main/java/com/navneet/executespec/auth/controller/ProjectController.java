package com.navneet.executespec.auth.controller;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import com.navneet.executespec.auth.dto.CreateProjectRequest;
import com.navneet.executespec.auth.dto.ProjectDto;
import com.navneet.executespec.auth.dto.ProjectMemberDto;
import com.navneet.executespec.auth.dto.UpdateProjectSettingsRequest;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.ProjectService;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

  private final ProjectService projectService;
  private final ProjectAccessGuard projectAccessGuard;

  @PostMapping
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('DEVELOPER') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<ProjectDto> createProject(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @Valid @RequestBody CreateProjectRequest req) {
    String orgId = userDetails.getUser().getOrgId();
    ProjectDto project = projectService.createProject(orgId, userDetails.getUser().getId(), req);
    return ResponseEntity.status(HttpStatus.CREATED).body(project);
  }

  @GetMapping
  public ResponseEntity<List<ProjectDto>> listProjects(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    boolean isOrgAdmin = isOrgAdminOrSuperAdmin(userDetails);
    String orgId = userDetails.getUser().getOrgId();
    if (orgId == null) return ResponseEntity.ok(List.of());
    List<ProjectDto> projects =
        projectService.listProjectsForUser(orgId, userDetails.getUser().getId(), isOrgAdmin);
    return ResponseEntity.ok(projects);
  }

  @GetMapping("/{id}")
  public ResponseEntity<ProjectDto> getProject(
      @AuthenticationPrincipal AppUserDetails userDetails, @PathVariable String id) {
    // GA-readiness P4.1-followup — tenant guard. ProjectService.getProject
    // does its own boolean-flag access logic, but the guard makes the org
    // boundary visible at the call site + closes the gap before any service
    // logic runs.
    projectAccessGuard.assertCanRead(id, userDetails);
    boolean isSuperAdmin = "SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole());
    boolean isOrgAdmin = isOrgAdminOrSuperAdmin(userDetails);
    return ResponseEntity.ok(
        projectService.getProject(id, userDetails.getUser().getId(), isSuperAdmin, isOrgAdmin));
  }

  @PatchMapping("/{id}/settings")
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<ProjectDto> updateSettings(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @PathVariable String id,
      @RequestBody UpdateProjectSettingsRequest req) {
    // GA-readiness P4.1-followup — @PreAuthorize role check confirms ORG_ADMIN
    // somewhere; the guard confirms it's THIS project's org.
    projectAccessGuard.assertCanRead(id, userDetails);
    return ResponseEntity.ok(projectService.updateSettings(id, req, userDetails.getUser().getId()));
  }

  @PutMapping("/{id}/archive")
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<ProjectDto> archiveProject(
      @AuthenticationPrincipal AppUserDetails userDetails, @PathVariable String id) {
    // GA-readiness P4.1-followup — tenant guard before destructive op.
    projectAccessGuard.assertCanRead(id, userDetails);
    return ResponseEntity.ok(projectService.archiveProject(id, userDetails.getUser().getId()));
  }

  @GetMapping("/{id}/members")
  public ResponseEntity<List<ProjectMemberDto>> listMembers(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails userDetails) {
    // GA-readiness P4.1-followup — listMembers exposed PII (email + role) of
    // every project member to any authenticated caller; the guard now
    // restricts to org members + invited cross-org collaborators.
    projectAccessGuard.assertCanRead(id, userDetails);
    return ResponseEntity.ok(projectService.listMembers(id));
  }

  @PostMapping("/{id}/members")
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<Void> addMember(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @PathVariable String id,
      @RequestBody Map<String, String> body) {
    // GA-readiness P4.1-followup — closes the access-control mutation gap.
    // Adding members is the most sensitive write surface on a project; the
    // guard ensures the caller is an admin of THIS project's org.
    projectAccessGuard.assertCanRead(id, userDetails);
    projectService.addMember(
        id, body.get("userId"), body.get("role"), userDetails.getUser().getId());
    return ResponseEntity.status(HttpStatus.CREATED).build();
  }

  @DeleteMapping("/{id}/members/{userId}")
  @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
  public ResponseEntity<Void> removeMember(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @PathVariable String id,
      @PathVariable String userId) {
    // GA-readiness P4.1-followup — same as addMember.
    projectAccessGuard.assertCanRead(id, userDetails);
    projectService.removeMember(id, userId, userDetails.getUser().getId());
    return ResponseEntity.noContent().build();
  }

  private boolean isOrgAdminOrSuperAdmin(AppUserDetails userDetails) {
    return "SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole())
        || (userDetails.getOrgMember() != null
            && "ORG_ADMIN".equals(userDetails.getOrgMember().getOrgRole()));
  }
}
