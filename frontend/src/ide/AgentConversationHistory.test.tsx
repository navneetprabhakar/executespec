import {act, type ReactNode} from 'react';
import {createRoot} from 'react-dom/client';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {ConversationHistoryRow} from './AgentConversationHistory';

(globalThis as {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLDivElement;

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
});

afterEach(() => host.remove());

function renderRow(row: ReactNode) {
  const root = createRoot(host);
  act(() => root.render(row));
  return root;
}

describe('ConversationHistoryRow', () => {
  it('marks archived conversations read-only', () => {
    const close = vi.fn();
    const root = renderRow(
      <ConversationHistoryRow
        title='Todo app'
        meta='Web · now'
        active
        archived
        onSelect={() => {}}
        onClose={close}
      />,
    );

    expect(host.textContent).toContain('Archived · Web · now');
    const button = host.querySelector<HTMLButtonElement>(
      'button[aria-label="Close conversation Todo app"]',
    );
    expect(button?.disabled).toBe(true);
    button?.click();
    expect(close).not.toHaveBeenCalled();
    act(() => root.unmount());
  });

  it('disables close for a non-terminal linked run regardless of row selection', () => {
    const root = renderRow(
      <ConversationHistoryRow
        title='Running task'
        meta='Run'
        active={false}
        closeDisabledReason='Run working; close it after the run finishes.'
        onSelect={() => {}}
        onClose={() => {}}
      />,
    );

    expect(
      host.querySelector<HTMLButtonElement>('button[aria-label="Close conversation Running task"]')
        ?.disabled,
    ).toBe(true);
    act(() => root.unmount());
  });
});
