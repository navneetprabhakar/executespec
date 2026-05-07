/**
 * RunPreviewPane — full port of v2 `frontend/src/components/runs/PreviewTab.tsx`
 * (1,051 LOC source) into the v3 RunSidePanel surface.
 *
 * 2026-05-07 — Closes the user feedback gap "check the previous preview tab
 * implementation" + "do the full port." The v3 RunSidePanel previously had a
 * minimal ~150 LOC inline PreviewPane that handled Start / Stop / iframe and
 * not much else; the v2 surface had:
 *   - project-type chip + DB chip + pulsing status indicator
 *   - live MM:SS countdown with amber-warn under 2 min
 *   - URL link, auto `/docs` deeplink for backend-only project types
 *   - separate frontend / backend URLs for full-stack
 *   - Vite warmup probe (2 successful health checks before iframe)
 *   - failure messaging with collapsible Dockerfile + Docker build output +
 *     runtime logs so users can self-diagnose
 *   - Start / Retry / Refresh / Rebuild / Run Tests (Beta) / Console / Stop
 *   - env-var dialog for third-party SECRET vars (no internal config exposed)
 *   - polled live process logs with All / Backend / Frontend / DB filter pills
 *   - SSE-driven status refresh so the pane reacts in lockstep with the engine
 *   - mobile preview QR for React Native / Expo project types
 *   - backend-only OpenAPI viewer (Swagger UI from preview path → JSON spec
 *     → ExecuteSpec-side OpenAPI) with a probe-first fallback
 *
 * The port preserves every functional path verbatim (state shape, SSE handling,
 * API calls, polling cadence, warmup logic). Only changes:
 *   - Tailwind v2 token classes (`bg-[var(--gray-100)]`,
 *     `text-secondary`, `accent-{green,amber,red,blue}`) replaced with v3
 *     `var(--v3-*)` tokens via inline styles + small helper classes so the
 *     pane reads consistently with the rest of the v3 RunSidePanel.
 *   - ConsolePanel + MobilePreviewPanel imported from the existing v2 paths
 *     (still on the working tree — unchanged behaviour, no cohabitation
 *     concern since they're already token-aware).
 *   - EnvConfigDialog + BackendPreview kept inline so this single file is
 *     the whole surface — easier to read, easier to delete the inline v3
 *     stub that used to live in RunSidePanel.tsx.
 *
 * Backend contract: no changes. Reuses the same endpoints v2 used:
 *   GET  /runs/{id}/preview/status
 *   POST /runs/{id}/preview/start (optional envVars)
 *   POST /runs/{id}/preview/stop
 *   POST /runs/{id}/preview/rebuild
 *   GET  /runs/{id}/preview/logs?tailFromIndex=&filter=
 *   POST /runs/{id}/preview/analyze
 *   GET  /runs/{id}/preview/probe-api-docs
 *   GET  /runs/{id}/openapi.yaml (text)
 *   POST /runs/{id}/run-tests
 */
import {useCallback, useEffect, useMemo, useRef, useState, type CSSProperties} from 'react';
import {
  AlertTriangle,
  ChevronDown,
  Clock,
  Database,
  ExternalLink,
  Eye,
  EyeOff,
  Filter,
  Play,
  RefreshCw,
  Shield,
  Square,
  Terminal,
  TerminalSquare,
  TestTube2,
  X,
} from 'lucide-react';
import {
  analyzePreview,
  getOpenApiSpec,
  getPreviewLogs,
  getPreviewStatus,
  probeApiDocs,
  rebuildPreview,
  runTests,
  startPreview,
  stopPreview,
  type ApiDocsProbeResult,
  type LocalPreviewStatus,
  type PreviewAnalysis,
} from '@/lib/api';
import {useSseStore} from '@/stores/useSseStore';
import {errMsg} from '@/lib/utils';
import ConsolePanel from '@/components/runs/ConsolePanel';
import MobilePreviewPanel from '@/components/runs/MobilePreviewPanel';

const BACKEND_ONLY = new Set(['SPRING_BOOT', 'GRADLE', 'NODE_BACKEND', 'PYTHON']);

const TYPE_LABELS: Record<string, string> = {
  SPRING_BOOT: 'Spring Boot',
  GRADLE: 'Gradle',
  NODE: 'Node.js',
  NODE_BACKEND: 'Node.js · Backend',
  NODE_FRONTEND: 'Node.js · Frontend',
  PYTHON: 'Python',
  GO: 'Go',
  RUST: 'Rust',
  DOTNET: '.NET',
  STATIC: 'Static',
  FULLSTACK: 'Full-Stack',
  FULL_STACK: 'Full-Stack',
  REACT_NATIVE_EXPO: 'Mobile · React Native',
  UNKNOWN: 'Unknown',
};

const DB_LABELS: Record<string, {label: string; emoji: string}> = {
  POSTGRESQL: {label: 'PostgreSQL', emoji: '\u{1F418}'},
  MYSQL: {label: 'MySQL', emoji: '\u{1F42C}'},
  MONGODB: {label: 'MongoDB', emoji: '\u{1F343}'},
  REDIS: {label: 'Redis', emoji: '\u{26A1}'},
};

const FULL_STACK_TYPES = new Set(['FULL_STACK', 'FULLSTACK']);

/** Statuses that indicate the preview is starting up (intermediate phases). */
const STARTING_STATUSES = new Set(['ANALYZING', 'BUILDING', 'STARTING']);

const STATUS_MESSAGES: Record<string, string> = {
  ANALYZING: 'Analyzing project…',
  BUILDING: 'Building & starting containers…',
  STARTING: 'Waiting for application…',
};

function formatCountdown(seconds: number): string {
  if (seconds <= 0) return '0:00';
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

// ── shared inline styles ──────────────────────────────────────────────────
const chipStyle: CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '2px 8px',
  borderRadius: 4,
  fontSize: 11,
  fontFamily: 'var(--v3-font-mono)',
  background: 'var(--v3-surface)',
  color: 'var(--v3-text-muted)',
  border: '1px solid var(--v3-border)',
  whiteSpace: 'nowrap',
};

