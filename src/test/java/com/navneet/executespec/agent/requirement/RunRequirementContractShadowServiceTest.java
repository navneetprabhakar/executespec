package com.navneet.executespec.agent.requirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.RequirementSpec;
import com.navneet.executespec.agent.requirement.RunRequirementContractShadowService.ShadowWriteOutcome;
import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;
import com.navneet.executespec.agent.requirement.repository.RunRequirementContractRepository;
import com.navneet.executespec.observability.Metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataRetrievalFailureException;

class RunRequirementContractShadowServiceTest {
  private static final String RUN_ID = "run-shadow-1";
  private static final String SOURCE = "Return 404 when an order is absent.";

  private final RunRequirementContractRepository repository =
      mock(RunRequirementContractRepository.class);
  private final Metrics metrics = mock(Metrics.class);
  // Mirrors Spring Boot's application mapper: Plan has a derived boolean getter that is emitted
  // by serialization but is not a record constructor property on replay.
  private final ObjectMapper objectMapper =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  private final RequirementIrCompiler compiler = new RequirementIrCompiler();
  private final RequirementIrCodec codec = new RequirementIrCodec(objectMapper);

  private RunRequirementContractShadowService service;

  @BeforeEach
  void setUp() {
    service = new RunRequirementContractShadowService(repository, objectMapper, metrics);
  }

  @Test
  void persistsCanonicalArtifactBoundToTheExactLegacyPlanBytes() throws Exception {
    Plan plan = plan(SOURCE);
    String exactPlanJson = objectMapper.writeValueAsString(plan);
    RequirementIrDocument document = compiler.compile(SOURCE, plan);

    var result = service.persist(RUN_ID, SOURCE, exactPlanJson);

    ArgumentCaptor<RunRequirementContract> row =
        ArgumentCaptor.forClass(RunRequirementContract.class);
    verify(repository).saveAndFlush(row.capture());
    RunRequirementContract persisted = row.getValue();

    assertThat(result.outcome()).isEqualTo(ShadowWriteOutcome.PERSISTED);
    assertThat(persisted.getRunId()).isEqualTo(RUN_ID);
    assertThat(persisted.getExactPlanFingerprint())
        .isEqualTo(codec.exactPlanFingerprint(exactPlanJson));
    assertThat(persisted.getCanonicalArtifactJson()).isEqualTo(codec.writeCanonical(document));
    assertThat(persisted.getArtifactDigest()).isEqualTo(codec.digest(document));
    assertThat(persisted.getBoundDigest())
        .isEqualTo(codec.boundDigest(document, exactPlanJson, SOURCE));
    assertThat(persisted.getSourceSha256()).isEqualTo(document.sourceSha256());
    assertThat(persisted.getCompilerFingerprint()).isEqualTo(document.compilerFingerprint());
    assertThat(persisted.getSchemaVersion()).isEqualTo(document.schemaVersion());
    assertThat(persisted.getCompleteness()).isEqualTo(document.completeness());
    assertThat(persisted.getRequirementCount()).isEqualTo(document.requirements().size());
    assertThat(persisted.getAcceptanceCriterionCount())
        .isEqualTo(document.acceptanceCriteria().size());
    assertThat(persisted.getTaskBindingCount()).isEqualTo(document.taskBindings().size());
    assertThat(persisted.getIssueCount()).isEqualTo(document.issues().size());
    assertThat(persisted.getBlockingIssueCount())
        .isEqualTo(document.issues().stream().filter(issue -> issue.blocking()).count());
    verify(metrics).recordRequirementContractShadow("persisted");
  }

  @Test
  void exactReplayReturnsTheExistingImmutableArtifactWithoutAnotherWrite() throws Exception {
    String exactPlanJson = objectMapper.writeValueAsString(plan(SOURCE));

    service.persist(RUN_ID, SOURCE, exactPlanJson);
    ArgumentCaptor<RunRequirementContract> row =
        ArgumentCaptor.forClass(RunRequirementContract.class);
    verify(repository).saveAndFlush(row.capture());
    when(repository.findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
            anyString(), anyString(), anyInt(), anyString()))
        .thenReturn(Optional.of(row.getValue()));

    var replay = service.persist(RUN_ID, SOURCE, exactPlanJson);

