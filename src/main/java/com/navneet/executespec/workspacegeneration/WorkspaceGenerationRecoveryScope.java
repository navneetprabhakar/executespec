package com.navneet.executespec.workspacegeneration;

import java.util.List;

/** States whose generation has neither been accepted nor durably rejected. */
final class WorkspaceGenerationRecoveryScope {

  static final List<WorkspaceGenerationState> UNRESOLVED =
      List.of(
          WorkspaceGenerationState.ADMITTED,
          WorkspaceGenerationState.OPERATION_CLAIMED,
          WorkspaceGenerationState.AUTHORITY_ACQUIRED,
          WorkspaceGenerationState.PREPARED,
          WorkspaceGenerationState.MUTATING,
          WorkspaceGenerationState.VERIFYING,
          WorkspaceGenerationState.VERIFIED,
          WorkspaceGenerationState.CHECKPOINTING,
          WorkspaceGenerationState.ROLLING_BACK,
          WorkspaceGenerationState.RECOVERY_FAILED);

  private WorkspaceGenerationRecoveryScope() {}
}
