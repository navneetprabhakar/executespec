package com.navneet.executespec.deployment.preview;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Set;

import com.navneet.executespec.security.SafeWorkspacePath;

/**
 * Writes the framework artifacts a preview generates ({@code Dockerfile}, {@code .dockerignore},
 * {@code docker-compose.yml}) into a cloned customer repository.
 *
 * <p>RR-476: these were written with a plain {@code Files.writeString} straight into the restored
 * repository. A cloned repository controls the contents of that directory, so it can ship {@code
 * Dockerfile} as a symlink to any file the server process can write — and the write happens before
 * any container isolation exists, under the ExecuteSpec server identity. Merely starting a preview
 * could therefore truncate a host file outside the workspace.
 *
 * <p>Containment is delegated to {@link SafeWorkspacePath}, the shared boundary used by the other
 * framework write sites: relative paths only, no dot or empty segments, canonical-root containment,
 * and no symbolic link in any existing component. The open then adds {@link
 * LinkOption#NOFOLLOW_LINKS} — that does not make the operation atomic, it only narrows the window
 * between the check and the syscall.
 *
 * <p><b>Residual:</b> this closes static symlink escapes. It does not eliminate check/use races: a
 * hostile process sharing the workspace could swap a component between validation and the open.
 * Eliminating that requires directory-descriptor-relative I/O or per-run container isolation.
 */
public final class PreviewArtifactWriter {

  private PreviewArtifactWriter() {}

  /**
   * Writes a generated framework artifact under {@code workspace}.
   *
   * @param workspace the run workspace root (containment boundary)
   * @param relativeName artifact file name relative to the workspace
   * @param content file content
   * @throws PreviewException if the target escapes the workspace, crosses a symbolic link, or
   *     cannot be written
   */
  public static void write(Path workspace, String relativeName, String content) {
    if (content == null) {
      throw new PreviewException("Refusing to write empty preview artifact " + relativeName + ".");
    }
    try {
      Path target = SafeWorkspacePath.resolveWithin(workspace, relativeName);
      try (OutputStream out = Files.newOutputStream(target, openOptions())) {
        out.write(content.getBytes(StandardCharsets.UTF_8));
      }
    } catch (SecurityException e) {
      // Deliberately does not report where a link pointed — the repository chose that path.
      throw new PreviewException(
          "Refusing to write preview artifact " + relativeName + ": " + e.getMessage());
    } catch (IOException e) {
      throw new PreviewException(
          "Failed to write preview artifact " + relativeName + ": " + e.getMessage(), e);
    }
  }

  private static OpenOption[] openOptions() {
    return new OpenOption[] {
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
      StandardOpenOption.TRUNCATE_EXISTING,
      LinkOption.NOFOLLOW_LINKS
    };
  }

  /** Marker line that separates a customer's own ignore rules from the ExecuteSpec block. */
  static final String MERGE_MARKER =
      "# --- Added by ExecuteSpec preview (security exclusions; do not rely on this file) ---";

  /**
   * Writes the generated preview {@code .dockerignore} WITHOUT discarding a customer-authored one
   * (RR-384).
   *
   * <p>Docker reads exactly one {@code .dockerignore} at the build-context root, so the two sets
   * cannot coexist as separate files. Previously the generated deny-list overwrote whatever the
   * customer had, silently dropping their build exclusions. This instead MERGES: the customer's
   * file is kept verbatim first, then the ExecuteSpec security block is appended. Appending last is
   * deliberate — {@code .dockerignore} evaluates patterns in order and a later pattern wins, so a
   * customer re-include (e.g. {@code *} then {@code !secret.pem}) can never pull a credential or a
   * control directory back into the build context. Generated pattern lines already present in the
   * customer file are skipped to avoid noise.
   *
   * <p>The customer's original bytes are preserved to {@code .dockerignore.user} (mirroring the
   * docker-compose backup convention) so the run workspace deliverable can be restored.
   *
   * <p><b>Not closed here (RR-384 remainder):</b> the merged result is still a deny-list over the
   * customer's tree, not an allowlisted staged build context, so a repository secret under an
   * unrecognised name is still copyable. Moving to a staged allowlist context is the durable fix.
   */
  public static void writeDockerIgnore(Path workspace, String generated) {
    if (generated == null) {
      throw new PreviewException("Refusing to write empty preview artifact .dockerignore.");
    }
    String existing = readExistingDockerIgnore(workspace);
    if (existing != null && !existing.isBlank()) {
      // Preserve the customer's original for deliverable restoration (best effort).
      try {
        Path backup = SafeWorkspacePath.resolveWithin(workspace, ".dockerignore.user");
        try (OutputStream out = Files.newOutputStream(backup, openOptions())) {
          out.write(existing.getBytes(StandardCharsets.UTF_8));
        }
      } catch (SecurityException | IOException ignored) {
        // A backup failure must not block the preview; the merged file still preserves the rules.
      }
    }
    write(workspace, ".dockerignore", mergeDockerIgnore(existing, generated));
  }

  private static String readExistingDockerIgnore(Path workspace) {
    try {
      Path target = SafeWorkspacePath.resolveWithin(workspace, ".dockerignore");
      if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
        return null; // absent, a directory, or a symlink — nothing safe to merge
      }
      return Files.readString(target, StandardCharsets.UTF_8);
    } catch (SecurityException e) {
      // The repository shipped .dockerignore as a symlink / escaping path. Do not read through it;
      // the subsequent safe write will reject it the same way, which is the intended fail-closed.
      throw new PreviewException(
          "Refusing to read preview artifact .dockerignore: " + e.getMessage());
    } catch (IOException e) {
      return null; // unreadable → fall back to the generated deny-list alone (still secure)
    }
  }

  /**
   * Merges a customer-authored {@code .dockerignore} with the generated ExecuteSpec block. Pure and
   * package-visible for unit testing.
   */
  static String mergeDockerIgnore(String existing, String generated) {
    if (existing == null || existing.isBlank()) {
      return generated;
    }
    Set<String> existingPatterns = new LinkedHashSet<>();
    for (String line : existing.split("\n", -1)) {
      String trimmed = line.strip();
      if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
        existingPatterns.add(trimmed);
      }
    }
    StringBuilder out = new StringBuilder();
    out.append(existing.stripTrailing()).append("\n\n").append(MERGE_MARKER).append('\n');
    for (String line : generated.split("\n", -1)) {
      String trimmed = line.strip();
      // Keep comments/blank lines from the generated block for readability; skip pattern lines the
      // customer already declared so the merged file does not repeat them.
      if (!trimmed.isEmpty() && !trimmed.startsWith("#") && existingPatterns.contains(trimmed)) {
        continue;
      }
      out.append(line).append('\n');
    }
    return out.toString();
  }
}
