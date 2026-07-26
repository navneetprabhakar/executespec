package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.safe;

import java.util.List;
import java.util.Locale;

/** Text repairs for executable setup, persistence, and black-box acceptance contracts. */
final class PlanSafetyExecutionTextSupport {

  private PlanSafetyExecutionTextSupport() {}

  static String idempotencyMigrationPath(List<PlannedTask> tasks) {
    if (tasks != null) {
      for (PlannedTask candidate : tasks) {
        if (candidate == null || candidate.touchesFiles() == null) continue;
        for (String path : candidate.touchesFiles()) {
          if (path == null) continue;
          String normalized = path.replace('\\', '/');
          int versions = normalized.toLowerCase(Locale.ROOT).indexOf("/alembic/versions/");
          if (versions >= 0) {
            return normalized.substring(0, versions + "/alembic/versions/".length())
                + "002_add_idempotency_record.py";
          }
        }
      }
    }
    return "backend/alembic/versions/002_add_idempotency_record.py";
  }

  static String canonicalIdempotencyModelPath(Plan plan) {
    if (plan != null && plan.tasks() != null) {
      for (PlannedTask task : plan.tasks()) {
        if (task == null || task.touchesFiles() == null) continue;
        for (String path : task.touchesFiles()) {
          if (path == null) continue;
          String normalized = path.replace('\\', '/');
          if (normalized.toLowerCase(Locale.ROOT).endsWith("/models.py")) return normalized;
        }
      }
      for (PlannedTask task : plan.tasks()) {
        if (task == null || task.touchesFiles() == null) continue;
        for (String path : task.touchesFiles()) {
          if (path == null) continue;
          String normalized = path.replace('\\', '/');
          int packageIndex = normalized.toLowerCase(Locale.ROOT).indexOf("/models/");
          if (packageIndex >= 0) {
            return normalized.substring(0, packageIndex + "/models/".length())
                + "idempotency_record.py";
          }
        }
      }
    }
    return "backend/app/models/idempotency_record.py";
  }

  static String rewriteRealHttpDatabase(String value) {
    return safe(value)
        .replaceAll(
            "(?i)(real HTTP client[^.\\n]{0,180})uses (?:an )?in-memory SQLite database",
            "$1uses a temporary file-backed SQLite database visible to the server subprocess")
        .replaceAll(
            "(?i)(real HTTP client[^.\\n]{0,180})with (?:an )?in-memory SQLite database",
            "$1with a temporary file-backed SQLite database visible to the server subprocess")
        .replaceAll(
            "(?i)(server launched as a subprocess[^.\\n]{0,180})against (?:an )?in-memory SQLite"
                + " database",
            "$1against a temporary file-backed SQLite database visible to that subprocess");
  }

  static String rewriteRepeatedCancellation(String value) {
    return safe(value)
        .replaceAll(
            "(?i)(?:the )?second cancel(?:lation)?[^.\\n]{0,140}(?:returns?|expects?|asserts?)"
                + "[^.\\n]{0,80}(?:200|success|stored response|same response|idempotent response)",
            "the second cancellation returns the stable invalid-state 409 response while stock"
                + " remains restored exactly once")
        .replaceAll(
            "(?i)(?:double|repeated) cancellation[^.\\n]{0,120}(?:returns?|is)[^.\\n]{0,80}"
                + "(?:200|successful|success|idempotent)",
            "repeated cancellation returns the stable invalid-state 409 response while stock"
                + " remains restored exactly once");
  }

  static String rewriteRootBuildExecution(String value) {
    String canonical =
        "Root `./build.sh` runs from the repository root: `python3.12 -m compileall -q backend/app`"
            + " followed by `npm --prefix frontend run build`, using already-installed"
            + " dependencies.";
    String rewritten =
        safe(value)
            .replaceAll(
                "(?i)cd backend\\s*&&\\s*\\(?python(?:3\\.12)?\\s+-m\\s+compileall(?:\\s+-q)?"
                    + "\\s+[^;&\\n`)]+\\)?",
                "python3.12 -m compileall -q backend/app")
            .replaceAll(
                "(?i)(?:cd \\.\\./frontend|cd frontend)\\s*&&\\s*npm run build",
                "npm --prefix frontend run build");
    boolean mentionsRootBuild = rewritten.toLowerCase(Locale.ROOT).contains("build.sh");
    boolean hasCanonicalCommands =
        rewritten.contains("python3.12 -m compileall -q backend/app")
            && rewritten.contains("npm --prefix frontend run build");
    if (mentionsRootBuild && !hasCanonicalCommands) return canonical + "\n\n" + rewritten;
    if (mentionsRootBuild
        && hasCanonicalCommands
        && !rewritten.toLowerCase(Locale.ROOT).contains("repository root")) {
      return "Root ./build.sh executes from the repository root.\n\n" + rewritten;
    }
    return rewritten;
  }

