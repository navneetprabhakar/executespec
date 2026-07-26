package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.Locale;
import java.util.Set;

/** Classifies shared package metadata separately from concrete implementation artifacts. */
final class PlanArtifactOwnershipSupport {
  private static final Set<String> PACKAGE_OR_REGISTRY_STEMS =
      Set.of("__init__", "index", "barrel", "package-info", "mod");

  private PlanArtifactOwnershipSupport() {}

  /**
   * Package initializers and registry/barrel files are integration surfaces that several domain
   * tasks may legitimately update. They are not evidence that those tasks co-own a concrete model.
   */
  static boolean isPackageOrRegistryMetadata(String rawPath) {
    String path = safe(rawPath).replace('\\', '/').toLowerCase(Locale.ROOT);
    int slash = path.lastIndexOf('/');
    String file = slash >= 0 ? path.substring(slash + 1) : path;
    int dot = file.lastIndexOf('.');
    String stem = dot > 0 ? file.substring(0, dot) : file;
    return PACKAGE_OR_REGISTRY_STEMS.contains(stem);
  }
}
