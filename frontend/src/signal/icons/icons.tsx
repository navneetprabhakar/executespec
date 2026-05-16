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
export function ArrowRight({size = 18, className}: IconProps) {
  return (
    <svg viewBox='0 0 24 24' width={size} height={size} className={className} {...stroke}>
      <path d='M5 12h14M12 5l7 7-7 7' />
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
export function Search({size = 18, className}: IconProps) {
  return (
    <svg viewBox='0 0 24 24' width={size} height={size} className={className} {...stroke}>
      <circle cx='11' cy='11' r='8' />
      <path d='m21 21-4.3-4.3' />
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
export function HomeIcon({size = 22, className}: IconProps) {
  return (
    <svg viewBox='0 0 24 24' width={size} height={size} className={className} {...stroke}>
      <path d='M3 11l9-8 9 8v9a2 2 0 01-2 2h-4v-7h-6v7H5a2 2 0 01-2-2z' />
    </svg>
  );
}
export function Check({size = 22, className}: IconProps) {
  return (
    <svg viewBox='0 0 24 24' width={size} height={size} className={className} {...stroke}>
      <path d='M9 11l3 3L22 4' />
      <path d='M21 12v7a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11' />
    </svg>
  );
}
export function Chat({size = 22, className}: IconProps) {
  return (
    <svg viewBox='0 0 24 24' width={size} height={size} className={className} {...stroke}>
      <path d='M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z' />
    </svg>
  );
}
export function More({size = 22, className}: IconProps) {
  return (
    <svg viewBox='0 0 24 24' width={size} height={size} className={className} {...stroke}>
      <circle cx='5' cy='12' r='1.5' />
      <circle cx='12' cy='12' r='1.5' />
      <circle cx='19' cy='12' r='1.5' />
    </svg>
  );
}
export function Moon({size = 14, className}: IconProps) {
  return (
    <svg viewBox='0 0 24 24' width={size} height={size} className={className} {...stroke}>
      <path d='M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z' />
    </svg>
  );
}
export function Sun({size = 14, className}: IconProps) {
  return (
    <svg viewBox='0 0 24 24' width={size} height={size} className={className} {...stroke}>
      <circle cx='12' cy='12' r='4' />
      <path d='M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41' />
    </svg>
  );
}
export function Share({size = 18, className}: IconProps) {
  return (
    <svg viewBox='0 0 24 24' width={size} height={size} className={className} {...stroke}>
      <circle cx='18' cy='5' r='3' />
      <circle cx='6' cy='12' r='3' />
      <circle cx='18' cy='19' r='3' />
      <line x1='8.6' y1='13.5' x2='15.4' y2='17.5' />
      <line x1='15.4' y1='6.5' x2='8.6' y2='10.5' />
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
