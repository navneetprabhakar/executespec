/**
 * Multichannel status indicator. Colour + ARIA label so colour-blind
 * users still get the signal. Used in lists, mission cards, and the
 * topbar agent-presence indicator.
 */
import type {CSSProperties} from 'react';

export type StatusTone = 'success' | 'progress' | 'warn' | 'fail' | 'queued';

const LABEL: Record<StatusTone, string> = {
  success: 'Ready',
  progress: 'Running',
  warn: 'Needs attention',
  fail: 'Failed',
  queued: 'Queued',
};

export default function StatusDot({
  tone,
  size = 8,
  className,
  style,
}: {
  tone: StatusTone;
  size?: number;
  className?: string;
  style?: CSSProperties;
}) {
  return (
    <span
      className={['v4-dot', `a-dot--${tone}`, className].filter(Boolean).join(' ')}
      role='img'
      aria-label={LABEL[tone]}
      style={{width: size, height: size, ...(style || {})}}
    />
  );
}
