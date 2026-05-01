package com.navneet.executespec.validator.model;

import java.nio.file.Path;
import java.time.Duration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidateFileRequest {
  private Path projectRoot;
  private Path filePath;
  private Language language;
  @Builder.Default private ValidationLevel level = ValidationLevel.TYPE;
  @Builder.Default private Duration timeout = Duration.ofSeconds(30);
}
