import {CommandIcon} from '@/ide/IdeIcons';

export const SANDBOX_PHASES = [
  {key: 'queued', label: 'Queued'},
  {key: 'planning', label: 'Plan'},
  {key: 'approval', label: 'Approval'},
  {key: 'coding', label: 'Code'},
  {key: 'building', label: 'Build'},
  {key: 'reviewing', label: 'Review'},
  {key: 'evidence', label: 'Evidence'},
  {key: 'done', label: 'Done'},
] as const;

const SANDBOX_SUGGESTIONS = [
  'Create a remote sandbox to add audit logging',
  'Fix a failing test in a remote sandbox',
  'Review waiting sandbox approvals',
  'Explain this project before I start work',
];

const WORKSPACE_SUGGESTIONS = [
  'Create a remote workspace to add audit logging',
  'Fix a failing test in a remote workspace',
  'Review waiting workspace approvals',
  'Explain this project before I start work',
];

export function RuntimeContractPanel({sandboxEnabled}: {sandboxEnabled: boolean}) {
  return (
    <div
      className='agent-contract-panel'
      aria-label={sandboxEnabled ? 'Sandbox contract' : 'Remote workspace contract'}
    >
      <div className='agent-contract-title'>What will happen before spend</div>
      <div className='agent-contract-grid'>
        <span>
          <b>Workspace</b>
          {sandboxEnabled ? 'isolated remote clone' : 'policy-guarded server clone'}
        </span>
        <span>
          <b>Branch</b>
          shown in plan
        </span>
        <span>
          <b>Tests</b>
          detected command
        </span>
        <span>
          <b>PR</b>
          publication policy shown
        </span>
        <span>
          <b>Cost</b>
          estimate and any cap shown
        </span>
        <span>
          <b>Local files</b>
          never touched
        </span>
      </div>
    </div>
  );
}

export function TrustBoundaryPanel({compact = false}: {compact?: boolean}) {
  return (
    <div className={compact ? 'agent-trust-panel compact' : 'agent-trust-panel'}>
      <span>Runs on a policy-guarded server workspace</span>
      <span>Your local files are never touched</span>
      <span>Approval required before any code runs</span>
      <span>Run actions and evidence are recorded subject to retention policy</span>
      <span>Cost estimate and any selected cap are shown before approval</span>
    </div>
  );
}

/**
 * Compact empty-state intro. One honest line plus suggestions; the full stage
 * list, pre-spend contract, and trust chips stay available — unweakened —
 * inside a collapsed "How it works" disclosure instead of dominating every new
 * conversation (founder feedback 2026-08-05).
 */
export function OperatorIntro({
  hasHistory,
  onPick,
  remoteSandboxEnabled = false,
}: {
  hasHistory: boolean;
  onPick: (text: string) => void;
  remoteSandboxEnabled?: boolean;
}) {
  const suggestions = remoteSandboxEnabled ? SANDBOX_SUGGESTIONS : WORKSPACE_SUGGESTIONS;
  return (
    <div className='agent-operator-intro compact'>
      <p className='agent-operator-lede'>
        Plan, code, test, and review in a policy-guarded{' '}
        {remoteSandboxEnabled ? 'remote sandbox' : 'server workspace'} — you approve the plan and
        cost before any spend.
      </p>
      <div className='agent-empty-chips'>
        {suggestions.map((suggestion) => (
          <button key={suggestion} onClick={() => onPick(suggestion)}>
            {suggestion}
          </button>
        ))}
      </div>
      <details className='agent-operator-details'>
        <summary>How it works</summary>
        <div className='agent-operator-details-body'>
          <p>
            Plan, code, test, and review without touching your local machine, then prepare a PR when
            publication gates pass. You approve the plan and cost before remote work starts; BYOK
            cost caps are optional.
          </p>
          <div className='agent-operator-steps' aria-label='Remote execution workflow'>
            <span>Request</span>
            <span>Plan gate</span>
            <span>{remoteSandboxEnabled ? 'Sandbox work' : 'Workspace work'}</span>
            <span>Evidence</span>
            <span>Publish / deploy</span>
          </div>
          <RuntimeContractPanel sandboxEnabled={remoteSandboxEnabled} />
          <TrustBoundaryPanel />
        </div>
      </details>
      {hasHistory && (
        <div className='agent-history-note'>
          Recent transcript is below. Start new remote work here or open Chats for older work.
        </div>
      )}
    </div>
  );
}

export function EmptyRuntimeState({
  onPick,
  remoteSandboxEnabled = false,
}: {
  onPick: (text: string) => void;
  remoteSandboxEnabled?: boolean;
}) {
  const suggestions = remoteSandboxEnabled ? SANDBOX_SUGGESTIONS : WORKSPACE_SUGGESTIONS;
  return (
    <div className='agent-empty'>
      <div className='agent-empty-icon'>
        <CommandIcon size={26} />
      </div>
      <strong>{remoteSandboxEnabled ? 'Remote sandbox ready' : 'Remote workspace ready'}</strong>
      <p>
        Describe the change, review the generated plan, then let ExecuteSpec work in a remote
        {remoteSandboxEnabled ? ' sandbox' : ' server workspace'}. Browser sessions never get local
        shell authority.
      </p>
      <div className='agent-empty-chips'>
        {suggestions.map((suggestion) => (
          <button key={suggestion} onClick={() => onPick(suggestion)}>
            {suggestion}
          </button>
        ))}
      </div>
    </div>
  );
}
