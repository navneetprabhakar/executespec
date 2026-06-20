package com.navneet.executespec.deployment.preview;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class PreviewDependencySignalsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void identifiesBackendFrameworkDependencies() throws Exception {
    JsonNode deps = deps("{\"express\":\"^4\",\"@nestjs/core\":\"^10\"}");

    assertThat(PreviewDependencySignals.hasBackendDependency(deps)).isTrue();
    assertThat(PreviewDependencySignals.hasBackendDependency(deps("{\"react\":\"^18\"}")))
        .isFalse();
  }

  @Test
  void identifiesBroadFrontendDependencies() throws Exception {
    assertThat(PreviewDependencySignals.hasFrontendDependency(deps("{\"react\":\"^18\"}")))
        .isTrue();
    assertThat(PreviewDependencySignals.hasFrontendDependency(deps("{\"vite\":\"^5\"}"))).isTrue();
    assertThat(PreviewDependencySignals.hasFrontendDependency(deps("{\"express\":\"^4\"}")))
        .isFalse();
  }

  @Test
  void preservesNarrowMonorepoFrontendSignal() throws Exception {
    assertThat(
            PreviewDependencySignals.hasMonorepoFrontendSignal(
                deps("{\"react\":\"^18\"}"), deps("{}")))
        .isTrue();
    assertThat(
            PreviewDependencySignals.hasMonorepoFrontendSignal(
                deps("{}"), deps("{\"@vitejs/plugin-react\":\"latest\"}")))
        .isTrue();
    assertThat(
            PreviewDependencySignals.hasMonorepoFrontendSignal(
                deps("{\"next\":\"latest\"}"), deps("{}")))
        .isFalse();
  }

  @Test
  void identifiesPreviewDirectoryNames() {
    assertThat(PreviewDependencySignals.isFrontendDirName("frontend")).isTrue();
    assertThat(PreviewDependencySignals.isFrontendDirName("service")).isFalse();
    assertThat(PreviewDependencySignals.isBackendDirName("service")).isTrue();
    assertThat(PreviewDependencySignals.isBackendDirName("client")).isFalse();
  }

  private static JsonNode deps(String json) throws Exception {
    return MAPPER.readTree(json);
  }
}
