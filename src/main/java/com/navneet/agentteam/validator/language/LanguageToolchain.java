package com.navneet.agentteam.validator.language;

import com.navneet.agentteam.validator.model.GateResult;
import com.navneet.agentteam.validator.model.Language;

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
