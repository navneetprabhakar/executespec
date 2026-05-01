package com.navneet.executespec.onboarding;

import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.ProjectMember;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.router.LlmProvider;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Wave 2.2 — provisions disposable sandbox projects on signup / first login.
 *
 * <p>Idempotent: if the user already has a sandbox id on {@code app_user}, we reuse that row
 * instead of double-provisioning. Sandbox projects have {@code is_sandbox = true} so {@code
 * SandboxCleanupJob} can GC them and {@code /org/projects} list filters them out.
 *
 * <p>Full plan: docs/plans/ttfv-reduction-plan.md.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SandboxProvisioningService {

  private final AppUserRepository userRepo;
  private final ProjectRepository projectRepo;
  private final ProjectMemberRepository projectMemberRepo;
  private final SampleSpecRegistry sampleSpecs;

  /**
   * Ensure the given user has a sandbox project. If not, create one and write the id to {@code
   * app_user.sandbox_project_id}. Returns the sandbox project id in all cases (whether newly
   * created or existing).
   *
   * @param sampleId optional — if provided, bumps the sandbox's AI provider to the sample's
   *     preferred provider so the first run uses the fastest-for-stack choice. Null = ANTHROPIC.
   */
  @Transactional
  public String provisionIfNeeded(String userId, String sampleId) {
    AppUser user =
        userRepo
            .findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

    if (user.getSandboxProjectId() != null) {
      // Verify the project still exists — cleanup job may have deleted it.
      if (projectRepo.findById(user.getSandboxProjectId()).isPresent()) {
        log.debug(
            "Sandbox already provisioned: userId={} projectId={}",
            userId,
            user.getSandboxProjectId());
        return user.getSandboxProjectId();
      }
      log.info(
          "Sandbox project {} gone (cleanup) — re-provisioning for userId={}",
          user.getSandboxProjectId(),
          userId);
    }

    LlmProvider provider = LlmProvider.ANTHROPIC;
    if (sampleId != null) {
      SampleSpec sample = sampleSpecs.findById(sampleId).orElse(null);
      if (sample != null && sample.preferredProvider() != null) {
        try {
          provider = LlmProvider.valueOf(sample.preferredProvider().toUpperCase());
        } catch (IllegalArgumentException ignored) {
          /* default ANTHROPIC */
        }
      }
    }

    String projectId = UUID.randomUUID().toString();
    String displayName =
        user.getFirstName() == null ? "Sandbox" : user.getFirstName() + "'s sandbox";

    Project sandbox =
        Project.builder()
            .id(projectId)
            .orgId(user.getOrgId()) // null for solo users — that's fine
            .name(displayName)
            .description("Disposable onboarding workspace — auto-deletes after 24h of inactivity.")
            .aiProvider(provider)
            .status("ACTIVE")
            .createdBy(userId)
            .isSandbox(true)
            .build();
    projectRepo.save(sandbox);

    projectMemberRepo.save(
        ProjectMember.builder()
            .id(UUID.randomUUID().toString())
            .projectId(projectId)
            .userId(userId)
            .projectRole("PROJECT_ADMIN")
            .build());

    user.setSandboxProjectId(projectId);
    user.setUpdatedAt(Instant.now());
    userRepo.save(user);

    log.info(
        "Sandbox provisioned: userId={} projectId={} provider={} sampleId={}",
        userId,
        projectId,
        provider,
        sampleId);
    return projectId;
  }

  /** Nullable — returns current sandbox id or null if none. */
  public String currentSandboxId(String userId) {
    return userRepo.findById(userId).map(AppUser::getSandboxProjectId).orElse(null);
  }
}
