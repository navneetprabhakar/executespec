package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyContractEnricher.*;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.*;
import static com.navneet.executespec.agent.PlanSafetyStructureSupport.*;
import static com.navneet.executespec.agent.PlanSafetyTextSupport.*;

import java.util.Locale;

final class PlanSafetyTextSupport {

  private PlanSafetyTextSupport() {}

  static String normalizeSqliteIdempotencyAlgorithm(String value) {
    return PlanSafetyReservationTextSupport.normalizeSqliteIdempotencyAlgorithm(value);
  }

  static String normalizeIdempotencyRecordNames(String value) {
    return safe(value)
        .replaceAll("\\bIdempotencyKey\\b", "IdempotencyRecord")
        .replaceAll(
            "(?i)(complete_(?:idempotency_)?record[^.\\n"
                + "]{0,500}?\\b(?:to|state\\s*=?)\\s*)PENDING\\b",
            "$1COMPLETED")
        .replaceAll(
            "(?i)(\\bupdate (?:the )?(?:idempotency )?record to\\s+)PENDING\\b", "$1COMPLETED")
        .replaceAll("(?i)\\bPROCESSING\\s*(?:→|->|to)\\s*PENDING\\b", "PROCESSING→COMPLETED")
        .replaceAll("\\bIDEMPOTENCY_CONFLICT\\b", "IDEMPOTENCY_KEY_CONFLICT")
        .replaceAll("(?i)\\bIdempotency-Replayed\\b", "X-Idempotency-Replay")
        .replaceAll(
            "(?i)(idempotency key conflict[^.\\n]*\\.|conflicting reuse[^.\\n]*\\.)"
                + "([^\\n]{0,180}?\\bcode\\s+`?)CONFLICT(`?)",
            "$1$2IDEMPOTENCY_KEY_CONFLICT$3")
        .replaceAll("(?i)\\(key,\\s*operation\\)", "(key, scope)")
        .replaceAll("(?i)operation=\\\"", "scope=\\\"")
        .replaceAll("(?i)operation='", "scope='")
        .replaceAll("(?i)\\bpayload_hash\\b", "request_body_hash")
        .replaceAll("(?i)(IdempotencyRecord[^.\\n]{0,180})\\bstatus\\b", "$1state")
        .replaceAll(
            "(?i)(IdempotencyRecord[^.\\n]{0,400})\\bstatus\\s*(\\(String,\\s*PROCESSING)",
            "$1state $2")
        .replaceAll("(?i)(idempotency_records?[^.\\n]{0,180})\\bstatus\\b", "$1state");
  }

  static String normalizeOrderStatusValues(String value, OrderStatusContract contract) {
    String input =
        safe(value).replace(contract.constraint(), "").replace(ORDER_LIFECYCLE_SUFFIX, "");
    String canonicalType =
        "string: " + String.join(" | ", contract.values()) + ", default " + contract.activeValue();
    input =
        input
            .replaceAll(
                "(?i)(\\bOrder\\b[^.;\\n]{0,120}?\\bstatus\\s*\\()(?:string\\s*:\\s*)?"
                    + "[A-Z_| /,]+(?:,\\s*default\\s+[A-Z_]+)?\\)",
                "$1" + java.util.regex.Matcher.quoteReplacement(canonicalType) + ")")
            .replace("(PENDING/RESERVED/RELEASED/PENDING)", "(PENDING/RESERVED)")
            .replace("(PENDING, RESERVED, RELEASED, PENDING)", "(PENDING, RESERVED)")
            .replace("(PENDING | RESERVED | RELEASED | PENDING)", "(PENDING | RESERVED)");
    java.util.regex.Matcher sentences =
        java.util.regex.Pattern.compile("(?s)(.+?(?:[!?]|\\.(?=\\s|$)|\\n|$))").matcher(input);
    StringBuilder normalized = new StringBuilder();
    boolean lifecycleDeclared = !input.equals(safe(value));
    while (sentences.find()) {
      String sentence = sentences.group();
      String lower = sentence.toLowerCase(Locale.ROOT);
      int declaredStates = 0;
      for (String state : ORDER_STATES) {
        if (sentence.matches("(?s).*\\b" + state + "\\b.*")) declaredStates++;
      }
      if (lower.contains("order")
          && lower.contains("status")
          && declaredStates >= 2
          && containsAny(lower, "values", "lifecycle", "enum")) {
        lifecycleDeclared = true;
        continue;
      }
      if (lower.contains("new and released orders are")
          && lower.contains("reservation release state belongs")) {
        lifecycleDeclared = true;
        continue;
      }
      for (String state : ORDER_STATES) {
        if (!contract.values().contains(state)) {
          sentence =
              sentence.replaceAll(
                  "(?i)(\\bOrder\\.status\\s+(?:is\\s+)?)" + state + "\\b",
                  "$1" + contract.activeValue());
        }
      }
      lower = sentence.toLowerCase(Locale.ROOT);
      if (lower.contains("status")
          && !lower.contains("reservation")
          && !lower.contains("idempotencyrecord")
          && !lower.contains("idempotency record")) {
        boolean cancellationAction =
            lower.contains("cancellation")
                || lower.contains("cancel button")
                || lower.contains("after cancel")
                || lower.contains("cancel order")
                || lower.contains("show cancel");
        String replacement =
            (cancellationAction && contract.values().contains("CANCELLED"))
                ? "CANCELLED"
                : contract.activeValue();
        for (String state : ORDER_STATES) {
          if (!contract.values().contains(state)) {
            sentence = sentence.replaceAll("\\b" + state + "\\b", replacement);
          }
        }
      }
      normalized.append(sentence);
    }
    String result = cleanDescriptionWhitespace(normalized.toString());
    if (lifecycleDeclared) {
      result = result.isEmpty() ? contract.constraint() : result + "\n\n" + contract.constraint();
    }
    return cleanDescriptionWhitespace(result);
  }

