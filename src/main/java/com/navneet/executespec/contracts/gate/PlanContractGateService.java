package com.navneet.executespec.contracts.gate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.contracts.ContractConsumerRepository;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor.RouteKey;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.service.ProjectRepoService;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * RR-819 — the plan-approval contract gate.
 *
 * <p>The authoritative contract in {@link PlanBreakingChangeReport} says the UI must demand a typed
 * {@code BREAKING} and disable plan approval when a touched contract has declared consumers. Before
 * this class, that requirement existed only as text: the orchestrator ran the classifier AFTER the
 * worker had written files, discarded {@code requiresTypedAck}, and turned every row into a MINOR
 * issue whose only enforcement was the description suffix {@code [REQUIRES ACK]}. A user could
 * approve and automatically publish a consumer-breaking change with no friction and no record.
 *
 * <p>This gate fires at the plan gate, before any worker spend or publication. What it can honestly
 * assert at that point is narrower than the post-run classifier: no new content exists yet, so it
 * cannot classify a change as BREAKING. It asserts what IS knowable — <em>this plan intends to
 * modify these registered contracts, and these other repositories declare themselves consumers of
 * them</em> — and requires an explicit typed acknowledgement bound to that exact evidence.
 *
 * <p>The post-run classifier remains as the second, tighten-only gate. Neither replaces the other:
 * this one has intent but no content; that one has content but arrives too late to refuse.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanContractGateService {

  /** The exact text an approver must type. Compared case-insensitively after trimming. */
  public static final String REQUIRED_ACK_TEXT = "BREAKING";

  private static final Pattern EXACT_HTTP_REMOVAL =
      Pattern.compile(
          "(?i)\\b(?:remove|delete|retire)\\s+"
              + "(?:(?:the|an)\\s+)?(?:(?:existing|legacy)\\s+)?"
              + "(?:(?:http|spring)\\s+)?(?:endpoint|route)?\\s*[:=-]?\\s*"
              + "(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE|ANY)\\s+"
              + "(/[A-Za-z0-9_~!$&'()*+=:@%{}./-]+)");

  private final ProjectRepoService projectRepoService;
  private final ContractRegistryRepository contractRegistryRepository;
  private final ContractConsumerRepository contractConsumerRepository;
  private final ProjectRepoRepository projectRepoRepository;
  private final PlanContractGateReportRepository gateReportRepository;
  private final ObjectMapper objectMapper;
  private final BrownfieldHttpContractGuard brownfieldHttpContractGuard;

  /**
   * @param contractId registered contract the plan intends to modify
   * @param contractName human-readable name
   * @param type contract format
   * @param detectedPath repository-relative path the plan touches
   * @param declaredConsumers full names of repositories that declared themselves consumers
   * @param matchedPlanTasks plan task ids whose {@code touchesFiles} named this contract
   */
  public record GateRow(
      UUID contractId,
      String contractName,
      String type,
      String detectedPath,
      List<String> declaredConsumers,
      List<String> matchedPlanTasks) {}

  /**
   * @param rows one entry per registered contract this plan intends to modify
   * @param brownfieldHttpRouteRemovals exact admitted baseline routes the plan explicitly removes
   * @param requiresTypedAck true when a row has a declared consumer or an exact baseline route is
   *     removed
   * @param digest SHA-256 of the serialized report; the acknowledgement is bound to it
   */
  public record HttpRouteRemoval(String method, String path) {}

  private record DurableReport(
      int schemaVersion, List<GateRow> rows, List<HttpRouteRemoval> brownfieldHttpRouteRemovals) {}

  public record GateReport(
      List<GateRow> rows,
      List<HttpRouteRemoval> brownfieldHttpRouteRemovals,
      boolean requiresTypedAck,
      String digest) {}

  /** Outcome of enforcing the gate at plan approval. */
  public enum AckOutcome {
    /** No gate, or the gate does not require friction. */
    NOT_REQUIRED,
    /** Acknowledgement recorded (or already recorded by this same approval). */
    ACKNOWLEDGED,
    /** Acknowledgement missing or the typed text is wrong. */
    MISSING,
    /** The digest supplied does not match the report currently persisted for this run. */
    STALE_REPORT
  }

  /**
   * Compose and persist the gate report for a plan about to enter the approval gate. Best-effort
   * and non-fatal: composition failure must not strand a run at the gate, but note that a run with
   * NO persisted report is treated as {@link AckOutcome#NOT_REQUIRED} at approval — the gate is a
   * tighten-only control layered on the existing plan gate, not a second liveness dependency.
   */
  public Optional<GateReport> composeAndPersist(
      String runId, String projectId, Plan plan, List<UUID> selectedRepoIds) {
    if (runId == null || runId.isBlank() || plan == null) return Optional.empty();
    List<ProjectRepo> repos = List.of();
    if (projectId != null
        && !projectId.isBlank()
        && selectedRepoIds != null
        && !selectedRepoIds.isEmpty()) {
      var resolved = projectRepoService.resolveForRun(projectId, selectedRepoIds);
      repos = resolved == null ? List.of() : resolved;
    }

    Set<String> touched = touchedPaths(plan);
    List<GateRow> rows = new ArrayList<>();
    boolean requiresAck = false;
    if (!touched.isEmpty()) {
      for (ProjectRepo repo : repos) {
        for (ContractRegistry contract : contractRegistryRepository.findByRepoId(repo.getId())) {
          String path = contract.getDetectedPath();
          if (path == null || path.isBlank()) continue;
          List<String> matchedTasks = matchingTasks(plan, path);
          if (matchedTasks.isEmpty()) continue;
          List<String> consumers = declaredConsumers(contract.getId(), projectId);
          if (!consumers.isEmpty()) requiresAck = true;
          rows.add(
              new GateRow(
                  contract.getId(),
                  contract.getName(),
                  String.valueOf(contract.typeEnum()),
                  normalize(path),
                  consumers,
                  matchedTasks));
        }
      }
    }
    List<HttpRouteRemoval> exactHttpRemovals = exactHttpRemovals(runId, plan);
    if (rows.isEmpty() && exactHttpRemovals.isEmpty()) return Optional.empty();
    if (!exactHttpRemovals.isEmpty()) requiresAck = true;

    String json;
    try {
      json = objectMapper.writeValueAsString(new DurableReport(1, rows, exactHttpRemovals));
    } catch (Exception e) {
      log.warn(
          "run={} contract gate report not persisted (serialization): {}", runId, e.toString());
      return Optional.empty();
    }
    String digest = sha256(json);
    gateReportRepository.save(
        PlanContractGateReport.builder()
            .runId(runId)
            .projectId(projectId)
            .reportJson(json)
            .reportDigest(digest)
            .requiresTypedAck(requiresAck)
            .contractCount(rows.size() + exactHttpRemovals.size())
            .createdAt(Instant.now())
            .build());
    log.info(
        "run={} plan contract gate composed: contracts={} requiresTypedAck={}",
        runId,
        rows.size() + exactHttpRemovals.size(),
        requiresAck);
    return Optional.of(new GateReport(rows, exactHttpRemovals, requiresAck, digest));
  }

  /** Read the persisted report for a run so the approval UI can render exactly what was gated. */
  public Optional<PlanContractGateReport> find(String runId) {
    return runId == null ? Optional.empty() : gateReportRepository.findById(runId);
  }

  /**
   * Enforce the gate at plan approval. The acknowledgement is server-verified as semantic {@code
   * BREAKING} after trimming/case-folding, bound to the exact report digest, and recorded with the
   * acting user; persisted acknowledgement bytes are not rewritten. A replayed acknowledgement
   * against an already-acknowledged gate is accepted (idempotent approval retry); one against a
   * different digest is refused.
   */
  public AckOutcome acknowledge(String runId, String ackText, String digest, String userId) {
    var existing = find(runId).orElse(null);
    if (existing == null || !existing.isRequiresTypedAck()) return AckOutcome.NOT_REQUIRED;
    if (ackText == null || !REQUIRED_ACK_TEXT.equalsIgnoreCase(ackText.trim())) {
      return AckOutcome.MISSING;
    }
    if (digest == null || !existing.getReportDigest().equalsIgnoreCase(digest.trim())) {
      return AckOutcome.STALE_REPORT;
    }
    if (existing.getAcknowledgedAt() != null) return AckOutcome.ACKNOWLEDGED;
    int claimed =
        gateReportRepository.claimAcknowledgement(
            runId, existing.getReportDigest(), userId, ackText.trim(), Instant.now());
    if (claimed == 0) {
      // Lost the race to a concurrent approval, or the digest moved between read and write.
      var reread = find(runId).orElse(null);
      return reread != null && reread.getAcknowledgedAt() != null
          ? AckOutcome.ACKNOWLEDGED
          : AckOutcome.STALE_REPORT;
    }
    log.info("run={} breaking-change acknowledgement recorded by user={}", runId, userId);
    return AckOutcome.ACKNOWLEDGED;
  }

  private List<String> declaredConsumers(UUID contractId, String projectId) {
    return contractConsumerRepository
        .findByContractIdAndDeclaredInProjectId(contractId, projectId)
        .stream()
        .map(
            cc ->
                projectRepoRepository
                    .findById(cc.getConsumerRepoId())
                    .map(ProjectRepo::getGithubRepoFullName)
                    .orElse(cc.getConsumerRepoId().toString()))
        .toList();
  }

  private List<HttpRouteRemoval> exactHttpRemovals(String runId, Plan plan) {
    if (!brownfieldHttpContractGuard.hasBaseline(runId)) return List.of();
    final Set<RouteKey> protectedRoutes;
    try {
      protectedRoutes = brownfieldHttpContractGuard.protectedRoutes(runId);
    } catch (RuntimeException unreadableBaseline) {
      log.warn(
          "run={} exact HTTP removal authority omitted (baseline unreadable): {}",
          runId,
          unreadableBaseline.getClass().getSimpleName());
      return List.of();
    }
    if (protectedRoutes.isEmpty()) return List.of();

    Set<RouteKey> exact = new LinkedHashSet<>();
    for (String evidence : admittedPlanText(plan)) {
      Matcher matcher = EXACT_HTTP_REMOVAL.matcher(evidence);
      while (matcher.find()) {
        if (isNegatedRemoval(evidence, matcher.start())) continue;
        String method = matcher.group(1).toUpperCase(Locale.ROOT);
        String path = matcher.group(2);
        RouteKey candidate = new RouteKey(method, path);
        if (!protectedRoutes.contains(candidate) && path.endsWith(".")) {
          candidate = new RouteKey(method, path.substring(0, path.length() - 1));
        }
        if (protectedRoutes.contains(candidate)) exact.add(candidate);
      }
    }
    return exact.stream()
        .sorted()
        .map(route -> new HttpRouteRemoval(route.method(), route.path()))
        .toList();
  }

  private static boolean isNegatedRemoval(String evidence, int removalStart) {
    int prefixStart = Math.max(0, removalStart - 40);
    String prefix = evidence.substring(prefixStart, removalStart).toLowerCase(Locale.ROOT);
    return prefix.matches("(?s).*\\b(?:do|must|shall|should|can)\\s+not\\s+$")
        || prefix.matches("(?s).*\\bnot\\s+$")
        || prefix.matches("(?s).*\\bnever\\s+$")
        || prefix.matches("(?s).*\\bwithout\\s+$");
  }

  private static List<String> admittedPlanText(Plan plan) {
    List<String> evidence = new ArrayList<>();
    plan.requirements().stream()
        .map(requirement -> requirement.text())
        .filter(text -> text != null && !text.isBlank())
        .forEach(evidence::add);
    for (var task : plan.tasks()) {
      if (task.title() != null) evidence.add(task.title());
      if (task.description() != null) evidence.add(task.description());
      task.hardConstraints().stream()
          .filter(text -> text != null && !text.isBlank())
          .forEach(evidence::add);
    }
    return List.copyOf(evidence);
  }

  private static Set<String> touchedPaths(Plan plan) {
    Set<String> out = new LinkedHashSet<>();
    for (var task : plan.tasks()) {
      for (String f : task.touchesFiles()) {
        String n = normalize(f);
        if (!n.isEmpty()) out.add(n);
      }
    }
    return out;
  }

  private static List<String> matchingTasks(Plan plan, String detectedPath) {
    String target = normalize(detectedPath);
    if (target.isEmpty()) return List.of();
    List<String> out = new ArrayList<>();
    for (var task : plan.tasks()) {
      boolean hit =
          task.touchesFiles().stream()
              .map(PlanContractGateService::normalize)
              .anyMatch(p -> p.equals(target) || p.endsWith("/" + target));
      if (hit) out.add(task.id());
    }
    return out;
  }

  /**
   * Repository-relative comparison form. Multi-repo plans name files as {@code
   * repos/<name>/<path>}, so the suffix match in {@link #matchingTasks} is what actually lines the
   * two up; this only removes the noise that would defeat it.
   */
  private static String normalize(String path) {
    if (path == null) return "";
    String p = path.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    while (p.startsWith("./")) p = p.substring(2);
    while (p.startsWith("/")) p = p.substring(1);
    return p;
  }

  private static String sha256(String value) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
