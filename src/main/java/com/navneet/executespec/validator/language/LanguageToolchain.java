package com.navneet.executespec.validator.language;

import java.nio.file.Path;
import java.time.Duration;

import com.navneet.executespec.validator.model.GateResult;
import com.navneet.executespec.validator.model.Language;

public interface LanguageToolchain {
  Language getLanguage();

  GateResult syntaxCheck(Path projectRoot, Path filePath, Duration timeout);

  GateResult typeCheck(Path projectRoot, Path filePath, Duration timeout);

  GateResult lintCheck(Path projectRoot, Path filePath, Duration timeout);

  GateResult unitTest(Path projectRoot, Path filePath, Duration timeout);

  boolean isAvailable();
}
