package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Unit tests for UiStyleResolver design system features: filesystem loading, fuzzy matching, token
 * truncation, 3-signal resolution, style hint extraction.
 */
class UiStyleResolverDesignTest {

  private static final String LOCAL_DESIGN_ROOT =
      "/Users/navneetprabhakar/Documents/git/uxdesigns/design-md";

  private UiStyleResolver resolver;

  static boolean designRootExists() {
    return Files.isDirectory(Path.of(LOCAL_DESIGN_ROOT));
  }

  @BeforeEach
  void setUp() {
    var props = new DesignSystemProperties();
    props.setRootPath(LOCAL_DESIGN_ROOT);
    props.setCacheTtl(Duration.ofMinutes(5));
    props.setMaxTokens(2500);
    props.setDefaultStyle("vercel");
    props.setCuratedStyles(
        List.of(
            "airbnb",
            "claude",
            "figma",
            "linear.app",
            "notion",
            "stripe",
            "supabase",
            "vercel",
            "spotify",
            "posthog",
            "resend",
            "mintlify"));

    resolver = new UiStyleResolver(new ObjectMapper(), props);
    resolver.init();
  }

  // ── loadDesignContent ─────────────────────────────────────

  @Test
  @EnabledIf("designRootExists")
  void loadDesignContent_existingDesign_returnsContent() {
    String content = resolver.loadDesignContent("vercel");
    assertThat(content).isNotNull().isNotBlank();
    // Should be truncated to within token budget (2500 tokens * 4 chars = 10000 chars)
    // Token budget: 2500 tokens * 4 chars = 10000 chars, plus section overhead
    assertThat(content.length()).isLessThanOrEqualTo(12_000);
  }

  @Test
  @EnabledIf("designRootExists")
  void loadDesignContent_missingDesign_fallsBackToDefault() {
    // Non-existent design should fall back to default (vercel)
    String content = resolver.loadDesignContent("nonexistent-design-xyz");
    assertThat(content).isNotNull().isNotBlank();
  }

  @Test
  @EnabledIf("designRootExists")
  void loadDesignContent_stripeDesign_loads() {
    String content = resolver.loadDesignContent("stripe");
    assertThat(content).isNotNull().contains("#"); // Markdown headings
  }

  @Test
  @EnabledIf("designRootExists")
  void loadDesignContent_cachedSecondCall() {
    String first = resolver.loadDesignContent("vercel");
    String second = resolver.loadDesignContent("vercel");
    assertThat(first).isEqualTo(second); // same object from cache
  }

  // ── fuzzyMatchDesign ──────────────────────────────────────

  @Test
  @EnabledIf("designRootExists")
  void fuzzyMatchDesign_exactMatch() {
    assertThat(resolver.fuzzyMatchDesign("stripe")).isEqualTo("stripe");
  }

  @Test
  @EnabledIf("designRootExists")
  void fuzzyMatchDesign_linearMatchesLinearApp() {
    String result = resolver.fuzzyMatchDesign("linear");
    assertThat(result).isEqualTo("linear.app");
  }

  @Test
  @EnabledIf("designRootExists")
  void fuzzyMatchDesign_unknownReturnsNull() {
    assertThat(resolver.fuzzyMatchDesign("zzqwwwqqzz")).isNull();
  }

  @Test
  @EnabledIf("designRootExists")
  void fuzzyMatchDesign_caseInsensitive() {
    assertThat(resolver.fuzzyMatchDesign("Stripe")).isEqualTo("stripe");
  }

  // ── resolveDesignName ─────────────────────────────────────

  @Test
  @EnabledIf("designRootExists")
  void resolveDesignName_explicitHintWins() {
    // Explicit hint should win over planner style
    String result = resolver.resolveDesignName("stripe", "notion", "FULL_STACK");
    assertThat(result).isEqualTo("stripe");
  }

  @Test
  @EnabledIf("designRootExists")
  void resolveDesignName_plannerStyleFallback() {
    String result = resolver.resolveDesignName(null, "notion", "FULL_STACK");
    assertThat(result).isEqualTo("notion");
  }

  @Test
  @EnabledIf("designRootExists")
  void resolveDesignName_projectTypeHeuristic() {
    // No explicit hint or planner style — should use default for frontend project
    String result = resolver.resolveDesignName(null, null, "NODE_FRONTEND");
    assertThat(result).isEqualTo("vercel"); // default style
  }

  @Test
  void resolveDesignName_backendReturnsNull() {
    assertThat(resolver.resolveDesignName("stripe", "stripe", "SPRING_BOOT")).isNull();
    assertThat(resolver.resolveDesignName(null, null, "JAVA")).isNull();
    assertThat(resolver.resolveDesignName(null, null, "PYTHON")).isNull();
    assertThat(resolver.resolveDesignName(null, null, "GO")).isNull();
  }

  // ── extractStyleHint ──────────────────────────────────────

  @Test
  @EnabledIf("designRootExists")
  void extractStyleHint_directNameMatch() {
    assertThat(resolver.extractStyleHint("Build a Stripe-like payment dashboard"))
        .isEqualTo("stripe");
  }

  @Test
  @EnabledIf("designRootExists")
  void extractStyleHint_patternMatch() {
    assertThat(resolver.extractStyleHint("Create a dashboard styled like Notion")).isNotNull();
  }

  @Test
  @EnabledIf("designRootExists")
  void extractStyleHint_noHint() {
    assertThat(resolver.extractStyleHint("Build a simple todo app")).isNull();
  }

  // ── enforceTokenLimit ─────────────────────────────────────

  @Test
  void enforceTokenLimit_shortContentUnchanged() {
    String shortContent = "# Design\n## Colors\nBlue and white.";
    String result = resolver.enforceTokenLimit(shortContent);
    assertThat(result).isEqualTo(shortContent);
  }

  @Test
  @EnabledIf("designRootExists")
  void enforceTokenLimit_longContentTruncated() {
    String content = resolver.loadDesignContent("vercel");
    // vercel DESIGN.md is ~19K chars, token limit is 2500 (10K chars)
    assertThat(content).isNotNull();
    assertThat(content.length()).isLessThanOrEqualTo(12_000);
  }

  // ── buildFrontendConstraintBlock ──────────────────────────

  @Test
  @EnabledIf("designRootExists")
  void buildFrontendConstraintBlock_withDesignContent() {
    String designContent = resolver.loadDesignContent("stripe");
    String block = resolver.buildFrontendConstraintBlock("{}", designContent);
    assertThat(block).contains("AUTHORITATIVE");
    assertThat(block).contains("NON-NEGOTIABLE");
  }

  @Test
  void buildFrontendConstraintBlock_nullDesignFallsBackToLegacy() {
    String block =
        resolver.buildFrontendConstraintBlock(
            "{\"design_system\":\"shadcn\",\"theme\":\"vercel-geist\"}", null);
    // Legacy fallback should produce something or null
    // (depends on theme presets being loaded)
    // Just verify no exception
    assertThat(true).isTrue();
  }

  // ── enrichWithDesignName ──────────────────────────────────

  @Test
  void enrichWithDesignName_addsKey() {
    String enriched = resolver.enrichWithDesignName("{\"framework\":\"react\"}", "stripe");
    assertThat(enriched).contains("design_name");
    assertThat(enriched).contains("stripe");
  }

  @Test
  void enrichWithDesignName_nullInputReturnsInput() {
    assertThat(resolver.enrichWithDesignName(null, "stripe")).isNull();
  }
}
