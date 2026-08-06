// Agent-surface icons — backed by `lucide-react`, the app's ONE icon library.
//
// These were hand-authored SVG paths. Hand-drawn glyphs drift: each one carried its
// own optical weight, corner radius and terminal treatment, so a row of them read as
// a set of near-misses rather than a family. Lucide is drawn on a single 24px grid
// with consistent joinery, so the whole surface inherits one hand.
//
// The export NAMES and prop shapes are unchanged, so no call site churns. Sizing and
// stroke come from `ICON_STROKE` / the ide.css `--ide-icon-*` scale — never per-icon
// literals — so the set stays coherent as new icons are added.
import {
  ArrowDown as LuArrowDown,
  ArrowUp as LuArrowUp,
  Check,
  AlertCircle,
  Folder,
  Loader,
  Paperclip as LuPaperclip,
  Square,
  X,
} from 'lucide-react';

/** One stroke weight for every chrome glyph in the app. */
export const ICON_STROKE = 1.75;

const common = {
  strokeWidth: ICON_STROKE,
  absoluteStrokeWidth: false,
  'aria-hidden': true,
  focusable: 'false',
} as const;

export const Paperclip = () => <LuPaperclip size={17} {...common} />;

export const ArrowUp = () => <LuArrowUp size={16} {...common} />;

export const ArrowDown = ({size = 14}: {size?: number}) => <LuArrowDown size={size} {...common} />;

export const CloseIcon = ({size = 14}: {size?: number}) => <X size={size} {...common} />;

export const ToolOkIcon = ({size = 12}: {size?: number}) => <Check size={size} {...common} />;

export const ToolErrIcon = ({size = 12}: {size?: number}) => (
  <AlertCircle size={size} {...common} />
);

/** Spins via `.agent-tool-spin` in ide.css; reduced-motion settles it. */
export const ToolRunningIcon = ({size = 12}: {size?: number}) => (
  <Loader size={size} className='agent-tool-spin' {...common} />
);

export const StopIcon = () => <Square size={12} fill='currentColor' {...common} />;

export const FolderIcon = () => <Folder size={14} {...common} />;
