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
// Filled, VS-Code folder-yellow, with subtle stroke. Open vs closed differ in
// the lid geometry so the tree reads correctly at a glance.

const FOLDER = '#dcb67a';
const FOLDER_STROKE = '#c89b5a';

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
      <path
        d='M1.5 3.4a1 1 0 0 1 1-1h3.1l1.4 1.4h6.5a1 1 0 0 1 1 1V12a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1V3.4z'
        fill={FOLDER}
        fillOpacity='0.85'
        stroke={FOLDER_STROKE}
        strokeOpacity='0.9'
        strokeWidth='0.7'
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
      {/* back lid */}
      <path
        d='M1.5 3.4a1 1 0 0 1 1-1h3.1l1.4 1.4h6.5a1 1 0 0 1 1 1V6H3.6z'
        fill={FOLDER}
        fillOpacity='0.55'
        stroke={FOLDER_STROKE}
        strokeOpacity='0.85'
        strokeWidth='0.7'
      />
      {/* front opened tray, slightly skewed */}
      <path
        d='M2.3 6h12.4a.7.7 0 0 1 .68.86l-1 4.3a1 1 0 0 1-.97.84H2.5a1 1 0 0 1-1-1V6.6z'
        fill={FOLDER}
        fillOpacity='0.9'
        stroke={FOLDER_STROKE}
        strokeOpacity='0.9'
        strokeWidth='0.7'
      />
    </svg>
  );
}

// ─────────────────────────── per-language file icons ────────────────────────
// VS-Code-ish: a faint tinted page silhouette + a bold, language-coloured
// letter/glyph badge. `LangFile` is the shared shell so every language icon is
// visually consistent; only colour + glyph differ.

function LangFile({
  size = 16,
  className,
  style,
  color,
  glyph,
  glyphSize = 6.2,
  monoGlyph,
}: IconProps & {
  color: string;
  glyph: string;
  /** Font size of the glyph in the 16-unit viewBox. */
  glyphSize?: number;
  /** When true the glyph is drawn as paths (for symbols like {} <> # $). */
  monoGlyph?: boolean;
}) {
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
      {/* page silhouette tinted with the language colour */}
      <path
        d='M4 1.6a1 1 0 0 1 1-1h4.3L12.4 3.7V14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V1.6z'
        fill={color}
        fillOpacity='0.16'
        stroke={color}
        strokeOpacity='0.5'
        strokeWidth='0.7'
      />
      <path
        d='M9.3 0.6v2.2a1 1 0 0 0 1 1h2.1'
        fill='none'
        stroke={color}
        strokeOpacity='0.5'
        strokeWidth='0.7'
      />
      <text
        x='8'
        y='10.6'
        textAnchor='middle'
        fontFamily={
          monoGlyph
            ? 'ui-monospace, Menlo, monospace'
            : 'system-ui, -apple-system, Segoe UI, sans-serif'
        }
        fontSize={glyphSize}
        fontWeight='700'
        fill={color}
        letterSpacing='-0.3'
      >
        {glyph}
      </text>
    </svg>
  );
}

// Language palette (VS-Code-ish material-icon-theme tones).
const C = {
  ts: '#3178c6',
  js: '#cbcb41',
  json: '#cbcb41',
  java: '#e8553e',
  py: '#3776ab',
  pyAccent: '#ffd343',
  go: '#00add8',
  rs: '#dea584',
  rb: '#cc342d',
  php: '#8993be',
  sql: '#f29111',
  md: '#519aba',
  yml: '#cb171e',
  css: '#42a5f5',
  scss: '#cc6699',
  html: '#e44d26',
  sh: '#89e051',
  kt: '#a97bff',
  txt: '#9e9e9e',
  c: '#5f9ea0',
  cpp: '#6f9bd1',
  cs: '#68217a',
  swift: '#fa7343',
  dockerfile: '#0db7ed',
  vue: '#41b883',
  def: '#8a909a',
} as const;

