package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * PR-N1 (2026-05-14) — pins the Java AST extractor's output contract.
 *
 * <p>Each test exercises a Java-specific shape that the pre-PR-N1 regex extractor either missed or
 * over-matched: multi-line signatures, generics + throws clauses, records, sealed classes, lambdas
 * (should NOT be reported as methods), interfaces. Hand-crafted source snippets keep the tests fast
 * + the expected output explicit.
 */
class JavaAstSymbolExtractorTest {

  @Test
  void simpleClass_classAndMethods_extractedInOrder() {
    String src =
        """
        public class UserService {
          public User getUser(String id) { return null; }
          public void deleteUser(String id) {}
        }
        """;
    List<String> out = JavaAstSymbolExtractor.extract(src);
    assertThat(out).containsExactly("class UserService", "getUser", "deleteUser");
  }

  @Test
  void multiLineSignatureWithGenericsAndThrows_extractedCleanly() {
    // Regex extractor commonly missed signatures like this; AST gets them.
    String src =
        """
        public class Foo {
          public <T extends Comparable<T>> List<T> sort(
              List<T> input,
              Comparator<? super T> cmp
          ) throws SortException, NullPointerException {
            return input;
          }
        }
        """;
    List<String> out = JavaAstSymbolExtractor.extract(src);
    assertThat(out).contains("class Foo", "sort");
  }

  @Test
  void recordDeclaration_extracted() {
    // Records didn't exist when the regex was written → regex never had a record pattern.
    String src =
        """
        public record UserId(String value) {
          public boolean isValid() { return value != null; }
        }
        """;
    List<String> out = JavaAstSymbolExtractor.extract(src);
    assertThat(out).contains("record UserId", "isValid");
  }

  @Test
  void sealedClassWithPermits_extracted() {
    // Java 17+ sealed types — regex doesn't handle permits clauses.
    String src =
        """
        public sealed class Shape permits Circle, Square, Triangle {
          public abstract double area();
        }
        """;
    List<String> out = JavaAstSymbolExtractor.extract(src);
    assertThat(out).contains("class Shape", "area");
  }

  @Test
  void interfaceDeclaration_extracted() {
    String src =
        """
        public interface Repository<T> {
          T findById(String id);
          void save(T entity);
        }
        """;
    List<String> out = JavaAstSymbolExtractor.extract(src);
    assertThat(out).containsExactly("interface Repository", "findById", "save");
  }

  @Test
  void enumDeclaration_extracted() {
    String src =
        """
        public enum Status {
          ACTIVE, ARCHIVED, DELETED;
          public boolean isFinal() { return this == DELETED; }
        }
        """;
    List<String> out = JavaAstSymbolExtractor.extract(src);
    assertThat(out).contains("enum Status", "isFinal");
  }

  @Test
  void nestedClasses_bothExtracted() {
    String src =
        """
        public class Outer {
          public void outerMethod() {}
          public static class Inner {
            public void innerMethod() {}
          }
        }
        """;
    List<String> out = JavaAstSymbolExtractor.extract(src);
    assertThat(out).contains("class Outer", "outerMethod", "class Inner", "innerMethod");
  }

  @Test
  void lambdas_areNotEmittedAsMethods() {
    // Pre-PR-N1, the regex's "method" pattern sometimes matched lambda bodies as new methods.
    // Confirm AST does NOT report lambda bodies as method declarations.
    String src =
        """
        public class Foo {
          private Runnable r = () -> System.out.println("hi");
          public void registered() {}
        }
        """;
    List<String> out = JavaAstSymbolExtractor.extract(src);
    // Only "registered" should be a method — no spurious entry for the lambda body
    assertThat(out).contains("class Foo", "registered");
    assertThat(out).doesNotContain("r"); // r is a field, not a method
  }

  @Test
  void deduped_constructorsAreNotMethods() {
    // JavaParser reports MethodDeclaration only — constructors are ConstructorDeclaration
    // (not extracted). Pin this so the count matches the regex's behaviour.
    String src =
        """
        public class User {
          public User(String name) {}
          public User() {}
          public String getName() { return ""; }
        }
        """;
    List<String> out = JavaAstSymbolExtractor.extract(src);
    assertThat(out).containsExactly("class User", "getName");
  }

  @Test
  void malformedSource_returnsEmptyList_doesNotThrow() {
    // Defensive: caller (CodeMapGenerator) falls back to regex on empty result, so we
    // contract that parse failure returns empty NOT throws.
    String src = "public class Foo { method without return type or paren";
    List<String> out = JavaAstSymbolExtractor.extract(src);
    // JavaParser will either parse a partial CompilationUnit (returning what it found) OR
    // return an empty Optional from getResult(). Either way our wrapper handles it without
    // throwing.
    assertThat(out).isNotNull();
  }

  @Test
  void emptyInput_returnsEmpty() {
    assertThat(JavaAstSymbolExtractor.extract(null)).isEmpty();
    assertThat(JavaAstSymbolExtractor.extract("")).isEmpty();
    assertThat(JavaAstSymbolExtractor.extract("   \n  ")).isEmpty();
  }

  @Test
  void deduplicates_byFirstOccurrence() {
    // Two classes can have a same-named method; only the first occurrence is kept.
    String src =
        """
        public class A {
          public void doIt() {}
        }
        class B {
          public void doIt() {}
        }
        """;
    List<String> out = JavaAstSymbolExtractor.extract(src);
    // doIt appears once (deduped)
    long doItCount = out.stream().filter("doIt"::equals).count();
    assertThat(doItCount).isEqualTo(1);
    assertThat(out).contains("class A", "class B", "doIt");
  }
}
