/**
 * IdeIcons — the IDE chrome's icon set. Two families live in this file:
 *
 *  1. Monochrome UI icons (activity bar / nav / toolbar). Hand-authored SVG that
 *     strokes/fills with `currentColor` so they inherit the surrounding text
 *     colour and the activity-bar active/hover states from `ide.css` "just work".
 *  2. Per-language file icons + folder icons — the REAL multi-colour glyphs from
 *     the Material Icon Theme (the same icons millions of VS Code users see),
 *     surfaced through `fileIcon(path)` / `fileIconFor(path)` / `FolderOpenIcon`
 *     / `FolderClosedIcon`. NOT redrawn — the exact upstream SVG markup is
 *     inlined verbatim (see `MATERIAL_ICONS` below) so colours render faithfully.
 *
 * Material Icon Theme markup is MIT-licensed:
 *   The MIT License (MIT) — Copyright (c) 2025 Material Extensions.
 *   https://github.com/material-extensions/vscode-material-icon-theme
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction… The above copyright notice and this
 *   permission notice shall be included in all copies or substantial portions of
 *   the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
 * The `MATERIAL_ICONS` literal below was extracted (viewBox + inner markup) from
 * `material-icon-theme@5.35.0` `icons/*.svg`; only the ~30 file types the IDE
 * Explorer uses are inlined, to keep the bundle lean.
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

// ──────────────────── Material Icon Theme — file + folder glyphs ─────────────
// The REAL Material Icon Theme SVGs (MIT — see the file header for the full
// licence + attribution). Each entry is `{vb, inner}`: the upstream icon's own
// viewBox + its inner markup, verbatim. We render the inner markup into a sized
// <svg> via `dangerouslySetInnerHTML` so the multi-colour fills survive exactly
// as VS Code paints them (we deliberately do NOT force `currentColor`). The
// wrapper sets `fill='none'` so the few icons that ship a transparent
// bounding-box <path> (document, log) stay transparent; every other path
// carries an explicit `fill`, which overrides the inherited `none`.
//
// AUTO-GENERATED literal (extracted from material-icon-theme@5.35.0
// `icons/*.svg`) — do not hand-edit individual entries; re-extract instead.
type MaterialGlyph = {vb: string; inner: string};
const MATERIAL_ICONS: Record<string, MaterialGlyph> = {
  typescript: {
    vb: '0 0 16 16',
    inner:
      '<path fill="#0288d1" d="M2 2v12h12V2zm4 6h3v1H8v4H7V9H6zm5 0h2v1h-2v1h1a1.003 1.003 0 0 1 1 1v1a1.003 1.003 0 0 1-1 1h-2v-1h2v-1h-1a1.003 1.003 0 0 1-1-1V9a1.003 1.003 0 0 1 1-1"/>',
  },
  react: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#00bcd4" d="M16 12c7.444 0 12 2.59 12 4s-4.556 4-12 4-12-2.59-12-4 4.556-4 12-4m0-2c-7.732 0-14 2.686-14 6s6.268 6 14 6 14-2.686 14-6-6.268-6-14-6"/><path fill="#00bcd4" d="M16 14a2 2 0 1 0 2 2 2 2 0 0 0-2-2"/><path fill="#00bcd4" d="M10.458 5.507c2.017 0 5.937 3.177 9.006 8.493 3.722 6.447 3.757 11.687 2.536 12.392a.9.9 0 0 1-.457.1c-2.017 0-5.938-3.176-9.007-8.492C8.814 11.553 8.779 6.313 10 5.608a.9.9 0 0 1 .458-.1m-.001-2A2.87 2.87 0 0 0 9 3.875C6.13 5.532 6.938 12.304 10.804 19c3.284 5.69 7.72 9.493 10.74 9.493A2.87 2.87 0 0 0 23 28.124c2.87-1.656 2.062-8.428-1.804-15.124-3.284-5.69-7.72-9.493-10.74-9.493Z"/><path fill="#00bcd4" d="M21.543 5.507a.9.9 0 0 1 .457.1c1.221.706 1.186 5.946-2.536 12.393-3.07 5.316-6.99 8.493-9.007 8.493a.9.9 0 0 1-.457-.1C8.779 25.686 8.814 20.446 12.536 14c3.07-5.316 6.99-8.493 9.007-8.493m0-2c-3.02 0-7.455 3.804-10.74 9.493C6.939 19.696 6.13 26.468 9 28.124a2.87 2.87 0 0 0 1.457.369c3.02 0 7.455-3.804 10.74-9.493C25.061 12.304 25.87 5.532 23 3.876a2.87 2.87 0 0 0-1.457-.369"/>',
  },
  react_ts: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#0288d1" d="M16 12c7.444 0 12 2.59 12 4s-4.556 4-12 4-12-2.59-12-4 4.556-4 12-4m0-2c-7.732 0-14 2.686-14 6s6.268 6 14 6 14-2.686 14-6-6.268-6-14-6"/><path fill="#0288d1" d="M16 14a2 2 0 1 0 2 2 2 2 0 0 0-2-2"/><path fill="#0288d1" d="M10.458 5.507c2.017 0 5.937 3.177 9.006 8.493 3.722 6.447 3.757 11.687 2.536 12.392a.9.9 0 0 1-.457.1c-2.017 0-5.938-3.176-9.007-8.492C8.814 11.553 8.779 6.313 10 5.608a.9.9 0 0 1 .458-.1m-.001-2A2.87 2.87 0 0 0 9 3.875C6.13 5.532 6.938 12.304 10.804 19c3.284 5.69 7.72 9.493 10.74 9.493A2.87 2.87 0 0 0 23 28.124c2.87-1.656 2.062-8.428-1.804-15.124-3.284-5.69-7.72-9.493-10.74-9.493Z"/><path fill="#0288d1" d="M21.543 5.507a.9.9 0 0 1 .457.1c1.221.706 1.186 5.946-2.536 12.393-3.07 5.316-6.99 8.493-9.007 8.493a.9.9 0 0 1-.457-.1C8.779 25.686 8.814 20.446 12.536 14c3.07-5.316 6.99-8.493 9.007-8.493m0-2c-3.02 0-7.455 3.804-10.74 9.493C6.939 19.696 6.13 26.468 9 28.124a2.87 2.87 0 0 0 1.457.369c3.02 0 7.455-3.804 10.74-9.493C25.061 12.304 25.87 5.532 23 3.876a2.87 2.87 0 0 0-1.457-.369"/>',
  },
  javascript: {
    vb: '0 0 16 16',
    inner:
      '<path fill="#ffca28" d="M2 2v12h12V2zm6 6h1v4a1.003 1.003 0 0 1-1 1H7a1.003 1.003 0 0 1-1-1v-1h1v1h1zm3 0h2v1h-2v1h1a1.003 1.003 0 0 1 1 1v1a1.003 1.003 0 0 1-1 1h-2v-1h2v-1h-1a1.003 1.003 0 0 1-1-1V9a1.003 1.003 0 0 1 1-1"/>',
  },
  json: {
    vb: '0 -960 960 960',
    inner:
      '<path fill="#f9a825" d="M560-160v-80h120q17 0 28.5-11.5T720-280v-80q0-38 22-69t58-44v-14q-36-13-58-44t-22-69v-80q0-17-11.5-28.5T680-720H560v-80h120q50 0 85 35t35 85v80q0 17 11.5 28.5T840-560h40v160h-40q-17 0-28.5 11.5T800-360v80q0 50-35 85t-85 35zm-280 0q-50 0-85-35t-35-85v-80q0-17-11.5-28.5T120-400H80v-160h40q17 0 28.5-11.5T160-600v-80q0-50 35-85t85-35h120v80H280q-17 0-28.5 11.5T240-680v80q0 38-22 69t-58 44v14q36 13 58 44t22 69v80q0 17 11.5 28.5T280-240h120v80z"/>',
  },
  java: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#f44336" d="M4 26h24v2H4zM28 4H7a1 1 0 0 0-1 1v13a4 4 0 0 0 4 4h10a4 4 0 0 0 4-4v-4h4a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2m0 8h-4V6h4Z"/>',
  },
  kotlin: {
    vb: '0 0 24 24',
    inner:
      '<defs><linearGradient id="kt-a" x1="1.725" x2="22.185" y1="22.67" y2="1.982" gradientTransform="translate(1.306 1.129)scale(.89324)" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#7c4dff"/><stop offset=".5" stop-color="#d500f9"/><stop offset="1" stop-color="#ef5350"/></linearGradient></defs><path fill="url(#kt-a)" d="M2.975 2.976v18.048h18.05v-.03l-4.478-4.511-4.48-4.515 4.48-4.515 4.443-4.477z"/>',
  },
  python: {
    vb: '0 0 24 24',
    inner:
      '<path fill="#0288d1" d="M9.86 2A2.86 2.86 0 0 0 7 4.86v1.68h4.29c.39 0 .71.57.71.96H4.86A2.86 2.86 0 0 0 2 10.36v3.781a2.86 2.86 0 0 0 2.86 2.86h1.18v-2.68a2.85 2.85 0 0 1 2.85-2.86h5.25c1.58 0 2.86-1.271 2.86-2.851V4.86A2.86 2.86 0 0 0 14.14 2zm-.72 1.61c.4 0 .72.12.72.71s-.32.891-.72.891c-.39 0-.71-.3-.71-.89s.32-.711.71-.711"/><path fill="#fdd835" d="M17.959 7v2.68a2.85 2.85 0 0 1-2.85 2.859H9.86A2.85 2.85 0 0 0 7 15.389v3.75a2.86 2.86 0 0 0 2.86 2.86h4.28A2.86 2.86 0 0 0 17 19.14v-1.68h-4.291c-.39 0-.709-.57-.709-.96h7.14A2.86 2.86 0 0 0 22 13.64V9.86A2.86 2.86 0 0 0 19.14 7zM8.32 11.513l-.004.004.038-.004zm6.54 7.276c.39 0 .71.3.71.89a.71.71 0 0 1-.71.71c-.4 0-.72-.12-.72-.71s.32-.89.72-.89"/>',
  },
  go: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#00acc1" d="M2 12h4v2H2zm-2 4h6v2H0zm4 4h2v2H4zm16.954-5H14v3h3.239a4.42 4.42 0 0 1-3.531 2 2.65 2.65 0 0 1-2.053-.858 2.86 2.86 0 0 1-.628-2.28A4.515 4.515 0 0 1 15.292 13a2.73 2.73 0 0 1 1.749.584l2.962-1.185A5.6 5.6 0 0 0 15.292 10a7.526 7.526 0 0 0-7.243 6.5 5.614 5.614 0 0 0 5.659 6.5 7.526 7.526 0 0 0 7.243-6.5 6.4 6.4 0 0 0 .003-1.5"/><path fill="#00acc1" d="M26.292 10a7.526 7.526 0 0 0-7.243 6.5 5.614 5.614 0 0 0 5.659 6.5 7.526 7.526 0 0 0 7.243-6.5 5.614 5.614 0 0 0-5.659-6.5m2.681 6.137A4.515 4.515 0 0 1 24.708 20a2.65 2.65 0 0 1-2.053-.858 2.86 2.86 0 0 1-.628-2.28A4.515 4.515 0 0 1 26.292 13a2.65 2.65 0 0 1 2.053.858 2.86 2.86 0 0 1 .628 2.28Z"/>',
  },
  rust: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#ff7043" d="m30 12-4-2V6h-4l-2-4-4 2-4-2-2 4H6v4l-4 2 2 4-2 4 4 2v4h4l2 4 4-2 4 2 2-4h4v-4l4-2-2-4ZM6 16a9.9 9.9 0 0 1 .842-4H10v8H6.842A9.9 9.9 0 0 1 6 16m10 10a9.98 9.98 0 0 1-7.978-4H16v-2h-2v-2h4c.819.819.297 2.308 1.179 3.37a1.89 1.89 0 0 0 1.46.63h3.34A9.98 9.98 0 0 1 16 26m-2-12v-2h4a1 1 0 0 1 0 2Zm11.158 6H24a2.006 2.006 0 0 1-2-2 2 2 0 0 0-2-2 3 3 0 0 0 3-3q0-.08-.004-.161A3.115 3.115 0 0 0 19.83 10H8.022a9.986 9.986 0 0 1 17.136 10"/>',
  },
  ruby: {
    vb: '0 0 24 24',
    inner:
      '<path fill="#f44336" d="M18.041 3.177c2.24.382 2.879 1.919 2.843 3.527V6.67l-1.013 13.266-13.132.897h.008c-1.093-.044-3.518-.151-3.634-3.545l1.217-2.222 2.462 5.74 2.097-6.77-.045.009.018-.018 6.85 2.186L13.945 9.3l6.53-.409-5.144-4.212 2.71-1.51v.009M3.113 17.252v.017zM6.916 6.874c2.63-2.622 6.033-4.168 7.34-2.844 1.297 1.306-.072 4.523-2.702 7.135-2.666 2.613-6.015 4.248-7.322 2.933-1.306-1.324.036-4.612 2.675-7.224z"/>',
  },
  php: {
    vb: '0 0 24 24',
    inner:
      '<path fill="#1e88e5" d="M12 18.08c-6.63 0-12-2.72-12-6.08s5.37-6.08 12-6.08S24 8.64 24 12s-5.37 6.08-12 6.08m-5.19-7.95c.54 0 .91.1 1.09.31.18.2.22.56.13 1.03-.1.53-.29.87-.58 1.09q-.42.33-1.29.33h-.87l.53-2.76zm-3.5 5.55h1.44l.34-1.75h1.23c.54 0 .98-.06 1.33-.17.35-.12.67-.31.96-.58.24-.22.43-.46.58-.73.15-.26.26-.56.31-.88.16-.78.05-1.39-.33-1.82-.39-.44-.99-.65-1.82-.65H4.59zm7.25-8.33-1.28 6.58h1.42l.74-3.77h1.14c.36 0 .6.06.71.18s.13.34.07.66l-.57 2.93h1.45l.59-3.07c.13-.62.03-1.07-.27-1.36-.3-.27-.85-.4-1.65-.4h-1.27L12 7.35zM18 10.13c.55 0 .91.1 1.09.31.18.2.22.56.13 1.03-.1.53-.29.87-.57 1.09-.29.22-.72.33-1.3.33h-.85l.5-2.76zm-3.5 5.55h1.44l.34-1.75h1.22c.55 0 1-.06 1.35-.17.35-.12.65-.31.95-.58.24-.22.44-.46.58-.73.15-.26.26-.56.32-.88.15-.78.04-1.39-.34-1.82-.36-.44-.99-.65-1.82-.65h-2.75z"/>',
  },
  html: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#e65100" d="m4 4 2 22 10 2 10-2 2-22Zm19.72 7H11.28l.29 3h11.86l-.802 9.335L15.99 25l-6.635-1.646L8.93 19h3.02l.19 2 3.86.77 3.84-.77.29-4H8.84L8 8h16Z"/>',
  },
  css: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#7e57c2" d="M20 18h-2v-2h-2v2c0 .193 0 .703 1.254 1.033A3.345 3.345 0 0 1 20 22h2v2h2v-2c0-.388-.562-.851-1.254-1.034C20.356 20.34 20 18.84 20 18m-3.254 2.966C14.356 20.34 14 18.84 14 18h-2v-2h-2v8h2v-2h4v2h2v-2c0-.388-.562-.851-1.254-1.034"/><path fill="#7e57c2" d="M24 4H4v20a4 4 0 0 0 4 4h16.16A3.84 3.84 0 0 0 28 24.16V8a4 4 0 0 0-4-4m2 14h-2v-2h-2v2c0 .193 0 .703 1.254 1.033A3.345 3.345 0 0 1 26 22v2a2 2 0 0 1-2 2h-2a2 2 0 0 1-2-2 2 2 0 0 1-2 2h-2a2 2 0 0 1-2-2 2 2 0 0 1-2 2h-2a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2 2 2 0 0 1 2-2h2a2 2 0 0 1 2 2 2 2 0 0 1 2-2h2a2 2 0 0 1 2 2Z"/>',
  },
  sass: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#ec407a" d="M27.837 5.673a4.33 4.33 0 0 0-2.293-2.701c-2.362-1.261-6.11-1.298-9.548-.092a26.3 26.3 0 0 0-8.76 4.966c-2.752 2.542-3.438 4.925-3.189 6.194.523 2.668 3.274 4.539 5.485 6.042.418.284.822.559 1.175.816-1.429.76-4.261 2.444-5.088 4.248a3.88 3.88 0 0 0-.118 3.332A2.37 2.37 0 0 0 6.869 29.8a5.6 5.6 0 0 0 1.49.2 6.35 6.35 0 0 0 5.19-2.856 6.74 6.74 0 0 0 .864-5.382 7.3 7.3 0 0 1 2.044-.03 3.92 3.92 0 0 1 2.816 1.311 1.82 1.82 0 0 1 .423 1.262 1.55 1.55 0 0 1-.772 1.05c-.234.14-.586.355-.504.803.036.194.198.633.894.512a2.93 2.93 0 0 0 2.145-2.651 4 4 0 0 0-1.197-2.904 5.94 5.94 0 0 0-4.396-1.626 10.6 10.6 0 0 0-2.672.304 20 20 0 0 0-2.203-1.846c-1.712-1.3-3.33-2.529-3.235-4.26.125-2.263 2.468-4.532 6.964-6.744 4.016-1.976 7.254-2.037 8.944-1.438a2 2 0 0 1 1.204.883 2.77 2.77 0 0 1-.36 2.47 9.71 9.71 0 0 1-7.425 4.304 3.86 3.86 0 0 1-3.238-.757c-.278-.302-.593-.645-1.074-.383q-.565.31-.225 1.189a3.9 3.9 0 0 0 2.407 1.92 11.7 11.7 0 0 0 7.128-.671c3.527-1.35 6.681-5.202 5.756-8.787M11.895 24.475a4 4 0 0 1-.192.468 4.5 4.5 0 0 1-.753 1.081 2.83 2.83 0 0 1-2.533 1.107c-.056-.032-.078-.146-.085-.193a3.28 3.28 0 0 1 1.076-2.284 11.3 11.3 0 0 1 2.644-1.933 3.85 3.85 0 0 1-.157 1.754"/>',
  },
  less: {
    vb: '0 0 24 24',
    inner:
      '<path fill="#0277bd" d="M8 3a2 2 0 0 0-2 2v4a2 2 0 0 1-2 2H3v2h1a2 2 0 0 1 2 2v4a2 2 0 0 0 2 2h2v-2H8v-5a2 2 0 0 0-2-2 2 2 0 0 0 2-2V5h2V3m6 0a2 2 0 0 1 2 2v4a2 2 0 0 0 2 2h1v2h-1a2 2 0 0 0-2 2v4a2 2 0 0 1-2 2h-2v-2h2v-5a2 2 0 0 1 2-2 2 2 0 0 1-2-2V5h-2V3z"/>',
  },
  vue: {
    vb: '0 0 24 24',
    inner:
      '<path fill="#41b883" d="M1.791 3.851 12 21.471 22.209 3.936V3.85H18.24l-6.18 10.616L5.906 3.851z"/><path fill="#35495e" d="m5.907 3.851 6.152 10.617L18.24 3.851h-3.723L12.084 8.03 9.66 3.85z"/>',
  },
  svelte: {
    vb: '0 0 300 300',
    inner:
      '<path fill="#ff5722" d="M175.94 24.328c-13.037.252-26.009 3.872-37.471 11.174L79.912 72.818a67.13 67.13 0 0 0-30.355 44.906 70.8 70.8 0 0 0 6.959 45.445 67.2 67.2 0 0 0-10.035 25.102 71.54 71.54 0 0 0 12.236 54.156c23.351 33.41 69.468 43.311 102.81 22.07l58.559-37.158a67.36 67.36 0 0 0 30.355-44.906 70.77 70.77 0 0 0-6.982-45.422 67.65 67.65 0 0 0 10.059-25.102 71.63 71.63 0 0 0-12.236-54.156v-.18c-15.324-21.925-40.453-33.727-65.342-33.246zm5.137 28.68a46.5 46.5 0 0 1 36.09 19.969 42.98 42.98 0 0 1 7.365 32.557 45 45 0 0 1-1.393 5.455l-1.123 3.37-2.986-2.247a75.9 75.9 0 0 0-22.902-11.45l-2.244-.651.201-2.246a13.16 13.16 0 0 0-2.379-8.711 13.99 13.99 0 0 0-14.953-5.412 12.8 12.8 0 0 0-3.594 1.572l-58.578 37.25a12.24 12.24 0 0 0-5.502 8.15 13.1 13.1 0 0 0 2.246 9.834 14.03 14.03 0 0 0 14.93 5.569 13.5 13.5 0 0 0 3.594-1.573l22.453-14.234a41.8 41.8 0 0 1 11.898-5.232 46.48 46.48 0 0 1 49.914 18.502 43.02 43.02 0 0 1 7.363 32.557 40.42 40.42 0 0 1-18.254 27.078l-58.58 37.316a43 43 0 0 1-11.898 5.23A46.545 46.545 0 0 1 82.81 227.14a42.98 42.98 0 0 1-7.341-32.557 38 38 0 0 1 1.39-5.41l1.102-3.37 3.008 2.246a75.9 75.9 0 0 0 22.836 11.361l2.244.65-.201 2.247a13.25 13.25 0 0 0 2.447 8.644 14.03 14.03 0 0 0 15.043 5.569 13.1 13.1 0 0 0 3.592-1.573l58.467-37.316a12.17 12.17 0 0 0 5.502-8.173 12.96 12.96 0 0 0-2.246-9.811 14.03 14.03 0 0 0-15.043-5.568 12.8 12.8 0 0 0-3.592 1.57l-22.453 14.258a42.9 42.9 0 0 1-11.877 5.209 46.52 46.52 0 0 1-49.846-18.5 43.02 43.02 0 0 1-7.297-32.557A40.42 40.42 0 0 1 96.798 96.98l58.646-37.316a42.8 42.8 0 0 1 11.811-5.21 46.5 46.5 0 0 1 13.822-1.444z"/>',
  },
  markdown: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#42a5f5" d="m14 10-4 3.5L6 10H4v12h4v-6l2 2 2-2v6h4V10zm12 6v-6h-4v6h-4l6 8 6-8z"/>',
  },
  yaml: {
    vb: '0 0 24 24',
    inner:
      '<path fill="#ff5252" d="M13 9h5.5L13 3.5zM6 2h8l6 6v12c0 1.1-.9 2-2 2H6c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2m12 16v-2H9v2zm-4-4v-2H6v2z"/>',
  },
  toml: {
    vb: '0 0 16 16',
    inner:
      '<path fill="#cfd8dc" d="M4 6V4h8v2H9v7H7V6z"/><path fill="#ef5350" d="M4 1v1H2v12h2v1H1V1zm8 0v1h2v12h-2v1h3V1z"/>',
  },
  xml: {
    vb: '0 0 24 24',
    inner:
      '<path fill="#8bc34a" d="M13 9h5.5L13 3.5zM6 2h8l6 6v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4c0-1.11.89-2 2-2m.12 13.5 3.74 3.74 1.42-1.41-2.33-2.33 2.33-2.33-1.42-1.41zm11.16 0-3.74-3.74-1.42 1.41 2.33 2.33-2.33 2.33 1.42 1.41z"/>',
  },
  console: {
    vb: '0 0 16 16',
    inner:
      '<path fill="#ff7043" d="M2 2a1 1 0 0 0-1 1v10c0 .554.446 1 1 1h12c.554 0 1-.446 1-1V3a1 1 0 0 0-1-1zm0 3h12v8H2zm1 2 2 2-2 2 1 1 3-3-3-3zm5 3.5V12h5v-1.5z"/>',
  },
  database: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#ffca28" d="M16 24c-5.525 0-10-.9-10-2v4c0 1.1 4.475 2 10 2s10-.9 10-2v-4c0 1.1-4.475 2-10 2m0-8c-5.525 0-10-.9-10-2v4c0 1.1 4.475 2 10 2s10-.9 10-2v-4c0 1.1-4.475 2-10 2m0-12C10.477 4 6 4.895 6 6v4c0 1.1 4.475 2 10 2s10-.9 10-2V6c0-1.105-4.477-2-10-2"/>',
  },
  c: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#0288d1" d="M19.563 22A5.57 5.57 0 0 1 14 16.437v-2.873A5.57 5.57 0 0 1 19.563 8H24V2h-4.437A11.563 11.563 0 0 0 8 13.563v2.873A11.564 11.564 0 0 0 19.563 28H24v-6Z"/>',
  },
  h: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#0288d1" d="M18.5 11a5.49 5.49 0 0 0-4.5 2.344V4H8v24h6V17a2 2 0 0 1 4 0v11h6V16.5a5.5 5.5 0 0 0-5.5-5.5"/>',
  },
  cpp: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#0288d1" d="M28 14v-4h-2v4h-6v-4h-2v4h-4v2h4v4h2v-4h6v4h2v-4h4v-2z"/><path fill="#0288d1" d="M13.563 22A5.57 5.57 0 0 1 8 16.437v-2.873A5.57 5.57 0 0 1 13.563 8H18V2h-4.437A11.563 11.563 0 0 0 2 13.563v2.873A11.564 11.564 0 0 0 13.563 28H18v-6Z"/>',
  },
  csharp: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#0288d1" d="M30 14v-2h-2V8h-2v4h-2V8h-2v4h-2v2h2v2h-2v2h2v4h2v-4h2v4h2v-4h2v-2h-2v-2Zm-4 2h-2v-2h2Zm-12.437 6A5.57 5.57 0 0 1 8 16.437v-2.873A5.57 5.57 0 0 1 13.563 8H18V2h-4.437A11.563 11.563 0 0 0 2 13.563v2.873A11.564 11.564 0 0 0 13.563 28H18v-6Z"/>',
  },
  swift: {
    vb: '0 0 24 24',
    inner:
      '<path fill="#ff6e40" d="M17.087 19.721c-2.36 1.36-5.59 1.5-8.86.1a13.8 13.8 0 0 1-6.23-5.32c.67.55 1.46 1 2.3 1.4 3.37 1.57 6.73 1.46 9.1 0-3.37-2.59-6.24-5.96-8.37-8.71-.45-.45-.78-1.01-1.12-1.51 8.28 6.05 7.92 7.59 2.41-1.01 4.89 4.94 9.43 7.74 9.43 7.74.16.09.25.16.36.22.1-.25.19-.51.26-.78.79-2.85-.11-6.12-2.08-8.81 4.55 2.75 7.25 7.91 6.12 12.24-.03.11-.06.22-.05.39 2.24 2.83 1.64 5.78 1.35 5.22-1.21-2.39-3.48-1.65-4.62-1.17"/>',
  },
  docker: {
    vb: '0 0 24 24',
    inner:
      '<path fill="#0288d1" d="M21.81 10.25c-.06-.04-.56-.43-1.64-.43-.28 0-.56.03-.84.08-.21-1.4-1.38-2.11-1.43-2.14l-.29-.17-.18.27c-.24.36-.43.77-.51 1.19-.2.8-.08 1.56.33 2.21-.49.28-1.29.35-1.46.35H2.62c-.34 0-.62.28-.62.63 0 1.15.18 2.3.58 3.38.45 1.19 1.13 2.07 2 2.61.98.6 2.59.94 4.42.94.79 0 1.61-.07 2.42-.22 1.12-.2 2.2-.59 3.19-1.16A8.3 8.3 0 0 0 16.78 16c1.05-1.17 1.67-2.5 2.12-3.65h.19c1.14 0 1.85-.46 2.24-.85.26-.24.45-.53.59-.87l.08-.24zm-17.96.99h1.76c.08 0 .16-.07.16-.16V9.5c0-.08-.07-.16-.16-.16H3.85c-.09 0-.16.07-.16.16v1.58c.01.09.07.16.16.16m2.43 0h1.76c.08 0 .16-.07.16-.16V9.5c0-.08-.07-.16-.16-.16H6.28c-.09 0-.16.07-.16.16v1.58c.01.09.07.16.16.16m2.47 0h1.75c.1 0 .17-.07.17-.16V9.5c0-.08-.06-.16-.17-.16H8.75c-.08 0-.15.07-.15.16v1.58c0 .09.06.16.15.16m2.44 0h1.77c.08 0 .15-.07.15-.16V9.5c0-.08-.06-.16-.15-.16h-1.77c-.08 0-.15.07-.15.16v1.58c0 .09.07.16.15.16M6.28 9h1.76c.08 0 .16-.09.16-.18V7.25c0-.09-.07-.16-.16-.16H6.28c-.09 0-.16.06-.16.16v1.57c.01.09.07.18.16.18m2.47 0h1.75c.1 0 .17-.09.17-.18V7.25c0-.09-.06-.16-.17-.16H8.75c-.08 0-.15.06-.15.16v1.57c0 .09.06.18.15.18m2.44 0h1.77c.08 0 .15-.09.15-.18V7.25c0-.09-.07-.16-.15-.16h-1.77c-.08 0-.15.06-.15.16v1.57c0 .09.07.18.15.18m0-2.28h1.77c.08 0 .15-.07.15-.16V5c0-.1-.07-.17-.15-.17h-1.77c-.08 0-.15.06-.15.17v1.56c0 .08.07.16.15.16m2.46 4.52h1.76c.09 0 .16-.07.16-.16V9.5c0-.08-.07-.16-.16-.16h-1.76c-.08 0-.15.07-.15.16v1.58c0 .09.07.16.15.16"/>',
  },
  git: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#e64a19" d="M13.172 2.828 11.78 4.22l1.91 1.91 2 2A2.986 2.986 0 0 1 20 10.81a3.25 3.25 0 0 1-.31 1.31l2.06 2a2.68 2.68 0 0 1 3.37.57 2.86 2.86 0 0 1 .88 2.117 3.02 3.02 0 0 1-.856 2.109A2.9 2.9 0 0 1 23 19.81a2.93 2.93 0 0 1-2.13-.87 2.694 2.694 0 0 1-.56-3.38l-2-2.06a3 3 0 0 1-.31.12V20a3 3 0 0 1 1.44 1.09 2.92 2.92 0 0 1 .56 1.72 2.88 2.88 0 0 1-.878 2.128 2.98 2.98 0 0 1-2.048.871 2.981 2.981 0 0 1-2.514-4.719A3 3 0 0 1 16 20v-6.38a2.96 2.96 0 0 1-1.44-1.09 2.9 2.9 0 0 1-.56-1.72 2.9 2.9 0 0 1 .31-1.31l-3.9-3.9-7.579 7.572a4 4 0 0 0-.001 5.658l10.342 10.342a4 4 0 0 0 5.656 0l10.344-10.344a4 4 0 0 0 0-5.656L18.828 2.828a4 4 0 0 0-5.656 0"/>',
  },
  lock: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#ffd54f" d="M25 12h-3V8a6 6 0 0 0-12 0v4H7a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h18a1 1 0 0 0 1-1V13a1 1 0 0 0-1-1M14 8a2 2 0 0 1 4 0v4h-4Zm2 17a4 4 0 1 1 4-4 4 4 0 0 1-4 4"/>',
  },
  nodejs: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#8bc34a" d="M16 20.003v2h4a2 2 0 0 0 2-2v-2a2 2 0 0 0-2-2h-2v-2h4v-2h-4a2 2 0 0 0-2 2v2a2 2 0 0 0 2 2h2v2Z"/><path fill="#8bc34a" d="m16 3.003-12 7v14l4 2h6v-13.5a.5.5 0 0 0-.5-.5h-1a.5.5 0 0 0-.5.5v11.5H8l-2-1.034V11.15l10-5.833 10 5.833v11.703l-10 5.833-1.745-1.022L13 29.253l3 1.75 12-7v-14Z"/>',
  },
  yarn: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#0288d1" d="M27.575 23.967a9.9 9.9 0 0 0-3.751 1.726 22.6 22.6 0 0 1-5.537 2.504 1.55 1.55 0 0 1-.931.52 59 59 0 0 1-6.11.548c-1.102.008-1.777-.282-1.965-.735a1.49 1.49 0 0 1 .82-1.965 3.6 3.6 0 0 1-.486-.359c-.163-.162-.334-.487-.385-.367-.213.52-.324 1.794-.897 2.366-.786.795-2.273.53-3.153.069-.965-.513.069-1.718.069-1.718a.69.69 0 0 1-.94-.324 4.6 4.6 0 0 1-.632-2.794 5.2 5.2 0 0 1 1.674-2.76 8.84 8.84 0 0 1 .624-4.17 9.9 9.9 0 0 1 3-3.469S7.136 11.015 7.82 9.177c.444-1.196.623-1.187.769-1.239a3.44 3.44 0 0 0 1.375-.811 4.99 4.99 0 0 1 4.178-1.607s1.094-3.357 2.12-2.7a17.4 17.4 0 0 1 1.452 2.735s1.213-.71 1.35-.445a10.74 10.74 0 0 1 .495 5.81 13.3 13.3 0 0 1-2.46 5.127c-.129.214 1.47.889 2.477 3.683.932 2.554.103 4.699.248 4.938.026.043.034.06.034.06s1.068.085 3.213-1.24a8.05 8.05 0 0 1 4.05-1.52 1.026 1.026 0 0 1 .453 2Z"/>',
  },
  pnpm: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#e0e0e0" d="M2 22h8v8H2zm10 0h8v8h-8zm10 0h8v8h-8zM12 12h8v8h-8z"/><path fill="#ffb300" d="M2 2h8v8H2zm10 0h8v8h-8zm10 0h8v8h-8zm0 10h8v8h-8z"/>',
  },
  bun: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#fff8e1" d="M30 17.045a9.8 9.8 0 0 0-.32-2.306l-.004.034a11.2 11.2 0 0 0-5.762-6.786c-3.495-1.89-5.243-3.326-6.8-3.811h.003c-1.95-.695-3.949.82-5.825 1.927-4.52 2.481-9.573 5.45-9.28 11.417.008-.029.017-.052.026-.08a9.97 9.97 0 0 0 3.934 7.257l-.01-.006C13.747 31.473 30.05 27.292 30 17.045"/><path fill="#37474f" d="M19.855 20.236A.8.8 0 0 0 19.26 20h-6.514a.8.8 0 0 0-.596.236.51.51 0 0 0-.137.463 4.37 4.37 0 0 0 1.641 2.339 4.2 4.2 0 0 0 2.349.926 4.2 4.2 0 0 0 2.343-.926 4.37 4.37 0 0 0 1.642-2.339.5.5 0 0 0-.132-.463Z"/><ellipse cx="22.5" cy="18.5" fill="#f8bbd0" rx="2.5" ry="1.5"/><ellipse cx="9.5" cy="18.5" fill="#f8bbd0" rx="2.5" ry="1.5"/><circle cx="10" cy="16" r="2" fill="#37474f"/><circle cx="22" cy="16" r="2" fill="#37474f"/><path fill="#455a64" d="M9.996 18A2 2 0 1 0 8 15.996V16a2 2 0 0 0 1.996 2"/><circle cx="9" cy="15" r="1" fill="#fafafa"/><circle cx="21" cy="15" r="1" fill="#fafafa"/>',
  },
  poetry: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#3f51b5" d="M20.137 17.834A18.52 18.52 0 0 1 6 24l5 6a25.1 25.1 0 0 0 13-8Z"/><path fill="#1976d2" d="M6 2v22a18.52 18.52 0 0 0 14.137-6.166Z"/><path fill="#29b6f6" d="m6 2 14.137 15.834A23.7 23.7 0 0 0 26 2Z"/>',
  },
  tune: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#fbc02d" d="M12 10h10v2H12z"/><path fill="#fbc02d" d="M16 4h2v8h-2zm4 18h10v2H20zm4 2h2v4h-2zm0-20h2v14h-2zM2 18h10v2H2z"/><path fill="#fbc02d" d="M6 18h2v10H6zM6 4h2v10H6zm10 12h2v12h-2z"/>',
  },
  image: {
    vb: '0 0 16 16',
    inner:
      '<path fill="#26a69a" d="M8.5 6h4l-4-4zM3.875 1H9.5l4 4v8.6c0 .773-.616 1.4-1.375 1.4h-8.25c-.76 0-1.375-.627-1.375-1.4V2.4c0-.777.612-1.4 1.375-1.4M4 13.6h8V8l-2.625 2.8L8 9.4zm1.25-7.7c-.76 0-1.375.627-1.375 1.4s.616 1.4 1.375 1.4c.76 0 1.375-.627 1.375-1.4S6.009 5.9 5.25 5.9"/>',
  },
  svg: {
    vb: '0 0 32 32',
    inner:
      '<path fill="#ffb300" d="M29.168 14.03a2.7 2.7 0 0 0-1.968-.83 2.51 2.51 0 0 0-1.929.8h-4.443l3.078-3.078a2.835 2.835 0 0 0 2.857-2.842 2.6 2.6 0 0 0-.831-1.969 2.82 2.82 0 0 0-2.014-.788 2.67 2.67 0 0 0-1.968.788 2.36 2.36 0 0 0-.812 1.922L18 11.17V6.726a2.51 2.51 0 0 0 .8-1.929 2.7 2.7 0 0 0-.832-1.968 2.745 2.745 0 0 0-3.936 0 2.7 2.7 0 0 0-.832 1.968 2.51 2.51 0 0 0 .8 1.93v4.443l-3.138-3.138a2.36 2.36 0 0 0-.812-1.922 2.66 2.66 0 0 0-1.968-.788 2.83 2.83 0 0 0-2.014.788 2.6 2.6 0 0 0-.831 1.969 2.74 2.74 0 0 0 .831 2.013 2.8 2.8 0 0 0 2.026.829l3.078 3.078H6.729a2.51 2.51 0 0 0-1.929-.8 2.7 2.7 0 0 0-1.968.831 2.745 2.745 0 0 0 0 3.937 2.7 2.7 0 0 0 1.968.832 2.51 2.51 0 0 0 1.929-.8h4.443l-3.078 3.077a2.835 2.835 0 0 0-2.857 2.842 2.6 2.6 0 0 0 .831 1.969 2.82 2.82 0 0 0 2.014.788 2.67 2.67 0 0 0 1.968-.788 2.36 2.36 0 0 0 .812-1.922L14 20.827v4.444a2.51 2.51 0 0 0-.8 1.929 2.784 2.784 0 0 0 4.768 1.968A2.7 2.7 0 0 0 18.8 27.2a2.51 2.51 0 0 0-.8-1.929v-4.444l3.138 3.138a2.36 2.36 0 0 0 .812 1.922 2.66 2.66 0 0 0 1.968.788 2.83 2.83 0 0 0 2.014-.788 2.6 2.6 0 0 0 .831-1.969 2.74 2.74 0 0 0-.831-2.013 2.8 2.8 0 0 0-2.026-.829L20.828 18h4.443a2.51 2.51 0 0 0 1.93.8 2.784 2.784 0 0 0 1.967-4.769Z"/>',
  },
  document: {
    vb: '0 0 24 24',
    inner:
      '<path d="M0 0h24v24H0z"/><path fill="#42a5f5" d="M8 16h8v2H8zm0-4h8v2H8zm6-10H6c-1.1 0-2 .9-2 2v16c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8zm4 18H6V4h7v5h5z"/>',
  },
  log: {
    vb: '0 0 24 24',
    inner:
      '<path d="M0 0h24v24H0z"/><path fill="#afb42b" d="M19 5v9h-5v5H5V5zm0-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h10l6-6V5c0-1.1-.9-2-2-2m-7 11H7v-2h5zm5-4H7V8h10z"/>',
  },
  file: {
    vb: '0 0 16 16',
    inner:
      '<path d="m8.668 6h3.6641l-3.6641-3.668v3.668m-4.668-4.668h5.332l4 4v8c0 0.73828-0.59375 1.3359-1.332 1.3359h-8c-0.73828 0-1.332-0.59766-1.332-1.3359v-10.664c0-0.74219 0.59375-1.3359 1.332-1.3359m3.332 1.3359h-3.332v10.664h8v-6h-4.668z" fill="#90a4ae" />',
  },
  folder: {
    vb: '0 0 16 16',
    inner:
      '<path d="m6.922 3.768-.644-.536A1 1 0 0 0 5.638 3H2a1 1 0 0 0-1 1v8a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V5a1 1 0 0 0-1-1H7.562a1 1 0 0 1-.64-.232" fill="#90a4ae" />',
  },
  'folder-open': {
    vb: '0 0 16 16',
    inner:
      '<path d="M14.483 6H4.721a1 1 0 0 0-.949.684L2 12V5h12a1 1 0 0 0-1-1H7.562a1 1 0 0 1-.64-.232l-.644-.536A1 1 0 0 0 5.638 3H2a1 1 0 0 0-1 1v8a1 1 0 0 0 1 1h11l2.403-5.606A1 1 0 0 0 14.483 6" fill="#90a4ae" />',
  },
};

/**
 * Render one Material Icon Theme glyph by key. The glyph's own viewBox is used,
 * the size scales it isotropically, and `fill='none'` on the wrapper keeps the
 * transparent bounding-box paths (document/log) invisible while every coloured
 * path keeps its explicit fill. We deliberately do NOT set `currentColor`.
 */
