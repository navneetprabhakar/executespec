import { useEffect, useRef } from 'react';
import { useSseStore } from '@/stores/useSseStore';
import { useRunStore } from '@/stores/useRunStore';

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

    function connect() {
      esRef.current?.close();
      setStatus(attemptRef.current === 0 ? 'CONNECTING' : 'RECONNECTING');

      const es = new EventSource(`/api/runs/${runId}/events`);
      esRef.current = es;

      es.onopen = () => {
        setStatus('CONNECTED');
        attemptRef.current = 0;
      };

      es.onmessage = (ev) => {
        try {
          const data = JSON.parse(ev.data);
          const entry = {
            id: `${Date.now()}-${Math.random()}`,
            type: data.type ?? 'log',
            data,
            timestamp: Date.now(),
          };
          addEntry(entry);

          if (data.type === 'runStatus' && data.status) {
            patchRunStatus(runId!, data.status);
            if (['COMPLETE', 'FAILED', 'CANCELLED'].includes(data.status)) {
              fetchRun(runId!);
            }
          }
          if (data.type === 'complete' || data.type === 'runRestarted') {
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
