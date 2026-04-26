package com.navneet.executespec.arch.invariants;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InvariantCheckerTest {

    private final InvariantChecker checker = new InvariantChecker();

    private InvariantChecker.ContentLoader fakeLoader(Map<String, String> files) {
        return files::get;
    }

    @Test
    void exclusiveWriter_flagsForbiddenWriters() {
        String yaml = """
                id: payments-only-writes-payments
                description: only payment-service writes to payments
                enforcement:
                  rule:
                    kind: exclusive-writer
                    table: payments
                    allowed_writers: [payment-service]
                """;
        List<String> files = List.of(
                "payment-service/repo.java",     // allowed
                "audit-service/payment-audit.java"); // forbidden
        Map<String, String> contents = Map.of(
                "payment-service/repo.java", "INSERT INTO payments (...) VALUES (...);",
                "audit-service/payment-audit.java", "UPDATE payments SET status='audited';");
        List<InvariantChecker.Finding> f = checker.check(yaml, files, fakeLoader(contents));
        assertThat(f).hasSize(1);
        assertThat(f.get(0).file()).contains("audit-service");
        assertThat(f.get(0).message()).contains("not in allowed_writers");
    }

    @Test
    void noDirectCall_flagsDirectReference() {
        String yaml = """
                id: api-cannot-call-storage
                description: api must go via service-layer
                enforcement:
                  rule:
                    kind: no-direct-call
                    from: api/
                    forbidden_target: storage.S3Client
                """;
        List<String> files = List.of("api/Foo.java", "service/Bar.java");
        Map<String, String> contents = Map.of(
                "api/Foo.java", "import storage.S3Client;",
                "service/Bar.java", "import storage.S3Client;"); // OK — not in 'from'
        List<InvariantChecker.Finding> f = checker.check(yaml, files, fakeLoader(contents));
        assertThat(f).hasSize(1);
        assertThat(f.get(0).file()).contains("api/Foo.java");
    }

    @Test
    void layerIsolation_flagsCrossLayerReference() {
        String yaml = """
                id: domain-isolation
                description: domain layer cannot import from infra
                enforcement:
                  rule:
                    kind: layer-isolation
                    from: '**/domain/**'
                    forbidden: 'infra.'
                """;
        List<String> files = List.of(
                "src/domain/User.java",
                "src/infra/Database.java");
        Map<String, String> contents = Map.of(
                "src/domain/User.java", "import infra.Database;",
                "src/infra/Database.java", "// nothing here");
        List<InvariantChecker.Finding> f = checker.check(yaml, files, fakeLoader(contents));
        assertThat(f).hasSize(1);
        assertThat(f.get(0).file()).contains("domain/User.java");
    }

    @Test
    void unknownRuleKind_returnsEmpty() {
        String yaml = """
                id: x
                enforcement:
                  rule:
                    kind: future-kind
                """;
        assertThat(checker.check(yaml, List.of("a.java"), fakeLoader(Map.of()))).isEmpty();
    }

    @Test
    void emptyOrInvalidYaml_returnsEmpty_doesNotThrow() {
        assertThat(checker.check(null, List.of(), fakeLoader(Map.of()))).isEmpty();
        assertThat(checker.check("", List.of(), fakeLoader(Map.of()))).isEmpty();
        assertThat(checker.check("not: { valid yaml", List.of(), fakeLoader(Map.of()))).isEmpty();
    }

    @Test
    void violation_phaseEnumNormalisesUnknown() {
        ArchInvariantViolation v = new ArchInvariantViolation();
        v.setDetectionPhase("FUTURE_PHASE");
        assertThat(v.phaseEnum()).isEqualTo(ArchInvariantViolation.DetectionPhase.PLAN);
        v.setDetectionPhase("ARCH_REGEN");
        assertThat(v.phaseEnum()).isEqualTo(ArchInvariantViolation.DetectionPhase.ARCH_REGEN);
    }
}
