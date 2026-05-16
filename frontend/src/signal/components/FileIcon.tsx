/**
 * VS Code / IntelliJ-style file-type icons for the FileTree.
 *
 * Replaces the old emoji-based glyphs (☕ 🐍 🐳 …) which rendered as
 * platform-specific emoji fonts and looked off in the dark Signal theme.
 * These are 16×16 inline SVGs with brand-correct colors so the icons match
 * what users see in their IDE.
 *
 * Icon mark is intentionally simple — a single recognisable glyph + the
 * canonical brand color. We're not trying to ship full Material/Seti icons
 * (those would add a 100 KB+ chunk we don't need); the user just needs to
 * scan a folder tree and tell `pom.xml` apart from `App.tsx` at a glance.
 *
 * Each icon component takes a `size` prop (default 14) so the FileTree can
 * size them down a hair next to the filename. Colors are hardcoded brand
 * marks — they don't theme-shift, which matches how VS Code's seti theme
 * behaves (the icons stay on-brand in both light and dark editor themes).
 */
import type {CSSProperties} from 'react';

interface IconProps {
  size?: number;
  style?: CSSProperties;
}

const baseStyle: CSSProperties = {
  display: 'inline-block',
  flexShrink: 0,
  verticalAlign: 'middle',
};

function wrap(size: number, extra?: CSSProperties): CSSProperties {
  return {...baseStyle, width: size, height: size, ...(extra || {})};
}

/** Java — orange "coffee" mark (Oracle-ish). */
function JavaIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <rect x='1' y='1' width='14' height='14' rx='2' fill='#ED8B00' />
      <text
        x='8'
        y='12'
        textAnchor='middle'
        fontSize='10'
        fontWeight='700'
        fill='#fff'
        fontFamily='Arial, sans-serif'
      >
        J
      </text>
    </svg>
  );
}

/** Kotlin — gradient "K". */
function KotlinIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <defs>
        <linearGradient id='kt-grad' x1='0%' y1='0%' x2='100%' y2='100%'>
          <stop offset='0%' stopColor='#7F52FF' />
          <stop offset='100%' stopColor='#E44857' />
        </linearGradient>
      </defs>
      <rect x='1' y='1' width='14' height='14' rx='2' fill='url(#kt-grad)' />
      <text
        x='8'
        y='12'
        textAnchor='middle'
        fontSize='10'
        fontWeight='700'
        fill='#fff'
        fontFamily='Arial, sans-serif'
      >
        K
      </text>
    </svg>
  );
}

/** Python — yellow + blue Py mark. */
function PythonIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <path
        d='M7.5 1 a3 3 0 0 0 -3 3 v2 h6 v1 h-6 a3 3 0 0 0 -3 3 v2 a3 3 0 0 0 3 3 h2 v-3 a3 3 0 0 1 3 -3 h3 a2 2 0 0 0 2 -2 v-3 a3 3 0 0 0 -3 -3 z M5.5 3 a1 1 0 1 1 0 -.01'
        fill='#3776AB'
      />
      <path
        d='M8.5 15 a3 3 0 0 0 3 -3 v-2 h-6 v-1 h6 a3 3 0 0 0 3 -3 v-2 a3 3 0 0 0 -3 -3 h-2 v3 a3 3 0 0 1 -3 3 h-3 a2 2 0 0 0 -2 2 v3 a3 3 0 0 0 3 3 z M10.5 13 a1 1 0 1 1 0 .01'
        fill='#FFD43B'
      />
    </svg>
  );
}

/** TypeScript — blue "TS" badge. */
function TypeScriptIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <rect x='1' y='1' width='14' height='14' rx='2' fill='#3178C6' />
      <text
        x='8'
        y='12'
        textAnchor='middle'
        fontSize='7'
        fontWeight='800'
        fill='#fff'
        fontFamily='Arial, sans-serif'
        letterSpacing='-0.3'
      >
        TS
      </text>
    </svg>
  );
}

/** JavaScript — yellow "JS" badge. */
function JavaScriptIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <rect x='1' y='1' width='14' height='14' rx='2' fill='#F7DF1E' />
      <text
        x='8'
        y='12'
        textAnchor='middle'
        fontSize='7'
        fontWeight='800'
        fill='#000'
        fontFamily='Arial, sans-serif'
        letterSpacing='-0.3'
      >
        JS
      </text>
    </svg>
  );
}