function buttonBase(tone: 'green' | 'amber' | 'red' | 'blue' | 'neutral'): CSSProperties {
  const map: Record<typeof tone, {fg: string; bg: string; bgHover: string}> = {
    green: {
      fg: 'var(--v3-success)',
      bg: 'color-mix(in srgb, var(--v3-success) 14%, transparent)',
      bgHover: 'color-mix(in srgb, var(--v3-success) 22%, transparent)',
    },
    amber: {
      fg: 'var(--v3-warning)',
      bg: 'color-mix(in srgb, var(--v3-warning) 14%, transparent)',
      bgHover: 'color-mix(in srgb, var(--v3-warning) 22%, transparent)',
    },
    red: {
      fg: 'var(--v3-error)',
      bg: 'color-mix(in srgb, var(--v3-error) 14%, transparent)',
      bgHover: 'color-mix(in srgb, var(--v3-error) 22%, transparent)',
    },
    blue: {
      fg: 'var(--v3-brand-hover)',
      bg: 'color-mix(in srgb, var(--v3-brand) 12%, transparent)',
      bgHover: 'color-mix(in srgb, var(--v3-brand) 22%, transparent)',
    },
    neutral: {
      fg: 'var(--v3-text-muted)',
      bg: 'var(--v3-surface)',
      bgHover: 'var(--v3-surface-elevated)',
    },
  };
  const c = map[tone];
  return {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 6,
    padding: '5px 12px',
    borderRadius: 4,
    fontSize: 11.5,
    fontWeight: 600,
    background: c.bg,
    color: c.fg,
    border: 'none',
    cursor: 'pointer',
    fontFamily: 'inherit',
    transition: 'background 0.15s',
    whiteSpace: 'nowrap',
    // CSSProperties doesn't expose --hover; sub-component handles hover via onMouseEnter/Leave
    ['--btn-bg-hover' as keyof CSSProperties]: c.bgHover,
  } as CSSProperties;
}

function ToneButton(props: {
  tone: 'green' | 'amber' | 'red' | 'blue' | 'neutral';
  onClick: () => void;
  disabled?: boolean;
  title?: string;
  children: React.ReactNode;
}) {
  const [hover, setHover] = useState(false);
  const base = buttonBase(props.tone);
  const hoverBg = (base as Record<string, string>)['--btn-bg-hover'];
  return (
    <button
      type='button'
      onClick={props.onClick}
      disabled={props.disabled}
      title={props.title}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        ...base,
        background: hover && !props.disabled ? hoverBg : base.background,
        opacity: props.disabled ? 0.4 : 1,
        cursor: props.disabled ? 'not-allowed' : 'pointer',
      }}
    >
      {props.children}
    </button>
  );
}

// ──────────────────────────────────────────────────────────────────────────
//                              Main component
// ──────────────────────────────────────────────────────────────────────────

