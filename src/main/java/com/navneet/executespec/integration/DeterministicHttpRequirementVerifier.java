package com.navneet.executespec.integration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.contractsurface.ExactHttpConstraintParser;
import com.navneet.executespec.contractsurface.ExactHttpConstraintParser.Polarity;
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
  private final SpringHttpSurfaceExtractor extractor;

  Verification verify(Plan plan, String originalRequest, List<Path> repositoryRoots) {
    if (plan != null && plan.runtimeSurface() != Plan.RuntimeSurface.HTTP) {
      return Verification.notApplicable();
    }
    HttpConstraints constraints = exactRequirements(plan, originalRequest);
    if (constraints.required().isEmpty() && constraints.forbidden().isEmpty()) {
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
        constraints.required().stream()
            .filter(route -> !containsRoute(actual, route))
            .sorted()
            .toList();
    List<RouteKey> presentForbidden =
        constraints.forbidden().stream()
            .filter(route -> containsRoute(actual, route))
            .sorted()
            .toList();
    List<RouteKey> unprovenMethodRejections =
        constraints.forbidden().stream()
            .filter(route -> !containsRoute(actual, route))
            .filter(
                route ->
                    actual.stream().noneMatch(candidate -> candidate.path().equals(route.path())))
            .sorted()
            .toList();
    return new Verification(
        true,
        constraints.required(),
        constraints.forbidden(),
        Set.copyOf(actual),
        missing,
        presentForbidden,
        unprovenMethodRejections);
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
      if (!verification.presentForbidden().isEmpty()) {
        throw new IllegalStateException(
            "DETERMINISTIC_HTTP_FORBIDDEN_METHOD_PRESENT: methods required to be rejected are "
                + "implemented: "
                + display(verification.presentForbidden()));
      }
      if (!verification.unprovenMethodRejections().isEmpty()) {
        throw new IllegalStateException(
            "DETERMINISTIC_HTTP_METHOD_REJECTION_UNPROVEN: no supported route exists at paths "
                + "that must reject a method with 405: "
                + display(verification.unprovenMethodRejections()));
      }
      return;
    }
    throw new IllegalStateException(
        "DETERMINISTIC_HTTP_REQUIREMENT_MISSING: required HTTP routes are absent: "
            + display(verification.missing()));
  }

  private static HttpConstraints exactRequirements(Plan plan, String originalRequest) {
    Set<RouteKey> sourceRequired = new LinkedHashSet<>();
    Set<RouteKey> sourceForbidden = new LinkedHashSet<>();
    addConstraints(originalRequest, sourceRequired, sourceForbidden, Set.of());

    List<String> evidence = new ArrayList<>();
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
    Set<RouteKey> required = new LinkedHashSet<>(sourceRequired);
    Set<RouteKey> forbidden = new LinkedHashSet<>(sourceForbidden);
    Set<RouteKey> sourceAuthority = new LinkedHashSet<>(sourceRequired);
    sourceAuthority.addAll(sourceForbidden);
    for (String text : evidence) {
      addConstraints(text, required, forbidden, sourceAuthority);
    }
    return new HttpConstraints(Set.copyOf(required), Set.copyOf(forbidden));
  }

  private static void addConstraints(
      String evidence,
      Set<RouteKey> required,
      Set<RouteKey> forbidden,
      Set<RouteKey> immutableSourceAuthority) {
    for (var constraint : ExactHttpConstraintParser.parse(evidence)) {
      RouteKey route = new RouteKey(constraint.method(), constraint.path());
      if (immutableSourceAuthority.contains(route)) continue;
      if (constraint.polarity() == Polarity.METHOD_NOT_ALLOWED) {
        forbidden.add(route);
        required.remove(route);
      } else if (!forbidden.contains(route)) {
        required.add(route);
      }
    }
  }

  private static boolean containsRoute(Set<RouteKey> actual, RouteKey route) {
    return actual.contains(route) || actual.contains(new RouteKey("ANY", route.path()));
  }

  private static String display(List<RouteKey> routes) {
    return routes.stream()
        .map(route -> route.method() + " " + route.path())
        .reduce((left, right) -> left + ", " + right)
        .orElse("(unknown)");
  }

  record Verification(
      boolean applicable,
      Set<RouteKey> required,
      Set<RouteKey> forbidden,
      Set<RouteKey> actual,
      List<RouteKey> missing,
      List<RouteKey> presentForbidden,
      List<RouteKey> unprovenMethodRejections) {
    Verification {
      required = required == null ? Set.of() : Set.copyOf(required);
      forbidden = forbidden == null ? Set.of() : Set.copyOf(forbidden);
      actual = actual == null ? Set.of() : Set.copyOf(actual);
      missing = missing == null ? List.of() : List.copyOf(missing);
      presentForbidden = presentForbidden == null ? List.of() : List.copyOf(presentForbidden);
      unprovenMethodRejections =
          unprovenMethodRejections == null ? List.of() : List.copyOf(unprovenMethodRejections);
    }

    static Verification notApplicable() {
      return new Verification(false, Set.of(), Set.of(), Set.of(), List.of(), List.of(), List.of());
    }
  }

  private record HttpConstraints(Set<RouteKey> required, Set<RouteKey> forbidden) {}
}
