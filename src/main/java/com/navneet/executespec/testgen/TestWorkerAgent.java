package com.navneet.executespec.testgen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.testgen.model.TestPlan;
import com.navneet.executespec.testgen.model.TestTask;
import com.navneet.executespec.testgen.model.TestTaskStatus;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestTaskRepository;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Generates a single integration test class per TestTask. Always runs at CAPABLE tier (Sonnet) —
 * test generation requires understanding of HTTP semantics, assertion patterns, and edge cases. One
 * retry with compilation error context; no tier escalation.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TestWorkerAgent {

  private final LlmRouter llmRouter;
  private final ModelResolver modelResolver;
  private final StateStore stateStore;
  private final TestTaskRepository testTaskRepository;
  private final TestPlanRepository testPlanRepository;
  private final TestProjectScaffold scaffold;
  private final ObjectMapper objectMapper;

  @Value("classpath:prompts/test-worker.txt")
  private Resource promptResource;

  /**
   * Generate a test class for the given task and write it to the test workspace.
   *
   * @return the generated Java source code
   */
  public String generateTestClass(
      String runId,
      TestTask task,
      String conventionsDoc,
      String userRequest,
      boolean isCli,
      boolean isStaticHtml) {
    return generateTestClass(runId, task, conventionsDoc, userRequest, isCli, isStaticHtml, "");
  }

  /**
   * Generate a test class with live API probe results injected into the prompt.
   *
   * @param apiProbeResults markdown summary from {@link ApiProber#probe} — empty string if
   *     unavailable
   * @return the generated Java source code
   */
  public String generateTestClass(
      String runId,
      TestTask task,
      String conventionsDoc,
      String userRequest,
      boolean isCli,
      boolean isStaticHtml,
      String apiProbeResults) {
    log.info(
        "[TestWorker] Generating test class {} for run {} (isCli={}, isStaticHtml={})",
        task.getTaskName(),
        runId,
        isCli,
        isStaticHtml);

    task.setStatus(TestTaskStatus.GENERATING);
    testTaskRepository.save(task);

    // Build prompt
    String systemPrompt =
        buildPrompt(task, conventionsDoc, userRequest, isCli, isStaticHtml, apiProbeResults);

    // Call LLM via ModelResolver
    LlmProvider aiProvider =
        stateStore.findRun(runId).map(r -> r.getAiProviderEnum()).orElse(LlmProvider.OPENAI);
    ResolvedModel model = modelResolver.resolve("TestWorkerAgent", aiProvider);
    ChatResponse lr = callLlmTracked(runId, model, systemPrompt);
    String code = lr.getResult().getOutput().getText();
    accumulateWorkerTokens(task.getTestPlanId(), lr, model);

    // Strip markdown fences if present
    code = stripMarkdownFences(code);

    // Write to workspace-test
    try {
      writeTestFile(runId, task.getTaskName(), code);
    } catch (IOException e) {
      throw new RuntimeException("Failed to write test file " + task.getTaskName(), e);
    }

    // Persist
    task.setGeneratedCode(code);
    task.setStatus(TestTaskStatus.COMPILED); // optimistic — compilation gate will verify
    testTaskRepository.save(task);

    log.info(
        "[TestWorker] Generated {} ({} chars) for run {}",
        task.getTaskName(),
        code.length(),
        runId);
    return code;
  }

  /** Retry generating a test class with compilation error context. */
  public String retryWithError(
      String runId,
      TestTask task,
      String conventionsDoc,
      String userRequest,
      String compilationError,
      boolean isCli,
      boolean isStaticHtml) {
    return retryWithError(
        runId, task, conventionsDoc, userRequest, compilationError, isCli, isStaticHtml, "");
  }

  /** Retry generating a test class with compilation error and existing probe context. */
  public String retryWithError(
      String runId,
      TestTask task,
      String conventionsDoc,
      String userRequest,
      String compilationError,
      boolean isCli,
      boolean isStaticHtml,
      String apiProbeResults) {
    log.info("[TestWorker] Retrying {} with compilation error context", task.getTaskName());

    String systemPrompt =
        buildPrompt(task, conventionsDoc, userRequest, isCli, isStaticHtml, apiProbeResults)
            + "\n\nYour previous attempt had COMPILATION ERRORS:\n"
            + compilationError
            + "\n\nFix ALL compilation errors. Output ONLY valid, compilable Java source code.";

    LlmProvider aiProviderRetry =
        stateStore.findRun(runId).map(r -> r.getAiProviderEnum()).orElse(LlmProvider.OPENAI);
    ResolvedModel retryModel = modelResolver.resolve("TestWorkerAgent", aiProviderRetry);
    ChatResponse lr = callLlmTracked(runId, retryModel, systemPrompt);
    String code = lr.getResult().getOutput().getText();
    accumulateWorkerTokens(task.getTestPlanId(), lr, retryModel);
    code = stripMarkdownFences(code);

    try {
      writeTestFile(runId, task.getTaskName(), code);
    } catch (IOException e) {
      throw new RuntimeException("Failed to write retry test file " + task.getTaskName(), e);
    }

    task.setGeneratedCode(code);
    task.setCompilationError(null);
    testTaskRepository.save(task);

    return code;
  }

  String buildPrompt(
      TestTask task,
      String conventionsDoc,
      String userRequest,
      boolean isCli,
      boolean isStaticHtml) {
    return buildPrompt(task, conventionsDoc, userRequest, isCli, isStaticHtml, "");
  }

  String buildPrompt(
      TestTask task,
      String conventionsDoc,
      String userRequest,
      boolean isCli,
      boolean isStaticHtml,
      String apiProbeResults) {
    String testMethodsJson = plannedTestMethodsJson(task);

    String testingContext;
    String technicalReqs;
    String statusCodeConventions;
    String frameworkInstructions;

    if (isStaticHtml) {
      testingContext =
          "You are testing a STATIC HTML / FRONTEND web application using Playwright browser"
              + " automation.";
      technicalReqs =
          "- Use Playwright (com.microsoft.playwright) for browser-based testing\n"
              + "- Launch headless Chromium: playwright.chromium().launch(new"
              + " BrowserType.LaunchOptions().setHeadless(true))\n"
              + "- Base URL from System.getProperty(\"app.base-url\", \"http://localhost:8080\")\n"
              + "- Use @BeforeAll to create Playwright, Browser, BrowserContext, and Page"
              + " instances\n"
              + "- Use @AfterAll to close Page, BrowserContext, Browser, and Playwright (in reverse"
              + " order)\n"
              + "- Use page.navigate(BASE_URL) to load the page\n"
              + "- Use page.locator(\"css-selector\") to find elements\n"
              + "- Use locator.click(), locator.fill(), locator.textContent(), locator.inputValue()"
              + " for interactions\n"
              + "- Use assertThat() with textContent()/inputValue() results for verification\n"
              + "- Use @TestMethodOrder(MethodOrderer.OrderAnnotation.class) and @Order for"
              + " sequenced tests\n"
              + "- Package: com.executespec.tests";
      statusCodeConventions =
          "BROWSER TEST CONVENTIONS:\n"
              + "- Verify page loads by checking for expected elements or text content\n"
              + "- Verify interactions by checking display/output elements after clicks\n"
              + "- Use try/catch for resilient element selection (multiple possible selectors)";
      frameworkInstructions =
          "Java + Playwright (com.microsoft.playwright) + JUnit 5 + AssertJ. Package:"
              + " com.executespec.tests. Use browser automation for UI interaction testing. Do NOT"
              + " use RestAssured or make raw HTTP requests.";
    } else if (isCli) {
      testingContext = "You are testing a Command Line Interface (CLI) application.";
      technicalReqs =
          "- Execute the application binary/script directly within the JVM (e.g. ProcessBuilder)\n"
              + "- Assume the binary or script is executable from the current working directory\n"
              + "- Assert on stdout output, stderr, and exit codes\n"
              + "- Use JUnit 5 and AssertJ";
      statusCodeConventions =
          "CLI PROCESS CONVENTIONS:\n"
              + "- Success means exit code 0\n"
              + "- Error means non-zero exit code\n"
              + "- Use standard string assertions on stdout to verify behavior";
      frameworkInstructions =
          "Java + ProcessBuilder + JUnit 5 + AssertJ. Package: com.executespec.tests. "
              + "Do NOT use RestAssured or Playwright.";
    } else {
      testingContext = "You are testing against a live running application via HTTP.";
      technicalReqs =
          "- Use RestAssured for all HTTP calls\n"
              + "- Base URL is configurable via system property \"app.base-url\" (default:"
              + " http://localhost:8080)\n"
              + "- If auth is required, implement a helper method that calls the login endpoint and"
              + " caches the JWT token. Use this token in all authenticated requests.\n"
              + "- Use JUnit 5 (@Test, @BeforeAll, @AfterAll, @Order, @TestMethodOrder)\n"
              + "- Order test methods logically: create -> read -> update -> delete\n"
              + "- Use AssertJ for assertions (assertThat(...).isEqualTo(...))\n"
              + "- Each test method must be independent OR explicitly ordered with @Order"
              + " annotation\n"
              + "- For CRUD tests: create a test entity in @BeforeAll, clean up in @AfterAll\n"
              + "- For lifecycle tests: use @TestMethodOrder(MethodOrderer.OrderAnnotation.class)";
      statusCodeConventions =
          "HTTP STATUS CODE CONVENTIONS:\n"
              + "- POST that creates a resource: expect 201 Created (NOT 200)\n"
              + "- POST that performs an action (login, approve, etc.): expect 200 OK\n"
              + "- GET: expect 200 OK\n"
              + "- PUT/PATCH: expect 200 OK\n"
              + "- DELETE: expect 200 OK or 204 No Content\n"
              + "- 404 for resource not found\n"
              + "- 400 or 422 for validation errors\n"
              + "- 401 for unauthenticated, 403 for unauthorized\n"
              + "- Always check the ACTUAL conventions doc above — if it specifies different status"
              + " codes, use those instead";
      frameworkInstructions =
          "Java + RestAssured + JUnit 5 + AssertJ. Package: com.executespec.tests. "
              + "Do NOT use Playwright or ProcessBuilder.";
    }

    String probeSection =
        (apiProbeResults != null && !apiProbeResults.isBlank())
            ? "LIVE API PROBE RESULTS:\n" + apiProbeResults
            : "";
    return loadPrompt()
        .replace("{testing_context}", testingContext)
        .replace("{technical_requirements}", technicalReqs)
        .replace("{status_code_conventions}", statusCodeConventions)
        .replace("{framework_instructions}", frameworkInstructions)
        .replace("{task_name}", task.getTaskName())
        .replace("{task_description}", task.getTaskDescription())
        .replace("{target_entity}", task.getTargetEntity() != null ? task.getTargetEntity() : "")
        .replace("{category}", task.getTestCategory().name())
        .replace("{test_methods_json}", testMethodsJson)
        .replace("{conventions_doc}", conventionsDoc != null ? conventionsDoc : "")
        .replace("{user_request}", userRequest != null ? userRequest : "")
        .replace("{api_probe_results}", probeSection);
  }

  private ChatResponse callLlmTracked(String runId, ResolvedModel model, String systemPrompt) {
    Prompt prompt =
        new Prompt(
            List.of(
                new SystemMessage(systemPrompt), new UserMessage("Generate the test class now.")));
    return llmRouter.callText(
        model.providerName(),
        model.getModel(),
        prompt.getInstructions(),
        runId,
        "TestWorkerAgent",
        "test-worker");
  }

  private synchronized void accumulateWorkerTokens(
      Long testPlanId, ChatResponse lr, ResolvedModel model) {
    try {
      var usage = lr.getMetadata().getUsage();
      int inTokens = usage.getPromptTokens().intValue();
      int outTokens = usage.getCompletionTokens().intValue();
      TestPlan testPlan = testPlanRepository.findById(testPlanId).orElse(null);
      if (testPlan != null) {
        testPlan.setWorkerInputTokens(testPlan.getWorkerInputTokens() + inTokens);
        testPlan.setWorkerOutputTokens(testPlan.getWorkerOutputTokens() + outTokens);
        testPlan.setWorkerProvider(model.providerName());
        testPlan.setWorkerModel(model.getModel());
        testPlanRepository.save(testPlan);
      }
    } catch (Exception e) {
      log.warn("[TestWorker] Failed to extract token usage: {}", e.getMessage());
    }
  }

  private void writeTestFile(String runId, String className, String code) throws IOException {
    Path testDir = scaffold.getTestSourceDir(runId);
    Files.createDirectories(testDir);
    Files.writeString(testDir.resolve(className + ".java"), code);
  }

  private String stripMarkdownFences(String code) {
    if (code == null) return code;
    code = code.strip();
    if (code.startsWith("```java")) {
      code = code.substring(7);
    } else if (code.startsWith("```")) {
      code = code.substring(3);
    }
    if (code.endsWith("```")) {
      code = code.substring(0, code.length() - 3);
    }
    return code.strip();
  }

  String plannedTestMethodsJson(TestTask task) {
    if (task == null || task.getTestPlanId() == null) return fallbackTestMethods(task);
    try {
      TestPlan testPlan = testPlanRepository.findById(task.getTestPlanId()).orElse(null);
      if (testPlan == null || testPlan.getPlanJson() == null || testPlan.getPlanJson().isBlank()) {
        return fallbackTestMethods(task);
      }
      var root = objectMapper.readTree(testPlan.getPlanJson());
      if (!root.isArray()) return fallbackTestMethods(task);
      for (var node : root) {
        if (!task.getTaskName().equals(node.path("taskName").asText())) continue;
        var methods = node.path("testMethods");
        if (methods.isArray()) return objectMapper.writeValueAsString(methods);
        return fallbackTestMethods(task);
      }
    } catch (Exception e) {
      log.debug(
          "[TestWorker] Failed to extract planned methods for task {}: {}",
          task.getTaskName(),
          e.getMessage());
    }
    return fallbackTestMethods(task);
  }

  private String fallbackTestMethods(TestTask task) {
    String description = task == null ? "" : task.getTaskDescription();
    return description == null ? "" : description;
  }

  private String loadPrompt() {
    try {
      return promptResource.getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new RuntimeException("Failed to load test-worker.txt prompt", e);
    }
  }

  void setPromptResourceForTest(Resource promptResource) {
    this.promptResource = promptResource;
  }
}
