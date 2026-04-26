package com.navneet.executespec.standards;

import com.navneet.executespec.standards.model.Standard;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * E3.10 — composes the {@code {{ENGINEERING_STANDARDS}}} prompt
 * fragment that the planner / worker / reviewer all share. Compact
 * form per plan §5.10: id + scope + severity + description (no
 * enforcement bodies — those run server-side, not LLM-side).
 *
 * <p>This is the single source of truth for "what standards apply to
 * this run" that the LLM agents see; the actual enforcement runs
 * server-side via the per-strategy enforcers (E3.4 / E3.5 / E3.6 /
 * E3.7+8). The LLM agents need to *know about* the standards so
 * they generate compliant code in the first place — server-side
 * enforcement catches mistakes after the fact.
 */
@Service
public class StandardsPromptComposer {

    /** Empty when no standards apply — slot is a no-op. */
    public String compose(List<EffectiveStandardsResolver.Effective> effective) {
        if (effective == null || effective.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("=== ENGINEERING_STANDARDS ===\n");
        sb.append(effective.size()).append(" standard(s) apply to this run. ")
                .append("Generate code that satisfies them; violations will be flagged by the reviewer.\n\n");
        for (EffectiveStandardsResolver.Effective e : effective) {
            Standard s = e.standard();
            sb.append("[").append(s.id()).append("] ")
                    .append("severity=").append(s.severity());
            if (e.overridden()) {
                sb.append(" (project downgraded from ").append(e.originalSeverity()).append(")");
            }
            if (e.orgLevelLocked()) sb.append(" [LOCKED]");
            sb.append("\n");
            sb.append("  ").append(s.description()).append("\n");
            if (s.scope() != null && !s.scope().isEmpty()) {
                sb.append("  scope:");
                if (s.scope().languages() != null && !s.scope().languages().isEmpty()) {
                    sb.append(" lang=").append(String.join(",", s.scope().languages()));
                }
                if (s.scope().paths() != null && !s.scope().paths().isEmpty()) {
                    sb.append(" paths=").append(String.join(",", s.scope().paths()));
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
