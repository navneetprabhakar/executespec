package com.navneet.executespec.repo;

import java.util.List;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * E2.13 — detects monorepo shape on a linked repo via root-level signature files. v1 surfaces
 * detection only ("Monorepo detected (Nx, 14 packages); scope-aware planning coming soon" per plan
 * §2.13); scoped codemaps + build-graph integration land in v1.1.
 *
 * <p>Cheap to call — works off the file path list alone. Returns a {@link Verdict} with the
 * detected flavor + an estimate of the package count (best-effort: counts top-level entries under
 * common workspace dirs like {@code packages/}, {@code apps/}, {@code services/}, {@code libs/}).
 */
@Component
@Slf4j
public class MonorepoDetector {

  public Verdict detect(List<String> repoFilePaths) {
    if (repoFilePaths == null || repoFilePaths.isEmpty()) {
      return new Verdict(false, Flavor.NONE, 0, "no files");
    }
    Flavor flavor = detectFlavor(repoFilePaths);
    if (flavor == Flavor.NONE) {
      return new Verdict(false, Flavor.NONE, 0, "no monorepo signature");
    }
    int packageCount = estimatePackageCount(repoFilePaths);
    String hint =
        flavor.label
            + " monorepo detected"
            + (packageCount > 0 ? " (" + packageCount + " packages)" : "")
            + " — scope-aware planning coming soon";
    return new Verdict(true, flavor, packageCount, hint);
  }

  Flavor detectFlavor(List<String> files) {
    // Order matters — first match wins for clarity in the UI label.
    for (String f : files) {
      String basename = f.contains("/") ? f.substring(f.lastIndexOf('/') + 1) : f;
      if (basename.equals("nx.json")) return Flavor.NX;
      if (basename.equals("turbo.json")) return Flavor.TURBO;
      if (basename.equals("lerna.json")) return Flavor.LERNA;
      if (basename.equals("pnpm-workspace.yaml") || basename.equals("pnpm-workspace.yml"))
        return Flavor.PNPM_WORKSPACES;
      if (basename.equals("WORKSPACE")
          || basename.equals("WORKSPACE.bazel")
          || basename.equals("BUILD.bazel")) return Flavor.BAZEL;
      if (basename.equals("pants.toml")
          || basename.equals("BUILD") && f.startsWith("BUILD")) // top-level Pants
      return Flavor.PANTS;
    }
    return Flavor.NONE;
  }

  /**
   * Best-effort package count: distinct first-level entries under {@code packages/}, {@code apps/},
   * {@code services/}, {@code libs/}. Returns 0 when no workspace dir is present (the flavor
   * signature may still be set — e.g. a single-package Nx workspace).
   */
  int estimatePackageCount(List<String> files) {
    java.util.Set<String> packages = new java.util.HashSet<>();
    for (String f : files) {
      if (f == null) continue;
      for (String dir : List.of("packages/", "apps/", "services/", "libs/")) {
        if (f.startsWith(dir)) {
          int slash = f.indexOf('/', dir.length());
          String pkg = slash > 0 ? f.substring(dir.length(), slash) : f.substring(dir.length());
          if (!pkg.isEmpty()) packages.add(dir + pkg);
        }
      }
    }
    return packages.size();
  }

  public enum Flavor {
    NONE("(none)"),
    NX("Nx"),
    TURBO("Turbo"),
    LERNA("Lerna"),
    PNPM_WORKSPACES("pnpm workspaces"),
    BAZEL("Bazel"),
    PANTS("Pants");

    public final String label;

    Flavor(String label) {
      this.label = label;
    }
  }

  /**
   * @param isMonorepo true when at least one signature file matched
   * @param flavor detected monorepo flavor (NONE when isMonorepo=false)
   * @param packageCount best-effort count of distinct packages found in standard workspace dirs; 0
   *     when no workspace dir
   * @param uiHint ready-to-render label for the RepoCard chip
   */
  public record Verdict(boolean isMonorepo, Flavor flavor, int packageCount, String uiHint) {}
}
