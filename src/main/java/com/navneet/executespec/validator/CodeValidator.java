package com.navneet.executespec.validator;

import com.navneet.executespec.validator.model.ValidateFileRequest;
import com.navneet.executespec.validator.model.ValidateProjectRequest;
import com.navneet.executespec.validator.model.ValidationResult;

public interface CodeValidator {
  ValidationResult validateFile(ValidateFileRequest request);

  ValidationResult validateProject(ValidateProjectRequest request);
}
