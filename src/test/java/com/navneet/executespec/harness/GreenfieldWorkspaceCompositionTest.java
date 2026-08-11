package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.smoketest.BuildCommandResolver;
import com.navneet.executespec.smoketest.TestCommandResolver;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;
import com.navneet.executespec.tool.BuildProjectTool;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.workspacegeneration.GreenfieldBaselinePlan;
import com.navneet.executespec.workspacegeneration.GreenfieldBaselineService;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceContract;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceContractResolver;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceVerifier;
import com.navneet.executespec.workspacegeneration.WorkspaceOrigin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GreenfieldWorkspaceCompositionTest {

  @TempDir Path workspaceRoot;

  @Test
  void initializedGreenfieldMetadataCannotTurnTheRunIntoBrownfield() {
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspaceRoot.toString());
    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    WorkspaceInitService workspaces =
        new WorkspaceInitService(
            properties,
            mock(WorkspaceRestoreService.class),
            snapshots,
            mock(VendoredPathGuard.class),
            mock(BrownfieldHttpContractGuard.class),
            new WorkspaceRepositoryRevisionService(properties, snapshots));
    workspaces.ensureRunWorkspace("run-greenfield");
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Build FastAPI")
            .description("Build Python FastAPI")
            .requiredArtifacts(List.of("main.py", "requirements.txt"))
            .build();

    GreenfieldBaselinePlan baseline =
        new GreenfieldBaselineService()
            .plan(workspaces.runWorkspacePath("run-greenfield"), task, WorkspaceOrigin.GREENFIELD);

    assertThat(baseline.greenfield()).isTrue();
    assertThat(baseline.kind()).isEqualTo(GreenfieldBaselinePlan.Kind.PYTHON);
    assertThat(baseline.requiredTaskPaths()).containsExactly("main.py", "requirements.txt");
    assertThat(baseline.filesToCreate())
        .containsKeys(
            ".executespec/bootstrap/python.marker", ".executespec/bootstrap/test_acceptance.py")
        .doesNotContainKey("requirements.txt");
  }

  @Test
  void exactFastApiShapeReachesExecutableRedAndAcceptsARealScriptedCandidate() throws Exception {
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspaceRoot.toString());
    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    WorkspaceInitService workspaces =
        new WorkspaceInitService(
            properties,
            mock(WorkspaceRestoreService.class),
            snapshots,
            mock(VendoredPathGuard.class),
            mock(BrownfieldHttpContractGuard.class),
            new WorkspaceRepositoryRevisionService(properties, snapshots));
    workspaces.ensureRunWorkspace("run-fastapi");
    Path root = workspaces.runWorkspacePath("run-fastapi");
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Build FastAPI todo API in main.py")
            .description(
                "Implement GET /health, GET /todos, POST /todos and DELETE /todos/{id} in "
                    + "`main.py`; include `requirements.txt`.")
            .requiredArtifacts(List.of("main.py", "requirements.txt"))
            .build();
    Plan plan =
        Plan.builder()
            .planTitle("FastAPI todo")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .projectType("python")
            .tasks(List.of(task))
            .build();
    GreenfieldBaselineService baselines = new GreenfieldBaselineService();
    GreenfieldBaselinePlan baseline = baselines.plan(root, task, plan, WorkspaceOrigin.GREENFIELD);
    BuildCommandResolver buildCommands = new BuildCommandResolver();
    TestCommandResolver testCommands = new TestCommandResolver();
    ObjectMapper json = new ObjectMapper();
    TaskAcceptanceContract contract =
        new TaskAcceptanceContractResolver(json, buildCommands, testCommands)
            .resolveFailureFirst(task, plan, 0, root, baseline);

    baselines.materialize(root, baseline);
    TaskAcceptanceVerifier.InitialFailure initial =
        new TaskAcceptanceVerifier(
                buildCommands,
                testCommands,
                mock(BuildProjectTool.class),
                mock(WorkspaceTestRunner.class),
                json)
            .probeInitial(new ExecutionContext("run-fastapi", "T1", root), contract);

    assertThat(contract.buildCommand().source()).isEqualTo("inferred:python");
    assertThat(contract.testCommand().source()).isEqualTo("executespec-bootstrap-pytest");
    assertThat(initial.observed()).isTrue();
    assertThat(initial.code()).isEqualTo("ENDPOINT_CONTRACT_UNSATISFIED");

    Files.writeString(
        root.resolve("main.py"),
        """
        from fastapi import FastAPI, HTTPException
        from pydantic import BaseModel

        app = FastAPI()
        todos = []
        next_id = 1

        class TodoInput(BaseModel):
            title: str

        @app.get("/health")
        def health():
            return {"status": "ok"}

        @app.get("/todos")
        def list_todos():
            return todos

        @app.post("/todos")
        def create_todo(payload: TodoInput):
            global next_id
            todo = {"id": next_id, "title": payload.title, "done": False}
            next_id += 1
            todos.append(todo)
            return todo

        @app.delete("/todos/{id}")
        def delete_todo(id: int):
            for index, todo in enumerate(todos):
                if todo["id"] == id:
                    todos.pop(index)
                    return {"deleted": True}
            raise HTTPException(status_code=404, detail="todo not found")
        """);
    Files.writeString(root.resolve("requirements.txt"), "fastapi\nuvicorn\n");
    Files.writeString(
        root.resolve("openapi.yaml"),
        """
        openapi: 3.0.0
        paths:
          /health:
            get: {}
          /todos:
            get: {}
            post: {}
          /todos/{id}:
            delete: {}
        """);
    Files.writeString(
        root.resolve("held_out_test.py"),
        """
        from fastapi.testclient import TestClient
        from main import app

        client = TestClient(app)
        assert client.get("/health").json() == {"status": "ok"}
        assert client.get("/todos").json() == []
        first = client.post("/todos", json={"title": "first"})
        assert first.status_code == 200
        assert first.json() == {"id": 1, "title": "first", "done": False}
        second = client.post("/todos", json={"title": "second"})
        assert second.json()["id"] == 2
        assert client.delete("/todos/1").json() == {"deleted": True}
        assert client.delete("/todos/999").status_code == 404
        """);

    assertThat(run(root, contract.buildCommand().command())).isZero();
    assertThat(run(root, contract.testCommand().command())).isZero();
    assertThat(run(root, "python3 held_out_test.py")).isZero();
  }

  @Test
  void nodeJavascriptAdapterUsesExecutableSyntaxAndHiddenPathChecks() throws Exception {
    Path root = workspaceRoot.resolve("node-run");
    Files.createDirectories(root);
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Build Node slug utility")
            .description("Export slugify from `src/slug.mjs` and include edge handling.")
            .requiredArtifacts(List.of("src/slug.mjs"))
            .build();
    Plan plan =
        Plan.builder()
            .planTitle("Node slug utility")
            .runtimeSurface(Plan.RuntimeSurface.LIBRARY)
            .projectType("node-backend")
            .tasks(List.of(task))
            .build();
    GreenfieldBaselineService baselines = new GreenfieldBaselineService();
    GreenfieldBaselinePlan baseline = baselines.plan(root, task, plan, WorkspaceOrigin.GREENFIELD);
    BuildCommandResolver buildCommands = new BuildCommandResolver();
    TestCommandResolver testCommands = new TestCommandResolver();
    TaskAcceptanceContract contract =
        new TaskAcceptanceContractResolver(new ObjectMapper(), buildCommands, testCommands)
            .resolveFailureFirst(task, plan, 0, root, baseline);

    assertThat(baseline.kind()).isEqualTo(GreenfieldBaselinePlan.Kind.NODE_JAVASCRIPT);
    assertThat(baseline.supported()).isTrue();
    assertThat(baseline.filesToCreate().keySet())
        .containsExactlyInAnyOrder(
            ".executespec/bootstrap/node-js.marker", ".executespec/bootstrap/test_acceptance.mjs");
    assertThat(contract.buildCommand().command()).isEqualTo("node --check src/slug.mjs");
    assertThat(contract.buildCommand().source()).isEqualTo("executespec-bootstrap-node-check");
    assertThat(contract.testCommand().command())
        .isEqualTo("node --test .executespec/bootstrap/test_acceptance.mjs");

    baselines.materialize(root, baseline);
    assertThat(buildCommands.resolve(root, null).command())
        .isEqualTo(contract.buildCommand().command());
    assertThat(testCommands.resolve(root).orElseThrow().command())
        .isEqualTo(contract.testCommand().command());
    Files.createDirectories(root.resolve("src"));
    Files.writeString(
        root.resolve("src/slug.mjs"),
        "export const slugify = value => value.trim().toLowerCase().replace(/[^a-z0-9]+/g,"
            + " '-').replace(/^-|-$/g, '');\n");
    Files.writeString(
        root.resolve("held_out_test.mjs"),
        "import assert from 'node:assert/strict';\n"
            + "import {slugify} from './src/slug.mjs';\n"
            + "assert.equal(slugify('  Hello, World!  '), 'hello-world');\n"
            + "assert.equal(slugify('---'), '');\n");

    assertThat(run(root, contract.buildCommand().command())).isZero();
    assertThat(run(root, contract.testCommand().command())).isZero();
    assertThat(run(root, "node held_out_test.mjs")).isZero();
  }

  private static int run(Path root, String command) throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder("/bin/sh", "-c", command)
            .directory(root.toFile())
            .redirectErrorStream(true);
    builder
        .environment()
        .put("PYTHONPYCACHEPREFIX", root.resolve(".executespec/bootstrap/pycache").toString());
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes());
    int exit = process.waitFor();
    assertThat(exit).as("command=%s output=%s", command, output).isZero();
    return exit;
  }
}
