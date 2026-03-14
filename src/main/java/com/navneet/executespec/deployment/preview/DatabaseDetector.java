package com.navneet.executespec.deployment.preview;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Scans a workspace directory for database dependency indicators.
 * Returns the detected {@link DatabaseType}, or null if none detected.
 * When multiple databases are detected, returns the primary one (first match in priority order).
 * <p>
 * Fallback: if a database dependency is detected but the specific type cannot be determined,
 * defaults to {@link DatabaseType#POSTGRESQL}.
 */
@Component
@Slf4j
public class DatabaseDetector {

    // ── Pattern sets ─────────────────────────────────────────────────────────

    // PostgreSQL indicators
    private static final Pattern PG_MAVEN = Pattern.compile("org\\.postgresql|postgresql");
    private static final Pattern PG_NPM = Pattern.compile("\"(pg|postgres|@prisma/client|knex|typeorm|sequelize)\"");
    private static final Pattern PG_PYTHON = Pattern.compile("psycopg2|asyncpg|databases\\[postgresql\\]");
    private static final Pattern PG_PRISMA = Pattern.compile("provider\\s*=\\s*\"postgresql\"");
    private static final Pattern PG_SPRING_PROPS = Pattern.compile("spring\\.datasource\\.url.*postgresql");

    // MySQL indicators
    private static final Pattern MYSQL_MAVEN = Pattern.compile("mysql-connector|com\\.mysql");
    private static final Pattern MYSQL_NPM = Pattern.compile("\"(mysql2|mysql)\"");
    private static final Pattern MYSQL_PYTHON = Pattern.compile("pymysql|mysql-connector-python|mysqlclient");
    private static final Pattern MYSQL_PRISMA = Pattern.compile("provider\\s*=\\s*\"mysql\"");
    private static final Pattern MYSQL_SPRING_PROPS = Pattern.compile("spring\\.datasource\\.url.*mysql");

    // MongoDB indicators
    private static final Pattern MONGO_MAVEN = Pattern.compile("mongodb-driver|spring-boot-starter-data-mongodb|mongo-java-driver");
    private static final Pattern MONGO_NPM = Pattern.compile("\"(mongoose|mongodb)\"");
    private static final Pattern MONGO_PYTHON = Pattern.compile("pymongo|motor|mongoengine");
    private static final Pattern MONGO_PRISMA = Pattern.compile("provider\\s*=\\s*\"mongodb\"");
    private static final Pattern MONGO_SPRING_PROPS = Pattern.compile("spring\\.data\\.mongodb");

    // Redis indicators
    private static final Pattern REDIS_MAVEN = Pattern.compile("spring-boot-starter-data-redis|jedis|lettuce-core");
    private static final Pattern REDIS_NPM = Pattern.compile("\"(redis|ioredis|@redis/client)\"");
    private static final Pattern REDIS_PYTHON = Pattern.compile("\"redis\":|redis==|redis>=");

    // Generic "uses a database" indicators (for fallback)
    private static final Pattern GENERIC_DB = Pattern.compile(
            "spring-boot-starter-data-jpa|typeorm|sequelize|prisma|sqlalchemy|django\\.db|knex|drizzle-orm");

    /**
     * Scan workspace for database dependencies.
     *
     * @param workspacePath path to workspace/{runId}
     * @return detected DatabaseType, or null if no database dependency found
     */
    public DatabaseType detect(Path workspacePath) {
        if (workspacePath == null || !Files.isDirectory(workspacePath)) {
            return null;
        }

        // Check Prisma schema first (most specific)
        DatabaseType prismaResult = checkPrismaSchema(workspacePath);
        if (prismaResult != null) {
            log.info("Database detected via Prisma schema: {}", prismaResult);
            return prismaResult;
        }

        // Check Spring application config (application.yml / application.properties)
        DatabaseType springResult = checkSpringConfig(workspacePath);
        if (springResult != null) {
            log.info("Database detected via Spring config: {}", springResult);
            return springResult;
        }

        // Check Maven/Gradle dependencies
        DatabaseType javaResult = checkJavaDeps(workspacePath);
        if (javaResult != null) {
            log.info("Database detected via Java dependencies: {}", javaResult);
            return javaResult;
        }

        // Check Node.js dependencies
        DatabaseType nodeResult = checkNodeDeps(workspacePath);
        if (nodeResult != null) {
            log.info("Database detected via Node.js dependencies: {}", nodeResult);
            return nodeResult;
        }

        // Check Python dependencies
        DatabaseType pythonResult = checkPythonDeps(workspacePath);
        if (pythonResult != null) {
            log.info("Database detected via Python dependencies: {}", pythonResult);
            return pythonResult;
        }

        // Check subdirectories for multi-module projects (e.g., backend/, server/)
        for (String subDir : new String[]{"backend", "server", "api", "app"}) {
            Path sub = workspacePath.resolve(subDir);
            if (Files.isDirectory(sub)) {
                DatabaseType subResult = detectInDir(sub);
                if (subResult != null) {
                    log.info("Database detected in subdirectory {}: {}", subDir, subResult);
                    return subResult;
                }
            }
        }

        return null;
    }

    // ── Detection in a single directory ──────────────────────────────────────

    private DatabaseType detectInDir(Path dir) {
        DatabaseType prisma = checkPrismaSchema(dir);
        if (prisma != null) return prisma;

        DatabaseType java = checkJavaDeps(dir);
        if (java != null) return java;

        DatabaseType node = checkNodeDeps(dir);
        if (node != null) return node;

        DatabaseType python = checkPythonDeps(dir);
        return python;
    }

    // ── Prisma schema ────────────────────────────────────────────────────────

    private DatabaseType checkPrismaSchema(Path dir) {
        Path schema = dir.resolve("prisma/schema.prisma");
        if (!Files.isRegularFile(schema)) return null;
        String content = readSafe(schema);
        if (content == null) return null;

        if (PG_PRISMA.matcher(content).find()) return DatabaseType.POSTGRESQL;
        if (MYSQL_PRISMA.matcher(content).find()) return DatabaseType.MYSQL;
        if (MONGO_PRISMA.matcher(content).find()) return DatabaseType.MONGODB;

        // Prisma exists but provider unclear → default PG
        return DatabaseType.POSTGRESQL;
    }

    // ── Spring config ────────────────────────────────────────────────────────

    private DatabaseType checkSpringConfig(Path dir) {
        for (String fileName : new String[]{
                "src/main/resources/application.yml",
                "src/main/resources/application.yaml",
                "src/main/resources/application.properties"}) {
            Path file = dir.resolve(fileName);
            if (!Files.isRegularFile(file)) continue;
            String content = readSafe(file);
            if (content == null) continue;

            if (PG_SPRING_PROPS.matcher(content).find()) return DatabaseType.POSTGRESQL;
            if (MYSQL_SPRING_PROPS.matcher(content).find()) return DatabaseType.MYSQL;
            if (MONGO_SPRING_PROPS.matcher(content).find()) return DatabaseType.MONGODB;
        }
        return null;
    }

    // ── Java (Maven / Gradle) ────────────────────────────────────────────────

    private DatabaseType checkJavaDeps(Path dir) {
        // Check pom.xml
        String pom = readSafe(dir.resolve("pom.xml"));
        if (pom != null) {
            if (PG_MAVEN.matcher(pom).find()) return DatabaseType.POSTGRESQL;
            if (MYSQL_MAVEN.matcher(pom).find()) return DatabaseType.MYSQL;
            if (MONGO_MAVEN.matcher(pom).find()) return DatabaseType.MONGODB;
            if (REDIS_MAVEN.matcher(pom).find()) return DatabaseType.REDIS;
            if (GENERIC_DB.matcher(pom).find()) return DatabaseType.POSTGRESQL; // JPA without specific driver → PG
        }

        // Check build.gradle
        String gradle = readSafe(dir.resolve("build.gradle"));
        if (gradle == null) {
            gradle = readSafe(dir.resolve("build.gradle.kts"));
        }
        if (gradle != null) {
            if (PG_MAVEN.matcher(gradle).find()) return DatabaseType.POSTGRESQL;
            if (MYSQL_MAVEN.matcher(gradle).find()) return DatabaseType.MYSQL;
            if (MONGO_MAVEN.matcher(gradle).find()) return DatabaseType.MONGODB;
            if (REDIS_MAVEN.matcher(gradle).find()) return DatabaseType.REDIS;
            if (GENERIC_DB.matcher(gradle).find()) return DatabaseType.POSTGRESQL;
        }

        return null;
    }

    // ── Node.js (package.json) ───────────────────────────────────────────────

    private DatabaseType checkNodeDeps(Path dir) {
        String pkg = readSafe(dir.resolve("package.json"));
        if (pkg == null) return null;

        // Check specific DB drivers first (order matters for priority)
        if (MONGO_NPM.matcher(pkg).find()) return DatabaseType.MONGODB;
        if (REDIS_NPM.matcher(pkg).find()) return DatabaseType.REDIS;
        if (MYSQL_NPM.matcher(pkg).find()) return DatabaseType.MYSQL;
        if (PG_NPM.matcher(pkg).find()) return DatabaseType.POSTGRESQL;

        // Generic ORM without specific driver → check for Prisma schema
        if (pkg.contains("prisma") || pkg.contains("drizzle-orm") || pkg.contains("typeorm")) {
            return DatabaseType.POSTGRESQL; // Default to PG for ORMs
        }

        return null;
    }

    // ── Python ───────────────────────────────────────────────────────────────

    private DatabaseType checkPythonDeps(Path dir) {
        String content = null;

        // Check requirements.txt
        content = readSafe(dir.resolve("requirements.txt"));
        if (content == null) {
            content = readSafe(dir.resolve("pyproject.toml"));
        }
        if (content == null) {
            content = readSafe(dir.resolve("Pipfile"));
        }
        if (content == null) return null;

        if (MONGO_PYTHON.matcher(content).find()) return DatabaseType.MONGODB;
        if (REDIS_PYTHON.matcher(content).find()) return DatabaseType.REDIS;
        if (MYSQL_PYTHON.matcher(content).find()) return DatabaseType.MYSQL;
        if (PG_PYTHON.matcher(content).find()) return DatabaseType.POSTGRESQL;

        // Generic Python ORM → default PG
        if (content.contains("sqlalchemy") || content.contains("django")) {
            return DatabaseType.POSTGRESQL;
        }

        return null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String readSafe(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            String content = Files.readString(file);
            // Limit to first 50KB to avoid reading huge files
            return content.length() > 50_000 ? content.substring(0, 50_000) : content;
        } catch (IOException e) {
            log.debug("Failed to read {}: {}", file, e.getMessage());
            return null;
        }
    }
}