export default function RunPreviewPane({runId}: {runId: string}) {
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
  const [warmupReady, setWarmupReady] = useState(false);
  const [testsTriggered, setTestsTriggered] = useState(false);
  const [showConsole, setShowConsole] = useState(false);
  const logPollRef = useRef<ReturnType<typeof setInterval>>();
  const countdownRef = useRef<ReturnType<typeof setInterval>>();
  const logEndRef = useRef<HTMLDivElement>(null);

  // Fetch status once on mount
  useEffect(() => {
    getPreviewStatus(runId)
      .then(setStatus)
      .catch(() => {});
    return () => {
      if (logPollRef.current) clearInterval(logPollRef.current);
    };
  }, [runId]);

  // SSE-driven status refresh — pull the latest preview event and refetch.
  const PREVIEW_EVENTS = useMemo(
    () =>
      new Set([
        'PREVIEW_ANALYZING',
        'PREVIEW_BUILDING',
        'PREVIEW_STARTING',
        'PREVIEW_READY',
        'PREVIEW_FAILED',
        'PREVIEW_TIMED_OUT',
        'PREVIEW_STOPPED',
      ]),
    [],
  );
  const sseEntries = useSseStore((s) => s.entries);
  const lastHandledRef = useRef<string | null>(null);
  useEffect(() => {
    for (let i = sseEntries.length - 1; i >= 0; i--) {
      const entry = sseEntries[i];
      if (PREVIEW_EVENTS.has(entry.type)) {
        if (entry.id === lastHandledRef.current) break;
        lastHandledRef.current = entry.id;
        getPreviewStatus(runId)
          .then(setStatus)
          .catch(() => {});
        break;
      }
    }
  }, [sseEntries, runId, PREVIEW_EVENTS]);

  // Reset Run Tests button state on test completion / report ready.
  const lastTestEventRef = useRef<string | null>(null);
  useEffect(() => {
    for (let i = sseEntries.length - 1; i >= 0; i--) {
      const entry = sseEntries[i];
      if (entry.type === 'TEST_EXECUTION_COMPLETED' || entry.type === 'TEST_REPORT_READY') {
        if (entry.id === lastTestEventRef.current) break;
        lastTestEventRef.current = entry.id;
        setTestsTriggered(false);
        break;
      }
    }
  }, [sseEntries]);

  // Countdown timer for running previews.
  const expiredFetchedRef = useRef(false);
  useEffect(() => {
    expiredFetchedRef.current = false;
  }, [status?.expiresAt]);
  useEffect(() => {
    if (status?.status === 'RUNNING' && status.expiresAt) {
      const updateCountdown = () => {
        const remaining = Math.max(
          0,
          // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
          Math.floor((new Date(status.expiresAt!).getTime() - Date.now()) / 1000),
        );
        setCountdown(remaining);
        if (remaining <= 0 && !expiredFetchedRef.current) {
          expiredFetchedRef.current = true;
          getPreviewStatus(runId)
            .then(setStatus)
            .catch(() => {});
          if (countdownRef.current) clearInterval(countdownRef.current);
        }
      };
      updateCountdown();
      countdownRef.current = setInterval(updateCountdown, 1000);
    }
    return () => {
      if (countdownRef.current) clearInterval(countdownRef.current);
    };
  }, [status?.status, status?.expiresAt, runId]);

  // Determine if full-stack for log filtering.
  const isFullStack = status?.projectType ? FULL_STACK_TYPES.has(status.projectType) : false;
  const hasDatabase = !!status?.databaseType;
  const effectiveFilter = isFullStack ? logFilter : logFilter === 'db' ? 'db' : 'all';

  // Poll logs while in any starting phase or RUNNING — fetch ALL logs from startup.
  useEffect(() => {
    const isActive = status?.status
      ? STARTING_STATUSES.has(status.status) || status.status === 'RUNNING'
      : false;
    if (isActive) {
      const fetchLogs = () => {
        if (document.hidden) return;
        getPreviewLogs(runId, 0, effectiveFilter)
          .then((r) => setLogs(r.lines))
          .catch(() => {});
      };
      fetchLogs();
      logPollRef.current = setInterval(fetchLogs, 10000);
    }
    return () => {
      if (logPollRef.current) clearInterval(logPollRef.current);
    };
  }, [status?.status, runId, effectiveFilter]);

  // Fetch OpenAPI spec for backend-only projects (used as a Swagger UI source
  // when the running app doesn't expose a /docs endpoint).
  useEffect(() => {
    if (
      status?.status === 'RUNNING' &&
      status.projectType &&
      BACKEND_ONLY.has(status.projectType)
    ) {
      getOpenApiSpec(runId)
        .then(setOpenapiSpec)
        .catch(() => setOpenapiSpec(null));
    }
  }, [status?.status, status?.projectType, runId]);

  // Auto-scroll logs.
  useEffect(() => {
    logEndRef.current?.scrollIntoView({behavior: 'smooth'});
  }, [logs]);

  const handleStart = useCallback(async () => {
    setAnalyzing(true);
    try {
      const result = await analyzePreview(runId);
      setAnalysis(result);
      const thirdPartySecrets = result.requiredEnvVars.filter(
        (v) => v.category === 'SECRET' && !v.defaultValue,
      );
      if (thirdPartySecrets.length > 0) {
        setShowEnvDialog(true);
        setAnalyzing(false);
        return;
      }
      setAnalyzing(false);
      setLoading(true);
      const s = await startPreview(runId);
      setStatus(s);
    } catch (e) {
      setStatus((prev) => (prev ? {...prev, status: 'FAILED', errorMessage: errMsg(e)} : null));
    } finally {
      setLoading(false);
      setAnalyzing(false);
    }
  }, [runId]);

  const handleStartWithEnvVars = useCallback(
    async (envVars: Record<string, string>) => {
      setShowEnvDialog(false);
      setLoading(true);
      try {
        const s = await startPreview(runId, envVars);
        setStatus(s);
      } catch (e) {
        setStatus((prev) => (prev ? {...prev, status: 'FAILED', errorMessage: errMsg(e)} : null));
      } finally {
        setLoading(false);
      }
    },
    [runId],
  );

  const handleStop = useCallback(async () => {
    setLoading(true);
    try {
      await stopPreview(runId);
      const s = await getPreviewStatus(runId);
      setStatus(s);
    } catch {
      // swallow — status fetch handles its own state
    } finally {
      setLoading(false);
    }
  }, [runId]);

  const handleRefresh = useCallback(async () => {
    const s = await getPreviewStatus(runId).catch(() => null);
    if (s) setStatus(s);
    const l = await getPreviewLogs(runId, 0, effectiveFilter).catch(() => null);
    if (l) setLogs(l.lines);
  }, [runId, effectiveFilter]);

  const handleRebuild = useCallback(async () => {
    setLoading(true);
    try {
      const s = await rebuildPreview(runId);
      setStatus(s);
    } catch (e) {
      setStatus((prev) => (prev ? {...prev, status: 'FAILED', errorMessage: errMsg(e)} : null));
    } finally {
      setLoading(false);
    }
  }, [runId]);

  const handleRunTests = useCallback(async () => {
    setTestsTriggered(true);
    try {
      await runTests(runId);
    } catch {
      setTestsTriggered(false);
    }
  }, [runId]);

  const isIdle = !status || status.status === 'IDLE' || status.status === 'STOPPED';
  const isStartingPhase = status?.status ? STARTING_STATUSES.has(status.status) : false;
  const isRunning = status?.status === 'RUNNING';
  const isFailed = status?.status === 'FAILED';
  const isTimedOut = status?.status === 'TIMED_OUT';
  const projectType = status?.projectType ?? 'UNKNOWN';
  const isBackendOnly = BACKEND_ONLY.has(projectType);

  // Vite warmup: poll the preview URL with no-cors fetch until 2 successful
  // non-throwing responses, then mount the iframe. Avoids a flash of the
  // Cloud-Run cold-start error page on Node-frontend / full-stack runs.
  const VITE_TYPES = useMemo(() => new Set(['NODE_FRONTEND', 'FULL_STACK', 'FULLSTACK']), []);
  const needsWarmup = status?.projectType ? VITE_TYPES.has(status.projectType) : true;
  useEffect(() => {
    if (!isRunning || !status?.url || isBackendOnly) {
      setWarmupReady(false);
      return;
    }
    if (!needsWarmup) {
      setWarmupReady(true);
      return;
    }
    setWarmupReady(false);
    let cancelled = false;
    let successCount = 0;
    let attempt = 0;
    const maxAttempts = 15; // 15 × 2s = 30s max
    const poll = async () => {
      while (!cancelled && attempt < maxAttempts) {
        attempt++;
        try {
          // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
          await fetch(status.url!, {mode: 'no-cors'});
          successCount++;
          if (successCount >= 2) {
            if (!cancelled) setWarmupReady(true);
            return;
          }
        } catch {
          successCount = 0;
        }
        await new Promise((r) => setTimeout(r, 2000));
      }
      if (!cancelled) setWarmupReady(true);
    };
    poll();
    return () => {
      cancelled = true;
    };
  }, [isRunning, status?.url, isBackendOnly, needsWarmup]);

  const hasLogs = isStartingPhase || isRunning || isFailed || isTimedOut;
  const showLogFilter = (isFullStack || hasDatabase) && hasLogs;
  const dbInfo = status?.databaseType ? DB_LABELS[status.databaseType] : null;
  const previewCountInfo =
    status?.maxPreviewStarts && status.maxPreviewStarts > 0
      ? `${status.previewStartCount ?? 0} of ${status.maxPreviewStarts} previews used`
      : null;
  const countdownWarning = countdown > 0 && countdown <= 120;

  return (
    <div style={{display: 'flex', flexDirection: 'column', height: '100%'}}>
      {/* ── Control bar ───────────────────────────────────────────── */}
      <div
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'center',
          gap: 8,
          padding: '10px 12px',
          borderBottom: '1px solid var(--v3-border)',
          flexShrink: 0,
        }}
      >
        <div style={{flex: 1, minWidth: 0}}>
          <div style={{display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap'}}>
            <span style={chipStyle}>{TYPE_LABELS[projectType] ?? projectType}</span>
            {dbInfo && (
              <span style={chipStyle}>
                <Database className='h-3 w-3' />
                {dbInfo.label}
              </span>
            )}
            {isRunning && (
              <span
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 6,
                  fontSize: 11.5,
                  color: 'var(--v3-success)',
                }}
              >
                <span style={{position: 'relative', width: 6, height: 6, display: 'inline-flex'}}>
                  <span
                    style={{
                      position: 'absolute',
                      inset: 0,
                      borderRadius: 999,
                      background: 'var(--v3-success)',
                      opacity: 0.65,
                      animation: 'v3PulseDot 1.4s ease-in-out infinite',
                    }}
                  />
                  <span
                    style={{
                      position: 'relative',
                      width: 6,
                      height: 6,
                      borderRadius: 999,
                      background: 'var(--v3-success)',
                    }}
                  />
                </span>
                Running
              </span>
            )}
            {isRunning && countdown > 0 && (
              <span
                className='v3-mono'
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 4,
                  fontSize: 11,
                  color: countdownWarning ? 'var(--v3-warning)' : 'var(--v3-text-muted)',
                }}
              >
                <Clock className='h-3 w-3' />
                {formatCountdown(countdown)}
              </span>
            )}
            {isStartingPhase && (
              <span
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 6,
                  fontSize: 11.5,
                  color: 'var(--v3-warning)',
                  animation: 'v3PulseDot 2s ease-in-out infinite',
                }}
              >
                <span
                  style={{
                    width: 12,
                    height: 12,
                    border: '2px solid color-mix(in srgb, var(--v3-warning) 30%, transparent)',
                    borderTopColor: 'var(--v3-warning)',
                    borderRadius: 999,
                    animation: 'v3Spin 0.7s linear infinite',
                  }}
                />
                {(status?.status && STATUS_MESSAGES[status.status]) || 'Starting…'}
              </span>
            )}
            {isFailed && (
              <span style={{fontSize: 11.5, color: 'var(--v3-error)'}}>Failed</span>
            )}
            {isTimedOut && (
              <span
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 4,
                  fontSize: 11.5,
                  color: 'var(--v3-error)',
                }}
              >
                <AlertTriangle className='h-3 w-3' />
                Timed out
              </span>
            )}
            {previewCountInfo && (
              <span style={{fontSize: 10, color: 'var(--v3-text-subtle)'}}>
                {previewCountInfo}
              </span>
            )}
          </div>
          {isRunning && status?.url && (
            <a
              href={status.url}
              target='_blank'
              rel='noopener noreferrer'
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                fontSize: 11.5,
                color: 'var(--v3-success)',
                textDecoration: 'none',
                marginTop: 4,
              }}
            >
              <ExternalLink className='h-3 w-3' />
              {status.url}
            </a>
          )}
          {isRunning && status?.url && isBackendOnly && (
            <a
              href={`${status.url.replace(/\/$/, '')}/docs`}
              target='_blank'
              rel='noopener noreferrer'
              title='Open Swagger UI / OpenAPI docs'
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                fontSize: 11.5,
                color: 'var(--v3-brand-hover)',
                textDecoration: 'none',
                marginTop: 2,
              }}
            >
              <ExternalLink className='h-3 w-3' />
              View API docs
            </a>
          )}
          {isRunning && status?.backendUrl && status?.frontendUrl && (
            <div
              style={{
                display: 'flex',
                gap: 12,
                marginTop: 2,
                fontSize: 11,
                color: 'var(--v3-text-subtle)',
              }}
            >
              <span>
                Backend: <span style={{color: 'var(--v3-text-muted)'}}>{status.backendUrl}</span>
              </span>
              <span>
                Frontend: <span style={{color: 'var(--v3-text-muted)'}}>{status.frontendUrl}</span>
              </span>
            </div>
          )}
        </div>

        <div style={{display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0, flexWrap: 'wrap'}}>
          {!isStartingPhase && (
            <button
              type='button'
              onClick={handleRefresh}
              title='Refresh status'
              style={{
                background: 'transparent',
                border: 0,
                color: 'var(--v3-text-subtle)',
                cursor: 'pointer',
                padding: 6,
                borderRadius: 4,
              }}
            >
              <RefreshCw className='h-3.5 w-3.5' />
            </button>
          )}
          {!isIdle && (
            <ToneButton
              tone='amber'
              onClick={handleRebuild}
              disabled={loading}
              title='Re-detect project type and rebuild from scratch'
            >
              <RefreshCw className='h-3 w-3' />
              Rebuild
            </ToneButton>
          )}
          {(isIdle || isFailed || isTimedOut) && (
            <ToneButton tone='green' onClick={handleStart} disabled={loading || analyzing}>
              {loading || analyzing ? (
                <span
                  style={{
                    width: 12,
                    height: 12,
                    border: '2px solid color-mix(in srgb, var(--v3-success) 30%, transparent)',
                    borderTopColor: 'var(--v3-success)',
                    borderRadius: 999,
                    animation: 'v3Spin 0.7s linear infinite',
                  }}
                />
              ) : (
                <Play className='h-3 w-3' />
              )}
              {analyzing ? 'Analyzing…' : isFailed || isTimedOut ? 'Retry' : 'Start preview'}
            </ToneButton>
          )}
          {isRunning && (
            <ToneButton tone='blue' onClick={handleRunTests} disabled={testsTriggered}>
              {testsTriggered ? (
                <span
                  style={{
                    width: 12,
                    height: 12,
                    border: '2px solid color-mix(in srgb, var(--v3-brand) 30%, transparent)',
                    borderTopColor: 'var(--v3-brand)',
                    borderRadius: 999,
                    animation: 'v3Spin 0.7s linear infinite',
                  }}
                />
              ) : (
                <TestTube2 className='h-3 w-3' />
              )}
              {testsTriggered ? 'Tests running…' : 'Run tests'}
              <span
                style={{
                  fontSize: 9,
                  fontWeight: 700,
                  textTransform: 'uppercase',
                  letterSpacing: 0.6,
                  opacity: 0.7,
                }}
              >
                Beta
              </span>
            </ToneButton>
          )}
          {isRunning && (
            <ToneButton
              tone={showConsole ? 'green' : 'neutral'}
              onClick={() => setShowConsole((c) => !c)}
              title='Toggle container console'
            >
              <TerminalSquare className='h-3 w-3' />
              Console
            </ToneButton>
          )}
          {(isRunning || isStartingPhase) && (
            <ToneButton tone='red' onClick={handleStop} disabled={loading}>
              <Square className='h-3 w-3' />
              Stop
            </ToneButton>
          )}
        </div>
      </div>

      {/* ── Main content ─────────────────────────────────────────── */}
      <div style={{flex: 1, overflow: 'hidden', minHeight: 0}}>
        {isRunning && status?.url ? (
          projectType === 'REACT_NATIVE_EXPO' ? (
            <MobilePreviewPanel url={status.url} />
          ) : isBackendOnly ? (
            <BackendPreview runId={runId} url={status.url} openapiSpec={openapiSpec} />
          ) : !warmupReady ? (
            <CenteredMessage
              spinnerTone='amber'
              title='Warming up app…'
              subtitle='This preview will automatically shut down in 5 minutes'
            />
          ) : (
            <iframe
              src={status.url}
              style={{width: '100%', height: '100%', border: 0, background: 'white'}}
              title='App preview'
              sandbox='allow-scripts allow-same-origin allow-forms allow-popups'
            />
          )
        ) : isStartingPhase ? (
          <CenteredMessage
            spinnerTone='amber'
            title={(status?.status && STATUS_MESSAGES[status.status]) || 'Starting…'}
            subtitle='This preview will automatically shut down in 5 minutes'
            extra={
              dbInfo ? (
                <p style={{fontSize: 11, color: 'var(--v3-text-muted)', margin: 0}}>
                  {dbInfo.emoji} {dbInfo.label} database auto-configured
                </p>
              ) : null
            }
          />
        ) : isFailed || isTimedOut ? (
          <FailureView status={status} logs={logs} timedOut={isTimedOut} />
        ) : (
          <CenteredMessage
            title='Preview not started'
            subtitle={
              <>
                Click <strong style={{color: 'var(--v3-text)'}}>Start preview</strong> to launch
                the {TYPE_LABELS[projectType]?.toLowerCase() ?? 'application'} dev server. Database
                and dependencies will be auto-detected.
              </>
            }
            extra={
              projectType === 'UNKNOWN' ? (
                <p style={{fontSize: 11, color: 'var(--v3-text-subtle)', margin: 0}}>
                  No recognised project files found. Preview may not be available for this run.
                </p>
              ) : null
            }
          />
        )}
      </div>

      {/* ── Console panel ─────────────────────────────────────────── */}
      {isRunning && showConsole && (
        <div
          style={{
            borderTop: '1px solid var(--v3-border)',
            padding: 12,
            flexShrink: 0,
          }}
        >
          <ConsolePanel runId={runId} />
        </div>
      )}

      {/* ── Process logs (collapsible) ────────────────────────────── */}
      {hasLogs && (
        <div
          style={{
            borderTop: '1px solid var(--v3-border)',
            flexShrink: 0,
            background: 'var(--v3-bg)',
          }}
        >
          <button
            type='button'
            onClick={() => setLogsExpanded(!logsExpanded)}
            style={{
              width: '100%',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              padding: '8px 12px',
              fontSize: 11.5,
              color: 'var(--v3-text-muted)',
              background: 'transparent',
              border: 0,
              cursor: 'pointer',
              fontFamily: 'inherit',
            }}
          >
            <span style={{display: 'inline-flex', alignItems: 'center', gap: 6}}>
              <Terminal className='h-3 w-3' />
              Process logs {logs.length > 0 && `(${logs.length} lines)`}
            </span>
            <ChevronDown
              className='h-3 w-3'
              style={{transform: logsExpanded ? 'rotate(180deg)' : 'none', transition: 'transform 0.15s'}}
            />
          </button>
          {logsExpanded && (
            <>
              {showLogFilter && (
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 6,
                    padding: '0 12px 8px',
                    flexWrap: 'wrap',
                  }}
                >
                  <Filter className='h-3 w-3' style={{color: 'var(--v3-text-subtle)'}} />
                  {(
                    [
                      'all' as const,
                      ...(isFullStack ? (['backend', 'frontend'] as const) : []),
                      ...(hasDatabase ? (['db'] as const) : []),
                    ]
                  ).map((f) => {
                    const active = logFilter === f;
                    return (
                      <button
                        key={f}
                        type='button'
                        onClick={() => setLogFilter(f as typeof logFilter)}
                        style={{
                          padding: '2px 8px',
                          fontSize: 10,
                          fontWeight: 600,
                          borderRadius: 4,
                          background: active
                            ? 'color-mix(in srgb, var(--v3-brand) 18%, transparent)'
                            : 'var(--v3-surface)',
                          color: active ? 'var(--v3-brand-hover)' : 'var(--v3-text-subtle)',
                          border: 0,
                          cursor: 'pointer',
                          fontFamily: 'inherit',
                        }}
                      >
                        {f === 'all'
                          ? 'All'
                          : f === 'backend'
                            ? 'Backend'
                            : f === 'frontend'
                              ? 'Frontend'
                              : 'Database'}
                      </button>
                    );
                  })}
                </div>
              )}
              <div
                style={{
                  maxHeight: 192,
                  overflowY: 'auto',
                  padding: '0 12px 12px',
                }}
              >
                <pre
                  className='v3-mono'
                  style={{
                    margin: 0,
                    fontSize: 10.5,
                    color: 'var(--v3-text-subtle)',
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-all',
                    lineHeight: 1.5,
                  }}
                >
                  {logs.length > 0 ? logs.join('\n') : 'No output yet…'}
                </pre>
                <div ref={logEndRef} />
              </div>
            </>
          )}
        </div>
      )}

      {/* ── Env-var dialog ────────────────────────────────────────── */}
      {showEnvDialog && analysis && (
        <EnvConfigDialog
          analysis={analysis}
          onStart={handleStartWithEnvVars}
          onClose={() => setShowEnvDialog(false)}
        />
      )}

      {/* Inline keyframes (the v3-pulse-dot lives in RunSidePanel; spinner is local). */}
      <style>{`@keyframes v3Spin { from {transform: rotate(0)} to {transform: rotate(360deg)} }`}</style>
    </div>
  );
}

