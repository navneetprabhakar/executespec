package com.navneet.executespec.standards;

import com.navneet.executespec.standards.model.Standard;
import com.navneet.executespec.standards.model.StandardsParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * E3.3 — computes the effective set of standards for a Project by
 * merging org-level standards (V136 {@link OrgStandard}) with
 * project-level overrides ({@link ProjectStandardOverride}) per plan
 * §5.7.
 *
 * <h2>Merge algorithm</h2>
 * <ol>
 *   <li>Load every {@link OrgStandard} for the project's org → parse
 *       each YAML into a {@link Standard}</li>
 *   <li>Load every {@link ProjectStandardOverride} for the project</li>
 *   <li>For each org standard:
 *     <ul>
 *       <li>Override type {@code NOT_APPLICABLE} → exclude</li>
 *       <li>Override type {@code SEVERITY_DOWNGRADE} → apply new
 *           severity (only if it's strictly lower; upgrades rejected
 *           per plan §5.7)</li>
 *       <li>Standard's {@code orgLevelLocked == true} + override
 *           exists → log + ignore the override</li>
 *     </ul>
 *   </li>
 *   <li>Project-level standards (parsed from
 *       {@code .executespec/config/<project>/standards.yaml} —
 *       caller-supplied) merge in: same id replaces the org-level
 *       entry (subject to {@code orgLevelLocked})</li>
 *   <li>Return list of {@link Effective} records — each carries the
 *       source level (ORG / PROJECT), whether the row was overridden,
 *       and the original severity for provenance display</li>
 * </ol>
 *
 * <p>Severity ordering (lowest → highest impact):
 * {@code ADVISORY → WARNING → BLOCKING}. A "downgrade" moves a
 * standard left along that ordering.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EffectiveStandardsResolver {

    private final OrgStandardRepository orgStandardRepo;
    private final ProjectStandardOverrideRepository overrideRepo;
    private final StandardsParser parser;

    /**
     * @param orgId                 project's org
     * @param projectId             the project
     * @param projectLevelYaml      raw YAML from
     *                              {@code .executespec/config/<project>/standards.yaml};
     *                              null/blank when the file doesn't exist yet
     * @return list of effective standards with provenance metadata
     */
    public List<Effective> resolve(String orgId, String projectId, String projectLevelYaml) {
        Map<String, Effective> byId = new HashMap<>();

        // 1 + 2 — load org-level standards + this project's overrides
        Map<String, ProjectStandardOverride> overrides = new HashMap<>();
        for (ProjectStandardOverride o : overrideRepo.findByProjectId(projectId)) {
            overrides.put(o.getStandardId(), o);
        }

        // 3 — apply org-level rows + overrides
        for (OrgStandard os : orgStandardRepo.findByOrgId(orgId)) {
            try {
                List<Standard> parsed = parser.parse(os.getStandardYaml());
                if (parsed.isEmpty()) continue;
                Standard s = parsed.get(0); // one YAML body per OrgStandard row
                Effective effective = applyOverride(s, os.isOrgLevelLocked(), overrides.get(s.id()));
                if (effective != null) byId.put(s.id(), effective);
            } catch (Exception ex) {
                log.warn("[EffectiveStandardsResolver] org standard {}/{} parse failed: {}",
                        orgId, os.getStandardId(), ex.getMessage());
            }
        }

        // 4 — merge project-level standards (project overrides org-level when
        // same id, subject to orgLevelLocked)
        if (projectLevelYaml != null && !projectLevelYaml.isBlank()) {
            try {
                for (Standard s : parser.parse(projectLevelYaml)) {
                    Effective existing = byId.get(s.id());
                    if (existing != null && existing.orgLevelLocked()) {
                        log.warn("[EffectiveStandardsResolver] project {} attempted to override "
                                + "org-level-locked standard '{}' — ignoring", projectId, s.id());
                        continue;
                    }
                    byId.put(s.id(), new Effective(s, Source.PROJECT, false, s.severity(), false));
                }
            } catch (Exception ex) {
                log.warn("[EffectiveStandardsResolver] project {} standards.yaml parse failed: {}",
                        projectId, ex.getMessage());
            }
        }

        return new ArrayList<>(byId.values());
    }

    /**
     * Apply an override (if any) to an org-level standard. Returns null when
     * the override excludes the standard ({@link
     * ProjectStandardOverride.OverrideType#NOT_APPLICABLE}).
     */
    Effective applyOverride(Standard org, boolean orgLevelLocked, ProjectStandardOverride override) {
        if (override == null) {
            return new Effective(org, Source.ORG, false, org.severity(), orgLevelLocked);
        }
        if (orgLevelLocked) {
            log.warn("[EffectiveStandardsResolver] org-level-locked '{}' had an override "
                    + "row — ignoring (override row should not have been written)", org.id());
            return new Effective(org, Source.ORG, false, org.severity(), true);
        }
        return switch (override.overrideTypeEnum()) {
            case NOT_APPLICABLE -> null;
            case SEVERITY_DOWNGRADE -> {
                Standard.Severity newSev = parseSeverity(override.getNewSeverity());
                if (newSev == null) {
                    log.warn("[EffectiveStandardsResolver] override for '{}' has invalid "
                            + "newSeverity '{}'; falling back to org severity",
                            org.id(), override.getNewSeverity());
                    yield new Effective(org, Source.ORG, false, org.severity(), false);
                }
                if (severityRank(newSev) >= severityRank(org.severity())) {
                    // Not strictly lower → reject the upgrade per plan §5.7
                    log.warn("[EffectiveStandardsResolver] override for '{}' attempted to "
                            + "upgrade severity {} → {}; rejected, keeping {}",
                            org.id(), org.severity(), newSev, org.severity());
                    yield new Effective(org, Source.ORG, false, org.severity(), false);
                }
                Standard downgraded = new Standard(
                        org.id(), org.scope(), newSev, org.description(),
                        org.enforcement(), org.rationale(), org.relatedAdrs(), org.orgLevelLocked());
                yield new Effective(downgraded, Source.ORG, true, org.severity(), false);
            }
        };
    }

    static int severityRank(Standard.Severity sev) {
        return switch (sev) {
            case BLOCKING -> 3;
            case WARNING -> 2;
            case ADVISORY -> 1;
        };
    }

    static Standard.Severity parseSeverity(String s) {
        if (s == null) return null;
        try { return Standard.Severity.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException ex) { return null; }
    }

    /**
     * @param standard         the merged standard (severity may be downgraded)
     * @param source           ORG (came from org_standard) or PROJECT
     *                         (came from .executespec/config/.../standards.yaml)
     * @param overridden       true when a SEVERITY_DOWNGRADE override took effect
     * @param originalSeverity severity before override (matches
     *                         {@link Standard#severity()} when not overridden)
     * @param orgLevelLocked   true when the org marked this standard locked
     *                         (project cannot override)
     */
    public record Effective(
            Standard standard,
            Source source,
            boolean overridden,
            Standard.Severity originalSeverity,
            boolean orgLevelLocked) {}

    public enum Source { ORG, PROJECT }
}
