import {ApiError} from '@/lib/api/transport';

type StoredAttempt = {
  key: string;
  digest: string;
  createdAtEpochMs: number;
};

type AttemptStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

function canonicalize(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>)
        .filter(([, item]) => item !== undefined)
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([key, item]) => [key, canonicalize(item)]),
    );
  }
  return value;
}

async function fingerprint(value: unknown): Promise<string> {
  const webCrypto = globalThis.crypto;
  if (!webCrypto?.subtle) {
    throw new Error('Secure browser hashing is unavailable. Reload in a supported browser.');
  }
  const canonicalBytes = new TextEncoder().encode(JSON.stringify(canonicalize(value)));
  const digest = await webCrypto.subtle.digest('SHA-256', canonicalBytes);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

function secureUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  if (typeof crypto === 'undefined' || typeof crypto.getRandomValues !== 'function') {
    throw new Error('Secure browser randomness is unavailable. Reload in a supported browser.');
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, '0'));
  return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-${hex
    .slice(6, 8)
    .join('')}-${hex.slice(8, 10).join('')}-${hex.slice(10).join('')}`;
}

/**
 * One browser-tab ledger for run-creating mutations.
 *
 * The key survives an ambiguous response and a same-tab reload through sessionStorage. A changed
 * payload is a different deliberate action and receives a new key. Successful/final responses
 * clear the attempt so repeating the same request later also creates fresh work.
 */
export class RunMutationAttemptLedger {
  private readonly memory = new Map<string, StoredAttempt>();

  constructor(
    private readonly storage: AttemptStorage | null = typeof sessionStorage === 'undefined'
      ? null
      : sessionStorage,
    private readonly uuid: () => string = secureUuid,
    private readonly now: () => number = Date.now,
  ) {}

  async begin(scope: string, payload: unknown): Promise<StoredAttempt> {
    const storageKey = this.storageKey(scope);
    // v1 stored canonical request bodies as its "fingerprint". Remove the known scoped legacy row
    // before hashing so an upgraded tab does not leave old spec text behind in sessionStorage.
    try {
      this.storage?.removeItem(this.legacyStorageKey(scope));
    } catch {
      // Storage availability does not change the in-memory retry contract.
    }
    const nextDigest = await fingerprint(payload);
    const existing = this.read(storageKey);
    if (existing?.digest === nextDigest) return existing;

    const created = {key: this.uuid(), digest: nextDigest, createdAtEpochMs: this.now()};
    this.write(storageKey, created);
    return created;
  }

  complete(scope: string, key: string): void {
    const storageKey = this.storageKey(scope);
    if (this.read(storageKey)?.key !== key) return;
    this.memory.delete(storageKey);
    try {
      this.storage?.removeItem(storageKey);
    } catch {
      // The in-memory ledger still protects retries for this page lifetime.
    }
  }

  private storageKey(scope: string): string {
    return `executespec:run-mutation:v2:${scope}`;
  }

  private legacyStorageKey(scope: string): string {
    return `executespec:run-mutation:v1:${scope}`;
  }

  private read(storageKey: string): StoredAttempt | null {
    const memory = this.memory.get(storageKey);
    if (memory) return memory;
    try {
      const raw = this.storage?.getItem(storageKey);
      if (!raw) return null;
      const parsed = JSON.parse(raw) as Partial<StoredAttempt>;
      if (
        typeof parsed.key !== 'string' ||
        typeof parsed.digest !== 'string' ||
        !/^[a-f0-9]{64}$/.test(parsed.digest) ||
        typeof parsed.createdAtEpochMs !== 'number'
      )
        return null;
      const attempt = {
        key: parsed.key,
        digest: parsed.digest,
        createdAtEpochMs: parsed.createdAtEpochMs,
      };
      this.memory.set(storageKey, attempt);
      return attempt;
    } catch {
      return null;
    }
  }

  private write(storageKey: string, attempt: StoredAttempt): void {
    this.memory.set(storageKey, attempt);
    try {
      this.storage?.setItem(storageKey, JSON.stringify(attempt));
    } catch {
      // Keep the current-page retry guarantee when storage is unavailable.
    }
  }
}

export const runMutationAttempts = new RunMutationAttemptLedger();

/** Keep only errors whose outcome may be unknown or whose same-key claim is still in flight. */
export function shouldRetainRunMutationAttempt(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false;
  if (error.status === 0 || error.status >= 500) return true;
  return error.status === 409 && /still in flight|retry shortly/i.test(error.message);
}

/** Executes one run-creating request while retaining its key only when the outcome is ambiguous. */
export async function executeRunMutation<T>(
  scope: string,
  payload: unknown,
  operation: (idempotencyKey: string) => Promise<T>,
): Promise<T> {
  const attempt = await runMutationAttempts.begin(scope, payload);
  try {
    const result = await operation(attempt.key);
    runMutationAttempts.complete(scope, attempt.key);
    return result;
  } catch (error) {
    if (!shouldRetainRunMutationAttempt(error)) {
      runMutationAttempts.complete(scope, attempt.key);
    }
    throw error;
  }
}
