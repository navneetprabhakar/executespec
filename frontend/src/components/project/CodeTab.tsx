/**
 * CodeTab — combines Architecture · Repos · Tech-Debt · Standards · Contracts
 * into a single "Code" surface so engineers don't have to stitch the picture
 * across 3 tabs (per UX & Wireframe Report 2026-04-26 §3.2 §5.3).
 *
 * Layout:
 *   ┌── sub-tabs ────────────────────────────────────────────┐
 *   │  Architecture · Repos · Tech debt · Standards · Contracts
 *   └─────────────────────────────────────────────────────────┘
 *
 * Each sub-tab renders the existing panel — this is purely a re-organize,
 * not a rewrite, so existing functionality + a11y + 4-band tokens are all
 * preserved verbatim.
 */
import { useState } from 'react';
import ArchitectureViewer from './ArchitectureViewer';
import ProjectRepoManager from './ProjectRepoManager';
import TechDebtPanel from './TechDebtPanel';
import StandardsCompliancePanel from './StandardsCompliancePanel';
import ContractCoveragePanel from './ContractCoveragePanel';
import WorkspaceHealthPanel from './WorkspaceHealthPanel';
import AdrPanel from './AdrPanel';
import { cn } from '@/lib/utils';

type CodeSubTab = 'architecture' | 'repos' | 'tech-debt' | 'standards' | 'contracts' | 'health' | 'adrs';

const SUB_TABS: { key: CodeSubTab; label: string }[] = [
  { key: 'architecture', label: 'Architecture' },
  { key: 'repos', label: 'Repos' },
  { key: 'health', label: 'Health' },
  { key: 'tech-debt', label: 'Tech debt' },
  { key: 'standards', label: 'Standards' },
  { key: 'contracts', label: 'Contracts' },
  { key: 'adrs', label: 'ADRs' },
];

interface CodeTabProps {
  projectId: string;
  orgId: string | null | undefined;
  repos: any[];
  initialSub?: CodeSubTab;
}

export default function CodeTab({ projectId, orgId, repos, initialSub = 'architecture' }: CodeTabProps) {
  const [sub, setSub] = useState<CodeSubTab>(initialSub);

  return (
    <div className="space-y-3">
      {/* Sub-tab strip — reuses the same chip pattern as the parent IA */}
      <nav className="flex gap-0.5 overflow-x-auto -mx-3 px-3" role="tablist" aria-label="Code surfaces">
        {SUB_TABS.map((t) => (
          <button
            key={t.key}
            role="tab"
            aria-selected={sub === t.key}
            onClick={() => setSub(t.key)}
            className={cn(
              'px-2.5 py-1 text-[11px] font-medium whitespace-nowrap rounded-sm transition-colors',
              sub === t.key
                ? 'bg-accent-blue/10 text-accent-blue border border-accent-blue/30'
                : 'border border-transparent text-muted hover:text-secondary hover:border-[var(--border-default)]',
            )}
          >
            {t.label}
          </button>
        ))}
      </nav>

      <div role="tabpanel" aria-labelledby={sub}>
        {sub === 'architecture' && (
          <ArchitectureViewer projectId={projectId} repoCount={repos.length} />
        )}
        {sub === 'repos' && <ProjectRepoManager projectId={projectId} />}
        {sub === 'health' && <WorkspaceHealthPanel projectId={projectId} />}
        {sub === 'tech-debt' && <TechDebtPanel projectId={projectId} />}
        {sub === 'standards' && orgId && (
          <StandardsCompliancePanel projectId={projectId} orgId={orgId} />
        )}
        {sub === 'standards' && !orgId && (
          <div className="text-xs text-muted p-3">
            Standards require an organization context.
          </div>
        )}
        {sub === 'contracts' && (
          repos.length > 0 ? (
            <div className="space-y-2">
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
            <div className="text-xs text-muted p-3">
              Link at least one repository to scan for contracts.
            </div>
          )
        )}
        {sub === 'adrs' && <AdrPanel projectId={projectId} />}
      </div>
    </div>
  );
}
