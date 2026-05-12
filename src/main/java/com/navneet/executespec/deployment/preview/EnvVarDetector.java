package com.navneet.executespec.deployment.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Scans a workspace directory for required environment variables. Detects env vars from
 * .env.example files, source code patterns, and framework configuration files.
 */
@Component
@Slf4j
public class EnvVarDetector {

  /** Detected environment variable with classification metadata. */
  public record DetectedEnvVar(
      String name, // e.g., "STRIPE_SECRET_KEY"
      EnvVarCategory category, // SECRET, DATABASE, STANDARD
      String source, // e.g., ".env.example", "src/config.ts"
      String defaultValue // nullable — from .env.example or ${VAR:default}
      ) {}

  public enum EnvVarCategory {
    SECRET, // API keys, tokens, passwords
    DATABASE, // DB connection vars (auto-injected, excluded from user prompt)
    STANDARD // Non-secret config vars
  }

  // ── Exclusion: vars auto-handled by runtime, compose, or internal wiring ──
  // These are NEVER shown to the user. The preview system auto-configures them.
  private static final Set<String> EXCLUDED_VARS =
      Set.of(
          // Runtime / OS
          "PORT",
          "HOST",
          "HOSTNAME",
          "NODE_ENV",
          "JAVA_HOME",
          "PATH",
          "HOME",
          "USER",
          "LANG",
          "TERM",
          "SHELL",
          "PWD",
          "TMPDIR",
          "TZ",
          "LC_ALL",
          "LC_CTYPE",
          // Database (auto-injected by ComposeFileGenerator)
          "DATABASE_URL",
          "DB_HOST",
          "DB_PORT",
          "DB_USER",
          "DB_PASSWORD",
          "DB_NAME",
          "DB_URL",
          "DB_CONNECTION",
          "DB_DATABASE",
          "DB_USERNAME",
          "PGHOST",
          "PGPORT",
          "PGUSER",
          "PGPASSWORD",
          "PGDATABASE",
          "SPRING_DATASOURCE_URL",
          "SPRING_DATASOURCE_USERNAME",
          "SPRING_DATASOURCE_PASSWORD",
          "SPRING_DATASOURCE_DRIVER_CLASS_NAME",
          "SPRING_JPA_DATABASE_PLATFORM",
          "SPRING_JPA_HIBERNATE_DDL_AUTO",
          "SPRING_JPA_SHOW_SQL",
          "SPRING_DATA_MONGODB_URI",
          "SPRING_DATA_REDIS_HOST",
          "SPRING_DATA_REDIS_PORT",
          "MONGO_URI",
          "MONGODB_URI",
          "MONGO_URL",
          "MONGO_HOST",
          "MONGO_PORT",
          "REDIS_URL",
          "REDIS_HOST",
          "REDIS_PORT",
          "MYSQL_ROOT_PASSWORD",
          "MYSQL_USER",
          "MYSQL_PASSWORD",
          "MYSQL_DATABASE",
          "POSTGRES_USER",
          "POSTGRES_PASSWORD",
          "POSTGRES_DB",
          // NPM / build tools
          "npm_config_cache",
          "npm_lifecycle_event",
          "NPM_CONFIG_LOGLEVEL",
          "CI",
          "GENERATE_SOURCEMAP",
          "TSC_COMPILE_ON_ERROR",
          "ESLINT_NO_DEV_ERRORS",
          // Internal networking (user never needs to configure these — we control both containers)
          "BACKEND_URL",
          "BACKEND_HOST",
          "BACKEND_PORT",
          "BACKEND_API_URL",
          "API_URL",
          "API_HOST",
          "API_PORT",
          "API_BASE_URL",
          "API_ENDPOINT",
          "SERVER_URL",
          "SERVER_HOST",
          "SERVER_PORT",
          "SERVER_ADDRESS",
          "FRONTEND_URL",
          "FRONTEND_HOST",
          "FRONTEND_PORT",
          "BASE_URL",
          "APP_URL",
          "APP_HOST",
          "APP_PORT",
          "APP_NAME",
          "NEXT_PUBLIC_API_URL",
          "NEXT_PUBLIC_BACKEND_URL",
          "NEXT_PUBLIC_BASE_URL",
          "NEXT_PUBLIC_APP_URL",
          "NEXT_PUBLIC_SERVER_URL",
          "NEXT_PUBLIC_WS_URL",
          "REACT_APP_API_URL",
          "REACT_APP_BACKEND_URL",
          "REACT_APP_BASE_URL",
          "REACT_APP_SERVER_URL",
          "REACT_APP_WS_URL",
          "VITE_API_URL",
          "VITE_BACKEND_URL",
          "VITE_BASE_URL",
          "VITE_APP_URL",
          "VITE_SERVER_URL",
          "VITE_WS_URL",
          "VITE_API_BASE_URL",
          "PUBLIC_URL",
          "WEBSITE_URL",
          "SITE_URL",
          // CORS (auto-configured — both services are in same compose network)
          "CORS_ORIGIN",
          "CORS_ORIGINS",
          "CORS_ALLOWED_ORIGINS",
          "ALLOWED_ORIGINS",
          "CORS_ALLOW_ORIGIN",
          "CORS_WHITELIST",
          "CORS_ENABLED",
          "ACCESS_CONTROL_ALLOW_ORIGIN",
          // Session / cookie / internal auth (not third-party)
          "SESSION_SECRET",
          "COOKIE_SECRET",
          "COOKIE_DOMAIN",
          "JWT_SECRET",
          "JWT_EXPIRATION",
          "JWT_EXPIRES_IN",
          "NEXTAUTH_URL",
          "NEXTAUTH_SECRET",
          // Logging / debug
          "LOG_LEVEL",
          "DEBUG",
          "VERBOSE",
          "NODE_DEBUG",
          "SPRING_PROFILES_ACTIVE",
          "SPRING_OUTPUT_ANSI_ENABLED",
          // Docker / runtime internals
          "DOCKER_HOST",
          "COMPOSE_PROJECT_NAME");

