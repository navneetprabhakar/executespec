package com.navneet.executespec.testgen;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Deterministic template expansion from the user's spec to produce an
 * API Conventions document. Both code workers and test workers reference
 * this document so they agree on endpoint paths, response shapes, and error formats.
 *
 * NO LLM call — pure string templating from the plan JSON.
 */
@Component
@Slf4j
public class ApiConventionsGenerator {

    /**
     * Generate conventions markdown from the planner's task plan JSON and user request.
     * Parses entity names, auth config, and extras from the plan to build a deterministic reference.
     *
     * @param userRequest the original natural-language spec
     * @param planJson    the JSON plan from PlannerAgent (contains task list with entity names, etc.)
     * @return markdown string with API conventions
     */
    public String generate(String userRequest, String planJson, boolean isCli) {
        var sb = new StringBuilder();
        
        if (isCli) {
            sb.append("## CLI Application Conventions\n\n");
            sb.append("- The application is a Command Line Interface (CLI) tool, NOT a web server.\n");
            sb.append("- Execute the application binary/script using `ProcessBuilder` or an equivalent technique.\n");
            sb.append("- Assert on standard output (stdout), standard error (stderr), and exit codes (0 for success).\n");
            sb.append("- DO NOT attempt to send HTTP requests or use RestAssured.\n\n");
            return sb.toString();
        }

        sb.append("## API Conventions\n\n");

        sb.append("### Base Configuration\n");
        sb.append("- Base path: /api/v1\n");
        sb.append("- Port: 8080\n");
        sb.append("- Content-Type: application/json\n\n");

        sb.append("### Endpoint Patterns\n");
        sb.append("- Collection: GET /api/v1/{entity-plural}\n");
        sb.append("- Single: GET /api/v1/{entity-plural}/{id}\n");
        sb.append("- Create: POST /api/v1/{entity-plural}\n");
        sb.append("- Update: PUT /api/v1/{entity-plural}/{id}\n");
        sb.append("- Delete: DELETE /api/v1/{entity-plural}/{id}\n\n");

        sb.append("### Response Formats\n");
        sb.append("- Single entity: raw JSON object { \"id\": 1, ... }\n");

        // Check if pagination is mentioned in the spec
        boolean hasPagination = containsIgnoreCase(userRequest, "pagination")
                || containsIgnoreCase(planJson, "pagination");
        if (hasPagination) {
            sb.append("- Collection: { \"content\": [...], \"page\": 0, \"size\": 20, \"totalElements\": N }\n");
        } else {
            sb.append("- Collection: JSON array [ { ... }, { ... } ]\n");
        }
        sb.append("- Error: { \"error\": \"message\", \"status\": 400 }\n\n");

        // Auth section
        boolean hasAuth = containsIgnoreCase(userRequest, "auth")
                || containsIgnoreCase(userRequest, "jwt")
                || containsIgnoreCase(userRequest, "login")
                || containsIgnoreCase(planJson, "auth")
                || containsIgnoreCase(planJson, "security");
        if (hasAuth) {
            sb.append("### Authentication\n");
            sb.append("- Type: JWT Bearer Token\n");
            sb.append("- Header: Authorization: Bearer {token}\n");
            sb.append("- Public endpoints: POST /api/v1/auth/login, POST /api/v1/auth/register\n");
            sb.append("- All other endpoints require valid token\n\n");
        }

        // Validation section
        boolean hasValidation = containsIgnoreCase(userRequest, "validation")
                || containsIgnoreCase(planJson, "validation")
                || containsIgnoreCase(planJson, "@Valid");
        if (hasValidation) {
            sb.append("### Validation\n");
            sb.append("- Required fields return 400 Bad Request with error details\n");
            sb.append("- Unique constraint violations return 409 Conflict\n");
            sb.append("- @Email, @NotBlank, @NotNull, @Positive annotations enforced\n\n");
        }

        sb.append("### General Rules\n");
        sb.append("- Entity names use plural lowercase for paths (User → /users, OrderItem → /order-items)\n");
        sb.append("- IDs are Long (auto-generated)\n");
        sb.append("- Timestamps use ISO-8601 format\n");
        sb.append("- DELETE returns 204 No Content on success\n");
        sb.append("- NOT FOUND returns 404\n");

        log.info("Generated API conventions doc ({} chars, auth={}, pagination={}, validation={})",
                sb.length(), hasAuth, hasPagination, hasValidation);
        return sb.toString();
    }

    private boolean containsIgnoreCase(String text, String search) {
        if (text == null || search == null) return false;
        return text.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
    }
}
