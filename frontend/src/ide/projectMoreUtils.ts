import type * as api from '@/lib/api';

export type ArchDocSection = {heading: string; body: string};
export type ContractDetectorSummary = {
  scanned: number;
  failed: number;
  totalContracts: number;
  driftCandidateIds: string[];
};

export function uiBound<T>(promise: Promise<T>, timeoutMs = 10_000): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = globalThis.setTimeout(() => reject(new Error('Request timed out.')), timeoutMs);
    promise.then(
      (value) => {
        globalThis.clearTimeout(timer);
        resolve(value);
      },
      (error) => {
        globalThis.clearTimeout(timer);
        reject(error);
      },
    );
  });
}

export function looksLikeArtifact(content: string): boolean {
  return content.length > 1200 || /```|^#{2,}\s|\|.+\|/m.test(content);
}

// Change-detection stamp for a generated doc. The row carries no status field,
// so fresh generation is detected by version/generatedAt moving.
export function docStamp(row: api.GeneratedDocRow | null | undefined): string {
  return row ? `${row.version}·${row.generatedAt}` : '';
}

export function techDebtCategories(
  debt: api.TechDebtReport | null | undefined,
): Array<{name: string; count: number}> {
  if (!debt) return [];
  return Object.entries(debt.issuesByCategory || {})
    .map(([name, count]) => ({name, count}))
    .sort((a, b) => b.count - a.count);
}

export function filterProjectFiles(files: string[] | null | undefined, query: string): string[] {
  if (!files) return [];
  const q = query.trim().toLowerCase();
  return q ? files.filter((file) => file.toLowerCase().includes(q)) : files;
}

export function invariantViolationCounts(
  violations: api.InvariantViolation[] | null | undefined,
): Map<string, number> {
  const counts = new Map<string, number>();
  for (const violation of violations ?? []) {
    if (violation.invariantUuid) {
      counts.set(violation.invariantUuid, (counts.get(violation.invariantUuid) ?? 0) + 1);
    }
  }
  return counts;
}

export function contractDetectorSummary(
  detectors: api.DetectorSummary[] | null | undefined,
): ContractDetectorSummary {
  const rows = detectors ?? [];
  return {
    scanned: rows.filter((detector) => detector.status === 'SCANNED').length,
    failed: rows.filter((detector) => detector.status === 'FAILED').length,
    totalContracts: rows.reduce((sum, detector) => sum + (detector.contractIds?.length ?? 0), 0),
    driftCandidateIds: Array.from(new Set(rows.flatMap((detector) => detector.contractIds ?? []))),
  };
}

export function memberDisplayName(member: {
  firstName?: string | null;
  lastName?: string | null;
  email: string;
}): string {
  return [member.firstName, member.lastName].filter(Boolean).join(' ') || member.email;
}

export function isExternalProjectMember(
  member: api.ProjectMemberDto & {external?: boolean},
): boolean {
  return member.external === true;
}

export type ProjectAuthorityInput = {
  globalRole?: string | null;
  orgRole?: string | null;
  projectRole?: string | null;
  hasProjectMembership?: boolean;
};

/**
 * RR-1507 — membership administration (add/remove members, resend/revoke org
 * invites). The server's add/remove path is `ProjectService.requireProjectAdmin`,
 * which admits only SUPER_ADMIN, host-org ORG_ADMIN, or a `PROJECT_ADMIN`
 * membership. A `PROJECT_DEVELOPER` is NOT admitted, so the UI must not present
 * a fully enabled member-admin surface that returns 403 after every confirm.
 */
export function canManageMembership(input: ProjectAuthorityInput): boolean {
  if (input.globalRole === 'SUPER_ADMIN') return true;
  if (input.projectRole === 'PROJECT_ADMIN') return true;
  if (input.hasProjectMembership) return false;
  return input.orgRole === 'ORG_ADMIN';
}

/**
 * RR-1507 — share-link management. The server's share-link guard is
 * `isProjectWriter`, which additionally admits `PROJECT_DEVELOPER`. This is a
 * genuinely wider authority than membership management, so it is a separate
 * predicate rather than one boolean covering both.
 */
export function canManageShareLinks(input: ProjectAuthorityInput): boolean {
  if (canManageMembership(input)) return true;
  return input.projectRole === 'PROJECT_DEVELOPER';
}

/**
 * @deprecated RR-1507 — this single predicate conflated two different server
 * guards (membership vs share-links). Retained as an alias for the wider
 * share-link authority; call sites should choose `canManageMembership` or
 * `canManageShareLinks` explicitly.
 */
export function canManageProjectSharing(input: ProjectAuthorityInput): boolean {
  return canManageShareLinks(input);
}

export function availableOrgMembers(
  orgMembers: api.OrgMemberDto[] | null | undefined,
  projectMembers: api.ProjectMemberDto[] | null | undefined,
): api.OrgMemberDto[] {
  const projectMemberIds = new Set((projectMembers ?? []).map((member) => member.userId));
  return (orgMembers ?? []).filter((member) => !projectMemberIds.has(member.userId));
}

// Split a markdown doc into a preamble (text before the first `##`) and top-level `##` sections.
// Heading text is lifted out so the section header can be a clickable disclosure; the body keeps
// everything else, including `###` subsections, code, and tables.
export function parseArchDoc(md: string): {preamble: string; sections: ArchDocSection[]} {
  const sections: Array<{heading: string; body: string[]}> = [];
  const preamble: string[] = [];
  for (const line of md.split('\n')) {
    const match = /^##\s+(.*)$/.exec(line);
    if (match) sections.push({heading: match[1].trim(), body: []});
    else if (sections.length > 0) sections[sections.length - 1].body.push(line);
    else preamble.push(line);
  }
  return {
    preamble: preamble.join('\n'),
    sections: sections.map((section) => ({
      heading: section.heading,
      body: section.body.join('\n'),
    })),
  };
}

/**
 * RR-1658 — roll up a batch of drift checks into the three states the backend actually
 * reports. The UI used to count only `hasDrift`, but `DriftCheckResult.hasDrift` is false
 * for BOTH `NO_DRIFT` and `UNAVAILABLE` (retained that way for wire compatibility after
 * RR-1631), so a contract whose source could not be read — SCM outage, expired PAT —
 * was presented as clean.
 *
 * Rows from a server predating the tri-state carry no `status`; those fall back to the
 * `hasDrift` boolean, which is exactly as informative as it was before, and are never
 * counted as unavailable on the basis of a missing field.
 */
export function summarizeDriftResults(results: api.DriftCheckResult[] | null | undefined): {
  drifted: number;
  clean: number;
  unavailable: number;
  total: number;
} {
  const rows = results ?? [];
  let drifted = 0;
  let unavailable = 0;
  for (const row of rows) {
    const status = row.status ?? (row.hasDrift ? 'DRIFT' : 'NO_DRIFT');
    if (status === 'DRIFT') drifted += 1;
    else if (status === 'UNAVAILABLE') unavailable += 1;
  }
  return {drifted, clean: rows.length - drifted - unavailable, unavailable, total: rows.length};
}

/** User-facing sentence for a drift rollup that never calls an unperformable check clean. */
export function driftSummaryMessage(summary: {
  drifted: number;
  unavailable: number;
  total: number;
}): string {
  const parts: string[] = [];
  if (summary.drifted > 0) {
    parts.push(`${summary.drifted} drift result${summary.drifted === 1 ? '' : 's'} detected`);
  }
  if (summary.unavailable > 0) {
    parts.push(
      `${summary.unavailable} contract${summary.unavailable === 1 ? '' : 's'} could not be checked`,
    );
  }
  if (parts.length === 0) {
    return summary.total === 0 ? 'No contracts to check.' : 'No drift detected.';
  }
  return `${parts.join('; ')}.`;
}
