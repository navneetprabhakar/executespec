package com.navneet.executespec.security;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.navneet.executespec.config.AgentProperties;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Final fail-closed gate for artifacts that are about to leave an ExecuteSpec workspace.
 *
 * <p>Write/edit tools remain useful early feedback, but are not a security boundary: shell and
 * local-hands clients can create files without those tools, and a repository can already contain a
 * credential. Publication therefore scans the complete Git candidate set or every archive member
 * immediately before push/download. Findings expose only category and relative path, never the
 * matched value. There is deliberately no silent or caller-controlled bypass.
 */
@Component
@RequiredArgsConstructor
public class ArtifactPublicationGuard {

  private static final int MAX_GIT_PATH_BYTES = 32 * 1024 * 1024;
  private static final int MAX_ARCHIVE_ENTRIES = 100_000;
  private static final long MAX_ARCHIVE_ENTRY_BYTES = 512L * 1024 * 1024;
  private static final long MAX_ARCHIVE_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
  private static final long GIT_ENUMERATION_TIMEOUT_SECONDS = 15;

  private final AgentProperties properties;
  private final GeneratedSecretScanner scanner;

  public void assertGitWorkspaceSafe(String runId) throws IOException {
    assertGitWorkspaceSafe(runId, null);
  }

  public void assertGitWorkspaceSafe(String runId, String subPath) throws IOException {
    Path runRoot = Path.of(properties.getWorkspace(), runId).toAbsolutePath().normalize();
    Path repoRoot =
        subPath == null || subPath.isBlank() ? runRoot : runRoot.resolve(subPath).normalize();
    if (!repoRoot.startsWith(runRoot) || !Files.isDirectory(repoRoot)) {
      throw new PublicationBlockedException("INVALID_WORKSPACE", "(invalid workspace)");
    }

    Path candidateList = Files.createTempFile("executespec-publication-candidates-", ".bin");
    try {
      Process process =
          new ProcessBuilder(
                  "git",
                  "-C",
                  repoRoot.toString(),
                  "ls-files",
                  "-z",
                  "--stage",
                  "--cached",
                  "--others",
                  "--exclude-standard")
              .redirectErrorStream(true)
              .redirectOutput(candidateList.toFile())
              .start();
      if (!process.waitFor(GIT_ENUMERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new PublicationBlockedException("GIT_CANDIDATE_ENUMERATION_TIMEOUT", "(repository)");
      }
      if (process.exitValue() != 0) {
        throw new PublicationBlockedException("GIT_CANDIDATE_ENUMERATION_FAILED", "(repository)");
      }
      if (Files.size(candidateList) > MAX_GIT_PATH_BYTES) {
        throw new PublicationBlockedException("CANDIDATE_LIST_TOO_LARGE", "(repository)");
      }
      byte[] output = Files.readAllBytes(candidateList);
      for (String record : nulSeparated(output)) {
        GitCandidate candidate = parseGitCandidate(record);
        assertLabelSafe(candidate.relativePath());
        if (!candidate.gitlink()) {
          assertRepositoryPathSafe(repoRoot, candidate.relativePath());
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while checking publication candidates", interrupted);
    } finally {
      Files.deleteIfExists(candidateList);
    }
  }

  public void assertFileSafe(Path root, Path file) throws IOException {
    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path normalized = file.toAbsolutePath().normalize();
    if (!normalized.startsWith(normalizedRoot)) {
      throw new PublicationBlockedException("PATH_ESCAPE", "(outside workspace)");
    }
    scanPath(normalizedRoot, normalized, normalizedRoot.relativize(normalized).toString());
  }

  public void assertZipSafe(Path archive) throws IOException {
    assertZipSafe(archive, MAX_ARCHIVE_ENTRIES, MAX_ARCHIVE_ENTRY_BYTES, MAX_ARCHIVE_TOTAL_BYTES);
  }

  void assertZipSafe(Path archive, int maxEntries, long maxEntryBytes, long maxTotalBytes)
      throws IOException {
    int entries = 0;
    long[] totalBytes = {0L};
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        entries++;
        if (entries > maxEntries) {
          throw new PublicationBlockedException("ARCHIVE_ENTRY_LIMIT_EXCEEDED", "(archive)");
        }
        assertArchiveEntryNameSafe(entry.getName());
        assertLabelSafe(entry.getName());
        if (!entry.isDirectory()) {
          assertStreamSafe(
              entry.getName(),
              new BoundedArchiveEntryInputStream(
                  zip, entry.getName(), totalBytes, maxEntryBytes, maxTotalBytes));
        }
        zip.closeEntry();
      }
    }
  }

  public void assertContentSafe(String label, byte[] content) throws IOException {
    assertStreamSafe(label, new ByteArrayInputStream(content == null ? new byte[0] : content));
  }

  /** Validates the bounded USTAR+gzip format used by governed project exports. */
  public void assertTarGzipSafe(Path archive) throws IOException {
    int entries = 0;
    long totalBytes = 0;
    try (InputStream file = Files.newInputStream(archive);
        GZIPInputStream gzip = new GZIPInputStream(file)) {
      byte[] header = new byte[512];
      while (true) {
        readFully(gzip, header);
        if (allZero(header)) return;
        validateTarHeader(header);
        entries++;
        if (entries > MAX_ARCHIVE_ENTRIES) {
          throw new PublicationBlockedException("ARCHIVE_ENTRY_LIMIT_EXCEEDED", "(archive)");
        }
        String name = tarName(header);
        assertArchiveEntryNameSafe(name);
        assertLabelSafe(name);
        long size = parseTarOctal(header, 124, 12);
        if (size > MAX_ARCHIVE_ENTRY_BYTES) {
          throw new PublicationBlockedException("ARCHIVE_MEMBER_TOO_LARGE", safePath(name));
        }
        if (totalBytes > MAX_ARCHIVE_TOTAL_BYTES - size) {
          throw new PublicationBlockedException(
              "ARCHIVE_INFLATED_SIZE_LIMIT_EXCEEDED", "(archive)");
        }
        totalBytes += size;
        byte type = header[156];
        if (type == 0 || type == '0') {
          assertStreamSafe(name, new FixedLengthInputStream(gzip, size));
        } else if (type == '5' && size == 0) {
          // Directory metadata only.
        } else {
          throw new PublicationBlockedException("UNSUPPORTED_TAR_ENTRY_TYPE", "(archive member)");
        }
        skipFully(gzip, (512 - (size % 512)) % 512);
      }
    }
  }

  private void assertRepositoryPathSafe(Path root, String relative) throws IOException {
    if (relative == null || relative.isBlank()) return;
    assertLabelSafe(relative);
    Path candidate = root.resolve(relative).normalize();
    if (!candidate.startsWith(root)) {
      throw new PublicationBlockedException("PATH_ESCAPE", "(escaped repository path)");
    }
    // Deleted tracked files appear in ls-files but are not part of the outgoing tree.
    if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return;
    scanPath(root, candidate, relative);
  }

  private void assertArchiveEntryNameSafe(String name) throws IOException {
    if (name == null || name.isBlank()) {
      throw new PublicationBlockedException("INVALID_ARCHIVE_PATH", "(archive member)");
    }
    String normalized = name.replace('\\', '/');
    if (normalized.startsWith("/")
        || normalized.matches("^[A-Za-z]:/.*")
        || normalized.indexOf('\0') >= 0) {
      throw new PublicationBlockedException("ARCHIVE_PATH_TRAVERSAL", "(archive member)");
    }
    for (String segment : normalized.split("/", -1)) {
      if ("..".equals(segment)) {
        throw new PublicationBlockedException("ARCHIVE_PATH_TRAVERSAL", "(archive member)");
      }
    }
  }

  private void scanPath(Path root, Path candidate, String relative) throws IOException {
    assertLabelSafe(relative);
    if (Files.isSymbolicLink(candidate)) {
      assertContentSafe(
          relative, Files.readSymbolicLink(candidate).toString().getBytes(StandardCharsets.UTF_8));
      return;
    }
    if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
      throw new PublicationBlockedException("UNSUPPORTED_FILE_TYPE", safePath(relative));
    }
    try (InputStream input = Files.newInputStream(candidate)) {
      assertStreamSafe(root.relativize(candidate).toString(), input);
    }
  }

