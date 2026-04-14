package com.navneet.executespec.codeintel;

import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pilot R3 — synthesises a project-wide architecture markdown doc from the
 * per-repo codemaps. Deterministic concatenation for now (no LLM); the prompt-
 * augmented version is a follow-up that can plug in once the dashboard demo
 * exposes the regen button to real users.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArchitectureDocGenerator {

    public String generate(String projectId, List<ProjectCodeMap> codeMaps, List<String> repoFullNames) {
        StringBuilder md = new StringBuilder();
        md.append("# Project architecture\n\n");
        md.append("Generated for project `").append(projectId).append("`. ");
        md.append("Compiled from ").append(codeMaps.size()).append(" repo codemap(s).\n\n");

        md.append("## Repositories in scope\n\n");
        if (repoFullNames.isEmpty()) {
            md.append("_No repos linked — link repos under the Repos tab to enrich this doc._\n\n");
        } else {
            repoFullNames.forEach(r -> md.append("- ").append(r).append("\n"));
            md.append("\n");
        }

        md.append("## Per-repo summary\n\n");
        for (ProjectCodeMap cm : codeMaps) {
            md.append("---\n\n");
            md.append(cm.getContent()).append("\n");
        }

        md.append("\n## Notes\n\n");
        md.append("Add cross-repo annotations using the Annotations editor on the dashboard. ");
        md.append("Annotations are appended verbatim to the planner prompt during R4 assessment.\n");

        return md.toString();
    }
}
