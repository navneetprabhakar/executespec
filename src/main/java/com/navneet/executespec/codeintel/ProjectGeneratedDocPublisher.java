package com.navneet.executespec.codeintel;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.repository.ProjectGeneratedDocRepository;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the short, serialized publication transaction for generated project documents.
 *
 * <p>Provider work finishes before this boundary. Publication locks the project, revalidates the
 * exact architecture source, deduplicates an already-published source, assigns a version, and
 * applies retention atomically.
 */
final class ProjectGeneratedDocPublisher {

  private final ProjectArchitectureService architectureService;
  private final ProjectGeneratedDocRepository docRepo;
  private final VersionRetentionService versionRetention;
  private final ProjectRepository projectRepo;
  private final TransactionTemplate transactionTemplate;

  ProjectGeneratedDocPublisher(
      ProjectArchitectureService architectureService,
      ProjectGeneratedDocRepository docRepo,
      VersionRetentionService versionRetention,
      ProjectRepository projectRepo,
      PlatformTransactionManager transactionManager) {
    this.architectureService = architectureService;
    this.docRepo = docRepo;
    this.versionRetention = versionRetention;
    this.projectRepo = projectRepo;
    this.transactionTemplate =
        transactionManager == null ? null : new TransactionTemplate(transactionManager);
  }

  ProjectGeneratedDoc publish(
      ProjectGeneratedDoc candidate, Predicate<ProjectGeneratedDoc> reusableDocument) {
    return publish(candidate, reusableDocument, ignored -> {});
  }

  ProjectGeneratedDoc publish(
      ProjectGeneratedDoc candidate,
      Predicate<ProjectGeneratedDoc> reusableDocument,
      Consumer<ProjectGeneratedDoc> publicationFence) {
    Supplier<ProjectGeneratedDoc> persistence =
        () -> {
          projectRepo
              .findByIdForQuotaUpdate(candidate.getProjectId())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "project not found: " + candidate.getProjectId()));
          ProjectArchitectureDoc current =
              architectureService
                  .latest(candidate.getProjectId())
                  .orElseThrow(ProjectDocGeneratorService.StaleSourceException::new);
          if (!Objects.equals(current.getVersion(), candidate.getSourceArchVersion())
              || !Objects.equals(
                  current.getGeneratedFromShas(), candidate.getSourceShasFingerprint())) {
            throw new ProjectDocGeneratorService.StaleSourceException();
          }
          Optional<ProjectGeneratedDoc> alreadyPersisted =
              candidate.getSourceArchVersion() == null
                      || candidate.getSourceShasFingerprint() == null
                  ? Optional.empty()
                  : docRepo
                      .findFirstByProjectIdAndDocTypeAndSourceArchVersionAndSourceShasFingerprintOrderByVersionDesc(
                          candidate.getProjectId(),
                          candidate.getDocType(),
                          candidate.getSourceArchVersion(),
                          candidate.getSourceShasFingerprint())
                      .filter(doc -> doc.getContent() != null && !doc.getContent().isBlank())
                      .filter(reusableDocument);
          if (alreadyPersisted.isPresent()) {
            publicationFence.accept(alreadyPersisted.get());
            return alreadyPersisted.get();
          }

          int nextVersion =
              docRepo
                  .findFirstByProjectIdAndDocTypeOrderByVersionDesc(
                      candidate.getProjectId(), candidate.getDocType())
                  .map(doc -> doc.getVersion() + 1)
                  .orElse(1);
          candidate.setVersion(nextVersion);
          ProjectGeneratedDoc saved = docRepo.save(candidate);
          publicationFence.accept(saved);
          versionRetention.pruneGeneratedDoc(candidate.getProjectId(), candidate.getDocType());
          return saved;
        };
    return transactionTemplate == null
        ? persistence.get()
        : transactionTemplate.execute(status -> persistence.get());
  }

  ProjectGeneratedDoc publishValidated(
      ProjectGeneratedDoc candidate,
      java.util.Set<String> sourceRepositories,
      String codemapEvidence,
      Consumer<ProjectGeneratedDoc> publicationFence) {
    return publish(
        candidate,
        existing ->
            ProjectGeneratedDocValidator.validate(
                    existing.getDocType(),
                    existing.getContent(),
                    sourceRepositories,
                    codemapEvidence)
                .isEmpty(),
        publicationFence);
  }
}
