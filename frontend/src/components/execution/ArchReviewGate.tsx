import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { MarkdownContent } from '@/components/shared/MarkdownContent';
import { useExecutionStore } from '@/stores/useExecutionStore';

export default function ArchReviewGate({ executionId }: { executionId: string }) {
  const { executionRun, approveArch, generatePlan, abort } = useExecutionStore();
  const [annotations, setAnnotations] = useState('');
  const [approved, setApproved] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const navigate = useNavigate();

  const doc = executionRun?.unifiedArchDoc ?? '(no architecture doc generated yet)';

  return (
    <div className="space-y-3">
      <h3 className="text-[12px] font-medium text-primary">Unified architecture — review before planning</h3>
      <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 max-h-[400px] overflow-y-auto">
        <MarkdownContent content={doc} />
      </div>
      {!approved ? (
        <>
          <textarea
            value={annotations}
            onChange={(e) => setAnnotations(e.target.value)}
            rows={3}
            placeholder="Optional: add corrections or highlights the planner should respect"
            className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1 text-[12px] font-mono"
          />
          <div className="flex items-center gap-2">
            <button
              onClick={async () => { await approveArch(executionId, annotations); setApproved(true); }}
              className="rounded-sm border border-emerald-500/40 bg-emerald-500/10 text-emerald-400 px-3 py-1.5 text-[12px]"
            >
              Approve & continue
            </button>
            <button
              disabled={cancelling}
              onClick={async () => {
                if (!confirm('Cancel this execution? Progress will be discarded.')) return;
                setCancelling(true);
                try {
                  await abort(executionId);
                  const pid = executionRun?.projectId;
                  if (pid) navigate(`/projects/${pid}/dashboard`);
                } finally { setCancelling(false); }
              }}
              className="rounded-sm border border-[var(--border-default)] text-muted hover:text-accent-red px-3 py-1.5 text-[12px] disabled:opacity-50"
            >
              {cancelling ? 'Cancelling…' : 'Cancel'}
            </button>
          </div>
        </>
      ) : (
        <button
          onClick={() => generatePlan(executionId)}
          className="rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue px-3 py-1.5 text-[12px]"
        >
          Generate execution plan
        </button>
      )}
    </div>
  );
}
