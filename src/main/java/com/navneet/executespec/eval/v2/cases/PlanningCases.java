package com.navneet.executespec.eval.v2.cases;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.eval.v2.TierEvalCase;
import com.navneet.executespec.router.AgentTier;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ClassPathResource;

/**
 * Production-shaped PLANNING-tier eval cases. Each case mirrors {@link
 * com.navneet.executespec.agent.OutlinePlannerAgent}'s FULL_PLAN invocation exactly: the verbatim
 * {@code prompts/outline-planner.md} system prompt + the real {@code submitOutline} tool schema.
 *
 * <p>Three input specs span the band the planner has to discriminate between in production:
 *
 * <ul>
 *   <li><b>small-rest</b> — a single-feature REST API spec; expects 3–6 tasks
 *   <li><b>medium-saas</b> — multi-feature SaaS scaffold; expects 8–14 tasks
 *   <li><b>refactor</b> — JS→TS migration; expects per-file/per-route task breakdown
 * </ul>
 */
public final class PlanningCases {

  private PlanningCases() {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static List<TierEvalCase> all() {
    return List.of(smallRestCase(), mediumSaasCase(), refactorCase());
  }

  private static TierEvalCase smallRestCase() {
    String spec =
        """
        Build a tiny URL-shortener REST API.

        Endpoints:
        - POST /api/shorten — body {url}, returns {slug, shortUrl}
        - GET /:slug — 302 redirect to the long URL
        - GET /api/stats/:slug — returns {slug, hits, createdAt}

        Constraints:
        - Express, in-memory store
        - Slug is 7 random alphanumeric chars
        - Unit tests with Jest, covering happy path + 1 error case per endpoint
        - README with curl examples
        """;
    return plannerCase("planning-plan-small-rest", spec, 3, 6, "single-feature REST");
  }

  private static TierEvalCase mediumSaasCase() {
    String spec =
        """
        Build a small B2B billing-portal backend.

        Modules:
        - /api/auth — signup/login with bcrypt + JWT
        - /api/orgs — CRUD for organizations, member invites by email
        - /api/subscriptions — POST creates a Stripe subscription, webhook updates status
        - /api/invoices — list + PDF download (use pdfkit)

        Constraints:
        - Node 20 + Express + Postgres + Prisma
        - All routes require Bearer JWT except /auth/*
        - Joi validation on every request body
        - Jest tests per module, covering happy + 1 error path each
        - Dockerfile + docker-compose with postgres
        - README with run instructions + the four sample curl calls
        """;
    return plannerCase("planning-plan-medium-saas", spec, 8, 14, "multi-feature SaaS");
  }

  private static TierEvalCase refactorCase() {
    String spec =
        """
        Migrate the following Express app from JavaScript to TypeScript while keeping every
        existing route working end-to-end.

        Current shape:
        - server.js boots Express on PORT
        - routes/users.js exports a Router with GET/POST/DELETE /api/users
        - routes/orders.js exports a Router with GET/POST/PATCH /api/orders
        - middleware/auth.js exports a Bearer-token middleware
        - lib/db.js wraps pg with a query() helper
        - tests use supertest + jest

        Migration deliverable:
        - tsconfig.json with strict mode + target es2022
        - All .js files renamed to .ts with proper types
        - Request/Response shapes typed via Express types
        - DB rows typed via per-table interfaces
        - npm scripts: dev (tsx watch), build (tsc), test (jest --config jest.config.ts)
        - All existing tests still pass after the migration
        """;
    return plannerCase("planning-plan-refactor-ts", spec, 6, 12, "JS→TS migration");
  }

  private static TierEvalCase plannerCase(
      String name, String spec, int minTasks, int maxTasks, String description) {
    String body = loadPrompt("prompts/outline-planner.md");
    String userPrompt = "Spec:\n\n" + spec + "\n\nProduce the plan now.";
    List<Message> messages =
        List.of(new SystemMessage(body), new UserMessage(userPrompt));
    ToolCallback submitOutline = buildSubmitOutlineShim();
    return new TierEvalCase() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public AgentTier tier() {
        return AgentTier.PLANNING;
      }

      @Override
      public List<Message> messages() {
        return messages;
      }

      @Override
      public List<ToolCallback> tools() {
        return List.of(submitOutline);
      }

      @Override
      public ValidationVerdict validate(
          String assistantText, String toolCallName, String toolCallArgsJson) {
        // Tool-call presence + JSON-validity already handled by the runner. We only
        // arrive here when submitOutline fired with parseable args.
        if (toolCallArgsJson == null || toolCallArgsJson.isBlank()) {
          return new ValidationVerdict(0, "tool args empty");
        }
        JsonNode args;
        try {
          args = MAPPER.readTree(toolCallArgsJson);
        } catch (Exception e) {
          return new ValidationVerdict(0, "args reparse failed: " + e.getMessage());
        }

        // Score additively, then floor at 0:
        //   +20  planTitle non-empty
        //   +20  planSummary non-empty
        //   +15  projectType present
        //   +25  tasks present + in the expected count band
        //   +10  every task has id + title
        //   +10  at least one task carries a meetsRequirements array
        int score = 0;
        java.util.List<String> notes = new java.util.ArrayList<>();
        if (args.has("planTitle") && !args.get("planTitle").asText("").isBlank()) {
          score += 20;
        } else {
          notes.add("no planTitle");
        }
        if (args.has("planSummary") && !args.get("planSummary").asText("").isBlank()) {
          score += 20;
        } else {
          notes.add("no planSummary");
        }
        if (args.has("projectType") && !args.get("projectType").asText("").isBlank()) {
          score += 15;
        } else {
          notes.add("no projectType");
        }
        JsonNode tasks = args.get("tasks");
        if (tasks != null && tasks.isArray() && tasks.size() > 0) {
          int count = tasks.size();
          if (count >= minTasks && count <= maxTasks) {
            score += 25;
            notes.add("tasks=" + count + " (in band " + minTasks + "-" + maxTasks + ")");
          } else {
            // Partial credit: out of band but plausible (within 50%).
            score += 10;
            notes.add(
                "tasks=" + count + " (out of band " + minTasks + "-" + maxTasks + ")");
          }
          boolean allHaveIdTitle = true;
          boolean anyMeetsReq = false;
          for (int i = 0; i < tasks.size(); i++) {
            JsonNode t = tasks.get(i);
            if (!t.has("id") || !t.has("title")) {
              allHaveIdTitle = false;
            }
            if (t.has("meetsRequirements")
                && t.get("meetsRequirements").isArray()
                && t.get("meetsRequirements").size() > 0) {
              anyMeetsReq = true;
            }
          }
          if (allHaveIdTitle) score += 10;
          else notes.add("some tasks missing id/title");
          if (anyMeetsReq) score += 10;
          else notes.add("no task cites meetsRequirements");
        } else {
          notes.add("no tasks array");
        }
        return new ValidationVerdict(
            Math.max(0, Math.min(100, score)), description + ": " + String.join("; ", notes));
      }
    };
  }

