package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.nio.file.Path;

import com.navneet.executespec.harness.SequentialExecutor;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;

class CodeChangeLoopArchitectureTest {

  @Test
  void workerAgentIsTheSharedProviderNeutralLoopImplementation() {
    assertThat(CodeChangeLoop.class).isAssignableFrom(WorkerAgent.class);
  }

  @Test
  void buildAndPatchDependOnTheSharedLoopPortRatherThanConcreteWorker() throws Exception {
    assertThat(field(CodeGenEngineImpl.class, "codeChangeLoop").getType())
        .isEqualTo(CodeChangeLoop.class);
    assertThat(field(SequentialExecutor.class, "worker").getType()).isEqualTo(CodeChangeLoop.class);
  }

  @Test
  void modeIdentityIsAnExplicitPartOfTheSharedLoopRequest() {
    WorkerTaskRequest request =
        WorkerTaskRequest.builder()
            .provider("anthropic")
            .model("model")
            .userPrompt("task")
            .context(new ExecutionContext("run", "task", Path.of(".").toAbsolutePath()))
            .changeMode(CodeChangeMode.BUILD)
            .build();

    assertThat(request.changeMode()).isEqualTo(CodeChangeMode.BUILD);
  }

  private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
    return owner.getDeclaredField(name);
  }
}
