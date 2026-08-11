package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real process, network-none qualification of the digest-identified Python runtime kit. */
@Tag("integration")
class PythonHttpRuntimeCleanroomIntegrationTest {

  @TempDir Path temporary;

  @Test
  void realCrudContractPassesAndEmitsStatefulRuntimeEvidence() throws Exception {
    Result result =
        run(
            "crud",
            "Implement GET /health, POST /todos, GET /todos, GET /todos/{id}, and DELETE"
                + " /todos/{id}.",
            """
            from fastapi import FastAPI, HTTPException
            from pydantic import BaseModel

            app = FastAPI()
            todos = []
            next_id = 1

            class TodoInput(BaseModel):
                title: str

            class Todo(BaseModel):
                id: int
                title: str

            @app.get("/health")
            def health():
                return {"status": "ok"}

            @app.post("/todos", response_model=Todo)
            def create_todo(payload: TodoInput):
                global next_id
                todo = {"id": next_id, "title": payload.title}
                next_id += 1
                todos.append(todo)
                return todo

            @app.get("/todos", response_model=list[Todo])
            def list_todos():
                return todos

            @app.get("/todos/{todo_id}", response_model=Todo)
            def get_todo(todo_id: int):
                for todo in todos:
                    if todo["id"] == todo_id:
                        return todo
                raise HTTPException(status_code=404, detail="not found")

            @app.delete("/todos/{todo_id}")
            def delete_todo(todo_id: int):
                for index, todo in enumerate(todos):
                    if todo["id"] == todo_id:
                        todos.pop(index)
                        return {"deleted": True}
                raise HTTPException(status_code=404, detail="not found")
            """);

    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(result.output())
        .contains("EXECUTESPEC_RUNTIME_KIT=python-http-v1")
        .contains("runtime_request")
        .contains("runtime_absence_check")
        .contains("2 passed");
  }

