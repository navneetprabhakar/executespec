package com.navneet.executespec.project.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.project.dto.CreateRepoRequest;
import com.navneet.executespec.project.dto.ProjectRepoDto;
import com.navneet.executespec.project.dto.ProjectRepoLinkDto;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.ScmCapability;
import com.navneet.executespec.repo.ScmCredential;
import com.navneet.executespec.repo.ScmCredentialResolver;
import com.navneet.executespec.repo.ScmProvider;
import com.navneet.executespec.repo.ScmRemoteRepository;
import com.navneet.executespec.repo.ScmRepositoryClient;
import com.navneet.executespec.repo.ScmRepositoryClientRegistry;
import com.navneet.executespec.repo.ScmSourceInventory;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectRepoService {

  /**
   * R-323 (D-125 #3) — hard reject on attach when GitHub reports the repo bigger than this many KB.
   * Configurable via {@code executespec.repo.max-size-kb}; default 250 MB. -1 disables the gate
   * entirely (escape hatch for staging / debug).
   */
  @org.springframework.beans.factory.annotation.Value("${executespec.repo.max-size-kb:250000}")
  private long repoMaxSizeKb;

  private final ProjectRepoRepository repoRepo;
  private final ProjectRepoLinkRepository linkRepo;
  private final ProjectRepository projectRepo;
  private final ProjectMemberRepository projectMemberRepo;
  private final StackDetector stackDetector;
  private final GitHubRepoService gitHub;
  private final ScmRepositoryClientRegistry scmClients;
  private final ScmCredentialResolver scmCredentials;
  private final com.navneet.executespec.auth.repository.AppUserRepository userRepo;

  /** V140 — gate per-tier repos-per-project cap on link + create-and-link. */
  private final com.navneet.executespec.auth.service.TierEnforcementService tierEnforcement;

  /** M-039 (Wave 3) — repo allowlist enforcement. */
  private final com.navneet.executespec.governance.GovernanceGuard governanceGuard;

  private final ProjectSourceFreshnessInvalidator sourceFreshnessInvalidator;

  /**
   * E1.5 — fires bootstrap PR async after a successful link. Lazy to keep service start-up cheap
   * when the bootstrap pipeline isn't on the critical path (e.g. tests, admin tooling, migrations).
   */
  private final org.springframework.beans.factory.ObjectProvider<
          com.navneet.executespec.folder.BootstrapService>
      bootstrapServiceProvider;

  /* ────────────────────────────── repo lifecycle ─────────────────────────── */

  public ProjectRepoDto createRepo(
      CreateRepoRequest req, String callerUserId, String callerOrgId, boolean isOrgAdmin) {
    ProjectRepo.Scope scope = ProjectRepo.Scope.valueOf(req.scope());
    if (scope == ProjectRepo.Scope.ORG) {
      if (callerOrgId == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Caller has no org; cannot create ORG-scoped repo");
      }
      if (!isOrgAdmin) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "Only ORG_ADMIN can create organization-scoped repos");
      }
    }
    String requestedFullName = req.requestedFullName();
    if (requestedFullName == null || requestedFullName.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repoFullName is required");
    }
    ScmProvider provider = ScmProvider.require(req.scmProvider());
    ScmRepositoryClient client = scmClients.require(provider);
    requireClientCapability(client, ScmCapability.ATTACH, "attach repositories");
    ScmCredential credential = scmCredentials.require(callerUserId, provider);
    ScmRemoteRepository remote = client.inspect(requestedFullName, credential);
    if (remote.immutableId() == null || remote.immutableId().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "SCM provider did not return an immutable repository id");
    }
    if (req.scmRepositoryId() != null
        && !req.scmRepositoryId().isBlank()
        && !remote.immutableId().equals(req.scmRepositoryId().trim())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Repository identity changed; refresh the provider catalogue");
    }
    var duplicate =
        scope == ProjectRepo.Scope.ORG
            ? repoRepo.findByOwnerOrgIdAndScmProviderAndScmRepositoryId(
                callerOrgId, provider.name(), remote.immutableId())
            : repoRepo.findByOwnerUserIdAndScmProviderAndScmRepositoryId(
                callerUserId, provider.name(), remote.immutableId());
    if (duplicate.isPresent()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          scope == ProjectRepo.Scope.ORG
              ? "Repo already exists for this organization"
              : "Repo already exists for this user");
    }

    String branch =
        req.defaultBranch() == null || req.defaultBranch().isBlank()
            ? remote.defaultBranch()
            : req.defaultBranch().trim();
    if (branch == null || branch.isBlank()) branch = "main";
    long observedSizeKb = remote.sizeKb() == null ? -1L : remote.sizeKb();
    boolean attachAnywayRequested = Boolean.TRUE.equals(req.attachAnyway());
    if (repoMaxSizeKb > 0) {
      boolean exceeds = observedSizeKb > 0 && observedSizeKb > repoMaxSizeKb;
      if (exceeds && !attachAnywayRequested) {
        long thresholdMb = repoMaxSizeKb / 1024;
        long actualMb = observedSizeKb / 1024;
        throw new ResponseStatusException(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "This repo (≈"
                + actualMb
                + " MB) is larger than the platform's "
                + thresholdMb
                + " MB clone-time budget. "
                + "Try a smaller repo, or attach anyway "
                + "(every run that touches it will count more credits), "
                + "or contact admin@executespec.ai to discuss large-repo support.");
      }
      if (exceeds) {
        long actualMb = observedSizeKb / 1024;
        log.info(
            "[R-325] attachAnyway accepted for {} (~{} MB) — credit surcharge will apply",
            remote.fullName(),
            actualMb);
      }
    }
    boolean attachedAnywayPersisted =
        attachAnywayRequested && observedSizeKb > 0 && observedSizeKb > repoMaxSizeKb;
    String subdir = detectMonorepoSubdir(remote, branch, client, credential);
    String headSha = client.branchHeadSha(remote, branch, credential);
    ScmSourceInventory sourceInventory = client.inventory(remote, branch, credential);
    ProjectRepo entity =
        ProjectRepo.builder()
            .id(UUID.randomUUID())
            .scope(scope)
            .ownerOrgId(scope == ProjectRepo.Scope.ORG ? callerOrgId : null)
            .ownerUserId(scope == ProjectRepo.Scope.USER ? callerUserId : null)
            // Legacy columns stay populated for GitHub compatibility only.
            .githubRepoFullName(provider == ScmProvider.GITHUB ? remote.fullName() : null)
            .githubOwner(provider == ScmProvider.GITHUB ? extractOwner(remote.fullName()) : null)
            .sourceType(ProjectRepo.SourceType.SCM.name())
            .scmProvider(provider.name())
            .scmRepositoryId(remote.immutableId())
            .scmCloneUrl(requireCredentialFreeCloneUrl(remote.cloneUrl()))
            .sourceKey(remote.fullName())
            .sourceDisplayName(remote.displayName())
            .sourceCapabilities(remote.capabilities().stream().sorted().toList())
            .defaultBranch(branch)
            .detectedStack(stackDetector.detect(remote, sourceInventory, client, credential))
            .lastSyncedSha(headSha)
            .subdir(subdir)
            // R-325 — persist the observed size + opt-in flag so the
            // credit-surcharge calc can run later. Negative observed
            // size = "we couldn't measure it" → store NULL so the
            // calc treats it as unknown rather than 0 KB.
            .sizeKb(observedSizeKb > 0 ? observedSizeKb : null)
            .attachedAnyway(attachedAnywayPersisted)
            .build();
    applySourceReadiness(entity, headSha, sourceInventory);
    repoRepo.save(entity);

    log.info(
        "Created ProjectRepo {} scope={} owner={}/{}",
        entity.getId(),
        scope,
        callerOrgId,
        callerUserId);
    return ProjectRepoDto.from(entity);
  }

  public List<ProjectRepoDto> listAvailable(
      String callerUserId, String callerOrgId, String scopeFilter) {
    Stream<ProjectRepo> stream = Stream.empty();

    boolean wantOrg = scopeFilter == null || "ALL".equals(scopeFilter) || "ORG".equals(scopeFilter);
    boolean wantUser =
        scopeFilter == null || "ALL".equals(scopeFilter) || "USER".equals(scopeFilter);

    if (wantOrg && callerOrgId != null) {
      stream = Stream.concat(stream, repoRepo.findByOwnerOrgId(callerOrgId).stream());
    }
    if (wantUser) {
      stream = Stream.concat(stream, repoRepo.findByOwnerUserId(callerUserId).stream());
    }
    return stream.map(ProjectRepoDto::from).collect(Collectors.toList());
  }

  /**
   * Fast metadata-only sync — refreshes {@code detected_stack} + {@code last_synced_sha} (+ the
   * monorepo subdir marker) for this repo. <b>Deliberately NOT {@code @Transactional}, and NOT a
   * codemap/arch regen.</b>
   *
   * <p>History (RL-2026-05-026 / Task #82): v2.5.12 folded a per-project codemap + per-repo-arch
   * regen into this method, inside a method-level transaction. That held a pooled DB connection
   * across the multi-minute, LLM-driven {@link
   * com.navneet.executespec.codeintel.ProjectArchitectureService#regenerateForRepo} call for every
   * linked project — exhausting the HikariCP pool under concurrency and making "Sync" hang for
   * minutes. Sync is a cheap metadata refresh again; the heavy regen moved to an explicit,
   * non-transactional endpoint ({@code POST
   * /api/projects/{projectId}/repos/{repoId}/regenerate-architecture}). The single {@code
   * repoRepo.save} below runs in its own short Spring-Data transaction, so the GitHub network calls
   * above never sit inside a held transaction.
   *
   * @param overridePat caller's PAT (used for the SHA + stack + subdir probes against private
   *     repos)
   */
  public ProjectRepoDto syncRepo(
      UUID repoId,
      String callerUserId,
      String callerOrgId,
      boolean isOrgAdmin,
      String overridePat) {
    ProjectRepo repo = loadRepoForWrite(repoId, callerUserId, callerOrgId, isOrgAdmin);
    requireScmSource(repo, "sync");
    ScmProvider provider = ScmProvider.require(repo.getScmProvider());
    ScmRepositoryClient client = scmClients.require(provider);
    requireClientCapability(client, ScmCapability.SYNC, "sync repositories");
    ScmCredential credential =
        provider == ScmProvider.GITHUB && overridePat != null && !overridePat.isBlank()
            ? new ScmCredential(null, overridePat)
            : scmCredentials.require(callerUserId, provider);
    String currentName = canonicalDisplayIdentity(repo);
    ScmRemoteRepository remote = client.inspect(currentName, repo.getScmRepositoryId(), credential);
    if (repo.getScmRepositoryId() != null
        && !repo.getScmRepositoryId().equals(remote.immutableId())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Remote repository identity no longer matches this project source");
    }
    // Safe legacy upgrade: immutable id comes only from a successful authenticated provider read.
    repo.setScmRepositoryId(remote.immutableId());
    repo.setScmCloneUrl(requireCredentialFreeCloneUrl(remote.cloneUrl()));
    repo.setSourceKey(remote.fullName());
    repo.setSourceDisplayName(remote.displayName());
    repo.setSourceCapabilities(remote.capabilities().stream().sorted().toList());
    if (provider == ScmProvider.GITHUB) {
      repo.setGithubRepoFullName(remote.fullName());
      repo.setGithubOwner(extractOwner(remote.fullName()));
    }
    if (remote.sizeKb() != null) repo.setSizeKb(remote.sizeKb());

    // Metadata refresh only. Provider calls run OUTSIDE a transaction.
    String headSha = client.branchHeadSha(remote, repo.getDefaultBranch(), credential);
    ScmSourceInventory inventory = client.inventory(remote, repo.getDefaultBranch(), credential);
    String detected = stackDetector.detect(remote, inventory, client, credential);
    if (detected != null) repo.setDetectedStack(detected);
    if (headSha != null) repo.setLastSyncedSha(headSha);
    applySourceReadiness(repo, headSha, inventory);
    // R-322 — re-read the monorepo marker on every sync so a customer
    // who drops .executespec/root.yml after attach gets the subdir
    // picked up on the next sync. We never clear back to NULL on a
    // miss — that's a deletion signal that we want explicit, not
    // accidental from a transient GH 5xx.
    String subdir = detectMonorepoSubdir(remote, repo.getDefaultBranch(), client, credential);
    if (subdir != null) repo.setSubdir(subdir);
    repo.setUpdatedAt(Instant.now());
    // repoRepo.save is itself transactional (Spring Data wraps each call) — a short write tx, not
    // one held across the network probes above. Architecture regen is now an explicit action.
    ProjectRepo saved = repoRepo.save(repo);
    sourceFreshnessInvalidator.invalidateRepo(repoId);
    return ProjectRepoDto.from(saved);
  }

  private static void applySourceReadiness(
      ProjectRepo repo, String headSha, ScmSourceInventory sourceInventory) {
    repo.setSourceReadinessCheckedAt(Instant.now());
    repo.setSourceFileCount(
        sourceInventory != null && sourceInventory.successful()
            ? (long) sourceInventory.files().size()
            : null);
    if (headSha == null || headSha.isBlank()) {
      repo.setSourceReadinessStatus(ProjectRepo.SourceReadiness.BLOCKED.name());
      repo.setSourceReadinessReason(
          sourceInventory == null || sourceInventory.errorCode() == null
              ? "BRANCH_UNREADABLE"
              : sourceInventory.errorCode());
      return;
    }
    if (sourceInventory == null || !sourceInventory.successful()) {
      repo.setSourceReadinessStatus(ProjectRepo.SourceReadiness.BLOCKED.name());
      repo.setSourceReadinessReason(
          sourceInventory == null ? "SOURCE_INVENTORY_FAILED" : sourceInventory.errorCode());
      return;
    }
    if (sourceInventory.files().isEmpty()) {
      repo.setSourceReadinessStatus(ProjectRepo.SourceReadiness.BLOCKED.name());
      repo.setSourceReadinessReason("EMPTY_REPOSITORY");
      return;
    }
    if (sourceInventory.truncated()) {
      repo.setSourceReadinessStatus(ProjectRepo.SourceReadiness.INVENTORIED.name());
      repo.setSourceReadinessReason("SOURCE_INVENTORY_TRUNCATED");
      return;
    }
    repo.setSourceReadinessStatus(ProjectRepo.SourceReadiness.READY.name());
    repo.setSourceReadinessReason("SOURCE_INVENTORY_CONFIRMED");
  }

  /**
   * Legacy overload — metadata-only sync, kept for any caller that isn't ready to pass a PAT.
   * Prefer the 5-arg form above (it passes the caller PAT for private-repo probes).
   */
  public ProjectRepoDto syncRepo(
      UUID repoId, String callerUserId, String callerOrgId, boolean isOrgAdmin) {
    return syncRepo(repoId, callerUserId, callerOrgId, isOrgAdmin, null);
  }

  @Transactional
  public void deleteRepo(UUID repoId, String callerUserId, String callerOrgId, boolean isOrgAdmin) {
    ProjectRepo repo = loadRepoForWrite(repoId, callerUserId, callerOrgId, isOrgAdmin);
    if (linkRepo.existsByRepoId(repoId)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Repo is linked to one or more projects; unlink first");
    }
    repoRepo.delete(repo);
    log.info("Deleted ProjectRepo {}", repoId);
  }

  /* ─────────────────────────── project ↔ repo links ──────────────────────── */

  public List<ProjectRepoLinkDto> listProjectRepos(
      String projectId, String callerUserId, String callerOrgId) {
    requireProjectAccess(projectId, callerUserId, callerOrgId);
    List<ProjectRepoLink> links = linkRepo.findByProjectId(projectId);
    List<ProjectRepoLinkDto> out = new ArrayList<>(links.size());
    for (ProjectRepoLink l : links) {
      repoRepo.findById(l.getRepoId()).ifPresent(r -> out.add(ProjectRepoLinkDto.from(l, r)));
    }
    return out;
  }

  @Transactional
  public ProjectRepoLinkDto linkToProject(
      String projectId, UUID repoId, String callerUserId, String callerOrgId) {

    Project project = requireProjectAccess(projectId, callerUserId, callerOrgId);
    // V140 — block before further work when the project is at its
    // tier repos-per-project cap.
    tierEnforcement.assertCanLinkRepoToProject(callerUserId, projectId);
    ProjectRepo repo =
        repoRepo
            .findById(repoId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repo not found"));

    // M-039 (Wave 3) — owner allowlist check. ProjectRepo.githubRepoFullName
    // is "owner/repo"; we extract the owner segment. SUPER_ADMIN bypass
    // is handled inside the guard.
    if (project.getOrgId() != null && repo.getGithubRepoFullName() != null) {
      String owner =
          repo.getGithubRepoFullName().contains("/")
              ? repo.getGithubRepoFullName().substring(0, repo.getGithubRepoFullName().indexOf('/'))
              : repo.getGithubRepoFullName();
      // Caller principal isn't on this signature; pass null so the
      // SUPER_ADMIN bypass is intentionally NOT available on this path
      // (callers rarely run as super-admin in the project flow). If
      // we need that bypass later, thread AppUserDetails through.
      governanceGuard.assertRepoOwnerAllowed(project.getOrgId(), owner, null);
    }

    // Eligibility rule: ORG repo must belong to the project's org; USER repo
    // must be owned by the caller (no cross-user linking for now).
    if (repo.getScope() == ProjectRepo.Scope.ORG) {
      if (!project.getOrgId().equals(repo.getOwnerOrgId())) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "ORG repo belongs to a different organization");
      }
    } else {
      if (!callerUserId.equals(repo.getOwnerUserId())) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "Only the repo owner can link their personal repo");
      }
    }
    requireScmSource(repo, "link through /api/projects/{projectId}/repos/{repoId}");

    linkRepo
        .findByProjectIdAndRepoId(projectId, repoId)
        .ifPresent(
            existing -> {
              throw new ResponseStatusException(
                  HttpStatus.CONFLICT, "Repo already linked to project");
            });

    ProjectRepoLink link =
        ProjectRepoLink.builder()
            .id(UUID.randomUUID())
            .projectId(projectId)
            .repoId(repoId)
            .linkedBy(callerUserId)
            .build();
    linkRepo.save(link);
    dispatchAfterCommit(() -> sourceFreshnessInvalidator.invalidateLink(projectId, repoId));

    log.info("Linked repo {} ({}) to project {}", repoId, repo.getGithubRepoFullName(), projectId);

    // E1.5 — fire bootstrap async so the controller responds immediately;
    // the slow GH round-trip (PAT preflight + branch creation + content
    // PUTs + open PR) runs on the background executor. Failures persist
    // BOOTSTRAP_FAILED on the project_repo row + emit an activity event;
    // they never bubble back to this thread.
    //
    // RR-311 — dispatch bootstrap AFTER this transaction commits. Firing it inline (before the
    // linkToProject transaction returns) let the async worker query/update repo/link state while
    // the
    // creating transaction was still invisible: it could report false-missing rows, race a
    // rollback,
    // or open a bootstrap PR for a link that never committed. Registering an after-commit callback
    // guarantees the link row is durable before any background work observes it; if the transaction
    // rolls back, afterCommit never runs and no orphan bootstrap is dispatched.
    com.navneet.executespec.folder.BootstrapService bootstrap =
        bootstrapServiceProvider.getIfAvailable();
    if (bootstrap != null && supportsCapability(repo, ScmCapability.BOOTSTRAP)) {
      String pat = userRepo.findById(callerUserId).map(u -> u.getGithubPat()).orElse(null);
      UUID actorUserId = parseUuidOrNull(callerUserId);
      UUID projectUuid = parseUuidOrNull(projectId);
      UUID repoIdForBootstrap = repo.getId();
      String projectName = project.getName();
      if (projectUuid != null) {
        dispatchAfterCommit(
            () ->
                bootstrap.bootstrapAsync(
                    repoIdForBootstrap, projectUuid, projectName, actorUserId, pat));
      }
    }

    return ProjectRepoLinkDto.from(link, repo);
  }

  private static UUID parseUuidOrNull(String s) {
    try {
      return s == null ? null : UUID.fromString(s);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /**
   * RR-311 — run {@code action} only after the current transaction commits. When there is no active
   * transaction (defensive / test paths) the action runs inline.
   */
  private static void dispatchAfterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }

  @Transactional
  public void unlinkFromProject(
      String projectId, UUID repoId, String callerUserId, String callerOrgId) {
    requireProjectAccess(projectId, callerUserId, callerOrgId);
    linkRepo
        .findByProjectIdAndRepoId(projectId, repoId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found"));
    linkRepo.deleteByProjectIdAndRepoId(projectId, repoId);
    dispatchAfterCommit(() -> sourceFreshnessInvalidator.invalidateLink(projectId, repoId));
    log.info("Unlinked repo {} from project {}", repoId, projectId);
  }

  @Transactional
  public ProjectRepoLinkDto updateRelationships(
      String projectId,
      UUID repoId,
      List<String> relationships,
      String callerUserId,
      String callerOrgId) {

    requireProjectAccess(projectId, callerUserId, callerOrgId);
    ProjectRepoLink link =
        linkRepo
            .findByProjectIdAndRepoId(projectId, repoId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found"));
    link.setRelationships(serializeList(relationships));
    linkRepo.save(link);

    ProjectRepo repo = repoRepo.findById(repoId).orElseThrow();
    return ProjectRepoLinkDto.from(link, repo);
  }

  /**
   * Pilot v3.0 — resolve a list of repo IDs (as provided by {@code RunRequest.selectedRepoIds})
   * into the underlying entities, filtering to those actually linked to {@code projectId}. Silently
   * drops IDs that are not linked (defensive — UI should only send linked IDs).
   */
  public List<ProjectRepo> resolveForRun(String projectId, List<UUID> repoIds) {
    if (repoIds == null || repoIds.isEmpty()) return List.of();
    List<ProjectRepoLink> allLinks = linkRepo.findByProjectId(projectId);
    return allLinks.stream()
        .filter(l -> repoIds.contains(l.getRepoId()))
        .map(l -> repoRepo.findById(l.getRepoId()).orElse(null))
        .filter(r -> r != null)
        .collect(Collectors.toList());
  }

  /**
   * Resolve the exact brownfield code boundary for a run. Unlike the historical compatibility
   * helper above, this method is fail-closed: a missing, duplicated, or no-longer-linked id is a
   * request error rather than an invitation to execute against a partial/empty workspace.
   */
  public List<ProjectRepo> resolveForRunStrict(String projectId, List<UUID> repoIds) {
    List<ProjectRepo> resolved = resolveLinkedStrict(projectId, repoIds);
    if (resolved.stream()
        .anyMatch(r -> ProjectRepo.SourceType.LOCAL.name().equals(r.getSourceType()))) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "LOCAL sources require an active local client execution session");
    }
    for (ProjectRepo repo : resolved) {
      requireCapability(repo, ScmCapability.CLOUD_RUN, "run in the cloud execution pipeline");
    }
    // RR-313 (2026-07-22) — attach/sync persist an explicit source-readiness verdict (BLOCKED when
    // the branch is unreadable, the source inventory failed, or the repository is empty), but run
    // admission previously checked only link + LOCAL + CLOUD_RUN capability and never consulted it.
    // A known-unreadable source therefore passed submission, charged quota/credits, and only failed
    // later during clone/planning. Refuse a repo whose last readiness check concluded BLOCKED so
    // the
    // failure surfaces as an actionable preflight error before any spend. Null/legacy readiness
    // (rows that predate the readiness contract) is left permissive here; requiring positive READY
    // evidence for freshly created sources is tracked separately (RR-318).
    for (ProjectRepo repo : resolved) {
      if (ProjectRepo.SourceReadiness.BLOCKED.name().equals(repo.getSourceReadinessStatus())) {
        String reason =
            repo.getSourceReadinessReason() == null ? "UNKNOWN" : repo.getSourceReadinessReason();
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Repository "
                + repo.getGithubRepoFullName()
                + " is not ready for execution ("
                + reason
                + "). Re-sync the source before submitting.");
      }
    }
    return resolved;
  }

  /**
   * Resolve the repository identity accepted by the GitHub-backed PR/code-review surfaces.
   *
   * <p>The caller-provided full name is only an assertion, never the source of truth. The durable
   * {@link ProjectRepo} row and its {@link ProjectRepoLink} define the target. This prevents a
   * caller with write access to project A from naming an arbitrary GitHub repository (or a repo
   * linked only to project B) and spending project A's review quota against it.
   *
   * @param projectId project that must own the link
   * @param repoId required linked repository id
   * @param assertedFullName optional compatibility assertion; rejected when it disagrees with the
   *     canonical row
   */
  public ProjectRepo resolveGitHubReviewRepoStrict(
      String projectId, UUID repoId, String assertedFullName) {
    if (repoId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repoId is required");
    }
    // Review admission is scoped to the durable project link, but it is not a cloud-run
    // admission check. Keep the two capability boundaries independent so an unsupported SCM is
    // rejected for the actual review contract rather than for an unrelated execution contract.
    ProjectRepo repo = resolveLinkedStrict(projectId, List.of(repoId)).getFirst();
    if (ProjectRepo.SourceType.LOCAL.name().equals(repo.getSourceType())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "PR and code review currently require a linked GitHub repository");
    }
    String provider = repo.getScmProvider();
    if (provider != null && !"GITHUB".equalsIgnoreCase(provider)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "PR and code review currently require a linked GitHub repository");
    }
    String canonicalFullName = repo.getGithubRepoFullName();
    if (canonicalFullName == null || canonicalFullName.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Linked GitHub repository has no canonical repository identity");
    }
    if (assertedFullName != null
        && !assertedFullName.isBlank()
        && !canonicalFullName.equalsIgnoreCase(assertedFullName.trim())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "repoFullName does not match the linked repoId");
    }
    return repo;
  }

  /**
   * Local-hands run admission (V278 binding contract). LOCAL sources are admitted only when the
   * submitting client proves it is the connector holding the lease: caller owns the source, the
   * request's installation identity matches the registered one, and the lease is currently ONLINE.
   * SCM sources pass through unchanged (the client works in its own clone). Cloud runs never reach
   * this method — {@link #resolveForRunStrict} keeps rejecting LOCAL sources there.
   */
  @Transactional
  public List<ProjectRepo> resolveForLocalRunStrict(
      String projectId, List<UUID> repoIds, UUID localInstallationId, String callerUserId) {
    List<ProjectRepo> resolved = resolveLinkedStrict(projectId, repoIds);
    List<ProjectRepo> localSources =
        resolved.stream()
            .filter(r -> ProjectRepo.SourceType.LOCAL.name().equals(r.getSourceType()))
            .toList();
    for (ProjectRepo source : resolved) {
      if (!ProjectRepo.SourceType.LOCAL.name().equals(source.getSourceType())) {
        requireCapability(source, ScmCapability.LOCAL_RUN, "run through local hands");
      }
    }
    if (localSources.isEmpty()) {
      return resolved;
    }
    if (localInstallationId == null) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Selected LOCAL sources require localInstallationId from the registered local"
              + " connector");
    }
    Instant now = Instant.now();
    for (ProjectRepo source : localSources) {
      if (callerUserId == null || !callerUserId.equals(source.getOwnerUserId())) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "LOCAL source " + source.getId() + " is not owned by the submitting user");
      }
      if (!localInstallationId.equals(source.getLocalInstallationId())) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "LOCAL source " + source.getId() + " is registered to a different client installation");
      }
      if (!"ONLINE".equals(source.localConnectionStatus(now))) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "LOCAL source "
                + source.getId()
                + " is OFFLINE; start the local connector so its lease is active");
      }
      source.setSourceLastSeenAt(now);
      repoRepo.save(source);
    }
    return resolved;
  }

  private List<ProjectRepo> resolveLinkedStrict(String projectId, List<UUID> repoIds) {
    if (projectId == null || projectId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId is required");
    }
    if (repoIds == null || repoIds.isEmpty()) {
      if (!linkRepo.findByProjectId(projectId).isEmpty()) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Select at least one linked repository before starting project work");
      }
      return List.of();
    }
    List<UUID> distinct = repoIds.stream().distinct().toList();
    List<ProjectRepo> resolved = resolveForRun(projectId, distinct);
    if (resolved.size() != distinct.size()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "One or more selected repositories are missing or are not linked to this project");
    }
    return resolved;
  }

  /**
   * Create a brand-new GitHub repo and link it to the project in one shot. Used by the Launch flow
   * when a user describes a new microservice. Always creates ORG-scoped repos — new-repo-at-Launch
   * is an enterprise path.
   */
  @Transactional
  public ProjectRepo createAndLinkNewRepo(
      String projectId,
      String name,
      String description,
      String githubOwner,
      String visibility,
      String pat,
      String callerUserId,
      String callerOrgId) {
    Project project = requireProjectAccess(projectId, callerUserId, callerOrgId);
    // V140 — block before any GitHub side-effect when the project is
    // at its tier repos-per-project cap.
    tierEnforcement.assertCanLinkRepoToProject(callerUserId, projectId);
    // M-039 (Wave 3) — owner allowlist check before GitHub call.
    // Caller principal not threaded through this signature so SUPER_ADMIN
    // bypass intentionally not available; ORG_ADMIN can update the
    // allowlist instead.
    governanceGuard.assertRepoOwnerAllowed(project.getOrgId(), githubOwner, null);

    String cloneUrl =
        gitHub.createRepo(
            description,
            name,
            "public".equals(visibility) ? "public" : "private",
            pat,
            githubOwner);
    if (cloneUrl == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "GitHub repo creation failed — check PAT + owner access");
    }
    // cloneUrl = https://github.com/{owner}/{name}.git (possibly with suffix)
    String fullName = extractFullNameFromCloneUrl(cloneUrl);
    if (fullName == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Created repo but could not parse clone URL: " + cloneUrl);
    }

    // v2.5.2 — auto-provision the PR webhook on new repos so the
    // merge-cascade pipeline fires without a manual setup step.
    boolean webhookOk = false;
    try {
      webhookOk = gitHub.installWebhook(fullName, pat);
    } catch (Exception e) {
      log.warn("installWebhook threw for {}: {}", fullName, e.getMessage());
    }

    ProjectRepo entity =
        ProjectRepo.builder()
            .id(UUID.randomUUID())
            .scope(project.getOrgId() != null ? ProjectRepo.Scope.ORG : ProjectRepo.Scope.USER)
            .ownerOrgId(project.getOrgId())
            .ownerUserId(project.getOrgId() != null ? null : callerUserId)
            .githubRepoFullName(fullName)
            .githubOwner(extractOwner(fullName))
            // RR-318 — a created source must carry the same neutral SCM identity contract as an
            // attached one, not just legacy name/owner columns, so run admission can reason about
            // it.
            .sourceType(ProjectRepo.SourceType.SCM.name())
            .scmProvider(ScmProvider.GITHUB.name())
            .sourceKey(fullName)
            .sourceDisplayName(fullName)
            .defaultBranch("main")
            .detectedStack(null) // fresh repo — no stack detected yet
            .lastSyncedSha(null)
            .webhookInstalled(webhookOk)
            .build();
    // RR-318 — pass the created repo through the same canonical inspect/inventory/readiness
    // finalizer as attach so it persists an immutable provider id, credential-free clone URL,
    // capabilities, branch SHA and an honest readiness verdict. A brand-new repo has no commits, so
    // this correctly persists BLOCKED (BRANCH_UNREADABLE/EMPTY_REPOSITORY) readiness — RR-313 run
    // admission then refuses it until the user pushes code and re-syncs, instead of admitting a
    // just-created source with false identity and no proven readability.
    hydrateCreatedSourceIdentity(entity, fullName, pat);
    repoRepo.save(entity);

    linkRepo.save(
        ProjectRepoLink.builder()
            .id(UUID.randomUUID())
            .projectId(projectId)
            .repoId(entity.getId())
            .linkedBy(callerUserId)
            .build());
    dispatchAfterCommit(() -> sourceFreshnessInvalidator.invalidateLink(projectId, entity.getId()));
    log.info(
        "Created+linked new repo {} ({}) for project {} [webhook={}]",
        entity.getId(),
        fullName,
        projectId,
        webhookOk);
    return entity;
  }

  /**
   * v2.5.2 — manually install the PR webhook on a repo the user linked (rather than created).
   * Requires the caller's PAT to have admin:repo_hook on the target repo. Idempotent: GitHub's
   * "already exists" path is treated as success.
   */
  @Transactional
  public boolean installWebhook(
      UUID repoId, String pat, String callerUserId, String callerOrgId, boolean isOrgAdmin) {
    ProjectRepo repo = loadRepoForWrite(repoId, callerUserId, callerOrgId, isOrgAdmin);
    requireScmSource(repo, "install a webhook");
    requireCapability(repo, ScmCapability.WEBHOOK, "install webhooks");
    boolean ok = gitHub.installWebhook(repo.getGithubRepoFullName(), pat);
    if (ok) {
      repo.setWebhookInstalled(true);
      repo.setUpdatedAt(Instant.now());
      repoRepo.save(repo);
    }
    return ok;
  }

  /**
   * RR-318 — hydrate the immutable source identity + readiness of a just-created GitHub repository
   * from an authenticated provider read, mirroring the attach path. Fails closed: any inability to
   * verify the identity leaves the source BLOCKED so it cannot be admitted for a run.
   */
  private void hydrateCreatedSourceIdentity(ProjectRepo entity, String fullName, String pat) {
    try {
      ScmRepositoryClient client = scmClients.require(ScmProvider.GITHUB);
      ScmCredential credential = new ScmCredential(null, pat);
      ScmRemoteRepository remote = client.inspect(fullName, credential);
      if (remote.immutableId() != null && !remote.immutableId().isBlank()) {
        entity.setScmRepositoryId(remote.immutableId());
      }
      entity.setScmCloneUrl(requireCredentialFreeCloneUrl(remote.cloneUrl()));
      entity.setSourceKey(remote.fullName());
      entity.setSourceDisplayName(remote.displayName());
      entity.setSourceCapabilities(remote.capabilities().stream().sorted().toList());
      String branch = entity.getDefaultBranch();
      String headSha = null;
      try {
        headSha = client.branchHeadSha(remote, branch, credential);
      } catch (RuntimeException noCommitsYet) {
        // A repository created moments ago has no commits — leave headSha null.
      }
      ScmSourceInventory inventory = null;
      try {
        inventory = client.inventory(remote, branch, credential);
      } catch (RuntimeException inventoryFailure) {
        // Fall through with a null inventory → applySourceReadiness marks it BLOCKED.
      }
      if (headSha != null && !headSha.isBlank()) entity.setLastSyncedSha(headSha);
      applySourceReadiness(entity, headSha, inventory);
    } catch (RuntimeException identityFailure) {
      log.warn(
          "RR-318 — created repo {} identity could not be verified; marking source BLOCKED: {}",
          fullName,
          identityFailure.getMessage());
      entity.setSourceReadinessCheckedAt(Instant.now());
      entity.setSourceReadinessStatus(ProjectRepo.SourceReadiness.BLOCKED.name());
      entity.setSourceReadinessReason("SOURCE_IDENTITY_UNVERIFIED");
    }
  }

  private static String extractFullNameFromCloneUrl(String cloneUrl) {
    if (cloneUrl == null) return null;
    // Expect https://github.com/<owner>/<repo>(.git)?
    int host = cloneUrl.indexOf("github.com/");
    if (host < 0) return null;
    String tail = cloneUrl.substring(host + "github.com/".length());
    if (tail.endsWith(".git")) tail = tail.substring(0, tail.length() - 4);
    return tail.contains("/") ? tail : null;
  }

  private static void requireScmSource(ProjectRepo repo, String operation) {
    if (ProjectRepo.SourceType.LOCAL.name().equals(repo.getSourceType())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "LOCAL sources cannot " + operation + "; renew them through the local connector");
    }
  }

  /** Server-side provider gate for every provider-specific project action. */
  public void requireCapability(ProjectRepo repo, ScmCapability capability, String operation) {
    if (!supportsCapability(repo, capability)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          (repo.getScmProvider() == null ? "GITHUB" : repo.getScmProvider())
              + " project sources do not support "
              + operation);
    }
  }

  public ProjectRepo requireLinkedCapability(
      String projectId, UUID repoId, ScmCapability capability, String operation) {
    linkRepo
        .findByProjectIdAndRepoId(projectId, repoId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repo not linked"));
    ProjectRepo repo =
        repoRepo
            .findById(repoId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repo not found"));
    requireCapability(repo, capability, operation);
    return repo;
  }

  public void requireProjectCapability(
      String projectId, ScmCapability capability, String operation) {
    // RR-315 (2026-07-22) — this is the admission gate for provider-backed project actions
    // (architecture regeneration, confirmed chat actions). It previously returned normally when the
    // project had no repos, only LOCAL repos, or dangling links, so an action requiring an SCM
    // capability advanced vacuously past the check and then failed later or operated against an
    // empty source boundary. Require at least one non-LOCAL, resolvable linked repository that
    // actually carries the capability.
    int capableScmRepos = 0;
    for (ProjectRepoLink link : linkRepo.findByProjectId(projectId)) {
      ProjectRepo repo = repoRepo.findById(link.getRepoId()).orElse(null);
      if (repo == null) continue; // dangling link — a missing repo cannot vouch for the capability
      if (ProjectRepo.SourceType.LOCAL.name().equals(repo.getSourceType())) continue;
      requireCapability(repo, capability, operation);
      capableScmRepos++;
    }
    if (capableScmRepos == 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "No linked SCM repository can " + operation + "; link a supported repository first.");
    }
  }

  private boolean supportsCapability(ProjectRepo repo, ScmCapability capability) {
    if (ProjectRepo.SourceType.LOCAL.name().equals(repo.getSourceType())) return false;
    return scmClients.require(ScmProvider.require(repo.getScmProvider())).supports(capability);
  }

  private static void requireClientCapability(
      ScmRepositoryClient client, ScmCapability capability, String operation) {
    if (!client.supports(capability)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, client.provider() + " does not support " + operation);
    }
  }

  /* ──────────────────────────────── helpers ──────────────────────────────── */

  /**
   * GA-readiness P4.1-followup (2026-05-06) — exposed as public so {@code ProjectRepoController}
   * can call it BEFORE the mutating service operation, making the tenant-boundary check visible at
   * the controller call site. Internal service callers still call this directly; the only callers
   * added by exposing it are the controller's repo-scoped handlers (deleteRepo / installRepoWebhook
   * / syncRepo).
   *
   * <p>Throws 404 when the repo doesn't exist (no existence-leak), 403 when the caller is
   * authenticated but the repo is owned by another user / org (and the caller isn't SUPER_ADMIN).
   * Returns the loaded entity on success — controllers can ignore the return value when the
   * side-effect (exception on denial) is what they want.
   */
  public ProjectRepo loadRepoForRead(UUID repoId, String callerUserId, String callerOrgId) {
    ProjectRepo repo =
        repoRepo
            .findById(repoId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repo not found"));
    // v2.5.12 (Gap #1) — SUPER_ADMIN sees everything. Same bypass shape used
    // by TierEnforcementService.assertCanSubmitRun + AdminReconciliationService.
    // The DB hit is bounded (per-request, single PK lookup) and only triggers
    // when the user isn't already the owner.
    boolean visible =
        (repo.getScope() == ProjectRepo.Scope.ORG && repo.getOwnerOrgId().equals(callerOrgId))
            || (repo.getScope() == ProjectRepo.Scope.USER
                && repo.getOwnerUserId().equals(callerUserId));
    if (!visible) {
      boolean isSuperAdmin =
          callerUserId != null
              && userRepo
                  .findById(callerUserId)
                  .map(u -> "SUPER_ADMIN".equals(u.getGlobalRole()))
                  .orElse(false);
      if (isSuperAdmin) return repo;
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Repo not visible to caller");
    }
    return repo;
  }

  public ProjectRepo loadRepoForWrite(
      UUID repoId, String callerUserId, String callerOrgId, boolean isOrgAdmin) {
    ProjectRepo repo = loadRepoForRead(repoId, callerUserId, callerOrgId);
    if (repo.getScope() == ProjectRepo.Scope.ORG && !isOrgAdmin) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Only ORG_ADMIN can modify organization-scoped repos");
    }
    if (repo.getScope() == ProjectRepo.Scope.USER && !callerUserId.equals(repo.getOwnerUserId())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Only the owner can modify their personal repo");
    }
    return repo;
  }

  private Project requireProjectAccess(String projectId, String callerUserId, String callerOrgId) {
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    if (callerOrgId != null && callerOrgId.equals(project.getOrgId())) {
      return project;
    }
    if (callerUserId != null
        && projectMemberRepo.existsByProjectIdAndUserId(projectId, callerUserId)) {
      return project;
    }
    boolean isSuperAdmin =
        callerUserId != null
            && userRepo
                .findById(callerUserId)
                .map(u -> "SUPER_ADMIN".equals(u.getGlobalRole()))
                .orElse(false);
    if (isSuperAdmin) return project;
    throw new ResponseStatusException(
        HttpStatus.FORBIDDEN, "Project belongs to another organization");
  }

  private static String extractOwner(String fullName) {
    int slash = fullName.indexOf('/');
    return slash > 0 ? fullName.substring(0, slash) : fullName;
  }

  /**
   * R-322 (D-125 #2) — read the monorepo marker file {@code .executespec/root.yml} from the repo's
   * default branch and return the customer-declared {@code root: <subdir>} value.
   *
   * <p>Returns {@code null} when the file is missing, unparseable, or the declared subdir fails
   * sanity checks (absolute, traversal, length). Callers treat {@code null} as "use repo root" —
   * the existing default for every row before V154.
   */
  String detectMonorepoSubdir(String repoUrl, String branch, String overridePat) {
    try {
      // RR-1631 — failure is out-of-band now, so a root.yml that legitimately opens with a "#
      // "/"//"
      // comment is no longer mistaken for a failed read.
      String body =
          gitHub
              .readRepoFile(repoUrl, ".executespec/root.yml", branch, overridePat)
              .contentOrNull();
      if (body == null || body.isBlank()) return null;
      return parseRootYamlSubdir(body);
    } catch (Exception e) {
      log.debug(
          "[R-322] subdir marker probe failed for {} ({}): {}", repoUrl, branch, e.getMessage());
      return null;
    }
  }

  private String detectMonorepoSubdir(
      ScmRemoteRepository repository,
      String branch,
      ScmRepositoryClient client,
      ScmCredential credential) {
    try {
      String body = client.readFile(repository, branch, ".executespec/root.yml", credential);
      // No `startsWith("// ")` guard: read failures now surface as ScmProviderException (caught
      // below) rather than as an in-band "// ..." sentinel string, so inspecting content for
      // failure is both dead and wrong — it would discard a legitimate file whose first line
      // happens to start with "// " (RR-1631 root cause). The sibling overload above was already
      // migrated; this second call site was missed.
      if (body == null || body.isBlank()) return null;
      return parseRootYamlSubdir(body);
    } catch (Exception ex) {
      log.debug(
          "[R-322] subdir marker probe failed for {} ({}): {}",
          repository.fullName(),
          branch,
          ex.getMessage());
      return null;
    }
  }

  private static String canonicalDisplayIdentity(ProjectRepo repo) {
    if (repo.getSourceKey() != null && !repo.getSourceKey().isBlank()) return repo.getSourceKey();
    if (repo.getGithubRepoFullName() != null && !repo.getGithubRepoFullName().isBlank()) {
      return repo.getGithubRepoFullName();
    }
    throw new ResponseStatusException(
        HttpStatus.UNPROCESSABLE_ENTITY, "SCM source has no repository display identity");
  }

  private static String requireCredentialFreeCloneUrl(String cloneUrl) {
    try {
      java.net.URI uri = java.net.URI.create(cloneUrl);
      String host = uri.getHost();
      boolean knownHost =
          "github.com".equalsIgnoreCase(host) || "bitbucket.org".equalsIgnoreCase(host);
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getUserInfo() != null
          || !knownHost
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw new IllegalArgumentException("unsafe clone URL");
      }
      return uri.toString();
    } catch (Exception ex) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "SCM provider returned an unsafe clone URL");
    }
  }

  /**
   * Parses the {@code root: <subdir>} key out of a {@code .executespec/root.yml} file. Tolerant of
   * comments, surrounding whitespace, and unquoted / single-/ double-quoted values. Rejects
   * absolute paths and traversal segments so a malicious marker can't escape the repo root.
   */
  static String parseRootYamlSubdir(String yamlBody) {
    if (yamlBody == null) return null;
    for (String rawLine : yamlBody.split("\\R")) {
      String line = rawLine;
      int hash = line.indexOf('#');
      if (hash >= 0) line = line.substring(0, hash);
      line = line.trim();
      if (line.isEmpty()) continue;
      if (!line.startsWith("root")) continue;
      int colon = line.indexOf(':');
      if (colon < 0) continue;
      String value = line.substring(colon + 1).trim();
      if (value.isEmpty()) return null;
      // Strip matching surrounding quotes if any.
      if ((value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2)
          || (value.startsWith("'") && value.endsWith("'") && value.length() >= 2)) {
        value = value.substring(1, value.length() - 1);
      }
      // Normalise leading "./" so "./apps/web" and "apps/web" agree.
      while (value.startsWith("./")) value = value.substring(2);
      // Trim a trailing slash so "apps/web/" and "apps/web" agree.
      while (value.endsWith("/") && value.length() > 1) {
        value = value.substring(0, value.length() - 1);
      }
      // Sanity gates: reject absolute, traversal, empty, oversized.
      if (value.isEmpty()) return null;
      if (value.startsWith("/")) return null;
      if (value.contains("..")) return null;
      if (value.length() > 512) return null;
      return value;
    }
    return null;
  }

  private static String serializeList(List<String> items) {
    if (items == null || items.isEmpty()) return null;
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append('"').append(items.get(i).replace("\"", "\\\"")).append('"');
    }
    return sb.append(']').toString();
  }
}
