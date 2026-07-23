import {describe, expect, it} from 'vitest';

import {normalizeUiProvider, UI_PROVIDER_USAGE, uiSlashCommandsFor, uiSlashHelp} from './uiSlash';

describe('ui slash provider contract', () => {
  it('accepts every product provider and only keeps documented aliases', () => {
    expect(normalizeUiProvider('anthropic')).toBe('ANTHROPIC');
    expect(normalizeUiProvider('openai')).toBe('OPENAI');
    expect(normalizeUiProvider('gemini')).toBe('GEMINI');
    expect(normalizeUiProvider('google')).toBe('GEMINI');
    expect(normalizeUiProvider('deepseek')).toBe('DEEPSEEK');
    expect(normalizeUiProvider('openrouter')).toBe('OPENROUTER');
    expect(normalizeUiProvider('fireworks')).toBe('FIREWORKS');
    expect(normalizeUiProvider('sarvam')).toBeNull();
    expect(normalizeUiProvider('unknown')).toBeNull();
  });

  it('publishes the same provider usage on every web chat surface', () => {
    expect(UI_PROVIDER_USAGE).toBe(
      '/provider anthropic|openai|gemini|deepseek|openrouter|fireworks',
    );
    for (const surface of ['spec', 'project', 'run'] as const) {
      const provider = uiSlashCommandsFor(surface).find(
        (command) => command.command === '/provider',
      );
      expect(provider).toBeDefined();
      expect(`/provider ${provider?.args?.replace(/^<|>$/g, '')}`).toBe(UI_PROVIDER_USAGE);
      expect(uiSlashHelp(surface)).toContain(`/provider ${provider?.args}`);
    }
  });
});