  @Test
  void fabricatedDecoratorAndStaticOpenApiCannotPass() throws Exception {
    Result result =
        run(
            "fake",
            """
            from pathlib import Path
            Path("openapi.json").write_text('{"openapi":"3.0.0","paths":{"/health":{"get":{}}}}')

            class FakeApp:
                def get(self, path):
                    return lambda function: function

            app = FakeApp()

            @app.get("/health")
            def health():
                return {"status": "fake"}
            """);

    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).containsAnyOf("exited before readiness", "readiness timed out");
  }

  @Test
  void importFailureCannotPass() throws Exception {
    Result result = run("import-failure", "import package_that_does_not_exist\n");

    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("exited before readiness");
  }

  @Test
  void runtimeCrashCannotPass() throws Exception {
    Result result =
        run(
            "crash",
            """
            from fastapi import FastAPI
            app = FastAPI()

            @app.get("/health")
            def health():
                raise RuntimeError("broken")
            """);

    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("runtime route crashed: GET /health: 500");
  }

  @Test
  void statusAbsentFromTheLiveContractCannotPass() throws Exception {
    Result result =
        run(
            "undeclared-status",
            """
from fastapi import FastAPI, Response
app = FastAPI()

@app.get("/health")
def health():
    return Response(content='{"status":"ok"}', status_code=202, media_type="application/json")
""");

    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output())
        .contains("runtime status is absent from live OpenAPI: GET /health: 202");
  }

  @Test
  void responseThatViolatesTheLiveSchemaCannotPass() throws Exception {
    Result result =
        run(
            "schema-mismatch",
            """
            from fastapi import FastAPI
            from pydantic import BaseModel
            app = FastAPI()

            class Health(BaseModel):
                status: str
                checks: list[str]

            @app.get("/health", responses={200: {"model": Health}})
            def health():
                return {"status": 7, "checks": "not-a-list"}
            """);

    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("GET /health violates declared schema");
  }

  @Test
  void nonPersistentMutationCannotPass() throws Exception {
    Result result =
        run(
            "non-persistent",
            "Implement GET /health, POST /todos, GET /todos, GET /todos/{id}, and DELETE"
                + " /todos/{id}.",
            """
            from fastapi import FastAPI, HTTPException
            from pydantic import BaseModel
            app = FastAPI()

            class TodoInput(BaseModel):
                title: str

            @app.post("/todos")
            def create_todo(payload: TodoInput):
                return {"id": 1, "title": payload.title}

            @app.get("/todos")
            def list_todos():
                return []

            @app.get("/todos/{todo_id}")
            def get_todo(todo_id: int):
                raise HTTPException(status_code=404, detail="not found")

            @app.delete("/todos/{todo_id}")
            def delete_todo(todo_id: int):
                return {"deleted": True}

            @app.get("/health")
            def health():
                return {"status": "ok"}
            """);

    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("created resource 1 is absent from list response");
  }

  @Test
  void differentLiteralRouteCannotPassStructuralNormalization() throws Exception {
    Result result =
        run(
            "literal-mismatch",
            """
            from fastapi import FastAPI
            app = FastAPI()

            @app.get("/status")
            def status():
                return {"status": "ok"}
            """);

    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).contains("runtime route missing: GET /health");
  }

  private Result run(String name, String application) throws Exception {
    return run(name, "Implement GET /health.", application);
  }

  private Result run(String name, String endpointContract, String application) throws Exception {
    String image =
        System.getProperty(
            "executespec.sandbox.toolchain.image",
            System.getenv("EXECUTESPEC_SANDBOX_TOOLCHAIN_IMAGE"));
    Assumptions.assumeTrue(
        image != null && (image.startsWith("sha256:") || image.contains("@sha256:")),
        "set EXECUTESPEC_SANDBOX_TOOLCHAIN_IMAGE to a content-addressed validated image");

    Path workspace = temporary.resolve(name);
    Files.createDirectories(workspace);
    Files.writeString(workspace.resolve("main.py"), application);
    Files.writeString(
        workspace.resolve("requirements.txt"), "fastapi==0.115.12\nuvicorn==0.34.3\n");
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("FastAPI todo runtime")
            .description(endpointContract)
            .requiredArtifacts(List.of("main.py", "requirements.txt"))
            .build();
    Plan plan =
        Plan.builder()
            .planTitle("Cleanroom runtime fixture")
            .projectType("python")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .tasks(List.of(task))
            .build();
    GreenfieldBaselineService baselines = new GreenfieldBaselineService();
    baselines.materialize(
        workspace, baselines.plan(workspace, task, plan, WorkspaceOrigin.GREENFIELD));
    makeContainerReadable(workspace);

    Process process =
        new ProcessBuilder(
                "docker",
                "run",
                "--rm",
                "--network",
                "none",
                "--user",
                "65532:65532",
                "--security-opt",
                "no-new-privileges",
                "--cap-drop",
                "ALL",
                "--read-only",
                "--pids-limit",
                "128",
                "--memory",
                "1g",
                "--memory-swap",
                "1g",
                "--cpus",
                "2",
                "--tmpfs",
                "/tmp:rw,noexec,nosuid,size=128m",
                "--volume",
                workspace + ":/workspace:rw",
                "--workdir",
                "/workspace",
                image,
                PythonHttpRuntimeAcceptanceService.RUNTIME_COMMAND)
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(Duration.ofMinutes(3).toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
      throw new AssertionError("cleanroom runtime fixture exceeded three minutes");
    }
    String output = new String(process.getInputStream().readAllBytes());
    return new Result(process.exitValue(), output, workspace);
  }

  private static void makeContainerReadable(Path root) throws Exception {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.toList()) {
        Files.setPosixFilePermissions(
            path,
            PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwxr-xr-x" : "rw-r--r--"));
      }
    }
  }

  private record Result(int exitCode, String output, Path workspace) {}
}
