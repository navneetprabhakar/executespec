package com.navneet.executespec.project.template;

import java.util.List;

import com.navneet.executespec.auth.security.AppUserDetails;

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

  @GetMapping("/api/projects/{projectId}/templates")
  public ResponseEntity<List<SpecTemplateService.TemplateDto>> listForProject(
      @PathVariable String projectId, @AuthenticationPrincipal AppUserDetails user) {
    return ResponseEntity.ok(service.listForProject(projectId, user.getUser()));
  }

  @PostMapping("/api/projects/{projectId}/templates")
  public ResponseEntity<SpecTemplateService.TemplateDto> createForProject(
      @PathVariable String projectId,
      @RequestBody SpecTemplateService.CreateRequest req,
      @AuthenticationPrincipal AppUserDetails user) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.createForProject(projectId, user.getUser(), req));
  }

  @PostMapping("/api/orgs/{orgId}/templates")
  public ResponseEntity<SpecTemplateService.TemplateDto> createForOrg(
      @PathVariable String orgId,
      @RequestBody SpecTemplateService.CreateRequest req,
      @AuthenticationPrincipal AppUserDetails user) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.createForOrg(orgId, user.getUser(), req));
  }

  @DeleteMapping("/api/projects/{projectId}/templates/{templateId}")
  public ResponseEntity<Void> delete(
      @PathVariable String projectId,
      @PathVariable String templateId,
      @AuthenticationPrincipal AppUserDetails user) {
    // projectId in the path is for routing/access symmetry; the service
    // resolves authorization from the template's owner_type/owner_id.
    service.delete(templateId, user.getUser());
    return ResponseEntity.noContent().build();
  }
}
