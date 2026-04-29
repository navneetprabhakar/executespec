package com.navneet.executespec.migration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.readymade.service.StackNormalizer;
import lombok.extern.slf4j.Slf4j;

/**
 * R-338 — checks whether a project_repo's detected stack matches a
 * migration recipe's {@code source_stack} predicate.
 *
 * <p>Pure stateless utility.  Reuses {@link StackNormalizer} for the
 * canonical lowercase / hyphenated form so the same normalisation
 * applies across both products.
 *
 * <p>Match semantics (v1, conservative):
 * <ul>
 *   <li>Recipe declares {@code language} → repo's normalised
 *       language must equal it.</li>
 *   <li>Recipe declares {@code framework} → repo's normalised
 *       framework must equal it.  Recipe omits framework → match
 *       any framework (recipe is language-only, e.g. JS → TS).</li>
 *   <li>{@code min_version} ignored in v1 — surfaced in the recipe
 *       data but not enforced; per-version checks land alongside
 *       W8 hybrid AST work where versions actually drive recipe
 *       behaviour.</li>
 * </ul>
 */
@Slf4j
public final class MigrationStackMatcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MigrationStackMatcher() {}

    /** Outcome of a (recipe.source_stack, repo.detected_stack) check. */
    public enum Outcome {
        APPLICABLE,
        INCOMPATIBLE_STACK,
        UNKNOWN_STACK
    }

    /**
     * @param recipeSourceStack  JSON object string from
     *                           {@code migration_recipe.source_stack}.
     *                           Required.
     * @param repoDetectedStack  JSON object string from
     *                           {@code project_repo.detected_stack}.
     *                           Null / blank → UNKNOWN_STACK.
     */
    public static Outcome match(String recipeSourceStack, String repoDetectedStack) {
        if (recipeSourceStack == null || recipeSourceStack.isBlank()) {
            // Defensive: a recipe without a source_stack predicate
            // shouldn't have shipped, but if it has, treat as
            // applicable to anything (don't block the user).
            return Outcome.APPLICABLE;
        }
        if (repoDetectedStack == null || repoDetectedStack.isBlank()) {
            return Outcome.UNKNOWN_STACK;
        }
        try {
            JsonNode recipe = MAPPER.readTree(recipeSourceStack);
            JsonNode repo = MAPPER.readTree(repoDetectedStack);

            String recipeLang = StackNormalizer.normalizeLanguage(recipe.path("language").asText(null));
            String repoLang = StackNormalizer.normalizeLanguage(repo.path("language").asText(null));
            if (recipeLang.isEmpty() || repoLang.isEmpty()) {
                return Outcome.UNKNOWN_STACK;
            }
            if (!recipeLang.equals(repoLang)) {
                return Outcome.INCOMPATIBLE_STACK;
            }

            // Framework check — recipe-side framework may be absent;
            // when absent, the recipe applies regardless of repo's
            // framework (e.g. JS → TS doesn't care about framework).
            String recipeFramework = StackNormalizer.normalizeFramework(
                recipe.path("framework").asText(null));
            if (!recipeFramework.isEmpty()) {
                String repoFramework = StackNormalizer.normalizeFramework(
                    repo.path("framework").asText(null));
                if (!recipeFramework.equals(repoFramework)) {
                    return Outcome.INCOMPATIBLE_STACK;
                }
            }

            return Outcome.APPLICABLE;
        } catch (Exception e) {
            log.debug("[R-338] stack-match parse failure: {}", e.getMessage());
            return Outcome.UNKNOWN_STACK;
        }
    }
}
