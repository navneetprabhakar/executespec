import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getPreviewStatus, startPreview, stopPreview, getPreviewLogs, getOpenApiSpec,
  analyzePreview, probeApiDocs,
} from '@/lib/api';
import type { LocalPreviewStatus, PreviewAnalysis, ApiDocsProbeResult } from '@/lib/api';
import { cn } from '@/lib/utils';
import {
  Play, Square, RefreshCw, ChevronDown, Terminal, ExternalLink,
  Filter, AlertTriangle, Database, Eye, EyeOff, X, Shield, Clock,
} from 'lucide-react';

const BACKEND_ONLY = new Set(['SPRING_BOOT', 'GRADLE', 'NODE_BACKEND', 'PYTHON']);

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

const DB_LABELS: Record<string, { label: string; emoji: string }> = {
  POSTGRESQL: { label: 'PostgreSQL', emoji: '\u{1F418}' },
  MYSQL: { label: 'MySQL', emoji: '\u{1F42C}' },
  MONGODB: { label: 'MongoDB', emoji: '\u{1F343}' },
  REDIS: { label: 'Redis', emoji: '\u{26A1}' },
};

const FULL_STACK_TYPES = new Set(['FULL_STACK', 'FULLSTACK']);

/** Statuses that indicate the preview is starting up (intermediate phases) */
const STARTING_STATUSES = new Set(['ANALYZING', 'BUILDING', 'STARTING']);

const STATUS_MESSAGES: Record<string, string> = {
  ANALYZING: 'Analyzing project...',
  BUILDING: 'Building & starting containers...',
  STARTING: 'Waiting for application...',
};

