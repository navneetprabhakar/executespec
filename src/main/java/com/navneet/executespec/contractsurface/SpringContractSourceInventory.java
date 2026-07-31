package com.navneet.executespec.contractsurface;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounded, complete filesystem inventory used by the Spring public-contract extractor. */
final class SpringContractSourceInventory {

  static final TraversalLimits DEFAULT_LIMITS =
      new TraversalLimits(250_000, 25_000, 256L * 1024 * 1024);

  private static final Set<String> EXCLUDED_SEGMENTS =
      Set.of(".git", ".agent-context", "target", "build", "dist", "node_modules", "vendor");

  private SpringContractSourceInventory() {}

  static Inventory scan(Path root, TraversalLimits limits) {
    if (limits == null) throw new IllegalArgumentException("traversal limits are required");
    List<SourceFile> javaSources = new ArrayList<>();
    List<SourceFile> applicationConfigurations = new ArrayList<>();
    long[] visitedEntries = {0L};
    long[] candidateFiles = {0L};
    long[] candidateBytes = {0L};
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
              visitedEntries[0]++;
              requireWithinLimit(
                  root, "visited-entry", visitedEntries[0], limits.maxVisitedEntries(), directory);
              if (!root.equals(directory) && hasExcludedSegment(root.relativize(directory))) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              visitedEntries[0]++;
              requireWithinLimit(
                  root, "visited-entry", visitedEntries[0], limits.maxVisitedEntries(), file);
              if (attrs.isSymbolicLink()) {
                throw new SpringHttpSurfaceExtractor.SurfaceExtractionException(
                    "symbolic link cannot be admitted into Spring HTTP surface inventory: "
                        + relative(root, file));
              }
              if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
              if (!isCandidate(root, file)) return FileVisitResult.CONTINUE;

              candidateFiles[0]++;
              requireWithinLimit(
                  root, "source-file", candidateFiles[0], limits.maxCandidateFiles(), file);
              long declaredSize = attrs.size();
              if (declaredSize < 0
                  || declaredSize > limits.maxCandidateBytes() - candidateBytes[0]) {
                throw budgetExceeded(
                    root,
                    "source-byte",
                    saturatedAdd(candidateBytes[0], Math.max(0L, declaredSize)),
                    limits.maxCandidateBytes(),
                    file);
              }
              long remainingBytes = limits.maxCandidateBytes() - candidateBytes[0];
              byte[] bytes = readBounded(root, file, remainingBytes);
              if (bytes.length > limits.maxCandidateBytes() - candidateBytes[0]) {
                throw budgetExceeded(
                    root,
                    "source-byte",
                    saturatedAdd(candidateBytes[0], bytes.length),
                    limits.maxCandidateBytes(),
                    file);
              }
              candidateBytes[0] += bytes.length;
              SourceFile source = new SourceFile(file, new String(bytes, StandardCharsets.UTF_8));
              if (file.getFileName().toString().endsWith(".java")) {
                javaSources.add(source);
              } else {
                applicationConfigurations.add(source);
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure)
                throws IOException {
              throw new IOException(
                  "could not inspect contract source " + relative(root, file), failure);
            }
          });
    } catch (SpringHttpSurfaceExtractor.SurfaceExtractionException overflow) {
      throw overflow;
    } catch (IOException failure) {
      throw new SpringHttpSurfaceExtractor.SurfaceExtractionException(
          "could not inventory Spring HTTP surface: " + failure.getMessage(), failure);
    }
    return new Inventory(
        javaSources.stream().sorted().toList(),
        applicationConfigurations.stream().sorted().toList());
  }

  static boolean isProductionRelativePath(String rawPath) {
    Path path = Path.of(rawPath.replace('\\', '/'));
    if (hasExcludedSegment(path)) return false;
    String normalized = rawPath.replace('\\', '/').toLowerCase(Locale.ROOT);
    return !normalized.contains("/src/test/") && !normalized.startsWith("src/test/");
  }

  private static boolean isCandidate(Path root, Path file) {
    Path relative = root.relativize(file);
    if (hasExcludedSegment(relative)) return false;
    String normalized = relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    if (normalized.contains("/src/test/") || normalized.startsWith("src/test/")) return false;
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".java")
        || (name.startsWith("application")
            && (name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml")));
  }

  private static boolean hasExcludedSegment(Path relative) {
    for (Path segment : relative) {
      if (EXCLUDED_SEGMENTS.contains(segment.toString().toLowerCase(Locale.ROOT))) return true;
    }
    return false;
  }

  private static byte[] readBounded(Path root, Path file, long remainingBytes) throws IOException {
    int readLimit = Math.toIntExact(remainingBytes + 1L);
    try (InputStream input = Files.newInputStream(file)) {
      return input.readNBytes(readLimit);
    } catch (IOException unreadable) {
      throw new IOException("could not read contract source " + relative(root, file), unreadable);
    }
  }

  private static void requireWithinLimit(
      Path root, String budget, long observed, long limit, Path file) {
    if (observed > limit) throw budgetExceeded(root, budget, observed, limit, file);
  }

  private static SpringHttpSurfaceExtractor.SurfaceExtractionException budgetExceeded(
      Path root, String budget, long observed, long limit, Path file) {
    return new SpringHttpSurfaceExtractor.SurfaceExtractionException(
        "Spring HTTP surface "
            + budget
            + " budget exceeded ("
            + observed
            + " > "
            + limit
            + ") at "
            + relative(root, file));
  }

  private static long saturatedAdd(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  private static String relative(Path root, Path path) {
    if (root.equals(path)) return ".";
    return root.relativize(path).toString().replace('\\', '/');
  }

  record TraversalLimits(long maxVisitedEntries, long maxCandidateFiles, long maxCandidateBytes) {
    TraversalLimits {
      if (maxVisitedEntries <= 0 || maxCandidateFiles <= 0 || maxCandidateBytes <= 0) {
        throw new IllegalArgumentException("Spring HTTP surface traversal limits must be positive");
      }
      if (maxCandidateBytes >= Integer.MAX_VALUE) {
        throw new IllegalArgumentException(
            "Spring HTTP surface source-byte limit must be below Integer.MAX_VALUE");
      }
    }
  }

  record SourceFile(Path path, String content) implements Comparable<SourceFile> {
    @Override
    public int compareTo(SourceFile other) {
      return path.compareTo(other.path);
    }
  }

  record Inventory(List<SourceFile> javaSources, List<SourceFile> applicationConfigurations) {}
}
