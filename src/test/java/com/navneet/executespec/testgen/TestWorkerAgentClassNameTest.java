package com.navneet.executespec.testgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * RR-966 regression — the test class name comes verbatim from model JSON and becomes a filename in
 * the generated test scaffold. A prompt-influenced {@code ../} name would overwrite another
 * writable Java file, which is then compiled and executed.
 */
class TestWorkerAgentClassNameTest {

  @Test
  void acceptsABareJavaIdentifier() {
    assertThat(TestWorkerAgent.requireJavaClassName("OrderControllerIT"))
        .isEqualTo("OrderControllerIT");
    assertThat(TestWorkerAgent.requireJavaClassName("_Weird$Name1")).isEqualTo("_Weird$Name1");
  }

  @Test
  void rejectsPathSeparatorsAndDotSegments() {
    assertThatThrownBy(
            () -> TestWorkerAgent.requireJavaClassName("../../main/java/com/acme/Config"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> TestWorkerAgent.requireJavaClassName("a/b"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> TestWorkerAgent.requireJavaClassName("a\\b"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> TestWorkerAgent.requireJavaClassName("com.acme.Foo"))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  void rejectsEmptyBlankAndOverLongNames() {
    assertThatThrownBy(() -> TestWorkerAgent.requireJavaClassName(null))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> TestWorkerAgent.requireJavaClassName("  "))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> TestWorkerAgent.requireJavaClassName("A".repeat(200)))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  void rejectsNamesStartingWithADigitOrContainingControlCharacters() {
    assertThatThrownBy(() -> TestWorkerAgent.requireJavaClassName("1Foo"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> TestWorkerAgent.requireJavaClassName("Foo" + (char) 0 + "Bar"))
        .isInstanceOf(SecurityException.class);
  }
}
