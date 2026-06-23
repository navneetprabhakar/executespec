package com.navneet.executespec.deployment.preview;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewPythonSignalsTest {

  @Test
  void cloudRunCommandPrefersDjangoManagePy(@TempDir Path workspace) throws IOException {
    Files.writeString(workspace.resolve("requirements.txt"), "django==5.0\n");
    Files.writeString(workspace.resolve("manage.py"), "import django\n");

    assertThat(PreviewPythonSignals.cloudRunCommand(workspace, "${PORT:-8080}"))
        .isEqualTo("CMD python manage.py runserver 0.0.0.0:${PORT:-8080}");
  }

  @Test
  void cloudRunCommandUsesFastApiModuleWhenSourceDeclaresApp(@TempDir Path workspace)
      throws IOException {
    Files.writeString(
        workspace.resolve("api.py"), "from fastapi import FastAPI\napp = FastAPI()\n");

    assertThat(PreviewPythonSignals.cloudRunCommand(workspace, "8081"))
        .isEqualTo("CMD uvicorn api:app --host 0.0.0.0 --port 8081");
  }

  @Test
  void cloudRunCommandFallsBackToMainModuleWhenFastApiOnlyAppearsInDependencies(
      @TempDir Path workspace) throws IOException {
    Files.writeString(workspace.resolve("requirements.txt"), "fastapi==0.110\n");

    assertThat(PreviewPythonSignals.cloudRunCommand(workspace, "8081"))
        .isEqualTo("CMD uvicorn main:app --host 0.0.0.0 --port 8081");
  }

  @Test
  void dockerCommandSetsFlaskAppWhenSourceDeclaresFlask(@TempDir Path workspace)
      throws IOException {
    Files.writeString(workspace.resolve("requirements.txt"), "flask==3.0\n");
    Files.writeString(
        workspace.resolve("server.py"), "from flask import Flask\napp = Flask(__name__)\n");

    PreviewPythonSignals.DockerCommand command = PreviewPythonSignals.dockerCommand(workspace);

    assertThat(command.envLine()).isEqualTo("ENV FLASK_APP=server.py\n");
    assertThat(command.command()).contains("flask", "run", "--host", "0.0.0.0");
  }

  @Test
  void dockerCommandUsesEntryPointPriorityForPlainPython(@TempDir Path workspace)
      throws IOException {
    Files.writeString(workspace.resolve("server.py"), "print('server')\n");
    Files.writeString(workspace.resolve("app.py"), "print('app')\n");

    PreviewPythonSignals.DockerCommand command = PreviewPythonSignals.dockerCommand(workspace);

    assertThat(command.command()).isEqualTo("CMD [\"python\", \"app.py\"]");
    assertThat(command.envLine()).isEmpty();
  }

  @Test
  void entryPointFallsBackToMainGuardWhenCommonNamesAreMissing(@TempDir Path workspace)
      throws IOException {
    Files.writeString(workspace.resolve("worker.py"), "if __name__ == '__main__':\n    pass\n");

    assertThat(PreviewPythonSignals.findEntryPoint(workspace)).isEqualTo("worker.py");
  }

  @Test
  void dependencyScanReadsRequirementsAndPyproject(@TempDir Path workspace) throws IOException {
    Files.writeString(workspace.resolve("requirements.txt"), "requests==2.31\n");
    Files.writeString(
        workspace.resolve("pyproject.toml"), "[project]\ndependencies = ['fastapi']\n");

    assertThat(PreviewPythonSignals.hasDependency(workspace, "requests")).isTrue();
    assertThat(PreviewPythonSignals.hasDependency(workspace, "fastapi")).isTrue();
    assertThat(PreviewPythonSignals.hasDependency(workspace, "flask")).isFalse();
  }
}
