package com.navneet.executespec.codereview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.router.ModelResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the agent's tool-output parser. The agent dispatches a single {@code submitCodeReview} tool
 * call; the orchestrator passes the raw arguments JSON back into {@link
 * CodeReviewAgent#parseSubmission(String)} which must:
 *
 * <ul>
 *   <li>handle missing optional fields (suggestedFix, droppedStaticFindings)
 *   <li>drop malformed issues (missing filePath / blank message) instead of throwing
 *   <li>normalize unknown source/severity values to safe defaults
 *   <li>preserve every well-formed dropped finding for audit
 * </ul>
 */
class CodeReviewAgentParseTest {

  private CodeReviewAgent agent;

  @BeforeEach
  void setUp() {
    agent =
        new CodeReviewAgent(
            mock(LlmRouter.class),
            mock(ModelResolver.class),
            new ObjectMapper(),
            mock(RunCostTracker.class));
  }

  @Test
  void parses_clean_review_with_one_issue_and_one_dropped_finding() throws Exception {
    String json =
        "{\n"
            + "  \"summary\": \"Generally clean. One serious bug in payments code.\",\n"
            + "  \"issues\": [\n"
            + "    {\n"
            + "      \"fingerprintRuleId\": \"LLM-NATIVE-charge-without-tx\",\n"
            + "      \"source\": \"LLM\",\n"
            + "      \"severity\": \"CRITICAL\",\n"
            + "      \"category\": \"BUG\",\n"
            + "      \"filePath\": \"src/billing/Charges.java\",\n"
            + "      \"lineStart\": 142,\n"
            + "      \"lineEnd\": 148,\n"
            + "      \"message\": \"Stripe charge persists outside a DB transaction.\",\n"
            + "      \"suggestedFix\": \"Wrap in @Transactional or compensate on rollback.\"\n"
            + "    }\n"
            + "  ],\n"
            + "  \"droppedStaticFindings\": [\n"
            + "    {\n"
            + "      \"ruleId\": \"java.lang.security.audit.weak-prng\",\n"
            + "      \"filePath\": \"src/test/Foo.java\",\n"
            + "      \"lineStart\": 12,\n"
            + "      \"reason\": \"Test fixture using SeededRandom for deterministic test"
            + " output.\"\n"
            + "    }\n"
            + "  ]\n"
            + "}";

    CodeReviewAgent.AgentResult result = agent.parseSubmission(json);

    assertNotNull(result);
    assertTrue(result.summary().startsWith("Generally clean"));
    assertEquals(1, result.issues().size());
    assertEquals("LLM", result.issues().get(0).source());
    assertEquals("CRITICAL", result.issues().get(0).severity());
    assertEquals("src/billing/Charges.java", result.issues().get(0).filePath());
    assertEquals(142, result.issues().get(0).lineStart());
    assertNotNull(result.issues().get(0).suggestedFix());
    assertEquals(1, result.droppedStaticFindings().size());
    assertEquals(
        "java.lang.security.audit.weak-prng", result.droppedStaticFindings().get(0).ruleId());
  }

  @Test
  void drops_malformed_issue_without_filepath_keeps_well_formed_one() throws Exception {
    String json =
        "{\n"
            + "  \"summary\": \"\",\n"
            + "  \"issues\": [\n"
            + "    { \"fingerprintRuleId\": \"a\", \"source\": \"LLM\", \"severity\": \"MAJOR\","
            + " \"category\": \"BUG\", \"filePath\": \"\", \"lineStart\": 1, \"lineEnd\": 1,"
            + " \"message\": \"missing path\" },\n"
            + "    { \"fingerprintRuleId\": \"b\", \"source\": \"LLM\", \"severity\": \"MAJOR\","
            + " \"category\": \"BUG\", \"filePath\": \"src/x.ts\", \"lineStart\": 2, \"lineEnd\":"
            + " 2, \"message\": \"\" },\n"
            + "    { \"fingerprintRuleId\": \"c\", \"source\": \"LLM\", \"severity\": \"MAJOR\","
            + " \"category\": \"BUG\", \"filePath\": \"src/y.ts\", \"lineStart\": 3, \"lineEnd\":"
            + " 3, \"message\": \"good one\" }\n"
            + "  ]\n"
            + "}";

    CodeReviewAgent.AgentResult result = agent.parseSubmission(json);

    assertEquals(1, result.issues().size(), "expected only the well-formed issue to survive");
    assertEquals("c", result.issues().get(0).ruleId());
    assertEquals("good one", result.issues().get(0).message());
  }

  @Test
  void normalizes_unknown_source_and_severity_to_safe_defaults() throws Exception {
    String json =
        "{\n"
            + "  \"summary\": \"\",\n"
            + "  \"issues\": [\n"
            + "    { \"fingerprintRuleId\": \"x\", \"source\": \"INVENTED\", \"severity\": \"MEH\","
            + " \"category\": \"BUG\", \"filePath\": \"src/z.ts\", \"lineStart\": 5, \"lineEnd\":"
            + " 5, \"message\": \"hi\" }\n"
            + "  ]\n"
            + "}";

    CodeReviewAgent.AgentResult result = agent.parseSubmission(json);

    assertEquals(1, result.issues().size());
    // Unknown source falls back to LLM; unknown severity falls back to INFO.
    assertEquals("LLM", result.issues().get(0).source());
    assertEquals("INFO", result.issues().get(0).severity());
  }

  @Test
  void absent_droppedStaticFindings_array_is_safe() throws Exception {
    String json = "{\"summary\":\"all good\",\"issues\":[]}";
    CodeReviewAgent.AgentResult result = agent.parseSubmission(json);
    assertNotNull(result.issues());
    assertEquals(0, result.issues().size());
    assertNotNull(result.droppedStaticFindings());
    assertEquals(0, result.droppedStaticFindings().size());
    // No suggestedFix anywhere — null is fine.
    assertEquals("all good", result.summary());
    assertNull(result.provider());
    assertNull(result.model());
  }
}
