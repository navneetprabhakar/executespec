import {useMemo} from 'react';
import type {ReadymadeVariant, VariantResolutionResult} from '@/lib/api';

/**
 * R-335 — language + framework chip picker for Readymades detail
 * page.  Replaces the prior "Pick variant (or auto-resolve from
 * your stack)" copy + a single fused select with a clearer
 * two-axis picker:
 *
 * <ol>
 *   <li>Language chips (typescript, python, …) — pick first.</li>
 *   <li>Framework chips, filtered by chosen language.</li>
 * </ol>
 *
 * <p>Auto-resolution from the repo's detected stack happens silently
 * in the parent (no manual "Resolve" button).  The detected stack
 * shows here as a small hint chip above the language row, and the
 * matching variant is pre-selected when the resolution returns OK.
 *
 * <p>Single-variant solutions render a static "Variant: ts · express"
 * line and skip both chip rows entirely (the picker would have only
 * one option, which is noise).
 */
export interface StackPickerProps {
  variants: ReadymadeVariant[];
  selectedVariantId: string;
  onChange: (id: string) => void;
  resolution: VariantResolutionResult | null;
}

export default function StackPicker({
  variants,
  selectedVariantId,
  onChange,
  resolution,
}: StackPickerProps) {
  const selectedVariant = variants.find((v) => v.id === selectedVariantId);

  const languages = useMemo(() => {
    const set = new Set(variants.map((v) => v.language));
    return Array.from(set);
  }, [variants]);

  const selectedLanguage = selectedVariant?.language ?? '';

  const frameworksForLanguage = useMemo(() => {
    if (!selectedLanguage) return [];
    return variants.filter((v) => v.language === selectedLanguage);
  }, [variants, selectedLanguage]);

  // Single-variant: render a static info line, skip the chip rows.
  if (variants.length === 1) {
    const v = variants[0];
    return (
      <div style={{fontSize: 12.5, color: 'var(--v3-text)'}}>
        Variant:&nbsp;
        <code style={codeStyle}>
          {v.language} · {v.framework}
        </code>
      </div>
    );
  }

  return (
    <div style={{display: 'flex', flexDirection: 'column', gap: 10}}>
      {resolution && <DetectionHint resolution={resolution} />}

      <FieldLabel>Language</FieldLabel>
      <ChipRow>
        {languages.map((lang) => {
          const active = lang === selectedLanguage;
          return (
            <Chip
              key={lang}
              active={active}
              onClick={() => {
                // When the user changes language, jump to the first
                // variant under that language so the framework row
                // never lands in an undefined state.
                const first = variants.find((v) => v.language === lang);
                if (first) onChange(first.id);
              }}
            >
              {lang}
            </Chip>
          );
        })}
      </ChipRow>

      {selectedLanguage && frameworksForLanguage.length > 1 && (
        <>
          <FieldLabel>Framework</FieldLabel>
          <ChipRow>
            {frameworksForLanguage.map((v) => {
              const active = v.id === selectedVariantId;
              return (
                <Chip key={v.id} active={active} onClick={() => onChange(v.id)}>
                  {variantFrameworkLabel(v)}
                </Chip>
              );
            })}
          </ChipRow>
        </>
      )}
    </div>
  );
}

function DetectionHint({resolution}: {resolution: VariantResolutionResult}) {
  // Only surface the hint when we actually detected something.
  // REPO_NOT_LINKED + STACK_NOT_DETECTED are silent — the user just
  // picks manually.
  if (resolution.reason === 'REPO_NOT_LINKED') return null;
  if (resolution.reason === 'STACK_NOT_DETECTED') {
    return (
      <span style={{...hintLineStyle, color: 'var(--v3-text-subtle)'}}>
        Stack not detected — pick manually.
      </span>
    );
  }
  const detected = [resolution.detectedLanguage, resolution.detectedFramework]
    .filter(Boolean)
    .join(' · ');
  if (resolution.reason === 'OK') {
    return (
      <span style={{...hintLineStyle, color: '#4ADE80'}}>
        ✓ Detected from your repo: <code style={codeStyle}>{detected}</code>
      </span>
    );
  }
  // NO_VARIANT_FOR_STACK
  return (
    <span style={{...hintLineStyle, color: '#FBBF24'}}>
      Detected <code style={codeStyle}>{detected}</code>, but no matching variant — pick another
      below.
    </span>
  );
}

function variantFrameworkLabel(v: ReadymadeVariant): string {
  let optsLabel = '';
  if (v.options) {
    try {
      const parsed = JSON.parse(v.options);
      const pairs = Object.entries(parsed)
        .map(([k, val]) => `${k}=${val}`)
        .join(', ');
      if (pairs) optsLabel = ` (${pairs})`;
    } catch {
      /* ignore */
    }
  }
  return `${v.framework}${optsLabel}`;
}

function FieldLabel({children}: {children: React.ReactNode}) {
  return (
    <label
      style={{
        fontSize: 11,
        fontWeight: 600,
        color: 'var(--v3-text-subtle)',
        textTransform: 'uppercase',
        letterSpacing: 0.4,
      }}
    >
      {children}
    </label>
  );
}

function ChipRow({children}: {children: React.ReactNode}) {
  return <div style={{display: 'flex', flexWrap: 'wrap', gap: 6}}>{children}</div>;
}

function Chip({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type='button'
      onClick={onClick}
      style={{
        padding: '4px 10px',
        background: active ? 'var(--v3-brand)' : 'var(--v3-bg)',
        color: active ? 'var(--v3-text-on-brand)' : 'var(--v3-text)',
        border: `1px solid ${active ? 'var(--v3-brand)' : 'var(--v3-border)'}`,
        borderRadius: 999,
        fontSize: 12,
        cursor: 'pointer',
        fontFamily: 'inherit',
        fontWeight: active ? 600 : 500,
      }}
    >
      {children}
    </button>
  );
}

const codeStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 11.5,
  padding: '1px 5px',
  background: 'var(--v3-bg)',
  border: '1px solid var(--v3-border)',
  borderRadius: 3,
};

const hintLineStyle: React.CSSProperties = {
  fontSize: 11.5,
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
};
