import {useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {TopBar, SectionHeader, IconButton} from '@/signal/components/Chrome';
import {Feed, Row, Card, KvList, TabsStrip, Button} from '@/signal/components/Primitives';
import {LoadingState, SkeletonRow} from '@/signal/components/Loaders';
import {ScoreCard, PrCard, TaskLine} from '@/signal/components/Compose';
import {Share} from '@/signal/icons/icons';
import {Glossary} from '@/signal/components/Glossary';
import {GitHubLogo} from '@/signal/components/BrandLogos';
import {CodeView} from '@/signal/components/CodeView';
import {FileTree, type FileEntry} from '@/signal/components/FileTree';
import {Markdown} from '@/signal/components/Markdown';
import {ConfirmModal} from '@/signal/components/Modal';
import {PrCommentInbox} from '@/signal/components/PrCommentInbox';
import {useAuthStore} from '@/stores/useAuthStore';
import * as api from '@/lib/api';

type ResultTab =
  | 'overview'
  | 'tasks'
  | 'timeline'
  | 'code'
  | 'cost'
  | 'pr'
  | 'validation'
  | 'tests'
  | 'chat';

function shortStatusReason(r?: string | null): string {
  if (!r) return '—';
  return r.toUpperCase().replace(/_/g, ' ');
}

function isTerminalStatus(s?: string): boolean {
  return ['COMPLETE', 'COMPLETED', 'FAILED', 'CANCELLED', 'PARTIAL'].includes(
    (s || '').toUpperCase(),
  );
}

// Run-management glyphs (18px, stroke-only) for the run-detail header.
const RUN_ICON = {width: 18, height: 18} as const;
const forkIcon = (
  <svg
    viewBox='0 0 24 24'
    style={RUN_ICON}
    fill='none'
    stroke='currentColor'
    strokeWidth={2}
    strokeLinecap='round'
    strokeLinejoin='round'
  >
    <circle cx='6' cy='6' r='2.2' />
    <circle cx='6' cy='18' r='2.2' />
    <circle cx='18' cy='9' r='2.2' />
    <path d='M6 8.2v7.6' />
    <path d='M18 11.2c0 3.2-3.6 3.8-6 3.8' />
  </svg>
);
const archiveIcon = (
  <svg
    viewBox='0 0 24 24'
    style={RUN_ICON}
    fill='none'
    stroke='currentColor'
    strokeWidth={2}
    strokeLinecap='round'
    strokeLinejoin='round'
  >
    <rect x='3' y='4' width='18' height='4' rx='1' />
    <path d='M5 8v11a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8' />
    <line x1='10' y1='12' x2='14' y2='12' />
  </svg>
);
const trashIcon = (
  <svg
    viewBox='0 0 24 24'
    style={RUN_ICON}
    fill='none'
    stroke='currentColor'
    strokeWidth={2}
    strokeLinecap='round'
    strokeLinejoin='round'
  >
    <polyline points='3 6 5 6 21 6' />
    <path d='M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2' />
  </svg>
);

export default function ResultPage() {
  const {runId, runShort} = useParams<{runId?: string; runShort?: string}>();
  const id = runId || runShort;
  // Permissions drive whether the "Preview deploy" and "Push to GitHub"
  // affordances even render. FREE-tier users have canDeploy=false and
  // maxPreviewStarts=5; clicking Start used to surface a generic 403
  // without context.
  const permissions = useAuthStore((s) => s.permissions);
  const [run, setRun] = useState<api.RunDetail | null>(null);
  const [report, setReport] = useState<api.ValidationReportData | null>(null);
  const [tab, setTab] = useState<ResultTab>('overview');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');
  const [promptCopied, setPromptCopied] = useState(false);
  const chatEndRef = useRef<HTMLDivElement | null>(null);
  // Timeline / Code / Chat — three regression fixes for v3-parity.
  const [timeline, setTimeline] = useState<api.TimelineData | null>(null);
  const [files, setFiles] = useState<api.FileWithStatus[] | null>(null);
  const [selectedFile, setSelectedFile] = useState<string | null>(null);
  const [fileContent, setFileContent] = useState<string | null>(null);
  const [chatMessages, setChatMessages] = useState<api.RunChatMessage[] | null>(null);
  const [chatInput, setChatInput] = useState('');
  const [chatBusy, setChatBusy] = useState(false);
  const [retryingTaskId, setRetryingTaskId] = useState<string | null>(null);
  const [showDiff, setShowDiff] = useState(false);
  const [fileDiff, setFileDiff] = useState<string | null>(null);
  const [testReport, setTestReport] = useState<api.TestReportData | null>(null);
  const [testReportLoading, setTestReportLoading] = useState(false);
  // Re-run on a different provider — fires `createRun` with the same spec
  // against the chosen provider so the user can A/B providers without
  // re-typing. Used to ship as a single "Iterate" row that always rebuilt on
  // the same provider; that hid the most valuable comparison.
  const [rerunBusy, setRerunBusy] = useState<string | null>(null);
  const navigate = useNavigate();
  const [confirmDelete, setConfirmDelete] = useState(false);

  // Run management — fork / archive / unarchive / soft-delete. These run-
  // lifecycle endpoints existed in the backend but no screen surfaced them.
  async function manageRun(action: 'fork' | 'archive' | 'unarchive' | 'delete') {
    if (!id) return;
    setBusy(true);
    setErr('');
    setMsg('');
    try {
      if (action === 'fork') {
        const forked = await api.forkRun(id);
        navigate(`/plan/${forked.runId}`);
        return;
      }
      if (action === 'delete') {
        await api.softDeleteRun(id);
        navigate('/runs');
        return;
      }
      await (action === 'archive' ? api.archiveRun(id) : api.unarchiveRun(id));
      setRun(await api.getRun(id));
      setMsg(action === 'archive' ? 'Run archived.' : 'Run unarchived.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Action failed.');
    } finally {
      setBusy(false);
      setConfirmDelete(false);
    }
  }

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    Promise.allSettled([api.getRun(id), api.getValidationReport(id)]).then(([r, vr]) => {
      if (cancelled) return;
      if (r.status === 'fulfilled') setRun(r.value);
      else setErr('Failed to load run.');
      if (vr.status === 'fulfilled') setReport(vr.value);
    });
    return () => {
      cancelled = true;
    };
  }, [id]);

  // Lazy-load tab-specific data so we don't fetch the whole world up-front.
  useEffect(() => {
    if (!id) return;
    if (tab === 'timeline' && timeline == null) {
      api
        .getTimeline(id)
        .then(setTimeline)
        .catch(() => setTimeline({tasks: []} as api.TimelineData));
    }
    if (tab === 'code' && files == null) {
      api
        .getFilesWithStatus(id)
        .then(setFiles)
        .catch(() => setFiles([]));
    }
    if (tab === 'chat' && chatMessages == null) {
      api
        .getRunChat(id)
        .then(setChatMessages)
        .catch(() => setChatMessages([]));
    }
    if (tab === 'tests' && testReport == null && !testReportLoading) {
      setTestReportLoading(true);
      api
        .getTestReport(id)
        .then((tr) => setTestReport(tr ?? ({results: []} as unknown as api.TestReportData)))
        .catch(() => setTestReport({results: []} as unknown as api.TestReportData))
        .finally(() => setTestReportLoading(false));
    }
  }, [id, tab, timeline, files, chatMessages, testReport, testReportLoading]);

  // Load file content when user picks a file from the Code tab list.
  useEffect(() => {
    if (!id || !selectedFile) {
      setFileContent(null);
      return;
    }
    let cancelled = false;
    setFileContent('Loading…');
    api
      .getFile(id, selectedFile)
      .then((c) => {
        if (!cancelled) setFileContent(typeof c === 'string' ? c : JSON.stringify(c, null, 2));
      })
      .catch(() => {
        if (!cancelled) setFileContent('Failed to load file.');
      });
    return () => {
      cancelled = true;
    };
  }, [id, selectedFile]);

  // Auto-scroll the chat thread to the newest message as it grows / while the
  // assistant is replying. Without this the view stayed pinned at the top and
  // the latest message landed below the fold.
  useEffect(() => {
    if (tab !== 'chat') return;
    // Scroll the messages box (the end-marker's parent) straight to the bottom.
    // Direct scrollTop is deterministic where scrollIntoView raced the Markdown
    // content settling its height on a long thread.
    const box = chatEndRef.current?.parentElement;
    if (box) box.scrollTop = box.scrollHeight;
  }, [tab, chatMessages, chatBusy]);

  async function sendChat() {
    if (!id || !chatInput.trim() || chatBusy) return;
    setChatBusy(true);
    const text = chatInput.trim();
    setChatInput('');
    // Optimistic insert.
    const userMsg: api.RunChatMessage = {role: 'user', content: text};
    setChatMessages((prev) => [...(prev || []), userMsg]);
    try {
      const resp = await api.sendRunChatMessage(id, text);
      const reply =
        (resp as unknown as {reply?: string; message?: string}).reply ||
        (resp as unknown as {message?: string}).message ||
        '(no reply)';
      const assistantMsg: api.RunChatMessage = {role: 'assistant', content: reply};
      setChatMessages((prev) => [...(prev || []), assistantMsg]);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Chat failed.');
    } finally {
      setChatBusy(false);
    }
  }

  async function iterateAsNewRun() {
    if (!id || chatBusy) return;
    const baseSpec = (run as unknown as {userRequest?: string})?.userRequest?.trim();
    if (!baseSpec) {
      setErr('Cannot iterate: source spec is missing.');
      return;
    }
    const convo = chatMessages || [];
    if (convo.length === 0) {
      setErr(
        'Start a conversation first — "Iterate as new run" summarizes the discussion into the next run.',
      );
      return;
    }
    setChatBusy(true);
    setErr('');
    try {
      // Purpose: summarize the run conversation into one iteration instruction,
      // then fork the original spec + that instruction into a fresh run. (The
      // /iterate endpoint is 410 in V2, so we compose the new spec client-side and
      // submit via createRun.) Summarized by the lightweight conversation model.
      const wire: api.ConversationMessage[] = [
        ...convo.map((m) => ({
          role: ((m as unknown as {role?: string}).role === 'user' ? 'user' : 'assistant') as
            | 'user'
            | 'assistant',
          content: (m as unknown as {content?: string}).content || '',
        })),
        ...(chatInput.trim() ? [{role: 'user' as const, content: chatInput.trim()}] : []),
        {
          role: 'user' as const,
          content:
            'Summarize the refinements we discussed above into ONE consolidated instruction for the next iteration of this run. Output only the instruction text — no preamble, no questions.',
        },
      ];
      const summaryResp = await api.conversationChat(wire);
      const summary = (
        (summaryResp as unknown as {reply?: string; message?: string}).reply ||
        (summaryResp as unknown as {message?: string}).message ||
        ''
      ).trim();
      const spec = summary
        ? `${baseSpec}\n\nIterate on the current implementation with these refinements (summarized from the run discussion):\n${summary}`
        : baseSpec;
      const provider = (run as unknown as {aiProvider?: string})?.aiProvider;
      const projectId = (run as unknown as {projectId?: string})?.projectId;
      const execMode = (
        (run as unknown as {executionMode?: string})?.executionMode === 'PRODUCTION'
          ? 'PRODUCTION'
          : 'PROTOTYPE'
      ) as 'PROTOTYPE' | 'PRODUCTION';
      const resp = await api.createRun({
        userRequest: spec,
        aiProvider: provider,
        executionMode: execMode,
        skipApproval: execMode === 'PROTOTYPE',
        projectId,
      });
      const newId = (resp as unknown as {runId?: string}).runId;
      if (newId) {
        setChatInput('');
        window.location.href = execMode === 'PROTOTYPE' ? `/live/${newId}` : `/plan/${newId}`;
      }
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Iterate failed.');
    } finally {
      setChatBusy(false);
    }
  }

  async function rerunOnProvider(provider: 'ANTHROPIC' | 'OPENAI' | 'GEMINI') {
    if (!run) return;
    const spec = (run as unknown as {userRequest?: string})?.userRequest?.trim();
    if (!spec) {
      setErr('Cannot re-run: source spec is missing.');
      return;
    }
    setRerunBusy(provider);
    try {
      // Mirror the original run's executionMode so a PROTOTYPE re-run stays
      // PROTOTYPE (no plan gate). Project context (if any) is preserved.
      const projectId = (run as unknown as {projectId?: string})?.projectId;
      const execMode = (
        (run as unknown as {executionMode?: string})?.executionMode === 'PRODUCTION'
          ? 'PRODUCTION'
          : 'PROTOTYPE'
      ) as 'PROTOTYPE' | 'PRODUCTION';
      const resp = await api.createRun({
        userRequest: spec,
        aiProvider: provider,
        executionMode: execMode,
        skipApproval: execMode === 'PROTOTYPE',
        projectId,
      });
      const newId = (resp as unknown as {runId?: string}).runId;
      if (newId) window.location.href = `/live/${newId}`;
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Re-run failed.');
    } finally {
      setRerunBusy(null);
    }
  }

  const score =
    (run as unknown as {verifiedScore?: number})?.verifiedScore ??
    (run as unknown as {accuracyScore?: number})?.accuracyScore ??
    0;
  const status = run?.status as string | undefined;
  const userRequest = run?.userRequest || '';
  const totalCost = (run as unknown as {actualCostUsd?: number})?.actualCostUsd ?? 0;
  const prUrl =
    (run as unknown as {scmPrUrl?: string; githubPrUrl?: string})?.scmPrUrl ||
    (run as unknown as {githubPrUrl?: string})?.githubPrUrl;
  const statusReason = (run as unknown as {statusReason?: string})?.statusReason;
  // Match the 4-band palette used by RunCard/ScoreRing/AllRunsPage/HomePage:
  // ≥85 = green / Shippable, 70-84 = amber / Needs review, <70 = red / Failed.
  // The previous 90 threshold here put green-band runs (85-89) into "Needs
  // review" which directly contradicted the chip colour everywhere else.
  const verdict = score >= 85 ? 'Shippable' : score >= 70 ? 'Needs review' : 'Failed';
  const tasks = run?.tasks || [];

  const reportAny = (report || {}) as unknown as {
    buildStatus?: string;
    testsPassed?: number;
    testsTotal?: number;
    coverageDelta?: number;
    reviewerStatus?: string;
    reviewerVerdict?: string;
    securityStatus?: string;
    architectureStatus?: string;
    statusReason?: string;
    issues?: api.ReportIssue[];
    accuracyScore?: number;
    rubricScore?: number;
    finalBuildPassed?: boolean;
    accuracyJson?: string;
  };
  // PR-R: when no concrete tests ran (testsTotal=0), surface the LLM-judged
  // testCoverageLevel from accuracyJson so the row isn't a blank dash.
  // Same for endpoint coverage — both are persisted on the report but
  // currently only the multiplicative scorer reads them.
  const accuracyJsonParsed = (() => {
    if (!reportAny.accuracyJson)
      return null as null | {
        testCoverageLevel?: string;
        endpointCoverage?: {coveragePercent?: number};
        implementedRequirements?: string[];
        missingRequirements?: string[];
      };
    try {
      return JSON.parse(reportAny.accuracyJson);
    } catch {
      return null;
    }
  })();
  const testCoverageLevel = accuracyJsonParsed?.testCoverageLevel as
    | 'NONE'
    | 'PARTIAL'
    | 'GOOD'
    | 'COMPREHENSIVE'
    | undefined;
  const endpointCoveragePct = accuracyJsonParsed?.endpointCoverage?.coveragePercent;
  // Reviewer verdict / build-passed mirror the multiplicative formula's
  // "trust" signals — surface them on the breakdown so a reader can sanity-
  // check the score without opening the JSON.
  const reviewerVerdict = reportAny.reviewerVerdict;
  const finalBuildPassed = reportAny.finalBuildPassed;

  // 2026-05-19 honesty pass — lead with a plain-English sentence derived from
  // the actually-measured signals, not the multiplicative score number. The
  // old "Failed 50%" banner contradicted "COMPLETE / CLEAN COMPLETION" + a
  // passing build whenever a single unmeasured factor dragged the product
  // toward 0. Reviewer-side rebuild of the score itself is a separate
  // workstream — this just stops the headline from lying.
  const verdictSentence = (() => {
    const parts: string[] = [];
    if (reportAny.buildStatus === 'PASSING' || finalBuildPassed === true) {
      parts.push('Build passed.');
    } else if (reportAny.buildStatus === 'FAILING' || finalBuildPassed === false) {
      parts.push('Build failed.');
    }
    if (reportAny.testsTotal != null && reportAny.testsTotal > 0) {
      parts.push(`${reportAny.testsPassed ?? 0} of ${reportAny.testsTotal} tests pass.`);
    } else if (testCoverageLevel) {
      parts.push(`Tests: ${testCoverageLevel.toLowerCase()} coverage (LLM-judged).`);
    }
    if (reviewerVerdict === 'CLEAN' || reportAny.reviewerStatus === 'CLEAN') {
      parts.push('Reviewer ran clean.');
    } else if (reviewerVerdict === 'REVIEW_FAILED' || reviewerVerdict === 'UNRESOLVED_ISSUES') {
      parts.push('Reviewer flagged unresolved issues.');
    }
    if (parts.length === 0) return null;
    return parts.join(' ');
  })();
  // Names of signals that were "not measured" — surfaced once at the end of
  // the breakdown rather than as four separate dashes in the body.
  const unmeasured: string[] = [];
  if (!(reportAny.buildStatus || finalBuildPassed != null)) unmeasured.push('Build');
  if (!(reportAny.testsTotal != null && reportAny.testsTotal > 0) && !testCoverageLevel)
    unmeasured.push('Tests');
  if (reportAny.coverageDelta == null && endpointCoveragePct == null) unmeasured.push('Coverage');
  if (!(reportAny.reviewerStatus || reviewerVerdict)) unmeasured.push('Reviewer');
  if (!reportAny.securityStatus) unmeasured.push('Security');
  if (!reportAny.architectureStatus) unmeasured.push('Architecture');

  const tokensTotal = (run?.totalInputTokens ?? 0) + (run?.totalOutputTokens ?? 0);
  const elapsed = useMemo(() => {
    if (!run?.startedAt) return '—';
    const t0 = new Date(run.startedAt).getTime();
    const t1 = run.completedAt ? new Date(run.completedAt).getTime() : Date.now();
    if (!t0 || !t1) return '—';
    const m = Math.floor((t1 - t0) / 60000);
    const s = Math.floor(((t1 - t0) % 60000) / 1000);
    return `${m}m ${s}s`;
  }, [run?.startedAt, run?.completedAt]);

  async function downloadPdf() {
    if (!id) return;
    setBusy(true);
    setErr('');
    try {
      await api.downloadValidationReportPdf(id);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Download failed.');
    } finally {
      setBusy(false);
    }
  }

  async function share() {
    if (!id) return;
    try {
      const r = await api.shareValidationReport(id);
      const token =
        (r as unknown as {shareToken?: string; token?: string}).shareToken ||
        (r as unknown as {token?: string}).token;
      if (token) {
        const url = `${window.location.origin}/shared/${token}`;
        await navigator.clipboard.writeText(url).catch(() => undefined);
        setMsg('Share URL copied to clipboard.');
      } else {
        setMsg('Share link created.');
      }
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Share failed.');
    }
  }

  async function downloadWorkspace() {
    if (!id) return;
    try {
      await api.downloadWorkspace(id);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Workspace download failed.');
    }
  }

  async function retryReport() {
    if (!id) return;
    try {
      await api.retryValidationReport(id);
      setMsg('Validation report retry queued.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Retry failed.');
    }
  }

  const [previewBusy, setPreviewBusy] = useState(false);
  const canPreview = permissions ? permissions.canDeploy !== false : true;
  async function startPreview() {
    if (!id) return;
    if (!canPreview) {
      setErr('Preview deploys are gated to PRO+ tiers. Upgrade your plan to enable.');
      return;
    }
    setPreviewBusy(true);
    try {
      const resp = await api.startPreview(id);
      const url =
        (resp as unknown as {previewUrl?: string; url?: string})?.previewUrl ||
        (resp as unknown as {url?: string})?.url;
      setMsg(
        url ? `Preview live at ${url} (5-min TTL).` : 'Preview starting. Check Deployments in 30s.',
      );
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Preview start failed.');
    } finally {
      setPreviewBusy(false);
    }
  }

  async function retryTask(taskId: string) {
    if (!id) return;
    setRetryingTaskId(taskId);
    setErr('');
    setMsg('');
    try {
      await api.retryTask(id, taskId);
      setMsg(`Task ${taskId.slice(0, 6)} retry queued. Refresh in a few seconds to see progress.`);
      // Refresh the run so the task's status updates.
      const r = await api.getRun(id);
      setRun(r);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Retry failed.');
    } finally {
      setRetryingTaskId(null);
    }
  }

  // Lazy-load file diff when the user toggles "Diff" on a selected file.
  useEffect(() => {
    if (!id || !selectedFile || !showDiff) {
      setFileDiff(null);
      return;
    }
    let cancelled = false;
    setFileDiff('Loading…');
    api
      .getFileDiff(id, selectedFile)
      .then((d) => {
        if (cancelled) return;
        const diffText =
          (d as unknown as {diff?: string; content?: string}).diff ||
          (d as unknown as {content?: string}).content ||
          '';
        setFileDiff(diffText || 'No diff (file unchanged or newly added).');
      })
      .catch(() => {
        if (!cancelled) setFileDiff('Failed to load diff.');
      });
    return () => {
      cancelled = true;
    };
  }, [id, selectedFile, showDiff]);

  async function pushScm() {
    if (!id) return;
    try {
      await api.pushToScm(id);
      setMsg('Push triggered.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Push failed.');
    }
  }

  if (!id) {
    return (
      <>
        <TopBar title='Run' sub='no run id' back backTo='/home' />
        <Feed>
          <Card>
            <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0}}>
              No run selected.{' '}
              <a href='/runs' style={{color: 'var(--signal-coral)'}}>
                Browse runs →
              </a>
            </p>
          </Card>
        </Feed>
      </>
    );
  }

  return (
    <>
      <TopBar
        title={`R-${id.slice(0, 6)}`}
        sub={status ? `${status.toLowerCase()} · ${(userRequest || '').slice(0, 60)}` : 'Loading…'}
        back
        backTo='/runs'
        actions={
          <>
            <IconButton ariaLabel='Download' onClick={downloadWorkspace}>
              <svg
                viewBox='0 0 24 24'
                style={{width: 18, height: 18}}
                fill='none'
                stroke='currentColor'
                strokeWidth={2}
                strokeLinecap='round'
              >
                <path d='M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8' />
                <polyline points='16 6 12 2 8 6' />
                <line x1='12' y1='2' x2='12' y2='15' />
              </svg>
            </IconButton>
            <IconButton ariaLabel='Share' onClick={share}>
              <Share size={18} />
            </IconButton>
            <IconButton ariaLabel='Fork run' onClick={() => manageRun('fork')}>
              {forkIcon}
            </IconButton>
            <IconButton
              ariaLabel={run?.archivedAt ? 'Unarchive run' : 'Archive run'}
              onClick={() => manageRun(run?.archivedAt ? 'unarchive' : 'archive')}
            >
              {archiveIcon}
            </IconButton>
            {isTerminalStatus(status) && (
              <IconButton ariaLabel='Delete run' onClick={() => setConfirmDelete(true)}>
                {trashIcon}
              </IconButton>
            )}
          </>
        }
      />
      <ConfirmModal
        open={confirmDelete}
        title='Delete this run?'
        body='The run is soft-deleted with a 30-day undo window, then permanently removed. You can restore it from All Runs within 30 days.'
        confirmLabel='Delete run'
        danger
        busy={busy}
        onConfirm={() => manageRun('delete')}
        onClose={() => setConfirmDelete(false)}
      />
      <Feed>
        {err && (
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
        )}
        {msg && (
          <div
            style={{
              background: 'var(--signal-mint-soft)',
              color: 'var(--signal-mint-text-on-light)',
              border: '1px solid var(--signal-mint)',
              borderRadius: 10,
              padding: '10px 14px',
              marginBottom: 12,
              fontSize: 13,
            }}
          >
            {msg}
          </div>
        )}

        <TabsStrip
          items={[
            {label: 'Overview', active: tab === 'overview', onClick: () => setTab('overview')},
            {
              label: `Tasks · ${tasks.length}`,
              active: tab === 'tasks',
              onClick: () => setTab('tasks'),
            },
            {label: 'Timeline', active: tab === 'timeline', onClick: () => setTab('timeline')},
            {
              label: `Code · ${files?.length ?? '—'}`,
              active: tab === 'code',
              onClick: () => setTab('code'),
            },
            {label: 'Cost', active: tab === 'cost', onClick: () => setTab('cost')},
            {label: prUrl ? 'PR' : 'PR · —', active: tab === 'pr', onClick: () => setTab('pr')},
            {
              label: 'Validation',
              active: tab === 'validation',
              onClick: () => setTab('validation'),
            },
            {label: 'Tests', active: tab === 'tests', onClick: () => setTab('tests')},
            {label: 'Chat', active: tab === 'chat', onClick: () => setTab('chat')},
          ]}
        />

        {tab === 'overview' && (
          <>
            {verdictSentence && (
              <Card
                style={{
                  fontSize: 15,
                  lineHeight: 1.45,
                  padding: '14px 16px',
                  marginBottom: 12,
                }}
              >
                {verdictSentence}
              </Card>
            )}
            <ScoreCard
              verdict={verdict}
              score={Math.round(score)}
              label={`Verified score${prUrl ? ' · PR ready' : ''}`}
              cells={[
                {v: status || '—', l: 'Status'},
                {v: `$${totalCost.toFixed(2)}`, l: 'Total cost'},
                {v: prUrl ? 'open' : '—', l: 'Pull request'},
              ]}
            />

            {userRequest && (
              <>
                <SectionHeader>Submitted prompt</SectionHeader>
                <Card style={{position: 'relative', padding: '14px 16px', marginBottom: 12}}>
                  <Button
                    kind='secondary'
                    sm
                    onClick={() => {
                      void navigator.clipboard?.writeText(userRequest).catch(() => undefined);
                      setPromptCopied(true);
                      setTimeout(() => setPromptCopied(false), 2000);
                    }}
                    style={{position: 'absolute', top: 10, right: 10, zIndex: 1}}
                  >
                    {promptCopied ? 'Copied' : 'Copy'}
                  </Button>
                  <div
                    style={{
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-word',
                      fontSize: 13.5,
                      lineHeight: 1.55,
                      color: 'var(--signal-ink)',
                      maxHeight: 320,
                      overflowY: 'auto',
                      paddingRight: 72,
                    }}
                  >
                    {userRequest}
                  </div>
                </Card>
              </>
            )}

            <SectionHeader>Signal breakdown</SectionHeader>
            <Card>
              <KvList
                pairs={[
                  // 2026-05-19 honesty pass — only render signals that were
                  // actually measured. Unmeasured ones collapse into a single
                  // "Skipped" row at the end of this list. Showing four
                  // "not measured" rows next to the verdict destroyed
                  // credibility even when the run was legitimately fine.
                  ...(reportAny.buildStatus || finalBuildPassed != null
                    ? [
                        {
                          k: 'Build',
                          v: (
                            <span
                              style={{
                                color:
                                  reportAny.buildStatus === 'PASSING' || finalBuildPassed === true
                                    ? 'var(--signal-mint-text-on-light)'
                                    : 'var(--signal-coral-text)',
                              }}
                            >
                              {reportAny.buildStatus ??
                                (finalBuildPassed === true ? 'PASSED' : 'FAILED')}
                            </span>
                          ),
                        },
                      ]
                    : []),
                  ...((reportAny.testsTotal != null && reportAny.testsTotal > 0) ||
                  testCoverageLevel
                    ? [
                        {
                          k: 'Tests',
                          v:
                            reportAny.testsTotal != null && reportAny.testsTotal > 0 ? (
                              `${reportAny.testsPassed ?? 0} of ${reportAny.testsTotal}`
                            ) : testCoverageLevel ? (
                              <span
                                style={{
                                  color:
                                    testCoverageLevel === 'GOOD' ||
                                    testCoverageLevel === 'COMPREHENSIVE'
                                      ? 'var(--signal-mint-text-on-light)'
                                      : testCoverageLevel === 'NONE'
                                        ? 'var(--signal-coral-text)'
                                        : 'var(--signal-text-dim)',
                                }}
                              >
                                {testCoverageLevel.toLowerCase()} coverage{' '}
                                <span style={{color: 'var(--signal-text-mute)', fontSize: 11}}>
                                  (LLM-judged)
                                </span>
                              </span>
                            ) : null,
                        },
                      ]
                    : []),
                  ...(reportAny.coverageDelta != null || endpointCoveragePct != null
                    ? [
                        {
                          k: 'Coverage',
                          v:
                            reportAny.coverageDelta != null ? (
                              `${reportAny.coverageDelta >= 0 ? '+' : ''}${reportAny.coverageDelta.toFixed(1)}%`
                            ) : endpointCoveragePct != null ? (
                              <span
                                style={{
                                  color:
                                    endpointCoveragePct >= 95
                                      ? 'var(--signal-mint-text-on-light)'
                                      : endpointCoveragePct >= 70
                                        ? 'var(--signal-text-dim)'
                                        : 'var(--signal-coral-text)',
                                }}
                              >
                                endpoints {endpointCoveragePct}%
                              </span>
                            ) : null,
                        },
                      ]
                    : []),
                  ...(reportAny.reviewerStatus || reviewerVerdict
                    ? [
                        {
                          k: <Glossary term='wiring pass'>Reviewer</Glossary>,
                          v: (
                            <span
                              style={{
                                color:
                                  reviewerVerdict === 'CLEAN'
                                    ? 'var(--signal-mint-text-on-light)'
                                    : reviewerVerdict === 'REVIEW_FAILED' ||
                                        reviewerVerdict === 'UNRESOLVED_ISSUES'
                                      ? 'var(--signal-coral-text)'
                                      : undefined,
                              }}
                            >
                              {reportAny.reviewerStatus || reviewerVerdict}
                            </span>
                          ),
                        },
                      ]
                    : []),
                  ...(reportAny.accuracyScore != null
                    ? [
                        {
                          k: <Glossary term='verified score'>Accuracy (LLM)</Glossary>,
                          v: (
                            <span style={{color: 'var(--signal-text-dim)'}}>
                              {reportAny.accuracyScore}/100
                            </span>
                          ),
                        },
                      ]
                    : []),
                  ...(reportAny.securityStatus
                    ? [
                        {
                          k: 'Security',
                          v: (
                            <span
                              style={{
                                color:
                                  reportAny.securityStatus === 'CLEAN'
                                    ? 'var(--signal-mint-text-on-light)'
                                    : 'var(--signal-coral-text)',
                              }}
                            >
                              {reportAny.securityStatus}
                            </span>
                          ),
                        },
                      ]
                    : []),
                  ...(reportAny.architectureStatus
                    ? [
                        {
                          k: 'Architecture',
                          v: (
                            <span
                              style={{
                                color:
                                  reportAny.architectureStatus === 'CLEAN'
                                    ? 'var(--signal-mint-text-on-light)'
                                    : 'var(--signal-coral-text)',
                              }}
                            >
                              {reportAny.architectureStatus}
                            </span>
                          ),
                        },
                      ]
                    : []),
                  {
                    k: <Glossary term='status reason'>Status reason</Glossary>,
                    v: shortStatusReason(reportAny.statusReason || statusReason),
                  },
                  ...(unmeasured.length > 0
                    ? [
                        {
                          k: 'Skipped',
                          v: (
                            <span
                              style={{color: 'var(--signal-text-mute)', fontSize: 13}}
                              title='These signals weren’t run for this spec/mode. They don’t indicate failure.'
                            >
                              {unmeasured.join(', ')}
                            </span>
                          ),
                        },
                      ]
                    : []),
                ]}
              />
            </Card>

            <SectionHeader>What now</SectionHeader>
            <Row
              to='/compose'
              dot='signal'
              title='Iterate on this run'
              meta={<span>Reuse the spec in the composer</span>}
              showChev
            />
            {(() => {
              // Re-run on a different provider — surfaces the A/B comparison
              // the user actually wants to make when a score is mediocre.
              // Excludes the provider this run already used.
              const currentProvider = (
                run as unknown as {aiProvider?: string}
              )?.aiProvider?.toUpperCase();
              const others = (['ANTHROPIC', 'OPENAI', 'GEMINI'] as const).filter(
                (p) => p !== currentProvider,
              );
              return (
                <Row
                  dot='signal'
                  title='Try another provider'
                  meta={<span>Re-run this exact spec on a different model</span>}
                  trailing={
                    <div style={{display: 'flex', gap: 6}}>
                      {others.map((p) => (
                        <Button
                          key={p}
                          kind='secondary'
                          sm
                          disabled={rerunBusy != null}
                          onClick={() => rerunOnProvider(p)}
                        >
                          {rerunBusy === p ? '…' : p[0] + p.slice(1).toLowerCase()}
                        </Button>
                      ))}
                    </div>
                  }
                />
              );
            })()}
            <Row
              dot='lavender'
              title='Preview deploy'
              meta={
                <span>
                  Cloud Run · 5min TTL
                  {!canPreview && ' · PRO+ feature'}
                </span>
              }
              trailing={
                <Button
                  kind='secondary'
                  sm
                  onClick={startPreview}
                  disabled={previewBusy || !canPreview}
                >
                  {previewBusy ? 'Starting…' : !canPreview ? 'Upgrade' : 'Start'}
                </Button>
              }
            />
            <Row
              dot='lavender'
              title='Download report (PDF)'
              meta={<span>signed validation report</span>}
              trailing={
                <Button kind='secondary' sm onClick={downloadPdf} disabled={busy}>
                  {busy ? '…' : 'PDF'}
                </Button>
              }
            />
            <Row
              dot='lavender'
              title='Share read-only report'
              meta={<span>public link · 30d expiry</span>}
              trailing={
                <Button kind='secondary' sm onClick={share}>
                  Share
                </Button>
              }
            />
            <Row
              dot='lavender'
              title='Download workspace'
              meta={<span>full code · zip</span>}
              trailing={
                <Button kind='secondary' sm onClick={downloadWorkspace}>
                  Download
                </Button>
              }
            />
          </>
        )}

        {tab === 'tasks' && (
          <>
            {tasks.length === 0 ? (
              <Card>
                <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
                  No tasks recorded.
                </p>
              </Card>
            ) : (
              tasks.map((t, i) => {
                const tAny = t as unknown as {
                  taskId?: string;
                  title?: string;
                  status?: string;
                  complexity?: string;
                  iterations?: number;
                  failureReason?: string;
                };
                const failed = tAny.status === 'FAILED';
                const partial = tAny.status === 'PARTIAL';
                const canRetry = failed || partial;
                return (
                  <div
                    key={tAny.taskId || i}
                    style={{
                      marginBottom: 8,
                      background: 'var(--signal-card-bg)',
                      border: '1px solid var(--signal-hairline)',
                      borderRadius: 14,
                      overflow: 'hidden',
                    }}
                  >
                    <TaskLine
                      status={
                        tAny.status === 'COMPLETED' || tAny.status === 'DONE'
                          ? 'done'
                          : tAny.status === 'EXECUTING' || tAny.status === 'RUNNING'
                            ? 'live'
                            : 'todo'
                      }
                      title={tAny.title || `Task ${i + 1}`}
                      sub={
                        [
                          tAny.iterations ? `${tAny.iterations} iters` : null,
                          tAny.status ? tAny.status.toLowerCase() : null,
                        ]
                          .filter(Boolean)
                          .join(' · ') || undefined
                      }
                      tag={tAny.complexity || 'AUTO'}
                    />
                    {canRetry && tAny.taskId && (
                      <div
                        style={{
                          padding: '10px 14px',
                          borderTop: '1px solid var(--signal-hairline)',
                          display: 'flex',
                          justifyContent: 'space-between',
                          gap: 10,
                          alignItems: 'center',
                        }}
                      >
                        <span
                          style={{
                            fontSize: 12,
                            color: 'var(--signal-coral-text)',
                            minWidth: 0,
                            flex: 1,
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                          }}
                        >
                          {failed ? 'Failed' : 'Partial'}
                          {tAny.failureReason ? ` · ${tAny.failureReason.slice(0, 80)}` : ''}
                        </span>
                        <Button
                          kind='primary'
                          sm
                          disabled={retryingTaskId === tAny.taskId}
                          onClick={() => retryTask(tAny.taskId!)}
                        >
                          {retryingTaskId === tAny.taskId ? 'Retrying…' : 'Retry task'}
                        </Button>
                      </div>
                    )}
                  </div>
                );
              })
            )}
          </>
        )}

        {tab === 'cost' && (
          <>
            <Card>
              <KvList
                pairs={[
                  {k: 'Total cost', v: `$${totalCost.toFixed(2)}`},
                  {
                    k: 'Hard cap',
                    v:
                      (run as unknown as {hardCapUsd?: number})?.hardCapUsd != null
                        ? `$${(run as unknown as {hardCapUsd?: number}).hardCapUsd!.toFixed(2)}`
                        : '—',
                  },
                  {
                    k: 'Credits charged',
                    v: (run as unknown as {creditsCharged?: number})?.creditsCharged ?? '—',
                  },
                  {
                    k: 'Estimate range',
                    v: `$${(run as unknown as {costEstimateLow?: number})?.costEstimateLow?.toFixed(2) ?? '—'} – $${(run as unknown as {costEstimateHigh?: number})?.costEstimateHigh?.toFixed(2) ?? '—'}`,
                  },
                  {k: 'Input tokens', v: (run?.totalInputTokens ?? 0).toLocaleString()},
                  {k: 'Output tokens', v: (run?.totalOutputTokens ?? 0).toLocaleString()},
                  {k: 'Total tokens', v: tokensTotal.toLocaleString()},
                  {k: 'Elapsed', v: elapsed},
                ]}
              />
            </Card>
            <SectionHeader>Per-phase cost</SectionHeader>
            <Card>
              <KvList
                pairs={[
                  {
                    k: 'Classifier',
                    v:
                      (run as unknown as {classifierCostUsd?: number})?.classifierCostUsd != null
                        ? `$${(run as unknown as {classifierCostUsd: number}).classifierCostUsd.toFixed(4)}`
                        : '—',
                  },
                  {
                    k: 'Outline',
                    v:
                      (run as unknown as {outlineCostUsd?: number})?.outlineCostUsd != null
                        ? `$${(run as unknown as {outlineCostUsd: number}).outlineCostUsd.toFixed(4)}`
                        : '—',
                  },
                  {
                    k: 'Expansion',
                    v:
                      (run as unknown as {expansionCostUsd?: number})?.expansionCostUsd != null
                        ? `$${(run as unknown as {expansionCostUsd: number}).expansionCostUsd.toFixed(4)}`
                        : '—',
                  },
                  {
                    k: 'Coherence',
                    v:
                      (run as unknown as {coherenceCostUsd?: number})?.coherenceCostUsd != null
                        ? `$${(run as unknown as {coherenceCostUsd: number}).coherenceCostUsd.toFixed(4)}`
                        : '—',
                  },
                ]}
              />
            </Card>
          </>
        )}

        {tab === 'pr' &&
          (prUrl ? (
            <>
              <PrCard
                label='PR · OPEN'
                title={userRequest ? userRequest.slice(0, 80) : 'Open pull request'}
                repo={prUrl.replace(/^https?:\/\/[^/]+\//, '').replace(/\/pull\//, '#')}
                ciStatus={<span className='ci-ok'>● open</span>}
                actions={
                  <button type='button' onClick={() => window.open(prUrl, '_blank', 'noopener')}>
                    Open on GitHub
                  </button>
                }
              />
              <PrCommentInbox runId={id} variant='signal' />
            </>
          ) : (
            <Card>
              <p
                style={{
                  fontSize: 13.5,
                  color: 'var(--signal-text-dim)',
                  margin: 0,
                  lineHeight: 1.5,
                }}
              >
                No PR yet — the run is{' '}
                <strong style={{color: 'var(--signal-ink)'}}>
                  {status?.toLowerCase() || 'in progress'}
                </strong>
                . PRs open automatically once the executor finishes pushing.
              </p>
              <div style={{display: 'flex', gap: 6, marginTop: 12}}>
                <Button kind='secondary' sm onClick={pushScm}>
                  <span style={{display: 'inline-flex', alignItems: 'center', gap: 8}}>
                    <GitHubLogo size={14} />
                    Push to GitHub
                  </span>
                </Button>
              </div>
            </Card>
          ))}

        {tab === 'validation' && (
          <>
            <Card>
              {report === null ? (
                <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
                  No validation report.{' '}
                  <Button kind='ghost' sm onClick={retryReport}>
                    Retry generation
                  </Button>
                </p>
              ) : (
                <KvList
                  pairs={[
                    {k: 'Build', v: reportAny.buildStatus ?? '—'},
                    {
                      k: 'Tests',
                      v:
                        reportAny.testsTotal != null
                          ? `${reportAny.testsPassed}/${reportAny.testsTotal}`
                          : '—',
                    },
                    {
                      k: 'Coverage Δ',
                      v:
                        reportAny.coverageDelta != null
                          ? `${reportAny.coverageDelta.toFixed(1)}%`
                          : '—',
                    },
                    {k: 'Reviewer', v: reportAny.reviewerStatus ?? '—'},
                    {k: 'Security', v: reportAny.securityStatus ?? '—'},
                    {k: 'Architecture', v: reportAny.architectureStatus ?? '—'},
                  ]}
                />
              )}
            </Card>
            {(reportAny.issues || []).length > 0 && (
              <>
                <SectionHeader>Issues · {reportAny.issues!.length}</SectionHeader>
                {reportAny.issues!.slice(0, 20).map((iss, i) => {
                  const iAny = iss as unknown as {
                    id?: string;
                    title?: string;
                    description?: string;
                    severity?: string;
                    file?: string;
                    line?: number;
                    category?: string;
                  };
                  const sev = (iAny.severity || 'MINOR').toUpperCase();
                  return (
                    <Row
                      key={iAny.id || i}
                      dot={sev === 'CRITICAL' ? 'coral' : sev === 'MAJOR' ? 'signal' : 'lavender'}
                      title={iAny.title || iAny.description || `Issue ${i + 1}`}
                      meta={
                        <>
                          {iAny.file && (
                            <span className='mono'>
                              {iAny.file}
                              {iAny.line ? `:${iAny.line}` : ''}
                            </span>
                          )}
                          {iAny.category && (
                            <>
                              <span>·</span>
                              <span>{iAny.category}</span>
                            </>
                          )}
                        </>
                      }
                      trailing={sev}
                    />
                  );
                })}
              </>
            )}
            <div style={{display: 'flex', gap: 6, marginTop: 12}}>
              <Button kind='secondary' block onClick={downloadPdf} disabled={busy}>
                {busy ? '…' : 'Download PDF'}
              </Button>
              <Button kind='ghost' block onClick={retryReport}>
                Retry
              </Button>
            </div>
          </>
        )}

        {tab === 'timeline' &&
          (timeline == null ? (
            <LoadingState label='Loading timeline…' />
          ) : (timeline.tasks || []).length === 0 ? (
            <Card>
              <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
                No timeline data yet. Tasks populate as the run progresses.
              </p>
            </Card>
          ) : (
            <Card style={{padding: 14}}>
              {(() => {
                const allTasks = timeline.tasks || [];
                const minT = Math.min(...allTasks.map((t) => new Date(t.startedAt).getTime()));
                const maxT = Math.max(
                  ...allTasks.map((t) =>
                    t.completedAt ? new Date(t.completedAt).getTime() : Date.now(),
                  ),
                );
                const span = Math.max(1, maxT - minT);
                return allTasks.map((t, i) => {
                  const start = new Date(t.startedAt).getTime();
                  const end = t.completedAt ? new Date(t.completedAt).getTime() : Date.now();
                  const leftPct = ((start - minT) / span) * 100;
                  const widthPct = Math.max(2, ((end - start) / span) * 100);
                  const durMs = end - start;
                  const m = Math.floor(durMs / 60000);
                  const s = Math.floor((durMs % 60000) / 1000);
                  const dur = m > 0 ? `${m}m ${s}s` : `${s}s`;
                  const isDone = t.status === 'COMPLETED' || t.status === 'DONE';
                  const isFailed = t.status === 'FAILED';
                  return (
                    <div key={t.taskId || i} style={{marginBottom: 12}}>
                      <div
                        style={{
                          display: 'flex',
                          justifyContent: 'space-between',
                          alignItems: 'baseline',
                          marginBottom: 4,
                          gap: 8,
                        }}
                      >
                        <div
                          style={{
                            fontSize: 13,
                            color: 'var(--signal-ink)',
                            minWidth: 0,
                            flex: 1,
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                          }}
                        >
                          {t.title || `Task ${i + 1}`}
                        </div>
                        <div
                          style={{
                            fontSize: 11,
                            color: 'var(--signal-text-dim)',
                            fontFamily: "'JetBrains Mono', monospace",
                            flexShrink: 0,
                          }}
                        >
                          {dur}
                        </div>
                      </div>
                      <div
                        style={{
                          position: 'relative',
                          height: 8,
                          background: 'var(--signal-card-elev)',
                          borderRadius: 4,
                          overflow: 'hidden',
                        }}
                      >
                        <div
                          style={{
                            position: 'absolute',
                            left: `${leftPct}%`,
                            width: `${widthPct}%`,
                            top: 0,
                            bottom: 0,
                            background: isFailed
                              ? 'var(--signal-coral)'
                              : isDone
                                ? 'var(--signal-mint)'
                                : 'var(--signal-y)',
                            borderRadius: 4,
                          }}
                        />
                      </div>
                    </div>
                  );
                });
              })()}
            </Card>
          ))}

        {tab === 'code' &&
          (files == null ? (
            <LoadingState label='Loading files…' />
          ) : files.length === 0 ? (
            <Card>
              <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
                No files touched by this run yet.
              </p>
            </Card>
          ) : (
            <div className='signal-split-runs signal-split-code'>
              <div
                className='split-list'
                style={{maxHeight: 'calc(100dvh - 200px)', overflowY: 'auto'}}
              >
                <FileTree
                  files={files as FileEntry[]}
                  selectedPath={selectedFile}
                  onSelect={(p) => setSelectedFile(p)}
                />
                <Button
                  kind='secondary'
                  sm
                  block
                  style={{marginTop: 12}}
                  onClick={() => id && api.downloadWorkspace(id).catch(() => undefined)}
                >
                  Download workspace
                </Button>
              </div>
              <div className='split-detail'>
                {!selectedFile ? (
                  <Card>
                    <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
                      Pick a file on the left to view its contents.
                    </p>
                  </Card>
                ) : (
                  <>
                    <div style={{display: 'flex', gap: 6, marginBottom: 8}}>
                      <Button
                        kind={!showDiff ? 'primary' : 'secondary'}
                        sm
                        onClick={() => setShowDiff(false)}
                      >
                        Content
                      </Button>
                      <Button
                        kind={showDiff ? 'primary' : 'secondary'}
                        sm
                        onClick={() => setShowDiff(true)}
                      >
                        Diff
                      </Button>
                    </div>
                    <CodeView
                      path={selectedFile}
                      content={showDiff ? undefined : (fileContent ?? '')}
                      diff={showDiff ? (fileDiff ?? '') : undefined}
                      maxHeight={'calc(100dvh - 290px)'}
                    />
                  </>
                )}
              </div>
            </div>
          ))}

        {tab === 'tests' &&
          (testReport == null ? (
            <LoadingState label='Loading test report…' />
          ) : (
            (() => {
              const tr = testReport as unknown as {
                results?: api.TestResultDetail[];
                totalRun?: number;
                totalPassed?: number;
                totalFailed?: number;
                suggestions?: api.FailureSuggestion[];
                rootCauses?: api.RootCauseGroup[];
              };
              const results = tr.results || [];
              const passed =
                tr.totalPassed ??
                results.filter((r) => (r as unknown as {status?: string}).status === 'PASSED')
                  .length;
              const failed =
                tr.totalFailed ??
                results.filter((r) => (r as unknown as {status?: string}).status === 'FAILED')
                  .length;
              const total = tr.totalRun ?? passed + failed;
              const rootCauses = tr.rootCauses || [];
              const suggestions = tr.suggestions || [];
              return (
                <>
                  <Card style={{padding: 16}}>
                    <KvList
                      pairs={[
                        {k: 'Total', v: String(total)},
                        {
                          k: 'Passed',
                          v: (
                            <span style={{color: 'var(--signal-mint-text-on-light)'}}>
                              {passed}
                            </span>
                          ),
                        },
                        {
                          k: 'Failed',
                          v: <span style={{color: 'var(--signal-coral-text)'}}>{failed}</span>,
                        },
                        {
                          k: 'Pass rate',
                          v: total > 0 ? `${Math.round((passed / total) * 100)}%` : '—',
                        },
                      ]}
                    />
                  </Card>

                  <Button
                    kind='secondary'
                    block
                    style={{marginTop: 10}}
                    onClick={async () => {
                      if (!id) return;
                      try {
                        await api.runTests(id);
                        setMsg('Test re-run queued.');
                      } catch (e: unknown) {
                        setErr(e instanceof Error ? e.message : 'Re-run failed.');
                      }
                    }}
                  >
                    Re-run tests
                  </Button>

                  {rootCauses.length > 0 && (
                    <>
                      <SectionHeader>Root causes · {rootCauses.length}</SectionHeader>
                      {rootCauses.map((g, i) => {
                        const gAny = g as unknown as {
                          signature?: string;
                          description?: string;
                          failureCount?: number;
                          suggestedFix?: string;
                        };
                        return (
                          <Card key={i} style={{marginBottom: 10}}>
                            <div style={{fontSize: 14, fontWeight: 600, marginBottom: 6}}>
                              {gAny.description || gAny.signature || `Root cause ${i + 1}`}
                            </div>
                            <div
                              style={{
                                fontSize: 12,
                                color: 'var(--signal-text-dim)',
                                marginBottom: 10,
                              }}
                            >
                              {gAny.failureCount ?? 0} affected test
                              {(gAny.failureCount ?? 0) === 1 ? '' : 's'}
                            </div>
                            {gAny.suggestedFix && (
                              <div
                                style={{
                                  background: 'var(--signal-card-elev)',
                                  padding: '10px 12px',
                                  borderRadius: 8,
                                  fontSize: 13,
                                  lineHeight: 1.5,
                                }}
                              >
                                <Markdown>{gAny.suggestedFix}</Markdown>
                              </div>
                            )}
                            {gAny.signature && id && (
                              <Button
                                kind='primary'
                                sm
                                style={{marginTop: 10}}
                                onClick={async () => {
                                  try {
                                    await api.applyTestFixes(id, gAny.signature);
                                    setMsg('Test-fix run queued.');
                                  } catch (e: unknown) {
                                    setErr(e instanceof Error ? e.message : 'Apply fix failed.');
                                  }
                                }}
                              >
                                Apply fix as new run
                              </Button>
                            )}
                          </Card>
                        );
                      })}
                    </>
                  )}

                  {suggestions.length > 0 && (
                    <>
                      <SectionHeader>Suggestions · {suggestions.length}</SectionHeader>
                      {suggestions.map((s, i) => {
                        const sAny = s as unknown as {title?: string; description?: string};
                        return (
                          <Card key={i} style={{marginBottom: 8}}>
                            <div style={{fontSize: 13.5, fontWeight: 600}}>
                              {sAny.title || `Suggestion ${i + 1}`}
                            </div>
                            {sAny.description && (
                              <div
                                style={{
                                  fontSize: 12,
                                  color: 'var(--signal-text-dim)',
                                  marginTop: 4,
                                }}
                              >
                                {sAny.description}
                              </div>
                            )}
                          </Card>
                        );
                      })}
                    </>
                  )}

                  <SectionHeader>All tests · {results.length}</SectionHeader>
                  {results.slice(0, 30).map((r, i) => {
                    const rAny = r as unknown as {
                      id?: string | number;
                      testName?: string;
                      testClass?: string;
                      status?: string;
                      durationMs?: number;
                      failureMessage?: string;
                    };
                    const status = (rAny.status || 'UNKNOWN').toUpperCase();
                    return (
                      <Row
                        key={rAny.id || i}
                        dot={
                          status === 'PASSED' ? 'mint' : status === 'FAILED' ? 'coral' : 'lavender'
                        }
                        title={rAny.testName || `Test ${i + 1}`}
                        meta={
                          <>
                            {rAny.testClass && <span className='mono'>{rAny.testClass}</span>}
                            {rAny.durationMs != null && (
                              <>
                                <span>·</span>
                                <span>{(rAny.durationMs / 1000).toFixed(2)}s</span>
                              </>
                            )}
                          </>
                        }
                        trailing={status}
                        trailingKind={
                          status === 'PASSED' ? 'good' : status === 'FAILED' ? 'fail' : 'default'
                        }
                      />
                    );
                  })}
                </>
              );
            })()
          ))}

        {tab === 'chat' && (
          <>
            <Card
              style={{
                background: 'var(--signal-lavender-soft)',
                borderColor: 'transparent',
                marginBottom: 12,
              }}
            >
              <div style={{fontSize: 13, color: '#5443a8', lineHeight: 1.55}}>
                Run-scoped chat — ask the agent to explain decisions, iterate on the implementation,
                or kick off a new run. "Iterate as new run" forks the spec.
              </div>
            </Card>
            <div
              style={{
                display: 'flex',
                flexDirection: 'column',
                gap: 10,
                marginBottom: 12,
                maxHeight: 'calc(100dvh - 360px)',
                minHeight: 260,
                overflowY: 'auto',
              }}
            >
              {chatMessages == null ? (
                <LoadingState label='Loading chat…' />
              ) : chatMessages.length === 0 && !chatBusy ? (
                <Card>
                  <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
                    No messages yet. Ask the agent anything about this run.
                  </p>
                </Card>
              ) : (
                chatMessages.map((m, i) => {
                  const isUser = (m as unknown as {role?: string}).role === 'user';
                  const content = (m as unknown as {content?: string}).content || '';
                  const ts =
                    (m as unknown as {timestamp?: string; sentAt?: string}).timestamp ||
                    (m as unknown as {sentAt?: string}).sentAt;
                  return (
                    <div
                      key={i}
                      style={{
                        alignSelf: isUser ? 'flex-end' : 'flex-start',
                        maxWidth: '85%',
                        padding: '10px 14px',
                        background: isUser ? 'var(--signal-ink)' : 'var(--signal-card-bg)',
                        color: isUser ? 'var(--signal-bg)' : 'var(--signal-ink)',
                        borderRadius: 14,
                        ...(isUser
                          ? {borderBottomRightRadius: 4}
                          : {
                              borderBottomLeftRadius: 4,
                              border: '1px solid var(--signal-hairline)',
                            }),
                      }}
                    >
                      <div
                        style={{
                          fontFamily: "'JetBrains Mono', monospace",
                          fontSize: 10,
                          opacity: 0.6,
                          marginBottom: 4,
                        }}
                      >
                        {isUser ? 'YOU' : 'RUN COPILOT'}
                        {ts && ` · ${new Date(ts).toLocaleTimeString()}`}
                      </div>
                      {/* Both sides render Markdown so the chat fully supports formatting. */}
                      <Markdown>{content}</Markdown>
                    </div>
                  );
                })
              )}
              {chatBusy && (
                <div
                  style={{
                    alignSelf: 'flex-start',
                    padding: '8px 14px',
                    color: 'var(--signal-text-dim)',
                    fontSize: 12,
                  }}
                >
                  Thinking…
                </div>
              )}
              <div ref={chatEndRef} />
            </div>
            <div style={{display: 'flex', gap: 8, alignItems: 'flex-end', flexWrap: 'wrap'}}>
              <textarea
                value={chatInput}
                onChange={(e) => setChatInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    void sendChat();
                  }
                }}
                placeholder='Ask anything about this run…'
                rows={2}
                disabled={chatBusy}
                style={{
                  flex: 1,
                  background: 'var(--signal-card-elev)',
                  border: '1px solid var(--signal-hairline)',
                  borderRadius: 12,
                  padding: '10px 14px',
                  fontFamily: 'inherit',
                  fontSize: 14,
                  color: 'var(--signal-ink)',
                  resize: 'vertical',
                  outline: 'none',
                  minHeight: 60,
                  maxHeight: 200,
                }}
              />
              <div style={{display: 'flex', flexDirection: 'column', gap: 6, flexShrink: 0}}>
                <Button
                  kind='primary'
                  sm
                  onClick={sendChat}
                  disabled={chatBusy || !chatInput.trim()}
                >
                  Send
                </Button>
                <Button
                  kind='secondary'
                  sm
                  onClick={iterateAsNewRun}
                  disabled={chatBusy || !(chatMessages && chatMessages.length > 0)}
                >
                  Iterate as new run
                </Button>
              </div>
            </div>
          </>
        )}
      </Feed>
    </>
  );
}
