package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyContractEnricher.*;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.*;
import static com.navneet.executespec.agent.PlanSafetyStructureSupport.*;
import static com.navneet.executespec.agent.PlanSafetyTextSupport.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class PlanSafetyEvidenceSupport {

  private PlanSafetyEvidenceSupport() {}

  static java.util.Map<ApiOperation, String> findCanonicalApiEndpoints(List<PlannedTask> tasks) {
    java.util.Map<ApiOperation, String> endpoints = new java.util.LinkedHashMap<>();
    if (tasks == null) return endpoints;
    for (PlannedTask task : tasks) {
      if (task == null || isFrontendOnlyTask(task) || isTestOnlyTask(task)) continue;
      String identity = taskIdentityEvidence(task);
      boolean backendApiOwner =
          containsAny(identity, "/api/", "controller", "routes", "endpoint", "router");
      if (!backendApiOwner) continue;
      for (String endpoint : extractApiEndpoints(normalizeTechnicalSpacing(task.description()))) {
        ApiOperation operation = classifyApiOperation(endpoint);
        if (operation != null) endpoints.putIfAbsent(operation, endpoint);
      }
    }
    return java.util.Map.copyOf(endpoints);
  }

  static boolean planUsesApiBasePath(Plan plan) {
    if (plan == null || plan.tasks() == null) return false;
    return plan.tasks().stream()
        .filter(Objects::nonNull)
        .map(PlannedTask::description)
        .filter(Objects::nonNull)
        .anyMatch(
            description ->
                description.contains("defaults to `/api`")
                    || description.contains("proxy that forwards `/api`")
                    || description.contains("proxy for `/api`")
                    || description.matches("(?is).*\\bproxy\\b.{0,80}`/api`.*")
                    || description.matches("(?is).*`/api`.{0,80}\\bproxy\\b.*"));
  }

  static String normalizeApiEndpoints(String value, java.util.Map<ApiOperation, String> canonical) {
    if (value == null || value.isBlank() || canonical == null || canonical.isEmpty()) {
      return safe(value);
    }
    String normalized = value;
    for (String endpoint : extractApiEndpoints(value)) {
      ApiOperation operation = classifyApiOperation(endpoint);
      String expected = operation == null ? null : canonical.get(operation);
      if (expected != null && !endpoint.equals(expected)) {
        normalized = normalized.replace(endpoint, expected);
      }
    }
    normalized =
        normalized.replaceAll(
            "(?i)(/api/reservations/)\\{order_id\\}(/(?:release|cancel))", "$1{reservation_id}$2");
    return normalized;
  }

  static List<String> canonicalEndpointsUsedBy(
      String value, java.util.Map<ApiOperation, String> canonical) {
    LinkedHashSet<String> endpoints = new LinkedHashSet<>();
    for (String endpoint : extractApiEndpoints(value)) {
      ApiOperation operation = classifyApiOperation(endpoint);
      String expected = operation == null ? null : canonical.get(operation);
      if (expected != null) endpoints.add(expected);
      else if (operation != null) endpoints.add(endpoint);
    }
    return List.copyOf(endpoints);
  }

  static List<String> extractApiEndpoints(String value) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])(/api/[A-Za-z0-9_{}?=&./:-]+)")
            .matcher(safe(value));
    List<String> endpoints = new ArrayList<>();
    while (matcher.find()) {
      String endpoint = matcher.group(1).replaceAll("[.,:;]+$", "");
      if (!endpoint.endsWith(".py")
          && !endpoint.endsWith(".ts")
          && !endpoint.endsWith(".tsx")
          && !endpoints.contains(endpoint)) endpoints.add(endpoint);
    }
    return List.copyOf(endpoints);
  }

  static ApiOperation classifyApiOperation(String endpoint) {
    String path = safe(endpoint).toLowerCase(Locale.ROOT);
    if (path.contains("adjust")) return ApiOperation.ADJUST_STOCK;
    if (path.contains("transfer")) return ApiOperation.TRANSFER_STOCK;
    if (path.contains("release") || path.contains("cancel")) {
      return ApiOperation.RELEASE_RESERVATION;
    }
    if (path.contains("reserve") || path.contains("reservation")) {
      return ApiOperation.RESERVE_ORDER;
    }
    if (path.matches("/api/orders/?(?:\\?.*)?")) return ApiOperation.CREATE_ORDER;
    if (path.contains("audit")) return ApiOperation.AUDIT_HISTORY;
    return null;
  }

  static String taskEvidence(PlannedTask task) {
    return (safe(task.title())
            + "\n"
            + safe(task.description())
            + "\n"
            + String.join("\n", task.touchesFiles()))
        .toLowerCase(Locale.ROOT);
  }

  static String taskIdentityEvidence(PlannedTask task) {
    return (safe(task.title()) + "\n" + String.join("\n", task.touchesFiles()))
        .toLowerCase(Locale.ROOT);
  }

  static boolean containsAny(String evidence, String... needles) {
    for (String needle : needles) {
      if (evidence.contains(needle)) return true;
    }
    return false;
  }

  static boolean isDedicatedTestTask(PlannedTask task) {
    if (task == null || task.touchesFiles() == null || task.touchesFiles().isEmpty()) return false;
    String title = safe(task.title()).toLowerCase(Locale.ROOT);
    if (containsAny(
        title, "black-box", "blackbox", "acceptance suite", "playwright", "e2e tests")) {
      return true;
    }
    return task.touchesFiles().stream()
        .filter(Objects::nonNull)
        .allMatch(
            path -> {
              String normalized = path.toLowerCase(Locale.ROOT);
              return normalized.startsWith("tests/")
                  || normalized.contains("/tests/")
                  || normalized.startsWith("test/")
                  || normalized.contains("/test/")
                  || normalized.contains("test_")
                  || normalized.endsWith(".spec.ts")
                  || normalized.endsWith(".test.ts")
                  || normalized.endsWith("conftest.py");
            });
  }

  static boolean positivelyRequiresAuthentication(String requirement) {
    String normalized = requirement.toLowerCase(Locale.ROOT);
    if (!containsAny(
        normalized, "authenticated", "authenticate", "authentication", "api key", "bearer")) {
      return false;
    }
    return !containsAny(
        normalized,
        "no authentication",
        "without authentication",
        "authentication is not required",
        "authentication not required",
        "do not add authentication",
        "don't add authentication",
        "must not add authentication",
        "skip authentication",
        "skip credential",
        "unauthenticated");
  }

  static boolean containsCreateReservationEndpoint(String evidence) {
    return evidence.matches("(?s).*post\\s+/reservations(?:\\s|,|\\.|$).*");
  }

  static boolean isFrontendOnlyTask(PlannedTask task) {
    return task.touchesFiles() != null
        && !task.touchesFiles().isEmpty()
        && task.touchesFiles().stream()
            .map(path -> path.toLowerCase(Locale.ROOT))
            .allMatch(path -> path.equals("package.json") || path.startsWith("frontend/"));
  }

  static boolean isTestOnlyTask(PlannedTask task) {
    String title = safe(task.title()).toLowerCase(Locale.ROOT);
    if (containsAny(
        title, "black-box", "blackbox", "acceptance suite", "playwright", "e2e tests")) {
      return true;
    }
    return task.touchesFiles() != null
        && !task.touchesFiles().isEmpty()
        && task.touchesFiles().stream()
            .filter(Objects::nonNull)
            .map(path -> path.toLowerCase(Locale.ROOT))
            .allMatch(
                path ->
                    path.contains("/test")
                        || path.startsWith("test")
                        || path.startsWith("acceptance/"));
  }

  static boolean consumesOrderStatus(String evidence) {
    return evidence.contains("order.status")
        || (evidence.contains("order") && evidence.contains("status"))
        || evidence.matches("(?s).*order.{0,80}(?:is in|has|with).{0,80}status.*")
        || evidence.matches("(?s).*order.{0,50}status.*");
  }

  static boolean orderSchemaDefinesStatus(String evidence) {
    int orderStart =
        firstNonNegative(
            evidence.indexOf("`order`:"),
            evidence.indexOf("order model"),
            evidence.indexOf("**order**"));
    if (orderStart >= 0) {
      int reservationStart =
          firstPositiveAfter(
              evidence, orderStart, "`reservation`", "reservation.py", "reservation model");
      String orderSection =
          evidence.substring(
              orderStart, reservationStart < 0 ? evidence.length() : reservationStart);
      if (orderSection.contains("status")) return true;
    }

    // Deterministic safety and coherence intentionally carry authoritative cross-task contracts in
    // hardConstraints so a bounded task description does not need to duplicate every enum. Those
    // constraints are appended after the description and can therefore sit outside the textual
    // `Order` schema section above. Recognize positive schema/lifecycle ownership without accepting
    // consumer-only mentions such as "read Order.status" or negative diagnostics.
    for (String sentence : evidence.split("[!?;\\n]+")) {
      if (!sentence.contains("order.status")) continue;
      if (containsAny(
          sentence,
          "does not define",
          "do not define",
          "must not define",
          "without order.status",
          "missing order.status")) {
        continue;
      }
      if (containsAny(
          sentence, "persist", "define", "migrate", "field", "column", "lifecycle", "default")) {
        return true;
      }
    }
    return false;
  }

  static int firstNonNegative(int... values) {
    int result = -1;
    for (int value : values) {
      if (value >= 0 && (result < 0 || value < result)) result = value;
    }
    return result;
  }

  static int firstPositiveAfter(String text, int start, String... needles) {
    int result = -1;
    for (String needle : needles) {
      int value = text.indexOf(needle, start + 1);
      if (value >= 0 && (result < 0 || value < result)) result = value;
    }
    return result;
  }

  static boolean isRuntimeOrMigrationTask(String evidence) {
    return containsAny(
        evidence,
        "alembic",
        "migration",
        "app/main.py",
        "app factory",
        "database.py",
        "startup",
        "readme.md",
        "running the server");
  }

  static String safe(String value) {
    return value == null ? "" : value;
  }

  static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max - 1) + "…";
  }
}
