package com.navneet.executespec.memory;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.memory.entity.ProjectChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

/**
 * Project chat endpoints (v2.4.0). Gated to ENTERPRISE + MAX via the
 * {@link TierConfig#isExecutionViewEnabled()} tier flag, matching the rest of the
 * project-shell premium surface.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/chat")
@RequiredArgsConstructor
public class ProjectChatController {

    private final ProjectChatService chatService;
    private final ProjectRepository projectRepo;
    private final OrgRepository orgRepo;
    private final TierConfigRepository tierConfigRepo;

    @GetMapping
    public ResponseEntity<List<ProjectChatMessage>> history(
            @AuthenticationPrincipal AppUserDetails principal,
            @PathVariable String projectId) {
        requireChatAccess(principal, projectId);
        return ResponseEntity.ok(chatService.history(projectId));
    }

    @PostMapping
    public ResponseEntity<ProjectChatMessage> send(
            @AuthenticationPrincipal AppUserDetails principal,
            @PathVariable String projectId,
            @RequestBody ChatRequest body) {
        AppUser user = requireChatAccess(principal, projectId);
        if (body == null || body.message == null || body.message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message required");
        }
        ProjectChatMessage reply = chatService.send(projectId, user.getOrgId(), user.getId(), body.message);
        return ResponseEntity.ok(reply);
    }

    /**
     * Distil the current chat history into a single-paragraph execution spec
     * ready to submit as a new project run. Does NOT submit — returns the spec
     * so the user can review / edit before launching.
     */
    @PostMapping("/compile")
    public ResponseEntity<java.util.Map<String, String>> compile(
            @AuthenticationPrincipal AppUserDetails principal,
            @PathVariable String projectId) {
        requireChatAccess(principal, projectId);
        String spec = chatService.compileToSpec(projectId);
        return ResponseEntity.ok(java.util.Map.of("spec", spec));
    }

    /**
     * v2.6.1 — clear all persisted chat messages for the project. Use when
     * a long history starts dragging answers off-context. Project memory +
     * architecture are preserved.
     */
    @DeleteMapping
    public ResponseEntity<java.util.Map<String, Integer>> clear(
            @AuthenticationPrincipal AppUserDetails principal,
            @PathVariable String projectId) {
        requireChatAccess(principal, projectId);
        int deleted = chatService.clearHistory(projectId);
        return ResponseEntity.ok(java.util.Map.of("deleted", deleted));
    }

    /**
     * v2.6.1 — compact older messages into a single summary while keeping the
     * recent tail verbatim. Use when the chat is too long to keep coherent
     * answers. 409 if the conversation is too short to need compaction.
     */
    @PostMapping("/compact")
    public ResponseEntity<ProjectChatService.CompactResult> compact(
            @AuthenticationPrincipal AppUserDetails principal,
            @PathVariable String projectId) {
        requireChatAccess(principal, projectId);
        try {
            return ResponseEntity.ok(chatService.compactHistory(projectId));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private AppUser requireChatAccess(AppUserDetails principal, String projectId) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        AppUser user = principal.getUser();
        Project project = projectRepo.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project"));
        if (!Objects.equals(project.getOrgId(), user.getOrgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "project not in caller org");
        }
        String orgId = user.getOrgId();
        String tier = orgId != null
                ? orgRepo.findById(orgId).map(Org::getTier).orElse(user.getTier())
                : user.getTier();
        if (tier == null) tier = "FREE";
        boolean allowed = tierConfigRepo.findById(tier)
                .map(TierConfig::isExecutionViewEnabled)
                .orElse(false);
        if (!allowed) {
            // R4 (2026-05-01 follow-up Task 7) — V165 retired MAX; gating
            // copy now names the post-V165 ladder.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Project chat requires Team or Enterprise tier");
        }
        return user;
    }

    public record ChatRequest(String message) {}
}
