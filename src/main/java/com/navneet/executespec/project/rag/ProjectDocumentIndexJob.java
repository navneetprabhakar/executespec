package com.navneet.executespec.project.rag;

import java.time.OffsetDateTime;
import java.util.List;

import com.navneet.executespec.project.rag.entity.ProjectDocument;
import com.navneet.executespec.project.rag.repository.ProjectDocumentRepository;

import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Real async dispatch plus bounded restart recovery for project-document indexing. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectDocumentIndexJob {

  private final ProjectDocumentService service;
  private final ProjectDocumentRepository repository;
  private final ProjectDocumentProperties properties;

  @Async
  @EventListener
  public void onQueued(ProjectDocumentProcessingEvent event) {
    service.processDocument(event.documentId());
  }

  @Scheduled(fixedDelayString = "${project.documents.recovery-interval-millis:60000}")
  public void recoverStaleJobs() {
    OffsetDateTime cutoff =
        OffsetDateTime.now().minusNanos(properties.getStaleProcessingMillis() * 1_000_000);
    List<ProjectDocument> recoverable =
        repository.findRecoverable(
            List.of(
                ProjectDocument.Status.UPLOADED,
                ProjectDocument.Status.EXTRACTING,
                ProjectDocument.Status.EMBEDDING,
                ProjectDocument.Status.INDEXING,
                ProjectDocument.Status.RETRY_PENDING),
            cutoff,
            PageRequest.of(0, 20));
    for (ProjectDocument document : recoverable) {
      try {
        service.processDocument(document.getId());
      } catch (RuntimeException e) {
        log.warn("Project document recovery attempt failed for document id {}", document.getId());
      }
    }
  }
}
