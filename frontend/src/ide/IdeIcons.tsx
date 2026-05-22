/**
 * IdeIcons — a from-scratch inline-SVG icon set for the IDE chrome.
 *
 * The IDE deliberately avoids heavy icon-font / icon-component dependencies, so
 * every glyph here is hand-authored SVG. Two families live in this file:
 *
 *  1. Monochrome UI icons (activity bar / nav / toolbar). They stroke/fill with
 *     `currentColor` so they inherit the surrounding text colour and the
 *     activity-bar active/hover states from `ide.css` "just work".
 *  2. Per-language file icons — small, recognisable VS-Code-style coloured
 *     glyphs keyed by file extension, surfaced through the `fileIcon(path)`
 *     helper that maps a path → the right component.
 *
 * Every component takes the same `IconProps` so callers can size them inline.
 */
import type {CSSProperties} from 'react';

export type IconProps = {
  /** Square px size for both width + height. Defaults to 16. */
  size?: number;
  className?: string;
  style?: CSSProperties;
};

// Shared <svg> wrapper. `tone` decides whether the icon paints with the
// inherited text colour (monochrome UI icons) or keeps its own language colour.
function Svg({
  size = 16,
  className,
  style,
  children,
  viewBox = '0 0 24 24',
  title,
}: IconProps & {
  children: React.ReactNode;
  viewBox?: string;
  title?: string;
}) {
  return (
    <svg
      width={size}
      height={size}
      viewBox={viewBox}
      className={className}
      style={style}
      fill='none'
      stroke='currentColor'
      strokeWidth={1.6}
      strokeLinecap='round'
      strokeLinejoin='round'
      role={title ? 'img' : undefined}
      aria-hidden={title ? undefined : true}
      aria-label={title}
      focusable='false'
    >
      {children}
    </svg>
  );
}

// ─────────────────────────── activity-bar / nav icons ───────────────────────
// All monochrome — stroke = currentColor so they pick up active/hover colour.

/** Workspace / files — stacked document sheets (VS Code "Explorer"). */
export function WorkspaceIcon(p: IconProps) {
  return (
    <Svg {...p}>
      <path d='M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z' />
      <path d='M14 3v5h5' />
    </Svg>
  );
}

/** New spec — document with a plus badge. */
export function NewSpecIcon(p: IconProps) {
  return (
    <Svg {...p}>
      <path d='M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-8' />
      <path d='M14 3v5h5' />
      <path d='M18 2.5v5M15.5 5h5' />
    </Svg>
  );
}

/** All runs — play inside a circle (an executed/streaming run). */
export function RunsIcon(p: IconProps) {
  return (
    <Svg {...p}>
      <circle cx='12' cy='12' r='9' />
      <path d='M10 8.5l5 3.5-5 3.5z' fill='currentColor' stroke='none' />
    </Svg>
  );
}

/** Projects — folder-tree (a folder with branch lines). */
export function ProjectsIcon(p: IconProps) {
  return (
    <Svg {...p}>
      <path d='M3 6a1 1 0 0 1 1-1h4l2 2h6a1 1 0 0 1 1 1v3' />
      <path d='M3 6v11a1 1 0 0 0 1 1h5' />
      <path d='M13 14h3M16 14v6M16 17h4' />
      <circle cx='13' cy='14' r='0.6' fill='currentColor' stroke='none' />
      <circle cx='20' cy='17' r='0.6' fill='currentColor' stroke='none' />
    </Svg>
  );
}

/** Admin — shield. */
export function AdminIcon(p: IconProps) {
  return (
    <Svg {...p}>
      <path d='M12 3l7 3v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6z' />
      <path d='M9 12l2 2 4-4' />
    </Svg>
  );
}

/** Account — user bust. */
export function AccountIcon(p: IconProps) {
  return (
    <Svg {...p}>
      <circle cx='12' cy='8' r='4' />
      <path d='M4 20c0-3.5 3.5-6 8-6s8 2.5 8 6' />
    </Svg>
  );
}

/** Settings — gear. */
export function SettingsIcon(p: IconProps) {
  return (
    <Svg {...p}>
      <circle cx='12' cy='12' r='3' />
      <path d='M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M19.1 4.9L17 7M7 17l-2.1 2.1' />
    </Svg>
  );
}

