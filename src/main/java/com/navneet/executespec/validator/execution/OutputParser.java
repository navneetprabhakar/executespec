package com.navneet.executespec.validator.execution;

import java.util.List;

import com.navneet.executespec.validator.model.ValidationError;

public interface OutputParser {
  List<ValidationError> parse(String rawOutput);
}
