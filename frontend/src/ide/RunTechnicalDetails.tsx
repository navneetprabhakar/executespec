import type {CSSProperties, ReactNode} from 'react';

export function RunTechnicalDetails({
  children,
  style,
}: {
  children: ReactNode;
  style?: CSSProperties;
}) {
  return (
    <details data-run-technical-details style={style}>
      <summary
        style={{
          cursor: 'pointer',
          fontSize: 11,
          color: 'var(--ide-fg-muted)',
        }}
      >
        Technical details
      </summary>
      <div
        style={{
          display: 'grid',
          gap: 6,
          marginTop: 8,
          fontSize: 11,
          color: 'var(--ide-fg-muted)',
          overflowWrap: 'anywhere',
        }}
      >
        {children}
      </div>
    </details>
  );
}
