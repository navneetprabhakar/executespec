package com.navneet.executespec.validator.language;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

import com.navneet.executespec.validator.execution.EslintOutputParser;
import com.navneet.executespec.validator.execution.ProcessExecutor;
import com.navneet.executespec.validator.execution.ProcessExecutor.ProcessResult;
import com.navneet.executespec.validator.model.GateResult;
import com.navneet.executespec.validator.model.Language;
import com.navneet.executespec.validator.model.ValidationError;
import com.navneet.executespec.validator.model.ValidationGate;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ReactToolchain implements LanguageToolchain {

  private final TypeScriptToolchain tsToolchain;
  private final ProcessExecutor processExecutor;
  private final EslintOutputParser eslintOutputParser;

  public ReactToolchain(
      TypeScriptToolchain tsToolchain,
      ProcessExecutor processExecutor,
      EslintOutputParser eslintOutputParser) {
    this.tsToolchain = tsToolchain;
    this.processExecutor = processExecutor;
    this.eslintOutputParser = eslintOutputParser;
  }

  @Override
  public Language getLanguage() {
    return Language.REACT;
  }

  @Override
  public GateResult syntaxCheck(Path projectRoot, Path filePath, Duration timeout) {
    return tsToolchain.syntaxCheck(projectRoot, filePath, timeout);
  }

  @Override
  public GateResult typeCheck(Path projectRoot, Path filePath, Duration timeout) {
    return tsToolchain.typeCheck(projectRoot, filePath, timeout);
  }

  @Override
  public GateResult lintCheck(Path projectRoot, Path filePath, Duration timeout) {
    ProcessResult result =
        processExecutor.execute(
            ProcessExecutor.ProcessCommand.builder()
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

    boolean hasHookViolation =
        errors.stream()
            .anyMatch(
                e ->
                    e.getCode() != null
                        && (e.getCode().contains("rules-of-hooks")
                            || e.getCode().contains("exhaustive-deps")));

    return GateResult.builder()
        .gate(ValidationGate.LINT)
        .passed(!hasHookViolation && result.isSuccess())
        .duration(result.getDuration())
        .errors(errors)
        .rawOutput(result.getOutput())
        .build();
  }

  @Override
  public GateResult unitTest(Path projectRoot, Path filePath, Duration timeout) {
    return tsToolchain.unitTest(projectRoot, filePath, timeout);
  }

  @Override
  public boolean isAvailable() {
    return tsToolchain.isAvailable();
  }
}