/** Search — magnifier. */
export function SearchIcon(p: IconProps) {
  return (
    <Svg {...p}>
      <circle cx='11' cy='11' r='7' />
      <path d='M16 16l5 5' />
    </Svg>
  );
}

/** Sidebar toggle — split panel. */
export function SidebarIcon(p: IconProps) {
  return (
    <Svg {...p}>
      <rect x='3' y='4' width='18' height='16' rx='2' />
      <path d='M9 4v16' />
    </Svg>
  );
}

/** Plus — bare add affordance (used for the create-project header button). */
export function PlusIcon(p: IconProps) {
  return (
    <Svg {...p}>
      <path d='M12 5v14M5 12h14' />
    </Svg>
  );
}

/** Hamburger — mobile menu trigger. */
export function MenuIcon(p: IconProps) {
  return (
    <Svg {...p}>
      <path d='M4 7h16M4 12h16M4 17h16' />
    </Svg>
  );
}

/** Command palette — terminal-prompt chevron + line. */
export function CommandIcon(p: IconProps) {
  return (
    <Svg {...p}>
      <rect x='3' y='4' width='18' height='16' rx='2' />
      <path d='M7 9l3 3-3 3M13 15h4' />
    </Svg>
  );
}

// Theme glyphs — sun / moon / monitor (for the status-bar theme cycle).
export function SunIcon(p: IconProps) {
  return (
    <Svg {...p}>
      <circle cx='12' cy='12' r='4' />
      <path d='M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5L19 19M19 5l-1.5 1.5M6.5 17.5L5 19' />
    </Svg>
  );
}
export function MoonIcon(p: IconProps) {
  return (
    <Svg {...p}>
      <path d='M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z' />
    </Svg>
  );
}
export function MonitorIcon(p: IconProps) {
  return (
    <Svg {...p}>
      <rect x='3' y='4' width='18' height='12' rx='2' />
      <path d='M9 20h6M12 16v4' />
    </Svg>
  );
}

/** Swap / switch-UI — two opposed arrows. */
export function SwapIcon(p: IconProps) {
  return (
    <Svg {...p}>
      <path d='M7 7h12l-3-3M17 17H5l3 3' />
    </Svg>
  );
}

/** Branch — git branch (status bar). */
export function BranchIcon(p: IconProps) {
  return (
    <Svg {...p}>
      <circle cx='7' cy='5' r='2.2' />
      <circle cx='7' cy='19' r='2.2' />
      <circle cx='17' cy='8' r='2.2' />
      <path d='M7 7v10M17 10c0 4-4 3-7 4' />
    </Svg>
  );
}

// ─────────────────────────── folder icons ───────────────────────────────────
// VS-Code folder-yellow with a defined tab + body. Closed = solid folder;
// open = back panel + a forward-tilted front flap (the Material/VS Code "open
// folder" silhouette) so the tree's expanded state reads at a glance.

const FOLDER = '#dcb67a';
const FOLDER_STROKE = '#c19a5b';

export function FolderClosedIcon({size = 16, className, style}: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox='0 0 16 16'
      className={className}
      style={style}
      aria-hidden='true'
      focusable='false'
    >
      {/* tab */}
      <path d='M1.4 4.2a1 1 0 0 1 1-1h3.2a1 1 0 0 1 .7.3l1 1z' fill={FOLDER} />
      {/* body */}
      <path
        d='M1.4 4.6a1 1 0 0 1 1-1h11.2a1 1 0 0 1 1 1v6.8a1 1 0 0 1-1 1H2.4a1 1 0 0 1-1-1z'
        fill={FOLDER}
        stroke={FOLDER_STROKE}
        strokeWidth='0.6'
        strokeLinejoin='round'
      />
    </svg>
  );
}

