package com.navneet.executespec.validator;

import static com.navneet.executespec.config.CustomMetricsConfig.VALIDATION_TOTAL;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.navneet.executespec.validator.gate.GateChain;
import com.navneet.executespec.validator.language.LanguageToolchain;
import com.navneet.executespec.validator.model.*;

import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CodeValidatorImpl implements CodeValidator {

  private final GateChain gateChain;
  private final Map<Language, LanguageToolchain> toolchainMap;
  private final MeterRegistry meterRegistry;

  public CodeValidatorImpl(
      GateChain gateChain, List<LanguageToolchain> toolchains, MeterRegistry meterRegistry) {
    this.gateChain = gateChain;
    this.meterRegistry = meterRegistry;
    this.toolchainMap =
        toolchains.stream()
            .collect(Collectors.toMap(LanguageToolchain::getLanguage, Function.identity()));
    toolchainMap.forEach(
        (lang, tc) -> {
          if (tc.isAvailable()) {
            log.info("Validator toolchain ready: {}", lang);
          } else {
            log.info(
                "Validator toolchain not installed (skipped): {} — install the language tools to"
                    + " enable validation",
                lang);
          }
        });
  }

  @Override
  public ValidationResult validateFile(ValidateFileRequest request) {
    LanguageToolchain toolchain = toolchainMap.get(request.getLanguage());
    if (toolchain == null || !toolchain.isAvailable()) {
      String reason =
          toolchain == null
              ? "No validator toolchain registered for " + request.getLanguage()
              : "Validator toolchain unavailable for " + request.getLanguage();
      log.warn("{} — validation is NOT_VERIFIED", reason);
      return ValidationResult.unmeasured(reason);
    }
    log.info(
        "Validating file: {} [{}] level={}",
        request.getFilePath(),
        request.getLanguage(),
        request.getLevel());
    ValidationResult result =
        gateChain.execute(
            toolchain,
            request.getProjectRoot(),
            request.getFilePath(),
            request.getLevel(),
            request.getTimeout());
    String validationResult =
        result.getVerdict() == ValidationVerdict.FAIL
            ? "fail"
            : result.getVerdict() == ValidationVerdict.UNMEASURED ? "unmeasured" : "pass";
    Counter.builder(VALIDATION_TOTAL)
        .tag("result", validationResult)
        .tag("gate", request.getLanguage().name())
        .register(meterRegistry)
        .increment();
    log.info(
        "Validation result for {}: {} ({} errors, {}ms)",
        request.getFilePath(),
        result.getVerdict(),
        result.getErrors().size(),
        result.getTotalTime() == null
            ? Duration.ZERO.toMillis()
            : result.getTotalTime().toMillis());
    return result;
  }

  @Override
  public ValidationResult validateProject(ValidateProjectRequest request) {
    Path root = request.getProjectRoot();
    Language lang = detectPrimaryLanguage(root);
    if (lang == null) {
      String reason = "Primary language could not be detected; validation is NOT_VERIFIED";
      log.warn(reason + " root={}", root);
      return ValidationResult.unmeasured(reason);
    }
    LanguageToolchain toolchain = toolchainMap.get(lang);
    if (toolchain == null || !toolchain.isAvailable()) {
      String reason =
          toolchain == null
              ? "No validator toolchain registered for " + lang
              : "Validator toolchain unavailable for " + lang;
      log.warn("{} — validation is NOT_VERIFIED", reason);
      return ValidationResult.unmeasured(reason);
    }
    return gateChain.execute(
        toolchain, root, Path.of("."), request.getLevel(), request.getTimeout());
  }

  private Language detectPrimaryLanguage(Path root) {
    if (root.resolve("pom.xml").toFile().exists() || root.resolve("build.gradle").toFile().exists())
      return Language.JAVA;
    if (root.resolve("tsconfig.json").toFile().exists()) return Language.TYPESCRIPT;
    if (root.resolve("package.json").toFile().exists()) return Language.JAVASCRIPT;
    if (root.resolve("requirements.txt").toFile().exists()
        || root.resolve("pyproject.toml").toFile().exists()) return Language.PYTHON;
    return null;
  }
}
