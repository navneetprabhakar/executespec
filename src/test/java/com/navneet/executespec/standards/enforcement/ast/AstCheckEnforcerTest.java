package com.navneet.executespec.standards.enforcement.ast;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.standards.model.Standard;

import org.junit.jupiter.api.Test;

class AstCheckEnforcerTest {

  private final AstCheckEnforcer enforcer = new AstCheckEnforcer();

  private AstCheckEnforcer.FileReader fakeReader(Map<Path, String> files) {
    return files::get;
  }

  private Standard.AstCheckEnforcement enforcement(String kind, Map<String, Object> params) {
    return new Standard.AstCheckEnforcement(kind, params, null);
  }

  // ── forbidden-import ────────────────────────────────────────────────────

  @Test
  void forbiddenImport_javaFlagsExactAndSubpackage() {
    Path f = Path.of("src/main/java/com/acme/Foo.java");
    Map<Path, String> files =
        Map.of(
            f,
            "package com.acme;\n"
                + "import com.bad.Thing;\n"
                + "import com.bad.sub.Other;\n"
                + "import java.util.List;\n");
    List<AstCheckEnforcer.AstViolation> v =
        enforcer.enforce(
            enforcement("forbidden-import", Map.of("import", "com.bad")),
            List.of(f),
            fakeReader(files));
    // Both com.bad.Thing AND com.bad.sub.Other → 1 file, but only one violation per file
    assertThat(v).hasSize(1);
    assertThat(v.get(0).ruleKind()).isEqualTo("forbidden-import");
    assertThat(v.get(0).file()).contains("Foo.java");
  }

  @Test
  void forbiddenImport_typescriptFlagsModulePath() {
    Path f = Path.of("src/Foo.ts");
    Map<Path, String> files =
        Map.of(f, "import { x } from 'lodash';\nimport { y } from '@bad/lib';\n");
    List<AstCheckEnforcer.AstViolation> v =
        enforcer.enforce(
            enforcement("forbidden-import", Map.of("import", "@bad/lib")),
            List.of(f),
            fakeReader(files));
    assertThat(v).hasSize(1);
  }

  @Test
  void forbiddenImport_noMatch_noViolation() {
    Path f = Path.of("src/main/java/com/acme/Foo.java");
    Map<Path, String> files = Map.of(f, "package com.acme;\nimport java.util.List;\n");
    List<AstCheckEnforcer.AstViolation> v =
        enforcer.enforce(
            enforcement("forbidden-import", Map.of("import", "com.bad")),
            List.of(f),
            fakeReader(files));
    assertThat(v).isEmpty();
  }

  // ── naming-pattern ──────────────────────────────────────────────────────

  @Test
  void namingPattern_javaClassMustEndWithService() {
    Path f = Path.of("src/Foo.java");
    Map<Path, String> files =
        Map.of(f, "public class Foo {}\nclass FooService {}\nclass BarHelper {}\n");
    List<AstCheckEnforcer.AstViolation> v =
        enforcer.enforce(
            enforcement(
                "naming-pattern",
                Map.of(
                    "regex", ".*Service",
                    "declaration", "class")),
            List.of(f),
            fakeReader(files));
    // Foo + BarHelper violate; FooService passes
    assertThat(v).hasSize(2);
  }

  @Test
  void namingPattern_typescriptFunctionMatch() {
    Path f = Path.of("src/foo.ts");
    Map<Path, String> files =
        Map.of(f, "export function handleClick() {}\nexport function badName() {}\n");
    List<AstCheckEnforcer.AstViolation> v =
        enforcer.enforce(
            enforcement(
                "naming-pattern",
                Map.of(
                    "regex", "handle.*",
                    "declaration", "function")),
            List.of(f),
            fakeReader(files));
    assertThat(v).hasSize(1); // badName violates
  }

  @Test
  void namingPattern_invalidRegex_skippedSafely() {
    Path f = Path.of("src/Foo.java");
    Map<Path, String> files = Map.of(f, "class Foo {}");
    List<AstCheckEnforcer.AstViolation> v =
        enforcer.enforce(
            enforcement(
                "naming-pattern",
                Map.of(
                    "regex", "[invalid",
                    "declaration", "class")),
            List.of(f),
            fakeReader(files));
    assertThat(v).isEmpty(); // logged but not thrown
  }

  // ── dependency-direction ────────────────────────────────────────────────

  @Test
  void dependencyDirection_layerViolationDetected() {
    Path apiFile = Path.of("src/main/java/com/acme/api/Controller.java");
    Path domainFile = Path.of("src/main/java/com/acme/domain/User.java");
    Map<Path, String> files =
        Map.of(
            apiFile, "import com.acme.domain.User;\n", // OK
            domainFile, "import com.acme.api.Controller;\n"); // VIOLATION

    Map<String, Object> params = new HashMap<>();
    params.put("from", "**/com/acme/domain/**");
    params.put("forbidden_target", "com.acme.api");

    List<AstCheckEnforcer.AstViolation> v =
        enforcer.enforce(
            enforcement("dependency-direction", params),
            List.of(apiFile, domainFile),
            fakeReader(files));
    assertThat(v).hasSize(1);
    assertThat(v.get(0).file()).contains("domain/User.java");
  }

  // ── Misc ────────────────────────────────────────────────────────────────

  @Test
  void unknownRuleKind_returnsEmpty_doesNotThrow() {
    Path f = Path.of("src/x.java");
    List<AstCheckEnforcer.AstViolation> v =
        enforcer.enforce(
            enforcement("future-rule", Map.of()), List.of(f), fakeReader(Map.of(f, "class X {}")));
    assertThat(v).isEmpty();
  }

  @Test
  void nullEnforcement_returnsEmpty() {
    assertThat(enforcer.enforce(null, List.of(Path.of("x.java")), fakeReader(Map.of()))).isEmpty();
  }

  @Test
  void emptyFileList_returnsEmpty() {
    assertThat(
            enforcer.enforce(
                enforcement("forbidden-import", Map.of("import", "com.bad")),
                List.of(),
                fakeReader(Map.of())))
        .isEmpty();
  }

  @Test
  void langOf_handlesAllExtensions() {
    assertThat(AstCheckEnforcer.Lang.of(Path.of("Foo.java"))).isEqualTo(AstCheckEnforcer.Lang.JAVA);
    assertThat(AstCheckEnforcer.Lang.of(Path.of("foo.ts")))
        .isEqualTo(AstCheckEnforcer.Lang.TYPESCRIPT);
    assertThat(AstCheckEnforcer.Lang.of(Path.of("foo.tsx")))
        .isEqualTo(AstCheckEnforcer.Lang.TYPESCRIPT);
    assertThat(AstCheckEnforcer.Lang.of(Path.of("foo.go"))).isEqualTo(AstCheckEnforcer.Lang.OTHER);
  }
}