    assertThat(replay.outcome()).isEqualTo(ShadowWriteOutcome.IDEMPOTENT_REPLAY);
    assertThat(replay.exactPlanFingerprint()).isEqualTo(row.getValue().getExactPlanFingerprint());
    assertThat(replay.boundDigest()).isEqualTo(row.getValue().getBoundDigest());
    verify(repository, times(1)).saveAndFlush(org.mockito.ArgumentMatchers.any());
    verify(repository, never()).delete(org.mockito.ArgumentMatchers.any());
    verify(metrics).recordRequirementContractShadow("idempotent_replay");
  }

  @Test
  void sameIdentityWithDifferentSourceOrDigestsIsAnImmutableConflict() throws Exception {
    String exactPlanJson = objectMapper.writeValueAsString(plan(SOURCE));

    service.persist(RUN_ID, SOURCE, exactPlanJson);
    ArgumentCaptor<RunRequirementContract> row =
        ArgumentCaptor.forClass(RunRequirementContract.class);
    verify(repository).saveAndFlush(row.capture());
    when(repository.findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
            anyString(), anyString(), anyInt(), anyString()))
        .thenReturn(Optional.of(row.getValue()));

    var conflict = service.persist(RUN_ID, "Return 410 when an order is absent.", exactPlanJson);

    assertThat(conflict.outcome()).isEqualTo(ShadowWriteOutcome.CONFLICT);
    assertThat(conflict.reasonCode()).isEqualTo("IMMUTABLE_IDENTITY_CONFLICT");
    verify(repository, times(1)).saveAndFlush(org.mockito.ArgumentMatchers.any());
    verify(repository, never()).delete(org.mockito.ArgumentMatchers.any());
    verify(metrics).recordRequirementContractShadow("conflict");
  }

  @Test
  void semanticallyEquivalentButByteDifferentPlanJsonCreatesDistinctBindings() throws Exception {
    Plan plan = plan(SOURCE);
    String compact = objectMapper.writeValueAsString(plan);
    String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(plan);

    var compactResult = service.persist(RUN_ID, SOURCE, compact);
    var prettyResult = service.persist(RUN_ID, SOURCE, pretty);

    assertThat(pretty).isNotEqualTo(compact);
    assertThat(compactResult.outcome()).isEqualTo(ShadowWriteOutcome.PERSISTED);
    assertThat(prettyResult.outcome()).isEqualTo(ShadowWriteOutcome.PERSISTED);
    assertThat(prettyResult.exactPlanFingerprint())
        .isNotEqualTo(compactResult.exactPlanFingerprint());
    assertThat(prettyResult.boundDigest()).isNotEqualTo(compactResult.boundDigest());

    ArgumentCaptor<RunRequirementContract> rows =
        ArgumentCaptor.forClass(RunRequirementContract.class);
    verify(repository, times(2)).saveAndFlush(rows.capture());
    assertThat(rows.getAllValues())
        .extracting(RunRequirementContract::getArtifactDigest)
        .containsOnly(rows.getAllValues().getFirst().getArtifactDigest());
    assertThat(rows.getAllValues())
        .extracting(RunRequirementContract::getExactPlanFingerprint)
        .doesNotHaveDuplicates();
  }

  @Test
  void malformedPlanJsonIsACompileFailureAndNeverTouchesTheRepository() {
    var result = service.persist(RUN_ID, SOURCE, "{not-json");

    assertThat(result.outcome()).isEqualTo(ShadowWriteOutcome.COMPILE_FAILED);
    assertThat(result.exactPlanFingerprint()).isNull();
    assertThat(result.boundDigest()).isNull();
    verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    verify(repository, never())
        .findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
            anyString(), anyString(), anyInt(), anyString());
    verify(metrics).recordRequirementContractShadow("compile_failed");
  }

  @Test
  void repositoryFailureIsTypedMeteredAndFailSoft() throws Exception {
    String exactPlanJson = objectMapper.writeValueAsString(plan(SOURCE));
    when(repository.findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
            anyString(), anyString(), anyInt(), anyString()))
        .thenThrow(new DataRetrievalFailureException("database unavailable"));

    var result = service.persist(RUN_ID, SOURCE, exactPlanJson);

    assertThat(result.outcome()).isEqualTo(ShadowWriteOutcome.PERSIST_FAILED);
    assertThat(result.reasonCode()).isEqualTo(DataRetrievalFailureException.class.getSimpleName());
    assertThat(result.exactPlanFingerprint()).isNull();
    assertThat(result.boundDigest()).isNull();
    verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    verify(metrics).recordRequirementContractShadow("persist_failed");
  }

  @Test
  void metricFailureNeverChangesTheSuccessfulPersistenceOutcome() throws Exception {
    String exactPlanJson = objectMapper.writeValueAsString(plan(SOURCE));
    doThrow(new IllegalStateException("registry unavailable"))
        .when(metrics)
        .recordRequirementContractShadow("persisted");

    var result = service.persist(RUN_ID, SOURCE, exactPlanJson);

    assertThat(result.outcome()).isEqualTo(ShadowWriteOutcome.PERSISTED);
    verify(repository).saveAndFlush(org.mockito.ArgumentMatchers.any());
    verify(metrics).recordRequirementContractShadow("persisted");
  }

  @Test
  void duplicateInsertRaceAcceptsOnlyAnExactPersistedWinner() throws Exception {
    String exactPlanJson = objectMapper.writeValueAsString(plan(SOURCE));
    service.persist(RUN_ID, SOURCE, exactPlanJson);
    ArgumentCaptor<RunRequirementContract> candidate =
        ArgumentCaptor.forClass(RunRequirementContract.class);
    verify(repository).saveAndFlush(candidate.capture());
    RunRequirementContract winner = candidate.getValue();
    reset(repository, metrics);
    when(repository.findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
            anyString(), anyString(), anyInt(), anyString()))
        .thenReturn(Optional.empty(), Optional.of(winner));
    when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new DataIntegrityViolationException("concurrent insert"));

    var result = service.persist(RUN_ID, SOURCE, exactPlanJson);

    assertThat(result.outcome()).isEqualTo(ShadowWriteOutcome.IDEMPOTENT_REPLAY);
    assertThat(result.reasonCode()).isEqualTo("CONCURRENT_EXACT_REPLAY");
    verify(repository).saveAndFlush(org.mockito.ArgumentMatchers.any());
    verify(metrics).recordRequirementContractShadow("idempotent_replay");
  }

  private static Plan plan(String requirementText) {
    return Plan.builder()
        .requirements(
            List.of(
                new RequirementSpec(
                    "R1", requirementText, RequirementSpec.RequirementKind.FUNCTIONAL)))
        .tasks(
            List.of(
                PlannedTask.builder()
                    .id("T1")
                    .title("Implement order lookup")
                    .description(requirementText)
                    .meetsRequirements(List.of("R1"))
                    .build()))
        .build();
  }
}
