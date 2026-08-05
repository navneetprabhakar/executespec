import {describe, expect, it} from 'vitest';
import {safeInternalRedirect} from './authRedirect';

describe('safeInternalRedirect', () => {
  it('preserves internal path, query, and hash', () => {
    expect(safeInternalRedirect('/settings?section=notifications#notification-preferences')).toBe(
      '/settings?section=notifications#notification-preferences',
    );
    expect(safeInternalRedirect('/cli-login?port=62601&state=abc')).toBe(
      '/cli-login?port=62601&state=abc',
    );
  });

  it('rejects absolute, protocol-relative, backslash, and control-character targets', () => {
    expect(safeInternalRedirect('https://evil.example/path')).toBeUndefined();
    expect(safeInternalRedirect('//evil.example/path')).toBeUndefined();
    expect(safeInternalRedirect('/%2f%2fevil.example/path')).toBeUndefined();
    expect(safeInternalRedirect('/\\evil.example/path')).toBeUndefined();
    expect(safeInternalRedirect('/settings\nnext')).toBeUndefined();
  });
});
