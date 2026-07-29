package com.navneet.executespec.harness;

import java.util.Locale;

import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.RunModelOverrides;
import com.navneet.executespec.state.repository.AgentTaskRepository;

/** Persists the exact initial task-route explanation before worker dispatch. */
final class TaskRoutePersistence {

  private TaskRoutePersistence() {}

  static String configuredLowComplexityTier(String configuredDefault, String override) {
    String configured = override != null && !override.isBlank() ? override : configuredDefault;
    return configured == null || configured.isBlank()
        ? AgentTier.WORKHORSE.name()
        : configured.trim().toUpperCase(Locale.ROOT);
  }

  static String source(
      Boolean routePinned,
      boolean evidenceRouteChanged,
      boolean continuationOverride,
      String providerName,
      AgentTier tier) {
    if (continuationOverride) return "CONTINUATION_DECISION";
    if (evidenceRouteChanged) return "EVIDENCE_ROUTING";
    if (Boolean.TRUE.equals(routePinned)) return "RUN_OVERRIDE";
    try {
      LlmProvider provider = LlmProvider.valueOf(providerName.toUpperCase(Locale.ROOT));
      if (RunModelOverrides.lookup(provider, tier) != null) return "ORG_OVERRIDE";
    } catch (RuntimeException ignored) {
      // The concrete route is still persisted for legacy provider spellings.
    }
    return "DEFAULT_MATRIX";
  }

  static void persist(
      AgentTaskRepository repository,
      String runId,
      String taskId,
      String provider,
      String model,
      AgentTier tier,
      String category,
      String source) {
    if (repository == null || runId == null || runId.length() < 8 || taskId == null) return;
    repository
        .findById(runId.substring(0, 8) + "_" + taskId)
        .ifPresent(
            task -> {
              task.setProvider(provider == null ? null : provider.toUpperCase(Locale.ROOT));
              task.setModel(model);
              task.setRoutingTier(tier == null ? null : tier.name());
              task.setRoutingTaskCategory(category);
              task.setRoutingSource(source);
              repository.save(task);
            });
  }
}
