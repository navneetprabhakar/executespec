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
public class RuffOutputParser implements OutputParser {

  private static final Pattern RUFF_PATTERN =
      Pattern.compile("^(.+?):(\\d+):(\\d+):\\s+(\\w+)\\s+(.+)$", Pattern.MULTILINE);

  @Override
  public List<ValidationError> parse(String rawOutput) {
    List<ValidationError> errors = new ArrayList<>();
    Matcher matcher = RUFF_PATTERN.matcher(rawOutput);
    while (matcher.find()) {
      errors.add(
          ValidationError.builder()
              .file(matcher.group(1).trim())
              .line(Integer.parseInt(matcher.group(2)))
              .column(Integer.parseInt(matcher.group(3)))
              .gate("LINT")
              .code(matcher.group(4))
              .message(matcher.group(5).trim())
              .severity(ValidationError.Severity.WARNING)
              .build());
    }
    return errors;
  }
}