export function FolderOpenIcon({size = 16, className, style}: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox='0 0 16 16'
      className={className}
      style={style}
      aria-hidden='true'
      focusable='false'
    >
      {/* back panel (slightly darker so the open flap reads in front) */}
      <path
        d='M1.4 4.2a1 1 0 0 1 1-1h3.2a1 1 0 0 1 .7.3l1 1h6.3a1 1 0 0 1 1 1v6.7a1 1 0 0 1-1 1H2.4a1 1 0 0 1-1-1z'
        fill={FOLDER}
        fillOpacity='0.7'
        stroke={FOLDER_STROKE}
        strokeWidth='0.6'
        strokeLinejoin='round'
      />
      {/* forward-tilted front flap */}
      <path
        d='M2.7 7.2h12.1a.85.85 0 0 1 .82 1.07l-1.05 4a1 1 0 0 1-.97.73H1.9a1 1 0 0 1-.97-1.26l1.07-4a1 1 0 0 1 .73-.74z'
        fill={FOLDER}
        stroke={FOLDER_STROKE}
        strokeWidth='0.6'
        strokeLinejoin='round'
      />
    </svg>
  );
}

// ─────────────────────────── per-language file icons ────────────────────────
// VS-Code / Material-Icon-Theme aesthetic: each glyph is a bold, saturated,
// brand-coloured mark on a transparent ground (no faint page silhouette). Most
// languages use a rounded "badge tile" with a monogram (the Material look);
// the handful with truly iconic marks (Python, Go, Rust, Docker, HTML5,
// Markdown, lockfiles, env, images, …) draw the real glyph as SVG paths.
//
// All file icons render in a 16-unit viewBox so they line up in the tree.

/**
 * Rounded badge tile + short monogram — the Material-Icon-Theme workhorse for
 * languages without a distinctive logo (TS, JS, Java, C#, …). The tile is the
 * brand colour; the monogram is white (or a custom `fg`) so it stays legible on
 * both light + dark themes.
 *
 * The monogram is hard-fitted to the tile with `textLength` +
 * `lengthAdjust='spacingAndGlyphs'`, so any 1–4 char label is squeezed to
 * exactly fit (no overflow past the tile edges, regardless of font metrics).
 * Vertical centring uses an explicit `dy` rather than `dominantBaseline`, which
 * renders inconsistently across SVG engines.
 */
function BadgeFile({
  size = 16,
  className,
  style,
  color,
  glyph,
  glyphSize = 6.6,
  fg = '#fff',
  mono,
}: IconProps & {
  color: string;
  glyph: string;
  /** Cap on the monogram font size in the 16-unit viewBox. */
  glyphSize?: number;
  /** Monogram colour (defaults to white). */
  fg?: string;
  /** Draw the monogram in a monospace face (for symbols like {} <> # $). */
  mono?: boolean;
}) {
  // Usable inner width of the tile (13-unit tile minus a little padding).
  const maxW = 11;
  // 1–2 char marks keep their natural size; 3+ chars get squeezed to fit.
  const textLength = glyph.length <= 2 ? undefined : maxW;
  return (
    <svg
      width={size}
      height={size}
      viewBox='0 0 16 16'
      className={className}
      style={style}
      aria-hidden='true'
      focusable='false'
    >
      <rect x='1.5' y='1.5' width='13' height='13' rx='2.6' fill={color} />
      <text
        x='8'
        y='8'
        dy='0.34em'
        textAnchor='middle'
        textLength={textLength}
        lengthAdjust='spacingAndGlyphs'
        fontFamily={
          mono
            ? 'ui-monospace, SFMono-Regular, Menlo, monospace'
            : 'system-ui, -apple-system, Segoe UI, sans-serif'
        }
        fontSize={glyphSize}
        fontWeight='700'
        fill={fg}
      >
        {glyph}
      </text>
    </svg>
  );
}

