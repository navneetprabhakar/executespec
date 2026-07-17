package com.navneet.executespec.activity;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * partitioning scheme. Cursor pagination is stable across page boundaries via the descending {@code
 * (occurredAt,id)} tuple.
 *
 * <p>RBAC: project membership for tier-1 events; tier-2 (admin-toggle) events require an explicit
 * {@code includeAdminEvents=true} query param plus an enforced ORG_ADMIN/PROJECT_ADMIN capability.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/activity")
@RequiredArgsConstructor
@Slf4j
public class ActivityFeedController {

  static final int MAX_RETENTION_DAYS = 366;
  static final int MAX_PAGE_SIZE = 500;

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
      @RequestParam(required = false) String cursor,
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
    AppUserDetails principal = principalOrNull(auth);
    if (includeAdminEvents) {
      projectAccessGuard.assertCanAdmin(projectId, principal);
    } else {
      projectAccessGuard.assertCanRead(projectId, principal);
    }

    int safeLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    int safeSinceDays = Math.min(Math.max(sinceDays, 1), MAX_RETENTION_DAYS);
    Instant to = Instant.now().plusSeconds(1); // include an event committed during request setup
    Instant from = to.minus(safeSinceDays, ChronoUnit.DAYS);
    UUID actorUuid = parseOptionalUuid(actorId, "actorId");
    ActivityCursor parsedCursor = parseCursor(cursor);

    Set<String> requestedTypes =
        eventTypes == null
            ? Set.of()
            : eventTypes.stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    List<String> permittedTypes =
        Arrays.stream(ActivityEventType.values())
            .filter(type -> type.tier() != ActivityEventType.Tier.AUDIT_ONLY)
            .filter(
                type -> includeAdminEvents || type.tier() != ActivityEventType.Tier.ADMIN_TOGGLE)
            .map(Enum::name)
            .filter(name -> requestedTypes.isEmpty() || requestedTypes.contains(name))
            .toList();

    if (permittedTypes.isEmpty()) {
      return ResponseEntity.ok(
          new ActivityPage(projectId, from.toString(), to.toString(), List.of(), false, null));
    }

    List<ActivityFeedEntry> page =
        feedRepo.findFilteredPage(
            projectUuid,
            from,
            to,
            permittedTypes,
            actorUuid,
            blankToNull(targetType),
            blankToNull(targetId),
            parsedCursor == null ? null : parsedCursor.occurredAt(),
            parsedCursor == null ? null : parsedCursor.id(),
            org.springframework.data.domain.PageRequest.of(0, safeLimit + 1));

    // Defence in depth: the repository owns filtering for performance, while this final allow-list
    // prevents a future query regression from exposing an audit/admin row.
    Set<String> permittedTypeSet = Set.copyOf(permittedTypes);
    List<ActivityFeedEntry> allowedPage =
        page.stream()
            .filter(entry -> permittedTypeSet.contains(entry.getEventType()))
            .filter(entry -> actorUuid == null || actorUuid.equals(entry.getActorUserId()))
            .filter(
                entry ->
                    blankToNull(targetType) == null
                        || blankToNull(targetType).equals(entry.getTargetType()))
            .filter(
                entry ->
                    blankToNull(targetId) == null
                        || blankToNull(targetId).equals(entry.getTargetId()))
            .toList();
    boolean hasMore = allowedPage.size() > safeLimit;
    List<ActivityFeedEntry> visible = hasMore ? allowedPage.subList(0, safeLimit) : allowedPage;
    List<ActivityEntry> entries =
        visible.stream()
            .map(entry -> toDto(entry, ActivityEventType.valueOf(entry.getEventType())))
            .toList();
    String nextCursor =
        hasMore && !visible.isEmpty() ? encodeCursor(visible.get(visible.size() - 1)) : null;

    return ResponseEntity.ok(
        new ActivityPage(projectId, from.toString(), to.toString(), entries, hasMore, nextCursor));
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

  private static UUID parseOptionalUuid(String value, String field) {
    if (value == null || value.isBlank()) return null;
    UUID parsed = parseUuid(value);
    if (parsed == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a UUID");
    }
    return parsed;
  }

  private static ActivityCursor parseCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) return null;
    int separator = cursor.lastIndexOf('|');
    if (separator <= 0 || separator == cursor.length() - 1) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid activity cursor");
    }
    try {
      Instant occurredAt = Instant.parse(cursor.substring(0, separator));
      UUID id = UUID.fromString(cursor.substring(separator + 1));
      return new ActivityCursor(occurredAt, id);
    } catch (DateTimeParseException | IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid activity cursor");
    }
  }

  private static String encodeCursor(ActivityFeedEntry entry) {
    return entry.getOccurredAt() + "|" + entry.getId();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
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
      String projectId,
      String from,
      String to,
      List<ActivityEntry> entries,
      boolean hasMore,
      String nextCursor) {}

  private record ActivityCursor(Instant occurredAt, UUID id) {}

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