  static String normalizeReservationStatusValues(String value) {
    String input =
        safe(value)
            .replace(RESERVATION_LIFECYCLE_CONSTRAINT, "")
            .replace(RESERVATION_LIFECYCLE_SHORT, "");
    java.util.regex.Matcher sentences =
        java.util.regex.Pattern.compile("(?s)(.+?(?:[!?]|\\.(?=\\s|$)|\\n|$))").matcher(input);
    StringBuilder normalized = new StringBuilder();
    boolean lifecycleDeclared = !input.equals(safe(value));
    while (sentences.find()) {
      String sentence = sentences.group();
      String lower = sentence.toLowerCase(Locale.ROOT);
      if (lower.contains("reservation") && lower.contains("status")) {
        if (containsAny(lower, "lifecycle", "allowed values", "enum")
            || (lower.contains("reservation.status") && lower.contains(" or "))) {
          lifecycleDeclared = true;
          continue;
        }
        sentence =
            sentence.replaceAll(
                "(?i)(transition(?:s|ed|ing)?\\s+(?:the\\s+)?Reservation\\s+status\\s+from)"
                    + "\\s+[A-Z_]+(\\s+to\\s+)[A-Z_]+",
                "$1 ACTIVE$2RELEASED");
        sentence =
            sentence.replaceAll(
                "(?i)(UPDATE\\s+reservations\\s+SET\\s+status\\s*=\\s*['\"])[A-Z_]+"
                    + "(['\"].{0,240}?\\bstatus\\s*=\\s*['\"])[A-Z_]+(['\"])",
                "$1RELEASED$2ACTIVE$3");
        sentence =
            sentence.replaceAll("(?i)(reservation\\s+status\\s+must\\s+be\\s+)[A-Z_]+", "$1ACTIVE");
        if (lower.contains("release") || lower.contains("cancel")) {
          sentence =
              sentence
                  .replaceAll(
                      "(?i)release_reservation\\(db,\\s*order_id",
                      "release_reservation(db, reservation_id")
                  .replace("release:{order_id}", "release:{reservation_id}")
                  .replace("{\"order_id\": order_id}", "{\"reservation_id\": reservation_id}");
        }
      }
      normalized.append(sentence);
    }
    String result = cleanDescriptionWhitespace(normalized.toString());
    if (lifecycleDeclared) {
      result =
          result.isEmpty()
              ? RESERVATION_LIFECYCLE_CONSTRAINT
              : result + "\n\n" + RESERVATION_LIFECYCLE_CONSTRAINT;
    }
    result =
        result
            .replaceAll(
                "(?i)(Reserve\\s+PENDING\\s+order[^.\\n]{0,180}?→\\s*201,\\s*)status\\s+PENDING",
                "$1Reservation.status ACTIVE")
            .replaceAll(
                "(?i)(release(?:d)?\\s+(?:the\\s+)?reservation[^.\\n]{0,160}?"
                    + "(?:verify|returns?|status)\\s+)ACTIVE\\b",
                "$1RELEASED")
            .replaceAll(
                "(?i)(\\bReservation(?:Out)?[^.\\n]{0,180}?\\bstatus\\b[^A-Za-z]{0,20})"
                    + "(?:PENDING|RESERVED)\\b",
                "$1ACTIVE")
            .replaceAll(
                "(?i)(\\breservation status\\s+(?:shows|is|to|=|:)\\s*[\"`]?)"
                    + "(?:PENDING|RESERVED)\\b",
                "$1ACTIVE")
            .replaceAll(
                "(?i)(\\btransitions? status\\s+)(?:PENDING|RESERVED)\\s*(?:→|->|to)\\s*"
                    + "RELEASED\\b",
                "$1ACTIVE→RELEASED");
    return cleanDescriptionWhitespace(result);
  }

