package com.navneet.executespec.validator.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.validator.model.ValidationError;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class MypyOutputParser implements OutputParser {

  private static final Pattern MYPY_PATTERN =
      Pattern.compile(
          "^(.+?):(\\d+):\\s+(error|warning|note):\\s+(.+?)(?:\\s+\\[(.+?)\\])?$",
          Pattern.MULTILINE);

  @Override
  public List<ValidationError> parse(String rawOutput) {
    List<ValidationError> errors = new ArrayList<>();
    Matcher matcher = MYPY_PATTERN.matcher(rawOutput);
    while (matcher.find()) {
      String severityStr = matcher.group(3);
      if ("note".equals(severityStr)) continue;
      errors.add(
          ValidationError.builder()
              .file(matcher.group(1).trim())
              .line(Integer.parseInt(matcher.group(2)))
              .column(0)
              .gate("TYPE_CHECK")
              .code(matcher.group(5) != null ? matcher.group(5) : "mypy")
              .message(matcher.group(4).trim())
              .severity(
                  "warning".equals(severityStr)
                      ? ValidationError.Severity.WARNING
                      : ValidationError.Severity.ERROR)
              .build());
    }
    return errors;
  }
}
