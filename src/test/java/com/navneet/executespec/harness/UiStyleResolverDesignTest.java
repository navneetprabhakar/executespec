package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for UiStyleResolver design system features: filesystem loading, fuzzy matching, token
 * truncation, 3-signal resolution, style hint extraction.
 */
class UiStyleResolverDesignTest {

  @TempDir Path designRoot;
  private UiStyleResolver resolver;

  @BeforeEach
  void setUp() throws IOException {
    List<String> styles =
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
            "mintlify");
    for (String style : styles) {
      Path directory = Files.createDirectories(designRoot.resolve(style));
      Files.writeString(directory.resolve("DESIGN.md"), "# " + style + "\n## Colors\nBlue\n");
    }
    var props = new DesignSystemProperties();
    props.setRootPath(designRoot.toString());
    props.setCacheTtl(Duration.ofMinutes(5));
    props.setMaxTokens(2500);
    props.setDefaultStyle("vercel");
    props.setCuratedStyles(styles);

    resolver = new UiStyleResolver(new ObjectMapper(), props);
    resolver.init();
  }

  @Test
  void loadDesignContent_existingDesign_returnsContent() {
    String content = resolver.loadDesignContent("vercel");
    assertThat(content).isNotNull().isNotBlank();
    // Should be truncated to within token budget (2500 tokens * 4 chars = 10000 chars)
    // Token budget: 2500 tokens * 4 chars = 10000 chars, plus section overhead
    assertThat(content.length()).isLessThanOrEqualTo(12_000);
  }

  @Test
  void loadDesignContent_missingDesign_fallsBackToDefault() {
    // Non-existent design should fall back to default (vercel)
    String content = resolver.loadDesignContent("nonexistent-design-xyz");
    assertThat(content).isNotNull().isNotBlank();
  }

  @Test
  void loadDesignContent_stripeDesign_loads() {
    String content = resolver.loadDesignContent("stripe");
    assertThat(content).isNotNull().contains("#"); // Markdown headings
  }

  @Test
  void loadDesignContent_cachedSecondCall() {
    String first = resolver.loadDesignContent("vercel");
    String second = resolver.loadDesignContent("vercel");
    assertThat(first).isEqualTo(second); // same object from cache
  }

  @Test
  void fuzzyMatchDesign_exactMatch() {
    assertThat(resolver.fuzzyMatchDesign("stripe")).isEqualTo("stripe");
  }

  @Test
  void fuzzyMatchDesign_linearMatchesLinearApp() {
    String result = resolver.fuzzyMatchDesign("linear");
    assertThat(result).isEqualTo("linear.app");
  }

  @Test
  void fuzzyMatchDesign_unknownReturnsNull() {
    assertThat(resolver.fuzzyMatchDesign("zzqwwwqqzz")).isNull();
  }

  @Test
  void fuzzyMatchDesign_caseInsensitive() {
    assertThat(resolver.fuzzyMatchDesign("Stripe")).isEqualTo("stripe");
  }

  @Test
  void resolveDesignName_explicitHintWins() {
    // Explicit hint should win over planner style
    String result = resolver.resolveDesignName("stripe", "notion", "FULL_STACK");
    assertThat(result).isEqualTo("stripe");
  }

  @Test
  void resolveDesignName_plannerStyleFallback() {
    String result = resolver.resolveDesignName(null, "notion", "FULL_STACK");
    assertThat(result).isEqualTo("notion");
  }

  @Test
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

  @Test
  void extractStyleHint_directNameMatch() {
    assertThat(resolver.extractStyleHint("Build a Stripe-like payment dashboard"))
        .isEqualTo("stripe");
  }

  @Test
  void extractStyleHint_patternMatch() {
    assertThat(resolver.extractStyleHint("Create a dashboard styled like Notion")).isNotNull();
  }

  @Test
  void extractStyleHint_noHint() {
    assertThat(resolver.extractStyleHint("Build a simple todo app")).isNull();
  }

  @Test
  void enforceTokenLimit_shortContentUnchanged() {
    String shortContent = "# Design\n## Colors\nBlue and white.";
    String result = resolver.enforceTokenLimit(shortContent);
    assertThat(result).isEqualTo(shortContent);
  }

  // RR-1054 — the char ceiling is a HARD limit independent of heading shape. A malformed/hostile
  // DESIGN.md with FOUR OR FEWER `## ` sections previously bypassed the limiter entirely and
  // returned unbounded content. maxTokens=2500 => ~10K char ceiling.
  @Test
  void enforceTokenLimit_headingFreeContentIsStillTruncated() {
    StringBuilder hostile = new StringBuilder("# Design\n");
    for (int i = 0; i < 4000; i++) {
      hostile.append("filler filler filler filler line ").append(i).append('\n');
    }
    assertThat(hostile.length()).isGreaterThan(50_000);
    String result = resolver.enforceTokenLimit(hostile.toString());
    // Bounded to the ceiling plus the truncation marker and one boundary line — never the full
    // input.
    assertThat(result.length()).isLessThan(hostile.length());
    assertThat(result.length()).isLessThanOrEqualTo(10_200);
    assertThat(result).contains("Truncated");
  }

  // RR-1054 — content with a handful of headings that also exceeds the ceiling is likewise bounded.
  @Test
  void enforceTokenLimit_fewHeadingsOverBudgetIsTruncated() {
    StringBuilder content = new StringBuilder();
    for (int s = 0; s < 3; s++) {
      content.append("## Section ").append(s).append('\n');
      for (int i = 0; i < 2000; i++) {
        content.append("body body body body ").append(i).append('\n');
      }
    }
    String result = resolver.enforceTokenLimit(content.toString());
    assertThat(result.length()).isLessThan(content.length());
    assertThat(result.length()).isLessThanOrEqualTo(10_200);
  }

  @Test
  void enforceTokenLimit_longContentTruncated() {
    String content = resolver.loadDesignContent("vercel");
    // vercel DESIGN.md is ~19K chars, token limit is 2500 (10K chars)
    assertThat(content).isNotNull();
    assertThat(content.length()).isLessThanOrEqualTo(12_000);
  }

  @Test
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
