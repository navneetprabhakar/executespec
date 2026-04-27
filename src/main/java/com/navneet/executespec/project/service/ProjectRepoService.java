package com.navneet.executespec.project.service;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.project.dto.CreateRepoRequest;
import com.navneet.executespec.project.dto.ProjectRepoDto;
import com.navneet.executespec.project.dto.ProjectRepoLinkDto;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectRepoService {

    private final ProjectRepoRepository repoRepo;
    private final ProjectRepoLinkRepository linkRepo;
    private final ProjectRepository projectRepo;
    private final StackDetector stackDetector;
    private final GitHubRepoService gitHub;
    private final com.navneet.executespec.auth.repository.AppUserRepository userRepo;
    /** V140 — gate per-tier repos-per-project cap on link + create-and-link. */
    private final com.navneet.executespec.auth.service.TierEnforcementService tierEnforcement;
    /** M-039 (Wave 3) — repo allowlist enforcement. */
    private final com.navneet.executespec.governance.GovernanceGuard governanceGuard;
    /** Lazy to break potential circular wiring on startup. */
    private final org.springframework.beans.factory.ObjectProvider<
            com.navneet.executespec.codeintel.ProjectArchitectureService> archServiceProvider;
    /** E1.5 — fires bootstrap PR async after a successful link. Lazy to keep
     *  service start-up cheap when the bootstrap pipeline isn't on the critical
     *  path (e.g. tests, admin tooling, migrations). */
    private final org.springframework.beans.factory.ObjectProvider<
            com.navneet.executespec.folder.BootstrapService> bootstrapServiceProvider;

    /* ────────────────────────────── repo lifecycle ─────────────────────────── */

    @Transactional
    public ProjectRepoDto createRepo(
            CreateRepoRequest req,
            String callerUserId,
            String callerOrgId,
            boolean isOrgAdmin) {

        ProjectRepo.Scope scope = ProjectRepo.Scope.valueOf(req.scope());

        if (scope == ProjectRepo.Scope.ORG) {
            if (callerOrgId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Caller has no org; cannot create ORG-scoped repo");
            }
            if (!isOrgAdmin) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Only ORG_ADMIN can create organization-scoped repos");
            }
            repoRepo.findByOwnerOrgIdAndGithubRepoFullName(callerOrgId, req.githubRepoFullName())
                    .ifPresent(existing -> {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Repo already exists for this organization");
                    });
        } else {
            repoRepo.findByOwnerUserIdAndGithubRepoFullName(callerUserId, req.githubRepoFullName())
                    .ifPresent(existing -> {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Repo already exists for this user");
                    });
        }

        String githubOwner = extractOwner(req.githubRepoFullName());

        String branch = req.defaultBranch() != null ? req.defaultBranch() : "main";
        String repoUrl = "https://github.com/" + req.githubRepoFullName();
        ProjectRepo entity = ProjectRepo.builder()
                .id(UUID.randomUUID())
                .scope(scope)
                .ownerOrgId(scope == ProjectRepo.Scope.ORG ? callerOrgId : null)
                .ownerUserId(scope == ProjectRepo.Scope.USER ? callerUserId : null)
                .githubRepoFullName(req.githubRepoFullName())
                .githubOwner(githubOwner)
                .defaultBranch(branch)
                .detectedStack(stackDetector.detect(req.githubRepoFullName(), null))
                .lastSyncedSha(gitHub.getBranchHeadSha(repoUrl, branch, null))
                .build();
        repoRepo.save(entity);

        log.info("Created ProjectRepo {} scope={} owner={}/{}",
                entity.getId(), scope, callerOrgId, callerUserId);
        return ProjectRepoDto.from(entity);
    }

    public List<ProjectRepoDto> listAvailable(String callerUserId, String callerOrgId, String scopeFilter) {
        Stream<ProjectRepo> stream = Stream.empty();

        boolean wantOrg = scopeFilter == null || "ALL".equals(scopeFilter) || "ORG".equals(scopeFilter);
        boolean wantUser = scopeFilter == null || "ALL".equals(scopeFilter) || "USER".equals(scopeFilter);

        if (wantOrg && callerOrgId != null) {
            stream = Stream.concat(stream, repoRepo.findByOwnerOrgId(callerOrgId).stream());
        }
        if (wantUser) {
            stream = Stream.concat(stream, repoRepo.findByOwnerUserId(callerUserId).stream());
        }
        return stream.map(ProjectRepoDto::from).collect(Collectors.toList());
    }

    /**
     * v2.5.12 — Sync is now more than a metadata refresh. For every project
     * this repo is linked to, we also regenerate the per-repo codemap + per-
     * repo architecture doc (Steps 1 + 2 of the 3-step arch pipeline). Step 3
     * (combined architecture doc) is deliberately skipped to keep sync
     * cheap — it re-runs on the next project-level Analyse.
     *
     * <p>Metadata updates (detected_stack + last_synced_sha) happen first;
     * then the per-project regen loop runs best-effort (a failure on one
     * project logs a warning but does not abort the sync).
     *
     * @param overridePat caller's PAT (used for both SHA lookup and codemap
     *                    fetch against private repos)
     */
    @Transactional
    public ProjectRepoDto syncRepo(UUID repoId, String callerUserId, String callerOrgId,
                                    boolean isOrgAdmin, String overridePat) {
        ProjectRepo repo = loadRepoForRead(repoId, callerUserId, callerOrgId);

        // 1. Metadata refresh (unchanged semantics).
        String detected = stackDetector.detect(repo.getGithubRepoFullName(), null);
        if (detected != null) repo.setDetectedStack(detected);
        String headSha = gitHub.getBranchHeadSha(
                "https://github.com/" + repo.getGithubRepoFullName(),
                repo.getDefaultBranch(),
                overridePat);
        if (headSha != null) repo.setLastSyncedSha(headSha);
        repo.setUpdatedAt(Instant.now());
        ProjectRepo saved = repoRepo.save(repo);

        // 2. Per-project regen (Steps 1 + 2). Multi-project repos fan out here.
        List<ProjectRepoLink> links = linkRepo.findByRepoId(repoId);
        if (!links.isEmpty()) {
            com.navneet.executespec.codeintel.ProjectArchitectureService archService =
                    archServiceProvider.getIfAvailable();
            if (archService == null) {
                log.warn("Sync: ProjectArchitectureService unavailable — skipping per-project regen for repoId={}", repoId);
            } else {
                for (ProjectRepoLink link : links) {
                    try {
                        archService.regenerateForRepo(link.getProjectId(), repoId, overridePat);
                    } catch (Exception e) {
                        log.warn("Sync: per-project regen failed project={} repo={}: {}",
                                link.getProjectId(), repo.getGithubRepoFullName(), e.getMessage());
                    }
                }
            }
        }
        return ProjectRepoDto.from(saved);
    }

    /** Legacy overload — metadata-only sync, kept for any caller that isn't ready
     *  to pass a PAT. Prefer the 5-arg form above for full behaviour. */
    @Transactional
    public ProjectRepoDto syncRepo(UUID repoId, String callerUserId, String callerOrgId, boolean isOrgAdmin) {
        return syncRepo(repoId, callerUserId, callerOrgId, isOrgAdmin, null);
    }

    @Transactional
    public void deleteRepo(UUID repoId, String callerUserId, String callerOrgId, boolean isOrgAdmin) {
        ProjectRepo repo = loadRepoForWrite(repoId, callerUserId, callerOrgId, isOrgAdmin);
        if (linkRepo.existsByRepoId(repoId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Repo is linked to one or more projects; unlink first");
        }
        repoRepo.delete(repo);
        log.info("Deleted ProjectRepo {}", repoId);
    }

    /* ─────────────────────────── project ↔ repo links ──────────────────────── */

    public List<ProjectRepoLinkDto> listProjectRepos(String projectId, String callerUserId, String callerOrgId) {
        requireProjectAccess(projectId, callerUserId, callerOrgId);
        List<ProjectRepoLink> links = linkRepo.findByProjectId(projectId);
        List<ProjectRepoLinkDto> out = new ArrayList<>(links.size());
        for (ProjectRepoLink l : links) {
            repoRepo.findById(l.getRepoId())
                    .ifPresent(r -> out.add(ProjectRepoLinkDto.from(l, r)));
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
        ProjectRepo repo = repoRepo.findById(repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repo not found"));

        // M-039 (Wave 3) — owner allowlist check. ProjectRepo.githubRepoFullName
        // is "owner/repo"; we extract the owner segment. SUPER_ADMIN bypass
        // is handled inside the guard.
        if (project.getOrgId() != null && repo.getGithubRepoFullName() != null) {
            String owner = repo.getGithubRepoFullName().contains("/")
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
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "ORG repo belongs to a different organization");
            }
        } else {
            if (!callerUserId.equals(repo.getOwnerUserId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Only the repo owner can link their personal repo");
            }
        }

        linkRepo.findByProjectIdAndRepoId(projectId, repoId).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Repo already linked to project");
        });

        ProjectRepoLink link = ProjectRepoLink.builder()
                .id(UUID.randomUUID())
                .projectId(projectId)
                .repoId(repoId)
                .linkedBy(callerUserId)
                .build();
        linkRepo.save(link);

        log.info("Linked repo {} ({}) to project {}", repoId, repo.getGithubRepoFullName(), projectId);

        // E1.5 — fire bootstrap async so the controller responds immediately;
        // the slow GH round-trip (PAT preflight + branch creation + content
        // PUTs + open PR) runs on the background executor. Failures persist
        // BOOTSTRAP_FAILED on the project_repo row + emit an activity event;
        // they never bubble back to this thread.
        com.navneet.executespec.folder.BootstrapService bootstrap = bootstrapServiceProvider.getIfAvailable();
        if (bootstrap != null) {
            String pat = userRepo.findById(callerUserId)
                    .map(u -> u.getGithubPat()).orElse(null);
            UUID actorUserId = parseUuidOrNull(callerUserId);
            UUID projectUuid = parseUuidOrNull(projectId);
            if (projectUuid != null) {
                bootstrap.bootstrapAsync(repo.getId(), projectUuid, project.getName(), actorUserId, pat);
            }
        }

        return ProjectRepoLinkDto.from(link, repo);
    }

    private static UUID parseUuidOrNull(String s) {
        try { return s == null ? null : UUID.fromString(s); }
        catch (IllegalArgumentException ex) { return null; }
    }

    @Transactional
    public void unlinkFromProject(String projectId, UUID repoId, String callerUserId, String callerOrgId) {
        requireProjectAccess(projectId, callerUserId, callerOrgId);
        linkRepo.findByProjectIdAndRepoId(projectId, repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found"));
        linkRepo.deleteByProjectIdAndRepoId(projectId, repoId);
        log.info("Unlinked repo {} from project {}", repoId, projectId);
    }

    @Transactional
    public ProjectRepoLinkDto updateRelationships(
            String projectId, UUID repoId, List<String> relationships,
            String callerUserId, String callerOrgId) {

        requireProjectAccess(projectId, callerUserId, callerOrgId);
        ProjectRepoLink link = linkRepo.findByProjectIdAndRepoId(projectId, repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found"));
        link.setRelationships(serializeList(relationships));
        linkRepo.save(link);

        ProjectRepo repo = repoRepo.findById(repoId).orElseThrow();
        return ProjectRepoLinkDto.from(link, repo);
    }

    /**
     * Pilot v3.0 — resolve a list of repo IDs (as provided by {@code RunRequest.selectedRepoIds})
     * into the underlying entities, filtering to those actually linked to {@code projectId}.
     * Silently drops IDs that are not linked (defensive — UI should only send linked IDs).
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
     * Create a brand-new GitHub repo and link it to the project in one shot.
     * Used by the Launch flow when a user describes a new microservice.
     * Always creates ORG-scoped repos — new-repo-at-Launch is an enterprise path.
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

        String cloneUrl = gitHub.createRepo(
                description, name, "public".equals(visibility) ? "public" : "private",
                pat, githubOwner);
        if (cloneUrl == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "GitHub repo creation failed — check PAT + owner access");
        }
        // cloneUrl = https://github.com/{owner}/{name}.git (possibly with suffix)
        String fullName = extractFullNameFromCloneUrl(cloneUrl);
        if (fullName == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Created repo but could not parse clone URL: " + cloneUrl);
        }

        // v2.5.2 — auto-provision the PR webhook on new repos so the
        // merge-cascade pipeline fires without a manual setup step.
        boolean webhookOk = false;
        try {
            webhookOk = gitHub.installWebhook(fullName, pat);
        } catch (Exception e) {
            log.warn("installWebhook threw for {}: {}", fullName, e.getMessage());
        }

        ProjectRepo entity = ProjectRepo.builder()
                .id(UUID.randomUUID())
                .scope(project.getOrgId() != null ? ProjectRepo.Scope.ORG : ProjectRepo.Scope.USER)
                .ownerOrgId(project.getOrgId())
                .ownerUserId(project.getOrgId() != null ? null : callerUserId)
                .githubRepoFullName(fullName)
                .githubOwner(extractOwner(fullName))
                .defaultBranch("main")
                .detectedStack(null)      // fresh repo — no stack detected yet
                .lastSyncedSha(null)
                .webhookInstalled(webhookOk)
                .build();
        repoRepo.save(entity);

        linkRepo.save(ProjectRepoLink.builder()
                .id(UUID.randomUUID())
                .projectId(projectId)
                .repoId(entity.getId())
                .linkedBy(callerUserId)
                .build());
        log.info("Created+linked new repo {} ({}) for project {} [webhook={}]",
                entity.getId(), fullName, projectId, webhookOk);
        return entity;
    }

    /**
     * v2.5.2 — manually install the PR webhook on a repo the user linked (rather
     * than created). Requires the caller's PAT to have admin:repo_hook on the
     * target repo. Idempotent: GitHub's "already exists" path is treated as success.
     */
    @Transactional
    public boolean installWebhook(UUID repoId, String pat, String callerUserId, String callerOrgId) {
        ProjectRepo repo = loadRepoForRead(repoId, callerUserId, callerOrgId);
        boolean ok = gitHub.installWebhook(repo.getGithubRepoFullName(), pat);
        if (ok) {
            repo.setWebhookInstalled(true);
            repo.setUpdatedAt(Instant.now());
            repoRepo.save(repo);
        }
        return ok;
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

    /* ──────────────────────────────── helpers ──────────────────────────────── */

    private ProjectRepo loadRepoForRead(UUID repoId, String callerUserId, String callerOrgId) {
        ProjectRepo repo = repoRepo.findById(repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repo not found"));
        // v2.5.12 (Gap #1) — SUPER_ADMIN sees everything. Same bypass shape used
        // by TierEnforcementService.assertCanSubmitRun + AdminReconciliationService.
        // The DB hit is bounded (per-request, single PK lookup) and only triggers
        // when the user isn't already the owner.
        boolean visible = (repo.getScope() == ProjectRepo.Scope.ORG && repo.getOwnerOrgId().equals(callerOrgId))
                || (repo.getScope() == ProjectRepo.Scope.USER && repo.getOwnerUserId().equals(callerUserId));
        if (!visible) {
            boolean isSuperAdmin = callerUserId != null && userRepo.findById(callerUserId)
                    .map(u -> "SUPER_ADMIN".equals(u.getGlobalRole()))
                    .orElse(false);
            if (isSuperAdmin) return repo;
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Repo not visible to caller");
        }
        return repo;
    }

    private ProjectRepo loadRepoForWrite(UUID repoId, String callerUserId, String callerOrgId, boolean isOrgAdmin) {
        ProjectRepo repo = loadRepoForRead(repoId, callerUserId, callerOrgId);
        if (repo.getScope() == ProjectRepo.Scope.ORG && !isOrgAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only ORG_ADMIN can modify organization-scoped repos");
        }
        if (repo.getScope() == ProjectRepo.Scope.USER && !callerUserId.equals(repo.getOwnerUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the owner can modify their personal repo");
        }
        return repo;
    }

    private Project requireProjectAccess(String projectId, String callerUserId, String callerOrgId) {
        Project project = projectRepo.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        if (callerOrgId == null || !callerOrgId.equals(project.getOrgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project belongs to another organization");
        }
        return project;
    }

    private static String extractOwner(String fullName) {
        int slash = fullName.indexOf('/');
        return slash > 0 ? fullName.substring(0, slash) : fullName;
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
