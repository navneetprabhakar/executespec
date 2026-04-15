package com.navneet.executespec.codeintel;

import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ProjectArchitectureController {

    private final ProjectArchitectureService service;
    private final StalenessService stalenessService;
    private final com.navneet.executespec.project.workspace.ProjectWorkspaceService workspace;
    private final ArchitectureDiffService diffService;

    public record ArchDocDto(
            UUID id,
            Integer version,
            String content,
            String userAnnotations,
            Instant generatedAt) {
        static ArchDocDto from(ProjectArchitectureDoc d) {
            return new ArchDocDto(d.getId(), d.getVersion(), d.getContent(),
                    d.getUserAnnotations(), d.getGeneratedAt());
        }
    }

    public record CodeMapDto(String content, Integer version, Instant generatedAt) {
        static CodeMapDto from(ProjectCodeMap c) {
            return new CodeMapDto(c.getContent(), c.getVersion(), c.getGeneratedAt());
        }
    }

    @PostMapping("/api/projects/{projectId}/architecture/generate")
    public ResponseEntity<ArchDocDto> generate(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                com.navneet.executespec.auth.security.AppUserDetails userDetails,
            @PathVariable String projectId) {
        String pat = userDetails != null ? userDetails.getUser().getGithubPat() : null;
        ProjectArchitectureDoc doc = service.generate(projectId, null, pat);
        stalenessService.invalidate(projectId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ArchDocDto.from(doc));
    }

    /** Alias + preferred entry: regenerates codemaps + architecture + summary on disk. */
    @PostMapping("/api/projects/{projectId}/analyse")
    public ResponseEntity<ArchDocDto> analyse(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                com.navneet.executespec.auth.security.AppUserDetails userDetails,
            @PathVariable String projectId,
            @org.springframework.web.bind.annotation.RequestParam(value = "branch", required = false) String branch) {
        String pat = userDetails != null ? userDetails.getUser().getGithubPat() : null;
        ProjectArchitectureDoc doc = service.generate(projectId, branch, pat);
        stalenessService.invalidate(projectId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ArchDocDto.from(doc));
    }

    @GetMapping("/api/projects/{projectId}/summary")
    public ResponseEntity<Map<String, String>> summary(@PathVariable String projectId) {
        String content = workspace.readFile(projectId, "summary.md").orElse("");
        return ResponseEntity.ok(Map.of("content", content));
    }

    @GetMapping("/api/projects/{projectId}/architecture/staleness")
    public ResponseEntity<StalenessService.StalenessReport> staleness(@PathVariable String projectId) {
        return ResponseEntity.ok(stalenessService.report(projectId));
    }

    @GetMapping("/api/projects/{projectId}/architecture")
    public ResponseEntity<ArchDocDto> latest(@PathVariable String projectId) {
        return service.latest(projectId)
                .map(d -> ResponseEntity.ok(ArchDocDto.from(d)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/api/projects/{projectId}/architecture/versions")
    public ResponseEntity<List<ArchDocDto>> versions(@PathVariable String projectId) {
        List<ArchDocDto> out = service.versions(projectId).stream().map(ArchDocDto::from).toList();
        return ResponseEntity.ok(out);
    }

    /**
     * v2.5.5 (UX-4.2) — source file changes between two architecture versions.
     * Uses GitHub's compare API against each repo's SHAs captured at generate-time.
     * The text diff of {@code architecture.md} remains client-side (LCS).
     */
    @GetMapping("/api/projects/{projectId}/architecture/diff")
    public ResponseEntity<ArchitectureDiffService.DiffReport> diff(
            @PathVariable String projectId,
            @RequestParam("from") int from,
            @RequestParam("to") int to,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.navneet.executespec.auth.security.AppUserDetails userDetails) {
        String userId = userDetails == null ? null : userDetails.getUser().getId();
        return ResponseEntity.ok(diffService.diff(projectId, from, to, userId));
    }

    @PutMapping("/api/projects/{projectId}/architecture/annotate")
    public ResponseEntity<ArchDocDto> annotate(
            @PathVariable String projectId,
            @RequestBody Map<String, String> body) {
        String annotation = body.getOrDefault("annotation", "");
        return ResponseEntity.ok(ArchDocDto.from(service.annotate(projectId, annotation)));
    }

    @GetMapping("/api/projects/{projectId}/repos/{repoId}/codemap")
    public ResponseEntity<CodeMapDto> codemap(
            @PathVariable String projectId,
            @PathVariable UUID repoId) {
        return service.latestCodeMap(projectId, repoId)
                .map(c -> ResponseEntity.ok(CodeMapDto.from(c)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No codemap for repo — regenerate architecture first"));
    }
}
