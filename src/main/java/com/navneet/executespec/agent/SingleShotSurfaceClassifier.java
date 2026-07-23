package com.navneet.executespec.agent;

import java.util.Locale;

/** Deterministic runtime-surface classification for the planner-free single-shot path. */
final class SingleShotSurfaceClassifier {

  private SingleShotSurfaceClassifier() {}

  /** Explicit backend/API language wins; otherwise ordinary web-app language means a browser UI. */
  static Plan.RuntimeSurface infer(String spec) {
    String text = spec == null ? "" : spec.toLowerCase(Locale.ROOT);
    if (text.matches("(?s).*\\b(rest|graphql|grpc|websocket)\\s+api\\b.*")
        || text.matches("(?s).*\\b(api|backend|server|endpoint)s?\\b.*")) {
      return Plan.RuntimeSurface.HTTP;
    }
    if (text.matches("(?s).*\\b(cli|command[- ]line|terminal)\\b.*")) {
      return Plan.RuntimeSurface.CLI;
    }
    if (text.matches("(?s).*\\b(library|package|reusable module)\\b.*")) {
      return Plan.RuntimeSurface.LIBRARY;
    }
    if (text.matches("(?s).*\\bweb\\b.*\\bapp\\b.*")
        || text.matches(
            "(?s).*\\b(webapp|website|browser|frontend|front-end|user"
                + " interface|ui|page|dashboard|landing site)\\b.*")) {
      return Plan.RuntimeSurface.STATIC_WEB;
    }
    return Plan.RuntimeSurface.HTTP;
  }

  static String projectType(Plan.RuntimeSurface surface) {
    return surface == Plan.RuntimeSurface.STATIC_WEB ? "node-frontend" : "unknown";
  }

  static String taskConstraint(Plan.RuntimeSurface surface) {
    return surface == Plan.RuntimeSurface.STATIC_WEB
        ? "\n\nThis is a browser user-interface deliverable. Create a working page with HTML and"
            + " client-side UI code; an API-only implementation does not satisfy the request."
        : "";
  }
}
