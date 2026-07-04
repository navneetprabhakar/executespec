import {useEffect, useId, useMemo, useRef, useState} from 'react';
import type {CSSProperties} from 'react';
import {providerLabel} from '@/signal/lib/runUtils';

export type ModelPickerOption = {
  value: string;
  label: string;
  provider: string;
  helper?: string;
};

type ModelPickerProps = {
  value: string;
  options: ModelPickerOption[];
  onChange: (value: string) => void;
  ariaLabel: string;
  disabled?: boolean;
  style?: CSSProperties;
};

const PROVIDER_MARKS: Record<string, {text: string; tone: string}> = {
  ANTHROPIC: {text: 'A', tone: 'anthropic'},
  OPENAI: {text: 'O', tone: 'openai'},
  GEMINI: {text: 'G', tone: 'gemini'},
  GOOGLE: {text: 'G', tone: 'gemini'},
  OPENROUTER: {text: 'OR', tone: 'openrouter'},
  DEEPSEEK: {text: 'DS', tone: 'deepseek'},
  FIREWORKS: {text: 'FW', tone: 'fireworks'},
  SARVAM: {text: 'S', tone: 'sarvam'},
};

function providerMark(provider: string): {text: string; tone: string} {
  const key = provider.toUpperCase();
  return PROVIDER_MARKS[key] || {text: key.slice(0, 2) || '?', tone: 'other'};
}

export function ProviderLogoMark({provider}: {provider: string}) {
  const mark = providerMark(provider);
  return (
    <span
      className={`ide-provider-logo ide-provider-logo-${mark.tone}`}
      aria-hidden='true'
      title={providerLabel(provider)}
    >
      {mark.text}
    </span>
  );
}

export function ModelPicker({
  value,
  options,
  onChange,
  ariaLabel,
  disabled = false,
  style,
}: ModelPickerProps) {
  const id = useId();
  const boxRef = useRef<HTMLDivElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const activeIndexRef = useRef(0);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const selected = useMemo(
    () => options.find((o) => o.value === value) || options[0],
    [options, value],
  );
  const selectedIndex = useMemo(
    () =>
      Math.max(
        0,
        options.findIndex((o) => o.value === selected?.value),
      ),
    [options, selected?.value],
  );

  useEffect(() => {
    if (!open) return;
    function onDocDown(e: MouseEvent) {
      if (!boxRef.current?.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener('mousedown', onDocDown);
    return () => document.removeEventListener('mousedown', onDocDown);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    activeIndexRef.current = selectedIndex;
    setActiveIndex(selectedIndex);
    window.requestAnimationFrame(() => optionRefs.current[selectedIndex]?.focus());
  }, [open, selectedIndex]);

  function openAt(index: number) {
    if (disabled || options.length === 0) return;
    activeIndexRef.current = index;
    setActiveIndex(index);
    setOpen(true);
  }

  function moveActive(delta: number) {
    const count = options.length;
    if (count === 0) return;
    const next = (activeIndexRef.current + delta + count) % count;
    activeIndexRef.current = next;
    setActiveIndex(next);
    optionRefs.current[next]?.focus();
  }

  function selectActive() {
    const option = options[activeIndexRef.current];
    if (!option) return;
    onChange(option.value);
    setOpen(false);
    window.requestAnimationFrame(() => triggerRef.current?.focus());
  }

  return (
    <div className='ide-model-picker' ref={boxRef} style={style}>
      <button
        ref={triggerRef}
        type='button'
        className='ide-model-picker-trigger'
        aria-label={ariaLabel}
        aria-haspopup='listbox'
        aria-expanded={open}
        aria-controls={`${id}-listbox`}
        disabled={disabled}
        onClick={() => setOpen((v) => !v)}
        onKeyDown={(e) => {
          if (e.key === 'Escape') setOpen(false);
          if (e.key === 'ArrowDown') {
            e.preventDefault();
            openAt(open ? activeIndex : selectedIndex);
          }
          if (e.key === 'ArrowUp') {
            e.preventDefault();
            openAt(open ? activeIndex : selectedIndex);
          }
        }}
      >
        {selected && <ProviderLogoMark provider={selected.provider} />}
        <span className='ide-model-picker-selected'>
          <span>{selected?.label || 'Recommended'}</span>
          {selected?.helper && <small>{selected.helper}</small>}
        </span>
        <span className='ide-model-picker-chevron' aria-hidden='true'>
          ▾
        </span>
      </button>

      {open && !disabled && (
        <div
          className='ide-model-picker-menu'
          id={`${id}-listbox`}
          role='listbox'
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') {
              e.preventDefault();
              moveActive(1);
            } else if (e.key === 'ArrowUp') {
              e.preventDefault();
              moveActive(-1);
            } else if (e.key === 'Home') {
              e.preventDefault();
              activeIndexRef.current = 0;
              setActiveIndex(0);
              optionRefs.current[0]?.focus();
            } else if (e.key === 'End') {
              e.preventDefault();
              const next = Math.max(0, options.length - 1);
              activeIndexRef.current = next;
              setActiveIndex(next);
              optionRefs.current[next]?.focus();
            } else if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              selectActive();
            } else if (e.key === 'Escape') {
              e.preventDefault();
              setOpen(false);
              triggerRef.current?.focus();
            }
          }}
        >
          {options.map((option, idx) => {
            const active = idx === activeIndex;
            const selectedOption = option.value === value;
            return (
              <button
                key={`${option.provider}:${option.value || 'default'}`}
                ref={(node) => {
                  optionRefs.current[idx] = node;
                }}
                type='button'
                role='option'
                aria-selected={selectedOption}
                className={`ide-model-picker-option${active ? ' active' : ''}${
                  selectedOption ? ' selected' : ''
                }`}
                onFocus={() => {
                  activeIndexRef.current = idx;
                  setActiveIndex(idx);
                }}
                onClick={() => {
                  onChange(option.value);
                  setOpen(false);
                  triggerRef.current?.focus();
                }}
              >
                <ProviderLogoMark provider={option.provider} />
                <span className='ide-model-picker-option-text'>
                  <span>{option.label}</span>
                  <small>{option.helper || providerLabel(option.provider)}</small>
                </span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
