package com.navneet.executespec.migration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.navneet.executespec.migration.service.MigrationStackMatcher.Outcome;

import org.junit.jupiter.api.Test;

/**
 * R-338 — pins the (recipe.source_stack, repo.detected_stack) matching rules. These are the gate
 * that determines whether the dry-run says APPLICABLE / INCOMPATIBLE_STACK / UNKNOWN_STACK.
 */
class MigrationStackMatcherTest {

  @Test
  void languageOnlyRecipe_matchesAnyFramework() {
    // JS → TS recipe targets language=javascript; framework
    // doesn't matter (works on Express, Fastify, plain Node...).
    String recipe = "{\"language\":\"javascript\"}";
    String repo = "{\"language\":\"JavaScript\",\"framework\":\"Express\"}";
    assertEquals(Outcome.APPLICABLE, MigrationStackMatcher.match(recipe, repo));
  }

  @Test
  void languageMismatch_isIncompatible() {
    String recipe = "{\"language\":\"typescript\",\"framework\":\"express\"}";
    String repo = "{\"language\":\"Python\",\"framework\":\"Flask\"}";
    assertEquals(Outcome.INCOMPATIBLE_STACK, MigrationStackMatcher.match(recipe, repo));
  }

  @Test
  void frameworkMismatch_isIncompatible() {
    String recipe = "{\"language\":\"typescript\",\"framework\":\"express\"}";
    String repo = "{\"language\":\"TypeScript/JS\",\"framework\":\"Fastify\"}";
    assertEquals(Outcome.INCOMPATIBLE_STACK, MigrationStackMatcher.match(recipe, repo));
  }

  @Test
  void exactMatchAfterNormalization() {
    // Recipe authors "express"; StackDetector emits "Express";
    // the normalizer collapses both to "express".
    String recipe = "{\"language\":\"typescript\",\"framework\":\"express\"}";
    String repo = "{\"language\":\"TypeScript/JS\",\"framework\":\"Express\"}";
    assertEquals(Outcome.APPLICABLE, MigrationStackMatcher.match(recipe, repo));
  }

  @Test
  void springBootDottedFramework_matches() {
    // "spring-boot" recipe key ↔ "Spring Boot" detector value.
    String recipe = "{\"language\":\"java\",\"framework\":\"spring-boot\"}";
    String repo = "{\"language\":\"Java\",\"framework\":\"Spring Boot\"}";
    assertEquals(Outcome.APPLICABLE, MigrationStackMatcher.match(recipe, repo));
  }

  @Test
  void nullDetectedStack_isUnknown() {
    String recipe = "{\"language\":\"typescript\",\"framework\":\"express\"}";
    assertEquals(Outcome.UNKNOWN_STACK, MigrationStackMatcher.match(recipe, null));
    assertEquals(Outcome.UNKNOWN_STACK, MigrationStackMatcher.match(recipe, ""));
  }

  @Test
  void emptyRepoLanguage_isUnknown() {
    String recipe = "{\"language\":\"typescript\"}";
    String repo = "{\"language\":\"\",\"framework\":\"Express\"}";
    assertEquals(Outcome.UNKNOWN_STACK, MigrationStackMatcher.match(recipe, repo));
  }

  @Test
  void malformedJson_isUnknown() {
    String recipe = "{\"language\":\"typescript\"}";
    assertEquals(Outcome.UNKNOWN_STACK, MigrationStackMatcher.match(recipe, "{not valid"));
  }

  @Test
  void recipeWithoutSourceStack_isApplicable() {
    // Defensive — a recipe author shouldn't ship without a
    // source_stack predicate, but if they do we don't want to
    // hard-block users.
    String repo = "{\"language\":\"TypeScript/JS\"}";
    assertEquals(Outcome.APPLICABLE, MigrationStackMatcher.match(null, repo));
    assertEquals(Outcome.APPLICABLE, MigrationStackMatcher.match("", repo));
  }
}