function MaterialIcon({
  name,
  size = 16,
  className,
  style,
}: IconProps & {name: keyof typeof MATERIAL_ICONS}) {
  const g = MATERIAL_ICONS[name];
  return (
    <svg
      width={size}
      height={size}
      viewBox={g.vb}
      className={className}
      style={style}
      fill='none'
      aria-hidden='true'
      focusable='false'
      dangerouslySetInnerHTML={{__html: g.inner}}
    />
  );
}

/** Build a thin named wrapper component around one Material glyph. */
const mk =
  (name: keyof typeof MATERIAL_ICONS) =>
  (p: IconProps): React.ReactElement => <MaterialIcon {...p} name={name} />;

// Folder icons (Material folder + folder-open, the VS Code default-folder grey).
export const FolderClosedIcon = mk('folder');
export const FolderOpenIcon = mk('folder-open');

// Per-language file icons. Names mirror the canonical Material icon each maps to
// so the EXT_MAP / NAME_MAP below read like the upstream VS Code mapping. The
// public component names are unchanged from the previous hand-drawn set so every
// `IdeIcons` caller (IdeKit, IdeWorkspace) stays put.
export const TsIcon = mk('typescript');
export const TsxIcon = mk('react_ts');
export const JsIcon = mk('javascript');
export const JsxIcon = mk('react');
export const JsonIcon = mk('json');
export const JavaIcon = mk('java');
export const KotlinIcon = mk('kotlin');
export const PyIcon = mk('python');
export const GoIcon = mk('go');
export const RustIcon = mk('rust');
export const RubyIcon = mk('ruby');
export const PhpIcon = mk('php');
export const SqlIcon = mk('database');
export const MdIcon = mk('markdown');
export const YamlIcon = mk('yaml');
export const TomlIcon = mk('toml');
export const XmlIcon = mk('xml');
export const CssIcon = mk('css');
export const ScssIcon = mk('sass');
export const LessIcon = mk('less');
export const HtmlIcon = mk('html');
export const VueIcon = mk('vue');
export const SvelteIcon = mk('svelte');
export const ShIcon = mk('console');
export const TxtIcon = mk('document');
export const LogIcon = mk('log');
export const CIcon = mk('c');
export const HIcon = mk('h');
export const CppIcon = mk('cpp');
export const CsIcon = mk('csharp');
export const SwiftIcon = mk('swift');
export const DockerIcon = mk('docker');
export const GitIcon = mk('git');
export const LockIcon = mk('lock');
export const NpmLockIcon = mk('nodejs');
export const YarnLockIcon = mk('yarn');
export const PnpmLockIcon = mk('pnpm');
export const BunLockIcon = mk('bun');
export const PoetryLockIcon = mk('poetry');
export const EnvIcon = mk('tune');
export const ImageIcon = mk('image');
export const SvgIcon = mk('svg');
/** Config / settings files (makefile, .editorconfig, generic rc) — Material tune. */
export const ConfigIcon = mk('tune');
export const DefaultFileIcon = mk('file');

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
  vue: VueIcon,
  svelte: SvelteIcon,
  xml: XmlIcon,
  svg: SvgIcon,
  sh: ShIcon,
  bash: ShIcon,
  zsh: ShIcon,
  fish: ShIcon,
  txt: TxtIcon,
  log: LogIcon,
  text: TxtIcon,
  c: CIcon,
  h: HIcon,
  cpp: CppIcon,
  cc: CppIcon,
  cxx: CppIcon,
  hpp: HIcon,
  cs: CsIcon,
  swift: SwiftIcon,
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
  // lock files — Material shows the package manager's own glyph for the common
  // ones (matches VS Code); the rest fall back to a padlock.
  'package-lock.json': NpmLockIcon,
  'yarn.lock': YarnLockIcon,
  'pnpm-lock.yaml': PnpmLockIcon,
  'bun.lockb': BunLockIcon,
  'bun.lock': BunLockIcon,
  'poetry.lock': PoetryLockIcon,
  'composer.lock': LockIcon,
  'cargo.lock': LockIcon,
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