  static String cleanDescriptionWhitespace(String value) {
    return safe(value)
        .replaceAll("[ \\t]+\\n", "\n")
        .replaceAll("\\n(?:[ \\t]*\\n){2,}", "\n\n")
        .trim();
  }

  static String rewriteOrmSchemaCreation(String value) {
    return safe(value)
        .replaceAll(
            "(?is)(an? `?async_session`? fixture[^.\\n]{0,300}?)that creates all tables via"
                + " `?Base\\s*\\.\\s*metadata(?:\\s*\\.\\s*create_all(?:\\([^)]*\\))?)?`?\\.",
            "$1that applies the configured Alembic migrations to the temporary database.")
        .replaceAll(
            "(?i)lifespan that creates tables on startup \\(call"
                + " `?Base\\.metadata\\.create_all`? as a fallback; the migration runner is the"
                + " primary path\\)",
            "lifespan that runs `alembic upgrade head` before serving requests; ORM schema"
                + " auto-creation is forbidden")
        .replaceAll(
            "(?i)runs? `?Base\\.metadata\\.create_all`?",
            "applies the configured Alembic migration")
        .replaceAll(
            "(?i)lifespan that creates tables on startup \\(via"
                + " `?Base\\.metadata\\.create_all`?\\)",
            "lifespan that runs Alembic migrations (`alembic upgrade head`) on startup");
  }

  static String normalizeTechnicalSpacing(String value) {
    String normalized =
        safe(value)
            .replaceAll("(?i)`(GET|POST|PUT|PATCH|DELETE)\\s*/api/", "`$1 /api/")
            .replaceAll("(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s*/api/", "$1 /api/")
            .replaceAll("(?i)README\\.\\s+md\\b", "README.md")
            .replaceAll(
                "(?i)([A-Za-z0-9_/-])\\.\\s+(sh|py|ts|tsx|js|jsx|json|toml|ini|txt|md)\\b", "$1.$2")
            .replaceAll("(?i)Python\\s+(\\d+)\\.\\s+(\\d+)", "Python $1.$2")
            .replaceAll("(?i)Node\\.\\s*js", "Node.js")
            .replaceAll("(?i)app\\.\\s+main:app", "app.main:app")
            .replaceAll("(?i)Base\\.\\s+metadata", "Base.metadata")
            .replaceAll("(?i)crypto\\.\\s+randomUUID", "crypto.randomUUID")
            .replaceAll(
                "(?i)SQLAlchemy(\\s*(?:>=|~=|==|>|<)?\\s*)(\\d+)\\.\\s+(\\d+)", "SQLAlchemy$1$2.$3")
            .replaceAll(
                "(?i)\\b(httpx|backend|frontend|app|database|Base)\\.\\s+(?=[A-Za-z_])", "$1.")
            .replaceAll("(?i)\\be\\.\\s+g\\.", "e.g.")
            .replaceAll("\\.\\s+/([A-Za-z0-9_./-]+\\.(?:sh|py))", "./$1")
            .replace(". . .", "...")
            .replaceAll(
                "(?i)green for PENDING,\\s*yellow for PENDING,\\s*red for CANCELLED",
                "green for PENDING and red for CANCELLED");
    return normalizeBacktickCodeSpacing(normalized);
  }

