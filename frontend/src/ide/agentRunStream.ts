import {useCallback, useEffect, useRef, useState} from 'react';
import * as api from '@/lib/api';

export type AgentRunStreamStatus = 'IDLE' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING' | 'CLOSED';

export type AgentRunEvent = {
  type?: string;
  payload?: unknown;
  data?: Record<string, unknown>;
  [key: string]: unknown;
};

export function parseAgentRunEvent(raw: string): AgentRunEvent | null {
  try {
    const value = JSON.parse(raw) as unknown;
    return value && typeof value === 'object' ? (value as AgentRunEvent) : null;
  } catch {
    return null;
  }
}

function payloadObject(event: AgentRunEvent): Record<string, unknown> | null {
  if (event.data && typeof event.data === 'object') return event.data;
  if (typeof event.payload !== 'string') return null;
  try {
    const value = JSON.parse(event.payload) as unknown;
    return value && typeof value === 'object' ? (value as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

function terminal(status: string | undefined): boolean {
  // COMPLETE/RUN_STATUS payloads may carry the human-readable status reason after a
  // pipe (for example `PARTIAL|FINAL_BUILD_FAILED`). Keep the transport detail
  // from preventing the stream from reaching its terminal state.
  const normalized = (status || '').toUpperCase().split('|', 1)[0].trim();
  return ['COMPLETE', 'COMPLETED', 'FAILED', 'ERROR', 'CANCELLED', 'PARTIAL'].includes(normalized);
}

/**
 * Return a terminal status carried by a run event, if any.
 *
 * The backend intentionally keeps both the legacy `payload` and typed event
 * fields. A completed run can therefore arrive as a RUN_STATUS event, a
 * COMPLETE event, or a RUN_DETAIL snapshot. Centralising this decoding keeps
 * EventSource completion/reconnect behaviour deterministic across all three.
 */
export function terminalStatusFromEvent(event: AgentRunEvent): string | null {
  const type = String(event.type || '').toUpperCase();
  const value = payloadObject(event);
  const candidate =
    type === 'RUN_DETAIL'
      ? value?.status
      : type === 'RUN_STATUS' || type === 'COMPLETE'
        ? event.payload ?? event.status ?? value?.status
        : null;
  if (candidate == null) return null;
  const status = String(candidate);
  return terminal(status) ? status : null;
}

export function useAgentRunStream(runId: string | null) {
  const [detail, setDetail] = useState<api.RunDetail | null>(null);
  const [costUsd, setCostUsd] = useState<number | null>(null);
  const [status, setStatus] = useState<AgentRunStreamStatus>('IDLE');
  const sourceRef = useRef<EventSource | null>(null);
  const retryRef = useRef<ReturnType<typeof setTimeout> | undefined>();
  const attemptRef = useRef(0);
  const lastEventIdRef = useRef<string | null>(null);
  const terminalRef = useRef(false);

  const refresh = useCallback(async () => {
    if (!runId) return;
    const [nextDetail, nextCost] = await Promise.all([
      api.getRun(runId),
      api.getLiveCost(runId).catch(() => null),
    ]);
    setDetail(nextDetail);
    if (nextCost && typeof nextCost.costUsd === 'number') setCostUsd(nextCost.costUsd);
    terminalRef.current = terminal(nextDetail.status);
  }, [runId]);

  useEffect(() => {
    sourceRef.current?.close();
    if (retryRef.current) clearTimeout(retryRef.current);
    terminalRef.current = false;
    setDetail(null);
    setCostUsd(null);
    attemptRef.current = 0;
    lastEventIdRef.current = null;
    if (!runId) {
      setStatus('IDLE');
      return;
    }

    let alive = true;
    void refresh().catch(() => undefined);
    const connect = async () => {
      if (!alive || terminalRef.current || typeof EventSource === 'undefined') {
        if (alive && typeof EventSource === 'undefined') setStatus('CLOSED');
        return;
      }
      setStatus(attemptRef.current === 0 ? 'CONNECTING' : 'RECONNECTING');
      let url = `/api/runs/${runId}/events`;
      try {
        const token = await api.getSseToken(runId);
        url += `?token=${encodeURIComponent(token.token)}`;
      } catch {
        // Initial snapshot remains useful if the scoped stream token is unavailable.
      }
      if (lastEventIdRef.current) {
        url += `${url.includes('?') ? '&' : '?'}lastEventId=${encodeURIComponent(lastEventIdRef.current)}`;
      }
      if (!alive) return;
      const source = new EventSource(url);
      sourceRef.current = source;
      source.onopen = () => {
        if (!alive) return;
        setStatus('CONNECTED');
        const reconnect = attemptRef.current > 0;
        attemptRef.current = 0;
        if (reconnect) void refresh().catch(() => undefined);
      };
      source.onmessage = (message) => {
        const event = parseAgentRunEvent(message.data);
        if (!event || !alive) return;
        if (message.lastEventId) lastEventIdRef.current = message.lastEventId;
        const type = String(event.type || '').toUpperCase();
        if (type === 'RUN_DETAIL') {
          const value = payloadObject(event);
          if (value) {
            setDetail(value as unknown as api.RunDetail);
            terminalRef.current = terminal(String(value.status || ''));
          }
        } else if (type === 'TOKEN_USAGE') {
          const value = payloadObject(event);
          const nextCost = Number(value?.costUsd ?? event.costUsd);
          if (Number.isFinite(nextCost)) setCostUsd(nextCost);
        } else if (type === 'RUN_STATUS' || type === 'COMPLETE') {
          terminalRef.current = terminalStatusFromEvent(event) !== null;
          if (terminalRef.current) void refresh().catch(() => undefined);
        }
        if (terminalRef.current) {
          source.close();
          sourceRef.current = null;
          setStatus('CLOSED');
        }
      };
      source.onerror = () => {
        source.close();
        sourceRef.current = null;
        if (!alive || terminalRef.current) return;
        setStatus('RECONNECTING');
        const delay = [1000, 2000, 4000, 8000, 16000, 30000][Math.min(attemptRef.current, 5)];
        attemptRef.current += 1;
        retryRef.current = setTimeout(() => void connect(), delay);
      };
    };
    void connect();
    return () => {
      alive = false;
      sourceRef.current?.close();
      sourceRef.current = null;
      if (retryRef.current) clearTimeout(retryRef.current);
      setStatus('CLOSED');
    };
  }, [refresh, runId]);

  return {detail, costUsd, status, refresh};
}
