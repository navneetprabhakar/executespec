package com.navneet.executespec.repositoryintel;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.rag.CodeIndexWorkspaceIdentity;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * Resolves only the canonical code-index clone after rechecking actor, host tenant, project, link,
 * source lifecycle, snapshot revision, and filesystem containment. It does not clone or refresh.
 */
@Component
@RequiredArgsConstructor
public final class AccessCheckedRepositoryRootResolver implements RepositoryRootResolver {

  private final AppUserRepository userRepository;
  private final OrgRepository orgRepository;
  private final OrgMemberRepository orgMemberRepository;
  private final ProjectAccessGuard projectAccessGuard;
  private final ProjectRepoRepository repository;
  private final ProjectRepoLinkRepository linkRepository;
  private final WorkspaceInitService workspaceInitService;
  private final AgentProperties agentProperties;

  @Override
  public AuthorizedRepositoryHandle resolve(RepositoryQuery query) {
    if (query == null) throw new IllegalArgumentException("query is required");
    return resolve(RepositoryTarget.from(query), query.expectedSnapshot());
  }

  @Override
  public AuthorizedRepositoryHandle resolve(RepositoryTarget target) {
    if (target == null) throw new IllegalArgumentException("target is required");
    return resolve(target, null);
  }

  private AuthorizedRepositoryHandle resolve(
      RepositoryTarget target, RepositorySnapshotId expectedSnapshot) {
    RepositoryAccessContext access = target.accessContext();
    AppUser actor =
        userRepository
            .findById(access.actorUserId())
            .orElseThrow(() -> denied(RepositoryAuthorityException.Reason.ACTOR_UNAVAILABLE));
    if (!"ACTIVE".equals(actor.getStatus()) || actor.getDeletedAt() != null) {
      throw denied(RepositoryAuthorityException.Reason.ACTOR_INACTIVE);
    }

    Project project;
    try {
      OrgMember actorOrgMembership = null;
      if (actor.getOrgId() != null && !"SUPER_ADMIN".equals(actor.getGlobalRole())) {
        actorOrgMembership =
            orgMemberRepository
                .findByOrgIdAndUserId(actor.getOrgId(), actor.getId())
                .orElseThrow(
                    () -> denied(RepositoryAuthorityException.Reason.ACTOR_MEMBERSHIP_UNAVAILABLE));
      }
      project =
          projectAccessGuard.assertCanRead(
              target.projectId(), new AppUserDetails(actor, actorOrgMembership));
    } catch (ResponseStatusException denied) {
      throw denied(RepositoryAuthorityException.Reason.PROJECT_ACCESS_DENIED, denied);
    }
    if (project == null) {
      throw denied(RepositoryAuthorityException.Reason.PROJECT_UNAVAILABLE);
    }
    if (!target.projectId().equals(project.getId())) {
      throw denied(RepositoryAuthorityException.Reason.PROJECT_IDENTITY_MISMATCH);
    }
    if (project.isSoftDeleted() || !"ACTIVE".equals(project.getStatus())) {
      throw denied(RepositoryAuthorityException.Reason.PROJECT_INACTIVE);
    }
    if (!project.getOrgId().equals(access.tenantOrgId())) {
      throw denied(RepositoryAuthorityException.Reason.TENANT_MISMATCH);
    }
    Org tenant =
        orgRepository
            .findById(project.getOrgId())
            .orElseThrow(() -> denied(RepositoryAuthorityException.Reason.TENANT_UNAVAILABLE));
    if (!"ACTIVE".equals(tenant.getStatus()) || tenant.getDeletedAt() != null) {
      throw denied(RepositoryAuthorityException.Reason.TENANT_INACTIVE);
    }

    UUID repositoryId;
    try {
      repositoryId = UUID.fromString(target.repositoryId());
    } catch (IllegalArgumentException invalid) {
      throw denied(RepositoryAuthorityException.Reason.REPOSITORY_ID_INVALID, invalid);
    }
    ProjectRepo source =
        repository
            .findById(repositoryId)
            .orElseThrow(() -> denied(RepositoryAuthorityException.Reason.REPOSITORY_UNAVAILABLE));
    ProjectRepoLink link =
        linkRepository
            .findByProjectIdAndRepoId(project.getId(), repositoryId)
            .orElseThrow(() -> denied(RepositoryAuthorityException.Reason.REPOSITORY_NOT_LINKED));
    if (!repositoryId.equals(source.getId())) {
      throw denied(RepositoryAuthorityException.Reason.REPOSITORY_IDENTITY_MISMATCH);
    }
    if (!project.getId().equals(link.getProjectId()) || !repositoryId.equals(link.getRepoId())) {
      throw denied(RepositoryAuthorityException.Reason.REPOSITORY_NOT_LINKED);
    }
    validateSourceOwnership(source, link, project, actor);
    if (!ProjectRepo.SourceType.SCM.name().equals(source.getSourceType())) {
      throw denied(RepositoryAuthorityException.Reason.REPOSITORY_SOURCE_UNSUPPORTED);
    }
    if (!ProjectRepo.SourceReadiness.READY.name().equals(source.getSourceReadinessStatus())) {
      throw denied(RepositoryAuthorityException.Reason.REPOSITORY_NOT_READY);
    }

    String sourceRevision = CodeIndexWorkspaceIdentity.sourceRevision(source);
    String expectedCommit = expectedSnapshot == null ? null : expectedSnapshot.commitSha();
    if (expectedCommit != null && !expectedCommit.equalsIgnoreCase(sourceRevision)) {
      throw denied(RepositoryAuthorityException.Reason.SNAPSHOT_SOURCE_MISMATCH);
    }
    Path root =
        canonicalMaterialization(CodeIndexWorkspaceIdentity.workspaceId(project.getId(), source));
    return expectedSnapshot == null
        ? new AuthorizedRepositoryHandle(
            actor.getId(),
            project.getOrgId(),
            project.getId(),
            source.getId().toString(),
            sourceRevision,
            root)
        : new AuthorizedRepositoryHandle(
            actor.getId(),
            project.getOrgId(),
            project.getId(),
            source.getId().toString(),
            expectedSnapshot.stableId(),
            sourceRevision,
            root);
  }

