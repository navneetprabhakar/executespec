import { motion } from 'framer-motion';
import { ChevronRight, Cpu, Github } from 'lucide-react';
import GlowDot from '@/components/shared/GlowDot';
import { fmtTokens, relativeTime, truncate } from '@/lib/utils';
import type { RunSummary } from '@/lib/api';
import { cn } from '@/lib/utils';

interface RunCardProps {
  run: RunSummary;
  isActive: boolean;
  onClick: () => void;
  index: number;
}

const cardVariants = {
  hidden: { opacity: 0, y: 8 },
  visible: (i: number) => ({
    opacity: 1, y: 0,
    transition: { delay: i * 0.04, duration: 0.25, ease: 'easeOut' },
  }),
};

export default function RunCard({ run, isActive, onClick, index }: RunCardProps) {
  return (
    <motion.button
      custom={index}
      variants={cardVariants}
      initial="hidden"
      animate="visible"
      onClick={onClick}
      className={cn(
        'w-full text-left p-4 rounded-xl border transition-all duration-150',
        'bg-white/[0.02] hover:bg-white/[0.04]',
        'hover:-translate-y-px',
        isActive
          ? 'border-accent-violet/40 bg-accent-violet/[0.05] shadow-glow-violet'
          : 'border-white/[0.06] hover:border-white/[0.10]'
      )}
    >
      <div className="flex items-start gap-3">
        <GlowDot status={run.status} className="mt-0.5 flex-shrink-0" />
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1">
            <span className="font-mono text-xs text-zinc-400">{run.runId.slice(0, 12)}</span>
            <span className="text-xs text-zinc-600">{relativeTime(run.startedAt)}</span>
          </div>
          <p className="text-sm text-zinc-200 line-clamp-2 leading-snug">
            {truncate(run.userRequest, 120)}
          </p>
          <div className="flex items-center gap-2 mt-2 flex-wrap">
            <span className="inline-flex items-center gap-1 text-xs text-zinc-500">
              <Cpu className="h-3 w-3" />
              {run.plannerModel}
            </span>
            {(run.totalInputTokens + run.totalOutputTokens) > 0 && (
              <span className="text-xs text-zinc-600">{fmtTokens(run.totalInputTokens + run.totalOutputTokens)} tok</span>
            )}
            {run.repoUrl && (
              <a
                href={run.repoUrl}
                target="_blank"
                rel="noreferrer"
                onClick={(e) => e.stopPropagation()}
                className="inline-flex items-center gap-1 text-xs text-zinc-500 hover:text-zinc-200 transition-colors"
              >
                <Github className="h-3 w-3" />
                GitHub
              </a>
            )}
          </div>
        </div>
        <ChevronRight className={cn('h-4 w-4 flex-shrink-0 mt-1 transition-colors',
          isActive ? 'text-accent-violet' : 'text-zinc-600')} />
      </div>
    </motion.button>
  );
}
