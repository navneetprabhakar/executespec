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
          target confirmed
        </span>
        <span>
          <b>Cost</b>
          cap before run
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
      <span>Every run event is recorded in the audit trail</span>
      <span>Cost is capped before the run starts</span>
    </div>
  );
}

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
    <div className='agent-operator-intro'>
      <div className='agent-operator-kicker'>
        {remoteSandboxEnabled ? 'Remote sandbox operator' : 'Remote workspace operator'}
      </div>
      <div className='agent-operator-grid'>
        <div>
          <h1>
            {remoteSandboxEnabled
              ? 'Run work in a remote sandbox.'
              : 'Run work in a policy-guarded server workspace.'}
          </h1>
          <p>
            Plan, code, test, review, and open a PR without touching your local machine. You approve
            the plan and cost before remote work starts.
          </p>
        </div>
        <div className='agent-operator-steps' aria-label='Remote execution workflow'>
          <span>Request</span>
          <span>Plan gate</span>
          <span>{remoteSandboxEnabled ? 'Sandbox work' : 'Workspace work'}</span>
          <span>Evidence</span>
          <span>PR / deploy</span>
        </div>
      </div>
      <RuntimeContractPanel sandboxEnabled={remoteSandboxEnabled} />
      <TrustBoundaryPanel />
      <div className='agent-empty-chips'>
        {suggestions.map((suggestion) => (
          <button key={suggestion} onClick={() => onPick(suggestion)}>
            {suggestion}
          </button>
        ))}
      </div>
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
