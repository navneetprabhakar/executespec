package com.navneet.executespec.codeintel;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * REST surface for {@link ProjectDocGeneratorService}. Two doc types
 * (EXPLANATION, SETUP), three operations per type (generate, get, download).
 */
@RestController
@RequiredArgsConstructor
public class ProjectDocGeneratorController {

    private final ProjectDocGeneratorService service;
    private final com.navneet.executespec.project.security.ProjectAccessGuard projectAccessGuard;

    public record GeneratedDocDto(
            UUID id,
            String docType,
            Integer version,
            String content,
            String generatedBy,
            Instant generatedAt,
            String modelUsed,
            Integer inputTokens,
            Integer outputTokens) {
        static GeneratedDocDto from(ProjectGeneratedDoc d) {
            return new GeneratedDocDto(
                    d.getId(),
                    d.getDocType() == null ? null : d.getDocType().name(),
                    d.getVersion(),
                    d.getContent(),
                    d.getGeneratedBy(),
                    d.getGeneratedAt(),
                    d.getModelUsed(),
                    d.getInputTokens(),
                    d.getOutputTokens());
        }
    }

    @PostMapping("/api/projects/{projectId}/docs/{docType}/generate")
    public ResponseEntity<GeneratedDocDto> generate(
            @PathVariable String projectId,
            @PathVariable String docType,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        projectAccessGuard.assertCanRead(projectId, userDetails);
        DocType type = parseType(docType);
        String userId = userDetails == null ? null : userDetails.getUser().getId();
        try {
            ProjectGeneratedDoc doc = service.generate(projectId, type, userId, null);
            return ResponseEntity.status(HttpStatus.CREATED).body(GeneratedDocDto.from(doc));
        } catch (IllegalStateException e) {
            // e.g. no architecture doc yet — caller should run /analyse first.
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping("/api/projects/{projectId}/docs/{docType}")
    public ResponseEntity<GeneratedDocDto> latest(
            @PathVariable String projectId,
            @PathVariable String docType,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        projectAccessGuard.assertCanRead(projectId, userDetails);
        DocType type = parseType(docType);
        return service.latest(projectId, type)
                .map(d -> ResponseEntity.ok(GeneratedDocDto.from(d)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/api/projects/{projectId}/docs/{docType}/download")
    public ResponseEntity<String> download(
            @PathVariable String projectId,
            @PathVariable String docType,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        projectAccessGuard.assertCanRead(projectId, userDetails);
        DocType type = parseType(docType);
        ProjectGeneratedDoc doc = service.latest(projectId, type)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        docType + " doc has not been generated for this project yet"));
        String filename = type.name().toLowerCase() + ".md";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/markdown; charset=utf-8"))
                .body(doc.getContent() == null ? "" : doc.getContent());
    }

    private static DocType parseType(String raw) {
        try {
            return DocType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unknown doc type '" + raw + "' (expected EXPLANATION or SETUP)");
        }
    }
}
