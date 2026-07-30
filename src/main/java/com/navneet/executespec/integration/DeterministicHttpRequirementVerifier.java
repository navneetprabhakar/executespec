package com.navneet.executespec.integration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor.RouteKey;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Deterministically verifies exact HTTP method/path requirements against the generated source.
 *
 * <p>This intentionally accepts only explicit {@code METHOD /path} requirements. Ambiguous prose
 * remains advisory rather than being guessed into a release-blocking contract. Non-HTTP plans and
 * HTTP plans with no exact route requirement are not applicable.
 */
@Component
@RequiredArgsConstructor
class DeterministicHttpRequirementVerifier {

  private static final Pattern EXACT_ROUTE =
      Pattern.compile(
          "(?i)(?:^|\\s|[`(])"
              + "(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE)\\s+"
              + "(/[A-Za-z0-9_~!$&'()*+=:@%{}./-]+)");
  private static final Pattern REMOVAL_PREFIX =
      Pattern.compile("(?i).*\\b(?:remove|delete|retire|drop)\\s*$", Pattern.DOTALL);

  private final SpringHttpSurfaceExtractor extractor;

  Verification verify(Plan plan, String originalRequest, List<Path> repositoryRoots) {
    if (plan != null && plan.runtimeSurface() != Plan.RuntimeSurface.HTTP) {
      return Verification.notApplicable();
    }
    Set<RouteKey> required = exactRequirements(plan, originalRequest);
    if (required.isEmpty()) {
      return Verification.notApplicable();
    }
    Set<RouteKey> actual = new LinkedHashSet<>();
    if (repositoryRoots != null) {
      repositoryRoots.stream()
          .filter(java.util.Objects::nonNull)
          .map(extractor::extract)
          .flatMap(snapshot -> snapshot.keys().stream())
          .forEach(actual::add);
    }
    List<RouteKey> missing =
        required.stream().filter(route -> !actual.contains(route)).sorted().toList();
    return new Verification(true, Set.copyOf(required), Set.copyOf(actual), missing);
  }

  Verification verify(Plan plan, String originalRequest, Path workspaceRoot) {
    return verify(plan, originalRequest, List.of(workspaceRoot));
  }

  void assertSatisfied(Plan plan, String originalRequest, Path workspaceRoot) {
    assertSatisfied(verify(plan, originalRequest, workspaceRoot));
  }

  void assertSatisfied(
      Plan plan, String originalRequest, CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    assertSatisfied(
        verify(
            plan,
            originalRequest,
            checkpoint.repositories().stream()
                .map(CorrectionWorkspaceTransaction.RepositoryCheckpoint::repository)
                .toList()));
  }

  private static void assertSatisfied(Verification verification) {
    if (!verification.applicable() || verification.missing().isEmpty()) {
      return;
    }
    String missing =
        verification.missing().stream()
            .map(route -> route.method() + " " + route.path())
            .reduce((left, right) -> left + ", " + right)
            .orElse("(unknown)");
    throw new IllegalStateException(
        "DETERMINISTIC_HTTP_REQUIREMENT_MISSING: required HTTP routes are absent: " + missing);
  }

  private static Set<RouteKey> exactRequirements(Plan plan, String originalRequest) {
    List<String> evidence = new ArrayList<>();
    if (originalRequest != null && !originalRequest.isBlank()) {
      evidence.add(originalRequest);
    }
    if (plan != null) {
      plan.requirements().stream()
          .map(requirement -> requirement.text())
          .filter(text -> text != null && !text.isBlank())
          .forEach(evidence::add);
      for (var task : plan.tasks()) {
        if (task.title() != null) evidence.add(task.title());
        if (task.description() != null) evidence.add(task.description());
        task.hardConstraints().stream()
            .filter(text -> text != null && !text.isBlank())
            .forEach(evidence::add);
      }
    }
    Set<RouteKey> required = new LinkedHashSet<>();
    for (String text : evidence) {
      Matcher matcher = EXACT_ROUTE.matcher(text);
      while (matcher.find()) {
        String prefix = text.substring(Math.max(0, matcher.start() - 48), matcher.start());
        if (REMOVAL_PREFIX.matcher(prefix).matches()) {
          continue;
        }
        String path = matcher.group(2);
        while (path.length() > 1 && ".,;:)`".indexOf(path.charAt(path.length() - 1)) >= 0) {
          path = path.substring(0, path.length() - 1);
        }
        required.add(new RouteKey(matcher.group(1).toUpperCase(Locale.ROOT), path));
      }
    }
    return Set.copyOf(required);
  }

  record Verification(
      boolean applicable, Set<RouteKey> required, Set<RouteKey> actual, List<RouteKey> missing) {
    Verification {
      required = required == null ? Set.of() : Set.copyOf(required);
      actual = actual == null ? Set.of() : Set.copyOf(actual);
      missing = missing == null ? List.of() : List.copyOf(missing);
    }

    static Verification notApplicable() {
      return new Verification(false, Set.of(), Set.of(), List.of());
    }
  }
}
