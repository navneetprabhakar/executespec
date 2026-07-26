package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SequentialExecutorGoalOutputPolicyTest {

  @Test
  void codeChangingCategoriesRequireMeaningfulDiff() {
    assertThat(TaskCategoryClassifier.requiresMeaningfulDiff("PROJECT_PATCH")).isTrue();
    assertThat(TaskCategoryClassifier.requiresMeaningfulDiff("BUG_FIX")).isTrue();
    assertThat(TaskCategoryClassifier.requiresMeaningfulDiff("TEST_FIX")).isTrue();
    assertThat(TaskCategoryClassifier.requiresMeaningfulDiff("REFACTOR")).isTrue();
    assertThat(TaskCategoryClassifier.requiresMeaningfulDiff("GREENFIELD_BUILD")).isTrue();
  }

  @Test
  void readOnlyHelpCanCompleteWithoutAProductDiff() {
    assertThat(TaskCategoryClassifier.requiresMeaningfulDiff("READ_ONLY_HELP")).isFalse();
  }
}
