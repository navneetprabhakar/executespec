import type * as api from '@/lib/api';

export type ArchDocSection = {heading: string; body: string};
export type ContractDetectorSummary = {
  scanned: number;
  failed: number;
  totalContracts: number;
  driftCandidateIds: string[];
};

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

export function canManageProjectSharing(input: {
  globalRole?: string | null;
  orgRole?: string | null;
  projectRole?: string | null;
  hasProjectMembership?: boolean;
}): boolean {
  if (input.globalRole === 'SUPER_ADMIN') return true;
  if (input.projectRole === 'PROJECT_ADMIN' || input.projectRole === 'PROJECT_DEVELOPER') {
    return true;
  }
  if (input.hasProjectMembership) return false;
  return input.orgRole === 'ORG_ADMIN';
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
