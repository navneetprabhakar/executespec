package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isFrontendOnlyTask;
import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps frontend call-site paths relative to an explicitly declared shared {@code /api} client. */
final class PlanFrontendApiCallSiteNormalizer {
  private static final Pattern API_BASE_URL =
      Pattern.compile("(?i)\\bbaseURL\\s*[:=]\\s*[`'\"]\\s*/api/?\\s*[`'\"]");
  private static final Pattern HTTP_API_CALL =
      Pattern.compile("(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s+(/api(?=/|\\b)[^\\s`'\"]*)");
  private static final Pattern TYPED_API_CALL =
      Pattern.compile(
          "(?i)(\\b(?:(?:apiClient|client)\\.)?(?:get|post|put|patch|delete)"
              + "(?:<[^>\\n]+>)?\\s*\\(\\s*[`'\"])/api(?=/|\\b)");

  private PlanFrontendApiCallSiteNormalizer() {}

  static Plan normalize(Plan plan) {
    if (plan == null || plan.tasks() == null || !declaresApiBaseUrl(plan)) return plan;
    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    boolean changed = false;
    for (PlannedTask task : plan.tasks()) {
      if (task == null || !isFrontendOnlyTask(task)) {
        tasks.add(task);
        continue;
      }
      String description = relativeCalls(safe(task.description()));
      List<String> constraints =
          (task.hardConstraints() == null ? List.<String>of() : task.hardConstraints())
              .stream().map(PlanFrontendApiCallSiteNormalizer::relativeCalls).toList();
      changed |=
          !description.equals(safe(task.description()))
              || !constraints.equals(
                  task.hardConstraints() == null ? List.of() : task.hardConstraints());
      tasks.add(TaskExpansionPlanNormalizer.copyTask(task, description, constraints));
    }
    return changed ? TaskExpansionPlanNormalizer.copyPlan(plan, tasks) : plan;
  }

  private static boolean declaresApiBaseUrl(Plan plan) {
    return plan.tasks().stream()
        .filter(java.util.Objects::nonNull)
        .map(
            task ->
                safe(task.description())
                    + "\n"
                    + String.join(
                        "\n", task.hardConstraints() == null ? List.of() : task.hardConstraints()))
        .anyMatch(evidence -> API_BASE_URL.matcher(evidence).find());
  }

  private static String relativeCalls(String evidence) {
    Matcher matcher = HTTP_API_CALL.matcher(evidence);
    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      String publicPath = matcher.group(2);
      String relativePath = publicPath.length() == 4 ? "/" : publicPath.substring(4);
      matcher.appendReplacement(
          result, Matcher.quoteReplacement(matcher.group(1) + " " + relativePath));
    }
    matcher.appendTail(result);
    return TYPED_API_CALL.matcher(result).replaceAll("$1");
  }
}
