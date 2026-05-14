/**
 * CodeTab — Architecture · Health · Tech-Debt · Standards · Contracts · ADRs · Invariants.
 *
 * v2.7.2 — Repos was promoted out of this tab to a top-level Project
 * tab so it's discoverable from the project header without clicking
 * through Code first. The legacy {@code ?tab=repos} URL still works
 * (ProjectDashboardPage's legacyTabToNew maps it to {@code tab:'repos'}).
 *
 * Layout:
 *   ┌── sub-tabs ────────────────────────────────────────────┐
 *   │  Architecture · Health · Tech debt · Standards ·       │
 *   │  Contracts · ADRs · Invariants                         │
 *   └─────────────────────────────────────────────────────────┘
 */
import {useState} from 'react';
import ArchitectureViewer from './ArchitectureViewer';
import CodeQualityPanel from './CodeQualityPanel';
import TechDebtPanel from './TechDebtPanel';
import StandardsCompliancePanel from './StandardsCompliancePanel';
import ContractCoveragePanel from './ContractCoveragePanel';
import WorkspaceHealthPanel from './WorkspaceHealthPanel';
import AdrPanel from './AdrPanel';
import InvariantPanel from './InvariantPanel';
import {cn} from '@/lib/utils';
import SegmentedTabs from '@/components/shared/SegmentedTabs';

export type CodeSubTab =
  | 'architecture'
  | 'tech-debt'
  | 'standards'
  | 'contracts'
  | 'health'
  | 'adrs'
  | 'invariants'
  | 'review';

const SUB_TABS: {key: CodeSubTab; label: string}[] = [
  {key: 'architecture', label: 'Architecture'},
  {key: 'health', label: 'Health'},
  {key: 'review', label: 'Code review'},
  {key: 'tech-debt', label: 'Tech debt'},
  {key: 'standards', label: 'Standards'},
  {key: 'contracts', label: 'Contracts'},
  {key: 'adrs', label: 'ADRs'},
  {key: 'invariants', label: 'Invariants'},
];

interface CodeTabProps {
  projectId: string;
  orgId: string | null | undefined;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  repos: any[];
  initialSub?: CodeSubTab;
  /**
   * Controlled-mode override: when provided, the parent owns the sub-tab
   * state and CodeTab re-renders whenever this changes (allowing deep-link
   * targets like EmptyProjectLanding's "Link a repo" to switch the sub
   * after mount, not only at mount). When omitted, falls back to the
   * uncontrolled internal state seeded by {@link initialSub}.
   */
  sub?: CodeSubTab;
  onSubChange?: (sub: CodeSubTab) => void;
}

export default function CodeTab({
  projectId,
  orgId,
  repos,
  initialSub = 'architecture',
  sub: controlledSub,
  onSubChange,
}: CodeTabProps) {
  const [internalSub, setInternalSub] = useState<CodeSubTab>(initialSub);
  const sub = controlledSub ?? internalSub;
  const setSub = (next: CodeSubTab) => {
    if (onSubChange) onSubChange(next);
    else setInternalSub(next);
  };

  return (
    <div className='space-y-3'>
      {/* 2026-05-14 UX feedback — swapped horizontal-scroll chip strip for
          SegmentedTabs (animated underline + More overflow). Pre-fix the
          phone showed "Tech d…" clipped against the viewport edge; now
          tab 5+ collapse into a "More ▾" dropdown so every label renders
          fully or under disclosure. Same controlled state, same behaviour. */}
      <div className='-mx-3 px-3'>
        <SegmentedTabs
          ariaLabel='Code surfaces'
          tabs={SUB_TABS.map((t) => ({key: t.key, label: t.label}))}
          active={sub}
          onChange={(k) => setSub(k as CodeSubTab)}
          overflowAs='menu'
          // Code tab has 8 surfaces; on 375px with "Code review" (~120px)
          // alone any visible-count > 2 pushes "More" off-canvas. 2 keeps
          // the two most-trafficked (Architecture + Health) inline; the
          // rest live in More.
          visibleOnPhone={2}
        />
      </div>

      <div role='tabpanel' aria-labelledby={sub}>
        {sub === 'architecture' && (
          <ArchitectureViewer projectId={projectId} repoCount={repos.length} />
        )}
        {sub === 'health' && <WorkspaceHealthPanel projectId={projectId} />}
        {sub === 'tech-debt' && <TechDebtPanel projectId={projectId} />}
        {sub === 'standards' && orgId && (
          <StandardsCompliancePanel projectId={projectId} orgId={orgId} />
        )}
        {sub === 'standards' && !orgId && (
          <div className='text-xs text-muted p-3'>Standards require an organization context.</div>
        )}
        {sub === 'contracts' &&
          (repos.length > 0 ? (
            <div className='space-y-2'>
              {/* eslint-disable-next-line @typescript-eslint/no-explicit-any */}
              {repos.map((r: any) => (
                <ContractCoveragePanel
                  key={r.id ?? r.repoId}
                  projectId={projectId}
                  repoId={r.id ?? r.repoId}
                  repoFullName={r.githubRepoFullName ?? r.repoName}
                />
              ))}
            </div>
          ) : (
            <div className='text-xs text-muted p-3'>
              Link at least one repository to scan for contracts.
            </div>
          ))}
        {sub === 'adrs' && <AdrPanel projectId={projectId} />}
        {sub === 'invariants' && <InvariantPanel projectId={projectId} />}
        {sub === 'review' && <CodeQualityPanel projectId={projectId} repos={repos} />}
      </div>
    </div>
  );
}
