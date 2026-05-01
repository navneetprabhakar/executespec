package com.navneet.executespec.standards;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.auth.security.AppUserDetails;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * M-024 (Wave 2) — org-level standards CRUD.
 *
 * <p>{@link OrgStandard} is the per-org entry that carries the YAML body for one engineering
 * standard. The existing {@code StandardsOverrideController} handles per-violation override
 * workflow + standing project-level exceptions; this controller closes the gap by exposing
 * list/create/ update/delete on the standards themselves so ORG_ADMINs don't have to land via SQL.
 *
 * <p>Endpoints (all require ORG_ADMIN or SUPER_ADMIN; user must belong to the requested org or be a
 * super-admin):
 *
 * <ul>
 *   <li>{@code GET /api/orgs/{orgId}/standards} — list every standard
 *   <li>{@code GET /api/orgs/{orgId}/standards/{standardId}} — single
 *   <li>{@code POST /api/orgs/{orgId}/standards} — create new
 *   <li>{@code PUT /api/orgs/{orgId}/standards/{standardId}} — update yaml + lock flag
 *   <li>{@code DELETE /api/orgs/{orgId}/standards/{standardId}} — remove
 * </ul>
 *
 * <p>YAML body is stored verbatim — it's parsed lazily by {@code StandardsParser} when the standard
 * fires during a run.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/standards")
@RequiredArgsConstructor
public class OrgStandardController {

  private final OrgStandardRepository repo;

  private void requireOrgScope(AppUserDetails principal, String orgId) {
    if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    boolean superAdmin = "SUPER_ADMIN".equals(principal.getUser().getGlobalRole());
    if (superAdmin) return;
    String myOrg = principal.getUser().getOrgId();
    if (myOrg == null || !myOrg.equals(orgId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Outside your org");
    }
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
  public ResponseEntity<List<StandardDto>> list(
      @AuthenticationPrincipal AppUserDetails principal, @PathVariable String orgId) {
    requireOrgScope(principal, orgId);
    return ResponseEntity.ok(repo.findByOrgId(orgId).stream().map(StandardDto::from).toList());
  }

  @GetMapping("/{standardId}")
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
  public ResponseEntity<StandardDto> get(
      @AuthenticationPrincipal AppUserDetails principal,
      @PathVariable String orgId,
      @PathVariable String standardId) {
    requireOrgScope(principal, orgId);
    OrgStandard row =
        repo.findByOrgIdAndStandardId(orgId, standardId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Standard not found"));
    return ResponseEntity.ok(StandardDto.from(row));
  }

  @PostMapping
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
  public ResponseEntity<StandardDto> create(
      @AuthenticationPrincipal AppUserDetails principal,
      @PathVariable String orgId,
      @RequestBody StandardUpsert body) {
    requireOrgScope(principal, orgId);
    if (body == null || body.standardId == null || body.standardId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "standardId is required");
    }
    if (body.standardYaml == null || body.standardYaml.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "standardYaml is required");
    }
    if (repo.findByOrgIdAndStandardId(orgId, body.standardId).isPresent()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Standard already exists for this org");
    }
    OrgStandard saved =
        repo.save(
            OrgStandard.builder()
                .orgId(orgId)
                .standardId(body.standardId.trim())
                .standardYaml(body.standardYaml)
                .orgLevelLocked(Boolean.TRUE.equals(body.orgLevelLocked))
                .build());
    return ResponseEntity.status(HttpStatus.CREATED).body(StandardDto.from(saved));
  }

  @PutMapping("/{standardId}")
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
  public ResponseEntity<StandardDto> update(
      @AuthenticationPrincipal AppUserDetails principal,
      @PathVariable String orgId,
      @PathVariable String standardId,
      @RequestBody StandardUpsert body) {
    requireOrgScope(principal, orgId);
    OrgStandard row =
        repo.findByOrgIdAndStandardId(orgId, standardId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Standard not found"));
    if (body.standardYaml != null && !body.standardYaml.isBlank()) {
      row.setStandardYaml(body.standardYaml);
    }
    if (body.orgLevelLocked != null) {
      row.setOrgLevelLocked(body.orgLevelLocked);
    }
    row.setUpdatedAt(Instant.now());
    return ResponseEntity.ok(StandardDto.from(repo.save(row)));
  }

  @DeleteMapping("/{standardId}")
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal AppUserDetails principal,
      @PathVariable String orgId,
      @PathVariable String standardId) {
    requireOrgScope(principal, orgId);
    OrgStandard row =
        repo.findByOrgIdAndStandardId(orgId, standardId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Standard not found"));
    repo.delete(row);
    return ResponseEntity.noContent().build();
  }

  /**
   * DTO + upsert payload share the same shape — orgLevelLocked is optional on create (defaults
   * false).
   */
  public record StandardDto(
      String id,
      String standardId,
      String standardYaml,
      boolean orgLevelLocked,
      Instant createdAt,
      Instant updatedAt) {
    static StandardDto from(OrgStandard s) {
      return new StandardDto(
          s.getId().toString(),
          s.getStandardId(),
          s.getStandardYaml(),
          s.isOrgLevelLocked(),
          s.getCreatedAt(),
          s.getUpdatedAt());
    }
  }

  public record StandardUpsert(String standardId, String standardYaml, Boolean orgLevelLocked) {}
}