/** Format seconds to MM:SS */
function formatCountdown(seconds: number): string {
  if (seconds <= 0) return '0:00';
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

export default function PreviewTab({ runId }: { runId: string }) {
  const [status, setStatus] = useState<LocalPreviewStatus | null>(null);
  const [logs, setLogs] = useState<string[]>([]);
  const [logsExpanded, setLogsExpanded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [analyzing, setAnalyzing] = useState(false);
  const [openapiSpec, setOpenapiSpec] = useState<string | null>(null);
  const [logFilter, setLogFilter] = useState<'all' | 'backend' | 'frontend' | 'db'>('all');
  const [showEnvDialog, setShowEnvDialog] = useState(false);
  const [analysis, setAnalysis] = useState<PreviewAnalysis | null>(null);
  const [countdown, setCountdown] = useState<number>(0);
  const pollRef = useRef<ReturnType<typeof setInterval>>();
  const logPollRef = useRef<ReturnType<typeof setInterval>>();
  const countdownRef = useRef<ReturnType<typeof setInterval>>();
  const logEndRef = useRef<HTMLDivElement>(null);

  // Fetch status once on mount
  useEffect(() => {
    getPreviewStatus(runId).then(setStatus).catch(() => {});
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
      if (logPollRef.current) clearInterval(logPollRef.current);
    };
  }, [runId]);

  // Poll status while in any starting phase
  useEffect(() => {
    const isStartingPhase = status?.status ? STARTING_STATUSES.has(status.status) : false;
    if (isStartingPhase) {
      pollRef.current = setInterval(() => {
        getPreviewStatus(runId).then((s) => {
          setStatus(s);
          if (!STARTING_STATUSES.has(s.status) && pollRef.current) {
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

  // Countdown timer for running previews
  useEffect(() => {
    if (status?.status === 'RUNNING' && status.expiresAt) {
      const updateCountdown = () => {
        const remaining = Math.max(0, Math.floor(
          (new Date(status.expiresAt!).getTime() - Date.now()) / 1000
        ));
        setCountdown(remaining);
        if (remaining <= 0) {
          // Refresh status — preview has expired
          getPreviewStatus(runId).then(setStatus).catch(() => {});
        }
      };
      updateCountdown();
      countdownRef.current = setInterval(updateCountdown, 1000);
    }
    return () => {
      if (countdownRef.current) clearInterval(countdownRef.current);
    };
  }, [status?.status, status?.expiresAt, runId]);

  // Determine if full-stack for log filtering
  const isFullStack = status?.projectType ? FULL_STACK_TYPES.has(status.projectType) : false;
  const hasDatabase = !!status?.databaseType;
  const effectiveFilter = isFullStack ? logFilter : (logFilter === 'db' ? 'db' : 'all');

  // Poll logs while in any starting phase or RUNNING — fetch ALL logs from startup
  useEffect(() => {
    const isActive = status?.status ? (STARTING_STATUSES.has(status.status) || status.status === 'RUNNING') : false;
    const active = isActive;
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
    setAnalyzing(true);
    try {
      // First: analyze workspace for DB + env var requirements
      const result = await analyzePreview(runId);
      setAnalysis(result);

      // Only show dialog if there are third-party SECRET vars without defaults
      // STANDARD vars are internal config and should never be shown to the user
      const thirdPartySecrets = result.requiredEnvVars.filter(
        v => v.category === 'SECRET' && !v.defaultValue
      );

      if (thirdPartySecrets.length > 0) {
        setShowEnvDialog(true);
        setAnalyzing(false);
        return;
      }

      // No env vars needed — start directly
      setAnalyzing(false);
      setLoading(true);
      const s = await startPreview(runId);
      setStatus(s);
    } catch (e: any) {
      setStatus(prev => prev ? { ...prev, status: 'FAILED', errorMessage: e.message } : null);
    } finally {
      setLoading(false);
      setAnalyzing(false);
    }
  }, [runId]);

  const handleStartWithEnvVars = useCallback(async (envVars: Record<string, string>) => {
    setShowEnvDialog(false);
    setLoading(true);
    try {
      const s = await startPreview(runId, envVars);
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
  const isStartingPhase = status?.status ? STARTING_STATUSES.has(status.status) : false;
  const isRunning = status?.status === 'RUNNING';
  const isFailed = status?.status === 'FAILED';
  const isTimedOut = status?.status === 'TIMED_OUT';
  const projectType = status?.projectType ?? 'UNKNOWN';
  const isBackendOnly = BACKEND_ONLY.has(projectType);
  const hasLogs = isStartingPhase || isRunning || isFailed || isTimedOut;
  const showLogFilter = (isFullStack || hasDatabase) && hasLogs;
  const dbInfo = status?.databaseType ? DB_LABELS[status.databaseType] : null;
  const previewCountInfo = status?.maxPreviewStarts && status.maxPreviewStarts > 0
    ? `${status.previewStartCount ?? 0} of ${status.maxPreviewStarts} previews used`
    : null;
  const countdownWarning = countdown > 0 && countdown <= 120; // amber warning under 2 min

  return (
    <div className="flex flex-col h-full">
      {/* ── Control bar ── */}
      <div className="flex items-center gap-3 px-4 py-3 border-b border-white/[0.06] flex-shrink-0">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-xs px-2 py-0.5 rounded bg-white/[0.06] text-zinc-400 font-mono">
              {TYPE_LABELS[projectType] ?? projectType}
            </span>
            {dbInfo && (
              <span className="flex items-center gap-1 text-xs px-2 py-0.5 rounded bg-accent-cyan/10 text-accent-cyan">
                <Database className="h-3 w-3" />
                {dbInfo.label}
              </span>
            )}
            {isRunning && (
              <span className="flex items-center gap-1.5 text-xs text-accent-emerald">
                <span className="relative flex h-1.5 w-1.5">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-accent-emerald opacity-75" />
                  <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-accent-emerald" />
                </span>
                Running
              </span>
            )}
            {isRunning && countdown > 0 && (
              <span className={cn(
                'flex items-center gap-1 text-xs font-mono',
                countdownWarning ? 'text-accent-amber' : 'text-zinc-500'
              )}>
                <Clock className="h-3 w-3" />
                {formatCountdown(countdown)}
              </span>
            )}
            {isStartingPhase && (
              <span className="flex items-center gap-1.5 text-xs text-accent-amber animate-pulse">
                <span className="h-3.5 w-3.5 rounded-full border-2 border-accent-amber/30 border-t-accent-amber animate-spin" />
                {STATUS_MESSAGES[status!.status] ?? 'Starting...'}
              </span>
            )}
            {isFailed && (
              <span className="text-xs text-accent-rose">Failed</span>
            )}
            {isTimedOut && (
              <span className="flex items-center gap-1 text-xs text-accent-rose">
                <AlertTriangle className="h-3 w-3" />
                Timed Out
              </span>
            )}
            {previewCountInfo && (
              <span className="text-[10px] text-zinc-600">{previewCountInfo}</span>
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
          {!isStartingPhase && (
            <button
              onClick={handleRefresh}
              className="p-1.5 rounded text-zinc-500 hover:text-zinc-300 hover:bg-white/[0.04] transition-colors"
              title="Refresh status"
            >
              <RefreshCw className="h-3.5 w-3.5" />
            </button>
          )}
          {(isIdle || isFailed || isTimedOut) && (
            <button
              onClick={handleStart}
              disabled={loading || analyzing}
              className={cn(
                'flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-medium transition-all',
                'bg-accent-emerald/15 text-accent-emerald hover:bg-accent-emerald/25 disabled:opacity-40'
              )}
            >
              {(loading || analyzing)
                ? <span className="h-3 w-3 rounded-full border-2 border-accent-emerald/30 border-t-accent-emerald animate-spin" />
                : <Play className="h-3 w-3" />}
              {analyzing ? 'Analyzing...' : isFailed || isTimedOut ? 'Retry' : 'Start Preview'}
            </button>
          )}
          {(isRunning || isStartingPhase) && (
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
        ) : isStartingPhase ? (
          <div className="flex-1 flex items-center justify-center h-full">
            <div className="text-center space-y-2">
              <span className="inline-block h-6 w-6 rounded-full border-2 border-accent-amber/30 border-t-accent-amber animate-spin" />
              <p className="text-sm text-zinc-400">{STATUS_MESSAGES[status!.status] ?? 'Starting...'}</p>
              {dbInfo && (
                <p className="text-xs text-accent-cyan">
                  {dbInfo.emoji} {dbInfo.label} database auto-configured
                </p>
              )}
              <p className="text-xs text-zinc-600">This preview will automatically shut down in 5 minutes</p>
            </div>
          </div>
        ) : (isFailed || isTimedOut) ? (
          <div className="p-4 space-y-3">
            <div className={cn(
              'p-3 rounded-lg border',
              isTimedOut
                ? 'bg-accent-amber/10 border-accent-amber/20'
                : 'bg-accent-rose/10 border-accent-rose/20'
            )}>
              <p className={cn(
                'text-sm font-medium',
                isTimedOut ? 'text-accent-amber' : 'text-accent-rose'
              )}>
                {isTimedOut ? 'Preview startup timed out (5 minutes)' : 'Preview failed to start'}
              </p>
              <p className={cn(
                'text-xs mt-1 break-all whitespace-pre-wrap',
                isTimedOut ? 'text-accent-amber/80' : 'text-accent-rose/80'
              )}>
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
                the {TYPE_LABELS[projectType]?.toLowerCase() ?? 'application'} dev server.
                {' '}Database and dependencies will be auto-detected.
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
              {/* Filter bar */}
              {showLogFilter && (
                <div className="flex items-center gap-1.5 px-4 pb-2">
                  <Filter className="h-3 w-3 text-zinc-600" />
                  {(['all', ...(isFullStack ? ['backend', 'frontend'] as const : []), ...(hasDatabase ? ['db'] as const : [])] as const).map(f => (
                    <button
                      key={f}
                      onClick={() => setLogFilter(f as typeof logFilter)}
                      className={cn(
                        'px-2 py-0.5 rounded text-[10px] font-medium transition-colors',
                        logFilter === f
                          ? 'bg-accent-cyan/15 text-accent-cyan'
                          : 'bg-white/[0.04] text-zinc-500 hover:text-zinc-300'
                      )}
                    >
                      {f === 'all' ? 'All' : f === 'backend' ? 'Backend' : f === 'frontend' ? 'Frontend' : 'Database'}
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

      {/* ── Environment Configuration Dialog ── */}
      {showEnvDialog && analysis && (
        <EnvConfigDialog
          analysis={analysis}
          onStart={handleStartWithEnvVars}
          onClose={() => setShowEnvDialog(false)}
        />
      )}
    </div>
  );
}

// ── Environment Configuration Dialog ────────────────────────────────────────

function EnvConfigDialog({
  analysis,
  onStart,
  onClose,
}: {
  analysis: PreviewAnalysis;
  onStart: (envVars: Record<string, string>) => void;
  onClose: () => void;
}) {
  const [values, setValues] = useState<Record<string, string>>(() => {
    const defaults: Record<string, string> = {};
    analysis.requiredEnvVars.forEach(v => {
      if (v.defaultValue) defaults[v.name] = v.defaultValue;
    });
    return defaults;
  });
  const [showSecrets, setShowSecrets] = useState<Record<string, boolean>>({});

  const dbInfo = analysis.databaseType ? DB_LABELS[analysis.databaseType] : null;
  // Only show third-party SECRET vars — internal config is auto-populated
  const secretVars = analysis.requiredEnvVars.filter(v => v.category === 'SECRET');

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="bg-zinc-900 border border-white/[0.08] rounded-xl shadow-2xl w-full max-w-lg max-h-[80vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-white/[0.06]">
          <h3 className="text-sm font-semibold text-zinc-100">Third-Party API Keys</h3>
          <button onClick={onClose} className="p-1 rounded hover:bg-white/[0.06] text-zinc-500 hover:text-zinc-300">
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* Scrollable content */}
        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-4">
          {/* Disclaimer */}
          <div className="flex items-start gap-2 p-3 rounded-lg bg-accent-amber/10 border border-accent-amber/20">
            <Shield className="h-4 w-4 text-accent-amber mt-0.5 flex-shrink-0" />
            <p className="text-xs text-accent-amber/90 leading-relaxed">
              These values are <strong>not stored, logged, or transmitted</strong> beyond this preview session.
              They exist only in the container&apos;s memory and are discarded when the preview stops.
            </p>
          </div>

          {/* Database info */}
          {dbInfo && (
            <div className="flex items-center gap-2 p-3 rounded-lg bg-accent-cyan/5 border border-accent-cyan/10">
              <Database className="h-4 w-4 text-accent-cyan" />
              <p className="text-xs text-accent-cyan">
                {dbInfo.emoji} <strong>{dbInfo.label}</strong> database will be auto-configured
                (fresh instance, no data persistence)
              </p>
            </div>
          )}

          {/* Third-party API keys only */}
          {secretVars.length > 0 && (
            <div className="space-y-2">
              <div>
                <p className="text-xs font-medium text-zinc-400 uppercase tracking-wide">External API Keys</p>
                <p className="text-[10px] text-zinc-600 mt-0.5">
                  Internal config (URLs, CORS, ports) is auto-configured. Only third-party keys are needed.
                </p>
              </div>
              {secretVars.map(v => (
                <div key={v.name} className="space-y-1">
                  <div className="flex items-center justify-between">
                    <label className="text-xs font-mono text-zinc-300">{v.name}</label>
                    <span className="text-[10px] text-zinc-600">from {v.source}</span>
                  </div>
                  <div className="relative">
                    <input
                      type={showSecrets[v.name] ? 'text' : 'password'}
                      value={values[v.name] ?? ''}
                      onChange={e => setValues(prev => ({ ...prev, [v.name]: e.target.value }))}
                      placeholder="Enter value (optional)..."
                      className="w-full px-3 py-2 pr-9 text-xs font-mono bg-black/30 border border-white/[0.08] rounded-lg text-zinc-200 placeholder-zinc-600 focus:outline-none focus:border-accent-cyan/40"
                    />
                    <button
                      type="button"
                      onClick={() => setShowSecrets(prev => ({ ...prev, [v.name]: !prev[v.name] }))}
                      className="absolute right-2 top-1/2 -translate-y-1/2 p-0.5 text-zinc-600 hover:text-zinc-400"
                    >
                      {showSecrets[v.name] ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}

          {secretVars.length === 0 && dbInfo && (
            <p className="text-xs text-zinc-500 text-center py-4">
              No additional configuration needed. Click Start Preview below.
            </p>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-2 px-5 py-3 border-t border-white/[0.06]">
          <button
            onClick={onClose}
            className="px-3 py-1.5 text-xs text-zinc-400 hover:text-zinc-200 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={() => {
              // Filter out empty values
              const nonEmpty = Object.fromEntries(
                Object.entries(values).filter(([, v]) => v.trim() !== '')
              );
              onStart(nonEmpty);
            }}
            className="flex items-center gap-1.5 px-4 py-1.5 rounded-lg text-xs font-medium bg-accent-emerald/15 text-accent-emerald hover:bg-accent-emerald/25 transition-all"
          >
            <Play className="h-3 w-3" />
            Start Preview
          </button>
        </div>
      </div>
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
  const [probe, setProbe] = useState<ApiDocsProbeResult | null>(null);
  const [probing, setProbing] = useState(true);

  // Probe via backend proxy (avoids CORS issues with direct browser fetch)
  useEffect(() => {
    let cancelled = false;
    setProbing(true);
    probeApiDocs(runId)
      .then(result => { if (!cancelled) setProbe(result); })
      .catch(() => { if (!cancelled) setProbe({ found: false, path: null, contentType: null }); })
      .finally(() => { if (!cancelled) setProbing(false); });
    return () => { cancelled = true; };
  }, [runId]);

  // Case 1: Found an HTML docs page (FastAPI /docs, Swagger UI) — iframe the preview URL
  if (probe?.found && probe.contentType === 'html' && probe.path) {
    return (
      <iframe
        src={url + probe.path}
        className="w-full h-full border-0"
        title="API Documentation"
        sandbox="allow-scripts allow-same-origin allow-forms"
      />
    );
  }

  // Case 2: Found a JSON spec on the preview — render Swagger UI pointed at it
  if (probe?.found && probe.contentType === 'json' && probe.path) {
    const specUrl = url + probe.path;
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
  <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"><\/script>
  <script>
    SwaggerUIBundle({
      url: '${specUrl}',
      dom_id: '#swagger-ui',
      presets: [SwaggerUIBundle.presets.apis],
      layout: 'BaseLayout',
      deepLinking: true,
      defaultModelsExpandDepth: 0,
    });
  <\/script>
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

  // Case 3: Use ExecuteSpec-side OpenAPI spec (generated by ApiTestSuiteAgent after merge)
  if (openapiSpec) {
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
  <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"><\/script>
  <script>
    SwaggerUIBundle({
      url: '/api/runs/${runId}/openapi.yaml',
      dom_id: '#swagger-ui',
      presets: [SwaggerUIBundle.presets.apis],
      layout: 'BaseLayout',
      deepLinking: true,
      defaultModelsExpandDepth: 0,
    });
  <\/script>
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

  // Case 4: Still probing or no docs found
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
        {probing ? (
          <p className="text-xs text-zinc-600 animate-pulse">Probing for API documentation...</p>
        ) : (
          <p className="text-xs text-zinc-600">
            No API documentation endpoint found. Try adding Swagger/OpenAPI to your project.
          </p>
        )}
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
