package com.navneet.executespec.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

/** Applies the authoritative-plus-additional array contract for one expanded task. */
@Slf4j
final class TaskExpansionValueMerger {

  static final int MAX_TOUCHES_FILES = 24;
  static final int MAX_TOUCH_FILE_CHARS = 160;
  static final int MAX_HARD_CONSTRAINTS = 12;
  static final int MAX_HARD_CONSTRAINT_CHARS = 360;
  static final int MAX_MEETS_REQUIREMENTS = 16;
  static final int MAX_REQUIREMENT_ID_CHARS = 40;

  private TaskExpansionValueMerger() {}

  record Result(
      List<String> values,
      int declaredRemainingCapacity,
      int providerItems,
      int acceptedAdditions,
      int providerOverflow,
      int preMergeDrops,
      int postMergeDrops,
      int authoritativeDrops,
      int truncatedItems) {

    boolean hasDrops() {
      return providerOverflow > 0
          || preMergeDrops > 0
          || postMergeDrops > 0
          || authoritativeDrops > 0
          || truncatedItems > 0;
    }
  }

  static Result mergeAndReport(
      String taskId,
      String field,
      List<String> authoritative,
      List<String> generatedAdditions,
      int maxItems,
      int maxLength) {
    Result result = merge(authoritative, generatedAdditions, maxItems, maxLength);
    if (result.hasDrops()) {
      log.warn(
          "TaskExpander output_contract task={} field={} remainingCapacity={} providerItems={}"
              + " acceptedAdditions={} providerOverflow={} preMergeDrops={} postMergeDrops={}"
              + " authoritativeDrops={} truncatedItems={}",
          taskId,
          field,
          result.declaredRemainingCapacity(),
          result.providerItems(),
          result.acceptedAdditions(),
          result.providerOverflow(),
          result.preMergeDrops(),
          result.postMergeDrops(),
          result.authoritativeDrops(),
          result.truncatedItems());
    }
    return result;
  }

  static Result authoritativeOnlyAndReport(
      String taskId,
      String field,
      List<String> authoritative,
      List<String> ignoredProviderValues,
      int maxItems,
      int maxLength) {
    List<String> authoritativeInput = authoritative == null ? List.of() : authoritative;
    List<String> values = boundValues(authoritativeInput, maxItems, maxLength);
    List<String> providerInput = ignoredProviderValues == null ? List.of() : ignoredProviderValues;
    Result result =
        new Result(
            values,
            0,
            providerInput.size(),
            0,
            providerInput.size(),
            0,
            providerInput.size(),
            Math.max(0, authoritativeInput.size() - values.size()),
            0);
    if (result.hasDrops()) {
      log.warn(
          "TaskExpander output_contract task={} field={} ownershipAuthoritative=true"
              + " providerItems={} acceptedAdditions=0 providerOverflow={} preMergeDrops=0"
              + " postMergeDrops={} authoritativeDrops={} truncatedItems=0",
          taskId,
          field,
          result.providerItems(),
          result.providerOverflow(),
          result.postMergeDrops(),
          result.authoritativeDrops());
    }
    return result;
  }

  static Result merge(
      List<String> authoritative, List<String> generatedAdditions, int maxItems, int maxLength) {
    List<String> authoritativeInput = authoritative == null ? List.of() : authoritative;
    List<String> boundedAuthoritative = boundValues(authoritativeInput, maxItems, maxLength);
    int authoritativeDrops = Math.max(0, authoritativeInput.size() - boundedAuthoritative.size());
    int remainingCapacity = Math.max(0, maxItems - boundedAuthoritative.size());

    List<String> providerInput = generatedAdditions == null ? List.of() : generatedAdditions;
    LinkedHashSet<String> normalizedProvider = new LinkedHashSet<>();
    int truncatedItems = 0;
    for (String value : providerInput) {
      String normalized = TaskExpansionTextNormalizer.boundText(value, maxLength);
      if (!normalized.equals(TaskExpanderAgent.safe(value).trim())) truncatedItems++;
      if (!normalized.isBlank()) normalizedProvider.add(normalized);
    }
    int preMergeDrops = Math.max(0, providerInput.size() - normalizedProvider.size());
    int providerOverflow = Math.max(0, normalizedProvider.size() - remainingCapacity);

    LinkedHashSet<String> accepted = new LinkedHashSet<>();
    for (String value : normalizedProvider) {
      if (boundedAuthoritative.contains(value)) continue;
      if (accepted.size() >= remainingCapacity) break;
      accepted.add(value);
    }
    int postMergeDrops = Math.max(0, normalizedProvider.size() - accepted.size());

    List<String> merged = new ArrayList<>(boundedAuthoritative.size() + accepted.size());
    merged.addAll(boundedAuthoritative);
    merged.addAll(accepted);
    return new Result(
        List.copyOf(merged),
        remainingCapacity,
        providerInput.size(),
        accepted.size(),
        providerOverflow,
        preMergeDrops,
        postMergeDrops,
        authoritativeDrops,
        truncatedItems);
  }

  static int remainingCapacity(List<String> authoritative, int maxItems, int maxLength) {
    return maxItems - boundValues(authoritative, maxItems, maxLength).size();
  }

  static List<String> boundValues(List<String> values, int maxItems, int maxLength) {
    if (values == null || values.isEmpty()) return List.of();
    LinkedHashSet<String> bounded = new LinkedHashSet<>();
    for (String value : values) {
      if (bounded.size() >= maxItems) break;
      String normalized = TaskExpansionTextNormalizer.boundText(value, maxLength);
      if (!normalized.isBlank()) bounded.add(normalized);
    }
    return List.copyOf(bounded);
  }
}