  /**
   * Build a {@link ToolCallback} with the exact {@code submitOutline} schema {@link
   * com.navneet.executespec.agent.OutlinePlannerAgent#buildSubmitOutlineCallback} uses for
   * FULL_PLAN mode. The callback never dispatches — {@link com.navneet.executespec.agent.LlmRouter}
   * captures the tool-call args without executing.
   */
  private static ToolCallback buildSubmitOutlineShim() {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("submitOutline")
            .description(
                "Submit the final outline-mode execution plan. Call exactly once with the complete plan.")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "planTitle": {"type": "string"},
    "planSummary": {"type": "string"},
    "projectType": {"type": "string"},
    "buildCommand": {"type": "string"},
    "smokeTestStrategy": {"type": "string"},
    "runtimeSurface": {"type": "string"},
    "requirements": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "id": {"type": "string"},
          "text": {"type": "string"},
          "kind": {"type": "string"}
        },
        "required": ["id", "text"]
      }
    },
    "tasks": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "id": {"type": "string"},
          "title": {"type": "string"},
          "description": {"type": "string"},
          "complexity": {"type": "string"},
          "type": {"type": "string"},
          "touchesFiles": {"type": "array", "items": {"type": "string"}},
          "dependsOn": {"type": "array", "items": {"type": "string"}},
          "hardConstraints": {"type": "array", "items": {"type": "string"}},
          "meetsRequirements": {"type": "array", "items": {"type": "string"}}
        },
        "required": ["id", "title"]
      }
    }
  },
  "required": ["planTitle", "planSummary", "tasks"]
}
""")
            .build();
    final String callbackId = "eval-v2-submitOutline-" + UUID.randomUUID();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return def;
      }

      @Override
      public String call(String toolInput) {
        // Shim — router returns the tool-call args directly; this is only invoked if Spring AI
        // ever auto-dispatches, which is disabled for our path.
        return "{\"ok\":true,\"note\":\"eval shim " + callbackId + "\"}";
      }
    };
  }

  private static String loadPrompt(String path) {
    try {
      return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("prompt file missing on classpath: " + path, e);
    }
  }
}
