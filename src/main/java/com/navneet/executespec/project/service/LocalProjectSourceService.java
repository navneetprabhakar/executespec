package com.navneet.executespec.project.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.project.dto.LocalProjectSourceDto;
import com.navneet.executespec.project.dto.LocalSourceHeartbeatRequest;
import com.navneet.executespec.project.dto.LocalSourceHeartbeatResponse;
import com.navneet.executespec.project.dto.ProjectRepoLinkDto;
import com.navneet.executespec.project.dto.RegisterLocalSourceRequest;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** First-class, path-free LOCAL project-source registration and lease renewal. */
@Service
public class LocalProjectSourceService {

  private static final int DEFAULT_LEASE_SECONDS = 90;
  private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:[\\\\/].*");
  private static final Pattern CONTROL = Pattern.compile(".*[\\p{Cntrl}].*", Pattern.DOTALL);
  private static final List<String> PATH_METADATA_TOKENS =
      List.of("path", "cwd", "directory", "workingdir", "workingdirectory", "absolutepath");

  private final ProjectRepoRepository repoRepository;
  private final ProjectRepoLinkRepository linkRepository;
  private final ProjectRepository projectRepository;
  private final ProjectMemberRepository projectMemberRepository;
  private final TierEnforcementService tierEnforcementService;
  private final Clock clock;

  /**
   * Spring-selected production constructor (system clock). The package-private overload below
   * exists only for deterministic tests and must never be ambiguous at startup.
   */
  @org.springframework.beans.factory.annotation.Autowired
  public LocalProjectSourceService(
      ProjectRepoRepository repoRepository,
      ProjectRepoLinkRepository linkRepository,
      ProjectRepository projectRepository,
      ProjectMemberRepository projectMemberRepository,
      TierEnforcementService tierEnforcementService) {
    this(
        repoRepository,
        linkRepository,
        projectRepository,
        projectMemberRepository,
        tierEnforcementService,
        Clock.systemUTC());
  }

  LocalProjectSourceService(
      ProjectRepoRepository repoRepository,
      ProjectRepoLinkRepository linkRepository,
      ProjectRepository projectRepository,
      ProjectMemberRepository projectMemberRepository,
      TierEnforcementService tierEnforcementService,
      Clock clock) {
    this.repoRepository = repoRepository;
    this.linkRepository = linkRepository;
    this.projectRepository = projectRepository;
    this.projectMemberRepository = projectMemberRepository;
    this.tierEnforcementService = tierEnforcementService;
    this.clock = clock;
  }

