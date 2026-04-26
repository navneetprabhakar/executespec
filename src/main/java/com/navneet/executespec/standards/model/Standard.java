package com.navneet.executespec.standards.model;

import java.util.List;

/**
 * E3.2 — strongly-typed standards model parsed from YAML.
 *
 * <p>Schema mirrors plan §5.3 — every field is optional except
 * {@code id}, {@code severity}, {@code description}, and
 * {@code enforcement}. The parser ({@link StandardsParser}) enforces
 * required-field validation at parse time so downstream consumers
 * (E3.3 EffectiveStandardsResolver / E3.10 worker+reviewer) never see
 * a half-constructed Standard.
 *
 * @param id              stable identifier (must be unique within document)
 * @param scope           where the standard applies (null = applies everywhere)
 * @param severity        BLOCKING | WARNING | ADVISORY
 * @param description     human-readable + LLM-fed description
 * @param enforcement     one of the {@link Enforcement} subtypes
 * @param rationale       optional, but the picky enterprise persona will write these
 * @param relatedAdrs     cross-link to ADR ids (E3.12)
 * @param orgLevelLocked  true → project-level cannot override
 */
public record Standard(
        String id,
        Scope scope,
        Severity severity,
        String description,
        Enforcement enforcement,
        String rationale,
        List<String> relatedAdrs,
        boolean orgLevelLocked) {

    /** Per plan §5.3 + §5.7. */
    public enum Severity { BLOCKING, WARNING, ADVISORY }

    /**
     * Where the standard applies. Every field is optional; an entirely-null
     * scope = "applies everywhere".
     *
     * @param languages     e.g. ["java", "typescript"]
     * @param paths         glob patterns to match repo paths
     * @param filePatterns  filename patterns
     * @param repos         monorepo-only — restrict to specific repos
     */
    public record Scope(
            List<String> languages,
            List<String> paths,
            List<String> filePatterns,
            List<String> repos) {
        public boolean isEmpty() {
            return (languages == null || languages.isEmpty())
                    && (paths == null || paths.isEmpty())
                    && (filePatterns == null || filePatterns.isEmpty())
                    && (repos == null || repos.isEmpty());
        }
    }

    /**
     * Polymorphic enforcement strategy. Every implementation is a
     * sealed-style record (the parser branches on the {@code type}
     * discriminator).
     */
    public sealed interface Enforcement
            permits LintEnforcement, FilePatternEnforcement, LlmReviewEnforcement, AstCheckEnforcement {
        String type();
    }

    public record LintEnforcement(String tool, String configPath) implements Enforcement {
        @Override public String type() { return "lint"; }
    }

    public record FilePatternEnforcement(
            List<String> forbiddenPatterns,
            List<String> requiredPatterns) implements Enforcement {
        @Override public String type() { return "file-pattern"; }
    }

    public record LlmReviewEnforcement(String reviewPrompt) implements Enforcement {
        @Override public String type() { return "llm-review"; }
    }

    public record AstCheckEnforcement(
            String ruleKind,
            java.util.Map<String, Object> parameters,
            String scriptPath) implements Enforcement {
        @Override public String type() { return "ast-check"; }
    }
}
