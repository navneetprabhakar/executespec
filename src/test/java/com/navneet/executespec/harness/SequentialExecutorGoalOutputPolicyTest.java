package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SequentialExecutorGoalOutputPolicyTest {

  @Test
  void codeChangingCategoriesRequireMeaningfulDiff() {
    assertThat(SequentialExecutor.requiresMeaningfulDiff("PROJECT_PATCH")).isTrue();
    assertThat(SequentialExecutor.requiresMeaningfulDiff("BUG_FIX")).isTrue();
    assertThat(SequentialExecutor.requiresMeaningfulDiff("TEST_FIX")).isTrue();
    assertThat(SequentialExecutor.requiresMeaningfulDiff("REFACTOR")).isTrue();
    assertThat(SequentialExecutor.requiresMeaningfulDiff("GREENFIELD_BUILD")).isTrue();
  }

  @Test
  void readOnlyHelpCanCompleteWithoutAProductDiff() {
    assertThat(SequentialExecutor.requiresMeaningfulDiff("READ_ONLY_HELP")).isFalse();
  }
}
