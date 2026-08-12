package com.navneet.executespec.workspacegeneration;

import java.util.List;

/** Immutable deterministic environment inputs kept separate from product-generation identity. */
public record BuildEnvironmentPlan(
    int schemaVersion,
    String runtimeImage,
    String networkPolicy,
    String cachePolicy,
    List<Module> modules,
    String manifestDigest,
    String planDigest) {

  public BuildEnvironmentPlan {
    modules = modules == null ? List.of() : List.copyOf(modules);
  }

  public record Module(
      String repositoryRelativeRoot,
      String ecosystem,
      List<String> manifestPaths,
      String manifestDigest,
      List<String> argv,
      int timeoutSeconds) {
    public Module {
      manifestPaths = manifestPaths == null ? List.of() : List.copyOf(manifestPaths);
      argv = argv == null ? List.of() : List.copyOf(argv);
    }
  }
}