/** React (.tsx / .jsx) — cyan atom. */
function ReactIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <circle cx='8' cy='8' r='1.5' fill='#61DAFB' />
      <g stroke='#61DAFB' strokeWidth='1' fill='none'>
        <ellipse cx='8' cy='8' rx='6' ry='2.3' />
        <ellipse cx='8' cy='8' rx='6' ry='2.3' transform='rotate(60 8 8)' />
        <ellipse cx='8' cy='8' rx='6' ry='2.3' transform='rotate(120 8 8)' />
      </g>
    </svg>
  );
}

/** Go — cyan gopher gradient. */
function GoIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <rect x='1' y='1' width='14' height='14' rx='2' fill='#00ADD8' />
      <text
        x='8'
        y='12'
        textAnchor='middle'
        fontSize='8'
        fontWeight='800'
        fill='#fff'
        fontFamily='Arial, sans-serif'
      >
        Go
      </text>
    </svg>
  );
}

/** Rust — orange-brown gear mark. */
function RustIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <rect x='1' y='1' width='14' height='14' rx='2' fill='#000' />
      <text
        x='8'
        y='12'
        textAnchor='middle'
        fontSize='10'
        fontWeight='800'
        fill='#DEA584'
        fontFamily='Arial, sans-serif'
      >
        R
      </text>
    </svg>
  );
}

/** Ruby — red gem. */
function RubyIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <path d='M3 6 L8 1 L13 6 L8 15 Z' fill='#CC342D' />
      <path d='M3 6 L8 6 L8 15 Z' fill='#A6201D' />
      <path d='M3 6 L13 6 L8 1 Z' fill='#E0463B' />
    </svg>
  );
}

/** HTML5 — orange shield. */
function HtmlIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <path d='M2 1 L14 1 L13 14 L8 15 L3 14 Z' fill='#E34F26' />
      <path d='M8 2 L8 14 L12 13 L13 2 Z' fill='#EF652A' />
      <text
        x='8'
        y='11'
        textAnchor='middle'
        fontSize='4.5'
        fontWeight='700'
        fill='#fff'
        fontFamily='Arial, sans-serif'
      >
        5
      </text>
    </svg>
  );
}

/** CSS — blue shield. */
function CssIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <path d='M2 1 L14 1 L13 14 L8 15 L3 14 Z' fill='#1572B6' />
      <path d='M8 2 L8 14 L12 13 L13 2 Z' fill='#33A9DC' />
      <text
        x='8'
        y='11'
        textAnchor='middle'
        fontSize='4.5'
        fontWeight='700'
        fill='#fff'
        fontFamily='Arial, sans-serif'
      >
        3
      </text>
    </svg>
  );
}

/** Markdown — speech-bubble mark. */
function MarkdownIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <rect
        x='1'
        y='3'
        width='14'
        height='10'
        rx='1.5'
        fill='none'
        stroke='#519ABA'
        strokeWidth='1'
      />
      <path
        d='M3.5 11 L3.5 5 L5 5 L6.5 7 L8 5 L9.5 5 L9.5 11 L8 11 L8 7.5 L6.5 9.5 L5 7.5 L5 11 Z'
        fill='#519ABA'
      />
      <path d='M12 5 L13.5 5 L13.5 8.5 L14.5 8.5 L12.75 11 L11 8.5 L12 8.5 Z' fill='#519ABA' />
    </svg>
  );
}

/** JSON — braces mark. */
function JsonIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <rect x='1' y='1' width='14' height='14' rx='2' fill='#FAB005' opacity='0.9' />
      <text
        x='8'
        y='12'
        textAnchor='middle'
        fontSize='10'
        fontWeight='700'
        fill='#000'
        fontFamily='Menlo, monospace'
      >
        {'{}'}
      </text>
    </svg>
  );
}

/** YAML — purple gear-ish. */
function YamlIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <rect x='1' y='1' width='14' height='14' rx='2' fill='#CB171E' />
      <text
        x='8'
        y='12'
        textAnchor='middle'
        fontSize='6.5'
        fontWeight='800'
        fill='#fff'
        fontFamily='Arial, sans-serif'
      >
        YML
      </text>
    </svg>
  );
}

/** XML — angle brackets. */
function XmlIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <rect x='1' y='1' width='14' height='14' rx='2' fill='#005A9E' />
      <text
        x='8'
        y='12'
        textAnchor='middle'
        fontSize='8'
        fontWeight='700'
        fill='#fff'
        fontFamily='Menlo, monospace'
      >
        {'<>'}
      </text>
    </svg>
  );
}

