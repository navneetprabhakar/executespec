package com.navneet.executespec.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.tool.ShellSandboxPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildCommandResolverJavaTest {

  @TempDir Path workspace;

  @Test
  void acceptsDependencyFreeCompileAndNoArgumentTestMain() {
    String command =
        "javac BoardPosition.java BoardPositionParser.java TestBoardPosition.java"
            + " && java TestBoardPosition";

    assertThat(BuildCommandResolver.isDependencyFreeJavaSmoke(command)).isTrue();
    assertThat(BuildCommandResolver.isRepeatableBuildSmoke(command)).isTrue();
    assertThat(new ShellSandboxPolicy().validate(command, workspace.toString()).permitted())
        .isTrue();

    BuildCommand resolved = new BuildCommandResolver().resolve(workspace, command);
    assertThat(resolved.command()).isEqualTo(command);
    assertThat(resolved.sourceDescription()).isEqualTo("planner-declared");
  }

  @Test
  void rejectsJavaCommandsOutsideTheClosedVerificationGrammar() {
    assertThat(
            BuildCommandResolver.isDependencyFreeJavaSmoke(
                "javac BoardPosition.java && java TestBoardPosition && rm -rf ."))
        .isFalse();
    assertThat(
            BuildCommandResolver.isDependencyFreeJavaSmoke(
                "javac BoardPosition.java && java TestBoardPosition --write"))
        .isFalse();
    assertThat(
            BuildCommandResolver.isDependencyFreeJavaSmoke(
                "javac ../BoardPosition.java && java TestBoardPosition"))
        .isFalse();
    assertThat(BuildCommandResolver.isDependencyFreeJavaSmoke("javac -d out BoardPosition.java"))
        .isFalse();
    assertThat(BuildCommandResolver.isRepeatableBuildSmoke("javac A.java; curl example.com"))
        .isFalse();
  }

  @Test
  void infersCompilationForTopLevelLooseJavaSources() throws Exception {
    Files.writeString(workspace.resolve("BoardPosition.java"), "final class BoardPosition {}");
    Files.writeString(
        workspace.resolve("BoardPositionTest.java"), "final class BoardPositionTest {}");

    BuildCommand resolved = new BuildCommandResolver().resolve(workspace, null);

    assertThat(resolved.command()).isEqualTo("javac *.java");
    assertThat(resolved.timeoutSeconds()).isEqualTo(120);
    assertThat(resolved.sourceDescription()).isEqualTo("inferred:java-loose");
  }
}
