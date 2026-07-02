export type AgentArtifact = {
  id: string;
  filename: string;
  language: string;
  content: string;
  lineCount: number;
  previewable: boolean;
};

const EXT_BY_LANGUAGE: Record<string, string> = {
  bash: 'sh',
  css: 'css',
  html: 'html',
  javascript: 'js',
  js: 'js',
  json: 'json',
  jsx: 'jsx',
  markdown: 'md',
  md: 'md',
  python: 'py',
  py: 'py',
  sh: 'sh',
  svg: 'svg',
  ts: 'ts',
  tsx: 'tsx',
  typescript: 'ts',
  xml: 'xml',
  yaml: 'yaml',
  yml: 'yml',
};

const PREVIEWABLE_EXTENSIONS = new Set(['html', 'htm', 'md', 'markdown', 'svg', 'txt']);

export function extractAgentArtifacts(markdown: string): AgentArtifact[] {
  const artifacts: AgentArtifact[] = [];
  const fencePattern = /```([^\n`]*)\n([\s\S]*?)```/g;
  let match: RegExpExecArray | null;
  while ((match = fencePattern.exec(markdown)) !== null) {
    const info = match[1]?.trim() ?? '';
    const content = trimFenceContent(match[2] ?? '');
    if (!content.trim()) continue;
    const language = languageFromFenceInfo(info);
    const filename =
      filenameFromFenceInfo(info) ||
      filenameFromNearbyText(markdown.slice(0, match.index)) ||
      fallbackFilename(artifacts.length + 1, language);
    const ext = extensionOf(filename);
    artifacts.push({
      id: `${artifacts.length}:${filename}`,
      filename,
      language,
      content,
      lineCount: content.split(/\r?\n/).length,
      previewable: PREVIEWABLE_EXTENSIONS.has(ext),
    });
  }
  return artifacts.slice(0, 8);
}

function trimFenceContent(value: string): string {
  return value.replace(/^\n+/, '').replace(/\n+$/, '');
}

function languageFromFenceInfo(info: string): string {
  const first = info.split(/\s+/).find(Boolean) ?? '';
  if (!first || first.includes('=') || looksLikePath(first)) return 'text';
  return first.toLowerCase();
}

function filenameFromFenceInfo(info: string): string | null {
  const parts = info.split(/\s+/).filter(Boolean);
  for (const part of parts) {
    const explicit = part.match(/^(?:file|filename|path)=(.+)$/i)?.[1];
    const candidate = explicit || part;
    if (looksLikePath(candidate)) return sanitizeFilename(candidate);
  }
  return null;
}

function filenameFromNearbyText(prefix: string): string | null {
  const lines = prefix.split(/\r?\n/).slice(-4).join('\n');
  const explicit = lines.match(
    /(?:file|filename|path)\s*[:=]\s*`?([A-Za-z0-9_./-]+\.[A-Za-z0-9]+)`?/i,
  );
  if (explicit?.[1]) return sanitizeFilename(explicit[1]);
  const inline = lines.match(/`([^`\n]+\.[A-Za-z0-9]+)`/);
  if (inline?.[1]) return sanitizeFilename(inline[1]);
  const trailing = lines.match(/([A-Za-z0-9_./-]+\.[A-Za-z0-9]+)\s*:?\s*$/);
  return trailing?.[1] ? sanitizeFilename(trailing[1]) : null;
}

function fallbackFilename(index: number, language: string): string {
  return `artifact-${index}.${EXT_BY_LANGUAGE[language] || 'txt'}`;
}

function looksLikePath(value: string): boolean {
  const cleaned = sanitizeFilename(value);
  return /^[A-Za-z0-9_./-]+\.[A-Za-z0-9]+$/.test(cleaned);
}

function sanitizeFilename(value: string): string {
  return value
    .trim()
    .replace(/^["'`]+|["'`.,:;]+$/g, '')
    .replace(/\\/g, '/')
    .replace(/^\/+/, '')
    .slice(0, 120);
}

function extensionOf(filename: string): string {
  return filename.split('.').pop()?.toLowerCase() || '';
}
