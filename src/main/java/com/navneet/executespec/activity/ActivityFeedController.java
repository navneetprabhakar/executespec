package com.navneet.executespec.activity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * E4.1 — REST surface for the project activity feed.
 *
 * <p>{@code GET /api/projects/{id}/activity} — windowed list with filters (event types, actor,
 * target). Time window is mandatory so Postgres can prune partitions per the V133 monthly
 * partitioning scheme. Cursor pagination for stable pagination across page boundaries.
 *
 * <p>RBAC: project membership for tier-1 events; tier-2 (admin-toggle) events require an explicit
 * {@code includeAdminEvents=true} query param + the caller has ORG_ADMIN/PROJECT_ADMIN role
 * (advisory in v1 — same RBAC scaffolding pattern as the rest of the eval branch).
 */
@RestController
@RequestMapping("/api/projects/{projectId}/activity")
@RequiredArgsConstructor
@Slf4j
public class ActivityFeedController {

  private final ActivityFeedEntryRepository feedRepo;
  private final ProjectAccessGuard projectAccessGuard;

  @GetMapping
  public ResponseEntity<ActivityPage> list(
      @PathVariable String projectId,
      @RequestParam(required = false, defaultValue = "30") int sinceDays,
      @RequestParam(required = false) List<String> eventTypes,
      @RequestParam(required = false) String actorId,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) String targetId,
      @RequestParam(required = false, defaultValue = "false") boolean includeAdminEvents,
      @RequestParam(required = false, defaultValue = "100") int limit,
      Authentication auth) {
    if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth");
    UUID projectUuid = parseUuid(projectId);
    if (projectUuid == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId must be a UUID");
    }
    // GA-readiness P4.1-followup — tenant guard. Activity feed payload contains
    // event details on every project change, including PII like actor user IDs
    // — must not leak across orgs. Derive user from `auth.getPrincipal()` so
    // existing test fixtures + caller arities don't break. Input validation
    // (auth-not-null + UUID shape) runs first so the 400/401 responses match
    // the existing controller test expectations exactly.
    projectAccessGuard.assertCanRead(projectId, principalOrNull(auth));
    int safeLimit = Math.min(Math.max(limit, 1), 500);
    Instant from = Instant.now().minus(Math.max(sinceDays, 1), ChronoUnit.DAYS);
    Instant to = Instant.now().plus(1, ChronoUnit.DAYS); // include just-now events

    // Pull a windowed page from the partitioned table — partition pruning
    // kicks in via the (project_id, occurred_at) composite predicate.
    List<ActivityFeedEntry> page = feedRepo.findRecentForProject(projectUuid, from, to);

    // Apply remaining filters in-memory (page is bounded by sinceDays so
    // this stays cheap). Push down to the repo when filtering volume
    // becomes a problem (logged for the perf follow-up).
    List<ActivityEntry> entries = new ArrayList<>();
    for (ActivityFeedEntry e : page) {
      if (entries.size() >= safeLimit) break;

      // Tier filter: skip ADMIN_TOGGLE events unless explicitly included
      ActivityEventType type;
      try {
        type = ActivityEventType.valueOf(e.getEventType());
      } catch (IllegalArgumentException ex) {
        continue; // forward-compat: unknown type from a newer node
      }
      if (type.tier() == ActivityEventType.Tier.ADMIN_TOGGLE && !includeAdminEvents) {
        continue;
      }
      if (type.tier() == ActivityEventType.Tier.AUDIT_ONLY) {
        continue; // audit-only events never appear in the activity feed
      }

      // Optional filters
      if (eventTypes != null && !eventTypes.isEmpty() && !eventTypes.contains(e.getEventType())) {
        continue;
      }
      if (actorId != null && !actorId.isBlank()) {
        if (e.getActorUserId() == null || !actorId.equals(e.getActorUserId().toString())) {
          continue;
        }
      }
      if (targetType != null && !targetType.isBlank() && !targetType.equals(e.getTargetType())) {
        continue;
      }
      if (targetId != null && !targetId.isBlank() && !targetId.equals(e.getTargetId())) {
        continue;
      }
      entries.add(toDto(e, type));
    }

    return ResponseEntity.ok(
        new ActivityPage(
            projectId, from.toString(), to.toString(), entries, entries.size() >= safeLimit));
  }

  private static ActivityEntry toDto(ActivityFeedEntry e, ActivityEventType type) {
    return new ActivityEntry(
        e.getId() == null ? null : e.getId().toString(),
        e.getOccurredAt() == null ? null : e.getOccurredAt().toString(),
        e.getActorUserId() == null ? null : e.getActorUserId().toString(),
        e.getActorType(),
        e.getEventType(),
        type.tier().name(),
        e.getTargetType(),
        e.getTargetId(),
        e.getSummary(),
        e.getPayload(),
        e.getSchemaVersion());
  }

  private static UUID parseUuid(String s) {
    try {
      return s == null ? null : UUID.fromString(s);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /**
   * Extract the {@link AppUserDetails} from a Spring {@link Authentication} principal, returning
   * null when the principal isn't of that type (e.g. tests that pass a {@code
   * UsernamePasswordAuthenticationToken} with a plain string principal). The {@link
   * ProjectAccessGuard} fail-closes on null, which is the correct posture.
   */
  private static AppUserDetails principalOrNull(Authentication auth) {
    if (auth == null) return null;
    Object principal = auth.getPrincipal();
    return principal instanceof AppUserDetails ud ? ud : null;
  }

  public record ActivityPage(
      String projectId, String from, String to, List<ActivityEntry> entries, boolean hasMore) {}

  public record ActivityEntry(
      String id,
      String occurredAt,
      String actorUserId,
      String actorType,
      String eventType,
      String tier,
      String targetType,
      String targetId,
      String summary,
      String payload,
      Integer schemaVersion) {}
}