// Language palette (Material-Icon-Theme / Seti brand tones).
const C = {
  ts: '#3178c6',
  js: '#f5de19',
  jsFg: '#323330',
  json: '#f5de19',
  java: '#e8553e',
  py: '#3c78aa',
  pyAccent: '#ffd845',
  go: '#00acd7',
  rs: '#dea584',
  rsFg: '#3b3b3b',
  rb: '#cc342d',
  php: '#7377ad',
  sql: '#dd7d1a',
  md: '#519aba',
  yml: '#9c528b',
  toml: '#9c4221',
  xml: '#f1662a',
  css: '#2965f1',
  scss: '#cd6799',
  less: '#2a4d80',
  html: '#e44d26',
  sh: '#4caf50',
  shTerm: '#2b2b2b',
  kt: '#a97bff',
  txt: '#7f8c98',
  c: '#5c6bc0',
  cpp: '#0277bd',
  cs: '#9b4f96',
  swift: '#fa7343',
  dockerfile: '#2496ed',
  vue: '#41b883',
  git: '#f04e2d',
  lock: '#c0392b',
  env: '#fbcb37',
  img: '#26a69a',
  svg: '#ffb13b',
  gear: '#6d8086',
  def: '#8a929c',
} as const;

export const TsIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.ts} glyph='TS' glyphSize={6.4} />
);
export const TsxIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.ts} glyph='TSX' glyphSize={5.6} />
);
export const JsIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.js} fg={C.jsFg} glyph='JS' glyphSize={6.4} />
);
export const JsxIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.js} fg={C.jsFg} glyph='JSX' glyphSize={5.6} />
);
/** JSON — yellow braces on a transparent ground (Material look). */
export const JsonIcon = ({size = 16, className, style}: IconProps) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 16 16'
    className={className}
    style={style}
    aria-hidden='true'
    focusable='false'
    fill='none'
    stroke={C.json}
    strokeWidth='1.4'
    strokeLinecap='round'
    strokeLinejoin='round'
  >
    <path d='M6.2 2.5C4.4 2.5 4.6 5 4.6 6.2c0 1-.6 1.8-2.1 1.8 1.5 0 2.1.8 2.1 1.8 0 1.2-.2 3.7 1.6 3.7' />
    <path d='M9.8 2.5c1.8 0 1.6 2.5 1.6 3.7 0 1 .6 1.8 2.1 1.8-1.5 0-2.1.8-2.1 1.8 0 1.2.2 3.7-1.6 3.7' />
  </svg>
);
export const JavaIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.java} glyph='J' glyphSize={7.4} />
);
export const KotlinIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.kt} glyph='Kt' glyphSize={6} />
);
/** Python — the two interlocking blue/yellow snakes. */
export const PyIcon = ({size = 16, className, style}: IconProps) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 16 16'
    className={className}
    style={style}
    aria-hidden='true'
    focusable='false'
  >
    {/* top-left (blue) snake */}
    <path
      d='M7.9 1.4c-1.6 0-3 .3-3 1.9v1.5h3.1v.5H3.6c-1.6 0-2.2 1-2.2 2.8 0 1.7.6 2.8 2.2 2.8h1V9.5c0-1.5 1.2-2.7 2.7-2.7h3c1.3 0 2-.9 2-2.2V3.3c0-1.2-1-1.9-2.4-1.9zM6.2 2.4a.7.7 0 1 1 0 1.4.7.7 0 0 1 0-1.4z'
      fill={C.py}
    />
    {/* bottom-right (yellow) snake */}
    <path
      d='M8.1 14.6c1.6 0 3-.3 3-1.9v-1.5H8v-.5h4.4c1.6 0 2.2-1 2.2-2.8 0-1.7-.6-2.8-2.2-2.8h-1v1.4c0 1.5-1.2 2.7-2.7 2.7h-3c-1.3 0-2 .9-2 2.2v1.3c0 1.2 1 1.9 2.4 1.9zm1.7-1a.7.7 0 1 1 0-1.4.7.7 0 0 1 0 1.4z'
      fill={C.pyAccent}
    />
  </svg>
);
/** Go — the cyan "GO" wordmark on a rounded tile. */
export const GoIcon = (p: IconProps) => <BadgeFile {...p} color={C.go} glyph='GO' glyphSize={6} />;
/** Rust — the gear cog (orange/brown). */
export const RustIcon = ({size = 16, className, style}: IconProps) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 16 16'
    className={className}
    style={style}
    aria-hidden='true'
    focusable='false'
    fill={C.rs}
  >
    <path d='M8 1.6 9.1 3l1.7-.5.3 1.8 1.8.4-.6 1.7L15 9l-1.5 1 .3 1.8-1.8.1-.7 1.7-1.6-.9L8 14.4l-1.4-1.6-1.6.9-.7-1.7-1.8-.1.3-1.8L1 9l1.4-1.4-.6-1.7 1.8-.4.3-1.8L5.6 3 6.7 1.6 8 .9zM8 4.4A3.6 3.6 0 1 0 8 11.6 3.6 3.6 0 0 0 8 4.4zm0 1.6A2 2 0 1 1 8 10 2 2 0 0 1 8 6z' />
  </svg>
);
export const RubyIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.rb} glyph='Rb' glyphSize={6} />
);
export const PhpIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.php} glyph='php' glyphSize={5.6} />
);
/** SQL — a database cylinder. */
export const SqlIcon = ({size = 16, className, style}: IconProps) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 16 16'
    className={className}
    style={style}
    aria-hidden='true'
    focusable='false'
    fill='none'
    stroke={C.sql}
    strokeWidth='1.4'
    strokeLinecap='round'
    strokeLinejoin='round'
  >
    <ellipse cx='8' cy='4' rx='5' ry='2.2' fill={C.sql} fillOpacity='0.18' />
    <path d='M3 4v8c0 1.2 2.2 2.2 5 2.2s5-1 5-2.2V4' />
    <path d='M3 8c0 1.2 2.2 2.2 5 2.2s5-1 5-2.2' />
  </svg>
);
/** Markdown — the CommonMark "M⌄" mark in a rounded box. */
export const MdIcon = ({size = 16, className, style}: IconProps) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 16 16'
    className={className}
    style={style}
    aria-hidden='true'
    focusable='false'
  >
    <rect
      x='1'
      y='3.5'
      width='14'
      height='9'
      rx='1.6'
      fill='none'
      stroke={C.md}
      strokeWidth='1.2'
    />
    {/* "M" */}
    <path
      d='M2.7 10.6V5.4h1.25l1.35 1.85L6.65 5.4H7.9v5.2H6.65V7.55L5.3 9.35 3.95 7.55v3.05z'
      fill={C.md}
    />
    {/* downward arrow */}
    <path d='M10.9 5.4h1.25v2.75h1.45L11.5 10.9 9.4 8.15h1.5z' fill={C.md} />
  </svg>
);
export const YamlIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.yml} glyph='YML' glyphSize={5.6} />
);
export const TomlIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.toml} glyph='TOML' glyphSize={5.2} />
);
/** XML — angle-bracket tags. */
export const XmlIcon = ({size = 16, className, style}: IconProps) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 16 16'
    className={className}
    style={style}
    aria-hidden='true'
    focusable='false'
    fill='none'
    stroke={C.xml}
    strokeWidth='1.5'
    strokeLinecap='round'
    strokeLinejoin='round'
  >
    <path d='M5 4.5 1.8 8 5 11.5' />
    <path d='M11 4.5 14.2 8 11 11.5' />
    <path d='M9.2 3.5 6.8 12.5' />
  </svg>
);
/** CSS3 — the shield mark. */
export const CssIcon = ({size = 16, className, style}: IconProps) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 16 16'
    className={className}
    style={style}
    aria-hidden='true'
    focusable='false'
  >
    <path d='M2.6 1.5h10.8l-1 11-3.8 1.4-3.8-1.4-1-11z' fill={C.css} />
    <path
      d='M11.2 4.3H8v0H4.8l.12 1.4H8v0h3l-.3 3.3-2.7.75-2.7-.75-.17-1.9'
      fill='none'
      stroke='#fff'
      strokeWidth='0.9'
      strokeLinejoin='round'
    />
  </svg>
);
/** SCSS / Sass — the pink "S" mark. */
export const ScssIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.scss} glyph='S' glyphSize={7.6} />
);
/** Less — the dark-blue tile. */
export const LessIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.less} glyph='LESS' glyphSize={5} />
);
/** HTML5 — the orange shield with a "5". */
export const HtmlIcon = ({size = 16, className, style}: IconProps) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 16 16'
    className={className}
    style={style}
    aria-hidden='true'
    focusable='false'
  >
    <path d='M2.6 1.5h10.8l-1 11-3.8 1.4-3.8-1.4-1-11z' fill={C.html} />
    <path d='M8 2.6v10.4l3-1.1.8-9.3z' fill='#000' fillOpacity='0.12' />
    <path
      d='M5 4.4h6l-.15 1.4H6.4l.1 1.2h4.2l-.4 4-2.3.7-2.3-.7-.14-1.6H6.7l.07.8L8 11l1.3-.35.14-1.55H4.9z'
      fill='#fff'
    />
  </svg>
);
/** Shell — a terminal window with a prompt. */
export const ShIcon = ({size = 16, className, style}: IconProps) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 16 16'
    className={className}
    style={style}
    aria-hidden='true'
    focusable='false'
  >
    <rect x='1.5' y='2.5' width='13' height='11' rx='1.8' fill={C.shTerm} />
    <path
      d='M4 6l2.2 2L4 10M7.4 10.2h3.6'
      fill='none'
      stroke={C.sh}
      strokeWidth='1.3'
      strokeLinecap='round'
      strokeLinejoin='round'
    />
  </svg>
);
export const TxtIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.txt} glyph='TXT' glyphSize={5.6} />
);
export const CIcon = (p: IconProps) => <BadgeFile {...p} color={C.c} glyph='C' glyphSize={7.4} />;
export const CppIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.cpp} glyph='C++' glyphSize={5.6} />
);
export const CsIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.cs} glyph='C#' glyphSize={5.6} />
);
export const SwiftIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.swift} glyph='Sw' glyphSize={6} />
);
export const VueIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.vue} glyph='V' glyphSize={7.4} />
);
/** Docker — the whale carrying containers. */
export const DockerIcon = ({size = 16, className, style}: IconProps) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 16 16'
    className={className}
    style={style}
    aria-hidden='true'
    focusable='false'
    fill={C.dockerfile}
  >
    <path d='M3 6.4h1.7v1.6H3zM5 6.4h1.7v1.6H5zM7 6.4h1.7v1.6H7zM5 4.5h1.7V6H5zM7 4.5h1.7V6H7zM9 6.4h1.7v1.6H9z' />
    <path d='M14.6 7c-.3-.2-1-.3-1.5-.2-.06-.5-.35-1-.8-1.3l-.3-.2-.2.3c-.25.4-.32 1-.1 1.45.1.2.27.36.46.47-.2.1-.5.18-.95.18H1.3l-.04.4a4.3 4.3 0 0 0 .5 2.4c.55.95 1.6 1.43 3.1 1.43 3.3 0 5.7-1.5 6.85-4.3.45.01 1.4.01 1.9-.93l.12-.2-.18-.13z' />
  </svg>
);
/** Git config (.gitignore, .gitattributes) — the orange Git node/branch mark. */
export const GitIcon = ({size = 16, className, style}: IconProps) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 16 16'
    className={className}
    style={style}
    aria-hidden='true'
    focusable='false'
  >
    <circle cx='4.5' cy='4.5' r='1.7' fill={C.git} />
    <circle cx='4.5' cy='11.5' r='1.7' fill={C.git} />
    <circle cx='11.5' cy='6.2' r='1.7' fill={C.git} />
    <path
      d='M4.5 6.2v3.6M4.5 10c0-2 1-3.2 5-3.6'
      fill='none'
      stroke={C.git}
      strokeWidth='1.3'
      strokeLinecap='round'
    />
  </svg>
);
/** Lock files (package-lock.json, yarn.lock, …) — a padlock. */
export const LockIcon = ({size = 16, className, style}: IconProps) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 16 16'
    className={className}
    style={style}
    aria-hidden='true'
    focusable='false'
  >
    <rect x='3.2' y='7' width='9.6' height='7' rx='1.4' fill={C.lock} />
    <path
      d='M5 7V5.2a3 3 0 0 1 6 0V7'
      fill='none'
      stroke={C.lock}
      strokeWidth='1.5'
      strokeLinecap='round'
    />
    <circle cx='8' cy='10' r='1' fill='#fff' />
    <path d='M8 10.6v1.6' stroke='#fff' strokeWidth='1.1' strokeLinecap='round' />
  </svg>
);
/** .env files — a key (secrets). */
export const EnvIcon = ({size = 16, className, style}: IconProps) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 16 16'
    className={className}
    style={style}
    aria-hidden='true'
    focusable='false'
    fill={C.env}
  >
    <circle cx='5.4' cy='5.4' r='3' fill='none' stroke={C.env} strokeWidth='1.6' />
    <path
      d='M7.5 7.5 13 13M11 11l1.5-1.5M12.6 12.6 14 11.2'
      stroke={C.env}
      strokeWidth='1.6'
      strokeLinecap='round'
      fill='none'
    />
    <circle cx='5.4' cy='5.4' r='0.9' fill={C.env} />
  </svg>
);
/** Raster images (png/jpg/gif/webp) — a framed photo with a sun + hill. */
export const ImageIcon = ({size = 16, className, style}: IconProps) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 16 16'
    className={className}
    style={style}
    aria-hidden='true'
    focusable='false'
  >
    <rect
      x='1.5'
      y='2.5'
      width='13'
      height='11'
      rx='1.8'
      fill={C.img}
      fillOpacity='0.16'
      stroke={C.img}
      strokeWidth='1.2'
    />
    <circle cx='5.4' cy='5.8' r='1.2' fill={C.svg} />
    <path
      d='M2.6 12 6 8.4l2 2 2.4-2.6 3 3.2'
      fill='none'
      stroke={C.img}
      strokeWidth='1.3'
      strokeLinecap='round'
      strokeLinejoin='round'
    />
  </svg>
);
/** SVG files — the orange "SVG" wordmark tile. */
export const SvgIcon = (p: IconProps) => (
  <BadgeFile {...p} color={C.svg} fg='#3b3b3b' glyph='SVG' glyphSize={5.6} />
);
/** Config / settings files (makefile, .editorconfig, generic rc) — a gear. */
export const ConfigIcon = ({size = 16, className, style}: IconProps) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 24 24'
    className={className}
    style={style}
    aria-hidden='true'
    focusable='false'
    fill='none'
    stroke={C.gear}
    strokeWidth='1.8'
    strokeLinecap='round'
    strokeLinejoin='round'
  >
    <circle cx='12' cy='12' r='3' />
    <path d='M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M19.1 4.9L17 7M7 17l-2.1 2.1' />
  </svg>
);
export const DefaultFileIcon = ({size = 16, className, style}: IconProps) => (
  <svg
    width={size}
    height={size}
    viewBox='0 0 16 16'
    className={className}
    style={style}
    aria-hidden='true'
    focusable='false'
  >
    {/* generic document sheet with a folded corner */}
    <path
      d='M4 1.6a1 1 0 0 1 1-1h4.3L12.4 3.7V14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V1.6z'
      fill={C.def}
      fillOpacity='0.18'
      stroke={C.def}
      strokeWidth='0.9'
      strokeLinejoin='round'
    />
    <path
      d='M9.3 0.7v2.1a1 1 0 0 0 1 1h2.1'
      fill='none'
      stroke={C.def}
      strokeWidth='0.9'
      strokeLinejoin='round'
    />
  </svg>
);