  private void validateSourceOwnership(
      ProjectRepo source, ProjectRepoLink link, Project project, AppUser actor) {
    if (source.getScope() == ProjectRepo.Scope.ORG) {
      if (!project.getOrgId().equals(source.getOwnerOrgId())) {
        throw denied(RepositoryAuthorityException.Reason.REPOSITORY_OWNER_MISMATCH);
      }
      return;
    }
    if (source.getScope() != ProjectRepo.Scope.USER
        || source.getOwnerUserId() == null
        || !source.getOwnerUserId().equals(actor.getId())
        || !source.getOwnerUserId().equals(link.getLinkedBy())) {
      throw denied(RepositoryAuthorityException.Reason.REPOSITORY_OWNER_MISMATCH);
    }
    AppUser owner =
        userRepository
            .findById(source.getOwnerUserId())
            .orElseThrow(
                () -> denied(RepositoryAuthorityException.Reason.REPOSITORY_OWNER_INACTIVE));
    if (!"ACTIVE".equals(owner.getStatus()) || owner.getDeletedAt() != null) {
      throw denied(RepositoryAuthorityException.Reason.REPOSITORY_OWNER_INACTIVE);
    }
  }

  private Path canonicalMaterialization(String workspaceId) {
    String configuredValue = agentProperties.getWorkspace();
    if (configuredValue == null || configuredValue.isBlank()) {
      throw denied(RepositoryAuthorityException.Reason.MATERIALIZATION_UNAVAILABLE);
    }
    Path configured = Path.of(configuredValue).toAbsolutePath().normalize();
    Path candidate =
        workspaceInitService.runWorkspacePath(workspaceId).toAbsolutePath().normalize();
    if (!candidate.startsWith(configured) || !configured.equals(candidate.getParent())) {
      throw denied(RepositoryAuthorityException.Reason.MATERIALIZATION_OUTSIDE_WORKSPACE);
    }
    rejectSymlinkSegments(configured, candidate);
    try {
      Path realWorkspace = configured.toRealPath();
      Path realRoot = candidate.toRealPath();
      if (!realRoot.startsWith(realWorkspace)) {
        throw denied(RepositoryAuthorityException.Reason.MATERIALIZATION_OUTSIDE_WORKSPACE);
      }
      if (!Files.isDirectory(realRoot, NOFOLLOW_LINKS)) {
        throw denied(RepositoryAuthorityException.Reason.MATERIALIZATION_UNAVAILABLE);
      }
      Path git = realRoot.resolve(".git");
      if (Files.isSymbolicLink(git) || !Files.isDirectory(git, NOFOLLOW_LINKS)) {
        throw denied(RepositoryAuthorityException.Reason.MATERIALIZATION_NOT_REPOSITORY);
      }
      return realRoot;
    } catch (IOException unavailable) {
      throw denied(RepositoryAuthorityException.Reason.MATERIALIZATION_UNAVAILABLE, unavailable);
    }
  }

  private static void rejectSymlinkSegments(Path configured, Path candidate) {
    Path current = configured;
    Path relative = configured.relativize(candidate);
    for (Path segment : relative) {
      current = current.resolve(segment);
      if (Files.isSymbolicLink(current)) {
        throw denied(RepositoryAuthorityException.Reason.MATERIALIZATION_SYMLINK_REJECTED);
      }
    }
  }

  private static RepositoryAuthorityException denied(RepositoryAuthorityException.Reason reason) {
    return new RepositoryAuthorityException(reason);
  }

  private static RepositoryAuthorityException denied(
      RepositoryAuthorityException.Reason reason, Throwable cause) {
    return new RepositoryAuthorityException(reason, cause);
  }
}
