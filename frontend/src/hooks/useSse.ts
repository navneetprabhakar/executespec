import { useEffect, useRef } from 'react';
import { useSseStore } from '@/stores/useSseStore';
import { useRunStore } from '@/stores/useRunStore';
import { getMessages } from '@/lib/api';

const BACKOFF = [1000, 2000, 4000, 8000, 16000, 30000];

export function useSse(runId: string | null) {
  const { setStatus, addEntry, clearEntries, setRunId } = useSseStore();
  const { patchRunStatus, fetchRun } = useRunStore();
  const esRef = useRef<EventSource | null>(null);
  const attemptRef = useRef(0);
  const timeoutRef = useRef<ReturnType<typeof setTimeout>>();

  useEffect(() => {
    if (!runId) {
      esRef.current?.close();
      esRef.current = null;
      setStatus('DISCONNECTED');
      setRunId(null);
      return;
    }

    clearEntries();
    setRunId(runId);
    attemptRef.current = 0;

    // Pre-populate with historical messages so revisiting a completed run shows its log.
    getMessages(runId).then((msgs) => {
      msgs.forEach((msg, i) => {
        addEntry({
          id: `hist-${i}-${msg.sentAt}`,
          type: 'log',
          data: { message: `[${msg.fromAgent}] ${msg.contentSummary}` },
          timestamp: new Date(msg.sentAt).getTime(),
        });
      });
    }).catch(() => { /* non-fatal */ });

    function connect() {
      esRef.current?.close();
      setStatus(attemptRef.current === 0 ? 'CONNECTING' : 'RECONNECTING');

      // Pass JWT via query param (EventSource can't set Authorization headers)
      const token = localStorage.getItem('access_token');
      const sseUrl = token
        ? `/api/runs/${runId}/events?token=${encodeURIComponent(token)}`
        : `/api/runs/${runId}/events`;
      const es = new EventSource(sseUrl);
      esRef.current = es;

      es.onopen = () => {
        setStatus('CONNECTED');
        const wasReconnect = attemptRef.current > 0;
        attemptRef.current = 0;
        // On reconnect, do a full fetch to catch any events missed during the gap.
        if (wasReconnect) fetchRun(runId!);
      };

      es.onmessage = (ev) => {
        try {
          const data = JSON.parse(ev.data);
          const entry = {
            id: `${Date.now()}-${Math.random()}`,
            type: data.type ?? 'LOG',
            data,
            timestamp: Date.now(),
          };
          addEntry(entry);

          // Backend emits type as SCREAMING_SNAKE_CASE; payload carries the status string.
          if (data.type === 'RUN_STATUS' && data.payload) {
            patchRunStatus(runId!, data.payload);
            // For terminal states or transitions that add tasks, do a full refetch.
            if (['COMPLETE', 'FAILED', 'CANCELLED', 'PAUSED', 'AWAITING_APPROVAL', 'AWAITING_MERGE', 'PARTIAL'].includes(data.payload)) {
              fetchRun(runId!);
            }
          }
          // Refresh task list whenever any task changes state
          if (data.type === 'TASK_STATUS') {
            fetchRun(runId!);
          }
          // Gate opened = plan tasks are now saved; refresh so the detail panel shows them
          if (data.type === 'GATE_OPENED') {
            fetchRun(runId!);
          }
          if (data.type === 'COMPLETE' || data.type === 'RUN_RESTARTED') {
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
    }

    connect();

    return () => {
      esRef.current?.close();
      clearTimeout(timeoutRef.current);
      setStatus('DISCONNECTED');
    };
  }, [runId]);
}
