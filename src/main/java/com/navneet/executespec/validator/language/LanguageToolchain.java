package com.navneet.executespec.validator.language;

import com.navneet.executespec.validator.model.GateResult;
import com.navneet.executespec.validator.model.Language;

import java.nio.file.Path;
import java.time.Duration;

public interface LanguageToolchain {
    Language getLanguage();
    GateResult syntaxCheck(Path projectRoot, Path filePath, Duration timeout);
    GateResult typeCheck(Path projectRoot, Path filePath, Duration timeout);
    GateResult lintCheck(Path projectRoot, Path filePath, Duration timeout);
    GateResult unitTest(Path projectRoot, Path filePath, Duration timeout);
    boolean isAvailable();
}
