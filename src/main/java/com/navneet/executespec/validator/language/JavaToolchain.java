package com.navneet.executespec.validator.language;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

import com.navneet.executespec.validator.execution.GenericTestOutputParser;
import com.navneet.executespec.validator.execution.JavacOutputParser;
import com.navneet.executespec.validator.execution.ProcessExecutor;
import com.navneet.executespec.validator.execution.ProcessExecutor.ProcessCommand;
import com.navneet.executespec.validator.execution.ProcessExecutor.ProcessResult;
import com.navneet.executespec.validator.model.GateResult;
import com.navneet.executespec.validator.model.Language;
import com.navneet.executespec.validator.model.ValidationError;
import com.navneet.executespec.validator.model.ValidationGate;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class JavaToolchain implements LanguageToolchain {

  private final ProcessExecutor processExecutor;
  private final JavacOutputParser javacOutputParser;
  private final GenericTestOutputParser testOutputParser;

  @Override
  public Language getLanguage() {
    return Language.JAVA;
  }

  @Override
  public GateResult syntaxCheck(Path projectRoot, Path filePath, Duration timeout) {
    // Java syntax check delegated to typeCheck (mvn compile) — can't compile single files in
    // isolation
    return GateResult.builder()
        .gate(ValidationGate.SYNTAX)
        .passed(true)
        .duration(Duration.ZERO)
        .errors(Collections.emptyList())
        .rawOutput("Java syntax check delegated to type check (mvn compile)")
        .build();
  }

  @Override
  public GateResult typeCheck(Path projectRoot, Path filePath, Duration timeout) {
    boolean isMaven = Files.exists(projectRoot.resolve("pom.xml"));
    boolean isGradle =
        Files.exists(projectRoot.resolve("build.gradle"))
            || Files.exists(projectRoot.resolve("build.gradle.kts"));

    List<String> args;
    if (isMaven) {
      args = List.of("mvn", "compile", "-q", "-B", "-f", projectRoot.resolve("pom.xml").toString());
    } else if (isGradle) {
      String gradleCmd = resolveGradleCommand(projectRoot);
      if (gradleCmd == null) {
        log.warn("Gradle project detected but no gradle/gradlew available — skipping type check");
        return GateResult.builder()
            .gate(ValidationGate.TYPE_CHECK)
            .passed(true)
            .duration(Duration.ZERO)
            .errors(Collections.emptyList())
            .rawOutput(
                "Gradle project detected but gradle/gradlew not available — skipping type check")
            .build();
      }
      args = List.of(gradleCmd, "compileJava", "-q", "--no-daemon");
    } else {
      return GateResult.builder()
          .gate(ValidationGate.TYPE_CHECK)
          .passed(true)
          .duration(Duration.ZERO)
          .errors(Collections.emptyList())
          .rawOutput("No build tool detected — skipping type check")
          .build();
    }

    ProcessResult result =
        processExecutor.execute(
            ProcessCommand.builder().args(args).workingDir(projectRoot).timeout(timeout).build());

    List<ValidationError> errors =
        result.isSuccess() ? Collections.emptyList() : javacOutputParser.parse(result.getOutput());

    return GateResult.builder()
        .gate(ValidationGate.TYPE_CHECK)
        .passed(result.isSuccess())
        .duration(result.getDuration())
        .errors(errors)
        .rawOutput(result.getOutput())
        .build();
  }

  @Override
  public GateResult lintCheck(Path projectRoot, Path filePath, Duration timeout) {
    // Checkstyle/SpotBugs — skip for v1
    return GateResult.builder()
        .gate(ValidationGate.LINT)
        .passed(true)
        .duration(Duration.ZERO)
        .errors(Collections.emptyList())
        .rawOutput("Java lint check not configured — skipping")
        .build();
  }

  @Override
  public GateResult unitTest(Path projectRoot, Path filePath, Duration timeout) {
    String fileName = filePath.getFileName().toString().replace(".java", "");
    String testClassName = fileName + "Test";

    boolean isMaven = Files.exists(projectRoot.resolve("pom.xml"));
    boolean isGradle =
        Files.exists(projectRoot.resolve("build.gradle"))
            || Files.exists(projectRoot.resolve("build.gradle.kts"));

    if (!isMaven && !isGradle) {
      return GateResult.builder()
          .gate(ValidationGate.UNIT_TEST)
          .passed(true)
          .duration(Duration.ZERO)
          .errors(Collections.emptyList())
          .rawOutput("No build tool detected — skipping tests")
          .build();
    }

    if (isGradle) {
      String gradleCmd = resolveGradleCommand(projectRoot);
      if (gradleCmd == null) {
        return GateResult.builder()
            .gate(ValidationGate.UNIT_TEST)
            .passed(true)
            .duration(Duration.ZERO)
            .errors(Collections.emptyList())
            .rawOutput("Gradle project but gradle/gradlew not available — skipping tests")
            .build();
      }
      ProcessResult result =
          processExecutor.execute(
              ProcessCommand.builder()
                  .args(List.of(gradleCmd, "test", "-q", "--no-daemon"))
                  .workingDir(projectRoot)
                  .timeout(timeout)
                  .build());
      List<ValidationError> errors =
          result.isSuccess() ? Collections.emptyList() : testOutputParser.parse(result.getOutput());
      return GateResult.builder()
          .gate(ValidationGate.UNIT_TEST)
          .passed(result.isSuccess())
          .duration(result.getDuration())
          .errors(errors)
          .rawOutput(result.getOutput())
          .build();
    }

    // Maven path
    if (!Files.exists(projectRoot.resolve("pom.xml"))) {
      return GateResult.builder()
          .gate(ValidationGate.UNIT_TEST)
          .passed(true)
          .duration(Duration.ZERO)
          .errors(Collections.emptyList())
          .rawOutput("No Maven build tool — skipping tests")
          .build();
    }

    // Check if test file exists
    ProcessResult findResult =
        processExecutor.execute(
            ProcessCommand.builder()
                .args(
                    List.of(
                        "find",
                        projectRoot.toString(),
                        "-name",
                        testClassName + ".java",
                        "-type",
                        "f"))
                .workingDir(projectRoot)
                .timeout(Duration.ofSeconds(5))
                .build());

    if (findResult.getOutput() == null || findResult.getOutput().trim().isEmpty()) {
      return GateResult.builder()
          .gate(ValidationGate.UNIT_TEST)
          .passed(true)
          .duration(Duration.ZERO)
          .errors(Collections.emptyList())
          .rawOutput("No test file found for " + fileName + " — skipping")
          .build();
    }

    ProcessResult result =
        processExecutor.execute(
            ProcessCommand.builder()
                .args(
                    List.of(
                        "mvn",
                        "test",
                        "-B",
                        "-q",
                        "-Dtest=" + testClassName,
                        "-DfailIfNoTests=false",
                        "-f",
                        projectRoot.resolve("pom.xml").toString()))
                .workingDir(projectRoot)
                .timeout(timeout)
                .build());

    List<ValidationError> errors =
        result.isSuccess() ? Collections.emptyList() : testOutputParser.parse(result.getOutput());

    return GateResult.builder()
        .gate(ValidationGate.UNIT_TEST)
        .passed(result.isSuccess())
        .duration(result.getDuration())
        .errors(errors)
        .rawOutput(result.getOutput())
        .build();
  }

  @Override
  public boolean isAvailable() {
    ProcessResult r =
        processExecutor.execute(
            ProcessCommand.builder()
                .args(List.of("mvn", "--version"))
                .workingDir(Path.of(System.getProperty("user.dir")))
                .timeout(Duration.ofSeconds(5))
                .build());
    return r.isSuccess();
  }

  /**
   * Resolve gradle command: prefer project's gradlew wrapper, fall back to system gradle. Returns
   * null if neither is available.
   */
  private String resolveGradleCommand(Path projectRoot) {
    Path gradlew = projectRoot.resolve("gradlew");
    if (Files.isExecutable(gradlew)) {
      return gradlew.toString();
    }
    // Try system gradle
    ProcessResult r =
        processExecutor.execute(
            ProcessCommand.builder()
                .args(List.of("gradle", "--version"))
                .workingDir(projectRoot)
                .timeout(Duration.ofSeconds(5))
                .build());
    if (r.isSuccess()) {
      return "gradle";
    }
    return null;
  }
}
