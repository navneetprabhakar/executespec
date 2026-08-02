package com.navneet.executespec.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import com.navneet.executespec.run.outcome.RunTerminalOutcome;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class RunCompletionEmailTemplateContractTest {

  @Test
  void notificationPreferencesLinkTargetsTheProtectedSettingsSection() throws Exception {
    try (var stream = getClass().getResourceAsStream("/templates/email/run-completion.html")) {
      assertThat(stream).as("run completion template").isNotNull();
      String template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

      assertThat(template).contains("/settings?section=notifications");
      assertThat(template).doesNotContain("/settings/notifications");
    }
  }

  @ParameterizedTest
  @EnumSource(RunTerminalOutcome.Category.class)
  void templateRendersEveryTerminalCategoryAndRecordProperty(RunTerminalOutcome.Category category) {
    RunTerminalOutcome.NextAction nextAction =
        new RunTerminalOutcome.NextAction("REVIEW", "Review safe evidence", "/runs/run-1234");
    String supportCode =
        category == RunTerminalOutcome.Category.PLATFORM_FAILURE ? "ES-RUN-RUN1234" : null;
    RunTerminalOutcome outcome =
        new RunTerminalOutcome(
            1,
            "TEST_CODE",
            category,
            "Safe outcome title",
            "Safe customer message.",
            new RunTerminalOutcome.Impact(
                0.42, 1.0, null, RunTerminalOutcome.Publication.NOT_CREATED, null),
            false,
            nextAction,
            supportCode,
            RunTerminalOutcome.AutomationDisposition.ACTION_REQUIRED,
            3);

    Context context = new Context();
    context.setVariable("firstName", "Navneet");
    context.setVariable("runId", "run-12345678");
    context.setVariable("status", "PARTIAL");
    context.setVariable("terminalOutcome", outcome);
    context.setVariable("outcomeTitle", outcome.customerTitle());
    context.setVariable("outcomeMessage", outcome.customerMessage());
    context.setVariable("nextAction", outcome.nextAction());
    context.setVariable("supportCode", outcome.supportCode());
    context.setVariable("runTitle", "Fixture run");
    context.setVariable("verifiedScore", 84.0);
    context.setVariable("costUsd", 0.42);
    context.setVariable("prUrl", null);
    context.setVariable("runUrl", "https://dev.executespec.ai/runs/run-12345678");
    context.setVariable("baseUrl", "https://dev.executespec.ai");
    context.setVariable("isSuccess", category == RunTerminalOutcome.Category.SUCCESS);
    context.setVariable("isPartial", category == RunTerminalOutcome.Category.WARNING);
    context.setVariable(
        "isAttention",
        category == RunTerminalOutcome.Category.ACTION_REQUIRED
            || category == RunTerminalOutcome.Category.POLICY_BLOCKED);
    context.setVariable("isStopped", category == RunTerminalOutcome.Category.USER_STOPPED);
    context.setVariable("isFailure", category == RunTerminalOutcome.Category.PLATFORM_FAILURE);

    String html = templateEngine().process("run-completion", context);

    assertThat(html)
        .contains("Safe outcome title", "Review safe evidence")
        .doesNotContain("nextAction.label");
    if (category != RunTerminalOutcome.Category.SUCCESS) {
      assertThat(html).contains("Safe customer message.");
    }
    if (supportCode != null) assertThat(html).contains(supportCode);
  }

  private static SpringTemplateEngine templateEngine() {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/email/");
    resolver.setSuffix(".html");
    resolver.setTemplateMode("HTML");
    resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
    SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);
    return engine;
  }
}
