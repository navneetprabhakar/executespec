package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;
import static com.navneet.executespec.agent.TaskExpansionSemanticContract.replaceContradictoryClauses;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TaskExpansionTextNormalizer {
  private TaskExpansionTextNormalizer() {}

  static String boundText(String value, int maxLength) {
    String safeValue = safe(value).trim();
    if (safeValue.length() <= maxLength) return safeValue;
    if (maxLength <= 0) return "";
    // lastIndexOf includes its from-index. Searching from maxLength allowed a newline or period at
    // exactly that index to be included by substring(boundary + 1), returning maxLength + 1
    // characters. Coherence commonly appends binding paragraphs after verbose prose, so this
    // one-character leak could make an otherwise mechanically compactable plan fail the final
    // 4,000-character contract gate. Search only inside the permitted prefix and retain the hard
    // min below as a second line of defence.
    int lastPermittedIndex = maxLength - 1;
    int boundary = safeValue.lastIndexOf('\n', lastPermittedIndex);
    if (boundary < maxLength / 2) boundary = safeValue.lastIndexOf('.', lastPermittedIndex);
    if (boundary < maxLength / 2) boundary = maxLength - 1;
    return safeValue.substring(0, Math.min(maxLength, boundary + 1)).trim();
  }

  static String boundTextPreservingFinalContract(String value, int maxLength) {
    String safeValue = safe(value).trim();
    if (safeValue.length() <= maxLength) return safeValue;
    List<String> paragraphs = paragraphs(safeValue);
    if (paragraphs.size() < 2) return boundText(safeValue, maxLength);

    String finalParagraph = paragraphs.getLast();
    List<String> executableContracts =
        paragraphs.stream()
            .filter(TaskExpansionTextNormalizer::isExplicitBindingParagraph)
            .filter(paragraph -> !paragraph.equals(finalParagraph))
            .toList();
    String tail = preservedTail(executableContracts, finalParagraph);
    if (tail.length() >= maxLength) return boundText(tail, maxLength);
    int headBudget = maxLength - tail.length() - 2;
    if (headBudget <= 0) return tail;
    String headSource =
        paragraphs.stream()
            .filter(paragraph -> !paragraph.equals(finalParagraph))
            .filter(paragraph -> !executableContracts.contains(paragraph))
            .collect(java.util.stream.Collectors.joining("\n\n"));
    String head = boundText(headSource, headBudget);
    return head.isBlank() ? tail : head + "\n\n" + tail;
  }

  static int preservedFinalContractChars(String value) {
    String safeValue = safe(value).trim();
    List<String> paragraphs = paragraphs(safeValue);
    if (paragraphs.isEmpty()) return 0;
    if (paragraphs.size() == 1) {
      return isExplicitBindingParagraph(paragraphs.getFirst()) ? paragraphs.getFirst().length() : 0;
    }
    String finalParagraph = paragraphs.getLast();
    List<String> executableContracts =
        paragraphs.stream()
            .filter(TaskExpansionTextNormalizer::isExplicitBindingParagraph)
            .filter(paragraph -> !paragraph.equals(finalParagraph))
            .toList();
    return preservedTail(executableContracts, finalParagraph).length();
  }

  private static List<String> paragraphs(String value) {
    String restoredBindingBoundaries =
        value.replaceAll(
            "(?<![A-Z0-9 _/+*+-])(?=(?:\\*\\*)?[A-Z][A-Z0-9 _/+-]{1,100}\\s*\\(binding\\b[^)]*\\)"
                + "\\s*:)",
            "\n\n");
    return java.util.Arrays.stream(restoredBindingBoundaries.split("\\n\\n+"))
        .map(String::trim)
        .filter(paragraph -> !paragraph.isBlank())
        .toList();
  }

  private static String preservedTail(List<String> executableContracts, String finalParagraph) {
    return String.join(
        "\n\n",
        java.util.stream.Stream.concat(
                executableContracts.stream(), java.util.stream.Stream.of(finalParagraph))
            .distinct()
            .toList());
  }

  private static boolean isExplicitBindingParagraph(String paragraph) {
    return paragraph.matches(
        "(?is)^\\s*(?:#{1,6}\\s+|[-*]\\s+)?(?:\\*\\*)?"
            + "[A-Z][A-Z0-9 _/+-]{1,100}\\s*\\(binding\\b[^)]*\\)"
            + "\\s*:\\s*(?:\\*\\*)?.*");
  }

  /**
   * Bounds an oversized worker brief without deleting executable contracts that happen to appear
   * after verbose implementation prose.
   */
  static String boundTextPreservingExecutableContracts(String value, int maxLength) {
    String safeValue = safe(value).trim();
    if (safeValue.length() <= maxLength) return safeValue;

    java.util.LinkedHashSet<String> operations = new java.util.LinkedHashSet<>();
    java.util.LinkedHashSet<String> critical = new java.util.LinkedHashSet<>();
    for (String rawLine : safeValue.split("\\R")) {
      String line = rawLine.trim();
      if (line.isBlank()) continue;
      java.util.regex.Matcher operation =
          java.util.regex.Pattern.compile(
                  "(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s*(?:`?/)[a-z0-9_{}?=&./:-]+")
              .matcher(line);
      boolean hasOperation = false;
      while (operation.find()) {
        hasOperation = true;
        operations.add(operation.group());
      }
      String lower = line.toLowerCase(Locale.ROOT);
      boolean executable =
          line.matches("(?i).*\\b(GET|POST|PUT|PATCH|DELETE)\\s*(?:`?/).*")
              || lower.contains("acceptance")
              || lower.contains("test")
              || lower.contains("transaction")
              || lower.contains("idempoten")
              || lower.contains("invariant")
              || lower.contains("constraint")
              || lower.matches(
                  ".*\\b(?:alembic|pytest|playwright|vitest|uvicorn|npm|pnpm|yarn|make)\\b.*");
      if (executable && !hasOperation && line.length() <= 360) critical.add(line);
    }

    String marker = "[Contract-preserving compaction: verbose implementation detail omitted.]";
    StringBuilder tail = new StringBuilder(marker);
    for (String operation : operations) {
      if (tail.length() + operation.length() + 1 > Math.min(1_500, maxLength / 2)) break;
      tail.append('\n').append(operation);
    }
    for (String line : critical) {
      if (tail.length() + line.length() + 1 > Math.min(1_500, maxLength / 2)) break;
      tail.append('\n').append(line);
    }
    int headBudget = maxLength - tail.length() - 2;
    if (headBudget <= 0) return boundText(tail.toString(), maxLength);
    String head = boundText(safeValue, headBudget);
    return head + "\n\n" + tail;
  }

  static String normalizeTechnicalSpacing(String value) {
    return PlanSafetyTextSupport.normalizeTechnicalSpacing(value)
        .replaceAll("(?i)(/api(?:/[a-z0-9_-]+)+)\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)}", "$1/{$2}")
        .replaceAll("\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)}", "{$1}")
        .replaceAll("(?i)\\bCANCELLED(?:LED)+\\b", "CANCELLED")
        .replaceAll("(?i)\\bCOMMITTED(?:TED)+\\b", "COMMITTED")
        .replaceAll("(?i)\\bRESERVED(?:VED)+\\b", "RESERVED")
        .replaceAll("(?i)(\\bstatus\\s*(?:becomes|→|->|=)\\s*)CANCEL\\b", "$1CANCELLED")
        .replace("npm --prefix.run build", "npm --prefix frontend run build")
        .replace("npm --prefix.run test -- --run", "npm --prefix frontend test -- --run")
        .replaceAll("(?i)npm\\s+--prefix\\s*\\.\\s*run\\s+", "npm --prefix frontend run ")
        .replaceAll(
            "(?i)cd\\s+frontend\\s*&&\\s*npm\\s+--prefix\\s+frontend\\s+", "npm --prefix frontend ")
        .replace(
            "cd frontend && npm --prefix frontend run build", "npm --prefix frontend run build")
        .replace(
            "cd frontend && npm --prefix frontend test -- --run",
            "npm --prefix frontend test -- --run")
        .replaceAll("\\.\\s+\\.\\s+\\.", "...");
  }

  static String removeEmptyNumberedSteps(String value) {
    String normalized = safe(value);
    String prior;
    do {
      prior = normalized;
      normalized = normalized.replaceAll("(?m)(^|[:\\n]\\s*)\\d+\\.\\s*(?=\\d+\\.)", "$1");
    } while (!normalized.equals(prior));
    return normalized;
  }

  static boolean installsDependenciesInsideBuildOrTest(String value) {
    String source = safe(value).toLowerCase(Locale.ROOT);
    String installer =
        "(?:pip install|npm ci|npm install|create(?:s)? (?:a )?(?:python )?venv|"
            + "python -m venv)";
    String script = "(?:build\\.sh|test\\.sh|run_tests\\.sh|run-tests\\.sh|make test|build target)";
    if (source.matches(
        "(?s).*"
            + script
            + "[^.\\n]{0,180}(?:runs?|executes?|contains?|invokes?|performs?|creates?)"
            + "[^.\\n]{0,120}"
            + installer
            + ".*")) {
      return true;
    }
    if (source.matches(
        "(?s).*(?:`build`|build target|(?:^|\\n)\\s*[-*]?\\s*build\\s*\\()"
            + "[^.\\n]{0,220}"
            + installer
            + ".*")) {
      return true;
    }
    boolean scriptSection = false;
    for (String rawLine : source.split("\\R", -1)) {
      String line = rawLine.trim();
      if (line.matches(
          "(?:#{1,6}\\s*)?(?:readme(?:\\.md)?\\s+)?(?:setup|installation|prerequisites)"
              + "\\b.*")) {
        scriptSection = false;
      }
      boolean namesScript =
          line.matches(".*" + script + "\\s*(?::|\\(|$).*")
              && !line.matches(".*(?:readme|setup|installation|prerequisites).*");
      if (namesScript) scriptSection = true;
      if ((namesScript || scriptSection) && line.matches(".*" + installer + ".*")) return true;
      if (scriptSection
          && line.matches("(?:#{1,6}\\s+|\\d+\\.\\s+|[-*]\\s+)(?!.*" + script + ").*:\\s*$")) {
        scriptSection = false;
      }
    }
    return false;
  }

  static String rewriteIncompleteIdempotencyRecordSchema(String description) {
    String canonical =
        "IdempotencyRecord fields are key, scope, request_body_hash, state"
            + " (PROCESSING|COMPLETED), nullable response_status, and nullable response_body,"
            + " with a database UniqueConstraint(key, scope).";
    String source = safe(description).trim();
    List<String> normalizedLines = new ArrayList<>();
    for (String line : source.split("\\R", -1)) {
      line = normalizeIdempotencyRecordSegment(line);
      normalizedLines.add(line);
    }
    source = String.join("\n", normalizedLines).trim();
    if (source.contains(canonical)) return source;
    // Preserve surrounding procedures: deleting a mixed schema/procedure paragraph previously
    // produced empty numbered steps and silently removed executable behavior.
    return source.isBlank() ? canonical : source + "\n\nCONTRACT CORRECTION: " + canonical;
  }

  private static String normalizeIdempotencyRecordSegment(String line) {
    String lower = line.toLowerCase(Locale.ROOT);
    int start = lower.indexOf("idempotencyrecord");
    if (start < 0) return line;
    int end = line.length();
    java.util.regex.Matcher nextModel =
        java.util.regex.Pattern.compile("\\s\\d+\\.\\s+\\*\\*").matcher(line);
    if (nextModel.find(start + "idempotencyrecord".length())) end = nextModel.start();
    String segment =
        line.substring(start, end)
            .replaceAll("(?i)\\bprincipal(?:_id)?\\b", "scope")
            .replaceAll("(?i)\\brequest_hash\\b", "request_body_hash")
            .replaceAll("(?i)\\bstatus\\b", "state")
            .replaceAll("(?i)\\bPENDING\\b", "PROCESSING")
            .replaceAll("(?i)\\bkey\\s*\\(\\s*unique\\s*\\)", "key")
            .replaceAll("(?i)\\bunique\\s*\\(\\s*key\\s*\\)", "UniqueConstraint(key, scope)");
    return line.substring(0, start) + segment + line.substring(end);
  }

  static String addIdempotencyTransactionContract(String description) {
    return appendContract(
        description,
        "IDEMPOTENCY TRANSACTION (binding): claim IdempotencyRecord ownership before any business"
            + " mutation in the same transaction, persist the business result and COMPLETED replay"
            + " response before commit, and make concurrent losers wait then re-read.");
  }

  static String addReleaseAlgorithmContract(String description) {
    return appendContract(
        description,
        "RELEASE ALGORITHM (binding): first execute a guarded conditional UPDATE WHERE"
            + " status='ACTIVE', verify rowcount == 1, and only then restore reserved stock in the"
            + " same transaction.");
  }

  static String rewriteStockAdjustmentAvailabilityGuard(String description) {
    return safe(description)
        .replaceAll("(?i)(`?on_hand`?\\s*\\+\\s*[^\\n;]{1,100}?\\s*>=\\s*)0\\b", "$1reserved")
        .replaceAll(
            "(?i)on_hand\\s*\\+\\s*delta\\s*>=\\s*reserved", "on_hand + :delta >= reserved");
  }

  static String rewriteStockTransferAvailabilityGuard(String description) {
    return safe(description)
        .replaceAll("(?i)(`?on_hand`?\\s*-\\s*[^\\n;]{1,100}?\\s*>=\\s*)0\\b", "$1reserved")
        .replaceAll(
            "(?i)on_hand\\s*-\\s*quantity\\s*>=\\s*reserved", "on_hand - :quantity >= reserved");
  }

  private static String appendContract(String description, String correction) {
    String source = safe(description).trim();
    return source.contains(correction) ? source : source + "\n\n" + correction;
  }

  static String replaceRealHttpContradictions(String description) {
    String canonical =
        "Launch the documented server as a subprocess, apply migrations to a temporary file-backed"
            + " SQLite database visible to that process, and use a real HTTP client against its"
            + " loopback URL without binding the application object into the client or replacing"
            + " runtime dependencies; an in-process client is not acceptance proof.";
    return replaceContradictoryClauses(
        description,
        canonical,
        "testclient",
        "app factory",
        "app=app",
        "get_db override",
        "dependency override",
        "not a subprocess",
        "without a subprocess");
  }

  static String replacePredictableCredentialContradictions(String description) {
    String source = safe(description);
    String[] forbidden = {
      "hardcoded test token",
      "hardcoded token",
      "deterministic test token",
      "deterministic token",
      "default test token",
      "default bearer token",
      "default credential",
      "fallback test token",
      "fallback bearer token",
      "fallback credential",
      "predictable test credential",
      "predictable credential"
    };
    if (!TaskExpansionSemanticContract.hasPositiveInstruction(source, forbidden)) return source;
    String canonical =
        "Production/runtime code obtains credentials only from a fail-closed external"
            + " configuration or authentication flow and never ships a hardcoded, deterministic,"
            + " fallback, or predictable default credential; randomly generated credentials are"
            + " confined to isolated test fixtures.";
    return replaceContradictoryClauses(source, canonical, forbidden);
  }

  static String rewriteFrontendOrderPayload(String description) {
    String normalized =
        safe(description)
            .replace("product_sku", "product_id")
            .replace("warehouse_code", "warehouse_id")
            .replaceAll("(?i)`?400`? (?:on )?insufficient stock", "422 on insufficient stock");
    String canonical =
        "The frontend order payload is {items: [{product_id, warehouse_id, quantity}]}; send the"
            + " required Idempotency-Key header, and surface the backend 422 insufficient-stock"
            + " response. Do not add alternate customer or string identity fields that the"
            + " backend does not define.";
    return replaceContradictoryClauses(normalized, canonical, "customer_name", "customer name");
  }

  static String rewriteRootVerificationCommands(String description) {
    String rewritten =
        safe(description)
            .replace("npm --prefix.run build", "npm --prefix frontend run build")
            .replace("npm --prefix.run test -- --run", "npm --prefix frontend test -- --run")
            .replaceAll(
                "(?i)cd frontend\\s*&&\\s*npm\\s+--prefix\\s*\\.\\s*run\\s+build",
                "npm --prefix frontend run build")
            .replaceAll(
                "(?i)cd frontend\\s*&&\\s*npm\\s+--prefix\\s*\\.\\s*run\\s+test\\s+--\\s+--run",
                "npm --prefix frontend test -- --run")
            .replaceAll(
                "(?is)`?cd backend\\s*&&\\s*python\\s+-c\\s+\"[^\"]+\"`?",
                "`python3.12 -m compileall -q backend/app`")
            .replaceAll(
                "(?i)`?python(?:3(?:\\.\\d+)?)?\\s+-c\\s+(['\"])[^\\n`]*?\\1`?",
                "`python3.12 -m compileall -q backend/app`")
            .replaceAll(
                "(?is)`?cd frontend\\s*&&\\s*npx tsc --noEmit\\s*&&\\s*npx vite build`?",
                "`npm --prefix frontend run build`")
            .replaceAll("(?im)^\\s*- `?python -m alembic upgrade head`?[^\\n]*$", "")
            .replaceAll(
                "(?im)^\\s*- `?python -c [^\\n]*$",
                "- `python3.12 -m compileall -q backend/app` — backend compile check.")
            .replace("python -m pytest tests/-v", "python3.12 -m pytest -v backend/tests")
            .replace(
                "cd backend && python -m pytest tests/-v", "python3.12 -m pytest -v backend/tests")
            .replace(
                "cd backend && python3.12 -m pytest -v backend/tests",
                "python3.12 -m pytest -v backend/tests")
            .replace("cd frontend && npx vitest run", "npm --prefix frontend test -- --run")
            .replace(
                "python -m pytest tests/-v --integration",
                "python3.12 -m pytest -v backend/tests/integration")
            .replace("npx --prefix frontend vitest run", "npm --prefix frontend test -- --run")
            .replace(
                "cd backend && python -m pytest tests/-k \"not acceptance\" --tb=short",
                "python3.12 -m pytest backend/tests --ignore=backend/tests/acceptance --tb=short")
            .replace(
                "cd backend && python -m pytest tests/acceptance/--tb=short",
                "python3.12 -m pytest backend/tests/acceptance --tb=short")
            // Provider output varies between python/python3/python3.12 and frequently embeds these
            // malformed path/option joins inside longer commands. Normalize the broken tokens
            // independently instead of relying on an exact full-command spelling.
            .replaceAll("(?i)\\btests/-v\\b", "backend/tests -v")
            .replaceAll("(?i)\\btests/-k\\b", "backend/tests -k")
            .replaceAll(
                "(?i)\\btests/acceptance/--([a-z][a-z0-9-]*)\\b", "backend/tests/acceptance --$1")
            .replaceAll(
                "(?i)\\btests/--ignore=tests/acceptance\\b",
                "backend/tests --ignore=backend/tests/acceptance")
            .replaceAll(
                "(?i)\\btests/acceptance/(-[a-z][a-z0-9-]*)\\b", "backend/tests/acceptance $1")
            .replaceAll(
                "(?i)If no e2e script exists, skip with a comment\\.?",
                "Run the required browser E2E suite through `npm --prefix frontend run test:e2e`;"
                    + " missing E2E configuration is a test failure, never a skip.")
            .replaceAll(
                "(?i)python(?:3(?:\\.\\d+)?)?\\s+-c\\s+\"import backend\\.app\\.main\"",
                "python3.12 -m compileall -q backend/app");
    // DeepSeek commonly emits the README migration/start commands and the build.sh body in one
    // compact paragraph. A broad proximity check then sees "build.sh ... alembic" even when the
    // migration command belongs to README. Normalize only clauses that positively assign a
    // forbidden command to build.sh; leave standalone README setup commands untouched.
    rewritten =
        rewritten
            .replaceAll(
                "(?is)build\\.sh([^\\.\\n]{0,240})(?:runs?|executes?|contains?|includes?)"
                    + "([^\\.\\n]{0,160})python(?:3(?:\\.\\d+)?)?\\s+-m\\s+alembic"
                    + "[^\\.\\n]*",
                "build.sh runs `python3.12 -m compileall -q backend/app` and"
                    + " `npm --prefix frontend run build`")
            .replaceAll(
                "(?is)build\\.sh([^\\.\\n]{0,240})(?:runs?|executes?|contains?|includes?)"
                    + "([^\\.\\n]{0,160})python(?:3(?:\\.\\d+)?)?\\s+-c\\s+[^\\.\\n]*",
                "build.sh runs `python3.12 -m compileall -q backend/app` and"
                    + " `npm --prefix frontend run build`");
    rewritten = normalizeRootBuildCommandClauses(rewritten);
    String canonical =
        "Binding commands: ./build.sh runs only `python3.12 -m compileall -q backend/app` and"
            + " `npm --prefix frontend run build` from the repository root. ./run_tests.sh runs"
            + " backend pytest, subprocess-based real-HTTP acceptance tests, and frontend Vitest"
            + " plus required Playwright browser E2E through declared package.json scripts, with"
            + " already-installed dependencies and no suite executed twice.";
    return rewritten.contains(canonical) ? rewritten : rewritten + "\n\n" + canonical;
  }

  /**
   * Compiles the packaging/verification task from the user contract instead of preserving
   * stochastic command prose. Shell commands are executable data: retaining a provider-generated
   * variant and appending a correction leaves the worker with two competing programs.
   */
  static String compileRootVerificationTaskDescription() {
    return """
Create the production root verification scripts and README; do not preserve or reinterpret
alternate commands from the planning draft.

- `./build.sh` is executable, uses `set -euo pipefail`, stays at the repository root, and
  runs only `python3.12 -m compileall -q backend/app` followed by
  `npm --prefix frontend run build`.
- `./run_tests.sh` is executable, uses already-installed dependencies, and runs each suite
  exactly once: backend maintained tests excluding the acceptance directory, the
  subprocess-based real-HTTP acceptance directory, frontend Vitest, and the required
  Playwright browser E2E package script. Use these exact commands:
  `python3.12 -m pytest backend/tests --ignore=backend/tests/acceptance --tb=short`;
  `python3.12 -m pytest backend/tests/acceptance --tb=short`;
  `npm --prefix frontend test -- --run`;
  `npm --prefix frontend run test:e2e`.
- Missing tests, package scripts, Playwright configuration, or dependencies are failures,
  never passing placeholders or skips. Neither root script installs packages, creates an
  environment, applies migrations, seeds data, or changes working directory.
- `README.md` documents prerequisites and exact setup/install, Alembic migration, uvicorn
  start, frontend development, root build, and root test commands that match the files
  actually shipped. Installation belongs only in documented setup, not in build/test
  execution.
""";
  }

  private static String normalizeRootBuildCommandClauses(String value) {
    String canonicalBuildClause =
        "./build.sh runs only `python3.12 -m compileall -q backend/app` and"
            + " `npm --prefix frontend run build` from the repository root.";
    List<String> retained = new ArrayList<>();
    for (String clause : safe(value).split("(?<=[.!?])\\s+|\\R+", -1)) {
      String lower = clause.toLowerCase(Locale.ROOT);
      boolean assignsToBuild =
          lower.matches(
              "(?s).*build\\.sh[^.\\n]{0,240}"
                  + "(?:runs?|executes?|contains?|includes?)[^.\\n]{0,160}.*");
      boolean forbidden =
          lower.matches(
              "(?s).*(?:python(?:3(?:\\.\\d+)?)?\\s+-m\\s+alembic|"
                  + "python(?:3(?:\\.\\d+)?)?\\s+-c\\s+).*");
      retained.add(assignsToBuild && forbidden ? canonicalBuildClause : clause.trim());
    }
    return String.join("\n", retained).trim();
  }

  static String replaceRepeatedCancellationSuccess(String description) {
    String canonical =
        "The second cancellation returns the stable invalid-state 409 response while stock remains"
            + " restored exactly once.";
    return replaceContradictoryClauses(
        description,
        canonical,
        "second cancel",
        "second release",
        "already released",
        "same release",
        "idempotent replay of same release",
        "double cancellation",
        "double release",
        "repeated cancellation",
        "repeated release");
  }

  static String replaceRootBuildWorkingDirectory(String description) {
    String canonical =
        "Root ./build.sh runs from the repository root: python3.12 -m compileall -q backend/app,"
            + " then npm --prefix frontend run build, using already-installed dependencies.";
    String rewritten =
        safe(description)
            .replaceAll("(?im)^\\s*(?:[-*]\\s*)?cd\\s+backend\\s*(?:&&)?\\s*$", "")
            .replaceAll("(?im)^\\s*(?:[-*]\\s*)?cd\\s+\\.\\.\\s*(?:&&)?\\s*$", "")
            .replaceAll(
                "(?i)cd backend\\s*&&\\s*\\(?python(?:3\\.12)?\\s+-m\\s+compileall(?:\\s+-q)?"
                    + "\\s+[^;&\\n`)]+\\)?",
                "python3.12 -m compileall -q backend/app")
            .replaceAll(
                "(?im)^\\s*(?:[-*]\\s*)?(?:python|python3|python3\\.12)\\s+-m\\s+compileall(?:\\s+-q)?"
                    + "\\s+(?:\\.|app|backend)(?=\\s*$)",
                "- python3.12 -m compileall -q backend/app")
            .replaceAll(
                "(?i)(?:cd \\.\\./frontend|cd frontend)\\s*&&\\s*npm run build",
                "npm --prefix frontend run build");
    if (!rewritten.contains("python3.12 -m compileall -q backend/app")
        || !rewritten.contains("npm --prefix frontend run build")) {
      return rewritten + "\n\n" + canonical;
    }
    if (!rewritten.toLowerCase(Locale.ROOT).contains("repository root")) {
      return "Root ./build.sh executes from the repository root.\n\n" + rewritten;
    }
    return rewritten;
  }
}
