import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes, useNavigate} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import IdePlan from './IdePlan';

(
  globalThis as typeof globalThis & {
    IS_REACT_ACT_ENVIRONMENT?: boolean;
  }
).IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({
  getRun: vi.fn(),
  getManagedAiBalance: vi.fn(),
  approveRun: vi.fn(),
  getPlanContractGate: vi.fn(),
  getRequirementGate: vi.fn(),
  cancelRun: vi.fn(),
}));

vi.mock('@/lib/api', () => api);
vi.mock('@/hooks/useSse', () => ({useSse: () => undefined}));
vi.mock('@/stores/useRunStore', () => ({
  useRunStore: (selector: (state: {activeRun: null}) => unknown) => selector({activeRun: null}),
}));
vi.mock('@/stores/useAuthStore', () => ({
  selectIsSuperAdmin: () => false,
  useAuthStore: (selector: (state: unknown) => unknown) => selector({}),
}));
vi.mock('@/hooks/useDocumentTitle', () => ({useDocumentTitle: () => undefined}));

/** SHA-256 of `{"tasks":["a"]}` / `{"tasks":["b"]}` — see planUtils.planFingerprint. */
async function sha256Hex(value: string): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

function runDetail(runId: string, planJson: string, credits: number) {
  return {
    runId,
    status: 'AWAITING_APPROVAL',
    planJson,
    creditsCharged: credits,
    planStrategy: 'SMALL',
    tasks: [],
  };
}

function requirementGate(
  runId: string,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    runId,
    gateKey: `gate:plan:${runId}`,
    gateStatus: 'PENDING',
    bindingMode: 'ENFORCED',
    bindingStatus: 'BOUND',
    bindingReason: 'NONE',
    enforced: true,
    approvalReady: true,
    schemaVersion: 1,
    compilerFingerprint: 'c'.repeat(64),
    sourceSha256: 's'.repeat(64),
    exactPlanFingerprint: '59604a18117b4b3cba1d3fd376d75f159fd8e3f6f3c7ae7304de0290e3ccb53c',
    artifactDigest: 'a'.repeat(64),
    boundDigest: 'b'.repeat(64),
    completeness: 'READY',
    requirementCount: 1,
    acceptanceCriterionCount: 1,
    taskBindingCount: 1,
    issueCount: 0,
    blockingIssueCount: 0,
    requirements: [
      {
        id: 'R1',
        text: 'Return 404 when the todo does not exist',
        kind: 'ERROR',
        origin: 'USER_STATED',
        state: 'EXPLICIT',
        acceptanceIds: ['A1'],
        taskIds: ['T1'],
      },
    ],
    acceptanceCriteria: [
      {
        id: 'A1',
        requirementIds: ['R1'],
        kind: 'STATUS_CODE',
        evidenceType: 'CONTRACT_TEST',
        expected: '404',
      },
    ],
    taskBindings: [{taskId: 'T1', requirementIds: ['R1']}],
    issues: [],
    ...overrides,
  };
}

function Harness() {
  const navigate = useNavigate();
  return (
    <>
      <button data-testid='go-b' onClick={() => navigate('/plan/run-b')}>
        go b
      </button>
      <IdePlan />
    </>
  );
}

let host: HTMLDivElement;
let root: Root;

function text(): string {
  return host.textContent || '';
}

function button(label: string): HTMLButtonElement | undefined {
  return Array.from(host.querySelectorAll('button')).find((b) =>
    (b.textContent || '').includes(label),
  ) as HTMLButtonElement | undefined;
}

