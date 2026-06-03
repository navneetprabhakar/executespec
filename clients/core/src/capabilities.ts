/**
 * Capability registry — the single source of truth for the unified `spec` client's feature set
 * (design §4 Feature Matrix · §9 parity enforcement). Both renderers — the CLI and the VS Code
 * extension — are expected to cover the same capabilities off this one list. The parity check
 * (`core/test/parity.test.mjs`) diffs each capability's declared affordances against the *real*
 * surfaces (the CLI command table + the extension `package.json` manifest) so that a feature which
 * ships to only one surface, or a manifest command with no registry entry, fails CI.
 *
 * `status` per surface lets the matrix encode the full F1–F25 *target* while the build stays green
 * during the phased rollout: only a `shipped` affordance is hard-checked against the live wiring.
 * A `planned` row documents the parity gap without failing. Flip a surface to `shipped` in the
 * same change that wires it — that is the moment the parity check starts enforcing it.
 *
 * This module is pure data + pure selectors: no `node:`/`fs`/`vscode` imports, so it loads
 * identically in both renderers and in the test.
 */

/** Feature-Matrix id, e.g. `"F6"`. */
export type CapabilityId = `F${number}`;

export type SurfaceStatus = 'shipped' | 'planned';

export interface SurfaceAffordance {
  /**
   * The concrete handle on this surface: a CLI command path (`"run submit"`) or flag
   * (`"run submit --provider"`) for the CLI; a VS Code command id (`"executespec.signIn"`),
   * configuration key (`"executespec.host"`), or view id (`"executespecRuns"`) for the extension.
   * Never empty — an empty handle is the "—" the Feature Matrix forbids.
   */
  readonly handle: string;
  readonly status: SurfaceStatus;
}

export interface Capability {
  readonly id: CapabilityId;
  readonly title: string;
  readonly cli: SurfaceAffordance;
  readonly vscode: SurfaceAffordance;
  /** Mutates server/run state (governance + submit). */
  readonly mutating: boolean;
  /** Requires a linked/open workspace folder (local-hands, linking). */
  readonly needsWorkspace: boolean;
  /** Reachable through the conversational layer (chat REPL / sidebar). */
  readonly interactive: boolean;
  /** Reachable through a deterministic, scriptable path (verb / command). */
  readonly scriptable: boolean;
}

const cap = (
  id: CapabilityId,
  title: string,
  cli: SurfaceAffordance,
  vscode: SurfaceAffordance,
  flags: Pick<Capability, 'mutating' | 'needsWorkspace' | 'interactive' | 'scriptable'>,
): Capability => ({ id, title, cli, vscode, ...flags });

const shipped = (handle: string): SurfaceAffordance => ({ handle, status: 'shipped' });
const planned = (handle: string): SurfaceAffordance => ({ handle, status: 'planned' });

/**
 * The Feature Matrix (design §4). `shipped` reflects what is actually wired today (gen-1: the CLI
 * verbs + the five extension commands / two config keys); everything else is `planned` and tracked
 * for parity. Keep this list and the design doc's §4 table in lockstep.
 */
