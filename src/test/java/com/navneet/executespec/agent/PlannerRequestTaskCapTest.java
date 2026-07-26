package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlannerRequestTaskCapTest {

  @TempDir Path workspace;

  @Test
  void largePlansCapFanOutWhileLegacyRetainsMigrationScale() {
    assertThat(request(PlanStrategy.LARGE).plannerMaxTasks()).isEqualTo(16);
    assertThat(request(PlanStrategy.LEGACY).plannerMaxTasks())
        .isEqualTo(PlanStrategy.LEGACY.maxTasks());
  }

  private PlannerRequest request(PlanStrategy strategy) {
    return PlannerRequest.builder()
        .spec("Build a product")
        .classification(Classification.MULTI_TASK)
        .planStrategy(strategy)
        .provider("deepseek")
        .model("deepseek-v4-pro-native")
        .context(new ExecutionContext("run-1", "_planner_", workspace))
        .build();
  }
}
