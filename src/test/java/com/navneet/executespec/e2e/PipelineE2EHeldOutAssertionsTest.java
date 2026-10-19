package com.navneet.executespec.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class PipelineE2EHeldOutAssertionsTest {
  private Process execute(String implementation) throws Exception {
    String fixture =
        "import re, sys, types\nmain = types.ModuleType('main')\n"
            + "sys.modules['main'] = main\nmain.slugify = "
            + implementation
            + "\n";
    Process process =
        new ProcessBuilder("python3", "-c", fixture + PipelineE2EHeldOutAssertions.slugify())
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(10, TimeUnit.SECONDS);
    if (!finished) process.destroyForcibly();
    assertThat(finished).as("independent behavior checks finish within ten seconds").isTrue();
    return process;
  }

  @Test
  void acceptsLowercasingBeforeAsciiFiltering() throws Exception {
    Process process = execute("lambda text: re.sub(r'[^a-z0-9]+', '-', text.lower()).strip('-')");
    assertThat(process.exitValue()).isZero();
    assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
        .contains("held-out 14/14 passed");
  }

  @Test
  void rejectsTheObservedFilterBeforeLowercaseDefect() throws Exception {
    Process process =
        execute("lambda text: re.sub(r'[^a-zA-Z0-9]+', '-', text).lower().strip('-')");
    assertThat(process.exitValue()).isNotZero();
    assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
        .contains("AssertionError", "İTEM", "i-tem");
  }
}
