package com.navneet.agentteam.validator.execution;

import com.navneet.agentteam.validator.model.ValidationError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class GenericTestOutputParser implements OutputParser {

    private static final Pattern PYTEST_PATTERN = Pattern.compile(
            "^FAILED\\s+(.+?)::(.+?)\\s+-\\s+(.+)$", Pattern.MULTILINE);

    private static final Pattern JEST_FAIL_PATTERN = Pattern.compile(
            "^\\s+●\\s+(.+)$", Pattern.MULTILINE);

    private static final Pattern SUREFIRE_PATTERN = Pattern.compile(
            "Tests run:\\s+(\\d+),\\s+Failures:\\s+(\\d+),\\s+Errors:\\s+(\\d+)");

    @Override
    public List<ValidationError> parse(String rawOutput) {
        List<ValidationError> errors = new ArrayList<>();

        Matcher pytestMatcher = PYTEST_PATTERN.matcher(rawOutput);
        while (pytestMatcher.find()) {
            errors.add(ValidationError.builder()
                    .file(pytestMatcher.group(1).trim())
                    .line(0).column(0)
                    .gate("UNIT_TEST")
                    .code("test:failed")
                    .message(pytestMatcher.group(2).trim() + " - " + pytestMatcher.group(3).trim())
                    .severity(ValidationError.Severity.ERROR)
                    .build());
        }

        if (errors.isEmpty()) {
            Matcher jestMatcher = JEST_FAIL_PATTERN.matcher(rawOutput);
            while (jestMatcher.find()) {
                errors.add(ValidationError.builder()
                        .file("test").line(0).column(0)
                        .gate("UNIT_TEST")
                        .code("test:failed")
                        .message(jestMatcher.group(1).trim())
                        .severity(ValidationError.Severity.ERROR)
                        .build());
            }
        }

        if (errors.isEmpty()) {
            Matcher surefireMatcher = SUREFIRE_PATTERN.matcher(rawOutput);
            while (surefireMatcher.find()) {
                int failures = Integer.parseInt(surefireMatcher.group(2));
                int errs = Integer.parseInt(surefireMatcher.group(3));
                if (failures > 0 || errs > 0) {
                    errors.add(ValidationError.builder()
                            .file("project").line(0).column(0)
                            .gate("UNIT_TEST")
                            .code("test:surefire")
                            .message(String.format("%d failures, %d errors", failures, errs))
                            .severity(ValidationError.Severity.ERROR)
                            .build());
                }
            }
        }

        return errors;
    }
}
