import {useCallback, useEffect, useRef, useState} from 'react';
import * as api from '@/lib/api';

export type AgentRunUnavailableReason = 'RUN_NOT_FOUND' | 'AUTH_REQUIRED' | 'ACCESS_DENIED';

const UNAVAILABLE_MESSAGES: Record<AgentRunUnavailableReason, string> = {
  RUN_NOT_FOUND: 'Run no longer available',
  AUTH_REQUIRED: 'Sign in again to view this run',
  ACCESS_DENIED: 'You do not have access to this run',
};

export function agentRunUnavailableMessage(reason?: AgentRunUnavailableReason | null) {
  return reason ? UNAVAILABLE_MESSAGES[reason] : null;
}

export type AgentRunStreamStatus = 'IDLE' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING' | 'CLOSED';

export type AgentRunEvent = {
  type?: string;
  payload?: unknown;
  data?: Record<string, unknown>;
  [key: string]: unknown;
};

const RECONNECT_BACKOFF_MS = [1000, 2000, 4000, 8000, 16000, 30000] as const;
const RECONNECT_MAX_MS = 30000;
const RECONNECT_STABLE_MS = 15000;
const RECONNECT_MAX_ATTEMPTS = 12;

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

/** Classify errors that cannot be recovered by reconnecting the run stream. */
export function unavailableReason(error: unknown): AgentRunUnavailableReason | null {
  const status =
    typeof error === 'object' && error !== null && 'status' in error
      ? Number((error as {status?: unknown}).status)
      : null;
  if (status === 404) return 'RUN_NOT_FOUND';
  if (status === 401) return 'AUTH_REQUIRED';
  if (status === 403) return 'ACCESS_DENIED';
  return null;
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
        ? (event.payload ?? event.status ?? value?.status)
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
  const stableRef = useRef<ReturnType<typeof setTimeout> | undefined>();
  const attemptRef = useRef(0);
  const lastEventIdRef = useRef<string | null>(null);
  const terminalRef = useRef(false);
  const unavailableRef = useRef<AgentRunUnavailableReason | null>(null);
  const [unavailable, setUnavailable] = useState<AgentRunUnavailableReason | null>(null);

  const markUnavailable = useCallback((reason: AgentRunUnavailableReason) => {
    unavailableRef.current = reason;
    terminalRef.current = true;
    sourceRef.current?.close();
    sourceRef.current = null;
    if (retryRef.current) clearTimeout(retryRef.current);
    if (stableRef.current) clearTimeout(stableRef.current);
    retryRef.current = undefined;
    stableRef.current = undefined;
    setUnavailable(reason);
    setStatus('CLOSED');
  }, []);

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
    if (stableRef.current) clearTimeout(stableRef.current);
    terminalRef.current = false;
    unavailableRef.current = null;
    setUnavailable(null);
    setDetail(null);
    setCostUsd(null);
    attemptRef.current = 0;
    lastEventIdRef.current = null;
    if (!runId) {
      setStatus('IDLE');
      return;
    }

    let alive = true;
    const clearStableTimer = () => {
      if (stableRef.current) clearTimeout(stableRef.current);
      stableRef.current = undefined;
    };
    const scheduleReconnect = () => {
      if (!alive || terminalRef.current || unavailableRef.current) return;
      clearStableTimer();
      if (attemptRef.current >= RECONNECT_MAX_ATTEMPTS) {
        setStatus('CLOSED');
        return;
      }
      setStatus('RECONNECTING');
      const delay = Math.min(
        RECONNECT_MAX_MS,
        RECONNECT_BACKOFF_MS[Math.min(attemptRef.current, RECONNECT_BACKOFF_MS.length - 1)],
      );
      attemptRef.current += 1;
      retryRef.current = setTimeout(() => void connect(), delay);
    };
    const connect = async () => {
      if (
        !alive ||
        terminalRef.current ||
        unavailableRef.current ||
        typeof EventSource === 'undefined'
      ) {
        if (alive && typeof EventSource === 'undefined') setStatus('CLOSED');
        return;
      }
      setStatus(attemptRef.current === 0 ? 'CONNECTING' : 'RECONNECTING');
      let token: string;
      try {
        token = (await api.getSseToken(runId)).token;
        if (!token) throw new Error('Scoped SSE token was empty.');
      } catch (error) {
        const reason = unavailableReason(error);
        if (reason && alive) {
          markUnavailable(reason);
          return;
        }
        // EventSource cannot send an Authorization header. Opening it without the scoped token
        // creates a guaranteed 401 loop, so keep the last durable snapshot and retry the token
        // request under the same bounded reconnect policy instead.
        scheduleReconnect();
        return;
      }
      let url = `/api/runs/${runId}/events?token=${encodeURIComponent(token)}`;
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
        // A socket that opens and immediately dies is not healthy. Reset the backoff only after
        // the connection stays up long enough to prove stability, matching the main run stream.
        clearStableTimer();
        stableRef.current = setTimeout(() => {
          attemptRef.current = 0;
          stableRef.current = undefined;
        }, RECONNECT_STABLE_MS);
        if (reconnect) {
          void refresh().catch((error) => {
            const reason = unavailableReason(error);
            if (reason && alive) markUnavailable(reason);
          });
        }
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
          clearStableTimer();
          if (retryRef.current) clearTimeout(retryRef.current);
          retryRef.current = undefined;
          source.close();
          sourceRef.current = null;
          setStatus('CLOSED');
        }
      };
      source.onerror = () => {
        source.close();
        sourceRef.current = null;
        if (!alive || terminalRef.current) return;
        scheduleReconnect();
      };
    };
    void (async () => {
      try {
        await refresh();
      } catch (error) {
        const reason = unavailableReason(error);
        if (reason && alive) {
          markUnavailable(reason);
          return;
        }
      }
      if (alive && !terminalRef.current && !unavailableRef.current) void connect();
    })();
    return () => {
      alive = false;
      sourceRef.current?.close();
      sourceRef.current = null;
      if (retryRef.current) clearTimeout(retryRef.current);
      if (stableRef.current) clearTimeout(stableRef.current);
      retryRef.current = undefined;
      stableRef.current = undefined;
      setStatus('CLOSED');
    };
  }, [markUnavailable, refresh, runId]);

  return {detail, costUsd, status, refresh, unavailable};
}
