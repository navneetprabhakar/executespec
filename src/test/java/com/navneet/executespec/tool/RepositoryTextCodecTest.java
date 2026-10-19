package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryTextCodecTest {
  @TempDir Path root;

  @Test
  void bomEncodedUtf16RemainsReadableInWholeAndStreamingPaths() throws Exception {
    Path file = root.resolve("source.py");
    Files.write(file, "# Café 東京\n".getBytes(StandardCharsets.UTF_16));
    assertThat(RepositoryTextCodec.read(file)).isEqualTo("# Café 東京\n");
    assertThat(RepositoryTextCodec.lines(file)).containsExactly("# Café 東京");
  }

  @Test
  void lateMalformedUtf8FallsBackWithoutLosingEarlierLines() throws Exception {
    Path file = root.resolve("legacy.txt");
    String content = "valid line\n".repeat(9000) + "café\n";
    Files.write(file, content.getBytes(StandardCharsets.ISO_8859_1));
    assertThat(RepositoryTextCodec.lines(file)).hasSize(9001).last().isEqualTo("café");
  }
}
