package com.navneet.executespec.project.template;

import java.util.List;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;

/** v2.5.2 — Spec template CRUD endpoints (UX-2.3 picker). */
@RestController
@RequiredArgsConstructor
public class SpecTemplateController {

  private final SpecTemplateService service;
  private final ProjectAccessGuard projectAccessGuard;

  @GetMapping("/api/projects/{projectId}/templates")
  public ResponseEntity<List<SpecTemplateService.TemplateDto>> listForProject(
      @PathVariable String projectId, @AuthenticationPrincipal AppUserDetails user) {
    // GA-readiness P4.1-followup — spec templates may contain proprietary
    // requirement language; tenant-bound.
    projectAccessGuard.assertCanRead(projectId, user);
    return ResponseEntity.ok(service.listForProject(projectId, user.getUser()));
  }

  @PostMapping("/api/projects/{projectId}/templates")
  public ResponseEntity<SpecTemplateService.TemplateDto> createForProject(
      @PathVariable String projectId,
      @RequestBody SpecTemplateService.CreateRequest req,
      @AuthenticationPrincipal AppUserDetails user) {
    // GA-readiness P4.1-followup — write access to project state.
    projectAccessGuard.assertCanWrite(projectId, user);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.createForProject(projectId, user.getUser(), req));
  }

  @PostMapping("/api/orgs/{orgId}/templates")
  public ResponseEntity<SpecTemplateService.TemplateDto> createForOrg(
      @PathVariable String orgId,
      @RequestBody SpecTemplateService.CreateRequest req,
      @AuthenticationPrincipal AppUserDetails user) {
    // Org-scoped (not project-scoped), so this URL is outside the AccessGuardCoverageTest
    // pattern. The tenant boundary is enforced in SpecTemplateService.createForOrg, which
    // rejects (403) when orgId != caller.getOrgId() — write access to org templates is
    // org-membership-bound, defense-in-depth at the service layer.
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.createForOrg(orgId, user.getUser(), req));
  }

  @DeleteMapping("/api/projects/{projectId}/templates/{templateId}")
  public ResponseEntity<Void> delete(
      @PathVariable String projectId,
      @PathVariable String templateId,
      @AuthenticationPrincipal AppUserDetails user) {
    // GA-readiness P4.1-followup — projectId in the path is the trust boundary;
    // the service's owner_type/owner_id resolution stays as defense in depth.
    projectAccessGuard.assertCanWrite(projectId, user);
    service.delete(templateId, user.getUser());
    return ResponseEntity.noContent().build();
  }
}
