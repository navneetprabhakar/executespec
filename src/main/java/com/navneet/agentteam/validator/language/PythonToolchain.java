package com.navneet.agentteam.validator.language;

import com.navneet.agentteam.validator.execution.*;
import com.navneet.agentteam.validator.execution.ProcessExecutor.ProcessCommand;
import com.navneet.agentteam.validator.execution.ProcessExecutor.ProcessResult;
import com.navneet.agentteam.validator.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PythonToolchain implements LanguageToolchain {

    private final ProcessExecutor processExecutor;
    private final MypyOutputParser mypyOutputParser;
    private final RuffOutputParser ruffOutputParser;
    private final GenericTestOutputParser testOutputParser;

    @Override
    public Language getLanguage() { return Language.PYTHON; }

    @Override
    public GateResult syntaxCheck(Path projectRoot, Path filePath, Duration timeout) {
        ProcessResult result = processExecutor.execute(ProcessCommand.builder()
                .args(List.of("python3", "-m", "py_compile", projectRoot.resolve(filePath).toString()))
                .workingDir(projectRoot).timeout(timeout).build());

        List<ValidationError> errors = result.isSuccess() ? Collections.emptyList()
                : List.of(ValidationError.builder()
                        .file(filePath.toString()).line(0).column(0).gate("SYNTAX")
                        .code("python:syntax").message(result.getOutput())
                        .severity(ValidationError.Severity.ERROR).build());

        return GateResult.builder().gate(ValidationGate.SYNTAX).passed(result.isSuccess())
                .duration(result.getDuration()).errors(errors).rawOutput(result.getOutput()).build();
    }

    @Override
    public GateResult typeCheck(Path projectRoot, Path filePath, Duration timeout) {
        ProcessResult result = processExecutor.execute(ProcessCommand.builder()
                .args(List.of("mypy", "--strict", "--no-error-summary", projectRoot.resolve(filePath).toString()))
                .workingDir(projectRoot).timeout(timeout).build());

        List<ValidationError> errors = result.isSuccess()
                ? Collections.emptyList() : mypyOutputParser.parse(result.getOutput());

        return GateResult.builder().gate(ValidationGate.TYPE_CHECK).passed(result.isSuccess())
                .duration(result.getDuration()).errors(errors).rawOutput(result.getOutput()).build();
    }

    @Override
    public GateResult lintCheck(Path projectRoot, Path filePath, Duration timeout) {
        ProcessResult result = processExecutor.execute(ProcessCommand.builder()
                .args(List.of("ruff", "check", projectRoot.resolve(filePath).toString()))
                .workingDir(projectRoot).timeout(timeout).build());

        List<ValidationError> errors = result.isSuccess()
                ? Collections.emptyList() : ruffOutputParser.parse(result.getOutput());

        return GateResult.builder().gate(ValidationGate.LINT).passed(result.isSuccess())
                .duration(result.getDuration()).errors(errors).rawOutput(result.getOutput()).build();
    }

    @Override
    public GateResult unitTest(Path projectRoot, Path filePath, Duration timeout) {
        String fileName = filePath.getFileName().toString();
        String testFile = "test_" + fileName;

        ProcessResult findResult = processExecutor.execute(ProcessCommand.builder()
                .args(List.of("find", projectRoot.toString(), "-name", testFile, "-type", "f"))
                .workingDir(projectRoot).timeout(Duration.ofSeconds(5)).build());

        if (findResult.getOutput() == null || findResult.getOutput().trim().isEmpty()) {
            return GateResult.builder().gate(ValidationGate.UNIT_TEST).passed(true)
                    .duration(Duration.ZERO).errors(Collections.emptyList())
                    .rawOutput("No test file found for " + fileName + " — skipping").build();
        }

        String testFilePath = findResult.getOutput().trim().split("\n")[0];

        ProcessResult result = processExecutor.execute(ProcessCommand.builder()
                .args(List.of("python3", "-m", "pytest", testFilePath, "-x", "-q"))
                .workingDir(projectRoot).timeout(timeout).build());

        List<ValidationError> errors = result.isSuccess()
                ? Collections.emptyList() : testOutputParser.parse(result.getOutput());

        return GateResult.builder().gate(ValidationGate.UNIT_TEST).passed(result.isSuccess())
                .duration(result.getDuration()).errors(errors).rawOutput(result.getOutput()).build();
    }

    @Override
    public boolean isAvailable() {
        // Check that python3 is available — mypy/ruff will be resolved from each project's venv at validation time
        ProcessResult p = processExecutor.execute(ProcessCommand.builder()
                .args(List.of("python3", "--version"))
                .workingDir(Path.of(System.getProperty("user.dir")))
                .timeout(Duration.ofSeconds(5)).build());
        return p.isSuccess();
    }
}
