import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {ModelPicker, type ModelPickerOption} from './ModelPicker';

(
  globalThis as typeof globalThis & {
    IS_REACT_ACT_ENVIRONMENT?: boolean;
  }
).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLDivElement;
let root: Root;

const options: ModelPickerOption[] = [
  {value: '', label: 'Recommended', provider: 'anthropic', helper: 'Org / system default'},
  {value: 'claude-sonnet-5', label: 'Claude Sonnet 5', provider: 'anthropic'},
  {value: 'deepseek-v4-pro-native', label: 'DeepSeek V4 Pro (Native)', provider: 'deepseek'},
  {value: 'glm-5.2', label: 'GLM 5.2 (OpenRouter)', provider: 'openrouter'},
];

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

describe('ModelPicker', () => {
  it('renders provider marks beside model names', () => {
    const onChange = vi.fn();

    act(() => {
      root.render(
        <ModelPicker ariaLabel='Compose model' value='' options={options} onChange={onChange} />,
      );
    });

    const trigger = host.querySelector('.ide-model-picker-trigger') as HTMLButtonElement;
    expect(trigger.textContent).toContain('A');
    expect(trigger.textContent).toContain('Recommended');

    act(() => trigger.click());

    const menu = host.querySelector('[role="listbox"]') as HTMLDivElement;
    expect(menu).not.toBeNull();
    expect(menu.textContent).toContain('Claude Sonnet 5');
    expect(menu.textContent).toContain('DS');
    expect(menu.textContent).toContain('DeepSeek V4 Pro (Native)');
    expect(menu.textContent).toContain('OR');
    expect(menu.textContent).toContain('GLM 5.2 (OpenRouter)');
  });

  it('supports keyboard selection', async () => {
    const onChange = vi.fn();

    act(() => {
      root.render(
        <ModelPicker ariaLabel='Compose model' value='' options={options} onChange={onChange} />,
      );
    });

    const trigger = host.querySelector('.ide-model-picker-trigger') as HTMLButtonElement;

    await act(async () => {
      trigger.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowDown', bubbles: true}));
      await new Promise((resolve) => window.setTimeout(resolve, 0));
    });

    const menu = host.querySelector('[role="listbox"]') as HTMLDivElement;
    expect(menu).not.toBeNull();

    act(() => {
      menu.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowDown', bubbles: true}));
      menu.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowDown', bubbles: true}));
      menu.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true}));
    });

    expect(onChange).toHaveBeenCalledWith('deepseek-v4-pro-native');
    expect(host.querySelector('[role="listbox"]')).toBeNull();
  });
});
