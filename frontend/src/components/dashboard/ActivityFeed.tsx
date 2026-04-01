import { motion } from 'framer-motion';
import { CheckCircle, AlertCircle, Loader2, GitMerge, Rocket } from 'lucide-react';
import GlassPanel from '@/components/shared/GlassPanel';
import { relativeTime } from '@/lib/utils';
import type { RunSummary } from '@/lib/api';

const STATUS_ICONS: Record<string, React.ElementType> = {
  COMPLETE: CheckCircle,
  FAILED:   AlertCircle,
  WORKING:  Loader2,
  MERGING:  GitMerge,
  default:  Rocket,
};

const STATUS_COLORS: Record<string, string> = {
  COMPLETE: 'text-accent-green',
  FAILED:   'text-accent-red',
  WORKING:  'text-accent-blue',
  MERGING:  'text-secondary',
  default:  'text-muted',
};

interface ActivityFeedProps {
  runs: RunSummary[];
}

export default function ActivityFeed({ runs }: ActivityFeedProps) {
  const events = [...runs]
    .sort((a, b) => new Date(b.completedAt ?? b.startedAt).getTime() - new Date(a.completedAt ?? a.startedAt).getTime())
    .slice(0, 20);

  return (
    <GlassPanel className="p-4">
      <h3 className="text-sm font-semibold text-primary mb-3">Recent Activity</h3>
      <div className="space-y-2 max-h-64 overflow-y-auto">
        {events.length === 0 ? (
          <p className="text-sm text-muted py-4 text-center">No activity yet</p>
        ) : events.map((run, i) => {
          const Icon = STATUS_ICONS[run.status] ?? STATUS_ICONS.default;
          const color = STATUS_COLORS[run.status] ?? STATUS_COLORS.default;
          return (
            <motion.div
              key={run.runId}
              initial={{ opacity: 0, x: -8 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ delay: i * 0.03 }}
              className="flex items-start gap-2.5"
            >
              <Icon className={`h-4 w-4 mt-0.5 flex-shrink-0 ${color}`} />
              <div className="flex-1 min-w-0">
                <p className="text-xs text-secondary truncate">
                  {(run.userRequest ?? '').slice(0, 60)}
                </p>
                <p className="text-xs text-muted">{run.status} · {relativeTime(run.completedAt ?? run.startedAt)}</p>
              </div>
            </motion.div>
          );
        })}
      </div>
    </GlassPanel>
  );
}
