package com.navneet.executespec.codeintel;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.RepoArchitectureDoc;
import com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.codeintel.repository.ProjectGeneratedDocRepository;
import com.navneet.executespec.codeintel.repository.RepoArchitectureDocRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Caps every version-tracked project artifact at the latest {@link #MAX_VERSIONS_RETAINED} entries.
 * Called by the corresponding write paths after a new version row is saved, so retention happens
 * eagerly at write time (no scheduled job needed).
 *
 * <p>Grouping keys per artifact:
 *
 * <ul>
 *   <li>{@link ProjectCodeMap}: ({@code projectId}, {@code repoId})
 *   <li>{@link RepoArchitectureDoc}: ({@code projectId}, {@code repoId})
 *   <li>{@link ProjectArchitectureDoc}: ({@code projectId})
 *   <li>{@link ProjectGeneratedDoc}: ({@code projectId}, {@code docType})
 * </ul>
 *
 * <p>Every prune is best-effort — a failure to delete legacy rows must never break the regen flow
 * that just persisted a new version. The UI side surfaces a "showing latest 20 versions; older
 * versions are pruned" notice on the version-history controls so customers don't expect deep
 * history.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VersionRetentionService {

  /** Hard cap on retained versions per artifact group. */
  public static final int MAX_VERSIONS_RETAINED = 20;

  private final ProjectCodeMapRepository codeMapRepo;
  private final RepoArchitectureDocRepository repoArchRepo;
  private final ProjectArchitectureDocRepository archDocRepo;
  private final ProjectGeneratedDocRepository generatedDocRepo;

  @Transactional
  public void pruneCodeMap(String projectId, UUID repoId) {
    if (projectId == null || repoId == null) return;
    try {
      List<ProjectCodeMap> all =
          codeMapRepo.findByProjectIdAndRepoIdOrderByVersionDesc(projectId, repoId);
      List<ProjectCodeMap> toDelete = tailToDelete(all);
      if (!toDelete.isEmpty()) {
        codeMapRepo.deleteAllInBatch(toDelete);
        log.info(
            "[{}] codemap retention pruned {} row(s) for repo {} (kept latest {})",
            projectId,
            toDelete.size(),
            repoId,
            MAX_VERSIONS_RETAINED);
      }
    } catch (Exception e) {
      log.warn(
          "[{}] codemap retention prune failed for repo {}: {}", projectId, repoId, e.getMessage());
    }
  }

  @Transactional
  public void pruneRepoArch(String projectId, UUID repoId) {
    if (projectId == null || repoId == null) return;
    try {
      List<RepoArchitectureDoc> all =
          repoArchRepo.findByProjectIdAndRepoIdOrderByVersionDesc(projectId, repoId);
      List<RepoArchitectureDoc> toDelete = tailToDelete(all);
      if (!toDelete.isEmpty()) {
        repoArchRepo.deleteAllInBatch(toDelete);
        log.info(
            "[{}] repo-arch retention pruned {} row(s) for repo {} (kept latest {})",
            projectId,
            toDelete.size(),
            repoId,
            MAX_VERSIONS_RETAINED);
      }
    } catch (Exception e) {
      log.warn(
          "[{}] repo-arch retention prune failed for repo {}: {}",
          projectId,
          repoId,
          e.getMessage());
    }
  }

  @Transactional
  public void pruneProjectArch(String projectId) {
    if (projectId == null) return;
    try {
      List<ProjectArchitectureDoc> all = archDocRepo.findByProjectIdOrderByVersionDesc(projectId);
      List<ProjectArchitectureDoc> toDelete = tailToDelete(all);
      if (!toDelete.isEmpty()) {
        archDocRepo.deleteAllInBatch(toDelete);
        log.info(
            "[{}] project-arch retention pruned {} row(s) (kept latest {})",
            projectId,
            toDelete.size(),
            MAX_VERSIONS_RETAINED);
      }
    } catch (Exception e) {
      log.warn("[{}] project-arch retention prune failed: {}", projectId, e.getMessage());
    }
  }

  @Transactional
  public void pruneGeneratedDoc(String projectId, ProjectGeneratedDoc.DocType docType) {
    if (projectId == null || docType == null) return;
    try {
      List<ProjectGeneratedDoc> all =
          generatedDocRepo.findByProjectIdAndDocTypeOrderByVersionDesc(projectId, docType);
      List<ProjectGeneratedDoc> toDelete = tailToDelete(all);
      if (!toDelete.isEmpty()) {
        generatedDocRepo.deleteAllInBatch(toDelete);
        log.info(
            "[{}] generated-doc retention pruned {} row(s) for docType {} (kept latest {})",
            projectId,
            toDelete.size(),
            docType,
            MAX_VERSIONS_RETAINED);
      }
    } catch (Exception e) {
      log.warn(
          "[{}] generated-doc retention prune failed for docType {}: {}",
          projectId,
          docType,
          e.getMessage());
    }
  }

  private static <T> List<T> tailToDelete(List<T> versionsDescending) {
    if (versionsDescending == null || versionsDescending.size() <= MAX_VERSIONS_RETAINED) {
      return List.of();
    }
    return versionsDescending.subList(MAX_VERSIONS_RETAINED, versionsDescending.size());
  }
}
