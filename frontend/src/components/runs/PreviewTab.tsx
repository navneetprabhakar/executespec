import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getPreviewStatus, startPreview, stopPreview, getPreviewLogs, getOpenApiSpec,
} from '@/lib/api';
import type { LocalPreviewStatus } from '@/lib/api';
import { cn } from '@/lib/utils';
import {
  Play, Square, RefreshCw, ChevronDown, Terminal, ExternalLink,
  Filter,
} from 'lucide-react';

const BACKEND_ONLY = new Set(['SPRING_BOOT', 'GRADLE']);

const TYPE_LABELS: Record<string, string> = {
  SPRING_BOOT: 'Spring Boot',
  GRADLE: 'Gradle',
  NODE: 'Node.js',
  PYTHON: 'Python',
  GO: 'Go',
  RUBY: 'Ruby',
  PHP: 'PHP',
  RUST: 'Rust',
  DOTNET: '.NET',
  STATIC: 'Static',
  FULLSTACK: 'Full-Stack',
  UNKNOWN: 'Unknown',
};

const FULL_STACK_TYPES = new Set(['FULL_STACK', 'FULLSTACK']);

export default function PreviewTab({ runId }: { runId: string }) {
  const [status, setStatus] = useState<LocalPreviewStatus | null>(null);
  const [logs, setLogs] = useState<string[]>([]);
  const [logsExpanded, setLogsExpanded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [openapiSpec, setOpenapiSpec] = useState<string | null>(null);
  const [logFilter, setLogFilter] = useState<'all' | 'backend' | 'frontend'>('all');
  const pollRef = useRef<ReturnType<typeof setInterval>>();
  const logPollRef = useRef<ReturnType<typeof setInterval>>();
  const logEndRef = useRef<HTMLDivElement>(null);

  // Fetch status once on mount
  useEffect(() => {
    getPreviewStatus(runId).then(setStatus).catch(() => {});
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
      if (logPollRef.current) clearInterval(logPollRef.current);
    };
  }, [runId]);

  // Poll status while STARTING
  useEffect(() => {
    if (status?.status === 'STARTING') {
      pollRef.current = setInterval(() => {
        getPreviewStatus(runId).then((s) => {
          setStatus(s);
          if (s.status !== 'STARTING' && pollRef.current) {
            clearInterval(pollRef.current);
            pollRef.current = undefined;
          }
        }).catch(() => {});
      }, 3000);
    }
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, [status?.status, runId]);

  // Determine if full-stack for log filtering
  const isFullStack = status?.projectType ? FULL_STACK_TYPES.has(status.projectType) : false;
  // For full-stack, default to backend logs; for single-stack show all
  const effectiveFilter = isFullStack ? logFilter : 'all';

  // Poll logs while STARTING or RUNNING — fetch ALL logs from startup
  useEffect(() => {
    const active = status?.status === 'STARTING' || status?.status === 'RUNNING';
    if (active) {
      const fetchLogs = () => getPreviewLogs(runId, 0, effectiveFilter).then(r => setLogs(r.lines)).catch(() => {});
      fetchLogs();
      logPollRef.current = setInterval(fetchLogs, 5000);
    }
    return () => {
      if (logPollRef.current) clearInterval(logPollRef.current);
    };
  }, [status?.status, runId, effectiveFilter]);

  // Fetch OpenAPI spec for backend-only projects
  useEffect(() => {
    if (status?.status === 'RUNNING' && status.projectType && BACKEND_ONLY.has(status.projectType)) {
      getOpenApiSpec(runId).then(setOpenapiSpec).catch(() => setOpenapiSpec(null));
    }
  }, [status?.status, status?.projectType, runId]);

  // Auto-scroll logs
  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs]);

  const handleStart = useCallback(async () => {
    setLoading(true);
    try {
      const s = await startPreview(runId);
      setStatus(s);
    } catch (e: any) {
      setStatus(prev => prev ? { ...prev, status: 'FAILED', errorMessage: e.message } : null);
    } finally {
      setLoading(false);
    }
  }, [runId]);

  const handleStop = useCallback(async () => {
    setLoading(true);
    try {
      await stopPreview(runId);
      const s = await getPreviewStatus(runId);
      setStatus(s);
    } catch {} finally {
      setLoading(false);
    }
  }, [runId]);

  const handleRefresh = useCallback(async () => {
    const s = await getPreviewStatus(runId).catch(() => null);
    if (s) setStatus(s);
    const l = await getPreviewLogs(runId, 0, effectiveFilter).catch(() => null);
    if (l) setLogs(l.lines);
  }, [runId, effectiveFilter]);

  const isIdle = !status || status.status === 'IDLE' || status.status === 'STOPPED';
  const isStarting = status?.status === 'STARTING';
  const isRunning = status?.status === 'RUNNING';
  const isFailed = status?.status === 'FAILED';
  const projectType = status?.projectType ?? 'UNKNOWN';
  const isBackendOnly = BACKEND_ONLY.has(projectType);
  const hasLogs = isStarting || isRunning || isFailed;
  const showLogFilter = isFullStack && hasLogs;

  return (
    <div className="flex flex-col h-full">
      {/* ── Control bar ── */}
      <div className="flex items-center gap-3 px-4 py-3 border-b border-white/[0.06] flex-shrink-0">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-xs px-2 py-0.5 rounded bg-white/[0.06] text-zinc-400 font-mono">
              {TYPE_LABELS[projectType] ?? projectType}
            </span>
            {isRunning && (
              <span className="flex items-center gap-1.5 text-xs text-accent-emerald">
                <span className="relative flex h-1.5 w-1.5">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-accent-emerald opacity-75" />
                  <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-accent-emerald" />
                </span>
                Running
              </span>
            )}
            {isStarting && (
              <span className="flex items-center gap-1.5 text-xs text-accent-amber animate-pulse">
                <span className="h-3.5 w-3.5 rounded-full border-2 border-accent-amber/30 border-t-accent-amber animate-spin" />
                Starting...
              </span>
            )}
            {isFailed && (
              <span className="text-xs text-accent-rose">Failed</span>
            )}
          </div>
          {isRunning && status?.url && (
            <a
              href={status.url}
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center gap-1 text-xs text-accent-emerald hover:underline mt-0.5"
            >
              <ExternalLink className="h-3 w-3" />
              {status.url}
            </a>
          )}
          {isRunning && status?.backendUrl && status?.frontendUrl && (
            <div className="flex items-center gap-3 mt-0.5 text-xs text-zinc-500">
              <span>Backend: <span className="text-zinc-300">{status.backendUrl}</span></span>
              <span>Frontend: <span className="text-zinc-300">{status.frontendUrl}</span></span>
            </div>
          )}
        </div>

        <div className="flex items-center gap-2 flex-shrink-0">
          {!isStarting && (
            <button
              onClick={handleRefresh}
              className="p-1.5 rounded text-zinc-500 hover:text-zinc-300 hover:bg-white/[0.04] transition-colors"
              title="Refresh status"
            >
              <RefreshCw className="h-3.5 w-3.5" />
            </button>
          )}
          {(isIdle || isFailed) && (
            <button
              onClick={handleStart}
              disabled={loading}
              className={cn(
                'flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-medium transition-all',
                'bg-accent-emerald/15 text-accent-emerald hover:bg-accent-emerald/25 disabled:opacity-40'
              )}
            >
              {loading
                ? <span className="h-3 w-3 rounded-full border-2 border-accent-emerald/30 border-t-accent-emerald animate-spin" />
                : <Play className="h-3 w-3" />}
              {isFailed ? 'Retry' : 'Start Preview'}
            </button>
          )}
          {(isRunning || isStarting) && (
            <button
              onClick={handleStop}
              disabled={loading}
              className={cn(
                'flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-medium transition-all',
                'bg-accent-rose/15 text-accent-rose hover:bg-accent-rose/25 disabled:opacity-40'
              )}
            >
              <Square className="h-3 w-3" />
              Stop
            </button>
          )}
        </div>
      </div>

      {/* ── Main content ── */}
      <div className="flex-1 overflow-hidden">
        {isRunning && status?.url ? (
          isBackendOnly ? (
            <BackendPreview runId={runId} url={status.url} openapiSpec={openapiSpec} />
          ) : (
            <iframe
              src={status.url}
              className="w-full h-full border-0"
              title="App preview"
              sandbox="allow-scripts allow-same-origin allow-forms allow-popups"
            />
          )
        ) : isStarting ? (
          <div className="flex-1 flex items-center justify-center h-full">
            <div className="text-center space-y-2">
              <span className="inline-block h-6 w-6 rounded-full border-2 border-accent-amber/30 border-t-accent-amber animate-spin" />
              <p className="text-sm text-zinc-400">Starting {TYPE_LABELS[projectType] ?? projectType} server...</p>
              <p className="text-xs text-zinc-600">This may take a moment for the first run</p>
            </div>
          </div>
        ) : isFailed ? (
          <div className="p-4 space-y-3">
            <div className="p-3 rounded-lg bg-accent-rose/10 border border-accent-rose/20">
              <p className="text-sm font-medium text-accent-rose">Preview failed to start</p>
              <p className="text-xs text-accent-rose/80 mt-1 break-all whitespace-pre-wrap">
                {status?.errorMessage ?? 'Unknown error'}
              </p>
            </div>
            {logs.length > 0 && (
              <div className="space-y-1">
                <p className="text-xs text-zinc-500 font-medium">Process output ({logs.length} lines):</p>
                <pre className="p-3 rounded-lg bg-black/30 text-[11px] font-mono text-zinc-400 whitespace-pre-wrap break-all max-h-64 overflow-y-auto leading-relaxed">
                  {logs.join('\n')}
                </pre>
              </div>
            )}
          </div>
        ) : (
          <div className="flex-1 flex items-center justify-center h-full">
            <div className="text-center space-y-2 px-8">
              <p className="text-sm text-zinc-400">Preview not started</p>
              <p className="text-xs text-zinc-600 max-w-sm">
                Click <strong className="text-zinc-400">Start Preview</strong> to launch
                the {TYPE_LABELS[projectType]?.toLowerCase() ?? 'application'} dev server
                directly in the workspace.
              </p>
              {projectType === 'UNKNOWN' && (
                <p className="text-xs text-zinc-700">
                  No recognised project files found. Preview may not be available for this run.
                </p>
              )}
            </div>
          </div>
        )}
      </div>

      {/* ── Log panel (collapsible) ── */}
      {hasLogs && (
        <div className="border-t border-white/[0.06] flex-shrink-0">
          <button
            onClick={() => setLogsExpanded(!logsExpanded)}
            className="w-full flex items-center justify-between px-4 py-2 text-xs text-zinc-500 hover:text-zinc-300 hover:bg-white/[0.02] transition-colors"
          >
            <span className="flex items-center gap-1.5">
              <Terminal className="h-3 w-3" />
              Process Logs {logs.length > 0 && `(${logs.length} lines)`}
            </span>
            <ChevronDown className={cn('h-3 w-3 transition-transform', logsExpanded && 'rotate-180')} />
          </button>
          {logsExpanded && (
            <>
              {/* Filter bar for full-stack projects */}
              {showLogFilter && (
                <div className="flex items-center gap-1.5 px-4 pb-2">
                  <Filter className="h-3 w-3 text-zinc-600" />
                  {(['all', 'backend', 'frontend'] as const).map(f => (
                    <button
                      key={f}
                      onClick={() => setLogFilter(f)}
                      className={cn(
                        'px-2 py-0.5 rounded text-[10px] font-medium transition-colors',
                        logFilter === f
                          ? 'bg-accent-cyan/15 text-accent-cyan'
                          : 'bg-white/[0.04] text-zinc-500 hover:text-zinc-300'
                      )}
                    >
                      {f === 'all' ? 'All' : f === 'backend' ? 'Backend' : 'Frontend'}
                    </button>
                  ))}
                </div>
              )}
              <div className="max-h-48 overflow-y-auto px-4 pb-3">
                <pre className="text-[11px] font-mono text-zinc-500 whitespace-pre-wrap break-all leading-relaxed">
                  {logs.length > 0 ? logs.join('\n') : 'No output yet...'}
                </pre>
                <div ref={logEndRef} />
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}

// ── Backend-only preview (OpenAPI viewer + health) ─────────────────────────────

function BackendPreview({
  runId,
  url,
  openapiSpec,
}: {
  runId: string;
  url: string;
  openapiSpec: string | null;
}) {
  if (openapiSpec) {
    // Render SwaggerUI via CDN in an iframe — no npm dependency needed
    const swaggerHtml = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>API Documentation</title>
  <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
  <style>
    body { margin: 0; background: #1a1a2e; }
    .swagger-ui { background: #1a1a2e; }
    .swagger-ui .topbar { display: none; }
    .swagger-ui .info { margin: 20px 0 10px; }
    .swagger-ui .scheme-container { background: transparent; box-shadow: none; }
  </style>
</head>
<body>
  <div id="swagger-ui"></div>
  <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
  <script>
    SwaggerUIBundle({
      url: '/api/runs/${runId}/openapi.yaml',
      dom_id: '#swagger-ui',
      presets: [SwaggerUIBundle.presets.apis],
      layout: 'BaseLayout',
      deepLinking: true,
      defaultModelsExpandDepth: 0,
    });
  </script>
</body>
</html>`;

    return (
      <iframe
        srcDoc={swaggerHtml}
        className="w-full h-full border-0"
        title="API Documentation"
        sandbox="allow-scripts allow-same-origin"
      />
    );
  }

  // No OpenAPI spec — show simple health status
  return (
    <div className="h-full flex items-center justify-center">
      <div className="text-center space-y-3 px-8">
        <div className="flex items-center justify-center gap-2">
          <span className="relative flex h-2.5 w-2.5">
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-accent-emerald opacity-75" />
            <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-accent-emerald" />
          </span>
          <span className="text-sm font-medium text-accent-emerald">Service running</span>
        </div>
        <p className="text-xs text-zinc-500">
          Backend API is live at <code className="font-mono text-zinc-300">{url}</code>
        </p>
        <p className="text-xs text-zinc-600">
          No OpenAPI spec found. The spec is auto-generated after merge —
          if this is a fresh run, try refreshing in a moment.
        </p>
        <a
          href={url}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1 text-xs text-accent-cyan hover:underline"
        >
          <ExternalLink className="h-3 w-3" />
          Open in new tab
        </a>
      </div>
    </div>
  );
}