  // ── Exclusion patterns: substrings that mark a var as internal config ──
  // If a var name contains any of these, it's internal wiring and should be auto-populated
  private static final Set<String> EXCLUDED_SUBSTRINGS =
      Set.of(
          "_URL",
          "_HOST",
          "_PORT",
          "_ORIGIN",
          "_ORIGINS",
          "_ENDPOINT",
          "_BASE_URL",
          "_API_URL",
          "_BACKEND",
          "_FRONTEND",
          "_SERVER",
          "_ADDRESS",
          "_DOMAIN",
          "_SCHEME",
          "_PROTOCOL",
          "CORS_",
          "PROXY_",
          "_TIMEOUT",
          "_INTERVAL",
          "_RETRY",
          "_RETRIES",
          "_MAX_",
          "_MIN_",
          "_LIMIT",
          "_SIZE",
          "_LENGTH",
          "_COUNT",
          "_ENABLED",
          "_DISABLED",
          "_ACTIVE",
          "_MODE",
          "_DIR",
          "_PATH",
          "_FILE",
          "_FOLDER",
          "_LOG",
          "_DEBUG",
          "_VERBOSE");

  /**
   * Known third-party service prefixes. Vars starting with these ARE shown to the user (they're
   * external API keys).
   */
  private static final Set<String> THIRD_PARTY_PREFIXES =
      Set.of(
          "STRIPE_",
          "OPENAI_",
          "ANTHROPIC_",
          "GOOGLE_",
          "FIREBASE_",
          "AWS_",
          "AZURE_",
          "GCP_",
          "CLOUDFLARE_",
          "TWILIO_",
          "SENDGRID_",
          "MAILGUN_",
          "POSTMARK_",
          "GITHUB_",
          "GITLAB_",
          "BITBUCKET_",
          "SLACK_",
          "DISCORD_",
          "TELEGRAM_",
          "SENTRY_",
          "DATADOG_",
          "NEWRELIC_",
          "LOGROCKET_",
          "ALGOLIA_",
          "ELASTICSEARCH_",
          "MEILISEARCH_",
          "SUPABASE_",
          "NEON_",
          "PLANETSCALE_",
          "UPSTASH_",
          "VERCEL_",
          "NETLIFY_",
          "HEROKU_",
          "RAILWAY_",
          "PUSHER_",
          "ABLY_",
          "PUBNUB_",
          "MAPBOX_",
          "GOOGLEMAPS_",
          "HERE_",
          "PLAID_",
          "RAZORPAY_",
          "PAYPAL_",
          "BRAINTREE_",
          "AUTH0_",
          "OKTA_",
          "CLERK_",
          "SUPERTOKENS_",
          "RESEND_",
          "LOOPS_",
          "PINECONE_",
          "WEAVIATE_",
          "QDRANT_",
          "CHROMA_",
          "REPLICATE_",
          "HUGGINGFACE_",
          "COHERE_",
          "MISTRAL_",
          "S3_",
          "R2_",
          "SPACES_",
          "MINIO_",
          "REDIS_CLOUD_",
          "MONGODB_ATLAS_");

