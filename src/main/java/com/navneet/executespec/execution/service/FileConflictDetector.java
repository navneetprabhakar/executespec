package com.navneet.executespec.execution.service;

import java.util.*;

import com.navneet.executespec.execution.model.FileConflict;
import com.navneet.executespec.execution.model.SolutionSpec;

import org.springframework.stereotype.Service;

/**
 * Cross-references predicted target files across {@link SolutionSpec}s and surfaces overlaps so the
 * planner / UI can warn or enforce order.
 */
@Service
public class FileConflictDetector {

  /** Key = "{repoId}::{path}". Value = sequence numbers that claim it. */
  public List<FileConflict> detectConflicts(List<SolutionSpec> specs) {
    if (specs == null || specs.size() < 2) return List.of();

    Map<String, List<SolutionSpec>> hits = new LinkedHashMap<>();
    for (SolutionSpec s : specs) {
      if (s.targetFiles() == null) continue;
      for (String f : s.targetFiles()) {
        String key = s.repoId() + "::" + f;
        hits.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
      }
    }

    List<FileConflict> out = new ArrayList<>();
    for (Map.Entry<String, List<SolutionSpec>> e : hits.entrySet()) {
      if (e.getValue().size() < 2) continue;
      List<SolutionSpec> claimants = e.getValue();
      List<Integer> seqs = claimants.stream().map(SolutionSpec::sequenceNumber).sorted().toList();
      String file = e.getKey().substring(e.getKey().indexOf("::") + 2);
      String resolution =
          "Enforce order by dependency: "
              + seqs.stream().map(i -> "S" + i).reduce((a, b) -> a + " → " + b).orElse("");
      out.add(new FileConflict(file, claimants.get(0).repoName(), seqs, resolution));
    }
    return out;
  }
}
