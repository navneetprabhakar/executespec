package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.api.dto.DeployConfirmRequest;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.auth.service.TierPermissionService;
import com.navneet.executespec.auth.service.TierPermissionService.TierPermissions;
import com.navneet.executespec.config.OperationalReleasePolicy;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * RR-572 (tier entitlement) and RR-573 (release safety) — the deploy-engine confirm path must
 * enforce the tier {@code canDeploy} gate and refuse a non-COMPLETE (e.g. FAILED) run.
 */
class DeploymentControllerReleaseSafetyTest {

  private final RunAccessGuard guard = mock(RunAccessGuard.class);
  private final StateStore stateStore = mock(StateStore.class);
  private final TierPermissionService tierPermissionService = mock(TierPermissionService.class);
  private final OperationalReleasePolicy releasePolicy = mock(OperationalReleasePolicy.class);

  private final DeploymentController controller =
      new DeploymentController(
          null,
          null,
          null,
          guard,
          null,
          null,
          null,
          stateStore,
          null,
          null,
          tierPermissionService,
          releasePolicy);

  private static AppUserDetails principal() {
    AppUser user =
        AppUser.builder()
            .id("user-1")
            .email("u@example.com")
            .orgId("org-a")
            .status("ACTIVE")
            .build();
    return new AppUserDetails(user, null);
  }

  private static DeployConfirmRequest confirmRequest() {
    return new DeployConfirmRequest("run-1", "CLOUD_RUN_DIRECT", null, null, null, null, null);
  }

  /** Records cannot be mocked; build a real one with only {@code canDeploy} varied. */
  private static TierPermissions permissions(boolean canDeploy) {
    return new TierPermissions(
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false, // 9 booleans
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false, // 18 booleans total
        0,
        0,
        0,
        0,
        0,
        0, // monthlyRunLimit..maxPreviewStarts
        canDeploy,
        0,
        "UNLIMITED",
        0L,
        true,
        "test");
  }

  @Test
  void confirmRejectsTierThatCannotDeploy() {
    when(releasePolicy.deploymentCreationEnabled()).thenReturn(true);
    when(tierPermissionService.getPermissions(any())).thenReturn(permissions(false));

    assertThatThrownBy(() -> controller.confirm(confirmRequest(), principal()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");
  }

  @Test
  void confirmRejectsFailedRun() {
    when(releasePolicy.deploymentCreationEnabled()).thenReturn(true);
    when(tierPermissionService.getPermissions(any())).thenReturn(permissions(true));
    PipelineRun run = new PipelineRun();
    run.setRunId("run-1");
    run.setStatus("FAILED");
    when(stateStore.findRun("run-1")).thenReturn(Optional.of(run));

    assertThatThrownBy(() -> controller.confirm(confirmRequest(), principal()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
  }
}
