import { motion } from 'framer-motion';
import { ChevronRight, FolderOpen, Github, RefreshCw, User } from 'lucide-react';
import GlowDot from '@/components/shared/GlowDot';
import { fmtTokens, relativeTime, truncate, describeRunStatus } from '@/lib/utils';
import type { RunSummary } from '@/lib/api';
import { cn } from '@/lib/utils';
import VerdictPill, { deriveVerdict } from '@/components/runs/VerdictPill';
import { ACTIVE_STATUSES, GATE_STATUSES } from '@/lib/constants';

// M-043 (Wave 2) — render VerdictPill on terminal rows. RunSummary
// only carries statusReason from the verdict-pill input set; that's
// enough for the decision tree's hard-block / yellow-light branches
// (FINAL_BUILD_FAILED, ALL_TASKS_FAILED, REVIEWER_FAILED, etc.).
// Pre-v2.5.10 runs without statusReason fall through to status-only
// rendering — VerdictPill returns null and we render nothing.
function isTerminal(status: string | undefined): boolean {
  if (!status) return false;
  if (ACTIVE_STATUSES.has(status)) return false;
  if (GATE_STATUSES.has(status)) return false;
  // Anything else (COMPLETE / FAILED / PARTIAL / CANCELLED / STUCK / QUEUED)
  // is terminal-ish for VerdictPill purposes; QUEUED is filtered upstream.
  return status !== 'QUEUED';
}

interface RunCardProps {
  run: RunSummary;
  isActive: boolean;
  onClick: () => void;
  index: number;
  compact?: boolean;
}

const cardVariants = {
  hidden: { opacity: 0, y: 8 },
  visible: (i: number) => ({
    opacity: 1, y: 0,
    transition: { delay: i * 0.04, duration: 0.25, ease: 'easeOut' },
  }),
};

