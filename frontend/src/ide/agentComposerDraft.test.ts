import {afterEach, describe, expect, it, vi} from 'vitest';
import {
  AGENT_COMPOSER_DRAFT_MAX_CHARS,
  agentComposerDraftKey,
  clearAgentComposerDraft,
  loadAgentComposerDraft,
  saveAgentComposerDraft,
} from './agentComposerDraft';

afterEach(() => {
  window.sessionStorage.clear();
  vi.restoreAllMocks();
});

describe('agentComposerDraftKey', () => {
  it('keys the global surface when no scope ids exist', () => {
    expect(agentComposerDraftKey({})).toBe('es:agent-draft:v1:global');
    expect(agentComposerDraftKey({projectId: null, runId: null})).toBe('es:agent-draft:v1:global');
  });

  it('keys by project when only a project is scoped', () => {
    expect(agentComposerDraftKey({projectId: 'p-1'})).toBe('es:agent-draft:v1:project:p-1');
  });

  it('prefers the run scope over the project scope', () => {
    expect(agentComposerDraftKey({projectId: 'p-1', runId: 'r-9'})).toBe(
      'es:agent-draft:v1:run:r-9',
    );
  });
});

describe('save/load/clear round trip', () => {
  it('restores a saved draft for the same scope', () => {
    saveAgentComposerDraft({projectId: 'p-1'}, 'add a health endpoint');
    expect(loadAgentComposerDraft({projectId: 'p-1'})).toBe('add a health endpoint');
  });

  it('keeps drafts isolated per scope', () => {
    saveAgentComposerDraft({projectId: 'p-1'}, 'project one draft');
    saveAgentComposerDraft({projectId: 'p-2'}, 'project two draft');
    saveAgentComposerDraft({}, 'global draft');
    expect(loadAgentComposerDraft({projectId: 'p-1'})).toBe('project one draft');
    expect(loadAgentComposerDraft({projectId: 'p-2'})).toBe('project two draft');
    expect(loadAgentComposerDraft({})).toBe('global draft');
    expect(loadAgentComposerDraft({runId: 'r-1'})).toBeNull();
  });

  it('returns null when nothing was saved', () => {
    expect(loadAgentComposerDraft({projectId: 'missing'})).toBeNull();
  });

  it('clears the stored draft explicitly', () => {
    saveAgentComposerDraft({runId: 'r-1'}, 'kill me later');
    clearAgentComposerDraft({runId: 'r-1'});
    expect(loadAgentComposerDraft({runId: 'r-1'})).toBeNull();
  });

  it('treats an empty save as a clear (message sent / composer emptied)', () => {
    saveAgentComposerDraft({projectId: 'p-1'}, 'draft text');
    saveAgentComposerDraft({projectId: 'p-1'}, '');
    expect(loadAgentComposerDraft({projectId: 'p-1'})).toBeNull();
    expect(window.sessionStorage.getItem(agentComposerDraftKey({projectId: 'p-1'}))).toBeNull();
  });

  it('treats a whitespace-only save as a clear', () => {
    saveAgentComposerDraft({projectId: 'p-1'}, 'draft text');
    saveAgentComposerDraft({projectId: 'p-1'}, '   \n\t ');
    expect(loadAgentComposerDraft({projectId: 'p-1'})).toBeNull();
  });
});

describe('size bound', () => {
  it('truncates an oversized draft on save', () => {
    const oversized = 'x'.repeat(AGENT_COMPOSER_DRAFT_MAX_CHARS + 5_000);
    saveAgentComposerDraft({projectId: 'p-1'}, oversized);
    const restored = loadAgentComposerDraft({projectId: 'p-1'});
    expect(restored).toHaveLength(AGENT_COMPOSER_DRAFT_MAX_CHARS);
  });

  it('re-bounds an oversized value found in storage on load', () => {
    window.sessionStorage.setItem(
      agentComposerDraftKey({projectId: 'p-1'}),
      'y'.repeat(AGENT_COMPOSER_DRAFT_MAX_CHARS + 100),
    );
    expect(loadAgentComposerDraft({projectId: 'p-1'})).toHaveLength(AGENT_COMPOSER_DRAFT_MAX_CHARS);
  });

  it('keeps a draft exactly at the bound intact', () => {
    const exact = 'z'.repeat(AGENT_COMPOSER_DRAFT_MAX_CHARS);
    saveAgentComposerDraft({}, exact);
    expect(loadAgentComposerDraft({})).toBe(exact);
  });
});

describe('fail-open storage errors', () => {
  it('save never throws when setItem throws (quota exceeded)', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    expect(() => saveAgentComposerDraft({projectId: 'p-1'}, 'draft')).not.toThrow();
  });

  it('load never throws and returns null when getItem throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    expect(loadAgentComposerDraft({projectId: 'p-1'})).toBeNull();
  });

  it('clear never throws when removeItem throws', () => {
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    expect(() => clearAgentComposerDraft({projectId: 'p-1'})).not.toThrow();
  });

  it('empty-save clear path never throws when removeItem throws', () => {
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    expect(() => saveAgentComposerDraft({projectId: 'p-1'}, '')).not.toThrow();
  });

  it('fails open when sessionStorage access itself throws', () => {
    const original = Object.getOwnPropertyDescriptor(window, 'sessionStorage');
    Object.defineProperty(window, 'sessionStorage', {
      configurable: true,
      get() {
        throw new Error('storage disabled');
      },
    });
    try {
      expect(() => saveAgentComposerDraft({}, 'draft')).not.toThrow();
      expect(loadAgentComposerDraft({})).toBeNull();
      expect(() => clearAgentComposerDraft({})).not.toThrow();
    } finally {
      if (original) Object.defineProperty(window, 'sessionStorage', original);
    }
  });
});
