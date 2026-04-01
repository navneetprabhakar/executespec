package com.navneet.executespec.deployment.preview;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates docker-compose.yml for preview containers.
 * Supports optional database service with healthcheck-based readiness,
 * ephemeral storage (tmpfs), and auto-injected connection strings.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ComposeFileGenerator {

    private final PreviewProperties props;

    /**
     * Generate a docker-compose.yml in the workspace directory.
     *
     * @param workspacePath workspace/{runId} directory (must already contain a Dockerfile)
     * @param projectType   detected project type (for container port resolution)
     * @param databaseType  detected database (nullable — no DB service if null)
     * @param envVars       user-provided environment variables (may be empty)
     * @param hostPort      allocated host port for the app service
     * @return path to the generated docker-compose.yml
     */
    public Path generate(Path workspacePath, PreviewProjectType projectType, PreviewMode previewMode,
                         DatabaseType databaseType, Map<String, String> envVars, int hostPort) {

        // CLI (ttyd) and MCP (Inspector) always bind to 8080; web apps use their native port
        int containerPort = (previewMode == PreviewMode.REST || previewMode == PreviewMode.FULL_STACK) ? projectType.getContainerPort() : 8080;

        // Determine resource limits — CLI/MCP are lightweight
        boolean heavyWeight = (previewMode == PreviewMode.REST || previewMode == PreviewMode.FULL_STACK)
                && (projectType == PreviewProjectType.SPRING_BOOT
                    || projectType == PreviewProjectType.FULL_STACK);
        String cpu = heavyWeight ? "1.5" : "0.5";
        String mem = heavyWeight ? "2G" : "512M";

        // Merge user-provided env vars with auto-injected DB vars
        Map<String, String> allEnvVars = new LinkedHashMap<>();
        if (databaseType != null) {
            allEnvVars.putAll(buildDbConnectionVars(databaseType, projectType));
        }
        // User-provided vars override auto-injected ones
        if (envVars != null) {
            allEnvVars.putAll(envVars);
        }

        var sb = new StringBuilder();
        sb.append("services:\n");

        // ── App service ──────────────────────────────────────────────────────
        sb.append("  app:\n");
        sb.append("    build:\n");
        sb.append("      context: .\n");
        sb.append("      dockerfile: Dockerfile\n");
        sb.append("    ports:\n");
        sb.append("      - \"").append(hostPort).append(":").append(containerPort).append("\"\n");
        sb.append("    deploy:\n");
        sb.append("      resources:\n");
        sb.append("        limits:\n");
        sb.append("          cpus: \"").append(cpu).append("\"\n");
        sb.append("          memory: ").append(mem).append("\n");

        if (!allEnvVars.isEmpty()) {
            sb.append("    environment:\n");
            for (var entry : allEnvVars.entrySet()) {
                // Escape values that contain special YAML characters
                sb.append("      ").append(entry.getKey()).append(": ")
                        .append(yamlQuote(entry.getValue())).append("\n");
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
        sb.append("\n");

        // ── Database service (optional) ──────────────────────────────────────
        if (databaseType != null) {
            sb.append("  db:\n");
            sb.append("    image: ").append(databaseType.getImage()).append("\n");

            // Environment (credentials)
            Map<String, String> dbEnv = buildDbServiceEnv(databaseType);
            if (!dbEnv.isEmpty()) {
                sb.append("    environment:\n");
                for (var entry : dbEnv.entrySet()) {
                    sb.append("      ").append(entry.getKey()).append(": ")
                            .append(yamlQuote(entry.getValue())).append("\n");
                }
            }

            // Ephemeral storage — data is lost on container stop (fresh each preview)
            sb.append("    tmpfs:\n");
            sb.append("      - ").append(databaseType.getDataDir()).append("\n");

            // Healthcheck
            sb.append("    healthcheck:\n");
            sb.append("      test: [\"CMD-SHELL\", \"").append(escapeYamlString(databaseType.getHealthcheckCmd())).append("\"]\n");
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
        }

        // ── Network ──
        // NOTE: Do NOT use "internal: true" here — it blocks host-to-container port forwarding
        // via published ports on many Docker Engine versions (iptables DNAT rules not created).
        // Sandbox isolation is provided by Docker's default network namespace + container security_opt.
        sb.append("networks:\n");
        sb.append("  preview-net:\n");
        sb.append("    driver: bridge\n");

        Path composeFile = workspacePath.resolve("docker-compose.yml");
        try {
            Files.writeString(composeFile, sb.toString());
            log.debug("Generated docker-compose.yml at {} (db={}, envVars={})",
                    composeFile, databaseType, allEnvVars.size());
        } catch (IOException e) {
            throw new PreviewException("Failed to write docker-compose.yml: " + e.getMessage(), e);
        }
        return composeFile;
    }

    // ── DB connection string injection ───────────────────────────────────────

    /**
     * Build environment variables for the app service to connect to the DB.
     * Auto-detects framework conventions based on project type.
     */
    private Map<String, String> buildDbConnectionVars(DatabaseType dbType, PreviewProjectType projectType) {
        var vars = new LinkedHashMap<String, String>();

        switch (dbType) {
            case POSTGRESQL -> {
                String jdbcUrl = "jdbc:postgresql://db:5432/" + dbType.getDbName();
                String genericUrl = "postgresql://" + dbType.getUser() + ":" + dbType.getPassword()
                        + "@db:5432/" + dbType.getDbName();

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
                String genericUrl = "mysql://" + dbType.getUser() + ":" + dbType.getPassword()
                        + "@db:3306/" + dbType.getDbName();

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

    /**
     * Build environment variables for the database service itself (credentials, db name).
     */
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

    // ── YAML helpers ─────────────────────────────────────────────────────────

    /**
     * Quote a YAML value if it contains special characters.
     */
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
