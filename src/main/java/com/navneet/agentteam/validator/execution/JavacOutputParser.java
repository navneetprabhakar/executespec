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
public class JavacOutputParser implements OutputParser {

    private static final Pattern JAVAC_PATTERN = Pattern.compile(
            "^(.+?):(\\d+): (error|warning): (.+)$", Pattern.MULTILINE);

    private static final Pattern MAVEN_PATTERN = Pattern.compile(
            "^\\[ERROR\\]\\s+(.+?):\\[(\\d+),(\\d+)\\]\\s+(.+)$", Pattern.MULTILINE);

    @Override
    public List<ValidationError> parse(String rawOutput) {
        List<ValidationError> errors = new ArrayList<>();

        Matcher mavenMatcher = MAVEN_PATTERN.matcher(rawOutput);
        while (mavenMatcher.find()) {
            errors.add(ValidationError.builder()
                    .file(mavenMatcher.group(1).trim())
                    .line(Integer.parseInt(mavenMatcher.group(2)))
                    .column(Integer.parseInt(mavenMatcher.group(3)))
                    .gate("TYPE_CHECK")
                    .code("java:compiler")
                    .message(mavenMatcher.group(4).trim())
                    .severity(ValidationError.Severity.ERROR)
                    .build());
        }

        if (errors.isEmpty()) {
            Matcher javacMatcher = JAVAC_PATTERN.matcher(rawOutput);
            while (javacMatcher.find()) {
                String severityStr = javacMatcher.group(3);
                errors.add(ValidationError.builder()
                        .file(javacMatcher.group(1).trim())
                        .line(Integer.parseInt(javacMatcher.group(2)))
                        .column(0)
                        .gate("TYPE_CHECK")
                        .code("java:compiler")
                        .message(javacMatcher.group(4).trim())
                        .severity("warning".equals(severityStr)
                                ? ValidationError.Severity.WARNING
                                : ValidationError.Severity.ERROR)
                        .build());
            }
        }

        if (errors.isEmpty() && rawOutput.contains("BUILD FAILURE")) {
            errors.add(ValidationError.builder()
                    .file("project")
                    .line(0).column(0)
                    .gate("TYPE_CHECK")
                    .code("maven:build-failure")
                    .message(extractFirstErrorLine(rawOutput))
                    .severity(ValidationError.Severity.ERROR)
                    .build());
        }

        return errors;
    }

    private String extractFirstErrorLine(String output) {
        for (String line : output.split("\n")) {
            if (line.startsWith("[ERROR]") && !line.contains("BUILD FAILURE")
                    && !line.contains("-> [Help")) {
                return line.replaceFirst("^\\[ERROR\\]\\s*", "").trim();
            }
        }
        return "Build failed — see raw output for details";
    }
}
