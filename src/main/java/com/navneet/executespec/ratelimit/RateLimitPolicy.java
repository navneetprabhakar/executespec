package com.navneet.executespec.ratelimit;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Defines rate limit thresholds per endpoint group and user tier.
 *
 * <p>Groups:
 * <ul>
 *   <li>AUTH — login, register, forgot-password (keyed by IP)</li>
 *   <li>RUNS — POST /api/runs (keyed by userId)</li>
 *   <li>WEBHOOK — all webhook endpoints (keyed by IP, high limit)</li>
 *   <li>READ — all GET requests (keyed by userId)</li>
 *   <li>WRITE — all POST/PUT/DELETE not in other groups (keyed by userId)</li>
 * </ul>
 */
@Component
public class RateLimitPolicy {

    /** Endpoint group → tier → max requests per minute. */
    private static final Map<String, Map<String, Integer>> LIMITS = Map.ofEntries(
            Map.entry("AUTH", Map.of(
                    "login", 10,
                    "register", 5,
                    "forgot", 3,
                    "DEFAULT", 10
            )),
            Map.entry("RUNS", Map.of(
                    "FREE", 5,
                    "PRO", 20,
                    "ENTERPRISE", 60,
                    "DEFAULT", 5
            )),
            Map.entry("WEBHOOK", Map.of(
                    "DEFAULT", 100
            )),
            Map.entry("READ", Map.of(
                    "FREE", 300,
                    "PRO", 600,
                    "ENTERPRISE", 2000,
                    "DEFAULT", 300
            )),
            Map.entry("WRITE", Map.of(
                    "FREE", 60,
                    "PRO", 200,
                    "ENTERPRISE", 1000,
                    "DEFAULT", 60
            )),
            Map.entry("PREVIEW", Map.of(
                    "FREE", 10,
                    "PRO", 30,
                    "ENTERPRISE", 100,
                    "DEFAULT", 10
            )),
            Map.entry("DEPLOY", Map.of(
                    "FREE", 5,
                    "PRO", 20,
                    "ENTERPRISE", 60,
                    "DEFAULT", 5
            ))
    );

    /**
     * Get the rate limit (requests per minute) for the given group and tier.
     *
     * @param group endpoint group (AUTH, RUNS, WEBHOOK, READ, WRITE)
     * @param tier  user tier or auth sub-type (FREE, PRO, ENTERPRISE, login, register, etc.)
     * @return max requests per minute
     */
    public int getLimit(String group, String tier) {
        Map<String, Integer> groupLimits = LIMITS.getOrDefault(group, Map.of("DEFAULT", 60));
        return groupLimits.getOrDefault(tier, groupLimits.getOrDefault("DEFAULT", 60));
    }

    /**
     * Classify a request into an endpoint group.
     */
    public String classifyGroup(String method, String path) {
        if (path.startsWith("/api/auth/login") || path.startsWith("/api/auth/register")
                || path.startsWith("/api/auth/forgot")) {
            return "AUTH";
        }
        if (path.contains("/webhook")) {
            return "WEBHOOK";
        }
        if ("POST".equalsIgnoreCase(method) && path.equals("/api/runs")) {
            return "RUNS";
        }
        if (path.contains("/preview/")) {
            return "PREVIEW";
        }
        if (path.contains("/deploy") || path.contains("/push-github")) {
            return "DEPLOY";
        }
        if ("GET".equalsIgnoreCase(method)) {
            return "READ";
        }
        return "WRITE";
    }

    /**
     * Get the sub-type for AUTH group (login, register, forgot).
     */
    public String getAuthSubType(String path) {
        if (path.contains("/login")) return "login";
        if (path.contains("/register")) return "register";
        if (path.contains("/forgot")) return "forgot";
        return "DEFAULT";
    }
}