// extension → component map. Aliases collapse onto the canonical icon.
const EXT_MAP: Record<string, (p: IconProps) => React.ReactElement> = {
  ts: TsIcon,
  mts: TsIcon,
  cts: TsIcon,
  tsx: TsxIcon,
  js: JsIcon,
  mjs: JsIcon,
  cjs: JsIcon,
  jsx: JsxIcon,
  json: JsonIcon,
  jsonc: JsonIcon,
  json5: JsonIcon,
  java: JavaIcon,
  kt: KotlinIcon,
  kts: KotlinIcon,
  py: PyIcon,
  pyi: PyIcon,
  go: GoIcon,
  rs: RustIcon,
  rb: RubyIcon,
  php: PhpIcon,
  sql: SqlIcon,
  md: MdIcon,
  mdx: MdIcon,
  markdown: MdIcon,
  yml: YamlIcon,
  yaml: YamlIcon,
  toml: TomlIcon,
  css: CssIcon,
  scss: ScssIcon,
  sass: ScssIcon,
  less: LessIcon,
  html: HtmlIcon,
  htm: HtmlIcon,
  xml: XmlIcon,
  svg: SvgIcon,
  sh: ShIcon,
  bash: ShIcon,
  zsh: ShIcon,
  fish: ShIcon,
  txt: TxtIcon,
  log: TxtIcon,
  text: TxtIcon,
  c: CIcon,
  h: CIcon,
  cpp: CppIcon,
  cc: CppIcon,
  cxx: CppIcon,
  hpp: CppIcon,
  cs: CsIcon,
  swift: SwiftIcon,
  vue: VueIcon,
  dockerfile: DockerIcon,
  // images
  png: ImageIcon,
  jpg: ImageIcon,
  jpeg: ImageIcon,
  gif: ImageIcon,
  webp: ImageIcon,
  bmp: ImageIcon,
  ico: ImageIcon,
  avif: ImageIcon,
  // dotfile "extensions" (when a leading-dot file does have a suffix)
  env: EnvIcon,
  gitignore: GitIcon,
  gitattributes: GitIcon,
  gitmodules: GitIcon,
};

