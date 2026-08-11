package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PythonRuntimeAcceptanceAdapterTest {

  @TempDir Path workspace;

  @BeforeEach
  void requireRuntime() throws Exception {
    Process check =
        new ProcessBuilder("python3", "-c", "import fastapi,uvicorn,pytest")
            .redirectErrorStream(true)
            .start();
    Assumptions.assumeTrue(check.waitFor(10, TimeUnit.SECONDS) && check.exitValue() == 0);
    Process socketCheck =
        new ProcessBuilder(
                "python3",
                "-c",
                "import socket; s=socket.socket(); s.bind(('127.0.0.1', 0)); s.close()")
            .redirectErrorStream(true)
            .start();
    Assumptions.assumeTrue(
        socketCheck.waitFor(10, TimeUnit.SECONDS) && socketCheck.exitValue() == 0,
        "local sandbox denies loopback sockets; the runtime adapter must run in its cleanroom");
  }

  @Test
  void runningFastApiRoutePassesRuntimeAcceptance() throws Exception {
    materialize(
        """
        from fastapi import FastAPI

        app = FastAPI()

        @app.get("/health")
        def health():
            return {"status": "ok"}
        """);

    Result result = runAcceptance();

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(result.output()).contains("2 passed");
  }

  @Test
  void fakeDecoratorAndFabricatedOpenApiCannotPass() throws Exception {
    materialize(
        """
        class FakeApp:
            def get(self, path):
                return lambda function: function

        app = FakeApp()

        @app.get("/health")
        def health():
            return {"status": "fake"}
        """);
    Files.writeString(
        workspace.resolve("openapi.json"),
        "{\"openapi\":\"3.0.0\",\"paths\":{\"/health\":{\"get\":{}}}}");

    Result result = runAcceptance();

    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).containsAnyOf("readiness timed out", "exited before readiness");
  }

  @Test
  void routeThatCrashesAtRuntimeIsRejected() throws Exception {
    materialize(
        """
        from fastapi import FastAPI

        app = FastAPI()

        @app.get("/health")
        def health():
            raise RuntimeError("broken behavior")
        """);

    Result result = runAcceptance();

    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("runtime route crashed: GET /health: 500");
  }

  private void materialize(String application) throws Exception {
    Files.writeString(workspace.resolve("main.py"), application);
    Files.writeString(workspace.resolve("requirements.txt"), "fastapi\nuvicorn\n");
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Health API")
            .description("Implement GET /health")
            .requiredArtifacts(List.of("main.py", "requirements.txt"))
            .build();
    Plan plan =
        Plan.builder()
            .planTitle("Runtime adapter fixture")
            .projectType("python")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .tasks(List.of(task))
            .build();
    GreenfieldBaselineService baselines = new GreenfieldBaselineService();
    GreenfieldBaselinePlan baseline =
        baselines.plan(workspace, task, plan, WorkspaceOrigin.GREENFIELD);
    baselines.materialize(workspace, baseline);
  }

  private Result runAcceptance() throws Exception {
    Process process =
        new ProcessBuilder(
                "python3", "-m", "pytest", "-q", ".executespec/bootstrap/test_acceptance.py")
            .directory(workspace.toFile())
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(3, TimeUnit.SECONDS);
      throw new AssertionError("runtime acceptance fixture exceeded 20 seconds");
    }
    return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes()));
  }

  private record Result(int exitCode, String output) {}
}
