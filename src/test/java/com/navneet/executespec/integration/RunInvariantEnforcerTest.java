package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.arch.invariants.ArchInvariant;
import com.navneet.executespec.arch.invariants.ArchInvariantRepository;
import com.navneet.executespec.arch.invariants.ArchInvariantViolation;
import com.navneet.executespec.arch.invariants.ArchInvariantViolationRepository;
import com.navneet.executespec.arch.invariants.InvariantChecker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class RunInvariantEnforcerTest {

  @TempDir Path workspace;

  @Test
  void violationIsPersistedAndProjectedAsCriticalRunEvidence() throws Exception {
    Files.createDirectories(workspace.resolve("api"));
    Files.writeString(workspace.resolve("api/Foo.java"), "import storage.S3Client;");
    ArchInvariantRepository invariants = mock(ArchInvariantRepository.class);
    ArchInvariantViolationRepository violations = mock(ArchInvariantViolationRepository.class);
    ArchInvariant invariant =
        invariant(
            "no-direct-storage",
            """
            enforcement:
              rule:
                kind: no-direct-call
                from: api/
                forbidden_target: storage.S3Client
            """);
    when(invariants.findByProjectId("p1")).thenReturn(List.of(invariant));
    RunInvariantEnforcer enforcer =
        new RunInvariantEnforcer(
            invariants, violations, new InvariantChecker(), new ObjectMapper());

    var issues = enforcer.enforce("run-1", "p1", workspace);

    assertThat(issues).hasSize(1);
    assertThat(issues.get(0).getId()).startsWith("SYNTH-INVARIANT-VIOLATION");
    assertThat(issues.get(0).getDescription()).contains("no-direct-storage");
    ArgumentCaptor<ArchInvariantViolation> saved =
        ArgumentCaptor.forClass(ArchInvariantViolation.class);
    verify(violations).save(saved.capture());
    assertThat(saved.getValue().getDetectionPhase()).isEqualTo("PLAN");
    assertThat(saved.getValue().getDetail()).contains("VIOLATION", "api/Foo.java");
  }

  @Test
  void unsupportedConfigurationIsPersistedAsUnavailableNotPass() {
    ArchInvariantRepository invariants = mock(ArchInvariantRepository.class);
    ArchInvariantViolationRepository violations = mock(ArchInvariantViolationRepository.class);
    when(invariants.findByProjectId("p1"))
        .thenReturn(
            List.of(
                invariant(
                    "future-rule",
                    """
                    enforcement:
                      rule:
                        kind: future-kind
                    """)));
    RunInvariantEnforcer enforcer =
        new RunInvariantEnforcer(
            invariants, violations, new InvariantChecker(), new ObjectMapper());

    var issues = enforcer.enforce("run-1", "p1", workspace);

    assertThat(issues).hasSize(1);
    assertThat(issues.get(0).getId()).startsWith("SYNTH-INVARIANT-UNAVAILABLE");
    ArgumentCaptor<ArchInvariantViolation> saved =
        ArgumentCaptor.forClass(ArchInvariantViolation.class);
    verify(violations).save(saved.capture());
    assertThat(saved.getValue().getDetail()).contains("UNSUPPORTED");
  }

  private static ArchInvariant invariant(String id, String yaml) {
    return ArchInvariant.builder()
        .id(UUID.randomUUID())
        .projectId("p1")
        .invariantId(id)
        .invariantYaml(yaml)
        .status("ACTIVE")
        .build();
  }
}
