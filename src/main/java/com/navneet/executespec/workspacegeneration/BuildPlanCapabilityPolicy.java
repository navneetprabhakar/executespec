package com.navneet.executespec.workspacegeneration;

import java.nio.file.Path;

import com.navneet.executespec.agent.Plan;

/** Side-effect-free runtime qualification before a customer is asked to approve a BUILD plan. */
public final class BuildPlanCapabilityPolicy {

  private BuildPlanCapabilityPolicy() {}

  public static void requireSupported(
      Plan plan, Path workspace, WorkspaceOrigin origin, BuildProtocolVersion protocol) {
    if (!protocol.sharedKernel()) return;
    GreenfieldBaselinePlanner planner = new GreenfieldBaselinePlanner();
    for (var task : plan.tasks()) {
      GreenfieldBaselinePlan baseline = planner.plan(workspace, task, plan, origin);
      if (!baseline.supported()) {
        throw new IllegalStateException(
            "BUILD_PLAN_RUNTIME_UNSUPPORTED:" + task.id() + ":" + baseline.kind());
      }
    }
  }
}
