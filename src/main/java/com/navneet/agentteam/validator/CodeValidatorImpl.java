package com.navneet.agentteam.validator;

import com.navneet.agentteam.validator.gate.GateChain;
import com.navneet.agentteam.validator.language.LanguageToolchain;
import com.navneet.agentteam.validator.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CodeValidatorImpl implements CodeValidator {

    private final GateChain gateChain;
    private final Map<Language, LanguageToolchain> toolchainMap;

    public CodeValidatorImpl(GateChain gateChain, List<LanguageToolchain> toolchains) {
        this.gateChain = gateChain;
        this.toolchainMap = toolchains.stream()
                .collect(Collectors.toMap(LanguageToolchain::getLanguage, Function.identity()));
        toolchainMap.forEach((lang, tc) -> {
            if (tc.isAvailable()) { log.info("Validator toolchain ready: {}", lang); }
            else { log.info("Validator toolchain not installed (skipped): {} — install the language tools to enable validation", lang); }
        });
    }

    @Override
    public ValidationResult validateFile(ValidateFileRequest request) {
        LanguageToolchain toolchain = toolchainMap.get(request.getLanguage());
        if (toolchain == null || !toolchain.isAvailable()) {
            log.warn("No toolchain for {} — skipping validation", request.getLanguage());
            return ValidationResult.pass();
        }
        log.info("Validating file: {} [{}] level={}", request.getFilePath(), request.getLanguage(), request.getLevel());
        ValidationResult result = gateChain.execute(toolchain, request.getProjectRoot(),
                request.getFilePath(), request.getLevel(), request.getTimeout());
        log.info("Validation result for {}: {} ({} errors, {}ms)",
                request.getFilePath(), result.getVerdict(), result.getErrors().size(), result.getTotalTime().toMillis());
        return result;
    }

    @Override
    public ValidationResult validateProject(ValidateProjectRequest request) {
        Path root = request.getProjectRoot();
        Language lang = detectPrimaryLanguage(root);
        if (lang == null) { return ValidationResult.pass(); }
        LanguageToolchain toolchain = toolchainMap.get(lang);
        if (toolchain == null || !toolchain.isAvailable()) { return ValidationResult.pass(); }
        return gateChain.execute(toolchain, root, Path.of("."), request.getLevel(), request.getTimeout());
    }

    private Language detectPrimaryLanguage(Path root) {
        if (root.resolve("pom.xml").toFile().exists() || root.resolve("build.gradle").toFile().exists()) return Language.JAVA;
        if (root.resolve("tsconfig.json").toFile().exists()) return Language.TYPESCRIPT;
        if (root.resolve("requirements.txt").toFile().exists() || root.resolve("pyproject.toml").toFile().exists()) return Language.PYTHON;
        return null;
    }
}
