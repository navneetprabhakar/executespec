package com.navneet.executespec.deployment.preview;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

final class PreviewDependencySignals {

  private static final Set<String> NODE_BACKEND_DEPS =
      Set.of("express", "fastify", "koa", "hono", "@nestjs/core");

  private static final Set<String> FRONTEND_FRAMEWORK_DEPS =
      Set.of(
          "react",
          "vue",
          "svelte",
          "@sveltejs/kit",
          "next",
          "nuxt",
          "solid-js",
          "preact",
          "lit",
          "angular",
          "@angular/core");

  private static final Set<String> FRONTEND_DIR_NAMES =
      Set.of("frontend", "client", "web", "app", "ui");
  private static final Set<String> BACKEND_DIR_NAMES =
      Set.of("backend", "server", "api", "service");

  private PreviewDependencySignals() {}

  static boolean hasBackendDependency(JsonNode deps) {
    return hasAny(deps, NODE_BACKEND_DEPS);
  }

  static boolean hasFrontendDependency(JsonNode deps) {
    return hasAny(deps, FRONTEND_FRAMEWORK_DEPS) || hasAny(deps, Set.of("vite", "webpack"));
  }

  static boolean hasMonorepoFrontendSignal(JsonNode dependencies, JsonNode devDependencies) {
    return hasAny(dependencies, Set.of("react", "vue", "svelte"))
        || hasAny(devDependencies, Set.of("vite", "@vitejs/plugin-react"));
  }

  static boolean isFrontendDirName(String dirName) {
    return FRONTEND_DIR_NAMES.contains(dirName);
  }

  static boolean isBackendDirName(String dirName) {
    return BACKEND_DIR_NAMES.contains(dirName);
  }

  private static boolean hasAny(JsonNode deps, Set<String> names) {
    if (deps == null) {
      return false;
    }
    for (String name : names) {
      if (deps.has(name)) {
        return true;
      }
    }
    return false;
  }
}
