package com.navneet.agentteam.validator.execution;

import com.navneet.agentteam.validator.model.ValidationError;
import java.util.List;

public interface OutputParser {
    List<ValidationError> parse(String rawOutput);
}
