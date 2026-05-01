import {useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {MarkdownContent} from '@/components/shared/MarkdownContent';
import {useExecutionStore} from '@/stores/useExecutionStore';

export default function ArchReviewGate({executionId}: {executionId: string}) {
  const {executionRun, approveArch, generatePlan, abort} = useExecutionStore();
  const [annotations, setAnnotations] = useState('');
  const [approved, setApproved] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const navigate = useNavigate();

  const doc = executionRun?.unifiedArchDoc ?? '(no architecture doc generated yet)';

  return (
    <div className='space-y-3'>
      <h3 className='text-sm font-medium text-primary'>
        Unified architecture — review before planning
      </h3>
      <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 max-h-[400px] overflow-y-auto'>
        <MarkdownContent content={doc} />
      </div>
      <textarea
        value={annotations}
        onChange={(e) => setAnnotations(e.target.value)}
        rows={3}
        disabled={approved}
        placeholder='Optional: add corrections or highlights the planner should respect'
        className='w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1 text-sm font-mono disabled:opacity-60'
      />
      <div className='flex items-center gap-2'>
        <button
          disabled={approved}
          onClick={async () => {
            setApproved(true);
            try {
              await approveArch(executionId, annotations);
              await generatePlan(executionId);
            } catch (e) {
              setApproved(false);
              throw e;
            }
          }}
          className='rounded-sm border border-status-success/40 bg-status-success/10 text-status-success px-3 py-1.5 text-sm disabled:opacity-60'
        >
          {approved ? 'Generating plan…' : 'Approve & continue'}
        </button>
        <button
          disabled={cancelling || approved}
          onClick={async () => {
            if (!confirm('Cancel this execution? Progress will be discarded.')) return;
            setCancelling(true);
            try {
              await abort(executionId);
              const pid = executionRun?.projectId;
              if (pid) navigate(`/projects/${pid}/dashboard`);
            } finally {
              setCancelling(false);
            }
          }}
          className='rounded-sm border border-[var(--border-default)] text-muted hover:text-accent-red px-3 py-1.5 text-sm disabled:opacity-50'
        >
          {cancelling ? 'Cancelling…' : 'Cancel'}
        </button>
      </div>
    </div>
  );
}
