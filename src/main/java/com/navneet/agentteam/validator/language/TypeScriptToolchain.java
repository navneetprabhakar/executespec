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
public class TypeScriptToolchain implements LanguageToolchain {

    private final ProcessExecutor processExecutor;
    private final TscOutputParser tscOutputParser;
    private final EslintOutputParser eslintOutputParser;
    private final GenericTestOutputParser testOutputParser;

    @Override
    public Language getLanguage() { return Language.TYPESCRIPT; }

    @Override
    public GateResult syntaxCheck(Path projectRoot, Path filePath, Duration timeout) {
        return GateResult.builder().gate(ValidationGate.SYNTAX).passed(true)
                .duration(Duration.ZERO).errors(Collections.emptyList())
                .rawOutput("TypeScript syntax check delegated to tsc --noEmit in type check").build();
    }

    @Override
    public GateResult typeCheck(Path projectRoot, Path filePath, Duration timeout) {
        if (!Files.exists(projectRoot.resolve("tsconfig.json"))) {
            return GateResult.builder().gate(ValidationGate.TYPE_CHECK).passed(true)
                    .duration(Duration.ZERO).errors(Collections.emptyList())
                    .rawOutput("No tsconfig.json found — skipping type check").build();
        }

        ProcessResult result = processExecutor.execute(ProcessCommand.builder()
                .args(List.of("npx", "tsc", "--noEmit", "--pretty", "false"))
                .workingDir(projectRoot).timeout(timeout).build());

        List<ValidationError> errors = result.isSuccess()
                ? Collections.emptyList() : tscOutputParser.parse(result.getOutput());

        return GateResult.builder().gate(ValidationGate.TYPE_CHECK).passed(result.isSuccess())
                .duration(result.getDuration()).errors(errors).rawOutput(result.getOutput()).build();
    }

    @Override
    public GateResult lintCheck(Path projectRoot, Path filePath, Duration timeout) {
        boolean hasConfig = Files.exists(projectRoot.resolve(".eslintrc.json"))
                || Files.exists(projectRoot.resolve(".eslintrc.js"))
                || Files.exists(projectRoot.resolve(".eslintrc.cjs"))
                || Files.exists(projectRoot.resolve("eslint.config.js"))
                || Files.exists(projectRoot.resolve("eslint.config.mjs"));

        if (!hasConfig) {
            return GateResult.builder().gate(ValidationGate.LINT).passed(true)
                    .duration(Duration.ZERO).errors(Collections.emptyList())
                    .rawOutput("No ESLint config found — skipping lint").build();
        }

        ProcessResult result = processExecutor.execute(ProcessCommand.builder()
                .args(List.of("npx", "eslint", "--format", "compact",
                        projectRoot.resolve(filePath).toString()))
                .workingDir(projectRoot).timeout(timeout).build());

        List<ValidationError> errors = result.isSuccess()
                ? Collections.emptyList() : eslintOutputParser.parse(result.getOutput());

        return GateResult.builder().gate(ValidationGate.LINT).passed(result.isSuccess())
                .duration(result.getDuration()).errors(errors).rawOutput(result.getOutput()).build();
    }

    @Override
    public GateResult unitTest(Path projectRoot, Path filePath, Duration timeout) {
        String testRunnerCmd = Files.exists(projectRoot.resolve("vitest.config.ts"))
                || Files.exists(projectRoot.resolve("vitest.config.js")) ? "vitest" : "jest";

        String fileName = filePath.getFileName().toString();
        String baseName = fileName.replaceFirst("\\.(ts|tsx)$", "");
        String ext = fileName.endsWith(".tsx") ? "tsx" : "ts";
        Path testDir = filePath.getParent();
        Path testFilePath = (testDir != null ? testDir : Path.of("")).resolve(baseName + ".test." + ext);
        Path specFilePath = (testDir != null ? testDir : Path.of("")).resolve(baseName + ".spec." + ext);

        boolean testExists = Files.exists(projectRoot.resolve(testFilePath))
                || Files.exists(projectRoot.resolve(specFilePath));

        if (!testExists) {
            return GateResult.builder().gate(ValidationGate.UNIT_TEST).passed(true)
                    .duration(Duration.ZERO).errors(Collections.emptyList())
                    .rawOutput("No test file found for " + fileName + " — skipping").build();
        }

        String actualTestFile = Files.exists(projectRoot.resolve(testFilePath))
                ? testFilePath.toString() : specFilePath.toString();

        ProcessResult result = processExecutor.execute(ProcessCommand.builder()
                .args(List.of("npx", testRunnerCmd, "--run", actualTestFile))
                .workingDir(projectRoot).timeout(timeout).build());

        List<ValidationError> errors = result.isSuccess()
                ? Collections.emptyList() : testOutputParser.parse(result.getOutput());

        return GateResult.builder().gate(ValidationGate.UNIT_TEST).passed(result.isSuccess())
                .duration(result.getDuration()).errors(errors).rawOutput(result.getOutput()).build();
    }

    @Override
    public boolean isAvailable() {
        // Check that npx is available — tsc will be resolved from each project's node_modules at validation time
        ProcessResult r = processExecutor.execute(ProcessCommand.builder()
                .args(List.of("npx", "--version"))
                .workingDir(Path.of(System.getProperty("user.dir")))
                .timeout(Duration.ofSeconds(5)).build());
        return r.isSuccess();
    }
}
