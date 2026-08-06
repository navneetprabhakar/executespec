import type {CSSProperties, ReactNode} from 'react';

/**
 * The collapsed home for exact routes, token counts, action enums and commands.
 *
 * Type comes from the ramp (`--ide-t-caption`), never a px literal: the phone
 * ramp steps `--ide-t-*` down at <=600px and a hardcoded `fontSize: 12` is
 * invisible to it. `.ide-root details > summary::before` supplies the app's one
 * disclosure marker, so nothing here draws a second one.
 */
export function RunTechnicalDetails({
  children,
  style,
}: {
  children: ReactNode;
  style?: CSSProperties;
}) {
  return (
    <details data-run-technical-details style={style}>
      <summary className='gf-disclosure-summary'>Technical details</summary>
      <div className='gf-disclosure-body'>{children}</div>
    </details>
  );
}