/** TOML — orange wrench-ish. */
function TomlIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <rect x='1' y='1' width='14' height='14' rx='2' fill='#9C4221' />
      <text
        x='8'
        y='12'
        textAnchor='middle'
        fontSize='5'
        fontWeight='800'
        fill='#fff'
        fontFamily='Arial, sans-serif'
        letterSpacing='-0.2'
      >
        TOML
      </text>
    </svg>
  );
}

/** Docker — blue whale silhouette. */
function DockerIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <path d='M1.5 8 H14 c0 2 -1 4 -3.5 4 H5 c-2 0 -3.5 -1 -3.5 -4 Z' fill='#2496ED' />
      <rect x='2' y='4' width='2' height='2' fill='#2496ED' />
      <rect x='4.5' y='4' width='2' height='2' fill='#2496ED' />
      <rect x='7' y='4' width='2' height='2' fill='#2496ED' />
      <rect x='4.5' y='1.5' width='2' height='2' fill='#2496ED' />
      <rect x='2' y='6.5' width='2' height='1.2' fill='#2496ED' />
      <rect x='4.5' y='6.5' width='2' height='1.2' fill='#2496ED' />
      <rect x='7' y='6.5' width='2' height='1.2' fill='#2496ED' />
      <rect x='9.5' y='6.5' width='2' height='1.2' fill='#2496ED' />
    </svg>
  );
}

/** Shell / bash — terminal prompt. */
function ShellIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <rect x='1' y='2' width='14' height='12' rx='1.5' fill='#4A4A4A' />
      <text
        x='3.5'
        y='11'
        fontSize='8'
        fontWeight='700'
        fill='#4EAB4E'
        fontFamily='Menlo, monospace'
      >
        {'>_'}
      </text>
    </svg>
  );
}

/** SQL — database cylinder. */
function SqlIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <ellipse cx='8' cy='3.5' rx='5.5' ry='1.8' fill='#336791' />
      <path d='M2.5 3.5 L2.5 12.5 a5.5 1.8 0 0 0 11 0 L13.5 3.5' fill='#336791' />
      <path
        d='M2.5 6.5 a5.5 1.8 0 0 0 11 0 M2.5 9.5 a5.5 1.8 0 0 0 11 0'
        fill='none'
        stroke='#fff'
        strokeWidth='0.4'
      />
    </svg>
  );
}

/** Image — picture frame. */
function ImageIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <rect
        x='1.5'
        y='2.5'
        width='13'
        height='11'
        rx='1'
        fill='none'
        stroke='#A074C4'
        strokeWidth='1'
      />
      <circle cx='5' cy='6' r='1.2' fill='#A074C4' />
      <path d='M2 13 L6 9 L9 11 L11.5 8.5 L14 11 L14 13 Z' fill='#A074C4' />
    </svg>
  );
}

/** Test file — small green check badge. */
function TestIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <rect x='1' y='1' width='14' height='14' rx='2' fill='#10B981' />
      <path
        d='M4 8.5 L7 11.5 L12 5.5'
        stroke='#fff'
        strokeWidth='2'
        fill='none'
        strokeLinecap='round'
        strokeLinejoin='round'
      />
    </svg>
  );
}

/** Config (gitignore / dockerignore / generic dotfile) — gear. */
function ConfigIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <path
        d='M8 5.5 a2.5 2.5 0 1 0 0 5 a2.5 2.5 0 0 0 0 -5 Z M7.2 1.5 L8.8 1.5 L9 3 L10.5 3.6 L11.8 2.7 L13.3 4.2 L12.4 5.5 L13 7 L14.5 7.2 L14.5 8.8 L13 9 L12.4 10.5 L13.3 11.8 L11.8 13.3 L10.5 12.4 L9 13 L8.8 14.5 L7.2 14.5 L7 13 L5.5 12.4 L4.2 13.3 L2.7 11.8 L3.6 10.5 L3 9 L1.5 8.8 L1.5 7.2 L3 7 L3.6 5.5 L2.7 4.2 L4.2 2.7 L5.5 3.6 L7 3 Z'
        fill='#888'
      />
    </svg>
  );
}

/** Default — generic page. */
function DefaultIcon({size = 14, style}: IconProps) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <path
        d='M3 1.5 L10 1.5 L13 4.5 L13 14.5 L3 14.5 Z'
        fill='none'
        stroke='#888'
        strokeWidth='1'
      />
      <path d='M10 1.5 L10 4.5 L13 4.5' fill='none' stroke='#888' strokeWidth='1' />
    </svg>
  );
}