  @Transactional
  public LocalProjectSourceDto register(
      String projectId,
      RegisterLocalSourceRequest request,
      String callerUserId,
      String callerOrgId,
      boolean superAdmin) {
    Project project = requireProjectAccess(projectId, callerUserId, callerOrgId, superAdmin);
    validate(request);

    Instant now = clock.instant();
    int leaseSeconds =
        request.leaseSeconds() == null ? DEFAULT_LEASE_SECONDS : request.leaseSeconds();

    ProjectRepo source =
        repoRepository
            .findByOwnerUserIdAndLocalFolderId(callerUserId, request.localFolderId())
            .orElse(null);
    boolean sourceCreated = source == null;
    if (sourceCreated) {
      source =
          ProjectRepo.builder()
              .id(UUID.randomUUID())
              .scope(ProjectRepo.Scope.USER)
              .ownerUserId(callerUserId)
              .sourceType(ProjectRepo.SourceType.LOCAL.name())
              .scmProvider(null)
              .sourceKey(request.localFolderId().toString())
              .defaultBranch(null)
              .localFolderId(request.localFolderId())
              .localInstallationId(request.installationId())
              .sourceReadinessStatus(ProjectRepo.SourceReadiness.LINKED.name())
              .sourceReadinessReason("LOCAL_CONNECTOR_REGISTERED_UNVERIFIED")
              .sourceReadinessCheckedAt(now)
              .createdAt(now)
              .build();
    } else {
      if (!ProjectRepo.SourceType.LOCAL.name().equals(source.getSourceType())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Source identity is not LOCAL");
      }
      if (!request.installationId().equals(source.getLocalInstallationId())) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "localFolderId is already registered by another client installation");
      }
    }

    source.setSourceDisplayName(request.displayName().trim());
    source.setSourceRevision(blankToNull(request.revision()));
    source.setSourceCapabilities(normalizeCapabilities(request.capabilities()));
    source.setSourceMetadata(normalizeMetadata(request.metadata()));
    source.setSourceLastSeenAt(now);
    source.setSourceLeaseExpiresAt(now.plusSeconds(leaseSeconds));
    source.setUpdatedAt(now);
    source = repoRepository.save(source);

    ProjectRepoLink link =
        linkRepository.findByProjectIdAndRepoId(projectId, source.getId()).orElse(null);
    boolean linkCreated = link == null;
    if (link == null) {
      tierEnforcementService.assertCanLinkRepoToProject(callerUserId, project.getId());
      link =
          linkRepository.save(
              ProjectRepoLink.builder()
                  .id(UUID.randomUUID())
                  .projectId(projectId)
                  .repoId(source.getId())
                  .linkedBy(callerUserId)
                  .linkedAt(now)
                  .build());
    }

    return new LocalProjectSourceDto(
        sourceCreated || linkCreated, ProjectRepoLinkDto.from(link, source, now));
  }

  /**
   * Renew the lease of an already-registered local source. Heartbeats never create sources or
   * links; an unknown folder, a foreign installation, or a missing project link fails closed so a
   * lease cannot be kept alive by anything but the registered connector itself.
   */
  @Transactional
  public LocalSourceHeartbeatResponse heartbeat(
      String projectId,
      UUID localFolderId,
      LocalSourceHeartbeatRequest request,
      String callerUserId,
      String callerOrgId,
      boolean superAdmin) {
    requireProjectAccess(projectId, callerUserId, callerOrgId, superAdmin);
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request is required");
    }
    requireRandomV4(localFolderId, "localFolderId");
    requireRandomV4(request.installationId(), "installationId");
    if (request.revision() != null && CONTROL.matcher(request.revision()).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "revision contains controls");
    }
    if (request.revision() != null && request.revision().length() > 128) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "revision exceeds 128 characters");
    }
    rejectAbsolutePath(request.revision(), "revision");
    if (request.leaseSeconds() != null
        && (request.leaseSeconds() < 30 || request.leaseSeconds() > 300)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "leaseSeconds must be between 30 and 300");
    }

    ProjectRepo source =
        repoRepository
            .findByOwnerUserIdAndLocalFolderId(callerUserId, localFolderId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Local source not found"));
    if (!ProjectRepo.SourceType.LOCAL.name().equals(source.getSourceType())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Source identity is not LOCAL");
    }
    if (!request.installationId().equals(source.getLocalInstallationId())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "localFolderId is held by another client installation");
    }
    linkRepository
        .findByProjectIdAndRepoId(projectId, source.getId())
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Local source is not linked to this project"));

    Instant now = clock.instant();
    int leaseSeconds =
        request.leaseSeconds() == null ? DEFAULT_LEASE_SECONDS : request.leaseSeconds();
    if (request.revision() != null && !request.revision().isBlank()) {
      source.setSourceRevision(request.revision().trim());
    }
    source.setSourceLastSeenAt(now);
    source.setSourceLeaseExpiresAt(now.plusSeconds(leaseSeconds));
    source.setUpdatedAt(now);
    source = repoRepository.save(source);

    return new LocalSourceHeartbeatResponse(
        localFolderId,
        source.localConnectionStatus(now),
        source.getSourceLastSeenAt(),
        source.getSourceLeaseExpiresAt(),
        source.getSourceRevision());
  }

  private Project requireProjectAccess(
      String projectId, String callerUserId, String callerOrgId, boolean superAdmin) {
    Project project =
        projectRepository
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    boolean sameOrg = callerOrgId != null && callerOrgId.equals(project.getOrgId());
    var membership =
        callerUserId == null
            ? java.util.Optional.<com.navneet.executespec.auth.entity.ProjectMember>empty()
            : projectMemberRepository.findByProjectIdAndUserId(projectId, callerUserId);
    boolean projectMember =
        membership.isPresent() && !"PROJECT_VIEWER".equals(membership.get().getProjectRole());
    if (!superAdmin && !sameOrg && !projectMember) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Project belongs to another organization");
    }
    return project;
  }

  private static void validate(RegisterLocalSourceRequest request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request is required");
    }
    requireRandomV4(request.localFolderId(), "localFolderId");
    requireRandomV4(request.installationId(), "installationId");
    if (request.displayName() == null
        || request.displayName().isBlank()
        || request.displayName().length() > 160) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid displayName");
    }
    if (CONTROL.matcher(request.displayName()).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "displayName contains controls");
    }
    rejectAbsolutePath(request.displayName(), "displayName");
    if (request.revision() != null && CONTROL.matcher(request.revision()).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "revision contains controls");
    }
    if (request.revision() != null && request.revision().length() > 128) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "revision exceeds 128 characters");
    }
    rejectAbsolutePath(request.revision(), "revision");
    if (request.leaseSeconds() != null
        && (request.leaseSeconds() < 30 || request.leaseSeconds() > 300)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "leaseSeconds must be between 30 and 300");
    }
    normalizeCapabilities(request.capabilities());
    normalizeMetadata(request.metadata());
  }

  private static void requireRandomV4(UUID value, String field) {
    if (value == null || value.version() != 4 || value.variant() != 2) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a random UUIDv4");
    }
  }

  private static List<String> normalizeCapabilities(List<String> capabilities) {
    if (capabilities == null || capabilities.isEmpty()) return List.of();
    if (capabilities.size() > 16) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at most 16 capabilities allowed");
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String capability : capabilities) {
      if (capability == null
          || capability.isBlank()
          || capability.length() > 64
          || !capability.matches("[A-Z][A-Z0-9_]*")) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid capability");
      }
      normalized.add(capability);
    }
    return List.copyOf(normalized);
  }

  private static Map<String, String> normalizeMetadata(Map<String, String> metadata) {
    if (metadata == null || metadata.isEmpty()) return Map.of();
    if (metadata.size() > 12) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "at most 12 metadata entries allowed");
    }
    Map<String, String> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : metadata.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key == null
          || !key.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")
          || value == null
          || value.length() > 256
          || CONTROL.matcher(value).matches()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid source metadata");
      }
      String compactKey = key.toLowerCase(Locale.ROOT).replaceAll("[_.-]", "");
      if (PATH_METADATA_TOKENS.stream().anyMatch(compactKey::contains)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "filesystem path metadata is not accepted");
      }
      rejectAbsolutePath(value, "metadata");
      normalized.put(key, value);
    }
    return Map.copyOf(normalized);
  }

  private static void rejectAbsolutePath(String value, String field) {
    if (value == null) return;
    String trimmed = value.trim();
    if (trimmed.startsWith("/")
        || trimmed.startsWith("~/")
        || trimmed.startsWith("\\\\")
        || trimmed.regionMatches(true, 0, "file:", 0, 5)
        || WINDOWS_ABSOLUTE.matcher(trimmed).matches()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, field + " must not contain an absolute filesystem path");
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