  static String normalizeBacktickCodeSpacing(String value) {
    StringBuilder result = new StringBuilder(value.length());
    boolean inCode = false;
    int segmentStart = 0;
    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) != '`') continue;
      String segment = value.substring(segmentStart, index);
      result.append(inCode ? normalizeInlineCode(segment) : segment).append('`');
      inCode = !inCode;
      segmentStart = index + 1;
    }
    String tail = value.substring(segmentStart);
    result.append(inCode ? normalizeInlineCode(tail) : tail);
    return result.toString();
  }

  static String normalizeInlineCode(String value) {
    return value
        .replaceAll("(?i)--prefix\\s+\\.\\s+run", "--prefix frontend run")
        // A dot that starts a shell path is an argument boundary, not member access. The former
        // expression allowed optional whitespace on both sides and therefore corrupted valid
        // commands such as `python -m venv .venv` and `source .venv/bin/activate` into
        // `venv.venv` / `source.venv`. Repair spaced member access, but preserve whitespace when
        // the dot is immediately followed by an identifier (the shell hidden-path shape).
        .replaceAll("(?<=[A-Za-z0-9_])(?:\\.\\s+|\\s+\\.\\s+)(?=[A-Za-z0-9_])", ".")
        .replaceAll("\\s*/\\s*", "/")
        // Slash-spacing cleanup above also compacts backticked HTTP operations such as
        // `GET /health` into `GET/health`. Restore the required method/path boundary for every
        // absolute route, not only `/api/...`; otherwise the exact-route gate loses root-level
        // operations and rejects a valid SINGLE_SHOT plan before any worker can run.
        .replaceAll("(?i)\\b(GET|POST|PUT|PATCH|DELETE)/", "$1 /");
  }

  static String rewriteFrontendMoneyParsing(String value) {
    return safe(value)
        .replaceAll(
            "(?i)decimal input,?\\s*convert to integer minor units before POST\\s*[—-]\\s*multiply"
                + " by 100,?\\s*round",
            "decimal string input; validate and split whole/fractional digits into integer minor"
                + " units before POST without floating-point multiplication or rounding")
        .replaceAll(
            "(?i)use integer minor units for prices \\(multiply decimal input by 100, round to"
                + " integer before POST\\)",
            "Use integer minor units for prices by parsing validated decimal strings into"
                + " whole/fractional digits; never multiply or round floating-point money");
  }

  static String rewriteSynchronousDatabaseContract(String value) {
    return safe(value)
        .replace("sqlite+aiosqlite", "sqlite+pysqlite")
        .replace("create_async_engine", "create_engine")
        .replace("async_sessionmaker", "sessionmaker")
        .replace("AsyncSession", "Session")
        .replace("AsyncEngine", "Engine")
        .replaceAll("(?i)async\\s+get_db", "synchronous get_db")
        .replaceAll("(?i)async\\s+database session", "synchronous database session");
  }

  static String bindAsyncDatabaseAlternatives(String value) {
    return safe(value)
        .replaceAll(
            "(?i)AsyncSession\\s*\\(or\\s+(?:a\\s+)?sync(?:hronous)?\\s+Session[^)]*\\)",
            "AsyncSession")
        .replaceAll(
            "(?i)AsyncSession\\s+or\\s+(?:a\\s+)?sync(?:hronous)?\\s+Session(?:\\s+matching"
                + " [^,.;\\n]+)?",
            "AsyncSession");
  }

  static String rewriteBuildAndTestInstallation(String value) {
    return safe(value)
        .replaceAll(
            "(?is)(`?run[_-]tests\\.sh`?[^.\\n"
                + "]{0,500}?)(?:creates? (?:a )?(?:python )?venv|python -m venv)[^.\\n"
                + "]*[.\\n"
                + "]",
            "$1uses the already-installed backend and frontend dependencies. ")
        .replaceAll(
            "(?is)(`?(?:build|test|run[_-]tests)\\.sh`?[^.\\n"
                + "]{0,500}?)(?:pip install|npm ci|npm install)[^.\\n"
                + "]*[.\\n"
                + "]",
            "$1uses dependencies installed by a separate setup command. ")
        .replaceAll(
            "(?i)build\\.sh installs backend dependencies with pip install and frontend"
                + " dependencies with npm ci\\.?",
            "build.sh compiles/import-checks the backend and type-checks/builds the frontend using"
                + " dependencies installed by a separate setup step.")
        .replace(
            "`build` (install backend deps via pip, install frontend deps via npm ci)",
            "`install` (explicit backend/frontend dependency setup), `build` (backend"
                + " compile/import checks plus frontend type-check and production build without"
                + " dependency installation)")
        .replaceAll(
            "(?i)build \\(install \\+ frontend build\\)",
            "build (backend compile/import checks plus frontend build, without dependency"
                + " installation)")
        .replace(
            "Ensure `make build` covers: Python venv creation, pip install, Alembic migrations,"
                + " frontend `npm ci` and `npm run build`.",
            "Ensure `make install` covers Python environment creation plus pip/npm installation;"
                + " `make build` validates migrations and runs the backend checks plus `npm run"
                + " build` without installing dependencies.")
        .replace(
            "`make build` runs type-checking, linting, and test",
            "`make build` runs backend compile/import checks and frontend type-check plus"
                + " production build without installing dependencies")
        .replace(
            "- Install backend dependencies (pip install -r requirements.txt from T1 scaffold).",
            "- Verify the already-installed backend dependencies and compile/import the backend;")
        .replace(
            "- Install frontend dependencies (npm ci from frontend/).",
            "- Type-check and build the frontend using already-installed dependencies.")
        .replace(
            "- Setup: clone, `./build.sh`.",
            "- Setup: clone, install backend/frontend dependencies with explicit setup commands,"
                + " then run `./build.sh`.");
  }

  static String rewriteIdempotencyHeaderTransport(String value) {
    return safe(value)
        .replaceAll(
            "(?i)`?OrderCreate`?:?\\s*idempotency_key \\(str, required\\),\\s*lines",
            "`OrderCreate`: lines")
        .replaceAll(
            "(?i)(POST /orders[^\\n]*accepts an `?Idempotency-Key`? header) \\(optional\\)",
            "$1 (required)")
        .replaceAll(
            "(?i)create_order\\(db, order_data, idempotency_key=None\\)",
            "create_order(db, order_data, idempotency_key)")
        .replaceAll(
            "(?i)optional\\s+`?idempotencyKey\\s*:\\s*string`?\\s+parameter",
            "required `idempotencyKey: string` parameter")
        .replaceAll("(?i)\\bidempotencyKey\\?\\s*:\\s*string\\b", "idempotencyKey: string")
        .replace("affected-row verificationor", "affected-row verification or");
  }

  static boolean makesIdempotencyHeaderOptional(String text) {
    for (String clause : safe(text).toLowerCase(Locale.ROOT).split("[.\\n;]+")) {
      boolean mentionsHeader =
          clause.contains("idempotency-key")
              || clause.contains("idempotency_key")
              || clause.contains("idempotencykey");
      if (mentionsHeader
          && (clause.contains("optional") || clause.matches("(?s).*\\bif provided\\b.*"))) {
        return true;
      }
    }
    return false;
  }

  static String removeBusinessAggregateIdempotencyFields(String value) {
    return safe(value)
        .replaceAll(
            "(?i)(\\*\\*Order\\*\\*:[^.\\n]{0,500}?),\\s*idempotency_key" + "\\s*\\([^)]*\\)", "$1")
        .replaceAll(
            "(?i)(\\*\\*Order\\*\\*:[^.\\n]{0,500}?),\\s*principal\\s*\\(string\\)",
            "$1, principal_id (string, indexed)")
        .replaceAll(
            "(?i)(Order(?: table)?[^\\n]{0,500}?)[,;]\\s*`?idempotency_key`?" + "\\s*\\([^)]*\\)",
            "$1")
        .replaceAll(
            "(?i)(Order(?: table)?[^\\n]{0,500}?)[,;]\\s*`?idempotency_principal`?"
                + "\\s*\\([^)]*\\)",
            "$1")
        .replaceAll(
            "(?i)\\s*Unique constraint on \\(`?idempotency_key`?,"
                + "\\s*`?idempotency_principal`?\\) where both are non-null\\.?",
            "");
  }

  static String rewriteMultiWarehouseReservationMutation(String value) {
    return safe(value)
        .replaceAll(
            "(?i)UPDATE inventories SET reserved = reserved \\+ :qty WHERE sku = :sku AND"
                + " \\(on_hand - reserved\\) >= :qty",
            "UPDATE inventories SET reserved = reserved + :qty WHERE id = :inventory_id AND"
                + " (on_hand - reserved) >= :qty")
        .replaceAll(
            "(?i)WHERE sku = :sku AND \\(on_hand - reserved\\) >= :qty",
            "WHERE id = :inventory_id AND (on_hand - reserved) >= :qty");
  }

  static String normalizeBackendApiBasePath(String value) {
    String normalized =
        safe(value)
            .replaceAll(
                "(?i)(\\b(?:GET|POST|PUT|PATCH|DELETE)\\s*`?)/(?!api/)"
                    + "(warehouses|inventory|stock|adjustments|transfers|orders|reservations)(?=[/{?`\\s,.)—-])",
                "$1/api/$2")
            .replaceAll(
                "(?i)(\\bprefix\\s+`?)/(?!api/)"
                    + "(warehouses|inventory|stock|adjustments|transfers|orders|reservations)(?=[/{?`\\s,.)—-])",
                "$1/api/$2");
    return normalizeTechnicalSpacing(normalized);
  }

  static String rewriteLegacyIdempotencyRecordSchema(String value) {
    return safe(value)
        .replaceAll(
            "(?i)(state\\s*\\([^)]*?)(?:PENDING\\s*,\\s*PENDING|PENDING\\s*\\|\\s*PENDING)([^)]*\\))",
            "$1PROCESSING, COMPLETED$2")
        .replaceAll(
            "(?i)\\*\\*Idempotency middleware/flow\\*\\*", "**Idempotency endpoint/service flow**")
        .replaceAll(
            "(?i)(\\*\\*Idempotency endpoint/service flow\\*\\*)"
                + "\\s*\\(`[^`]*?/middleware/idempotency\\.py`\\)",
            "$1")
        .replaceAll(
            "(?i)If PENDING and request_body_hash matches",
            "If COMPLETED and request_body_hash matches")
        .replaceAll(
            "(?is)- \\*\\*IdempotencyRecord\\*\\*:[\\s\\S]*?(?=\\n\\n\\*\\*Migration)",
            "- **IdempotencyRecord**: `id` (PK), `key`, `scope`, `request_body_hash`, `state`"
                + " (`PROCESSING` or `COMPLETED`), nullable `response_status`, nullable"
                + " `response_body`, and timestamps. Enforce `UniqueConstraint(key, scope)`.")
        .replaceAll(
            "(?i)`IdempotencyRecord`?: id \\(int PK\\), key \\(str, unique, indexed\\),"
                + " response_body \\(str, nullable[^)]*\\), status_code \\(int, nullable\\),"
                + " created_at \\(datetime\\)\\.?",
            "`IdempotencyRecord`: id (int PK), key, scope, request_body_hash, state"
                + " (PROCESSING|COMPLETED), nullable response_status, nullable response_body, and"
                + " created_at; UniqueConstraint(key, scope).");
  }

  static String rewritePydanticSettingsDependency(String value) {
    if (value == null
        || !value.toLowerCase(Locale.ROOT).contains("basesettings")
        || value.toLowerCase(Locale.ROOT).contains("pydantic-settings")) {
      return safe(value);
    }
    return value
        .replace("pydantic, python-dotenv", "pydantic, pydantic-settings, python-dotenv")
        .replace("Pydantic `BaseSettings`", "`pydantic-settings` `BaseSettings`")
        .replace("Pydantic BaseSettings", "`pydantic-settings` BaseSettings");
  }

  static String rewriteReversibleMigrationLanguage(String value) {
    String normalized =
        safe(value)
            .replaceAll(
                "(?i)reversible\\s*\\(\\s*no downgrade\\s*\\)",
                "reversible with a reverse-order downgrade");
    if (!normalized.toLowerCase(Locale.ROOT).contains("forward-only")
        && !normalized.toLowerCase(Locale.ROOT).contains("forward-migration")) {
      return normalized;
    }
    return normalized
        .replaceAll(
            "(?i)forward-migration \\(upgrade\\) only",
            "reversible migration whose upgrade creates the schema and downgrade drops it")
        .replaceAll(
            "(?i)forward-only \\(upgrade creates tables; downgrade drops them\\)",
            "reversible (upgrade creates tables; downgrade drops them)");
  }

  static String rewriteSameOrderReservationRace(String value) {
    return safe(value)
        .replaceAll(
            "(?is)(same-order concurrent reserve[^\\n"
                + "]{0,500}?same `?Idempotency-Key`?[^\\n"
                + "]{0,300}?)one succeeds \\(200\\), the other[^.\\n"
                + "]{0,220}INVALID_STATE[^.\\n"
                + "]*\\.?",
            "$1both return the original 200 status/body, the blocked request includes"
                + " X-Idempotency-Replay: true, and stock changes exactly once. A separate"
                + " different-key same-order race asserts one 200 and one 409 INVALID_STATE.")
        .replaceAll(
            "(?i)(two concurrent reservations? for the same order[^.\\n]{0,240})"
                + "(?:one gets?|the loser gets?)\\s+INSUFFICIENT_STOCK",
            "$1the loser gets INVALID_STATE after observing the committed RESERVED order")
        .replaceAll(
            "(?i)(same order[^.\\n]{0,180})one gets 409 INSUFFICIENT_STOCK",
            "$1one gets 409 INVALID_STATE");
  }
}
