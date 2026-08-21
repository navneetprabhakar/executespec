package com.navneet.executespec.workspacegeneration;

import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;

import org.springframework.stereotype.Component;

/** Cohesive facade over baseline contract planning and lease-bound materialization. */
@Component
public class GreenfieldBaselineService {

  private final GreenfieldBaselinePlanner planner = new GreenfieldBaselinePlanner();
  private final GreenfieldBaselineMaterializer materializer = new GreenfieldBaselineMaterializer();

  public GreenfieldBaselinePlan plan(
      Path workspaceRoot, PlannedTask task, WorkspaceOrigin workspaceOrigin) {
    return planner.plan(workspaceRoot, task, workspaceOrigin);
  }

  public GreenfieldBaselinePlan plan(
      Path workspaceRoot, PlannedTask task, Plan plan, WorkspaceOrigin workspaceOrigin) {
    return planner.plan(workspaceRoot, task, plan, workspaceOrigin);
  }

  public List<String> materialize(Path workspaceRoot, GreenfieldBaselinePlan plan) {
    return materializer.materialize(workspaceRoot, plan);
  }
}
