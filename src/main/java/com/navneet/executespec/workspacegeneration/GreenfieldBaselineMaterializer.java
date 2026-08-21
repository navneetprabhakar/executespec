package com.navneet.executespec.workspacegeneration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Materializes a frozen greenfield baseline under the active workspace-generation lease. */
public class GreenfieldBaselineMaterializer {

  public List<String> materialize(Path workspaceRoot, GreenfieldBaselinePlan plan) {
    if (plan == null || !plan.greenfield() || !plan.supported()) return List.of();
    Path root = normalizedRoot(workspaceRoot);
    List<String> created = new ArrayList<>();
    for (Map.Entry<String, String> entry :
        plan.filesToCreate().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      Path target = safeRelative(root, entry.getKey());
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
      try {
        ensureSafeParent(root, target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".executespec-baseline-", ".tmp");
        Files.writeString(temporary, entry.getValue(), StandardCharsets.UTF_8);
        try {
          Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unavailable) {
          Files.move(temporary, target);
        }
        created.add(root.relativize(target).toString().replace('\\', '/'));
      } catch (IOException failure) {
        throw new IllegalStateException(
            "GREENFIELD_BASELINE_WRITE_FAILED:" + entry.getKey(), failure);
      }
    }
    return List.copyOf(created);
  }

  private static void ensureSafeParent(Path root, Path parent) throws IOException {
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
      throw new IOException("workspace root is not a real directory");
    }
    Path relative = root.relativize(parent);
    Path current = root;
    for (Path segment : relative) {
      current = current.resolve(segment);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(current)
            || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("baseline parent is not a real directory: " + segment);
        }
      } else {
        Files.createDirectory(current);
      }
    }
  }

  private static Path normalizedRoot(Path workspaceRoot) {
    if (workspaceRoot == null) throw new IllegalArgumentException("workspaceRoot required");
    return workspaceRoot.toAbsolutePath().normalize();
  }

  private static Path safeRelative(Path root, String relative) {
    if (!GreenfieldBaselinePlanner.safeDeclaredPath(relative)) {
      throw new IllegalArgumentException("GREENFIELD_BASELINE_PATH_INVALID:" + relative);
    }
    Path target = root.resolve(relative).normalize();
    if (!target.startsWith(root)) {
      throw new IllegalArgumentException("GREENFIELD_BASELINE_PATH_ESCAPES_WORKSPACE:" + relative);
    }
    return target;
  }
}
