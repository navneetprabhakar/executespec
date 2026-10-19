package com.navneet.executespec.tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Preserve Unicode source text while retaining readable evidence for legacy byte encodings. */
public final class RepositoryTextCodec {
  private RepositoryTextCodec() {}

  public static String read(Path path) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    Charset charset = bomCharset(bytes);
    try {
      return charset.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException legacy) {
      return new String(bytes, StandardCharsets.ISO_8859_1);
    }
  }

  public static List<String> lines(Path path) throws IOException {
    try (var reader = reader(path)) {
      var lines = new java.util.ArrayList<String>();
      String line;
      while ((line = reader.readLine()) != null) lines.add(line);
      return List.copyOf(lines);
    }
  }

  /** Streaming validation avoids turning a range read into an unbounded full-file allocation. */
  public static BufferedReader reader(Path path) throws IOException {
    byte[] prefix;
    try (var input = Files.newInputStream(path)) {
      prefix = input.readNBytes(3);
    }
    Charset charset = bomCharset(prefix);
    try (var input = new InputStreamReader(Files.newInputStream(path), charset.newDecoder())) {
      char[] buffer = new char[8192];
      while (input.read(buffer) >= 0) {
        /* Validate without retaining the file. */
      }
    } catch (CharacterCodingException legacy) {
      charset = StandardCharsets.ISO_8859_1;
    }
    return Files.newBufferedReader(path, charset);
  }

  private static Charset bomCharset(byte[] prefix) {
    if (prefix.length >= 2
        && ((prefix[0] == (byte) 0xff && prefix[1] == (byte) 0xfe)
            || (prefix[0] == (byte) 0xfe && prefix[1] == (byte) 0xff)))
      return StandardCharsets.UTF_16;
    return StandardCharsets.UTF_8;
  }
}