export default function RunCard({ run, isActive, onClick, index, compact }: RunCardProps) {
  // R-190 (post-cutover) — `isVibe` source removed. The simplified-card
  // branch is now `compact`-only. RunList no longer passes compact=true
  // anywhere (it used to be `compact={isVibe}`); the branch stays for
  // explicit-compact callers and as the default sidebar-list rendering
  // when the host wants the dense list shape.

  // Derive verdict once per render. Returns null for in-flight runs and
  // pre-v2.5.10 terminals without statusReason.
  const verdictResult = isTerminal(run.status)
    ? deriveVerdict({ statusReason: run.statusReason ?? null })
    : null;

  // ── Compact card (sidebar / dense list rendering) ─────────────────────────
  if (compact) {
    return (
      <motion.button
        custom={index}
        variants={cardVariants}
        initial="hidden"
        animate="visible"
        onClick={onClick}
        className={cn(
          'w-full text-left p-3 rounded-md border transition-all duration-150',
          'hover:bg-[var(--gray-200)]',
          isActive
            ? 'border-l-2 border-l-[var(--text-primary)] border-[var(--border-hover)] bg-[var(--gray-200)]'
            : 'border-[var(--border-default)] hover:border-[var(--border-hover)]'
        )}
      >
        <div className="flex items-center gap-2.5">
          <GlowDot status={run.status} className="flex-shrink-0" />
          <div className="flex-1 min-w-0">
            <p className="text-sm font-medium text-primary line-clamp-1">
              {run.runTitle ?? truncate(run.userRequest ?? '', 40)}
            </p>
            <p className="text-xs text-muted mt-0.5">
              {relativeTime(run.startedAt)} · {describeRunStatus(run.status)}
            </p>
          </div>
          <ChevronRight className={cn('h-4 w-4 flex-shrink-0 transition-colors',
            isActive ? 'text-primary' : 'text-muted')} />
        </div>
      </motion.button>
    );
  }

  // ── Default: full card (Pro layout) ──────────────────────────────────────
  return (
    <motion.button
      custom={index}
      variants={cardVariants}
      initial="hidden"
      animate="visible"
      onClick={onClick}
      className={cn(
        'w-full text-left p-4 rounded-md border transition-all duration-150',
        'hover:bg-[var(--gray-200)]',
        isActive
          ? 'border-l-2 border-l-[var(--text-primary)] border-[var(--border-hover)] bg-[var(--gray-200)]'
          : 'border-[var(--border-default)] hover:border-[var(--border-hover)]'
      )}
    >
      <div className="flex items-start gap-3">
        <GlowDot status={run.status} className="mt-0.5 flex-shrink-0" />
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-0.5">
            <span className="font-mono text-xs text-muted">{run.runId.slice(0, 12)}</span>
            <span className="text-xs text-muted">{relativeTime(run.startedAt)}</span>
            {/* M-043 — verdict pill leads on terminal rows so the user sees
                "Shippable / Needs review / Blocked" before they click. */}
            {verdictResult && (
              <VerdictPill
                size="sm"
                statusReason={run.statusReason ?? null}
              />
            )}
            {run.approvedExecutionMode === 'BATCH' && (
              <span className="text-[9px] font-medium text-accent-emerald bg-accent-emerald/10 px-1.5 py-0.5 rounded-full leading-none">Batch</span>
            )}
            {run.isRetry && (
              <span className="inline-flex items-center gap-0.5 text-[9px] font-medium text-accent-amber bg-accent-amber/10 px-1.5 py-0.5 rounded-full leading-none">
                <RefreshCw className="h-2.5 w-2.5" />Retry
              </span>
            )}
          </div>
          <p className="text-[11px] text-muted mb-1 line-clamp-1">
            {describeRunStatus(run.status)}
          </p>
          {run.runTitle ? (
            <>
              <p className="text-sm font-medium text-primary line-clamp-1">
                {run.runTitle}
              </p>
              <p className="text-xs text-secondary line-clamp-1 leading-snug mt-0.5">
                {run.runSummary ?? truncate(run.userRequest ?? '', 60)}
              </p>
            </>
          ) : (
            <p className="text-sm text-primary line-clamp-2 leading-snug">
              {truncate(run.userRequest ?? '', 50)}
            </p>
          )}
          <div className="flex items-center gap-1.5 mt-2 text-xs text-muted flex-wrap">
            {run.createdByName && (
              <span className="inline-flex items-center gap-1">
                <User className="h-2.5 w-2.5" />
                {run.createdByName}
              </span>
            )}
            {run.createdByName && run.projectName && <span>·</span>}
            {run.projectName && (
              <span className="inline-flex items-center gap-1">
                <FolderOpen className="h-2.5 w-2.5" />
                {run.projectName}
              </span>
            )}
            {(run.createdByName || run.projectName) && run.keySource && <span>·</span>}
            {run.keySource && (
              <span>
                {run.keySource === 'SYSTEM' ? 'Platform' : run.keySource === 'OWN_KEYS' ? 'Own Keys' : 'Hybrid'}
              </span>
            )}
            {(run.totalInputTokens + run.totalOutputTokens) > 0 && (
              <>
                <span>·</span>
                <span>{fmtTokens(run.totalInputTokens + run.totalOutputTokens)} tok</span>
              </>
            )}
            {/* v2.5.14 Phase 2.3 — money is now neutral (was amber which
                read as "warning"). Still tabular-num aligned.
                v2.5.8 original: render $ spent next to tokens. */}
            {typeof run.actualCostUsd === 'number' && run.actualCostUsd > 0 && (
              <>
                <span>·</span>
                <span className="text-secondary font-medium tabular-nums">
                  ${run.actualCostUsd < 0.01 ? '<0.01' : run.actualCostUsd.toFixed(2)}
                </span>
              </>
            )}
            {run.repoUrl && (
              <>
                <span>·</span>
                <a
                  href={run.repoUrl}
                  target="_blank"
                  rel="noreferrer"
                  onClick={(e) => e.stopPropagation()}
                  className="inline-flex items-center gap-1 hover:text-primary transition-colors"
                >
                  <Github className="h-3 w-3" />
                  GitHub
                </a>
              </>
            )}
          </div>
        </div>
        <ChevronRight className={cn('h-4 w-4 flex-shrink-0 mt-1 transition-colors',
          isActive ? 'text-primary' : 'text-muted')} />
      </div>
    </motion.button>
  );
}
