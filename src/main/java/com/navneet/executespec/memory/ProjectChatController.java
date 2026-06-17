package com.navneet.executespec.memory;

import java.util.List;
import java.util.Objects;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.memory.entity.ProjectChatMessage;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * Project chat endpoints (v2.4.0). Gated to ENTERPRISE + MAX via the {@link
 * TierConfig#isExecutionViewEnabled()} tier flag, matching the rest of the project-shell premium
 * surface.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/chat")
@RequiredArgsConstructor
public class ProjectChatController {

  private final ProjectChatService chatService;
  private final ProjectRepository projectRepo;
  private final ProjectMemberRepository projectMemberRepo;
  private final OrgRepository orgRepo;
  private final TierConfigRepository tierConfigRepo;
  // 2026-05-02 — drive the chat header's context-window chip from the actual resolved model
  // (provider-level guess was wrong: gpt-4o = 128k vs gpt-5.4 = 256k; Sonnet 200k vs Opus-1M).
  // 2026-05-06 — switched from ModelContextWindowMap to ModelRegistry as the single source of
  // truth for context-window metadata. The map duplicated YAML and drifted (gpt-5.4 was 256K
  // in the map, 200K in YAML). YAML wins; ModelRegistry's prefix-fallback handles forward-compat
  // models the YAML doesn't yet know about.
  private final com.navneet.executespec.router.ModelResolver modelResolver;
  private final com.navneet.executespec.router.ModelRegistry modelRegistry;

  @GetMapping
  public ResponseEntity<List<ProjectChatMessage>> history(
      @AuthenticationPrincipal AppUserDetails principal, @PathVariable String projectId) {
    requireChatAccess(principal, projectId);
    return ResponseEntity.ok(chatService.history(projectId));
  }

  /**
   * 2026-05-02 — surface the resolved model + its context window so the chat header chip can render
   * an accurate "Nk window" segment instead of a per-provider guess. Mirrors the resolution path
   * used in {@link ProjectChatService#send} so the chip never drifts from the model that's actually
   * answering the next turn.
   */
  public record ChatInfo(String model, String provider, int contextWindow) {}

  @GetMapping("/info")
  public ResponseEntity<ChatInfo> info(
      @AuthenticationPrincipal AppUserDetails principal, @PathVariable String projectId) {
    AppUser user = requireChatAccess(principal, projectId);
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project"));
    com.navneet.executespec.router.LlmProvider provider =
        project.getAiProvider() != null
            ? project.getAiProvider()
            : com.navneet.executespec.router.LlmProvider.ANTHROPIC;
    com.navneet.executespec.router.ResolvedModel resolved =
        modelResolver.resolve(
            "ProjectChatAgent", provider, com.navneet.executespec.router.AgentTier.PLANNING);
    String model = resolved.getModel();
    return ResponseEntity.ok(
        new ChatInfo(
            model,
            resolved.getActualProvider() == null
                ? provider.name()
                : resolved.getActualProvider().name(),
            modelRegistry.contextLimitForOrPrefixFallback(model)));
  }

  @PostMapping
  public ResponseEntity<ProjectChatMessage> send(
      @AuthenticationPrincipal AppUserDetails principal,
      @PathVariable String projectId,
      @RequestBody ChatRequest body) {
    AppUser user = requireChatAccess(principal, projectId);
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project"));
    if (body == null || body.message == null || body.message.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message required");
    }
    // V169 (2026-05-02) — caller's PAT threads into project-action tools (regenerate_architecture
    // hits private repos with this PAT). May be null for users who haven't connected a PAT yet;
    // tools that need it return a clear error in that case.
    ProjectChatMessage reply =
        chatService.send(
            projectId, project.getOrgId(), user.getId(), body.message, user.getGithubPat());
    return ResponseEntity.ok(reply);
  }

  /**
   * Distil the current chat history into a single-paragraph execution spec ready to submit as a new
   * project run. Does NOT submit — returns the spec so the user can review / edit before launching.
   */
  @PostMapping("/compile")
  public ResponseEntity<java.util.Map<String, String>> compile(
      @AuthenticationPrincipal AppUserDetails principal, @PathVariable String projectId) {
    requireChatAccess(principal, projectId);
    String spec = chatService.compileToSpec(projectId);
    return ResponseEntity.ok(java.util.Map.of("spec", spec));
  }

  /**
   * v2.6.1 — clear all persisted chat messages for the project. Use when a long history starts
   * dragging answers off-context. Project memory + architecture are preserved.
   */
  @DeleteMapping
  public ResponseEntity<java.util.Map<String, Integer>> clear(
      @AuthenticationPrincipal AppUserDetails principal, @PathVariable String projectId) {
    requireChatAccess(principal, projectId);
    int deleted = chatService.clearHistory(projectId);
    return ResponseEntity.ok(java.util.Map.of("deleted", deleted));
  }

  /**
   * v2.6.1 — compact older messages into a single summary while keeping the recent tail verbatim.
   * Use when the chat is too long to keep coherent answers. 409 if the conversation is too short to
   * need compaction.
   */
  @PostMapping("/compact")
  public ResponseEntity<ProjectChatService.CompactResult> compact(
      @AuthenticationPrincipal AppUserDetails principal, @PathVariable String projectId) {
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
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project"));
    // RL-2026-05-056 — honour V175 cross-org ProjectMember access (consistent with
    // ProjectAccessGuard.assertCanRead). A cross-org invitee reaches the project via a
    // ProjectMember row, not org match; the old org-only check 403'd them out of project chat.
    boolean orgMatch = Objects.equals(project.getOrgId(), user.getOrgId());
    boolean isMember = projectMemberRepo.existsByProjectIdAndUserId(projectId, user.getId());
    if (!orgMatch && !isMember) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "project not in caller org");
    }
    if ("SUPER_ADMIN".equals(user.getGlobalRole())) {
      return user;
    }
    String orgId = project.getOrgId() != null ? project.getOrgId() : user.getOrgId();
    String tier =
        orgId != null
            ? orgRepo.findById(orgId).map(Org::getTier).orElse(user.getTier())
            : user.getTier();
    if (tier == null) tier = "FREE";
    boolean allowed =
        tierConfigRepo.findById(tier).map(TierConfig::isExecutionViewEnabled).orElse(false);
    if (!allowed) {
      // R4 (2026-05-01 follow-up Task 7) — V165 retired MAX; gating
      // copy now names the post-V165 ladder.
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Project chat requires Team or Enterprise tier");
    }
    return user;
  }

  public record ChatRequest(String message) {}
}
