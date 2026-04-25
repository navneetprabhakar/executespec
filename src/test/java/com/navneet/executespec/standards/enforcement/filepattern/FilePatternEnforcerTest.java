package com.navneet.executespec.standards.enforcement.filepattern;

import com.navneet.executespec.standards.model.Standard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilePatternEnforcerTest {

    private final FilePatternEnforcer enforcer = new FilePatternEnforcer();

    @Test
    void forbiddenPattern_emitsOneViolationPerMatchingFile() {
        // DetectorUtils.matchesGlob supports **, *, ? — not regex-style char
        // classes / quantifiers (those should be ast-check, not file-pattern).
        Standard.FilePatternEnforcement e = new Standard.FilePatternEnforcement(
                List.of("**/V*__SKIP*.sql"), List.of());
        List<String> files = List.of(
                "src/main/resources/db/migration/V42__SKIP_me.sql",
                "src/main/resources/db/migration/V43__SKIP_other.sql",
                "src/main/resources/db/migration/V44__legit.sql");
        List<FilePatternEnforcer.Violation> v = enforcer.enforce(e, files);
        assertThat(v).hasSize(2);
        assertThat(v).allSatisfy(x ->
                assertThat(x.kind()).isEqualTo(FilePatternEnforcer.Violation.Kind.FORBIDDEN_FILE_PRESENT));
        assertThat(v.get(0).file()).contains("SKIP_me");
    }

    @Test
    void requiredPattern_missingTriggersOneViolation() {
        Standard.FilePatternEnforcement e = new Standard.FilePatternEnforcement(
                List.of(), List.of("**/changelog-*.yaml"));
        List<String> files = List.of("src/main/Foo.java", "README.md");
        List<FilePatternEnforcer.Violation> v = enforcer.enforce(e, files);
        assertThat(v).hasSize(1);
        assertThat(v.get(0).kind()).isEqualTo(FilePatternEnforcer.Violation.Kind.REQUIRED_FILE_MISSING);
        assertThat(v.get(0).pattern()).isEqualTo("**/changelog-*.yaml");
        assertThat(v.get(0).file()).isNull();
    }

    @Test
    void requiredPattern_presentSatisfiesRule_noViolation() {
        Standard.FilePatternEnforcement e = new Standard.FilePatternEnforcement(
                List.of(), List.of("**/changelog-*.yaml"));
        List<String> files = List.of("docs/changelog-2026.yaml");
        List<FilePatternEnforcer.Violation> v = enforcer.enforce(e, files);
        assertThat(v).isEmpty();
    }

    @Test
    void mixedForbiddenAndRequired_bothEvaluated() {
        Standard.FilePatternEnforcement e = new Standard.FilePatternEnforcement(
                List.of("**/.env"), List.of("**/.gitignore"));
        List<String> files = List.of("backend/.env", "frontend/.env"); // .env present, .gitignore absent
        List<FilePatternEnforcer.Violation> v = enforcer.enforce(e, files);
        assertThat(v).hasSize(3); // 2 forbidden + 1 missing-required
        assertThat(v.stream().filter(x -> x.kind() == FilePatternEnforcer.Violation.Kind.FORBIDDEN_FILE_PRESENT)
                .count()).isEqualTo(2);
        assertThat(v.stream().filter(x -> x.kind() == FilePatternEnforcer.Violation.Kind.REQUIRED_FILE_MISSING)
                .count()).isEqualTo(1);
    }

    @Test
    void emptyOrNullEnforcement_returnsEmpty() {
        assertThat(enforcer.enforce(null, List.of("a.txt"))).isEmpty();
    }

    @Test
    void nullFileList_returnsEmpty() {
        Standard.FilePatternEnforcement e = new Standard.FilePatternEnforcement(
                List.of("**/*.bad"), List.of());
        assertThat(enforcer.enforce(e, null)).isEmpty();
    }

    @Test
    void blankPatterns_skippedSafely() {
        Standard.FilePatternEnforcement e = new Standard.FilePatternEnforcement(
                java.util.Arrays.asList("", "   ", null), List.of());
        assertThat(enforcer.enforce(e, List.of("a.txt", "b.txt"))).isEmpty();
    }
}