// Exact filenames (incl. dotfiles + extension-less files) that get a
// recognisable icon. Checked before falling through to the extension map.
const NAME_MAP: Record<string, (p: IconProps) => React.ReactElement> = {
  dockerfile: DockerIcon,
  '.dockerignore': DockerIcon,
  makefile: ConfigIcon,
  'cmakelists.txt': ConfigIcon,
  '.editorconfig': ConfigIcon,
  '.eslintrc': ConfigIcon,
  '.prettierrc': ConfigIcon,
  '.babelrc': ConfigIcon,
  '.npmrc': ConfigIcon,
  '.gitignore': GitIcon,
  '.gitattributes': GitIcon,
  '.gitmodules': GitIcon,
  '.env': EnvIcon,
  '.env.local': EnvIcon,
  '.env.development': EnvIcon,
  '.env.production': EnvIcon,
  // lock files
  'package-lock.json': LockIcon,
  'yarn.lock': LockIcon,
  'pnpm-lock.yaml': LockIcon,
  'bun.lockb': LockIcon,
  'composer.lock': LockIcon,
  'cargo.lock': LockIcon,
  'poetry.lock': LockIcon,
  'gemfile.lock': LockIcon,
};

/**
 * Map a file path (or bare name) → its language icon component.
 * Returns the default document icon when nothing matches.
 */
