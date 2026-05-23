import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import * as api from '@/lib/api';
import {Markdown} from '@/signal/components/Markdown';
import {Button, StatusPill} from '@/signal/components/Primitives';
import {IdeBadge} from '@/ide/IdeKit';

/**
 * CodeReviewPanel — the dedicated whole-repo Code Review surface, shared across
 * both chromes via the `variant` prop. One scan target = one (project, repo).
 *
 * Lifecycle:
 *  1. On mount, load the latest scan for the repo (`getLatestCodeReviewForRepo`).
 *  2. "New scan" submits (`submitCodeReview` → 202) then polls `getCodeReview`
 *     every ~2.5s until the scan reaches a terminal status (COMPLETE / FAILED).
 *     Intermediate statuses (QUEUED / CLONING / STATIC_RUNNING / LLM_RUNNING)
 *     render as a progress label.
 *  3. On COMPLETE: severity-grouped issue list (CRITICAL → MAJOR → MINOR → INFO),
 *     each group with an "Autofix N" button (`dispatchCodeReviewAutofix` →
 *     navigate to /result/{runId}) and a verbatim suggestedFix rendered as
 *     Markdown. A "Download report" action streams the markdown report.
 *
 * Tier gating: FREE → 403, quota → 429. The backend's verbatim message is
 * surfaced and the New-scan / autofix buttons disable while the gate is hit.
 *
 * Rendering: `variant='ide'` uses the IDE token set (ide-card / ide-btn /
 * IdeBadge / var(--ide-*)); `variant='signal'` uses the Signal primitives
 * (Card / Button / StatusPill). Markdown renders native in both chromes
 * (.ide-root .signal-md* remaps the tokens).
 */

type Variant = 'signal' | 'ide';
type Severity = 'CRITICAL' | 'MAJOR' | 'MINOR' | 'INFO';

const SEVERITY_ORDER: Severity[] = ['CRITICAL', 'MAJOR', 'MINOR', 'INFO'];

const TERMINAL = new Set<api.CodeReviewScanDto['status']>(['COMPLETE', 'FAILED']);

const STATUS_LABEL: Record<api.CodeReviewScanDto['status'], string> = {
  QUEUED: 'Queued…',
  CLONING: 'Cloning repo…',
  STATIC_RUNNING: 'Running static analysis…',
  LLM_RUNNING: 'LLM review in progress…',
  COMPLETE: 'Complete',
  FAILED: 'Failed',
};

const POLL_MS = 2500;

