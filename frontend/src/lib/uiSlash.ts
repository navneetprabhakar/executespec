export type UiSlashSurface = 'spec' | 'project' | 'run';

export type UiSlashCommand = {
  command: string;
  args?: string;
  description: string;
  surfaces?: readonly UiSlashSurface[];
};

export const UI_SLASH_COMMANDS: readonly UiSlashCommand[] = [
  {command: '/help', description: 'Show available UI commands'},
  {command: '/team', description: 'Open team access'},
  {command: '/invite', description: 'Open teammate invites'},
  {command: '/projects', description: 'List projects'},
  {command: '/runs', description: 'List recent work'},
  {command: '/status', description: 'Show current context'},
  {command: '/provider', args: '<anthropic|openai|deepseek>', description: 'Set provider'},
  {command: '/clear', description: 'Clear this chat'},
  {command: '/compile', description: 'Create work from project chat', surfaces: ['project']},
  {command: '/compact', description: 'Compact project chat history', surfaces: ['project']},
  {command: '/memory', description: 'Show project context panel', surfaces: ['project']},
] as const;

export function uiSlashUsage(command: UiSlashCommand): string {
  return command.args ? `${command.command} ${command.args}` : command.command;
}

export function uiSlashCommandsFor(surface: UiSlashSurface): UiSlashCommand[] {
  return UI_SLASH_COMMANDS.filter((c) => !c.surfaces || c.surfaces.includes(surface));
}

export function uiSlashSuggestions(input: string, surface: UiSlashSurface, limit = 12) {
  const line = input.trimStart().toLowerCase();
  if (!line.startsWith('/')) return [];
  return uiSlashCommandsFor(surface)
    .filter((c) => {
      const usage = uiSlashUsage(c).toLowerCase();
      return line === '/' || c.command.startsWith(line) || usage.startsWith(line);
    })
    .slice(0, limit);
}

export function uiSlashHelp(surface: UiSlashSurface): string {
  return uiSlashCommandsFor(surface)
    .map((c) => `${uiSlashUsage(c)} — ${c.description}`)
    .join('\n');
}

export function normalizeUiProvider(input?: string): string | null {
  const p = (input || '').trim().toUpperCase();
  if (!p) return null;
  if (p === 'CLAUDE') return 'ANTHROPIC';
  if (['ANTHROPIC', 'OPENAI', 'DEEPSEEK'].includes(p)) return p;
  return null;
}
