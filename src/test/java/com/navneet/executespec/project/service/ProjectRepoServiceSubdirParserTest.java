package com.navneet.executespec.project.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * R-322 (D-125 #2) — locks in the {@code .executespec/root.yml}
 * parsing rules.  The marker is a fixed-shape one-line file
 * ({@code root: <subdir>}) so we hand-roll a tolerant parser instead
 * of depending on SnakeYAML's full machinery; the tests below pin the
 * tolerance + safety gates so future edits don't quietly regress.
 */
class ProjectRepoServiceSubdirParserTest {

    @Test
    void parsesPlainKeyValue() {
        assertEquals("apps/web",
                ProjectRepoService.parseRootYamlSubdir("root: apps/web\n"));
    }

    @Test
    void parsesDoubleQuotedValue() {
        assertEquals("apps/web",
                ProjectRepoService.parseRootYamlSubdir("root: \"apps/web\""));
    }

    @Test
    void parsesSingleQuotedValue() {
        assertEquals("apps/web",
                ProjectRepoService.parseRootYamlSubdir("root: 'apps/web'"));
    }

    @Test
    void stripsLeadingDotSlash() {
        assertEquals("apps/web",
                ProjectRepoService.parseRootYamlSubdir("root: ./apps/web"));
    }

    @Test
    void stripsTrailingSlash() {
        assertEquals("apps/web",
                ProjectRepoService.parseRootYamlSubdir("root: apps/web/"));
    }

    @Test
    void ignoresCommentsAndBlankLines() {
        String body = """
                # ExecuteSpec project root marker
                # See README for the full schema.

                root: services/api
                """;
        assertEquals("services/api",
                ProjectRepoService.parseRootYamlSubdir(body));
    }

    @Test
    void ignoresInlineComment() {
        assertEquals("apps/web",
                ProjectRepoService.parseRootYamlSubdir("root: apps/web   # main app"));
    }

    @Test
    void rejectsAbsolutePath() {
        assertNull(ProjectRepoService.parseRootYamlSubdir("root: /etc/passwd"));
    }

    @Test
    void rejectsTraversalSegment() {
        assertNull(ProjectRepoService.parseRootYamlSubdir("root: ../../etc"));
    }

    @Test
    void rejectsBlankValue() {
        assertNull(ProjectRepoService.parseRootYamlSubdir("root:"));
        assertNull(ProjectRepoService.parseRootYamlSubdir("root: \"\""));
    }

    @Test
    void rejectsMissingKey() {
        assertNull(ProjectRepoService.parseRootYamlSubdir("# nothing here"));
        assertNull(ProjectRepoService.parseRootYamlSubdir(""));
        assertNull(ProjectRepoService.parseRootYamlSubdir(null));
    }

    @Test
    void rejectsOversizedValue() {
        StringBuilder b = new StringBuilder("root: ");
        for (int i = 0; i < 600; i++) b.append('a');
        assertNull(ProjectRepoService.parseRootYamlSubdir(b.toString()));
    }
}
