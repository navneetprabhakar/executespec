package com.navneet.executespec.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.validator.gate.GateChain;
import com.navneet.executespec.validator.language.LanguageToolchain;
import com.navneet.executespec.validator.model.GateResult;
import com.navneet.executespec.validator.model.Language;
import com.navneet.executespec.validator.model.ValidateFileRequest;
import com.navneet.executespec.validator.model.ValidateProjectRequest;
import com.navneet.executespec.validator.model.ValidationGate;
import com.navneet.executespec.validator.model.ValidationLevel;
import com.navneet.executespec.validator.model.ValidationResult;
import com.navneet.executespec.validator.model.ValidationVerdict;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Verification honesty: skipped infrastructure is never represented as a green pass. */
@ExtendWith(MockitoExtension.class)
class CodeValidatorImplTest {

  @Mock private GateChain gateChain;
  @Mock private LanguageToolchain python;
  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  @Test
  void unavailableToolchainIsUnmeasuredNotPass() {
    when(python.getLanguage()).thenReturn(Language.PYTHON);
    when(python.isAvailable()).thenReturn(false);
    CodeValidatorImpl validator = new CodeValidatorImpl(gateChain, List.of(python), meterRegistry);

    ValidationResult result =
        validator.validateFile(
            ValidateFileRequest.builder()
                .projectRoot(Path.of("/tmp/project"))
                .filePath(Path.of("main.py"))
                .language(Language.PYTHON)
                .level(ValidationLevel.TYPE)
                .build());

    assertThat(result.getVerdict()).isEqualTo(ValidationVerdict.UNMEASURED);
    assertThat(result.isMeasured()).isFalse();
    assertThat(result.getEvidenceStatus()).isEqualTo("NOT_VERIFIED");
    assertThat(result.getUnmeasuredReason()).contains("unavailable");
  }

  @Test
  void unknownProjectLanguageIsUnmeasuredNotPass() throws Exception {
    when(python.getLanguage()).thenReturn(Language.PYTHON);
    when(python.isAvailable()).thenReturn(true);
    CodeValidatorImpl validator = new CodeValidatorImpl(gateChain, List.of(python), meterRegistry);
    Path root = Files.createTempDirectory("validator-unknown-");

    ValidationResult result =
        validator.validateProject(ValidateProjectRequest.builder().projectRoot(root).build());

    assertThat(result.getVerdict()).isEqualTo(ValidationVerdict.UNMEASURED);
    assertThat(result.isMeasured()).isFalse();
    assertThat(result.getEvidenceStatus()).isEqualTo("NOT_VERIFIED");
    assertThat(result.getUnmeasuredReason()).contains("could not be detected");
  }

  @Test
  void realGateFailureRemainsFailure() {
    when(python.getLanguage()).thenReturn(Language.PYTHON);
    when(python.isAvailable()).thenReturn(true);
    GateResult failure =
        GateResult.builder()
            .gate(ValidationGate.SYNTAX)
            .passed(false)
            .errors(List.of())
            .rawOutput("syntax error")
            .build();
    when(gateChain.execute(
            org.mockito.ArgumentMatchers.eq(python),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            ValidationResult.builder()
                .verdict(ValidationVerdict.FAIL)
                .errors(List.of())
                .warnings(List.of())
                .gateResults(java.util.Map.of(ValidationGate.SYNTAX, failure))
                .measured(true)
                .evidenceStatus("VERIFIED")
                .build());
    CodeValidatorImpl validator = new CodeValidatorImpl(gateChain, List.of(python), meterRegistry);

    ValidationResult result =
        validator.validateFile(
            ValidateFileRequest.builder()
                .projectRoot(Path.of("/tmp/project"))
                .filePath(Path.of("main.py"))
                .language(Language.PYTHON)
                .build());

    assertThat(result.getVerdict()).isEqualTo(ValidationVerdict.FAIL);
    assertThat(result.isMeasured()).isTrue();
  }
}
