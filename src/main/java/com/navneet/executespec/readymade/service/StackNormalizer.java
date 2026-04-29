package com.navneet.executespec.readymade.service;

/**
 * R-329 — normalises the {@code StackDetector} output strings into the
 * canonical lowercase / hyphenated form used by
 * {@link com.navneet.executespec.readymade.entity.ReadymadeVariant}
 * (and the equivalent migration-recipe schema).
 *
 * <p>{@code StackDetector} returns title-cased, human-readable values
 * like {@code "TypeScript/JS"} + {@code "Spring Boot"}; variants are
 * authored with {@code "typescript"} + {@code "spring-boot"}.  This
 * is the bridge.
 *
 * <p>Pure static utility — no Spring ceremony, no I/O, no state.
 */
public final class StackNormalizer {

    private StackNormalizer() {}

    /**
     * Normalise a language string to the canonical key used by
     * variant authoring.  Examples:
     * <pre>
     *   "TypeScript/JS"     → "typescript"
     *   "TypeScript"        → "typescript"
     *   "JavaScript"        → "javascript"
     *   "Java"              → "java"
     *   "Python"            → "python"
     *   "Go"                → "go"
     *   null / blank        → ""
     * </pre>
     *
     * <p>For the {@code "TypeScript/JS"} compound case (which
     * {@code StackDetector} emits when the repo has a mix of .ts and
     * .js files), we collapse to {@code "typescript"} since the TS
     * variant of any solution will work for a JS file via tsc's
     * allowJs path. Preferring the safer end of the choice.
     */
    public static String normalizeLanguage(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase();
        if (s.isEmpty()) return "";
        // Compound form from StackDetector
        if (s.contains("/")) s = s.split("/")[0].trim();
        return s.replace(' ', '-');
    }

    /**
     * Normalise a framework string to the canonical key.  Examples:
     * <pre>
     *   "Spring Boot" → "spring-boot"
     *   "Next.js"     → "nextjs"
     *   "FastAPI"     → "fastapi"
     *   "Express"     → "express"
     *   "" / null     → ""
     * </pre>
     */
    public static String normalizeFramework(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase();
        if (s.isEmpty()) return "";
        // Strip dots ("next.js" → "nextjs")
        s = s.replace(".", "");
        // Hyphenate spaces ("spring boot" → "spring-boot")
        s = s.replace(' ', '-');
        return s;
    }
}
