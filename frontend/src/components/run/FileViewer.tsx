import Editor from '@monaco-editor/react';
import { useState } from 'react';

interface FileViewerProps {
  fileName: string;
  content: string;
  language?: string;
}

export function FileViewer({ fileName, content, language }: FileViewerProps) {
  const [copied, setCopied] = useState(false);

  const detectedLanguage = language || detectLanguage(fileName);

  const handleCopy = () => {
    navigator.clipboard.writeText(content);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="border border-white/[0.06] rounded-lg overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-2 bg-zinc-800 border-b border-white/[0.06]">
        <span className="text-sm font-mono text-zinc-300 truncate">{fileName}</span>
        <button
          onClick={handleCopy}
          className="text-xs text-zinc-500 hover:text-zinc-300 px-2 py-1 rounded hover:bg-white/[0.06]"
        >
          {copied ? 'Copied!' : 'Copy'}
        </button>
      </div>

      {/* Editor */}
      <Editor
        height={Math.min(Math.max(content.split('\n').length * 19, 100), 600)}
        language={detectedLanguage}
        value={content}
        theme="vs-dark"
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
    rb: 'ruby',
    php: 'php',
    gradle: 'groovy',
    tf: 'hcl',
  };
  return map[ext || ''] || 'plaintext';
}