export function CodeReviewPanel({
  projectId,
  repoId,
  repoFullName,
  variant,
}: {
  projectId: string;
  repoId?: string;
  repoFullName: string;
  variant: Variant;
}) {
  const navigate = useNavigate();
  const ide = variant === 'ide';

  const [data, setData] = useState<api.CodeReviewScanWithIssues | null | undefined>(undefined);
  const [scanning, setScanning] = useState(false);
  const [progress, setProgress] = useState<api.CodeReviewScanDto['status'] | null>(null);
  const [autofixBusy, setAutofixBusy] = useState<Severity | null>(null);
  const [downloading, setDownloading] = useState(false);
  // 403 (FREE) / 429 (quota) — surface the backend message verbatim + disable
  // the gated actions until the next successful call.
  const [err, setErr] = useState('');
  const [gated, setGated] = useState(false);

  // Poll loop is driven by a ref so the timeout chain can be cancelled cleanly
  // on unmount / target change without re-arming after the component is gone.
  const cancelledRef = useRef(false);

  useEffect(() => {
    cancelledRef.current = false;
    return () => {
      cancelledRef.current = true;
    };
  }, []);

  // Initial load — latest scan for this (project, repo). null = never scanned.
  useEffect(() => {
    if (!projectId || !repoFullName) {
      setData(null);
      return;
    }
    let live = true;
    setData(undefined);
    api
      .getLatestCodeReviewForRepo(projectId, repoFullName)
      .then((d) => {
        // A 204 (no scan yet) surfaces as `undefined` from `req`; coerce to
        // null so the panel renders the "never scanned" prompt rather than
        // sticking on the loading spinner.
        if (live) setData(d ?? null);
      })
      .catch(() => {
        if (live) setData(null);
      });
    return () => {
      live = false;
    };
  }, [projectId, repoFullName]);

  const pollUntilTerminal = useCallback(async (scanId: string) => {
    const tick = async () => {
      if (cancelledRef.current) return;
      try {
        const next = await api.getCodeReview(scanId);
        if (cancelledRef.current) return;
        setData(next);
        setProgress(next.scan.status);
        if (TERMINAL.has(next.scan.status)) {
          setScanning(false);
          setProgress(null);
          return;
        }
      } catch {
        // Transient poll failure — keep polling; a terminal read will clear it.
      }
      if (!cancelledRef.current) setTimeout(tick, POLL_MS);
    };
    setTimeout(tick, POLL_MS);
  }, []);

  async function startScan() {
    setErr('');
    setGated(false);
    setScanning(true);
    setProgress('QUEUED');
    try {
      const scan = await api.submitCodeReview({projectId, repoId, repoFullName});
      // Seed the panel with the QUEUED scan immediately so the user sees the
      // target flip to "in progress" before the first poll lands.
      setData((prev) => ({scan, reportMarkdown: null, issues: prev?.issues ?? []}));
      setProgress(scan.status);
      if (TERMINAL.has(scan.status)) {
        // Defensive — a synchronous terminal status shouldn't happen for a
        // QUEUED submit, but re-fetch the full payload (issues) if it does.
        const full = await api.getCodeReview(scan.id).catch(() => null);
        if (full) setData(full);
        setScanning(false);
        setProgress(null);
        return;
      }
      void pollUntilTerminal(scan.id);
    } catch (e: unknown) {
      setScanning(false);
      setProgress(null);
      if (e instanceof api.ApiError && (e.status === 403 || e.status === 429)) setGated(true);
      setErr(e instanceof Error ? e.message : 'Scan failed.');
    }
  }

  async function autofix(severity: Severity, scanId: string) {
    setErr('');
    setAutofixBusy(severity);
    try {
      const res = await api.dispatchCodeReviewAutofix(scanId, severity);
      navigate(`/result/${res.runId}`);
    } catch (e: unknown) {
      setAutofixBusy(null);
      if (e instanceof api.ApiError && (e.status === 403 || e.status === 429)) setGated(true);
      setErr(e instanceof Error ? e.message : 'Autofix dispatch failed.');
    }
  }

  // Report download — the markdown endpoint needs a bearer token. Try a plain
  // link first (window.open); if the backend rejects unauthenticated GETs we
  // fall back to fetch→blob with the auth header (mirrors downloadWorkspace).
  async function downloadReport(scanId: string) {
    setErr('');
    setDownloading(true);
    const url = api.codeReviewReportDownloadUrl(scanId);
    try {
      const res = await fetch(url, {
        headers: {Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`},
      });
      if (!res.ok) {
        // Last-ditch — open the plain link in a new tab (cookie auth may pass).
        if (res.status === 401 || res.status === 403) {
          window.open(url, '_blank', 'noopener');
          return;
        }
        throw new Error(`Download failed (${res.status}).`);
      }
      const blob = await res.blob();
      const objUrl = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = objUrl;
      a.download = `code-review-${scanId.slice(0, 8)}.md`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(objUrl);
    } catch (e: unknown) {
      // Network-level failure — fall back to a direct link (cookie auth).
      window.open(url, '_blank', 'noopener');
      setErr(e instanceof Error ? e.message : '');
    } finally {
      setDownloading(false);
    }
  }

  const scan = data?.scan;
  const issues = useMemo(() => data?.issues ?? [], [data]);
  const openIssues = useMemo(() => issues.filter((i) => i.status === 'OPEN'), [issues]);

  // Group OPEN issues by severity in the canonical order. Counts drive both the
  // summary chips and the per-group "Autofix N" buttons.
  const grouped = useMemo(() => {
    const by: Record<Severity, api.CodeReviewIssueDto[]> = {
      CRITICAL: [],
      MAJOR: [],
      MINOR: [],
      INFO: [],
    };
    for (const it of openIssues) by[it.severity].push(it);
    return by;
  }, [openIssues]);

  const isComplete = scan?.status === 'COMPLETE';
  // Non-null scan id, only set once a COMPLETE scan exists — lets the autofix /
  // download call sites avoid non-null assertions on `scan`.
  const completedScanId = isComplete && scan ? scan.id : null;
  const isFailed = scan?.status === 'FAILED';
  const inProgress = scanning || (scan != null && !TERMINAL.has(scan.status));
  const progressStatus = progress ?? scan?.status ?? null;

  // ── shared bits ──────────────────────────────────────────────────────────
  const muted = ide ? 'var(--ide-fg-muted)' : 'var(--signal-text-dim)';
  const bright = ide ? 'var(--ide-fg-bright)' : 'inherit';

  function MetaRow() {
    if (!scan) return null;
    const items: Array<[string, string]> = [
      ['Files', scan.totalFilesScanned != null ? scan.totalFilesScanned.toLocaleString() : '—'],
      ['LOC', scan.totalLoc != null ? scan.totalLoc.toLocaleString() : '—'],
      ['Static', scan.staticFindingsCount != null ? String(scan.staticFindingsCount) : '—'],
      ['LLM', scan.llmFindingsCount != null ? String(scan.llmFindingsCount) : '—'],
    ];
    return (
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(4, 1fr)',
          gap: 10,
          paddingTop: 10,
          marginTop: 10,
          borderTop: `1px solid ${ide ? 'var(--ide-border)' : 'var(--signal-hairline)'}`,
          fontSize: 12.5,
        }}
      >
        {items.map(([k, v]) => (
          <div key={k}>
            <div
              style={{
                fontSize: 10,
                fontWeight: 700,
                color: muted,
                letterSpacing: '0.1em',
                textTransform: 'uppercase',
                marginBottom: 4,
              }}
            >
              {k}
            </div>
            <div style={{fontWeight: 600, color: bright}}>{v}</div>
          </div>
        ))}
      </div>
    );
  }

  function SeverityCount({sev, n}: {sev: Severity; n: number}) {
    if (n <= 0) return null;
    if (ide) {
      const kind = sev === 'CRITICAL' ? 'bad' : sev === 'INFO' ? 'ok' : 'warn';
      return (
        <IdeBadge kind={kind}>
          {n} {sev}
        </IdeBadge>
      );
    }
    const kind = sev === 'CRITICAL' ? 'failed' : sev === 'MAJOR' ? 'needs' : undefined;
    return (
      <StatusPill kind={kind}>
        {n} {sev}
      </StatusPill>
    );
  }

  function NewScanButton() {
    const label = inProgress
      ? STATUS_LABEL[progressStatus ?? 'QUEUED']
      : scan
        ? 'New scan'
        : 'Run scan';
    const disabled = inProgress || gated;
    if (ide) {
      return (
        <button
          className='ide-btn'
          style={{padding: '4px 12px'}}
          disabled={disabled}
          onClick={startScan}
        >
          {label}
        </button>
      );
    }
    return (
      <Button kind='primary' sm disabled={disabled} onClick={startScan}>
        {label}
      </Button>
    );
  }

  // ── header card (scan status + counts + actions) ──────────────────────────
  const summaryCounts = SEVERITY_ORDER.map((s) => grouped[s].length);
  const totalOpen = summaryCounts.reduce((a, b) => a + b, 0);
  const clean = isComplete && totalOpen === 0;

  const header = (
    <>
      <div
        style={{
          display: 'flex',
          alignItems: 'flex-start',
          justifyContent: 'space-between',
          gap: 10,
        }}
      >
        <div style={{minWidth: 0, flex: 1}}>
          <div
            style={{
              fontWeight: 600,
              color: bright,
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
            }}
          >
            {repoFullName}
          </div>
          <div style={{fontSize: 12, color: muted, marginTop: 2}}>
            {inProgress
              ? STATUS_LABEL[progressStatus ?? 'QUEUED']
              : scan?.finishedAt
                ? `Last scan ${new Date(scan.finishedAt).toLocaleString()}${
                    scan.scannedSha ? ` · ${scan.scannedSha.slice(0, 7)}` : ''
                  }`
                : scan
                  ? STATUS_LABEL[scan.status]
                  : 'No scan yet'}
          </div>
        </div>
        <NewScanButton />
      </div>

      {/* Severity summary chips (or CLEAN). Hidden while a scan is mid-flight. */}
      {scan && !inProgress && (
        <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 10}}>
          {SEVERITY_ORDER.map((s) => (
            <SeverityCount key={s} sev={s} n={grouped[s].length} />
          ))}
          {clean &&
            (ide ? (
              <IdeBadge kind='ok'>CLEAN</IdeBadge>
            ) : (
              <StatusPill kind='shipped'>CLEAN</StatusPill>
            ))}
          {isFailed &&
            (ide ? (
              <IdeBadge kind='bad'>SCAN FAILED</IdeBadge>
            ) : (
              <StatusPill kind='failed'>SCAN FAILED</StatusPill>
            ))}
        </div>
      )}

      {isFailed && scan?.failureReason && (
        <div
          style={{
            fontSize: 12,
            color: ide ? 'var(--ide-red)' : 'var(--signal-coral-text)',
            marginTop: 8,
          }}
        >
          {scan.failureReason}
        </div>
      )}

      <MetaRow />

      {/* Download report — only meaningful once a scan has completed. */}
      {completedScanId &&
        (ide ? (
          <button
            className='ide-btn ghost'
            style={{padding: '4px 12px', marginTop: 12}}
            disabled={downloading}
            onClick={() => downloadReport(completedScanId)}
          >
            {downloading ? 'Preparing…' : 'Download report (Markdown)'}
          </button>
        ) : (
          <Button
            kind='ghost'
            sm
            block
            disabled={downloading}
            style={{marginTop: 12}}
            onClick={() => downloadReport(completedScanId)}
          >
            {downloading ? 'Preparing…' : 'Download report (Markdown)'}
          </Button>
        ))}
    </>
  );

  // ── severity-grouped issue list ───────────────────────────────────────────
  function IssueGroup({sev}: {sev: Severity}) {
    const list = grouped[sev];
    if (list.length === 0) return null;
    const accent =
      sev === 'CRITICAL'
        ? ide
          ? 'var(--ide-red)'
          : 'var(--signal-coral-text)'
        : sev === 'INFO'
          ? ide
            ? 'var(--ide-green)'
            : 'var(--signal-mint-text-on-light)'
          : ide
            ? 'var(--ide-yellow)'
            : 'var(--signal-text-dim)';
    const busy = autofixBusy === sev;
    return (
      <div style={{marginBottom: 18}}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 10,
            marginBottom: 8,
          }}
        >
          <div
            style={{
              fontSize: 12,
              fontWeight: 700,
              letterSpacing: '0.08em',
              textTransform: 'uppercase',
              color: accent,
            }}
          >
            {sev} · {list.length}
          </div>
          {ide ? (
            <button
              className='ide-btn'
              style={{padding: '3px 10px'}}
              disabled={busy || gated || !completedScanId}
              onClick={() => completedScanId && autofix(sev, completedScanId)}
            >
              {busy ? 'Dispatching…' : `Autofix ${list.length} →`}
            </button>
          ) : (
            <Button
              kind='secondary'
              sm
              disabled={busy || gated || !completedScanId}
              onClick={() => completedScanId && autofix(sev, completedScanId)}
            >
              {busy ? 'Dispatching…' : `Autofix ${list.length} →`}
            </Button>
          )}
        </div>
        {list.map((it) => (
          <IssueRow key={it.id} it={it} />
        ))}
      </div>
    );
  }

  function IssueRow({it}: {it: api.CodeReviewIssueDto}) {
    const loc =
      it.lineStart === it.lineEnd
        ? `${it.filePath}:${it.lineStart}`
        : `${it.filePath}:${it.lineStart}-${it.lineEnd}`;
    const body = (
      <>
        <div
          style={{
            display: 'flex',
            alignItems: 'baseline',
            gap: 8,
            flexWrap: 'wrap',
            marginBottom: 4,
          }}
        >
          <span
            className={ide ? 'ide-mono' : 'mono'}
            style={{fontSize: 12, color: bright, fontWeight: 600}}
          >
            {loc}
          </span>
          <span style={{fontSize: 11, color: muted}}>
            {it.source}
            {it.ruleId ? ` · ${it.ruleId}` : ''}
            {it.category ? ` · ${it.category}` : ''}
          </span>
        </div>
        <div style={{fontSize: 13, lineHeight: 1.5, color: bright}}>{it.message}</div>
        {it.suggestedFix && (
          <div style={{marginTop: 6, fontSize: 12.5}}>
            <Markdown>{it.suggestedFix}</Markdown>
          </div>
        )}
      </>
    );
    if (ide) {
      return (
        <div className='ide-card' style={{marginBottom: 6}}>
          {body}
        </div>
      );
    }
    return (
      <div className='signal-card' style={{marginBottom: 6, padding: '12px 14px'}}>
        {body}
      </div>
    );
  }

  // ── render ─────────────────────────────────────────────────────────────────
  const emptyClass = ide ? 'ide-empty' : '';
  const cardClass = ide ? 'ide-card' : 'signal-card';

  function ErrorBanner() {
    if (!err) return null;
    if (ide) {
      return (
        <div
          className='ide-card'
          style={{
            marginBottom: 12,
            fontSize: 13,
            color: 'var(--ide-red)',
            borderColor: 'var(--ide-red)',
          }}
        >
          {err}
        </div>
      );
    }
    return (
      <div
        style={{
          background: 'var(--signal-coral-soft)',
          color: 'var(--signal-coral-text)',
          border: '1px solid var(--signal-coral)',
          borderRadius: 10,
          padding: '10px 14px',
          marginBottom: 12,
          fontSize: 13,
        }}
      >
        {err}
      </div>
    );
  }

  if (data === undefined) {
    return (
      <div className={emptyClass} style={ide ? undefined : {padding: 24}}>
        Loading code review…
      </div>
    );
  }

  return (
    <div>
      <ErrorBanner />

      <div className={cardClass} style={{marginBottom: 14}}>
        {header}
      </div>

      {/* Issue list — only when a COMPLETE scan produced OPEN issues. */}
      {isComplete && totalOpen > 0 && (
        <div>
          {SEVERITY_ORDER.map((s) => (
            <IssueGroup key={s} sev={s} />
          ))}
        </div>
      )}

      {/* COMPLETE + zero open issues — clean repo. */}
      {clean && (
        <div className={emptyClass} style={ide ? undefined : {padding: 24}}>
          No open issues. This repo passed the last scan clean.
        </div>
      )}

      {/* Never scanned — prompt to run the first scan. */}
      {!scan && !inProgress && (
        <div className={emptyClass} style={ide ? undefined : {padding: 24}}>
          No scan yet. Run a scan to surface code-quality issues across the whole repo.
        </div>
      )}
    </div>
  );
}
