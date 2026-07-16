package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PromptResourceReaderTest {

  @Test
  void readsExistingPromptResource() {
    assertThat(PromptResourceReader.readOptional("prompts/fragments", "api-url-rules"))
        .isNotBlank();
  }

  @Test
  void missingResourceIsAnAbsentOptionalOverlay() {
    assertThat(PromptResourceReader.readOptional("prompts/fragments", "does-not-exist")).isEmpty();
  }

  @Test
  void dynamicKeysCannotEscapeOneClasspathSegment() {
    assertThat(PromptResourceReader.readOptional("prompts/fragments", "../application")).isEmpty();
    assertThat(PromptResourceReader.readOptional("prompts/fragments", "provider/foo/bar"))
        .isEmpty();
  }

  @Test
  void onlySafeLowercaseSegmentsAreAccepted() {
    assertThat(PromptResourceReader.isSafeSegment("node-fullstack")).isTrue();
    assertThat(PromptResourceReader.isSafeSegment("Node_Fullstack")).isFalse();
    assertThat(PromptResourceReader.isSafeSegment("-bad")).isFalse();
  }
}
