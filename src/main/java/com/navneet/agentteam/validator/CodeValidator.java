package com.navneet.agentteam.validator;

import com.navneet.agentteam.validator.model.ValidateFileRequest;
import com.navneet.agentteam.validator.model.ValidateProjectRequest;
import com.navneet.agentteam.validator.model.ValidationResult;

public interface CodeValidator {
    ValidationResult validateFile(ValidateFileRequest request);
    ValidationResult validateProject(ValidateProjectRequest request);
}