  private void assertStreamSafe(String relative, InputStream input) throws IOException {
    assertLabelSafe(relative);
    Optional<GeneratedSecretScanner.Finding> finding = scanner.scan(input);
    if (finding.isPresent()) {
      throw new PublicationBlockedException(finding.orElseThrow().category(), safePath(relative));
    }
  }

  private void assertLabelSafe(String label) throws IOException {
    Optional<GeneratedSecretScanner.Finding> finding = scanner.scan(label);
    if (finding.isPresent()) {
      throw new PublicationBlockedException(
          finding.orElseThrow().category(), "(credential-like artifact path)");
    }
  }

  private static List<String> nulSeparated(byte[] bytes) {
    List<String> paths = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] == 0) {
        if (i > start) paths.add(new String(bytes, start, i - start, StandardCharsets.UTF_8));
        start = i + 1;
      }
    }
    if (start < bytes.length)
      paths.add(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8));
    return paths;
  }

  private static GitCandidate parseGitCandidate(String record) {
    int tab = record.indexOf('\t');
    if (tab < 0) return new GitCandidate(record, false);
    String metadata = record.substring(0, tab);
    String path = record.substring(tab + 1);
    return new GitCandidate(path, metadata.startsWith("160000 "));
  }

  private static void validateTarHeader(byte[] header) throws IOException {
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

  private static String tarName(byte[] header) throws IOException {
    String name = tarString(header, 0, 100);
    String prefix = tarString(header, 345, 155);
    String combined = prefix.isBlank() ? name : prefix + "/" + name;
    if (combined.isBlank()) {
      throw new PublicationBlockedException("INVALID_ARCHIVE_PATH", "(archive member)");
    }
    return combined;
  }

  private static String tarString(byte[] header, int offset, int length) {
    int end = offset;
    int limit = offset + length;
    while (end < limit && header[end] != 0) end++;
    return new String(header, offset, end - offset, StandardCharsets.UTF_8);
  }

  private static long parseTarOctal(byte[] header, int offset, int length) throws IOException {
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

  private static boolean allZero(byte[] bytes) {
    for (byte value : bytes) if (value != 0) return false;
    return true;
  }

  private static void readFully(InputStream input, byte[] bytes) throws IOException {
    int offset = 0;
    while (offset < bytes.length) {
      int read = input.read(bytes, offset, bytes.length - offset);
      if (read < 0) throw new EOFException("archive ended before a complete header");
      offset += read;
    }
  }

  private static void skipFully(InputStream input, long bytes) throws IOException {
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

  private static String safePath(String path) {
    if (path == null || path.isBlank()) return "(workspace)";
    String normalized = path.replace('\\', '/').replaceAll("[\\r\\n\\t]", "_");
    return normalized.length() > 300 ? normalized.substring(0, 300) + "..." : normalized;
  }

  public static final class PublicationBlockedException extends IOException {
    private final String category;
    private final String relativePath;

    public PublicationBlockedException(String category, String relativePath) {
      super("Publication blocked: " + category + " detected in " + relativePath);
      this.category = category;
      this.relativePath = relativePath;
    }

    public String category() {
      return category;
    }

    public String relativePath() {
      return relativePath;
    }
  }

  private record GitCandidate(String relativePath, boolean gitlink) {}

  private static final class FixedLengthInputStream extends InputStream {
    private final InputStream delegate;
    private long remaining;

    private FixedLengthInputStream(InputStream delegate, long remaining) {
      this.delegate = delegate;
      this.remaining = remaining;
    }

    @Override
    public int read() throws IOException {
      if (remaining == 0) return -1;
      int value = delegate.read();
      if (value < 0) throw new EOFException("archive member ended early");
      remaining--;
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      if (remaining == 0) return -1;
      int requested = (int) Math.min(length, remaining);
      int read = delegate.read(bytes, offset, requested);
      if (read < 0) throw new EOFException("archive member ended early");
      remaining -= read;
      return read;
    }
  }

  private static final class BoundedArchiveEntryInputStream extends FilterInputStream {
    private final String label;
    private final long[] totalBytes;
    private final long maxEntryBytes;
    private final long maxTotalBytes;
    private long entryBytes;

    private BoundedArchiveEntryInputStream(
        InputStream delegate,
        String label,
        long[] totalBytes,
        long maxEntryBytes,
        long maxTotalBytes) {
      super(delegate);
      this.label = label;
      this.totalBytes = totalBytes;
      this.maxEntryBytes = maxEntryBytes;
      this.maxTotalBytes = maxTotalBytes;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value != -1) count(1);
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int read = super.read(bytes, offset, length);
      if (read > 0) count(read);
      return read;
    }

    private void count(long bytes) throws IOException {
      entryBytes += bytes;
      totalBytes[0] += bytes;
      if (entryBytes > maxEntryBytes) {
        throw new PublicationBlockedException("ARCHIVE_MEMBER_TOO_LARGE", safePath(label));
      }
      if (totalBytes[0] > maxTotalBytes) {
        throw new PublicationBlockedException("ARCHIVE_INFLATED_SIZE_LIMIT_EXCEEDED", "(archive)");
      }
    }
  }
}
