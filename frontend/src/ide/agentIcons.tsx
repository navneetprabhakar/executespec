// Presentational SVG icons for the agent panels — extracted from agentPanels.tsx to keep
// that file under its committed line ceiling. Pure JSX, no logic, no dependencies.
//
// ONE stroke family: 24x24 viewBox, `currentColor`, strokeWidth 2, round caps and
// joins, `aria-hidden`. A literal text glyph (`×`, `↓`, `✓`) cannot do any of that —
// it ignores the theme's colour roles and carries whatever weight the font happens
// to ship — so every agent-surface glyph is drawn here instead.

export const Paperclip = () => (
  <svg
    width='17'
    height='17'
    viewBox='0 0 24 24'
    fill='none'
    stroke='currentColor'
    strokeWidth='2'
    strokeLinecap='round'
    strokeLinejoin='round'
    aria-hidden='true'
  >
    <path d='M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48' />
  </svg>
);

export const ArrowUp = () => (
  <svg
    width='16'
    height='16'
    viewBox='0 0 24 24'
    fill='none'
    stroke='currentColor'
    strokeWidth='2'
    strokeLinecap='round'
    strokeLinejoin='round'
    aria-hidden='true'
  >
    <path d='M12 19V5M5 12l7-7 7 7' />
  </svg>
);

export const ArrowDown = ({size = 14}: {size?: number}) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 24 24'
    fill='none'
    stroke='currentColor'
    strokeWidth='2'
    strokeLinecap='round'
    strokeLinejoin='round'
    aria-hidden='true'
  >
    <path d='M12 5v14M19 12l-7 7-7-7' />
  </svg>
);

export const CloseIcon = ({size = 14}: {size?: number}) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 24 24'
    fill='none'
    stroke='currentColor'
    strokeWidth='2'
    strokeLinecap='round'
    strokeLinejoin='round'
    aria-hidden='true'
  >
    <path d='M18 6L6 18M6 6l12 12' />
  </svg>
);

/** Tool-call succeeded. */
export const ToolOkIcon = ({size = 12}: {size?: number}) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 24 24'
    fill='none'
    stroke='currentColor'
    strokeWidth='2'
    strokeLinecap='round'
    strokeLinejoin='round'
    aria-hidden='true'
  >
    <path d='M20 6L9 17l-5-5' />
  </svg>
);

/** Tool-call failed. */
export const ToolErrIcon = ({size = 12}: {size?: number}) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 24 24'
    fill='none'
    stroke='currentColor'
    strokeWidth='2'
    strokeLinecap='round'
    strokeLinejoin='round'
    aria-hidden='true'
  >
    <circle cx='12' cy='12' r='9' />
    <path d='M15 9l-6 6M9 9l6 6' />
  </svg>
);

/** Tool-call dispatched, outcome not yet known. */
export const ToolRunningIcon = ({size = 12}: {size?: number}) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 24 24'
    fill='none'
    stroke='currentColor'
    strokeWidth='2'
    strokeLinecap='round'
    strokeLinejoin='round'
    aria-hidden='true'
  >
    <circle cx='12' cy='12' r='9' />
    <path d='M12 7v5l3 3' />
  </svg>
);

export const StopIcon = () => (
  <svg width='15' height='15' viewBox='0 0 24 24' aria-hidden='true'>
    <rect x='6' y='6' width='12' height='12' rx='2' fill='currentColor' />
  </svg>
);

export const FolderIcon = () => (
  <svg
    width='13'
    height='13'
    viewBox='0 0 24 24'
    fill='none'
    stroke='currentColor'
    strokeWidth='2'
    strokeLinecap='round'
    strokeLinejoin='round'
    aria-hidden='true'
  >
    <path d='M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z' />
  </svg>
);