// ── Centered placeholder ───────────────────────────────────────────────────

function CenteredMessage({
  title,
  subtitle,
  extra,
  spinnerTone,
}: {
  title: React.ReactNode;
  subtitle?: React.ReactNode;
  extra?: React.ReactNode;
  spinnerTone?: 'amber';
}) {
  return (
    <div
      style={{
        height: '100%',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 32,
      }}
    >
      <div style={{textAlign: 'center', display: 'flex', flexDirection: 'column', gap: 8, maxWidth: 360}}>
        {spinnerTone === 'amber' && (
          <span
            style={{
              alignSelf: 'center',
              width: 24,
              height: 24,
              border: '2px solid color-mix(in srgb, var(--v3-warning) 30%, transparent)',
              borderTopColor: 'var(--v3-warning)',
              borderRadius: 999,
              animation: 'v3Spin 0.7s linear infinite',
            }}
          />
        )}
        <p style={{fontSize: 13, color: 'var(--v3-text-muted)', margin: 0}}>{title}</p>
        {subtitle && (
          <p style={{fontSize: 11.5, color: 'var(--v3-text-subtle)', margin: 0, lineHeight: 1.55}}>
            {subtitle}
          </p>
        )}
        {extra}
      </div>
    </div>
  );
}

// ── Failure / timed-out view ───────────────────────────────────────────────