beforeEach(() => {
  sessionStorage.clear();
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  api.getManagedAiBalance.mockResolvedValue(null);
  api.approveRun.mockResolvedValue(undefined);
  // RR-819 — 204 (no contract gate) is the default for the overwhelming majority of runs.
  api.getPlanContractGate.mockResolvedValue(undefined);
  // LEGACY gates have no RequirementIR artifact and therefore render no additional card.
  api.getRequirementGate.mockResolvedValue(undefined);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

async function render() {
  await act(async () => {
    root.render(
      <MemoryRouter initialEntries={['/plan/run-a']}>
        <Routes>
          <Route path='/plan/:runId' element={<Harness />} />
          <Route path='/live/:runId' element={<div>Live</div>} />
          <Route path='/runs' element={<div>Runs</div>} />
          <Route path='/compose' element={<div>Compose revised run</div>} />
        </Routes>
      </MemoryRouter>,
    );
  });
}

describe('IdePlan approval gate identity (RR-1459)', () => {
  it('surfaces an initial load failure and recovers through an explicit bounded retry', async () => {
    api.getRun
      .mockRejectedValueOnce(new Error('run detail unavailable'))
      .mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));

    await render();

    await vi.waitFor(() => expect(text()).toContain('run detail unavailable'));
    expect(text()).not.toContain('Loading plan…');
    expect(button('Approve and start work')).toBeUndefined();

    await act(async () => {
      button('Retry loading plan')?.click();
    });

    await vi.waitFor(() => expect(button('Approve and start work')).toBeTruthy());
    expect(text()).not.toContain('run detail unavailable');
    expect(api.getRun).toHaveBeenCalledTimes(2);
  });

  it('shows task descriptions, dependencies, requirements, constraints, and file scope', async () => {
    const planJson = JSON.stringify({
      projectType: 'python',
      runtimeSurface: 'HTTP',
      buildCommand: 'python3 -m compileall -q backend',
      smokeTestStrategy: 'BUILD',
      requirements: [{id: 'R1', text: 'Reservations are atomic.', kind: 'FUNCTIONAL'}],
      tasks: [
        {
          id: 'T2',
          title: 'Reserve inventory',
          description: 'Reserve every order line in one transaction.',
          complexity: 'HIGH',
          dependsOn: ['T1'],
          meetsRequirements: ['R1'],
          hardConstraints: ['Never partially reserve an order.'],
          touchesFiles: ['backend/app/orders.py'],
        },
      ],
    });
    api.getRun.mockResolvedValue({
      ...runDetail('run-a', planJson, 3),
      tasks: [{taskId: 'run-a_T2', title: 'Reserve inventory', status: 'PENDING'}],
    });

    await render();

    expect(text()).toContain('Step contracts');
    expect(text()).toContain('Reserve inventory · HIGH');
    const details = host.querySelector('details') as HTMLDetailsElement;
    details.open = true;
    expect(text()).toContain('Reserve every order line in one transaction.');
    expect(text()).toContain('T1');
    expect(text()).toContain('R1: Reservations are atomic.');
    expect(text()).toContain('Never partially reserve an order.');
    expect(text()).toContain('backend/app/orders.py');
  });

  it('recovers when SSE misses the transition from planning to the durable approval gate', async () => {
    vi.useFakeTimers();
    api.getRun
      .mockResolvedValueOnce({
        ...runDetail('run-a', '{"tasks":["a"]}', 3),
        status: 'PLANNING',
      })
      .mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));

    try {
      await render();
      expect(text()).toContain('Planning — classifying + outlining your request');

      await act(async () => {
        await vi.advanceTimersByTimeAsync(3_000);
      });

      expect(text()).toContain('3 credit(s)');
      expect(button('Approve and start work')).toBeTruthy();
      expect(api.getRun).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('does not carry run A gate state across a runId change to run B', async () => {
    let resolveB: ((value: unknown) => void) | undefined;
    api.getRun.mockImplementation((id: string) => {
      if (id === 'run-a') return Promise.resolve(runDetail('run-a', '{"tasks":["a"]}', 3));
      return new Promise((resolve) => {
        resolveB = resolve;
      });
    });

    await render();
    expect(text()).toContain('3 credit(s)');

    // Open the revision editor and type feedback so we can prove the draft does not leak either.
    await act(async () => {
      button('Revise as new run')?.click();
    });
    const textarea = host.querySelector('textarea') as HTMLTextAreaElement;
    expect(textarea).toBeTruthy();

    // Route moves to run B while run B's detail has not arrived yet.
    await act(async () => {
      (host.querySelector('[data-testid="go-b"]') as HTMLButtonElement).click();
    });

    // Run A's plan, credits and revision editor must all be gone in the same frame — nothing
    // approvable may remain on screen while the displayed plan belongs to a different run.
    expect(text()).not.toContain('3 credit(s)');
    expect(text()).not.toContain('Approve and start work');
    expect(host.querySelector('textarea')).toBeNull();
    expect(text()).toContain('Loading plan');
    expect(api.approveRun).not.toHaveBeenCalled();

    // Once B arrives the gate shows B's own numbers.
    await act(async () => {
      resolveB?.(runDetail('run-b', '{"tasks":["b"]}', 9));
    });
    expect(text()).toContain('9 credit(s)');
  });

  it('binds the approval to the fingerprint of the plan actually displayed', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    await render();

    await vi.waitFor(() => expect(button('Approve and start work')?.disabled).toBe(false));
    await act(async () => {
      button('Approve and start work')?.click();
    });

    expect(api.approveRun).toHaveBeenCalledWith(
      'run-a',
      true,
      3,
      undefined,
      await sha256Hex('{"tasks":["a"]}'),
      undefined,
    );
  });

  it('disables approval with an explicit error when exact plan verification is unavailable', async () => {
    vi.stubGlobal('crypto', {});
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    await render();

    await vi.waitFor(() => {
      expect(text()).toContain('Exact plan verification is unavailable in this browser.');
    });
    expect(text()).toContain('Approval is unavailable.');
    expect(button('Approve and start work')?.disabled).toBe(true);

    await act(async () => button('Approve and start work')?.click());
    expect(api.approveRun).not.toHaveBeenCalled();
  });

  it('starts a new-run handoff instead of calling the unsupported edit-plan endpoint', async () => {
    api.getRun.mockResolvedValue({
      ...runDetail('run-a', '{"tasks":["a"]}', 3),
      userRequest: 'Build an inventory API without authentication.',
    });
    await render();

    await act(async () => {
      button('Revise as new run')?.click();
    });
    const textarea = host.querySelector('textarea') as HTMLTextAreaElement;
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')
        ?.set as (value: string) => void;
      setter.call(textarea, 'Remove invented authentication tests.');
      textarea.dispatchEvent(new Event('input', {bubbles: true}));
    });
    await act(async () => {
      button('Start revised run')?.click();
    });

    const handoff = JSON.parse(sessionStorage.getItem('signal:pending-spec') || '{}');
    expect(handoff.source).toBe('plan-revision');
    expect(handoff.spec).toContain('Build an inventory API without authentication.');
    expect(handoff.spec).toContain('Remove invented authentication tests.');
    expect(text()).toContain('Compose revised run');
  });

  it('ignores a run detail that belongs to a different run than the route', async () => {
    // A server/store answer for the wrong run must never become an approvable gate.
    api.getRun.mockResolvedValue(runDetail('some-other-run', '{"tasks":["x"]}', 7));
    await render();

    expect(text()).toContain('did not match this work item');
    expect(button('Retry loading plan')).toBeTruthy();
    expect(text()).not.toContain('7 credit(s)');
    expect(button('Approve and start work')).toBeUndefined();
  });
});

