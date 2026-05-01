import {useRef, useState} from 'react';
import {execPreviewCommand} from '@/lib/api';
import type {ExecResult} from '@/lib/api';
import {Terminal, Play, Loader2} from 'lucide-react';

const ALLOWED_COMMANDS = [
  'ls',
  'ps',
  'cat',
  'curl',
  'env',
  'df',
  'free',
  'netstat',
  'ss',
  'ping',
  'whoami',
  'hostname',
  'date',
  'uptime',
  'pwd',
];

interface Entry {
  cmd: string;
  result: ExecResult;
}

export default function ConsolePanel({runId}: {runId: string}) {
  const [input, setInput] = useState('');
  const [history, setHistory] = useState<Entry[]>([]);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const outputRef = useRef<HTMLDivElement>(null);

  const handleRun = async () => {
    const trimmed = input.trim();
    if (!trimmed || running) return;

    const [cmd, ...args] = trimmed.split(/\s+/);
    setError(null);
    setRunning(true);
    try {
      const result = await execPreviewCommand(runId, cmd, args);
      setHistory((h) => [...h, {cmd: trimmed, result}]);
      setInput('');
      setTimeout(
        () =>
          outputRef.current?.scrollTo({top: outputRef.current.scrollHeight, behavior: 'smooth'}),
        50,
      );
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Command failed');
    } finally {
      setRunning(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleRun();
  };

  return (
    <div className='flex flex-col h-full bg-neutral-950 rounded-lg border border-neutral-800 overflow-hidden'>
      {/* Header */}
      <div className='flex items-center gap-2 px-4 py-2 border-b border-neutral-800 bg-neutral-900'>
        <Terminal className='w-4 h-4 text-status-success' />
        <span className='text-sm font-medium text-neutral-200'>Container Console</span>
        <span className='ml-auto text-xs text-neutral-500'>
          Allowed: {ALLOWED_COMMANDS.join(', ')}
        </span>
      </div>

      {/* Output */}
      <div
        ref={outputRef}
        className='flex-1 overflow-y-auto p-4 font-mono text-xs space-y-3 min-h-0'
        style={{maxHeight: '400px'}}
      >
        {history.length === 0 && (
          <p className='text-neutral-600 italic'>Run a command to inspect the running container.</p>
        )}
        {history.map((entry, i) => (
          <div key={i}>
            <div className='flex items-center gap-1 text-status-success mb-1'>
              <span>$</span>
              <span>{entry.cmd}</span>
            </div>
            {entry.result.stdout && (
              <pre className='whitespace-pre-wrap text-neutral-200 leading-relaxed'>
                {entry.result.stdout}
              </pre>
            )}
            {entry.result.stderr && (
              <pre className='whitespace-pre-wrap text-status-error leading-relaxed'>
                {entry.result.stderr}
              </pre>
            )}
            {entry.result.exitCode !== 0 && (
              <span className='text-status-error text-xs'>exit {entry.result.exitCode}</span>
            )}
          </div>
        ))}
        {error && <p className='text-status-error text-xs'>{error}</p>}
      </div>

      {/* Input */}
      <div className='flex items-center gap-2 px-4 py-2 border-t border-neutral-800 bg-neutral-900'>
        <span className='text-status-success font-mono text-sm'>$</span>
        <input
          type='text'
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder='ls -la'
          disabled={running}
          className='flex-1 bg-transparent text-neutral-100 font-mono text-sm outline-none placeholder:text-neutral-600'
        />
        <button
          onClick={handleRun}
          disabled={running || !input.trim()}
          className='flex items-center gap-1 px-3 py-1 text-xs rounded bg-status-success hover:bg-status-success text-white disabled:opacity-40 disabled:cursor-not-allowed transition-colors'
        >
          {running ? <Loader2 className='w-3 h-3 animate-spin' /> : <Play className='w-3 h-3' />}
          Run
        </button>
      </div>
    </div>
  );
}
