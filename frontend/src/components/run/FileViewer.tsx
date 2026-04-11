import Editor from '@monaco-editor/react';
import { useState } from 'react';
import { GEIST_THEME_NAME } from '@/lib/monacoGeistTheme';
import { MarkdownContent } from '@/components/shared/MarkdownContent';
import '@/lib/monacoSetup';

interface FileViewerProps {
  fileName: string;
  content: string;
  language?: string;
}

export function FileViewer({ fileName, content, language }: FileViewerProps) {
  const [copied, setCopied] = useState(false);
  const [mdPreview, setMdPreview] = useState(true);

  const detectedLanguage = language || detectLanguage(fileName);
  const isMarkdown = detectedLanguage === 'markdown';

  const handleCopy = () => {
    navigator.clipboard.writeText(content);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="border border-[var(--border-default)] rounded-sm overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-2 bg-[var(--gray-200)] border-b border-[var(--border-default)]">
        <span className="text-sm font-mono text-secondary truncate">{fileName}</span>
        <div className="flex items-center gap-2">
          {/* Markdown preview toggle */}
          {isMarkdown && (
            <div className="flex items-center bg-[var(--gray-100)] border border-[var(--border-default)] rounded text-xs overflow-hidden">
              <button
                onClick={() => setMdPreview(true)}
                className={`px-2 py-1 transition-colors ${mdPreview ? 'bg-[var(--gray-300)] text-primary' : 'text-muted hover:text-secondary'}`}
              >
                Preview
              </button>
              <button
                onClick={() => setMdPreview(false)}
                className={`px-2 py-1 transition-colors ${!mdPreview ? 'bg-[var(--gray-300)] text-primary' : 'text-muted hover:text-secondary'}`}
              >
                Source
              </button>
            </div>
          )}
          <button
            onClick={handleCopy}
            className="text-xs text-muted hover:text-secondary px-2 py-1 rounded hover:bg-[var(--gray-200)]"
          >
            {copied ? 'Copied!' : 'Copy'}
          </button>
        </div>
      </div>

      {/* Markdown rendered preview */}
      {isMarkdown && mdPreview ? (
        <div className="px-5 py-4 overflow-y-auto max-h-[600px] bg-[var(--geist-bg)]">
          <MarkdownContent content={content} />
        </div>
      ) : (
        /* Monaco editor — used for all non-markdown, or markdown in Source mode */
        <Editor
          height={Math.min(Math.max(content.split('\n').length * 19, 100), 600)}
          language={detectedLanguage}
          value={content}
          theme={GEIST_THEME_NAME}
          options={{
            readOnly: true,
            minimap: { enabled: false },
            scrollBeyondLastLine: false,
            fontSize: 13,
            lineNumbers: 'on',
            folding: true,
            wordWrap: 'on',
            renderLineHighlight: 'none',
            contextmenu: false,
          }}
        />
      )}
    </div>
  );
}

function detectLanguage(fileName: string): string {
  const ext = fileName.split('.').pop()?.toLowerCase();
  const map: Record<string, string> = {
    java: 'java',
    kt: 'kotlin',
    py: 'python',
    js: 'javascript',
    jsx: 'javascript',
    ts: 'typescript',
    tsx: 'typescript',
    html: 'html',
    css: 'css',
    scss: 'scss',
    json: 'json',
    yml: 'yaml',
    yaml: 'yaml',
    xml: 'xml',
    md: 'markdown',
    sql: 'sql',
    sh: 'shell',
    bash: 'shell',
    dockerfile: 'dockerfile',
    toml: 'toml',
    rs: 'rust',
    go: 'go',
    gradle: 'groovy',
    tf: 'hcl',
  };
  return map[ext || ''] || 'plaintext';
}
