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
public class TscOutputParser implements OutputParser {

  private static final Pattern TSC_PATTERN =
      Pattern.compile(
          "^(.+?)\\((\\d+),(\\d+)\\):\\s+(error|warning)\\s+(TS\\d+):\\s+(.+)$", Pattern.MULTILINE);

  @Override
  public List<ValidationError> parse(String rawOutput) {
    List<ValidationError> errors = new ArrayList<>();
    Matcher matcher = TSC_PATTERN.matcher(rawOutput);
    while (matcher.find()) {
      String severityStr = matcher.group(4);
      errors.add(
          ValidationError.builder()
              .file(matcher.group(1).trim())
              .line(Integer.parseInt(matcher.group(2)))
              .column(Integer.parseInt(matcher.group(3)))
              .gate("TYPE_CHECK")
              .code(matcher.group(5))
              .message(matcher.group(6).trim())
              .severity(
                  "warning".equals(severityStr)
                      ? ValidationError.Severity.WARNING
                      : ValidationError.Severity.ERROR)
              .build());
    }
    return errors;
  }
}
