import {useEffect, useRef} from 'react';
import {useSseStore} from '@/stores/useSseStore';
import {useRunStore} from '@/stores/useRunStore';
import {getMessages, getSseToken} from '@/lib/api';
import {refreshUsageBadge} from '@/components/billing/UsageBadge';

const BACKOFF = [1000, 2000, 4000, 8000, 16000, 30000];

// F-06 fix (2026-05-09) — silent-stall watchdog. The scoped SSE token has a
// 5-minute backend TTL; on a long-running run the open EventSource keeps
// streaming until either the token is rejected (server-side close → onerror
// fires → connect() retries with a fresh token) OR the server silently stops
// pushing events (token expired but socket stayed open at the proxy layer →
// no onerror, log appears to freeze).
// Watchdog: if no event arrives within STALL_THRESHOLD_MS, force-close +
// reconnect, which fetches a fresh token. Proven defense against the
// "live-log frozen for 5 min on a 12-min run" failure mode.
const STALL_THRESHOLD_MS = 60_000;

export function useSse(runId: string | null) {
  const {setStatus, addEntry, clearEntries, setRunId} = useSseStore();
  const {patchRunStatus, fetchRun} = useRunStore();
  const esRef = useRef<EventSource | null>(null);
  const attemptRef = useRef(0);
  const timeoutRef = useRef<ReturnType<typeof setTimeout>>();
  // F-06 fix (2026-05-09) — last-event timestamp + watchdog interval, used
  // to detect silent stalls (token expired server-side but proxy didn't
  // close the socket).
  const lastEventTsRef = useRef<number>(Date.now());
  const watchdogRef = useRef<ReturnType<typeof setInterval>>();
  // F-02 fix (2026-05-09) — track the last seq received so reconnects
  // resume from the right point (server replays from lastEventId+1) and
  // we never apply the same event twice. Backed by SseBroadcaster's
  // append-to-Redis-list replay path; the broadcaster already emits
  // SseEmitter.event().id(seqNum), so EventSource exposes ev.lastEventId
  // as the seq string.
  const lastEventIdRef = useRef<string | null>(null);
  // Local short-cycle dedup: if we replay a seq we already saw, skip the
  // delivery. Bounded size — the last 200 seqs are enough for any
  // reasonable reconnect window because the broadcaster caps replay at 500.
  const seenSeqsRef = useRef<Set<string>>(new Set());
  const seenSeqsOrderRef = useRef<string[]>([]);

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
    if (isNewRun) clearEntries();
    setRunId(runId);
    attemptRef.current = 0;

    // Pre-populate with historical messages so revisiting a completed run shows its log.
    // Only do this for NEW run IDs so we don't double-prepend history on remount.
    if (isNewRun) {
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

    async function connect() {
      esRef.current?.close();
      setStatus(attemptRef.current === 0 ? 'CONNECTING' : 'RECONNECTING');

      // Fetch a short-lived scoped SSE token (avoids exposing full JWT in URL)
      let sseUrl = `/api/runs/${runId}/events`;
      try {
        // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
        const {token: sseToken} = await getSseToken(runId!);
        sseUrl = `/api/runs/${runId}/events?token=${encodeURIComponent(sseToken)}`;
      } catch {
        console.warn('Failed to obtain scoped SSE token, connecting without auth');
      }
      // F-02 — pass lastEventId so the backend replays from there (it's
      // the same value the SseBroadcaster.subscribe(runId, lastEventId)
      // path already accepts). Dedup below catches anything the server
      // re-sends that we somehow already applied.
      if (lastEventIdRef.current) {
        sseUrl +=
          (sseUrl.includes('?') ? '&' : '?') +
          'lastEventId=' +
          encodeURIComponent(lastEventIdRef.current);
      }
      const es = new EventSource(sseUrl);
      esRef.current = es;

      es.onopen = () => {
        setStatus('CONNECTED');
        const wasReconnect = attemptRef.current > 0;
        attemptRef.current = 0;
        // On reconnect, do a full fetch to catch any events missed during the gap.
        // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
        if (wasReconnect) fetchRun(runId!);
      };

      es.onmessage = (ev) => {
        // F-06 — every received event resets the stall watchdog.
        lastEventTsRef.current = Date.now();

        // F-02 — short-cycle dedup. SseBroadcaster's replay log can re-emit
        // events the client already saw on a reconnect (especially after a
        // watchdog-forced reconnect that fires inside the replay window).
        // Skip events whose seq we've already applied; without this, the
        // RUN_DETAIL re-application can roll the store backward to an
        // older snapshot.
        const seq = ev.lastEventId;
        if (seq) {
          if (seenSeqsRef.current.has(seq)) {
            return;
          }
          seenSeqsRef.current.add(seq);
          seenSeqsOrderRef.current.push(seq);
          // Bounded — keep at most 200 (broadcaster replay caps at 500).
          if (seenSeqsOrderRef.current.length > 200) {
            const evicted = seenSeqsOrderRef.current.shift();
            if (evicted) seenSeqsRef.current.delete(evicted);
          }
          lastEventIdRef.current = seq;
        }
        try {
          const data = JSON.parse(ev.data);

          // ── RUN_DETAIL: backend pushes full run detail — update store directly ──
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
            id: `${Date.now()}-${Math.random()}`,
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
            // Credits charged when run transitions to WORKING — refresh sidebar usage
            if (data.payload === 'WORKING') {
              refreshUsageBadge();
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
        } catch {}
      };

      es.onerror = () => {
        es.close();
        esRef.current = null;
        setStatus('RECONNECTING');
        const delay = BACKOFF[Math.min(attemptRef.current, BACKOFF.length - 1)];
        attemptRef.current++;
        timeoutRef.current = setTimeout(connect, delay);
      };

      // F-06 — reset watchdog timestamp on successful connect.
      lastEventTsRef.current = Date.now();
    }

    connect();

    // F-06 — silent-stall watchdog. Runs every 15s; if last event was >60s
    // ago AND the run is still non-terminal, force-reconnect to pull a
    // fresh SSE token + a fresh socket. Skips terminal runs because no
    // events should arrive on those — silence isn't a failure.
    watchdogRef.current = setInterval(() => {
      const stalled = Date.now() - lastEventTsRef.current > STALL_THRESHOLD_MS;
      if (!stalled) return;
      const active = useRunStore.getState().activeRun;
      const status = active?.status ?? '';
      const TERMINAL = new Set(['COMPLETE', 'PARTIAL', 'FAILED', 'CANCELLED', 'ABORTED']);
      if (TERMINAL.has(status)) return;
      console.warn('[useSse] silent stall detected; forcing reconnect');
      esRef.current?.close();
      esRef.current = null;
      lastEventTsRef.current = Date.now();
      // Reset attempt counter so we reconnect immediately rather than
      // accumulating exponential backoff from the previous gap.
      attemptRef.current = 0;
      connect();
    }, 15_000);

    return () => {
      esRef.current?.close();
      clearTimeout(timeoutRef.current);
      clearInterval(watchdogRef.current);
      setStatus('DISCONNECTED');
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId]);
}
