import type { FileConflict } from '@/types/execution';
import { AlertTriangle } from 'lucide-react';

export default function ConflictWarning({ conflicts }: { conflicts: FileConflict[] }) {
  if (!conflicts || conflicts.length === 0) return null;
  return (
    <div className="rounded-sm border border-amber-500/40 bg-amber-500/10 p-2 text-xs text-accent-amber">
      <div className="flex items-center gap-1.5 font-medium mb-1">
        <AlertTriangle className="h-3 w-3" />
        File conflicts ({conflicts.length})
      </div>
      <ul className="space-y-0.5 list-disc ml-4">
        {conflicts.map((c, i) => (
          <li key={i}>
            <span className="font-mono">{c.file}</span> — touched by{' '}
            {c.solutions.map((n) => `S${n}`).join(', ')}. {c.suggestedResolution}
          </li>
        ))}
      </ul>
    </div>
  );
}
