package com.navneet.executespec.validator.gate;

import com.navneet.executespec.validator.language.LanguageToolchain;
import com.navneet.executespec.validator.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GateChain {

    public ValidationResult execute(LanguageToolchain toolchain, Path projectRoot,
                                     Path filePath, ValidationLevel level, Duration perGateTimeout) {
        Instant start = Instant.now();
        Map<ValidationGate, GateResult> gateResults = new LinkedHashMap<>();
        List<ValidationError> allErrors = new ArrayList<>();
        List<ValidationError> allWarnings = new ArrayList<>();
        StringBuilder rawOutputBuilder = new StringBuilder();

        // Gate 1: SYNTAX
        GateResult syntaxResult = toolchain.syntaxCheck(projectRoot, filePath, perGateTimeout);
        gateResults.put(ValidationGate.SYNTAX, syntaxResult);
        appendOutput(rawOutputBuilder, "SYNTAX", syntaxResult);
        if (!syntaxResult.isPassed()) {
            allErrors.addAll(syntaxResult.getErrors());
            return buildResult(ValidationVerdict.FAIL, allErrors, allWarnings, gateResults, start, rawOutputBuilder.toString());
        }
        if (level == ValidationLevel.SYNTAX) {
            return buildResult(ValidationVerdict.PASS, allErrors, allWarnings, gateResults, start, rawOutputBuilder.toString());
        }

        // Gate 2: TYPE CHECK
        GateResult typeResult = toolchain.typeCheck(projectRoot, filePath, perGateTimeout);
        gateResults.put(ValidationGate.TYPE_CHECK, typeResult);
        appendOutput(rawOutputBuilder, "TYPE_CHECK", typeResult);
        if (!typeResult.isPassed()) {
            allErrors.addAll(typeResult.getErrors());
            return buildResult(ValidationVerdict.FAIL, allErrors, allWarnings, gateResults, start, rawOutputBuilder.toString());
        }
        if (level == ValidationLevel.TYPE) {
            return buildResult(ValidationVerdict.PASS, allErrors, allWarnings, gateResults, start, rawOutputBuilder.toString());
        }

        // Gate 3: LINT (non-blocking unless React hook rules)
        GateResult lintResult = toolchain.lintCheck(projectRoot, filePath, perGateTimeout);
        gateResults.put(ValidationGate.LINT, lintResult);
        appendOutput(rawOutputBuilder, "LINT", lintResult);
        if (!lintResult.isPassed()) {
            boolean hasBlockingLintErrors = lintResult.getErrors().stream()
                    .anyMatch(e -> e.getSeverity() == ValidationError.Severity.ERROR
                            && e.getCode() != null
                            && (e.getCode().contains("rules-of-hooks") || e.getCode().contains("exhaustive-deps")));
            if (hasBlockingLintErrors) {
                allErrors.addAll(lintResult.getErrors());
                return buildResult(ValidationVerdict.FAIL, allErrors, allWarnings, gateResults, start, rawOutputBuilder.toString());
            }
            allWarnings.addAll(lintResult.getErrors());
        }
        if (level == ValidationLevel.LINT) {
            ValidationVerdict v = allWarnings.isEmpty() ? ValidationVerdict.PASS : ValidationVerdict.WARN;
            return buildResult(v, allErrors, allWarnings, gateResults, start, rawOutputBuilder.toString());
        }

        // Gate 4: UNIT TEST
        GateResult testResult = toolchain.unitTest(projectRoot, filePath, perGateTimeout);
        gateResults.put(ValidationGate.UNIT_TEST, testResult);
        appendOutput(rawOutputBuilder, "UNIT_TEST", testResult);
        if (!testResult.isPassed()) {
            allErrors.addAll(testResult.getErrors());
            return buildResult(ValidationVerdict.FAIL, allErrors, allWarnings, gateResults, start, rawOutputBuilder.toString());
        }

        ValidationVerdict v = allWarnings.isEmpty() ? ValidationVerdict.PASS : ValidationVerdict.WARN;
        return buildResult(v, allErrors, allWarnings, gateResults, start, rawOutputBuilder.toString());
    }

    private ValidationResult buildResult(ValidationVerdict verdict, List<ValidationError> errors,
                                          List<ValidationError> warnings, Map<ValidationGate, GateResult> gateResults,
                                          Instant start, String rawOutput) {
        return ValidationResult.builder().verdict(verdict).errors(errors).warnings(warnings)
                .gateResults(gateResults).totalTime(Duration.between(start, Instant.now()))
                .rawOutput(rawOutput).build();
    }

    private void appendOutput(StringBuilder sb, String gateName, GateResult result) {
        sb.append("=== ").append(gateName).append(" === ").append(result.isPassed() ? "PASS" : "FAIL").append("\n");
        if (result.getRawOutput() != null && !result.getRawOutput().isEmpty()) {
            sb.append(result.getRawOutput()).append("\n");
        }
        sb.append("\n");
    }
}
