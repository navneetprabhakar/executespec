export interface ParsedArgs {
  positionals: string[];
  flags: Record<string, string | boolean>;
}

// Flags that never take a value (so `--yes "spec"` doesn't swallow the positional).
const BOOLEAN_FLAGS = new Set([
  'watch',
  'yes',
  'json',
  'tdd',
  'local',
  'help',
  'version',
  'skip-approval',
  'no-browser',
]);

export function parseArgs(argv: string[]): ParsedArgs {
  const positionals: string[] = [];
  const flags: Record<string, string | boolean> = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!;
    if (a === '--') {
      positionals.push(...argv.slice(i + 1));
      break;
    }
    if (a.startsWith('--')) {
      const body = a.slice(2);
      const eq = body.indexOf('=');
      if (eq >= 0) {
        flags[body.slice(0, eq)] = body.slice(eq + 1);
        continue;
      }
      if (BOOLEAN_FLAGS.has(body)) {
        flags[body] = true;
        continue;
      }
      const next = argv[i + 1];
      if (next !== undefined && !next.startsWith('--')) {
        flags[body] = next;
        i++;
      } else {
        flags[body] = true;
      }
    } else {
      positionals.push(a);
    }
  }
  return { positionals, flags };
}

export function flagStr(flags: ParsedArgs['flags'], key: string): string | undefined {
  const v = flags[key];
  return typeof v === 'string' ? v : undefined;
}

export function flagBool(flags: ParsedArgs['flags'], key: string): boolean {
  return flags[key] === true || flags[key] === 'true';
}

export function flagNum(flags: ParsedArgs['flags'], key: string): number | undefined {
  const v = flags[key];
  if (typeof v === 'string' && v.trim() !== '' && !Number.isNaN(Number(v))) return Number(v);
  return undefined;
}
