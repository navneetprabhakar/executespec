package com.navneet.executespec.agent;

/** Bounded bootstrap repair changes file hints only; artifact authority is rebound separately. */
final class CoherenceBootstrapFilePatch {
  private CoherenceBootstrapFilePatch() {}

  static boolean apply(CoherenceMutableTask task, String path) {
    if (path == null
        || path.isBlank()
        || path.startsWith("/")
        || path.contains("..")
        || path.contains(".executespec")
        || !path.matches("[A-Za-z0-9_./-]+")
        || task.touchesFiles.size() >= TaskExpansionValueMerger.MAX_TOUCHES_FILES
        || task.touchesFiles.contains(path)) return false;
    String name = path.substring(path.lastIndexOf('/') + 1);
    boolean test = name.endsWith(".py") && (name.startsWith("test_") || name.endsWith("_test.py"));
    boolean manifest =
        java.util.Set.of("requirements.txt", "pyproject.toml", "setup.cfg").contains(name);
    if (!test && !manifest) return false;
    task.touchesFiles.add(path);
    return true;
  }
}