/** Folder — coloured tab. */
function FolderIcon({size = 14, open = false, style}: IconProps & {open?: boolean}) {
  return (
    <svg viewBox='0 0 16 16' style={wrap(size, style)}>
      <path
        d={
          open
            ? 'M1 4 L6 4 L7.5 5.5 L14 5.5 L14 13 L2 13 Z'
            : 'M1 4 L6 4 L7.5 5.5 L15 5.5 L15 13 L1 13 Z'
        }
        fill='#5B8DEF'
        opacity={open ? 0.85 : 1}
      />
    </svg>
  );
}

/**
 * Resolve a file's name to an icon component. Mirrors the old `iconFor`
 * fall-through ladder; centralised here so FileTree just renders the picked
 * component without duplicating the extension dispatch.
 */
export function FileIcon({filename, size = 14}: {filename: string; size?: number}) {
  const lower = filename.toLowerCase();
  // Tests come first — they hide other extensions (e.g. `.test.ts` should
  // be a test icon, not a TS icon).
  if (
    lower.endsWith('.test.ts') ||
    lower.endsWith('.test.tsx') ||
    lower.endsWith('.test.js') ||
    lower.endsWith('.test.jsx') ||
    lower.endsWith('.spec.ts') ||
    lower.endsWith('.spec.tsx') ||
    lower.endsWith('.spec.js') ||
    lower.endsWith('.spec.jsx') ||
    lower.endsWith('test.java') ||
    lower.endsWith('tests.java')
  )
    return <TestIcon size={size} />;
  // Dockerfile is name-based, not extension-based.
  if (lower === 'dockerfile' || lower.endsWith('.dockerfile')) return <DockerIcon size={size} />;
  // Dotfile configs.
  if (
    lower === '.gitignore' ||
    lower === '.gitattributes' ||
    lower === '.dockerignore' ||
    lower === '.editorconfig' ||
    lower === '.env' ||
    lower.startsWith('.env.') ||
    lower === '.npmrc' ||
    lower === '.prettierrc' ||
    lower === '.prettierrc.json' ||
    lower === '.eslintrc.cjs' ||
    lower === '.eslintrc.json'
  )
    return <ConfigIcon size={size} />;
  // Markdown.
  if (lower.endsWith('.md') || lower === 'readme' || lower === 'license')
    return <MarkdownIcon size={size} />;
  // Languages.
  if (lower.endsWith('.java')) return <JavaIcon size={size} />;
  if (lower.endsWith('.kt') || lower.endsWith('.kts')) return <KotlinIcon size={size} />;
  if (lower.endsWith('.py') || lower.endsWith('.pyi')) return <PythonIcon size={size} />;
  if (lower.endsWith('.tsx') || lower.endsWith('.jsx')) return <ReactIcon size={size} />;
  if (lower.endsWith('.ts') || lower.endsWith('.mts') || lower.endsWith('.cts'))
    return <TypeScriptIcon size={size} />;
  if (lower.endsWith('.js') || lower.endsWith('.mjs') || lower.endsWith('.cjs'))
    return <JavaScriptIcon size={size} />;
  if (lower.endsWith('.go')) return <GoIcon size={size} />;
  if (lower.endsWith('.rs')) return <RustIcon size={size} />;
  if (lower.endsWith('.rb')) return <RubyIcon size={size} />;
  // Markup / styles.
  if (lower.endsWith('.html') || lower.endsWith('.htm')) return <HtmlIcon size={size} />;
  if (lower.endsWith('.css') || lower.endsWith('.scss') || lower.endsWith('.less'))
    return <CssIcon size={size} />;
  // Config / data.
  if (lower.endsWith('.json')) return <JsonIcon size={size} />;
  if (lower.endsWith('.yml') || lower.endsWith('.yaml')) return <YamlIcon size={size} />;
  if (lower.endsWith('.xml') || lower === 'pom.xml') return <XmlIcon size={size} />;
  if (lower.endsWith('.toml')) return <TomlIcon size={size} />;
  // Shell.
  if (lower.endsWith('.sh') || lower.endsWith('.bash') || lower.endsWith('.zsh'))
    return <ShellIcon size={size} />;
  // SQL.
  if (lower.endsWith('.sql')) return <SqlIcon size={size} />;
  // Images.
  if (
    lower.endsWith('.png') ||
    lower.endsWith('.jpg') ||
    lower.endsWith('.jpeg') ||
    lower.endsWith('.gif') ||
    lower.endsWith('.svg') ||
    lower.endsWith('.webp') ||
    lower.endsWith('.ico')
  )
    return <ImageIcon size={size} />;
  return <DefaultIcon size={size} />;
}

export {FolderIcon};