function FailureView({
  status,
  logs,
  timedOut,
}: {
  status: LocalPreviewStatus | null;
  logs: string[];
  timedOut: boolean;
}) {
  const tone = timedOut ? 'var(--v3-warning)' : 'var(--v3-error)';
  const toneSoft = timedOut
    ? 'color-mix(in srgb, var(--v3-warning) 80%, var(--v3-text))'
    : 'color-mix(in srgb, var(--v3-error) 80%, var(--v3-text))';
  const bgTone = timedOut
    ? 'color-mix(in srgb, var(--v3-warning) 8%, transparent)'
    : 'color-mix(in srgb, var(--v3-error) 8%, transparent)';
  const bdTone = timedOut
    ? 'color-mix(in srgb, var(--v3-warning) 30%, transparent)'
    : 'color-mix(in srgb, var(--v3-error) 30%, transparent)';

  return (
    <div
      style={{
        padding: 14,
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
        overflow: 'auto',
        height: '100%',
      }}
    >
      <div
        style={{
          padding: 12,
          background: bgTone,
          border: `1px solid ${bdTone}`,
          borderRadius: 6,
        }}
      >
        <p style={{fontSize: 13, fontWeight: 600, color: tone, margin: 0}}>
          {timedOut ? 'Preview startup timed out' : 'Preview failed to start'}
        </p>
        <p
          style={{
            fontSize: 11.5,
            color: toneSoft,
            margin: '4px 0 0',
            wordBreak: 'break-all',
            whiteSpace: 'pre-wrap',
          }}
        >
          {status?.errorMessage ?? 'Unknown error'}
        </p>
      </div>
      {status?.dockerfile && (
        <details>
          <summary
            style={{
              fontSize: 11.5,
              fontWeight: 500,
              color: 'var(--v3-text-subtle)',
              cursor: 'pointer',
            }}
          >
            Generated Dockerfile
          </summary>
          <pre
            className='v3-mono'
            style={{
              marginTop: 6,
              padding: 12,
              background: 'var(--v3-surface)',
              borderRadius: 4,
              fontSize: 10.5,
              color: 'var(--v3-text-muted)',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-all',
              maxHeight: 192,
              overflowY: 'auto',
              lineHeight: 1.55,
            }}
          >
            {status.dockerfile}
          </pre>
        </details>
      )}
      {status?.buildOutput && (
        <details>
          <summary
            style={{
              fontSize: 11.5,
              fontWeight: 500,
              color: 'var(--v3-text-subtle)',
              cursor: 'pointer',
            }}
          >
            Docker build output
          </summary>
          <pre
            className='v3-mono'
            style={{
              marginTop: 6,
              padding: 12,
              background: 'var(--v3-surface)',
              borderRadius: 4,
              fontSize: 10.5,
              color: 'var(--v3-text-muted)',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-all',
              maxHeight: 192,
              overflowY: 'auto',
              lineHeight: 1.55,
            }}
          >
            {status.buildOutput}
          </pre>
        </details>
      )}
      {logs.length > 0 && (
        <div style={{display: 'flex', flexDirection: 'column', gap: 4}}>
          <p style={{fontSize: 11.5, fontWeight: 500, color: 'var(--v3-text-subtle)', margin: 0}}>
            Runtime logs ({logs.length} lines):
          </p>
          <pre
            className='v3-mono'
            style={{
              padding: 12,
              background: 'var(--v3-surface)',
              borderRadius: 4,
              fontSize: 10.5,
              color: 'var(--v3-text-muted)',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-all',
              maxHeight: 256,
              overflowY: 'auto',
              lineHeight: 1.55,
              margin: 0,
            }}
          >
            {logs.join('\n')}
          </pre>
        </div>
      )}
    </div>
  );
}

