package com.navneet.executespec.workspacegeneration;

import java.util.UUID;

import com.navneet.executespec.auth.security.AppUserDetails;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** SUPER_ADMIN-only access to rejected candidate evidence; never a customer run-detail surface. */
@RestController
@RequestMapping("/api/admin/workspace-generation-attempts")
@RequiredArgsConstructor
public class WorkspaceGenerationQualificationEvidenceController {

  private final WorkspaceGenerationQualificationEvidenceService evidence;
  private final WorkspaceGenerationSchemaAttestor schemaAttestor;
  private final WorkspaceGenerationHistoricalInventoryService historicalInventory;
  private final WorkspaceGenerationHistoricalAdjudicationService historicalAdjudication;
  private final WorkspaceGenerationReconciliationFailureStore reconciliationFailures;

  @GetMapping("/reconciliation-failures")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public ResponseEntity<java.util.List<WorkspaceGenerationReconciliationFailureStore.Failure>>
      reconciliationFailures() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(reconciliationFailures.readOpen());
  }

  @GetMapping("/schema-attestation")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public ResponseEntity<WorkspaceGenerationSchemaAttestor.Attestation> schemaAttestation() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(schemaAttestor.attest());
  }

  @GetMapping("/historical-inventory")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public ResponseEntity<WorkspaceGenerationHistoricalInventoryService.Inventory>
      historicalInventory() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(historicalInventory.capture());
  }

  @PostMapping("/historical-adjudications/dry-run")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public ResponseEntity<WorkspaceGenerationHistoricalAdjudicationService.Receipt>
      dryRunHistoricalAdjudication(
          @RequestBody HistoricalAdjudicationRequest request,
          @AuthenticationPrincipal AppUserDetails principal) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            historicalAdjudication.dryRun(
                request.recordKind(),
                request.recordId(),
                request.action(),
                request.operatorReason(),
                principal.getUser().getId()));
  }

  @PostMapping("/historical-adjudications/{dryRunId}/apply")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public ResponseEntity<WorkspaceGenerationHistoricalAdjudicationService.Receipt>
      applyHistoricalAdjudication(
          @PathVariable UUID dryRunId,
          @RequestBody HistoricalAdjudicationApplyRequest request,
          @AuthenticationPrincipal AppUserDetails principal) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            historicalAdjudication.apply(
                dryRunId, request.operatorReason(), principal.getUser().getId()));
  }

  @GetMapping("/historical-adjudications/{receiptId}")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public ResponseEntity<WorkspaceGenerationHistoricalAdjudicationService.Receipt>
      readHistoricalAdjudication(@PathVariable UUID receiptId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(historicalAdjudication.read(receiptId));
  }

  @GetMapping("/{attemptId}/qualification-evidence")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public ResponseEntity<WorkspaceGenerationQualificationEvidenceService.QualificationEvidence> read(
      @PathVariable UUID attemptId) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(evidence.read(attemptId));
  }

  public record HistoricalAdjudicationRequest(
      WorkspaceGenerationHistoricalAdjudicationService.RecordKind recordKind,
      String recordId,
      WorkspaceGenerationHistoricalAdjudicationService.Action action,
      String operatorReason) {}

  public record HistoricalAdjudicationApplyRequest(String operatorReason) {}
}