export const CAPABILITIES: readonly Capability[] = [
  cap('F1', 'Auth: sign in (PAT)', shipped('auth login'), shipped('executespec.signIn'),
    { mutating: true, needsWorkspace: false, interactive: false, scriptable: true }),
  cap('F2', 'Auth: status / whoami', shipped('auth status'), planned('executespec.account'),
    { mutating: false, needsWorkspace: false, interactive: false, scriptable: true }),
  cap('F3', 'Auth: sign out', shipped('auth logout'), shipped('executespec.signOut'),
    { mutating: true, needsWorkspace: false, interactive: false, scriptable: true }),
  cap('F4', 'Mint PAT (deep link)', planned('auth token --new'), planned('executespec.createToken'),
    { mutating: false, needsWorkspace: false, interactive: false, scriptable: true }),
  cap('F5', 'Chat (agentic)', planned('spec'), planned('executespec.chat'),
    { mutating: true, needsWorkspace: false, interactive: true, scriptable: false }),
  cap('F6', 'Submit run', shipped('run submit'), shipped('executespec.submitSpec'),
    { mutating: true, needsWorkspace: false, interactive: false, scriptable: true }),
  cap('F7', 'Watch run (live)', shipped('run watch'), shipped('executespec.watchRun'),
    { mutating: false, needsWorkspace: false, interactive: false, scriptable: true }),
  cap('F8', 'List runs', shipped('run list'), shipped('executespec.refreshRuns'),
    { mutating: false, needsWorkspace: false, interactive: false, scriptable: true }),
  cap('F9', 'View run detail', shipped('run view'), planned('executespec.viewRun'),
    { mutating: false, needsWorkspace: false, interactive: false, scriptable: true }),
  cap('F10', 'Approve plan gate', shipped('run approve'), planned('executespec.approve'),
    { mutating: true, needsWorkspace: false, interactive: true, scriptable: true }),
  cap('F11', 'Reject plan gate', shipped('run reject'), planned('executespec.reject'),
    { mutating: true, needsWorkspace: false, interactive: true, scriptable: true }),
  cap('F12', 'Cancel run', shipped('run cancel'), planned('executespec.cancel'),
    { mutating: true, needsWorkspace: false, interactive: false, scriptable: true }),
  cap('F13', 'Run report (score)', shipped('run report'), planned('executespec.report'),
    { mutating: false, needsWorkspace: false, interactive: false, scriptable: true }),
  cap('F14', 'Cost snapshot', planned('run cost'), planned('executespec.cost'),
    { mutating: false, needsWorkspace: false, interactive: false, scriptable: true }),
  cap('F15', 'Local-hands run', planned('run submit --local'), planned('executespec.runLocal'),
    { mutating: true, needsWorkspace: true, interactive: false, scriptable: true }),
  cap('F16', 'Tool-call permission prompts (local)', planned('repl [allow/deny]'),
    planned('executespec.toolPermission'),
    { mutating: true, needsWorkspace: true, interactive: true, scriptable: false }),
  cap('F17', 'Link directory ↔ project', planned('link'), planned('executespec.link'),
    { mutating: true, needsWorkspace: true, interactive: false, scriptable: true }),
  cap('F18', 'Unlink', planned('unlink'), planned('executespec.unlink'),
    { mutating: true, needsWorkspace: true, interactive: false, scriptable: true }),
  cap('F19', 'Select provider/model', shipped('run submit --provider'),
    shipped('executespec.defaultProvider'),
    { mutating: false, needsWorkspace: false, interactive: false, scriptable: true }),
  cap('F20', 'Open PR / artifacts', shipped('run view (PR url)'), planned('executespec.openPr'),
    { mutating: false, needsWorkspace: false, interactive: false, scriptable: true }),
  cap('F21', 'Local session resume', planned('spec (resume)'), planned('executespec.resumeSession'),
    { mutating: false, needsWorkspace: false, interactive: true, scriptable: false }),
  cap('F22', 'Config (host/defaults)', planned('config get/set'), shipped('executespec.host'),
    { mutating: true, needsWorkspace: false, interactive: false, scriptable: true }),
  cap('F23', 'Tail run logs', planned('run logs'), planned('executespec.logs'),
    { mutating: false, needsWorkspace: false, interactive: false, scriptable: true }),
  cap('F24', 'Retry failed task', planned('run retry'), planned('executespec.retryTask'),
    { mutating: true, needsWorkspace: false, interactive: false, scriptable: true }),
  cap('F25', 'Diagnostics / doctor', planned('doctor'), planned('executespec.doctor'),
    { mutating: false, needsWorkspace: false, interactive: false, scriptable: true }),
];

/** A VS Code handle that is a command id (as opposed to a config key or view id). */
export function isVscodeCommandHandle(handle: string): boolean {
  return handle.startsWith('executespec.');
}

/**
 * Parity-contract violations: any capability that fails to declare a non-empty handle on *both*
 * surfaces. An empty handle is the Feature Matrix "—" — a surface-exclusive capability, which the
 * design forbids. Returns human-readable messages (empty array = clean).
 */
export function parityContractViolations(
  caps: readonly Capability[] = CAPABILITIES,
): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const c of caps) {
    if (seen.has(c.id)) out.push(`${c.id}: duplicate capability id`);
    seen.add(c.id);
    if (!c.cli.handle.trim()) out.push(`${c.id} (${c.title}): missing CLI affordance`);
    if (!c.vscode.handle.trim()) out.push(`${c.id} (${c.title}): missing VS Code affordance`);
  }
  return out;
}
