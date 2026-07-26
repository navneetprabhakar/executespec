package com.navneet.executespec.agent;

import java.util.ArrayList;
import java.util.List;

/** Mutable patch workspace kept out of the coherence-agent orchestration boundary. */
final class CoherenceMutableTask {
  String description;
  final List<String> dependsOn;
  final List<String> hardConstraints;
  final List<String> meetsRequirements;
  final List<String> touchesFiles;

  private CoherenceMutableTask(
      String description,
      List<String> dependsOn,
      List<String> hardConstraints,
      List<String> meetsRequirements,
      List<String> touchesFiles) {
    this.description = description;
    this.dependsOn = dependsOn;
    this.hardConstraints = hardConstraints;
    this.meetsRequirements = meetsRequirements;
    this.touchesFiles = touchesFiles;
  }

  static CoherenceMutableTask from(PlannedTask task) {
    return new CoherenceMutableTask(
        task.description(),
        new ArrayList<>(task.dependsOn() == null ? List.of() : task.dependsOn()),
        new ArrayList<>(task.hardConstraints() == null ? List.of() : task.hardConstraints()),
        new ArrayList<>(task.meetsRequirements() == null ? List.of() : task.meetsRequirements()),
        new ArrayList<>(task.touchesFiles() == null ? List.of() : task.touchesFiles()));
  }

  PlannedTask toImmutable(PlannedTask base) {
    return PlannedTask.builder()
        .id(base.id())
        .title(base.title())
        .description(description)
        .complexity(base.complexity())
        .touchesFiles(List.copyOf(touchesFiles))
        .dependsOn(List.copyOf(dependsOn))
        .hardConstraints(List.copyOf(hardConstraints))
        .meetsRequirements(List.copyOf(meetsRequirements))
        .taskType(base.taskType())
        .build();
  }
}
