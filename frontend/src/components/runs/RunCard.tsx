import { motion } from 'framer-motion';
import { ChevronRight, FolderOpen, Github, RefreshCw, User } from 'lucide-react';
import GlowDot from '@/components/shared/GlowDot';
import { fmtTokens, relativeTime, truncate, describeRunStatus } from '@/lib/utils';
import type { RunSummary } from '@/lib/api';
import { cn } from '@/lib/utils';
import { useViewMode } from '@/hooks/useViewMode';

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
  const { isVibe } = useViewMode();

  // ── Vibe mode: simplified card ────────────────────────────────────────────
  if (isVibe || compact) {
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

  // ── Pro mode: full card ───────────────────────────────────────────────────
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
