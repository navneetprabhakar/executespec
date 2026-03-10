package com.navneet.executespec.auth.controller;

import com.navneet.executespec.auth.dto.CreateProjectRequest;
import com.navneet.executespec.auth.dto.ProjectDto;
import com.navneet.executespec.auth.dto.ProjectMemberDto;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
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
        List<ProjectDto> projects = projectService.listProjectsForUser(
                orgId, userDetails.getUser().getId(), isOrgAdmin);
        return ResponseEntity.ok(projects);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectDto> getProject(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @PathVariable String id) {
        boolean isSuperAdmin = "SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole());
        boolean isOrgAdmin = isOrgAdminOrSuperAdmin(userDetails);
        return ResponseEntity.ok(projectService.getProject(id, userDetails.getUser().getId(),
                isSuperAdmin, isOrgAdmin));
    }

    @PutMapping("/{id}/archive")
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<ProjectDto> archiveProject(@PathVariable String id) {
        return ResponseEntity.ok(projectService.archiveProject(id));
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<List<ProjectMemberDto>> listMembers(@PathVariable String id) {
        return ResponseEntity.ok(projectService.listMembers(id));
    }

    @PostMapping("/{id}/members")
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> addMember(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        projectService.addMember(id, body.get("userId"), body.get("role"));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{id}/members/{userId}")
    @PreAuthorize("hasRole('ORG_ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> removeMember(
            @PathVariable String id,
            @PathVariable String userId) {
        projectService.removeMember(id, userId);
        return ResponseEntity.noContent().build();
    }

    private boolean isOrgAdminOrSuperAdmin(AppUserDetails userDetails) {
        return "SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole()) ||
               (userDetails.getOrgMember() != null &&
                "ORG_ADMIN".equals(userDetails.getOrgMember().getOrgRole()));
    }
}
