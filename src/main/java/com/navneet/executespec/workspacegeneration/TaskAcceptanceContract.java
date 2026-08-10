package com.navneet.executespec.workspacegeneration;

import java.util.List;

/** Frozen server-owned policy describing what one planner task must prove before acceptance. */
public record TaskAcceptanceContract(
    int schemaVersion,
    String taskId,
    int taskIndex,
    String verificationPolicy,
    String buildRequirement,
    String testRequirement,
    List<String> declaredTouchPaths,
    String digest) {

  public TaskAcceptanceContract {
    declaredTouchPaths = declaredTouchPaths == null ? List.of() : List.copyOf(declaredTouchPaths);
  }
}