  // Keywords that indicate a third-party secret/API key
  private static final Set<String> SECRET_KEYWORDS =
      Set.of(
          "API_KEY",
          "APIKEY",
          "API_SECRET",
          "SECRET_KEY",
          "PRIVATE_KEY",
          "ACCESS_KEY",
          "CLIENT_SECRET",
          "CLIENT_ID",
          "WEBHOOK_SECRET",
          "SIGNING_SECRET",
          "PUBLISHABLE_KEY",
          "LIVE_KEY",
          "TEST_KEY");

  // Patterns for extracting env vars from source code
  private static final Pattern PROCESS_ENV =
      Pattern.compile("process\\.env\\.([A-Z_][A-Z0-9_]{2,})");
  private static final Pattern PROCESS_ENV_BRACKET =
      Pattern.compile("process\\.env\\[['\"]([A-Z_][A-Z0-9_]{2,})['\"]\\]");
  private static final Pattern PYTHON_ENVIRON =
      Pattern.compile("os\\.environ\\[['\"]([A-Z_][A-Z0-9_]{2,})['\"]\\]");
  private static final Pattern PYTHON_GETENV =
      Pattern.compile("os\\.getenv\\(['\"]([A-Z_][A-Z0-9_]{2,})['\"]");
  private static final Pattern JAVA_GETENV =
      Pattern.compile("System\\.getenv\\(['\"]([A-Z_][A-Z0-9_]{2,})['\"]\\)");
  private static final Pattern SPRING_PLACEHOLDER =
      Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]{2,})(?::([^}]*))?\\}");
  private static final Pattern ENV_FILE_LINE =
      Pattern.compile("^([A-Z_][A-Z0-9_]{2,})=(.*)$", Pattern.MULTILINE);

  /**
   * Scan workspace for required environment variables. Excludes database-related and standard
   * runtime vars.
   *
   * @param workspacePath path to workspace/{runId}
   * @return list of detected env vars, sorted by category (SECRET first) then name
   */
  public List<DetectedEnvVar> detect(Path workspacePath) {
    if (workspacePath == null || !Files.isDirectory(workspacePath)) {
      return List.of();
    }

    // LinkedHashMap to preserve first-seen source for each var
    Map<String, DetectedEnvVar> result = new LinkedHashMap<>();

    // 1. Check .env.example / .env.sample / .env.template
    for (String envFile :
        List.of(".env.example", ".env.sample", ".env.template", ".env.local.example")) {
      scanEnvFile(workspacePath.resolve(envFile), result);
      // Also check in subdirectories
      for (String subDir : List.of("backend", "server", "api", "frontend", "client")) {
        scanEnvFile(workspacePath.resolve(subDir).resolve(envFile), result);
      }
    }

    // 2. Check Spring application config
    scanSpringConfig(workspacePath, result);

    // 3. Scan source files (limited depth to avoid node_modules, etc.)
    scanSourceFiles(workspacePath, result);

    // Sort: SECRET first, then STANDARD, alphabetical within each
    var sorted = new ArrayList<>(result.values());
    sorted.sort(
        Comparator.comparing((DetectedEnvVar v) -> v.category() == EnvVarCategory.SECRET ? 0 : 1)
            .thenComparing(DetectedEnvVar::name));

    log.info(
        "Detected {} environment variables in workspace (excluding {} DB/system vars)",
        sorted.size(),
        EXCLUDED_VARS.size());
    return sorted;
  }

  // ── .env file scanning ───────────────────────────────────────────────────

  private void scanEnvFile(Path envFile, Map<String, DetectedEnvVar> result) {
    if (!Files.isRegularFile(envFile)) return;
    String content = readSafe(envFile);
    if (content == null) return;

    String source = envFile.getFileName().toString();
    Matcher matcher = ENV_FILE_LINE.matcher(content);
    while (matcher.find()) {
      String name = matcher.group(1);
      String value = matcher.group(2).trim();
      if (isExcluded(name)) continue;

      // If value is empty or a placeholder, it's required
      String defaultValue = (value.isEmpty() || isPlaceholder(value)) ? null : value;
      addVar(result, name, classifyVar(name), source, defaultValue);
    }
  }

  // ── Spring config scanning ───────────────────────────────────────────────

  private void scanSpringConfig(Path workspace, Map<String, DetectedEnvVar> result) {
    for (String configFile :
        List.of(
            "src/main/resources/application.yml",
            "src/main/resources/application.yaml",
            "src/main/resources/application.properties")) {
      Path file = workspace.resolve(configFile);
      if (!Files.isRegularFile(file)) continue;
      String content = readSafe(file);
      if (content == null) continue;

      Matcher matcher = SPRING_PLACEHOLDER.matcher(content);
      while (matcher.find()) {
        String name = matcher.group(1);
        String defaultValue = matcher.group(2); // may be null
        if (isExcluded(name)) continue;
        // If it has a default, it's optional
        addVar(result, name, classifyVar(name), "application.yml", defaultValue);
      }
    }
  }

  // ── Source file scanning ─────────────────────────────────────────────────

  private void scanSourceFiles(Path workspace, Map<String, DetectedEnvVar> result) {
    // Do NOT follow symlinks — symlink loops (e.g. node_modules/.bin/->../bin/) hang the scan
    // indefinitely. Customer workspaces don't legitimately need link-following for env-var detect.
    // Audit: 2026-05-12 line-by-line review item N #73.
    try (Stream<Path> walk = Files.walk(workspace, 4)) {
      walk.filter(Files::isRegularFile)
          .filter(this::isSourceFile)
          .filter(p -> !isInIgnoredDir(workspace, p))
          .forEach(file -> scanSingleSourceFile(file, workspace, result));
    } catch (IOException e) {
      log.debug("Failed to walk workspace for env var detection: {}", e.getMessage());
    }
  }

  private void scanSingleSourceFile(Path file, Path workspace, Map<String, DetectedEnvVar> result) {
    String content = readSafe(file);
    if (content == null) return;

    String relativePath = workspace.relativize(file).toString();

    // JavaScript/TypeScript: process.env.XXX
    extractAll(PROCESS_ENV, content, relativePath, result);
    extractAll(PROCESS_ENV_BRACKET, content, relativePath, result);

    // Python: os.environ / os.getenv
    extractAll(PYTHON_ENVIRON, content, relativePath, result);
    extractAll(PYTHON_GETENV, content, relativePath, result);

    // Java: System.getenv
    extractAll(JAVA_GETENV, content, relativePath, result);
  }

  private void extractAll(
      Pattern pattern, String content, String source, Map<String, DetectedEnvVar> result) {
    Matcher matcher = pattern.matcher(content);
    while (matcher.find()) {
      String name = matcher.group(1);
      if (!isExcluded(name)) {
        addVar(result, name, classifyVar(name), source, null);
      }
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private void addVar(
      Map<String, DetectedEnvVar> result,
      String name,
      EnvVarCategory category,
      String source,
      String defaultValue) {
    // Only add if not already present (first source wins)
    result.putIfAbsent(name, new DetectedEnvVar(name, category, source, defaultValue));
  }

  private EnvVarCategory classifyVar(String name) {
    String upper = name.toUpperCase();
    // Check for known secret/API key patterns
    for (String keyword : SECRET_KEYWORDS) {
      if (upper.contains(keyword)) return EnvVarCategory.SECRET;
    }
    // Check for known third-party service prefix → treat as SECRET
    for (String prefix : THIRD_PARTY_PREFIXES) {
      if (upper.startsWith(prefix)) return EnvVarCategory.SECRET;
    }
    return EnvVarCategory.STANDARD;
  }

  /**
   * Determines if a var should be excluded from user prompting. Strategy: ONLY show third-party API
   * keys/secrets. All internal config (URLs, ports, CORS, paths, timeouts, etc.) is auto-populated.
   */
  private boolean isExcluded(String name) {
    if (name.length() < 3) return true;
    String upper = name.toUpperCase();

    // Exact match exclusion
    if (EXCLUDED_VARS.contains(upper) || EXCLUDED_VARS.contains(name)) return true;

    // Substring-based exclusion for internal infrastructure patterns
    for (String sub : EXCLUDED_SUBSTRINGS) {
      if (upper.contains(sub)) {
        // Exception: if it's a known third-party service, don't exclude
        // e.g., STRIPE_SECRET_KEY contains _SECRET but is third-party
        if (isThirdParty(upper)) return false;
        return true;
      }
    }

    return false;
  }

  /**
   * Check if a var name belongs to a known third-party service. These should always be shown to the
   * user.
   */
  private boolean isThirdParty(String upperName) {
    for (String prefix : THIRD_PARTY_PREFIXES) {
      if (upperName.startsWith(prefix)) return true;
    }
    // Also check for common third-party key patterns
    for (String keyword : SECRET_KEYWORDS) {
      if (upperName.contains(keyword)) return true;
    }
    return false;
  }

  private boolean isPlaceholder(String value) {
    String lower = value.toLowerCase();
    return lower.startsWith("your_")
        || lower.startsWith("xxx")
        || lower.startsWith("<")
        || lower.equals("changeme")
        || lower.equals("todo")
        || lower.equals("replace_me")
        || lower.startsWith("${");
  }

  private boolean isSourceFile(Path path) {
    String name = path.getFileName().toString().toLowerCase();
    return name.endsWith(".ts")
        || name.endsWith(".tsx")
        || name.endsWith(".js")
        || name.endsWith(".jsx")
        || name.endsWith(".py")
        || name.endsWith(".java")
        || name.endsWith(".yml")
        || name.endsWith(".yaml")
        || name.endsWith(".properties")
        || name.endsWith(".env")
        || name.endsWith(".env.example");
  }

  private boolean isInIgnoredDir(Path workspace, Path file) {
    String relative = workspace.relativize(file).toString();
    return relative.contains("node_modules")
        || relative.contains(".git/")
        || relative.contains("target/")
        || relative.contains("build/")
        || relative.contains("dist/")
        || relative.contains("__pycache__")
        || relative.contains(".next/")
        || relative.contains(".nuxt/")
        || relative.contains("vendor/")
        || relative.contains(".gradle/");
  }

  private String readSafe(Path file) {
    if (file == null || !Files.isRegularFile(file)) return null;
    try {
      String content = Files.readString(file);
      return content.length() > 100_000 ? content.substring(0, 100_000) : content;
    } catch (IOException e) {
      log.debug("Failed to read {}: {}", file, e.getMessage());
      return null;
    }
  }
}
