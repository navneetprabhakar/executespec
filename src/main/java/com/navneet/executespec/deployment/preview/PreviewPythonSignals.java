package com.navneet.executespec.deployment.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class PreviewPythonSignals {

  private static final long MAX_FILE_SIZE = 50 * 1024;
  private static final int MAX_PYTHON_FILES = 20;

  private PreviewPythonSignals() {}

  record DockerCommand(String command, String envLine) {}

  static String cloudRunCommand(Path workspace, String portExpr) {
    if (Files.exists(workspace.resolve("manage.py"))) {
      return "CMD python manage.py runserver 0.0.0.0:" + portExpr;
    }
    String fastApiFile = findPythonFileWithPattern(workspace, "FastAPI(");
    if (fastApiFile != null) {
      return "CMD uvicorn " + moduleName(fastApiFile) + ":app --host 0.0.0.0 --port " + portExpr;
    }
    if (hasDependency(workspace, "fastapi")) {
      return "CMD uvicorn main:app --host 0.0.0.0 --port " + portExpr;
    }
    String flaskFile = findPythonFileWithPattern(workspace, "Flask(");
    if (flaskFile != null) {
      return "CMD FLASK_APP=" + flaskFile + " flask run --host 0.0.0.0 --port " + portExpr;
    }
    if (hasDependency(workspace, "flask")) {
      return "CMD flask run --host 0.0.0.0 --port " + portExpr;
    }
    String fallbackFastApi = findPythonFileWithPattern(workspace, "from fastapi", "import fastapi");
    if (fallbackFastApi != null) {
      return "CMD uvicorn "
          + moduleName(fallbackFastApi)
          + ":app --host 0.0.0.0 --port "
          + portExpr;
    }
    String fallbackFlask = findPythonFileWithPattern(workspace, "from flask", "import flask");
    if (fallbackFlask != null) {
      return "CMD FLASK_APP=" + fallbackFlask + " flask run --host 0.0.0.0 --port " + portExpr;
    }
    return "CMD python " + findEntryPoint(workspace);
  }

  static DockerCommand dockerCommand(Path workspace) {
    if (Files.exists(workspace.resolve("manage.py"))) {
      return new DockerCommand(
          """
          CMD ["python", "manage.py", "runserver", "0.0.0.0:8000"]""",
          "");
    }
    if (hasDependency(workspace, "fastapi")) {
      String fastApiFile = findPythonFileWithPattern(workspace, "FastAPI(");
      String module = fastApiFile != null ? moduleName(fastApiFile) : "main";
      return new DockerCommand(uvicornDockerCommand(module), "");
    }
    if (hasDependency(workspace, "flask")) {
      String flaskFile = findPythonFileWithPattern(workspace, "Flask(");
      String envLine = flaskFile != null ? "ENV FLASK_APP=" + flaskFile + "\n" : "";
      return new DockerCommand(flaskDockerCommand(), envLine);
    }
    String fastApiFile = findPythonFileWithPattern(workspace, "from fastapi", "import fastapi");
    if (fastApiFile != null) {
      return new DockerCommand(uvicornDockerCommand(moduleName(fastApiFile)), "");
    }
    String flaskFile = findPythonFileWithPattern(workspace, "from flask", "import flask");
    if (flaskFile != null) {
      return new DockerCommand(flaskDockerCommand(), "ENV FLASK_APP=" + flaskFile + "\n");
    }
    return new DockerCommand("CMD [\"python\", \"" + findEntryPoint(workspace) + "\"]", "");
  }

  static boolean hasDependency(Path workspace, String packageName) {
    Path reqFile = workspace.resolve("requirements.txt");
    if (Files.exists(reqFile) && fileContainsPattern(reqFile, packageName)) {
      return true;
    }
    Path pyproject = workspace.resolve("pyproject.toml");
    return Files.exists(pyproject) && fileContainsPattern(pyproject, packageName);
  }

  static List<Path> findPythonFiles(Path workspace) {
    try (Stream<Path> stream = Files.walk(workspace, 4)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".py"))
          .filter(p -> !isIgnoredPythonPath(workspace, p))
          .filter(PreviewPythonSignals::isSmallEnoughForScan)
          .sorted(Comparator.comparing(p -> workspace.relativize(p).toString()))
          .limit(MAX_PYTHON_FILES)
          .toList();
    } catch (IOException e) {
      return List.of();
    }
  }

  static String findPythonFileWithPattern(Path workspace, String... patterns) {
    for (String candidate :
        new String[] {
          "main.py",
          "app.py",
          "server.py",
          "run.py",
          "app/main.py",
          "app/app.py",
          "app/server.py",
          "src/main.py",
          "src/app.py",
          "src/server.py"
        }) {
      Path path = workspace.resolve(candidate);
      if (Files.isRegularFile(path) && fileContainsPattern(path, patterns)) return candidate;
    }
    for (Path pyFile : findPythonFiles(workspace)) {
      if (fileContainsPattern(pyFile, patterns)) {
        return workspace.relativize(pyFile).toString().replace('\\', '/');
      }
    }
    return null;
  }

  static String findEntryPoint(Path workspace) {
    String[] candidates = {"main.py", "app.py", "run.py", "server.py", "wsgi.py", "index.py"};
    for (String name : candidates) {
      if (Files.exists(workspace.resolve(name))) return name;
    }
    for (String directory : new String[] {"app", "src"}) {
      for (String name : candidates) {
        Path nested = workspace.resolve(directory).resolve(name);
        if (Files.exists(nested)) return workspace.relativize(nested).toString().replace('\\', '/');
      }
    }
    String mainFile = findPythonFileWithPattern(workspace, "if __name__");
    if (mainFile != null) return mainFile;
    return "app.py";
  }

  private static boolean isSmallEnoughForScan(Path path) {
    try {
      return Files.size(path) <= MAX_FILE_SIZE;
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean isIgnoredPythonPath(Path workspace, Path path) {
    Path relative = workspace.relativize(path);
    for (Path segment : relative) {
      String value = segment.toString();
      if (value.equals(".git")
          || value.equals(".venv")
          || value.equals("venv")
          || value.equals("site-packages")
          || value.equals("__pycache__")) return true;
    }
    return false;
  }

  private static boolean fileContainsPattern(Path file, String... patterns) {
    try {
      if (Files.size(file) > MAX_FILE_SIZE) return false;
      String content = Files.readString(file);
      for (String pattern : patterns) {
        assert pattern.indexOf('|') < 0
            : "fileContainsPattern pattern '"
                + pattern
                + "' contains '|' - use varargs OR semantics instead";
        if (content.contains(pattern)) return true;
      }
    } catch (IOException e) {
      return false;
    }
    return false;
  }

  static String moduleName(String fileName) {
    String module = fileName.replace('\\', '/');
    if (module.endsWith(".py")) module = module.substring(0, module.length() - 3);
    return module.replace('/', '.');
  }

  private static String uvicornDockerCommand(String module) {
    return "CMD [\"uvicorn\", \""
        + module
        + ":app\", \"--host\", \"0.0.0.0\", \"--port\", \"8000\"]";
  }

  private static String flaskDockerCommand() {
    return """
           CMD ["flask", "run", "--host", "0.0.0.0", "--port", "8000"]""";
  }
}
