package com.navneet.executespec.validator.language;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

import com.navneet.executespec.validator.execution.EslintOutputParser;
import com.navneet.executespec.validator.execution.GenericTestOutputParser;
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

/** Toolchain for plain JavaScript (.js, .mjs, .cjs) validation. */
@Slf4j
@Component
@RequiredArgsConstructor
public class JavascriptToolchain implements LanguageToolchain {

  private final ProcessExecutor processExecutor;
  private final EslintOutputParser eslintOutputParser;
  private final GenericTestOutputParser testOutputParser;

  @Override
  public Language getLanguage() {
    return Language.JAVASCRIPT;
  }

  @Override
  public GateResult syntaxCheck(Path projectRoot, Path filePath, Duration timeout) {
    // Use node --check to verify syntax (works for both CJS and ESM in Node 20)
    ProcessResult result =
        processExecutor.execute(
            ProcessCommand.builder()
                .args(List.of("node", "--check", projectRoot.resolve(filePath).toString()))
                .workingDir(projectRoot)
                .timeout(timeout)
                .build());

    return GateResult.builder()
        .gate(ValidationGate.SYNTAX)
        .passed(result.isSuccess())
        .duration(result.getDuration())
        .errors(
            result.isSuccess()
                ? Collections.emptyList()
                : List.of(
                    ValidationError.builder()
                        .message("Syntax error: " + result.getOutput())
                        .file(filePath.toString())
                        .build()))
        .rawOutput(result.getOutput())
        .build();
  }

  @Override
  public GateResult typeCheck(Path projectRoot, Path filePath, Duration timeout) {
    // JS files don't usually have a separate type check gate unless using JSDoc (skipped for now)
    return GateResult.builder()
        .gate(ValidationGate.TYPE_CHECK)
        .passed(true)
        .duration(Duration.ZERO)
        .errors(Collections.emptyList())
        .rawOutput("JavaScript type check skipped")
        .build();
  }

  @Override
  public GateResult lintCheck(Path projectRoot, Path filePath, Duration timeout) {
    boolean hasConfig =
        Files.exists(projectRoot.resolve(".eslintrc.json"))
            || Files.exists(projectRoot.resolve(".eslintrc.js"))
            || Files.exists(projectRoot.resolve(".eslintrc.cjs"))
            || Files.exists(projectRoot.resolve("eslint.config.js"))
            || Files.exists(projectRoot.resolve("eslint.config.mjs"));

    if (!hasConfig) {
      return GateResult.unmeasured(ValidationGate.LINT, "No ESLint config found");
    }

    ProcessResult result =
        processExecutor.execute(
            ProcessCommand.builder()
                .args(
                    List.of(
                        "npx",
                        "eslint",
                        "--format",
                        "compact",
                        projectRoot.resolve(filePath).toString()))
                .workingDir(projectRoot)
                .timeout(timeout)
                .build());

    List<ValidationError> errors =
        result.isSuccess() ? Collections.emptyList() : eslintOutputParser.parse(result.getOutput());

    return GateResult.builder()
        .gate(ValidationGate.LINT)
        .passed(result.isSuccess())
        .duration(result.getDuration())
        .errors(errors)
        .rawOutput(result.getOutput())
        .build();
  }

  @Override
  public GateResult unitTest(Path projectRoot, Path filePath, Duration timeout) {
    // Similar to TypeScriptToolchain, detect vitest or jest
    String testRunnerCmd =
        Files.exists(projectRoot.resolve("vitest.config.ts"))
                || Files.exists(projectRoot.resolve("vitest.config.js"))
            ? "vitest"
            : "jest";

    String fileName = filePath.getFileName().toString();
    String baseName = fileName.replaceFirst("\\.(js|mjs|cjs)$", "");

    Path testDir = filePath.getParent();
    Path testFilePath = (testDir != null ? testDir : Path.of("")).resolve(baseName + ".test.js");
    Path specFilePath = (testDir != null ? testDir : Path.of("")).resolve(baseName + ".spec.js");

    boolean testExists =
        Files.exists(projectRoot.resolve(testFilePath))
            || Files.exists(projectRoot.resolve(specFilePath));

    if (!testExists) {
      return GateResult.unmeasured(ValidationGate.UNIT_TEST, "No test file found for " + fileName);
    }

    String actualTestFile =
        Files.exists(projectRoot.resolve(testFilePath))
            ? testFilePath.toString()
            : specFilePath.toString();

    ProcessResult result =
        processExecutor.execute(
            ProcessCommand.builder()
                .args(List.of("npx", testRunnerCmd, "--run", actualTestFile))
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
                .args(List.of("node", "--version"))
                .workingDir(Path.of(System.getProperty("user.dir")))
                .timeout(Duration.ofSeconds(5))
                .build());
    return r.isSuccess();
  }
}
