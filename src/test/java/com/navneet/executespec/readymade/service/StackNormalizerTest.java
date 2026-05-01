package com.navneet.executespec.readymade.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * R-329 — pins the {@code StackDetector} → variant-key mapping for both products (readymades +
 * migrations share the same canonical lowercase / hyphenated form).
 */
class StackNormalizerTest {

  @Test
  void normalizeLanguage_collapsesCompoundForm() {
    assertEquals("typescript", StackNormalizer.normalizeLanguage("TypeScript/JS"));
  }

  @Test
  void normalizeLanguage_lowercasesSimpleForm() {
    assertEquals("java", StackNormalizer.normalizeLanguage("Java"));
    assertEquals("python", StackNormalizer.normalizeLanguage("Python"));
    assertEquals("typescript", StackNormalizer.normalizeLanguage("TypeScript"));
  }

  @Test
  void normalizeLanguage_emptyAndNull() {
    assertEquals("", StackNormalizer.normalizeLanguage(null));
    assertEquals("", StackNormalizer.normalizeLanguage(""));
    assertEquals("", StackNormalizer.normalizeLanguage("   "));
  }

  @Test
  void normalizeFramework_hyphenatesSpaces() {
    assertEquals("spring-boot", StackNormalizer.normalizeFramework("Spring Boot"));
  }

  @Test
  void normalizeFramework_stripsDotsAndLowercases() {
    assertEquals("nextjs", StackNormalizer.normalizeFramework("Next.js"));
  }

  @Test
  void normalizeFramework_passesThroughCanonicalForms() {
    assertEquals("express", StackNormalizer.normalizeFramework("Express"));
    assertEquals("fastify", StackNormalizer.normalizeFramework("Fastify"));
    assertEquals("fastapi", StackNormalizer.normalizeFramework("FastAPI"));
  }

  @Test
  void normalizeFramework_emptyAndNull() {
    assertEquals("", StackNormalizer.normalizeFramework(null));
    assertEquals("", StackNormalizer.normalizeFramework(""));
  }
}
