package com.navneet.executespec.deployment.analyzer;

import com.navneet.executespec.deployment.analyzer.InfrastructureManifest.CacheRequirement;
import com.navneet.executespec.deployment.analyzer.InfrastructureManifest.CacheRequirement.CacheType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Detects cache dependencies (Redis, Memcached) in a workspace.
 * Scans dependency files and configuration for cache client libraries.
 */
@Component
@Slf4j
public class CacheDetector {

    // Redis patterns
    private static final Pattern REDIS_MAVEN = Pattern.compile(
            "spring-boot-starter-data-redis|jedis|lettuce-core|spring-data-redis");
    private static final Pattern REDIS_NPM = Pattern.compile(
            "\"(redis|ioredis|@redis/client|bullmq|bull|@nestjs/bull)\"");
    private static final Pattern REDIS_PYTHON = Pattern.compile(
            "redis==|redis>=|aioredis|celery\\[redis\\]|django-redis|fakeredis");
    private static final Pattern REDIS_SPRING_PROPS = Pattern.compile(
            "spring\\.(?:data\\.)?redis\\.");

    // Memcached patterns
    private static final Pattern MEMCACHED_MAVEN = Pattern.compile("spymemcached|xmemcached");
    private static final Pattern MEMCACHED_NPM = Pattern.compile("\"memcached\"");
    private static final Pattern MEMCACHED_PYTHON = Pattern.compile("pymemcache|python-memcached");

    public CacheRequirement detect(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) return null;

        // Check root
        CacheRequirement result = detectInDir(workspace);
        if (result != null) return result;

        // Check subdirectories
        for (String subDir : new String[]{"backend", "server", "api", "app"}) {
            Path sub = workspace.resolve(subDir);
            if (Files.isDirectory(sub)) {
                result = detectInDir(sub);
                if (result != null) {
                    log.info("Cache detected in subdirectory {}: {}", subDir, result.type());
                    return result;
                }
            }
        }
        return null;
    }

    private CacheRequirement detectInDir(Path dir) {
        // Spring config
        for (String cfg : new String[]{
                "src/main/resources/application.yml",
                "src/main/resources/application.yaml",
                "src/main/resources/application.properties"}) {
            String content = readSafe(dir.resolve(cfg));
            if (content != null && REDIS_SPRING_PROPS.matcher(content).find()) {
                return new CacheRequirement(CacheType.REDIS, "spring.redis.* in " + cfg);
            }
        }

        // Maven / Gradle
        for (String file : new String[]{"pom.xml", "build.gradle", "build.gradle.kts"}) {
            String content = readSafe(dir.resolve(file));
            if (content != null) {
                if (REDIS_MAVEN.matcher(content).find())
                    return new CacheRequirement(CacheType.REDIS, "Redis dependency in " + file);
                if (MEMCACHED_MAVEN.matcher(content).find())
                    return new CacheRequirement(CacheType.MEMCACHED, "Memcached dependency in " + file);
            }
        }

        // Node
        String pkg = readSafe(dir.resolve("package.json"));
        if (pkg != null) {
            if (REDIS_NPM.matcher(pkg).find())
                return new CacheRequirement(CacheType.REDIS, "Redis dependency in package.json");
            if (MEMCACHED_NPM.matcher(pkg).find())
                return new CacheRequirement(CacheType.MEMCACHED, "Memcached dependency in package.json");
        }

        // Python
        for (String file : new String[]{"requirements.txt", "pyproject.toml", "Pipfile"}) {
            String content = readSafe(dir.resolve(file));
            if (content != null) {
                if (REDIS_PYTHON.matcher(content).find())
                    return new CacheRequirement(CacheType.REDIS, "Redis dependency in " + file);
                if (MEMCACHED_PYTHON.matcher(content).find())
                    return new CacheRequirement(CacheType.MEMCACHED, "Memcached dependency in " + file);
            }
        }

        return null;
    }

    private String readSafe(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            String content = Files.readString(file);
            return content.length() > 50_000 ? content.substring(0, 50_000) : content;
        } catch (IOException e) {
            return null;
        }
    }
}
