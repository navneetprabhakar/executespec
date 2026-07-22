package com.navneet.executespec.security;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.navneet.executespec.security.ArtifactPublicationGuard.PublicationBlockedException;

/**
 * USTAR (gzip tar) header parsing helpers for {@link ArtifactPublicationGuard#assertTarGzipSafe}.
 * Extracted from the guard so it stays under the production line ceiling; behaviour is
 * byte-identical to the previous in-class private statics. All failures surface as {@link
 * PublicationBlockedException} exactly as before.
 */
final class UstarTarReader {

  private UstarTarReader() {}

  static void validateTarHeader(byte[] header) throws IOException {
    String magic = new String(header, 257, 5, StandardCharsets.US_ASCII);
    if (!"ustar".equals(magic)) {
      throw new PublicationBlockedException("INVALID_TAR_HEADER", "(archive)");
    }
    long expected = parseTarOctal(header, 148, 8);
    long actual = 0;
    for (int i = 0; i < header.length; i++) {
      actual += i >= 148 && i < 156 ? ' ' : header[i] & 0xff;
    }
    if (expected != actual) {
      throw new PublicationBlockedException("INVALID_TAR_CHECKSUM", "(archive)");
    }
  }

  static String tarName(byte[] header) throws IOException {
    String name = tarString(header, 0, 100);
    String prefix = tarString(header, 345, 155);
    String combined = prefix.isBlank() ? name : prefix + "/" + name;
    if (combined.isBlank()) {
      throw new PublicationBlockedException("INVALID_ARCHIVE_PATH", "(archive member)");
    }
    return combined;
  }

  static String tarString(byte[] header, int offset, int length) {
    int end = offset;
    int limit = offset + length;
    while (end < limit && header[end] != 0) end++;
    return new String(header, offset, end - offset, StandardCharsets.UTF_8);
  }

  static long parseTarOctal(byte[] header, int offset, int length) throws IOException {
    long value = 0;
    boolean digitSeen = false;
    for (int i = offset; i < offset + length; i++) {
      byte current = header[i];
      if (current == 0 || current == ' ') continue;
      if (current < '0' || current > '7') {
        throw new PublicationBlockedException("INVALID_TAR_HEADER", "(archive)");
      }
      digitSeen = true;
      if (value > (Long.MAX_VALUE - (current - '0')) / 8) {
        throw new PublicationBlockedException("INVALID_TAR_HEADER", "(archive)");
      }
      value = value * 8 + (current - '0');
    }
    return digitSeen ? value : 0;
  }

  static boolean allZero(byte[] bytes) {
    for (byte value : bytes) if (value != 0) return false;
    return true;
  }

  static void readFully(InputStream input, byte[] bytes) throws IOException {
    int offset = 0;
    while (offset < bytes.length) {
      int read = input.read(bytes, offset, bytes.length - offset);
      if (read < 0) throw new EOFException("archive ended before a complete header");
      offset += read;
    }
  }

  static void skipFully(InputStream input, long bytes) throws IOException {
    long remaining = bytes;
    while (remaining > 0) {
      long skipped = input.skip(remaining);
      if (skipped > 0) {
        remaining -= skipped;
        continue;
      }
      if (input.read() < 0) throw new EOFException("archive ended before member padding");
      remaining--;
    }
  }
}