// ── Env-config dialog ──────────────────────────────────────────────────────

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
    analysis.requiredEnvVars.forEach((v) => {
      if (v.defaultValue) defaults[v.name] = v.defaultValue;
    });
    return defaults;
  });
  const [showSecrets, setShowSecrets] = useState<Record<string, boolean>>({});

  const dbInfo = analysis.databaseType ? DB_LABELS[analysis.databaseType] : null;
  const secretVars = analysis.requiredEnvVars.filter((v) => v.category === 'SECRET');

  return (
    <div
      role='dialog'
      aria-modal='true'
      aria-label='Third-party API keys'
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 50,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'rgba(5,7,10,0.78)',
        backdropFilter: 'blur(6px)',
        WebkitBackdropFilter: 'blur(6px)',
        padding: 16,
      }}
    >
      <div
        style={{
          background: 'var(--v3-surface)',
          border: '1px solid var(--v3-border)',
          borderRadius: 10,
          width: '100%',
          maxWidth: 520,
          maxHeight: '80vh',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
          boxShadow: '0 24px 64px rgba(0,0,0,0.5)',
        }}
      >
        {/* Header */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '12px 18px',
            borderBottom: '1px solid var(--v3-border)',
          }}
        >
          <h3 style={{margin: 0, fontSize: 13.5, fontWeight: 600, color: 'var(--v3-text)'}}>
            Third-party API keys
          </h3>
          <button
            type='button'
            onClick={onClose}
            style={{
              padding: 4,
              borderRadius: 4,
              background: 'transparent',
              border: 0,
              color: 'var(--v3-text-subtle)',
              cursor: 'pointer',
            }}
          >
            <X className='h-4 w-4' />
          </button>
        </div>

        {/* Body */}
        <div
          style={{
            flex: 1,
            overflowY: 'auto',
            padding: '14px 18px',
            display: 'flex',
            flexDirection: 'column',
            gap: 14,
          }}
        >
          <div
            style={{
              display: 'flex',
              alignItems: 'flex-start',
              gap: 8,
              padding: 12,
              borderRadius: 6,
              background: 'color-mix(in srgb, var(--v3-warning) 8%, transparent)',
              border: '1px solid color-mix(in srgb, var(--v3-warning) 22%, transparent)',
            }}
          >
            <Shield className='h-4 w-4' style={{color: 'var(--v3-warning)', marginTop: 2, flexShrink: 0}} />
            <p style={{fontSize: 11.5, color: 'var(--v3-warning)', lineHeight: 1.55, margin: 0}}>
              These values are <strong>not stored, logged, or transmitted</strong> beyond this
              preview session. They exist only in the container's memory and are discarded when
              the preview stops.
            </p>
          </div>

          {dbInfo && (
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                padding: 12,
                borderRadius: 6,
                background: 'var(--v3-bg)',
                border: '1px solid color-mix(in srgb, var(--v3-brand) 14%, transparent)',
              }}
            >
              <Database className='h-4 w-4' style={{color: 'var(--v3-text-muted)'}} />
              <p style={{fontSize: 11.5, color: 'var(--v3-text-muted)', margin: 0}}>
                {dbInfo.emoji} <strong>{dbInfo.label}</strong> database will be auto-configured
                (fresh instance, no data persistence)
              </p>
            </div>
          )}

          {secretVars.length > 0 && (
            <div style={{display: 'flex', flexDirection: 'column', gap: 8}}>
              <div>
                <p
                  style={{
                    fontSize: 10.5,
                    fontWeight: 600,
                    color: 'var(--v3-text-muted)',
                    textTransform: 'uppercase',
                    letterSpacing: 0.6,
                    margin: 0,
                  }}
                >
                  External API keys
                </p>
                <p style={{fontSize: 10.5, color: 'var(--v3-text-subtle)', margin: '2px 0 0'}}>
                  Internal config (URLs, CORS, ports) is auto-configured. Only third-party keys
                  are needed.
                </p>
              </div>
              {secretVars.map((v) => (
                <div key={v.name} style={{display: 'flex', flexDirection: 'column', gap: 4}}>
                  <div style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between'}}>
                    <label
                      className='v3-mono'
                      style={{fontSize: 11, color: 'var(--v3-text-muted)'}}
                    >
                      {v.name}
                    </label>
                    <span style={{fontSize: 10, color: 'var(--v3-text-subtle)'}}>
                      from {v.source}
                    </span>
                  </div>
                  <div style={{position: 'relative'}}>
                    <input
                      type={showSecrets[v.name] ? 'text' : 'password'}
                      value={values[v.name] ?? ''}
                      onChange={(e) =>
                        setValues((prev) => ({...prev, [v.name]: e.target.value}))
                      }
                      placeholder='Enter value (optional)…'
                      className='v3-mono'
                      style={{
                        width: '100%',
                        padding: '7px 32px 7px 12px',
                        fontSize: 11.5,
                        background: 'var(--v3-bg)',
                        border: '1px solid var(--v3-border)',
                        borderRadius: 4,
                        color: 'var(--v3-text)',
                        outline: 'none',
                      }}
                    />
                    <button
                      type='button'
                      onClick={() =>
                        setShowSecrets((prev) => ({...prev, [v.name]: !prev[v.name]}))
                      }
                      style={{
                        position: 'absolute',
                        right: 6,
                        top: '50%',
                        transform: 'translateY(-50%)',
                        padding: 2,
                        background: 'transparent',
                        border: 0,
                        color: 'var(--v3-text-subtle)',
                        cursor: 'pointer',
                      }}
                    >
                      {showSecrets[v.name] ? (
                        <EyeOff className='h-3.5 w-3.5' />
                      ) : (
                        <Eye className='h-3.5 w-3.5' />
                      )}
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}

          {secretVars.length === 0 && dbInfo && (
            <p
              style={{
                fontSize: 11.5,
                color: 'var(--v3-text-subtle)',
                textAlign: 'center',
                padding: '14px 0',
                margin: 0,
              }}
            >
              No additional configuration needed. Click Start preview below.
            </p>
          )}
        </div>

        {/* Footer */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            gap: 8,
            padding: '10px 18px',
            borderTop: '1px solid var(--v3-border)',
          }}
        >
          <button
            type='button'
            onClick={onClose}
            style={{
              padding: '6px 12px',
              fontSize: 11.5,
              background: 'transparent',
              color: 'var(--v3-text-muted)',
              border: 0,
              cursor: 'pointer',
              fontFamily: 'inherit',
            }}
          >
            Cancel
          </button>
          <ToneButton
            tone='green'
            onClick={() => {
              const nonEmpty = Object.fromEntries(
                Object.entries(values).filter(([, v]) => v.trim() !== ''),
              );
              onStart(nonEmpty);
            }}
          >
            <Play className='h-3 w-3' />
            Start preview
          </ToneButton>
        </div>
      </div>
    </div>
  );
}

// ── Backend-only preview (Swagger UI / OpenAPI) ───────────────────────────

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

  useEffect(() => {
    let cancelled = false;
    setProbing(true);
    probeApiDocs(runId)
      .then((result) => {
        if (!cancelled) setProbe(result);
      })
      .catch(() => {
        if (!cancelled) setProbe({found: false, path: null, contentType: null});
      })
      .finally(() => {
        if (!cancelled) setProbing(false);
      });
    return () => {
      cancelled = true;
    };
  }, [runId]);

  // Case 1: HTML docs page (FastAPI /docs, Swagger UI) — iframe directly.
  if (probe?.found && probe.contentType === 'html' && probe.path) {
    return (
      <iframe
        src={url + probe.path}
        style={{width: '100%', height: '100%', border: 0}}
        title='API Documentation'
        sandbox='allow-scripts allow-same-origin allow-forms'
      />
    );
  }

  // Case 2: JSON spec on the preview — render Swagger UI pointed at it.
  if (probe?.found && probe.contentType === 'json' && probe.path) {
    const specUrl = url + probe.path;
    return (
      <iframe
        srcDoc={swaggerHtml(specUrl)}
        style={{width: '100%', height: '100%', border: 0}}
        title='API Documentation'
        sandbox='allow-scripts allow-same-origin'
      />
    );
  }

  // Case 3: Use ExecuteSpec-side OpenAPI spec.
  if (openapiSpec) {
    return (
      <iframe
        srcDoc={swaggerHtml(`/api/runs/${runId}/openapi.yaml`)}
        style={{width: '100%', height: '100%', border: 0}}
        title='API Documentation'
        sandbox='allow-scripts allow-same-origin'
      />
    );
  }

  // Case 4: Probing or no docs found.
  return (
    <div
      style={{
        height: '100%',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 32,
      }}
    >
      <div style={{textAlign: 'center', display: 'flex', flexDirection: 'column', gap: 10}}>
        <div style={{display: 'inline-flex', alignItems: 'center', gap: 8, alignSelf: 'center'}}>
          <span style={{position: 'relative', width: 10, height: 10, display: 'inline-flex'}}>
            <span
              style={{
                position: 'absolute',
                inset: 0,
                borderRadius: 999,
                background: 'var(--v3-success)',
                opacity: 0.65,
                animation: 'v3PulseDot 1.4s ease-in-out infinite',
              }}
            />
            <span
              style={{
                position: 'relative',
                width: 10,
                height: 10,
                borderRadius: 999,
                background: 'var(--v3-success)',
              }}
            />
          </span>
          <span style={{fontSize: 13, fontWeight: 600, color: 'var(--v3-success)'}}>
            Service running
          </span>
        </div>
        <p style={{fontSize: 11.5, color: 'var(--v3-text-subtle)', margin: 0}}>
          Backend API is live at{' '}
          <code className='v3-mono' style={{color: 'var(--v3-text-muted)'}}>
            {url}
          </code>
        </p>
        {probing ? (
          <p style={{fontSize: 11.5, color: 'var(--v3-text-subtle)', margin: 0}}>
            Probing for API documentation…
          </p>
        ) : (
          <p style={{fontSize: 11.5, color: 'var(--v3-text-subtle)', margin: 0}}>
            No API documentation endpoint found. Try adding Swagger / OpenAPI to your project.
          </p>
        )}
        <a
          href={url}
          target='_blank'
          rel='noopener noreferrer'
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 4,
            fontSize: 11.5,
            color: 'var(--v3-text-muted)',
            textDecoration: 'none',
            alignSelf: 'center',
          }}
        >
          <ExternalLink className='h-3 w-3' />
          Open in new tab
        </a>
      </div>
    </div>
  );
}

function swaggerHtml(specUrl: string): string {
  return `<!DOCTYPE html>
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
}
