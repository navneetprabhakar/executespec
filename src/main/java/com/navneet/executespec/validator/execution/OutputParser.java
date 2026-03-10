package com.navneet.executespec.validator.execution;

import com.navneet.executespec.validator.model.ValidationError;
import java.util.List;

public interface OutputParser {
    List<ValidationError> parse(String rawOutput);
}
