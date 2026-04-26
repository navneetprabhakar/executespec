package com.navneet.executespec.standards.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StandardsParserTest {

    private final StandardsParser parser = new StandardsParser();

    @Test
    void emptyOrBlankYaml_returnsEmptyList() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
    }

    @Test
    void parsesLintStandardWithFullScope() {
        String yaml = """
            - id: no-checked-in-secrets
              scope:
                languages: [java]
                paths: ['src/**']
              severity: blocking
              description: Block secrets from being committed
              enforcement:
                type: lint
                tool: checkstyle
                config: .executespec/lint/checkstyle.xml
              rationale: Compliance requirement from legal
              related_adrs: [adr-0042]
              org_level_locked: true
            """;
        List<Standard> standards = parser.parse(yaml);
        assertThat(standards).hasSize(1);
        Standard s = standards.get(0);
        assertThat(s.id()).isEqualTo("no-checked-in-secrets");
        assertThat(s.severity()).isEqualTo(Standard.Severity.BLOCKING);
        assertThat(s.scope().languages()).containsExactly("java");
        assertThat(s.scope().paths()).containsExactly("src/**");
        assertThat(s.enforcement()).isInstanceOf(Standard.LintEnforcement.class);
        Standard.LintEnforcement le = (Standard.LintEnforcement) s.enforcement();
        assertThat(le.tool()).isEqualTo("checkstyle");
        assertThat(le.configPath()).endsWith("checkstyle.xml");
        assertThat(s.relatedAdrs()).containsExactly("adr-0042");
        assertThat(s.orgLevelLocked()).isTrue();
    }

    @Test
    void parsesFilePatternEnforcement() {
        String yaml = """
            - id: no-flyway-skip
              severity: warning
              description: Reject Flyway migrations with skip names
              enforcement:
                type: file-pattern
                forbidden_patterns:
                  - "src/main/resources/db/migration/V[0-9]+__SKIP*.sql"
                required_patterns:
                  - "src/main/resources/db/migration/V*.sql"
            """;
        List<Standard> standards = parser.parse(yaml);
        assertThat(standards).hasSize(1);
        Standard.FilePatternEnforcement fpe = (Standard.FilePatternEnforcement) standards.get(0).enforcement();
        assertThat(fpe.forbiddenPatterns()).hasSize(1);
        assertThat(fpe.requiredPatterns()).hasSize(1);
    }

    @Test
    void parsesLlmReviewEnforcement() {
        String yaml = """
            - id: no-pii-in-logs
              severity: blocking
              description: Reject log statements that include PII fields
              enforcement:
                type: llm-review
                review_prompt: "Examine all logging statements; flag any that include 'email', 'phone', or 'ssn'."
            """;
        Standard s = parser.parse(yaml).get(0);
        Standard.LlmReviewEnforcement lre = (Standard.LlmReviewEnforcement) s.enforcement();
        assertThat(lre.reviewPrompt()).contains("Examine all logging statements");
    }

    @Test
    void parsesAstCheckEnforcement_withDeclarativeRule() {
        String yaml = """
            - id: dao-must-extend
              severity: warning
              description: Repository classes must extend BaseRepository
              enforcement:
                type: ast-check
                rule:
                  kind: class-must-extend
                  pattern: '*Repository'
                  base: BaseRepository
            """;
        Standard s = parser.parse(yaml).get(0);
        Standard.AstCheckEnforcement ace = (Standard.AstCheckEnforcement) s.enforcement();
        assertThat(ace.ruleKind()).isEqualTo("class-must-extend");
        assertThat(ace.parameters()).containsEntry("pattern", "*Repository");
        assertThat(ace.parameters()).containsEntry("base", "BaseRepository");
        assertThat(ace.scriptPath()).isNull();
    }

    @Test
    void astCheck_customKind_requiresScriptPath() {
        String yaml = """
            - id: complex-rule
              severity: advisory
              description: Custom complexity check
              enforcement:
                type: ast-check
                rule:
                  kind: custom
            """;
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(StandardsParser.ParseException.class)
                .hasMessageContaining("rule.kind=custom requires rule.script");
    }

    @Test
    void duplicateIds_collected_inSinglePassError() {
        String yaml = """
            - id: dup
              severity: blocking
              description: first
              enforcement: { type: lint, tool: eslint }
            - id: dup
              severity: warning
              description: second
              enforcement: { type: lint, tool: eslint }
            """;
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(StandardsParser.ParseException.class)
                .hasMessageContaining("duplicate id 'dup'");
    }

    @Test
    void everyRequiredFieldErrorReportedInOnePass() {
        String yaml = """
            - id: missing-severity
              description: x
              enforcement: { type: lint, tool: eslint }
            - id: missing-description
              severity: blocking
              enforcement: { type: lint, tool: eslint }
            - id: missing-enforcement
              severity: blocking
              description: x
            """;
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(StandardsParser.ParseException.class)
                .satisfies(ex -> assertThat(((StandardsParser.ParseException) ex).errors).hasSize(3));
    }

    @Test
    void invalidSeverity_collected() {
        String yaml = """
            - id: x
              severity: super-blocking
              description: x
              enforcement: { type: lint, tool: eslint }
            """;
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(StandardsParser.ParseException.class)
                .hasMessageContaining("invalid severity 'super-blocking'");
    }

    @Test
    void unknownEnforcementType_collected() {
        String yaml = """
            - id: x
              severity: blocking
              description: x
              enforcement: { type: future-strategy }
            """;
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(StandardsParser.ParseException.class)
                .hasMessageContaining("unknown enforcement.type 'future-strategy'");
    }

    @Test
    void filePattern_requiresAtLeastOnePatternList() {
        String yaml = """
            - id: empty-pattern
              severity: blocking
              description: x
              enforcement: { type: file-pattern }
            """;
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(StandardsParser.ParseException.class)
                .hasMessageContaining("requires at least 'forbidden_patterns'");
    }

    @Test
    void emptyScope_normalisedToNull() {
        String yaml = """
            - id: simple
              scope: {}
              severity: warning
              description: x
              enforcement: { type: lint, tool: eslint }
            """;
        Standard s = parser.parse(yaml).get(0);
        assertThat(s.scope()).isNull();
    }
}
