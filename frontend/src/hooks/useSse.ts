import {useEffect, useRef} from 'react';
import {useSseStore} from '@/stores/useSseStore';
import {useRunStore} from '@/stores/useRunStore';
import {getMessages, getRunEventLog, getSseToken} from '@/lib/api';
import {replayEntry, replayNotice, runEventKey} from '@/lib/runReplay';

const BACKOFF = [1000, 2000, 4000, 8000, 16000, 30000];
/** RR-202 — the advertised reconnect ceiling; jitter never pushes past it. */
const BACKOFF_MAX = 30000;
/** RR-202 — a connection must stay open this long before the backoff counter is
 * reset. A socket that opens then dies milliseconds later must NOT reset to the
 * 1s delay (that is the reconnect storm); it keeps climbing toward the ceiling. */
const STABLE_MS = 15000;
/** RR-202 — retry budget. A stream that never stabilizes stops storming after
 * this many consecutive attempts and surfaces an error state instead. The
 * counter is cleared once a connection proves stable, so a healthy long-lived
 * run never exhausts it. */
const MAX_ATTEMPTS = 12;

export function useSse(runId: string | null) {
  const {setStatus, addEntry, hydrateEntries, clearEntries, setRunId, bumpFileWrittenTick} =
    useSseStore();
  const {patchRunStatus, fetchRun} = useRunStore();
  const esRef = useRef<EventSource | null>(null);
  const attemptRef = useRef(0);
  const lastEventIdRef = useRef<string | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout>>();
  const stableTimerRef = useRef<ReturnType<typeof setTimeout>>();

  useEffect(() => {
    if (!runId) {
      esRef.current?.close();
      esRef.current = null;
      setStatus('DISCONNECTED');
      setRunId(null);
      return;
    }

    // Only clear the log buffer when switching to a different run. Remounting the
    // hook for the SAME run (e.g. after a status flip re-renders RunDetail, or an
    // SSE reconnect re-triggers the effect) should preserve the entries collected
    // so far — otherwise the live log appears to vanish when a run completes.
    const prevRunId = useSseStore.getState().currentRunId;
    const isNewRun = prevRunId !== runId;
    // A token request can outlive this effect (for example when navigating from a live
    // run to another route). Keep a generation-local guard so a late response cannot
    // resurrect an EventSource for an unmounted run or schedule reconnects into a new
    // run's store state.
    let active = true;
    if (isNewRun) clearEntries();
    setRunId(runId);
    attemptRef.current = 0;
    lastEventIdRef.current = null;

    // Hydrate the durable event stream before attaching live SSE. The response carries the last
    // durable sequence, so the subsequent subscription asks the server to replay any event that
    // landed in the small fetch-to-connect gap.
    // Only do this for NEW run IDs so we don't double-prepend history on remount.
    if (isNewRun) {
      getRunEventLog(runId)
        .then((replay) => {
          if (!active) return;
          hydrateEntries(replay.events.map(replayEntry), replayNotice(replay));
          lastEventIdRef.current = replay.lastEventId;
        })
        .catch(() => {
          if (active)
            hydrateEntries([], 'Earlier activity could not be loaded. Live events will continue.');
        })
        .finally(() => {
          if (active) void connect();
        });
      getMessages(runId)
        .then((msgs) => {
          msgs.forEach((msg, i) => {
            addEntry({
              id: `hist-${i}-${msg.sentAt}`,
              type: 'LOG',
              data: {
                message: `[${msg.fromAgent}] ${msg.contentSummary}`,
                payload: `[${msg.fromAgent}] ${msg.contentSummary}`,
              },
              timestamp: new Date(msg.sentAt).getTime(),
            });
          });
        })
        .catch(() => {
          /* non-fatal */
        });
    }

    function clearStableTimer() {
      if (stableTimerRef.current) {
        clearTimeout(stableTimerRef.current);
        stableTimerRef.current = undefined;
      }
    }

    // RR-202 — schedule the next reconnect under a jittered, ceiling-capped
    // backoff and a retry budget. Called from onerror and from a token failure.
    function scheduleReconnect() {
      if (!active) return;
      clearStableTimer();
      if (attemptRef.current >= MAX_ATTEMPTS) {
        // Budget exhausted without ever stabilizing — stop the storm and surface
        // an error. The RunDetail background poll keeps run data fresh meanwhile.
        setStatus('ERROR');
        return;
      }
      setStatus('RECONNECTING');
      const base = BACKOFF[Math.min(attemptRef.current, BACKOFF.length - 1)];
      // Jitter spreads simultaneous reconnects; capped so the ceiling holds.
      const jitter = Math.random() * Math.min(1000, base * 0.25);
      const delay = Math.min(BACKOFF_MAX, base + jitter);
      attemptRef.current++;
      timeoutRef.current = setTimeout(() => {
        if (active) void connect();
      }, delay);
    }

    async function connect() {
      if (!active) return;
      esRef.current?.close();
      clearStableTimer();
      setStatus(attemptRef.current === 0 ? 'CONNECTING' : 'RECONNECTING');

      // Fetch a short-lived scoped SSE token (avoids exposing full JWT in URL).
      // RR-203 — the token is REQUIRED. EventSource cannot attach a bearer, so an
      // unauthenticated stream would just loop on 401. On token failure, surface
      // the error via the bounded backoff/budget rather than silently degrading
      // to an unauthenticated connection.
      let sseToken: string;
      try {
        // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
        sseToken = (await getSseToken(runId!)).token;
      } catch {
        if (!active) return;
        console.warn('Failed to obtain scoped SSE token; will retry');
        scheduleReconnect();
        return;
      }
      if (!active) return;
      let sseUrl = `/api/runs/${runId}/events?token=${encodeURIComponent(sseToken)}`;
      if (lastEventIdRef.current) {
        sseUrl += `&lastEventId=${encodeURIComponent(lastEventIdRef.current)}`;
      }
      const es = new EventSource(sseUrl);
      esRef.current = es;

      es.onopen = () => {
        if (!active) return;
        setStatus('CONNECTED');
        const wasReconnect = attemptRef.current > 0;
        // RR-202 — reset the backoff counter only after the connection proves
        // stable for STABLE_MS, not the instant it opens. This is the fix for the
        // reconnect storm: a flapping socket keeps climbing to the 30s ceiling.
        clearStableTimer();
        stableTimerRef.current = setTimeout(() => {
          attemptRef.current = 0;
        }, STABLE_MS);
        // On reconnect, do a full fetch to catch any events missed during the gap.
        // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
        if (wasReconnect) fetchRun(runId!);
      };

      es.onmessage = (ev) => {
        if (!active) return;
        try {
          if (ev.lastEventId) lastEventIdRef.current = ev.lastEventId;
          const data = JSON.parse(ev.data);

          // RUN_DETAIL: backend pushes full run detail — update store directly
          if (data.type === 'RUN_DETAIL' && data.payload) {
            try {
              const detail = JSON.parse(data.payload);
              useRunStore.setState({activeRun: detail});
            } catch {
              // Malformed payload — fall back to refetch
              // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
              fetchRun(runId!);
            }
            return; // Don't add RUN_DETAIL to the log buffer
          }

          const entry = {
            id: runEventKey(data, ev.lastEventId),
            type: data.type ?? 'LOG',
            data,
            timestamp: Date.now(),
          };
          addEntry(entry);

          // Backend emits type as SCREAMING_SNAKE_CASE; payload carries the status string.
          if (data.type === 'RUN_STATUS' && data.payload) {
            // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
            patchRunStatus(runId!, data.payload);
            // RUN_DETAIL push handles the full refetch — only fetch here as fallback
            // for terminal states where the SSE stream may close before RUN_DETAIL arrives.
            if (
              [
                'COMPLETE',
                'FAILED',
                'CANCELLED',
                'COMPILING',
                'WORKING',
                'QUEUED_BATCH',
                'IN_PROGRESS_BATCH',
              ].includes(data.payload)
            ) {
              // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
              fetchRun(runId!);
            }
          }
          // Gate opened = plan tasks are now saved; RUN_DETAIL push covers this
          // but keep as fallback for reliable gate display
          if (data.type === 'GATE_OPENED') {
            // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
            fetchRun(runId!);
          }
          if (data.type === 'RUN_RESTARTED' || data.type === 'BUDGET_EXCEEDED') {
            // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
            fetchRun(runId!);
          }
          // Batch progress/completion triggers refetch
          if (
            data.type === 'BATCH_PROGRESS' ||
            data.type === 'BATCH_COMPLETE' ||
            data.type === 'BATCH_FAILED'
          ) {
            // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
            fetchRun(runId!);
          }
          // PR-C (2026-05-13) — file-tree push. FILE_WRITTEN fires from WriteFileTool +
          // EditFileTool the moment a worker tool call lands. Bump the store tick; the
          // file-tree component subscribes to it and re-fetches (debounced) on change.
          // We deliberately don't call fetchRun() here — file changes don't affect run
          // metadata, only the listing endpoint, so this stays cheap.
          if (data.type === 'FILE_WRITTEN') {
            bumpFileWrittenTick();
          }
        } catch {}
      };

      es.onerror = () => {
        if (!active) return;
        es.close();
        esRef.current = null;
        // RR-202 — go through the shared jittered/budgeted backoff scheduler.
        scheduleReconnect();
      };
    }

    if (!isNewRun) void connect();

    return () => {
      active = false;
      esRef.current?.close();
      clearTimeout(timeoutRef.current);
      clearStableTimer();
      setStatus('DISCONNECTED');
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId]);
}
