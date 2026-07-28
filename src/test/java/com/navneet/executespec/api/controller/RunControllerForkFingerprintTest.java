package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import com.navneet.executespec.run.CustomerRunSubmissionService;
import com.navneet.executespec.run.RunRequestFingerprint;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestHeader;

class RunControllerForkFingerprintTest {

  @Test
  void forkEndpointAcceptsIdempotencyKeyHeader() {
    var method =
        Arrays.stream(RunController.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals("forkRun"))
            .findFirst()
            .orElseThrow();

    assertThat(method.getParameters())
        .anySatisfy(
            parameter -> {
              RequestHeader header = parameter.getAnnotation(RequestHeader.class);
              assertThat(header).isNotNull();
              assertThat(header.value()).isEqualTo("Idempotency-Key");
              assertThat(header.required()).isFalse();
            });
  }

  @Test
  void forkFingerprintCoversEveryEffectiveCommandFieldExceptTransportIdentity() {
    Set<String> semanticCommandFields =
        Arrays.stream(CustomerRunSubmissionService.Command.class.getRecordComponents())
            .map(RecordComponent::getName)
            .filter(name -> !Set.of("idempotencyKey", "requestFingerprint").contains(name))
            .collect(java.util.stream.Collectors.toSet());

    assertThat(RunController.forkFingerprintOf("source-run", command()).keySet())
        .contains("sourceRunId")
        .containsAll(semanticCommandFields)
        .doesNotContain("idempotencyKey", "requestFingerprint");
  }

  @Test
  void sourceRunAndEffectiveOverridesChangeForkFingerprint() {
    String baseline = hash("source-a", command());
    String anotherSource = hash("source-b", command());
    CustomerRunSubmissionService.Command changedRequest =
        CustomerRunSubmissionService.Command.builder()
            .userRequest("changed")
            .source("FORK")
            .executionMode("PRODUCTION")
            .selectedRepoIds(
                java.util.List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")))
            .build();

    assertThat(anotherSource).isNotEqualTo(baseline);
    assertThat(hash("source-a", changedRequest)).isNotEqualTo(baseline);
  }

  private static CustomerRunSubmissionService.Command command() {
    return CustomerRunSubmissionService.Command.builder()
        .userRequest("build")
        .source("FORK")
        .executionMode("PRODUCTION")
        .selectedRepoIds(java.util.List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")))
        .build();
  }

  private static String hash(String sourceRunId, CustomerRunSubmissionService.Command command) {
    return RunRequestFingerprint.of(
        new java.util.ArrayList<>(RunController.forkFingerprintOf(sourceRunId, command).values()));
  }
}
