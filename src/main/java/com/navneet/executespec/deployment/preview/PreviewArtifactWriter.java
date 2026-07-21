package com.navneet.executespec.deployment.preview;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

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
}
