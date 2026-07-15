package com.navneet.executespec.validator.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.navneet.executespec.validator.language.LanguageToolchain;
import com.navneet.executespec.validator.model.GateResult;
import com.navneet.executespec.validator.model.ValidationGate;
import com.navneet.executespec.validator.model.ValidationLevel;
import com.navneet.executespec.validator.model.ValidationVerdict;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GateChainTest {

  @Mock private LanguageToolchain toolchain;

  @Test
  void skippedGateProducesUnmeasuredResultAndDoesNotLookLikePass() {
    when(toolchain.syntaxCheck(Path.of("/tmp/project"), Path.of("main.py"), Duration.ofSeconds(5)))
        .thenReturn(measured(ValidationGate.SYNTAX));
    when(toolchain.typeCheck(Path.of("/tmp/project"), Path.of("main.py"), Duration.ofSeconds(5)))
        .thenReturn(GateResult.unmeasured(ValidationGate.TYPE_CHECK, "mypy unavailable"));

    var result =
        new GateChain()
            .execute(
                toolchain,
                Path.of("/tmp/project"),
                Path.of("main.py"),
                ValidationLevel.FULL,
                Duration.ofSeconds(5));

    assertThat(result.getVerdict()).isEqualTo(ValidationVerdict.UNMEASURED);
    assertThat(result.isMeasured()).isFalse();
    assertThat(result.getEvidenceStatus()).isEqualTo("NOT_VERIFIED");
    assertThat(result.getUnmeasuredReason()).contains("TYPE_CHECK");
    assertThat(result.getGateResults()).containsKey(ValidationGate.TYPE_CHECK);
  }

  private static GateResult measured(ValidationGate gate) {
    return GateResult.builder().gate(gate).passed(true).errors(List.of()).rawOutput("ok").build();
  }
}
