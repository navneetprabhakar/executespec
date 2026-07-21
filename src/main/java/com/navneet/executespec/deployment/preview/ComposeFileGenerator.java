package com.navneet.executespec.deployment.preview;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Generates docker-compose.yml for preview containers. Supports optional database service with
 * healthcheck-based readiness, ephemeral storage (tmpfs), and auto-injected connection strings.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ComposeFileGenerator {

  private final PreviewProperties props;

  /** Lightweight container for resolved CPU/memory limits. */
  private record ContainerResources(String cpu, String memory) {}

  /**
   * Generate a docker-compose.yml in the workspace directory.
   *
   * @param workspacePath workspace/{runId} directory (must already contain a Dockerfile)
   * @param projectType detected project type (for container port resolution)
   * @param databaseType detected database (nullable — no DB service if null)
   * @param envVars user-provided environment variables (may be empty)
   * @param hostPort allocated host port for the app service
   * @return path to the generated docker-compose.yml
   */
  public Path generate(
      Path workspacePath,
      PreviewProjectType projectType,
      PreviewMode previewMode,
      DatabaseType databaseType,
      Map<String, String> envVars,
      int hostPort) {

    // CLI (ttyd) and MCP (Inspector) always bind to 8080; web apps use their native port
    int containerPort =
        (previewMode == PreviewMode.REST || previewMode == PreviewMode.FULL_STACK)
            ? projectType.getContainerPort()
            : 8080;

    ContainerResources resources = calculateContainerResources(previewMode, projectType);

    // Merge user-provided env vars with auto-injected DB vars
    Map<String, String> allEnvVars = new LinkedHashMap<>();
    if (databaseType != null) {
      allEnvVars.putAll(buildDbConnectionVars(databaseType, projectType));
    }
    // User-provided vars override auto-injected ones — after validation (RR-156)
    if (envVars != null) {
      envVars.forEach(
          (k, v) -> {
            validateEnvName(k);
            validateEnvValue(k, v);
            allEnvVars.put(k, v);
          });
    }

    var sb = new StringBuilder();
    sb.append("services:\n");

    sb.append("  app:\n");
    sb.append("    build:\n");
    sb.append("      context: .\n");
    sb.append("      dockerfile: Dockerfile\n");
    sb.append("    ports:\n");
    // Use ${APP_PORT} env var with fallback so VM deploy script can override the host port
    // (deploy-preview.sh writes APP_PORT=<dynamic> to .env for port isolation)
    sb.append("      - \"${APP_PORT:-")
        .append(hostPort)
        .append("}:")
        .append(containerPort)
        .append("\"\n");
    sb.append("    deploy:\n");
    sb.append("      resources:\n");
    sb.append("        limits:\n");
    sb.append("          cpus: \"").append(resources.cpu()).append("\"\n");
    sb.append("          memory: ").append(resources.memory()).append("\n");

    if (!allEnvVars.isEmpty()) {
      sb.append("    environment:\n");
      for (var entry : allEnvVars.entrySet()) {
        // Escape values that contain special YAML characters
        sb.append("      ")
            .append(entry.getKey())
            .append(": ")
            .append(yamlQuote(entry.getValue()))
            .append("\n");
      }
    }

    if (databaseType != null) {
      sb.append("    depends_on:\n");
      sb.append("      db:\n");
      sb.append("        condition: service_healthy\n");
    }

    sb.append("    networks:\n");
    sb.append("      - preview-net\n");
    sb.append("    restart: \"no\"\n");
    // Security hardening — drop all capabilities, prevent privilege escalation
    sb.append("    security_opt:\n");
    sb.append("      - no-new-privileges:true\n");
    sb.append("    cap_drop:\n");
    sb.append("      - ALL\n");
    // Block GCP/AWS/Azure metadata endpoints by redirecting to localhost
    sb.append("    extra_hosts:\n");
    sb.append("      - \"metadata.google.internal:127.0.0.1\"\n");
    sb.append("      - \"169.254.169.254:127.0.0.1\"\n");
    sb.append("\n");

    // Database service (optional)
    if (databaseType != null) {
      sb.append(buildDatabaseConfig(databaseType));
    }

    // NOTE: Do NOT use "internal: true" here — it blocks host-to-container port forwarding
    // via published ports on many Docker Engine versions (iptables DNAT rules not created).
    // Sandbox isolation is provided by Docker's default network namespace + container security_opt.
    sb.append("networks:\n");
    sb.append("  preview-net:\n");
    sb.append("    driver: bridge\n");

    String compose = sb.toString();
    // RR-156: the generated document is executed by the host Docker daemon, so before it is
    // written it must be parsed back and shown to contain only the services and directives this
    // generator intended. If any user-controlled value had escaped its scalar and introduced a
    // top-level key or a privileged directive, that shows up here and the preview fails closed.
    assertNoInjectedDirectives(compose);

    Path composeFile = workspacePath.resolve("docker-compose.yml");
    // RR-476: symlink-safe, containment-checked write — the customer repository controls this
    // directory and could otherwise aim the write at a host file.
    PreviewArtifactWriter.write(workspacePath, "docker-compose.yml", compose);
    log.debug(
        "Generated docker-compose.yml at {} (db={}, envVars={})",
        composeFile,
        databaseType,
        allEnvVars.size());
    return composeFile;
  }

  /** Determine resource limits — CLI/MCP are lightweight, heavy-weight apps get more. */
  private ContainerResources calculateContainerResources(
      PreviewMode previewMode, PreviewProjectType projectType) {
    boolean heavyWeight =
        (previewMode == PreviewMode.REST || previewMode == PreviewMode.FULL_STACK)
            && (projectType == PreviewProjectType.SPRING_BOOT
                || projectType == PreviewProjectType.FULL_STACK);
    String cpu = heavyWeight ? "1.5" : "0.5";
    String mem = heavyWeight ? "2G" : "512M";
    return new ContainerResources(cpu, mem);
  }

  /** Build the YAML fragment for the database service (image, env, tmpfs, healthcheck, network). */
  private String buildDatabaseConfig(DatabaseType databaseType) {
    var sb = new StringBuilder();
    sb.append("  db:\n");
    sb.append("    image: ").append(databaseType.getImage()).append("\n");

    // Environment (credentials)
    Map<String, String> dbEnv = buildDbServiceEnv(databaseType);
    if (!dbEnv.isEmpty()) {
      sb.append("    environment:\n");
      for (var entry : dbEnv.entrySet()) {
        sb.append("      ")
            .append(entry.getKey())
            .append(": ")
            .append(yamlQuote(entry.getValue()))
            .append("\n");
      }
    }

    // Ephemeral storage — data is lost on container stop (fresh each preview)
    sb.append("    tmpfs:\n");
    sb.append("      - ").append(databaseType.getDataDir()).append("\n");

    // Healthcheck
    sb.append("    healthcheck:\n");
    sb.append("      test: [\"CMD-SHELL\", \"")
        .append(escapeYamlString(databaseType.getHealthcheckCmd()))
        .append("\"]\n");
    sb.append("      interval: 2s\n");
    sb.append("      timeout: 5s\n");
    sb.append("      retries: 15\n");
    sb.append("      start_period: 5s\n");

    sb.append("    networks:\n");
    sb.append("      - preview-net\n");
    sb.append("    restart: \"no\"\n");
    // NOTE: Do NOT add security_opt/cap_drop to DB containers — official DB images
    // (postgres, mysql, mongo) need privileges to chown/chmod their data directories
    // on startup. Security hardening is only applied to the app container (user code).
    sb.append("\n");

    return sb.toString();
  }

  /**
   * Build environment variables for the app service to connect to the DB. Auto-detects framework
   * conventions based on project type.
   */
  private Map<String, String> buildDbConnectionVars(
      DatabaseType dbType, PreviewProjectType projectType) {
    var vars = new LinkedHashMap<String, String>();

    switch (dbType) {
      case POSTGRESQL -> {
        String jdbcUrl = "jdbc:postgresql://db:5432/" + dbType.getDbName();
        String genericUrl =
            "postgresql://"
                + dbType.getUser()
                + ":"
                + dbType.getPassword()
                + "@db:5432/"
                + dbType.getDbName();

        if (projectType == PreviewProjectType.SPRING_BOOT) {
          vars.put("SPRING_DATASOURCE_URL", jdbcUrl);
          vars.put("SPRING_DATASOURCE_USERNAME", dbType.getUser());
          vars.put("SPRING_DATASOURCE_PASSWORD", dbType.getPassword());
          // Also set generic URL for ORMs that use it
          vars.put("DATABASE_URL", genericUrl);
        } else {
          vars.put("DATABASE_URL", genericUrl);
          vars.put("DB_HOST", "db");
          vars.put("DB_PORT", "5432");
          vars.put("DB_USER", dbType.getUser());
          vars.put("DB_PASSWORD", dbType.getPassword());
          vars.put("DB_NAME", dbType.getDbName());
          vars.put("PGHOST", "db");
          vars.put("PGPORT", "5432");
          vars.put("PGUSER", dbType.getUser());
          vars.put("PGPASSWORD", dbType.getPassword());
          vars.put("PGDATABASE", dbType.getDbName());
        }
      }
      case MYSQL -> {
        String jdbcUrl = "jdbc:mysql://db:3306/" + dbType.getDbName();
        String genericUrl =
            "mysql://"
                + dbType.getUser()
                + ":"
                + dbType.getPassword()
                + "@db:3306/"
                + dbType.getDbName();

        if (projectType == PreviewProjectType.SPRING_BOOT) {
          vars.put("SPRING_DATASOURCE_URL", jdbcUrl);
          vars.put("SPRING_DATASOURCE_USERNAME", dbType.getUser());
          vars.put("SPRING_DATASOURCE_PASSWORD", dbType.getPassword());
        }
        vars.put("DATABASE_URL", genericUrl);
        vars.put("DB_HOST", "db");
        vars.put("DB_PORT", "3306");
        vars.put("DB_USER", dbType.getUser());
        vars.put("DB_PASSWORD", dbType.getPassword());
        vars.put("DB_NAME", dbType.getDbName());
      }
      case MONGODB -> {
        String mongoUri = "mongodb://db:27017/" + dbType.getDbName();
        vars.put("MONGO_URI", mongoUri);
        vars.put("MONGODB_URI", mongoUri);
        vars.put("MONGO_URL", mongoUri);
        if (projectType == PreviewProjectType.SPRING_BOOT) {
          vars.put("SPRING_DATA_MONGODB_URI", mongoUri);
        }
      }
      case REDIS -> {
        String redisUrl = "redis://db:6379";
        vars.put("REDIS_URL", redisUrl);
        vars.put("REDIS_HOST", "db");
        vars.put("REDIS_PORT", "6379");
        if (projectType == PreviewProjectType.SPRING_BOOT) {
          vars.put("SPRING_DATA_REDIS_HOST", "db");
          vars.put("SPRING_DATA_REDIS_PORT", "6379");
        }
      }
    }

    return vars;
  }

  /** Build environment variables for the database service itself (credentials, db name). */
  private Map<String, String> buildDbServiceEnv(DatabaseType dbType) {
    var env = new LinkedHashMap<String, String>();

    switch (dbType) {
      case POSTGRESQL -> {
        env.put("POSTGRES_USER", dbType.getUser());
        env.put("POSTGRES_PASSWORD", dbType.getPassword());
        env.put("POSTGRES_DB", dbType.getDbName());
      }
      case MYSQL -> {
        env.put("MYSQL_ROOT_PASSWORD", dbType.getPassword());
        env.put("MYSQL_USER", dbType.getUser());
        env.put("MYSQL_PASSWORD", dbType.getPassword());
        env.put("MYSQL_DATABASE", dbType.getDbName());
      }
      case MONGODB -> {
        // Mongo 7+ doesn't need auth for local dev
        env.put("MONGO_INITDB_DATABASE", dbType.getDbName());
      }
      case REDIS -> {
        // Redis Alpine has no auth by default
      }
    }

    return env;
  }

  // ------------------------------------------------------------------ RR-156 injection defence

  /**
   * Environment variable names accepted into a generated Compose file. POSIX identifier shape only
   * — the exact set a shell can export. Anything else (a name containing a newline, a colon, an
   * indentation-breaking space, or YAML structure characters) is a Compose-injection attempt, not a
   * variable name.
   */
  private static final java.util.regex.Pattern ENV_NAME =
      java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

  /**
   * Top-level Compose keys this generator is allowed to emit. Anything else appearing in the parsed
   * document means a value broke out of its scalar.
   */
  private static final java.util.Set<String> ALLOWED_TOP_LEVEL_KEYS =
      java.util.Set.of("services", "networks");

  /** Service-level keys this generator is allowed to emit. */
  private static final java.util.Set<String> ALLOWED_SERVICE_KEYS =
      java.util.Set.of(
          "build",
          "image",
          "ports",
          "deploy",
          "environment",
          "depends_on",
          "networks",
          "restart",
          "security_opt",
          "cap_drop",
          "extra_hosts",
          "tmpfs",
          "healthcheck");

  private void validateEnvName(String name) {
    if (name == null || !ENV_NAME.matcher(name).matches()) {
      // Deliberately does not echo the name: it is attacker-controlled text.
      throw new PreviewException(
          "Invalid preview environment variable name. Names must match [A-Za-z_][A-Za-z0-9_]*.");
    }
  }

  private void validateEnvValue(String name, String value) {
    if (value == null) {
      return;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      // Newlines end a YAML scalar; other C0/C1 controls have no legitimate use in an env value
      // and are the classic vehicle for smuggling additional Compose directives.
      if (c == '\n' || c == '\r' || (c < 0x20 && c != '\t') || c == 0x7f) {
        throw new PreviewException(
            "Preview environment variable "
                + name
                + " contains a control character and was rejected.");
      }
    }
    if (value.length() > 32_768) {
      throw new PreviewException("Preview environment variable " + name + " is too large.");
    }
  }

  /** Parses the generated document and rejects anything this generator did not intend to emit. */
  @SuppressWarnings("unchecked")
  private void assertNoInjectedDirectives(String compose) {
    Object parsed;
    try {
      parsed = com.navneet.executespec.util.SafeYamlLoader.load(compose);
    } catch (RuntimeException e) {
      throw new PreviewException(
          "Generated docker-compose.yml is not valid YAML; refusing to run.");
    }
    if (!(parsed instanceof Map<?, ?> root)) {
      throw new PreviewException("Generated docker-compose.yml is not a mapping; refusing to run.");
    }
    for (Object key : root.keySet()) {
      if (!ALLOWED_TOP_LEVEL_KEYS.contains(String.valueOf(key))) {
        throw new PreviewException(
            "Generated docker-compose.yml contains an unexpected top-level directive; refusing"
                + " to run.");
      }
    }
    Object services = root.get("services");
    if (services instanceof Map<?, ?> serviceMap) {
      for (Object svc : serviceMap.values()) {
        if (!(svc instanceof Map<?, ?> svcDef)) {
          continue;
        }
        for (Object key : svcDef.keySet()) {
          if (!ALLOWED_SERVICE_KEYS.contains(String.valueOf(key))) {
            throw new PreviewException(
                "Generated docker-compose.yml contains an unexpected service directive; refusing"
                    + " to run.");
          }
        }
      }
    }
  }

  /** Quote a YAML value if it contains special characters. */
  private String yamlQuote(String value) {
    if (value == null) return "\"\"";
    // Always double-quote to be safe with special chars
    return "\"" + escapeYamlString(value) + "\"";
  }

  private String escapeYamlString(String value) {
    if (value == null) return "";
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
