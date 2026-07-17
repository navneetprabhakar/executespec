import type * as api from '@/lib/api';

export interface PendingProjectChatConfirmation {
  scope: api.ProjectChatActionScope;
  confirmation: api.ProjectChatConfirmationReference;
}

type ConfirmationTransport = Pick<
  typeof api,
  'issueProjectChatConfirmation' | 'consumeProjectChatConfirmation'
>;

/** Memory-only one-shot holder. There is intentionally no persistence/serialization surface. */
export class ProjectChatConfirmationSession {
  private issued: {
    scope: api.ProjectChatActionScope;
    response: api.IssuedProjectChatConfirmation;
  } | null = null;
  private expiryTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private readonly transport: ConfirmationTransport,
    private readonly now: () => number = () => Date.now(),
  ) {}

  async issue(
    projectId: string,
    scope: api.ProjectChatActionScope,
  ): Promise<PendingProjectChatConfirmation> {
    this.cancel();
    const response = await this.transport.issueProjectChatConfirmation(projectId, scope);
    if (!response.confirmationToken || this.expired(response.confirmation)) {
      this.cancel();
      throw new Error('Project action confirmation expired before it could be displayed.');
    }
    this.issued = {scope: cloneScope(scope), response};
    this.scheduleExpiry(response.confirmation);
    const pending = this.pending();
    if (!pending)
      throw new Error('Project action confirmation expired before it could be displayed.');
    return pending;
  }

  pending(): PendingProjectChatConfirmation | null {
    if (!this.issued) return null;
    if (this.expired(this.issued.response.confirmation)) {
      this.cancel();
      return null;
    }
    return {
      scope: cloneScope(this.issued.scope),
      confirmation: {...this.issued.response.confirmation},
    };
  }

  cancel() {
    if (this.expiryTimer) clearTimeout(this.expiryTimer);
    this.expiryTimer = null;
    this.issued = null;
  }

  async consume(): Promise<api.ProjectChatConfirmationExecutionResult> {
    const issued = this.issued;
    if (!issued) throw new Error('No pending project action confirmation.');
    if (this.expired(issued.response.confirmation)) {
      this.cancel();
      throw new Error('Project action confirmation expired. Prepare the action again.');
    }
    // A timed-out consume may have committed server-side. Clear before dispatch and never retry.
    this.cancel();
    return this.transport.consumeProjectChatConfirmation(
      issued.response.confirmation.projectId,
      issued.response.confirmation.confirmationId,
      issued.response.confirmationToken,
      cloneScope(issued.scope),
    );
  }

  private expired(reference: api.ProjectChatConfirmationReference): boolean {
    const expiresAt = Date.parse(reference.expiresAt);
    return !Number.isFinite(expiresAt) || expiresAt <= this.now();
  }

  private scheduleExpiry(reference: api.ProjectChatConfirmationReference) {
    const remaining = Date.parse(reference.expiresAt) - this.now();
    this.expiryTimer = setTimeout(
      () => this.cancel(),
      Math.max(0, Math.min(remaining, 2_147_000_000)),
    );
  }
}

export const PROJECT_CHAT_UNAVAILABLE_ACTIONS = {
  SEMANTIC_SEARCH:
    'Semantic search is read-only but may spend provider credits; it has no scoped confirmation contract yet.',
  SUBAGENT:
    'Subagent creation has no project-chat confirmation contract and is unavailable from this surface.',
  PLAN_MUTATION:
    'Plan approval, rejection, and cancellation use their dedicated run gates; project chat cannot confirm them.',
} as const;

function cloneScope(scope: api.ProjectChatActionScope): api.ProjectChatActionScope {
  return {...scope, target: {...scope.target}};
}
