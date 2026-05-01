package com.navneet.executespec.validator.model;

public enum Language {
  JAVA,
  PYTHON,
  TYPESCRIPT,
  REACT,
  JAVASCRIPT;

  /**
   * Detect language from file extension and path conventions. Returns null for non-code files
   * (README, configs, images, etc.)
   */
  public static Language fromPath(java.nio.file.Path filePath) {
    String name = filePath.getFileName().toString().toLowerCase();

    // React detection: .tsx files OR .jsx files
    if (name.endsWith(".tsx") || name.endsWith(".jsx")) {
      return REACT;
    }
    // TypeScript: .ts files (not .d.ts declaration files — still validate those as TS)
    if (name.endsWith(".ts")) {
      return TYPESCRIPT;
    }
    // Java: .java files
    if (name.endsWith(".java")) {
      return JAVA;
    }
    // Python: .py files
    if (name.endsWith(".py")) {
      return PYTHON;
    }
    // Javascript: .js files
    if (name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs")) {
      return JAVASCRIPT;
    }
    // Non-code files — skip validation
    return null;
  }
}
