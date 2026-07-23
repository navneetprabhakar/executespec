import type {PendingProjectChatConfirmation} from '@/ide/projectChatConfirmation';

export function ProjectChatConfirmationCard({
  pending,
  busy,
  onConfirm,
  onCancel,
}: {
  pending: PendingProjectChatConfirmation;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <div className='ide-card agent-sandbox-proposal waiting' role='alert'>
      <div style={{fontSize: 11, fontWeight: 700, letterSpacing: '0.12em'}}>
        Explicit confirmation required
      </div>
      <dl style={{display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: '4px 12px'}}>
        <dt>Action</dt>
        <dd className='ide-mono'>{pending.scope.action}</dd>
        <dt>Provider</dt>
        <dd className='ide-mono'>{pending.scope.provider}</dd>
        <dt>Model</dt>
        <dd className='ide-mono'>{pending.scope.model}</dd>
        <dt>Target</dt>
        <dd className='ide-mono'>{formatConfirmationTarget(pending.scope.target)}</dd>
        <dt>Hard cap</dt>
        <dd className='ide-mono'>
          {pending.confirmation.budget.maxTotalTokens.toLocaleString()} tokens · $
          {pending.confirmation.budget.maxCostUsd} · {pending.confirmation.budget.maxProviderCalls}{' '}
          provider calls
        </dd>
        <dt>Expires</dt>
        <dd>{new Date(pending.confirmation.expiresAt).toLocaleTimeString()}</dd>
      </dl>
      <div style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
        This approval is one-time. A failure or timeout requires a new confirmation.
      </div>
      <div style={{display: 'flex', gap: 8}}>
        <button className='ide-btn' type='button' onClick={onConfirm} disabled={busy}>
          {busy ? 'Running…' : 'Confirm and run'}
        </button>
        <button className='ide-btn ghost' type='button' onClick={onCancel} disabled={busy}>
          Cancel
        </button>
      </div>
    </div>
  );
}

export function formatConfirmationTarget(target: Record<string, string>): string {
  const entries = Object.entries(target).sort(([a], [b]) => a.localeCompare(b));
  return entries.length
    ? entries.map(([key, value]) => `${key}=${value || '(default)'}`).join(', ')
    : '(none)';
}