export function fileIconFor(path: string): (p: IconProps) => React.ReactElement {
  const name = (path.split('/').pop() || path).toLowerCase();
  if (NAME_MAP[name]) return NAME_MAP[name];
  const dot = name.lastIndexOf('.');
  // Leading-dot dotfiles (e.g. `.eslintrc`) have no real extension; treat the
  // segment after the first dot as the extension so `.env` / `.gitignore` still
  // resolve (NAME_MAP catches the common ones first).
  const ext = name.startsWith('.') ? name.slice(1) : dot > 0 ? name.slice(dot + 1) : '';
  return EXT_MAP[ext] ?? DefaultFileIcon;
}

/**
 * Convenience renderer: `fileIcon(path, {size})` → a ready React element.
 * Callers that want the component itself can use `fileIconFor`.
 */
export function fileIcon(path: string, props?: IconProps): React.ReactElement {
  const Icon = fileIconFor(path);
  return <Icon {...props} />;
}

// ─────────────────────────── run-status dots ────────────────────────────────
// Small filled circles keyed to the IDE status palette. `kind` follows the
// IdeKit `statusKind` vocabulary (ok / warn / bad) plus a neutral `idle`.

const DOT_COLOR: Record<string, string> = {
  ok: 'var(--ide-green)',
  warn: 'var(--ide-yellow)',
  bad: 'var(--ide-red)',
  idle: 'var(--ide-fg-muted)',
};

export function StatusDot({
  kind,
  size = 8,
  className,
  style,
}: {
  kind: 'ok' | 'warn' | 'bad' | 'idle';
} & IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox='0 0 8 8'
      className={className}
      style={style}
      aria-hidden='true'
      focusable='false'
    >
      <circle cx='4' cy='4' r='3.2' fill={DOT_COLOR[kind] ?? DOT_COLOR.idle} />
    </svg>
  );
}