export const TsIcon = (p: IconProps) => <LangFile {...p} color={C.ts} glyph='TS' glyphSize={5.4} />;
export const TsxIcon = (p: IconProps) => <LangFile {...p} color={C.ts} glyph='TSX' glyphSize={4.2} />;
export const JsIcon = (p: IconProps) => <LangFile {...p} color={C.js} glyph='JS' glyphSize={5.4} />;
export const JsxIcon = (p: IconProps) => (
  <LangFile {...p} color={C.js} glyph='JSX' glyphSize={4.2} />
);
export const JsonIcon = (p: IconProps) => (
  <LangFile {...p} color={C.json} glyph={'{ }'} glyphSize={5} monoGlyph />
);
export const JavaIcon = (p: IconProps) => (
  <LangFile {...p} color={C.java} glyph='Jv' glyphSize={5.4} />
);
export const KotlinIcon = (p: IconProps) => (
  <LangFile {...p} color={C.kt} glyph='Kt' glyphSize={5.4} />
);
export const PyIcon = (p: IconProps) => <LangFile {...p} color={C.py} glyph='Py' glyphSize={5.4} />;
export const GoIcon = (p: IconProps) => <LangFile {...p} color={C.go} glyph='Go' glyphSize={5.4} />;
export const RustIcon = (p: IconProps) => (
  <LangFile {...p} color={C.rs} glyph='Rs' glyphSize={5.4} />
);
export const RubyIcon = (p: IconProps) => (
  <LangFile {...p} color={C.rb} glyph='Rb' glyphSize={5.4} />
);
export const PhpIcon = (p: IconProps) => (
  <LangFile {...p} color={C.php} glyph='php' glyphSize={4.4} />
);
export const SqlIcon = (p: IconProps) => (
  <LangFile {...p} color={C.sql} glyph='SQL' glyphSize={4.2} />
);
export const MdIcon = (p: IconProps) => <LangFile {...p} color={C.md} glyph='M↓' glyphSize={5} />;
export const YamlIcon = (p: IconProps) => (
  <LangFile {...p} color={C.yml} glyph='yml' glyphSize={4.4} />
);
export const CssIcon = (p: IconProps) => (
  <LangFile {...p} color={C.css} glyph='#' glyphSize={6} monoGlyph />
);
export const ScssIcon = (p: IconProps) => (
  <LangFile {...p} color={C.scss} glyph='#' glyphSize={6} monoGlyph />
);
export const HtmlIcon = (p: IconProps) => (
  <LangFile {...p} color={C.html} glyph={'</>'} glyphSize={4.4} monoGlyph />
);
export const ShIcon = (p: IconProps) => (
  <LangFile {...p} color={C.sh} glyph='$_' glyphSize={5.2} monoGlyph />
);
export const TxtIcon = (p: IconProps) => (
  <LangFile {...p} color={C.txt} glyph='txt' glyphSize={4.4} />
);
export const CIcon = (p: IconProps) => <LangFile {...p} color={C.c} glyph='C' glyphSize={5.8} />;
export const CppIcon = (p: IconProps) => (
  <LangFile {...p} color={C.cpp} glyph='C+' glyphSize={5} />
);
export const CsIcon = (p: IconProps) => <LangFile {...p} color={C.cs} glyph='C#' glyphSize={5} />;
export const SwiftIcon = (p: IconProps) => (
  <LangFile {...p} color={C.swift} glyph='sw' glyphSize={5} />
);
export const VueIcon = (p: IconProps) => (
  <LangFile {...p} color={C.vue} glyph='V' glyphSize={5.8} />
);
export const DockerIcon = (p: IconProps) => (
  <LangFile {...p} color={C.dockerfile} glyph='dk' glyphSize={5} />
);
export const DefaultFileIcon = (p: IconProps) => (
  <LangFile {...p} color={C.def} glyph='·' glyphSize={7} monoGlyph />
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
  css: CssIcon,
  scss: ScssIcon,
  sass: ScssIcon,
  less: ScssIcon,
  html: HtmlIcon,
  htm: HtmlIcon,
  xml: HtmlIcon,
  svg: HtmlIcon,
  sh: ShIcon,
  bash: ShIcon,
  zsh: ShIcon,
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
};

// Filenames (no extension) that should still get a recognisable icon.
const NAME_MAP: Record<string, (p: IconProps) => React.ReactElement> = {
  dockerfile: DockerIcon,
  makefile: SettingsIcon,
  '.gitignore': SettingsIcon,
  '.env': SettingsIcon,
};

/**
 * Map a file path (or bare name) → its language icon component.
 * Returns the default page icon when nothing matches.
 */
export function fileIconFor(path: string): (p: IconProps) => React.ReactElement {
  const name = (path.split('/').pop() || path).toLowerCase();
  if (NAME_MAP[name]) return NAME_MAP[name];
  const dot = name.lastIndexOf('.');
  // Leading-dot dotfiles (e.g. `.eslintrc`) have no real extension.
  const ext = dot > 0 ? name.slice(dot + 1) : '';
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
