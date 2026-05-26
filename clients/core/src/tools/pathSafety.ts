import path from 'node:path';

/**
 * Typed tool failure. `kind` mirrors the server's `ToolResult.ErrorKind` so the result envelope is
 * the same shape whether a tool ran locally or server-side.
 */
export class ToolError extends Error {
  readonly kind: string;

  constructor(kind: string, message: string) {
    super(message);
    this.name = 'ToolError';
    this.kind = kind;
  }
}

/**
 * Resolve `p` under `root`, rejecting anything that escapes the workspace (mirrors the server
 * `PathSafety`/`FileSandboxPolicy`). The client only ever touches files inside the directory the
 * user pointed the run at.
 */
export function safeResolve(root: string, p: string): string {
  if (typeof p !== 'string' || p.length === 0) {
    throw new ToolError('INVALID_ARGS', 'path is required');
  }
  const resolved = path.resolve(root, p);
  const rel = path.relative(root, resolved);
  if (rel === '..' || rel.startsWith('..' + path.sep) || path.isAbsolute(rel)) {
    throw new ToolError('FORBIDDEN', `path escapes workspace: ${p}`);
  }
  return resolved;
}
