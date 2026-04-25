package com.navneet.executespec.folder;

import com.navneet.executespec.project.entity.ProjectRepo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link FolderReadmeGenerator} (E1.6). Loads the real template
 * from the classpath and asserts substitutions land where expected. Uses
 * the constructor-injected template override for isolated tests so future
 * template tweaks don't ripple through assertions.
 */
class FolderReadmeGeneratorTest {

    @Test
    void render_substitutesRepoFullNameAndProjectSlug() {
        FolderReadmeGenerator gen = new FolderReadmeGenerator();
        ProjectRepo repo = new ProjectRepo();
        repo.setGithubRepoFullName("acme/widget");

        String md = gen.render(repo, "acme-web");

        assertThat(md).contains("# `.executespec/` — for `acme/widget`");
        assertThat(md).contains("config/acme-web/standards.yaml");
        assertThat(md).contains("Generator version `" + FolderReadmeGenerator.GENERATOR_VERSION + "`");
    }

    @Test
    void render_substitutesPlaceholderSlug_whenProjectSlugBlank() {
        FolderReadmeGenerator gen = new FolderReadmeGenerator();
        ProjectRepo repo = new ProjectRepo();
        repo.setGithubRepoFullName("acme/widget");

        String md = gen.render(repo, null);

        assertThat(md).contains("config/<project-slug>/");
        assertThat(md).doesNotContain("config//"); // no double-slash from missing slug
    }

    @Test
    void render_handlesUnknownRepoNameGracefully() {
        FolderReadmeGenerator gen = new FolderReadmeGenerator();
        ProjectRepo repo = new ProjectRepo();
        // githubRepoFullName intentionally null — should not NPE
        String md = gen.render(repo, "demo");
        assertThat(md).contains("<unknown-repo>");
    }

    @Test
    void render_includesAutoSectionEndMarker() {
        // Future regen logic uses this marker to preserve manual edits below.
        FolderReadmeGenerator gen = new FolderReadmeGenerator();
        ProjectRepo repo = new ProjectRepo();
        repo.setGithubRepoFullName("acme/widget");

        String md = gen.render(repo, "demo");
        assertThat(md).contains("ExecuteSpec auto-section ends here");
    }

    @Test
    void render_throwsOnNullRepo() {
        FolderReadmeGenerator gen = new FolderReadmeGenerator();
        assertThatThrownBy(() -> gen.render(null, "demo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repo");
    }

    @Test
    void apply_substitutesAllPlaceholders_andLeavesUnknownsIntact() {
        String tpl = "Hello ${name}, welcome to ${product}. Version: ${unknown}";
        String out = FolderReadmeGenerator.apply(tpl, Map.of(
                "name", "Navneet",
                "product", "ExecuteSpec"));
        assertThat(out).isEqualTo("Hello Navneet, welcome to ExecuteSpec. Version: ${unknown}");
    }
}
