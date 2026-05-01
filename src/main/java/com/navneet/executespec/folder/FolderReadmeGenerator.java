package com.navneet.executespec.folder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import com.navneet.executespec.project.entity.ProjectRepo;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * E1.6 — generates the {@code .executespec/README.md} content for a given {@link ProjectRepo}. The
 * template lives at {@code resources/templates/executespec/README.md.template} and uses the
 * Spring-style {@code ${var}} placeholder syntax for substitutions (kept simple on purpose — no
 * Thymeleaf overhead for a one-shot doc).
 *
 * <p>The output is structured so customer manual edits below the "auto-section ends here" comment
 * are preserved across regenerations (the regen logic — landing in E1.5 — diff-merges only the
 * auto-section).
 *
 * <p>Generator version is bumped whenever the template's structure changes (new section, renamed
 * path, etc.); printing it in the README footer lets us reproduce a given README's intent later
 * without git archaeology.
 */
@Service
@Slf4j
public class FolderReadmeGenerator {

  /**
   * Generator version. Bump when the template's structure changes materially. Read in tests +
   * recorded in README footer.
   */
  public static final String GENERATOR_VERSION = "1";

  private static final String TEMPLATE_PATH = "templates/executespec/README.md.template";

  private final String template;

  public FolderReadmeGenerator() {
    this.template = loadTemplate();
  }

  /** Test seam — substitute the template content directly. */
  FolderReadmeGenerator(String templateOverride) {
    this.template = templateOverride;
  }

  /**
   * Render the README for the given repo + project.
   *
   * @param repo target repo (its full name appears in the title)
   * @param projectSlug the slug used to namespace {@code .executespec/config/<slug>/...} files;
   *     {@code null} or blank substitutes the placeholder text {@code <project-slug>} so the
   *     example layout is still readable on a fresh repo
   * @return rendered markdown
   */
  public String render(ProjectRepo repo, String projectSlug) {
    if (repo == null) {
      throw new IllegalArgumentException("repo must not be null");
    }
    String repoFullName =
        repo.getGithubRepoFullName() == null ? "<unknown-repo>" : repo.getGithubRepoFullName();
    String slug = (projectSlug == null || projectSlug.isBlank()) ? "<project-slug>" : projectSlug;
    String generatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    return apply(
        template,
        Map.of(
            "repoFullName", repoFullName,
            "configScope", slug,
            "generatedAt", generatedAt,
            "generatorVersion", GENERATOR_VERSION));
  }

  // ── Internal ────────────────────────────────────────────────────────────

  /**
   * Tiny {@code ${var}} substituter — no escaping, no defaults, no nested lookups. The template
   * lives in our own JAR so we trust its content; the variables are limited to what {@link #render}
   * populates.
   */
  static String apply(String template, Map<String, String> vars) {
    String out = template;
    for (Map.Entry<String, String> e : vars.entrySet()) {
      out = out.replace("${" + e.getKey() + "}", e.getValue());
    }
    return out;
  }

  private static String loadTemplate() {
    try {
      ClassPathResource res = new ClassPathResource(TEMPLATE_PATH);
      return new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException(
          "could not load " + TEMPLATE_PATH + " — is the template on the classpath?", ex);
    }
  }
}
