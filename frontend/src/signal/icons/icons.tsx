// Small icon set. Re-using lucide-style stroke icons inline so we don't add a dep.

interface IconProps {
  size?: number;
  className?: string;
}

const stroke = {
  stroke: 'currentColor',
  strokeWidth: 2,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  fill: 'none',
};

export function ArrowLeft({size = 18, className}: IconProps) {
  return (
    <svg viewBox='0 0 24 24' width={size} height={size} className={className} {...stroke}>
      <path d='M19 12H5M12 19l-7-7 7-7' />
    </svg>
  );
}
export function Chevron({size = 14, className}: IconProps) {
  return (
    <svg viewBox='0 0 24 24' width={size} height={size} className={className} {...stroke}>
      <path d='M9 18l6-6-6-6' />
    </svg>
  );
}
export function Bell({size = 18, className}: IconProps) {
  return (
    <svg viewBox='0 0 24 24' width={size} height={size} className={className} {...stroke}>
      <path d='M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M13.73 21a2 2 0 0 1-3.46 0' />
    </svg>
  );
}
export function Plus({size = 18, className}: IconProps) {
  return (
    <svg viewBox='0 0 24 24' width={size} height={size} className={className} {...stroke}>
      <path d='M12 5v14M5 12h14' />
    </svg>
  );
}
export function Settings({size = 18, className}: IconProps) {
  return (
    <svg viewBox='0 0 24 24' width={size} height={size} className={className} {...stroke}>
      <circle cx='12' cy='12' r='3' />
      <path d='M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 0 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-1.8-.3 1.6 1.6 0 0 0-1 1.5V21a2 2 0 0 1-4 0v-.1a1.6 1.6 0 0 0-1-1.5 1.6 1.6 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0 .3-1.8 1.6 1.6 0 0 0-1.5-1H3a2 2 0 0 1 0-4h.1A1.6 1.6 0 0 0 4.6 9a1.6 1.6 0 0 0-.3-1.8L4.2 7.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3H9a1.6 1.6 0 0 0 1-1.5V3a2 2 0 0 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8V9a1.6 1.6 0 0 0 1.5 1H21a2 2 0 0 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1z' />
    </svg>
  );
}