const GATE_ROWS = [
  {
    contractId: 'c-1',
    contractName: 'Orders API',
    type: 'OPENAPI',
    detectedPath: 'api/orders.yaml',
    declaredConsumers: ['acme/billing'],
    matchedPlanTasks: ['t-2'],
  },
];

function gateReport(runId: string, digest: string, requiresTypedAck = true) {
  return {
    runId,
    reportJson: JSON.stringify(GATE_ROWS),
    reportDigest: digest,
    requiresTypedAck,
    contractCount: GATE_ROWS.length,
  };
}

function ackInput(): HTMLInputElement | null {
  return host.querySelector('[data-testid="breaking-ack-input"]');
}

async function typeAck(value: string) {
  const input = ackInput();
  if (!input) throw new Error('acknowledgement input is not rendered');
  await act(async () => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set as (
      v: string,
    ) => void;
    setter.call(input, value);
    input.dispatchEvent(new Event('input', {bubbles: true}));
  });
}

describe('IdePlan breaking-change acknowledgement gate (RR-819)', () => {
  it('fails closed and retries when contract impact cannot be loaded', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getPlanContractGate
      .mockRejectedValueOnce(new Error('contract service unavailable'))
      .mockResolvedValue(undefined);

    await render();

    await vi.waitFor(() => expect(text()).toContain('contract service unavailable'));
    expect(button('Approve and start work')?.disabled).toBe(true);
    expect(api.approveRun).not.toHaveBeenCalled();

    await act(async () => {
      button('Retry contract check')?.click();
    });

    await vi.waitFor(() => expect(button('Approve and start work')?.disabled).toBe(false));
    expect(text()).not.toContain('contract service unavailable');
    expect(api.getPlanContractGate).toHaveBeenCalledTimes(2);
  });

  it('shows what breaks and blocks approval until the exact literal BREAKING is typed', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getPlanContractGate.mockResolvedValue(gateReport('run-a', 'DIGEST-A'));
    await render();

    // The approver must see WHAT breaks, not a bare confirmation box.
    expect(text()).toContain('Orders API');
    expect(text()).toContain('api/orders.yaml');
    expect(text()).toContain('acme/billing');

    const approve = button('Approve and start work');
    expect(approve?.disabled).toBe(true);

    await typeAck('breaking');
    expect(button('Approve and start work')?.disabled).toBe(true);
    await typeAck('BREAKIN');
    expect(button('Approve and start work')?.disabled).toBe(true);

    await typeAck('BREAKING');
    expect(button('Approve and start work')?.disabled).toBe(false);
  });

  it('refuses to approve even if the disabled button is clicked before the ack is typed', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getPlanContractGate.mockResolvedValue(gateReport('run-a', 'DIGEST-A'));
    await render();

    await act(async () => {
      button('Approve and start work')?.click();
    });
    expect(api.approveRun).not.toHaveBeenCalled();
  });

  it('sends the ack bound to the digest of the report it actually fetched', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getPlanContractGate.mockResolvedValue(gateReport('run-a', 'DIGEST-A'));
    await render();

    await typeAck('BREAKING');
    await act(async () => {
      button('Approve and start work')?.click();
    });

    expect(api.approveRun).toHaveBeenCalledWith(
      'run-a',
      true,
      3,
      undefined,
      await sha256Hex('{"tasks":["a"]}'),
      {ack: 'BREAKING', reportDigest: 'DIGEST-A'},
    );
  });

  it('never lets a gate report for a different run gate — or enable — this one', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    // A report the server (or a stale in-flight fetch) attributes to another run.
    api.getPlanContractGate.mockResolvedValue(gateReport('run-zzz', 'DIGEST-Z'));
    await render();

    // It must not render as this run's evidence...
    expect(host.querySelector('[data-testid="plan-contract-gate"]')).toBeNull();
    expect(text()).not.toContain('Orders API');

    // ...and it must not travel with this run's approval either.
    await vi.waitFor(() => expect(button('Approve and start work')?.disabled).toBe(false));
    await act(async () => {
      button('Approve and start work')?.click();
    });
    await vi.waitFor(async () => {
      expect(api.approveRun).toHaveBeenCalledWith(
        'run-a',
        true,
        3,
        undefined,
        await sha256Hex('{"tasks":["a"]}'),
        undefined,
      );
    });
  });

  it('drops the acknowledgement when the route moves to another run', async () => {
    api.getRun.mockImplementation((id: string) =>
      Promise.resolve(
        id === 'run-a'
          ? runDetail('run-a', '{"tasks":["a"]}', 3)
          : runDetail('run-b', '{"tasks":["b"]}', 9),
      ),
    );
    api.getPlanContractGate.mockImplementation((id: string) =>
      Promise.resolve(id === 'run-a' ? gateReport('run-a', 'DIGEST-A') : undefined),
    );
    await render();

    await typeAck('BREAKING');
    expect(button('Approve and start work')?.disabled).toBe(false);

    await act(async () => {
      (host.querySelector('[data-testid="go-b"]') as HTMLButtonElement).click();
    });

    // Run B has no gate, so it approves with no friction — but never carrying run A's ack.
    await vi.waitFor(() => expect(button('Approve and start work')?.disabled).toBe(false));
    await act(async () => {
      button('Approve and start work')?.click();
    });
    expect(api.approveRun).toHaveBeenCalledWith(
      'run-b',
      true,
      9,
      undefined,
      await sha256Hex('{"tasks":["b"]}'),
      undefined,
    );
  });

  it('separates the two 409s: missing acknowledgement vs a report that moved', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getPlanContractGate.mockResolvedValue(gateReport('run-a', 'DIGEST-A'));
    api.approveRun.mockRejectedValueOnce(
      new Error(
        'The breaking-change report you acknowledged is not the one currently on this run.' +
          ' Reload the plan and review the change again before approving.',
      ),
    );
    await render();

    await typeAck('BREAKING');
    await act(async () => {
      button('Approve and start work')?.click();
    });

    // The click starts with an async SHA-256 plan fingerprint. Under the full parallel Vitest run,
    // that digest can resolve after React's initial act turn, so wait for the actual rejection
    // recovery instead of asserting while the button still says "Approving…".
    await vi.waitFor(() => {
      expect(text()).toContain('reload');
      // The stale acknowledgement is dropped and the report is re-read, so the user
      // re-acknowledges the change they will actually ship.
      expect(ackInput()?.value).toBe('');
      expect(button('Approve and start work')?.disabled).toBe(true);
      expect(api.getPlanContractGate.mock.calls.length).toBeGreaterThan(1);
    });
  });

  it('does not add any friction to a run with no contract gate (the 204 case)', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getPlanContractGate.mockResolvedValue(undefined);
    await render();

    expect(host.querySelector('[data-testid="plan-contract-gate"]')).toBeNull();
    await vi.waitFor(() => expect(button('Approve and start work')?.disabled).toBe(false));

    await act(async () => {
      button('Approve and start work')?.click();
    });
    expect(api.approveRun).toHaveBeenCalledWith(
      'run-a',
      true,
      3,
      undefined,
      await sha256Hex('{"tasks":["a"]}'),
      undefined,
    );
  });

  it('does not gate when the server report says no typed acknowledgement is required', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getPlanContractGate.mockResolvedValue(gateReport('run-a', 'DIGEST-A', false));
    await render();

    expect(host.querySelector('[data-testid="plan-contract-gate"]')).toBeNull();
    await vi.waitFor(() => expect(button('Approve and start work')?.disabled).toBe(false));
  });

  it('renders exact blocking RequirementIR evidence and refuses approval', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getRequirementGate.mockResolvedValue(
      requirementGate('run-a', {
        bindingStatus: 'BLOCKED',
        approvalReady: false,
        completeness: 'BLOCKED',
        issueCount: 1,
        blockingIssueCount: 1,
        requirements: [
          {
            id: 'R1',
            text: 'Define the not-found response',
            kind: 'ERROR',
            origin: 'USER_STATED',
            state: 'NEEDS_CLARIFICATION',
            acceptanceIds: [],
            taskIds: ['T1'],
          },
        ],
        issues: [
          {
            code: 'MISSING_ACCEPTANCE',
            blocking: true,
            message: 'Error behavior has no status-code or response-shape acceptance criterion.',
            requirementIds: ['R1'],
          },
        ],
      }),
    );
    await render();

    await vi.waitFor(() => expect(text()).toContain('Resolve before approval'));
    expect(text()).toContain('Missing Acceptance');
    expect(text()).toContain('Error behavior has no status-code');
    expect(text()).toContain('Define the not-found response');
    expect(button('Approve and start work')?.disabled).toBe(true);

    await act(async () => button('Approve and start work')?.click());
    expect(api.approveRun).not.toHaveBeenCalled();
  });

  it('shows READY RequirementIR coverage without adding approval friction', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getRequirementGate.mockResolvedValue(requirementGate('run-a'));
    await render();

    await vi.waitFor(() => expect(text()).toContain('Requirement coverage'));
    expect(text()).toContain('1 requirement · 1 acceptance check · 1 task binding');
    expect(text()).toContain(`Bound evidence ${'b'.repeat(64)}`);
    expect(text()).toContain('Contract Test');
    expect(text()).toContain('Expected: 404');
    await vi.waitFor(() => expect(button('Approve and start work')?.disabled).toBe(false));
  });

  it('keeps blocked SHADOW evidence diagnostic-only', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getRequirementGate.mockResolvedValue(
      requirementGate('run-a', {
        bindingMode: 'SHADOW',
        bindingStatus: 'BLOCKED',
        enforced: false,
        approvalReady: false,
        completeness: 'BLOCKED',
        issueCount: 1,
        blockingIssueCount: 1,
        issues: [
          {
            code: 'MISSING_ACCEPTANCE',
            blocking: true,
            message: 'Diagnostic issue only.',
            requirementIds: ['R1'],
          },
        ],
      }),
    );
    await render();

    await vi.waitFor(() => expect(text()).toContain('Diagnostic only'));
    expect(text()).toContain('Diagnostic issue only.');
    expect(button('Approve and start work')?.disabled).toBe(false);
  });

  it('hides LEGACY evidence and preserves the existing approval path', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getRequirementGate.mockResolvedValue(
      requirementGate('run-a', {
        bindingMode: 'LEGACY',
        bindingStatus: 'LEGACY',
        enforced: false,
        approvalReady: true,
        schemaVersion: null,
        compilerFingerprint: null,
        sourceSha256: null,
        artifactDigest: null,
        boundDigest: null,
        completeness: null,
        requirementCount: 0,
        acceptanceCriterionCount: 0,
        taskBindingCount: 0,
        requirements: [],
        acceptanceCriteria: [],
        taskBindings: [],
      }),
    );
    await render();

    await vi.waitFor(() => expect(api.getRequirementGate).toHaveBeenCalledWith('run-a'));
    expect(host.querySelector('[data-testid="requirement-gate"]')).toBeNull();
    await vi.waitFor(() => expect(button('Approve and start work')?.disabled).toBe(false));
  });

  it('fails closed when RequirementIR evidence cannot be read and recovers on explicit retry', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getRequirementGate
      .mockRejectedValueOnce(new Error('requirement store unavailable'))
      .mockResolvedValueOnce(requirementGate('run-a'));
    await render();

    await vi.waitFor(() => expect(text()).toContain('requirement store unavailable'));
    expect(button('Approve and start work')?.disabled).toBe(true);
    await act(async () => button('Retry requirement check')?.click());
    await vi.waitFor(() => expect(text()).toContain('Requirement coverage'));
    expect(button('Approve and start work')?.disabled).toBe(false);
    expect(api.getRequirementGate).toHaveBeenCalledTimes(2);
  });

  it('rejects RequirementIR evidence attributed to a different run', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getRequirementGate.mockResolvedValue(requirementGate('run-b'));
    await render();

    await vi.waitFor(() => expect(text()).toContain('did not match this work item'));
    expect(host.querySelector('[data-testid="requirement-gate"]')).toBeNull();
    expect(button('Approve and start work')?.disabled).toBe(true);
  });

  it('rejects RequirementIR evidence bound to different plan bytes', async () => {
    api.getRun.mockResolvedValue(runDetail('run-a', '{"tasks":["a"]}', 3));
    api.getRequirementGate.mockResolvedValue(
      requirementGate('run-a', {exactPlanFingerprint: 'f'.repeat(64)}),
    );
    await render();

    await vi.waitFor(() => expect(text()).toContain('bound to a different plan'));
    expect(button('Approve and start work')?.disabled).toBe(true);
    await act(async () => button('Approve and start work')?.click());
    expect(api.approveRun).not.toHaveBeenCalled();
  });
});