  static String rewriteBlackBoxAcceptanceContradictions(String value, String requirements) {
    String rewritten =
        safe(value)
            .replaceAll(
                "(?i)release reservation and verify status ACTIVE",
                "release reservation and verify Reservation.status RELEASED and Order.status"
                    + " PENDING")
            .replaceAll(
                "(?is)\\*\\*Before all tests:\\*\\*\\s*Call `?POST \\{?BACKEND_URL\\}?/api/seed`?"
                    + ".*?(?=\\n\\n\\*\\*Test files)",
                "**Before all tests:** start the documented backend against a unique temporary"
                    + " file-backed database, apply Alembic migrations, and run the documented"
                    + " startup seed path. The E2E suite owns both backend and frontend processes.")
            .replaceAll(
                "(?im)^- `?seed`? fixture: calls? `?POST /api/seed`?[^\\n]*$",
                "- `seed` fixture: starts a fresh documented backend process against a unique"
                    + " temporary database; migrations and startup seed provide known state.")
            .replaceAll(
                "(?i)POST /api/seed \\(or the documented seed endpoint from T\\d+\\)",
                "the documented startup seed path on a fresh temporary database")
            .replaceAll("(?i)POST /api/seed", "the documented startup seed path")
            .replaceAll(
                "(?i)Fire 5 concurrent `?POST /api/reservations`?[^.]*for the same"
                    + " order\\+warehouse, each with a unique `?Idempotency-Key`?",
                "Create five separate pending orders that each need the same last stock unit,"
                    + " then fire five concurrent `POST /api/reservations` requests for those"
                    + " distinct orders and the same warehouse, each with a unique"
                    + " `Idempotency-Key`")
            .replaceAll(
                "(?i)all others get `?INSUFFICIENT_STOCK`? or `?IDEMPOTENCY_KEY_CONFLICT`?",
                "all others get `INSUFFICIENT_STOCK`")
            .replace(
                "(or reuse an already-running instance; do NOT start backend — the acceptance"
                    + " suite owns that)",
                "and start the backend with the documented command against a unique temporary"
                    + " database")
            .replaceAll(
                "(?i)cd backend\\s*&&\\s*uvicorn\\s+backend\\.app\\.main:app",
                "cd backend && uvicorn app.main:app")
            .replaceAll(
                "(?i)(cd backend\\s*&&[^\\n]{0,180}?uvicorn\\s+)backend\\.app\\.main:app",
                "$1app.main:app")
            .replaceAll(
                "(?i)cd frontend\\s*&&\\s*npx vite\\s+--port",
                "cd frontend && npm run dev -- --port")
            .replaceAll(
                "(?i)GET the same audit event by ID",
                "GET /api/audit and locate the same audit event by ID in the documented list"
                    + " response");
    if (requirements.contains("positive or negative stock adjustment")) {
      rewritten =
          rewritten
              .replaceAll(
                  "(?i)POST adjustment with negative quantity",
                  "POST adjustment with zero delta, and a negative delta that would make stock"
                      + " negative")
              .replaceAll(
                  "(?i)(?:submit|post|send)(?: an?)? adjustment with negative"
                      + " (?:quantity|delta)[^.\\n]{0,140}(?:error|invalid|reject)[^.\\n]*",
                  "submit an adjustment with zero delta and assert validation, then submit a"
                      + " negative delta that would make stock negative and assert the stable"
                      + " insufficient-stock error");
    }
    boolean warehouseCreationRequired =
        requirements.contains("create warehouse")
            || requirements.contains("create a warehouse")
            || requirements.contains("warehouse creation");
    boolean productCreationRequired =
        requirements.contains("create product")
            || requirements.contains("create a product")
            || requirements.contains("product creation");
    if (!warehouseCreationRequired && !productCreationRequired) {
      rewritten =
          rewritten
              .replaceAll(
                  "(?i)Create a warehouse and product via API, note their IDs[^.\\n]*\\.",
                  "Use deterministic seeded warehouse/product IDs, make a public stock adjustment,"
                      + " and record the resulting stock and audit response.")
              .replaceAll(
                  "(?i)GET the same warehouse/product[^.\\n]*\\.",
                  "After restarting the backend, read the same stock through the documented"
                      + " warehouse-stock API and verify the adjustment and audit entry persist.");
    }
    return rewritten;
  }
}
