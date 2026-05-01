package com.navneet.executespec.tool;

import java.nio.file.Path;
import java.util.Set;

import com.navneet.executespec.security.guardrail.GuardrailLevel;
import com.navneet.executespec.security.guardrail.GuardrailLevelResolver;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Guardrail-aware file-sandbox policy (Plan 1 P5). Orthogonal to the existing {@code
 * FileSystemTool}-internal workspace-root containment — that check stays where it is (LOW preserves
 * it byte-for-byte). This bean layers additional restrictions on top at MEDIUM / HIGH:
 *
 * <ul>
 *   <li>LOW (default) — {@link #validateWrite} returns ALLOW for every path the caller already
 *       deemed safe. No-op.
 *   <li>MEDIUM — writes must land inside {@code src/}, {@code test/}, {@code public/}, or be one of
 *       the root-level config files ({@code package.json}, {@code pom.xml}, {@code Dockerfile},
 *       etc.).
 *   <li>HIGH — further forbids dotfile writes except the canonical allowlist ({@code .env.example},
 *       {@code .gitignore}, {@code .gitattributes}, {@code .dockerignore}).
 * </ul>
 *
 * <p>Call-site wiring lives in a follow-up PR (see {@code
 * docs/plans/guardrail-levels-execution-plan.md} §P5 plus the plan's "Files modified" list). Today
 * the bean exists + is unit-tested so the flipping mechanism is ready.
 */
@Component
@Slf4j
public class FileSandboxPolicy {

  private static final Set<String> MEDIUM_ALLOWED_DIRS =
      Set.of(
          "src",
          "test",
          "tests",
          "public",
          "static",
          "assets",
          "resources",
          "migrations",
          "db",
          "scripts",
          "docs");

  /** Root-level config files that MEDIUM still permits. */
  private static final Set<String> MEDIUM_ALLOWED_ROOT_FILES =
      Set.of(
          "package.json",
          "package-lock.json",
          "yarn.lock",
          "pnpm-lock.yaml",
          "pom.xml",
          "build.gradle",
          "build.gradle.kts",
          "settings.gradle",
          "Cargo.toml",
          "Cargo.lock",
          "go.mod",
          "go.sum",
          "requirements.txt",
          "Pipfile",
          "Pipfile.lock",
          "pyproject.toml",
          "poetry.lock",
          "Gemfile",
          "Gemfile.lock",
          "tsconfig.json",
          "tsconfig.base.json",
          "vite.config.ts",
          "vite.config.js",
          "webpack.config.js",
          "rollup.config.js",
          "tailwind.config.js",
          "postcss.config.js",
          "Dockerfile",
          "docker-compose.yml",
          "docker-compose.yaml",
          "README.md",
          "LICENSE",
          "LICENSE.md",
          "CHANGELOG.md",
          ".env.example",
          ".gitignore",
          ".gitattributes",
          ".dockerignore");

  /** Dotfiles that HIGH still permits. */
  private static final Set<String> HIGH_ALLOWED_DOTFILES =
      Set.of(".env.example", ".gitignore", ".gitattributes", ".dockerignore");

  private final GuardrailLevelResolver guardrailLevelResolver;

  @Autowired
  public FileSandboxPolicy(GuardrailLevelResolver guardrailLevelResolver) {
    this.guardrailLevelResolver = guardrailLevelResolver;
  }

  /** Back-compat constructor. */
  public FileSandboxPolicy() {
    this.guardrailLevelResolver = null;
  }

  /**
   * Evaluates a worker write against the current guardrail level.
   *
   * @param workspaceRoot run workspace directory (absolute)
   * @param target absolute path the worker is about to write / edit
   * @return ALLOW at LOW, or ALLOW/BLOCK based on MEDIUM/HIGH rules above.
   */
  public Decision validateWrite(Path workspaceRoot, Path target) {
    GuardrailLevel level =
        guardrailLevelResolver != null ? guardrailLevelResolver.current() : GuardrailLevel.LOW;
    return validateWrite(workspaceRoot, target, level);
  }

  /** Explicit-level variant — used by tests and future call-sites that want to force a level. */
  public Decision validateWrite(Path workspaceRoot, Path target, GuardrailLevel level) {
    GuardrailLevel effective = level == null ? GuardrailLevel.LOW : level;
    if (workspaceRoot == null || target == null) {
      return Decision.allow(); // degenerate inputs — existing FileSystemTool handles them
    }
    if (!effective.atLeastMedium()) {
      return Decision.allow();
    }

    Path rel;
    try {
      rel = workspaceRoot.toAbsolutePath().relativize(target.toAbsolutePath());
    } catch (IllegalArgumentException e) {
      // different roots — containment check at the caller should have stopped this
      return recordAndBlock("target outside workspace root");
    }
    String relStr = rel.toString().replace('\\', '/');
    if (relStr.isEmpty() || relStr.startsWith("..")) {
      return recordAndBlock("target escapes workspace root");
    }

    String firstSegment = relStr.contains("/") ? relStr.substring(0, relStr.indexOf('/')) : relStr;
    String fileName = rel.getFileName() == null ? "" : rel.getFileName().toString();

    // MEDIUM path-allowlist.
    boolean inAllowedDir = MEDIUM_ALLOWED_DIRS.contains(firstSegment);
    boolean isAllowedRootFile =
        !relStr.contains("/") && MEDIUM_ALLOWED_ROOT_FILES.contains(fileName);
    if (!inAllowedDir && !isAllowedRootFile) {
      return recordAndBlock(
          "guardrail "
              + effective
              + ": writes restricted to "
              + MEDIUM_ALLOWED_DIRS
              + " or known root-level config files");
    }

    // HIGH dotfile lockdown.
    if (effective.atLeastHigh()
        && fileName.startsWith(".")
        && !HIGH_ALLOWED_DOTFILES.contains(fileName)) {
      return recordAndBlock(
          "guardrail HIGH: dotfile '"
              + fileName
              + "' is not in the allowlist "
              + HIGH_ALLOWED_DOTFILES);
    }
    return Decision.allow();
  }

  private Decision recordAndBlock(String reason) {
    if (guardrailLevelResolver != null) guardrailLevelResolver.recordBlock("file");
    return Decision.block(reason);
  }

  public record Decision(boolean allowed, String reason) {
    public static Decision allow() {
      return new Decision(true, null);
    }

    public static Decision block(String reason) {
      return new Decision(false, reason);
    }
  }
}
