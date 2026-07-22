import type {RunLogEvent, RunReplayLog} from '@/lib/api';
import type {LogEntry} from '@/stores/useSseStore';

function scalar(value: unknown): string {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : '';
}

/** Stable identity shared by durable replay rows and their live SSE duplicate. */
export function runEventKey(event: RunLogEvent, transportId?: string | null): string {
  const data =
    event.data && typeof event.data === 'object' ? (event.data as Record<string, unknown>) : {};
  const explicit =
    scalar(transportId) ||
    scalar(event.eventId) ||
    scalar(event._eventId) ||
    scalar(event.actionId) ||
    scalar(data.eventId) ||
    scalar(data.actionId);
  if (explicit) return `event:${explicit}`;

  // Older retained rows predate sequence IDs. This deterministic composite still prevents the
  // same row from appearing twice when replay hydration races the first live frame.
  return [
    'legacy',
    scalar(event.type),
    scalar(event.timestamp),
    scalar(event.taskId),
    scalar(event.payload),
    scalar(event.message),
    scalar(data.stepId),
  ].join(':');
}

/**
 * RR-1381 — persisted replay carries an ISO-8601 timestamp string (`Instant` serialization), while
 * live SSE frames may carry a numeric epoch. Accept either: a finite number passes through, a
 * parseable date string becomes epoch ms, anything else is 0 (unknown), never a silent 1970.
 */
export function normalizeEventTimestamp(value: unknown): number {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim()) {
    const parsed = Date.parse(value);
    if (!Number.isNaN(parsed)) return parsed;
    const asNum = Number(value);
    if (Number.isFinite(asNum)) return asNum;
  }
  return 0;
}

export function replayEntry(event: RunLogEvent): LogEntry {
  return {
    id: runEventKey(event),
    type: event.type || 'LOG',
    data: event,
    timestamp: normalizeEventTimestamp(event.timestamp),
  };
}

export function mergeRunEntries(current: readonly LogEntry[], incoming: readonly LogEntry[]) {
  const merged = new Map<string, LogEntry>();
  for (const entry of [...current, ...incoming]) merged.set(entry.id, entry);
  return [...merged.values()].slice(-500);
}

export function replayNotice(replay: RunReplayLog | null): string | null {
  if (!replay) return null;
  if (!replay.available) {
    return replay.reason === 'EXPIRED_OR_EMPTY'
      ? 'No earlier activity is retained. This is expected for a new run or after the 24-hour replay window expires.'
      : 'Earlier activity could not be loaded. Live events will continue below.';
  }
  if (replay.truncated) return 'Earlier activity was truncated to the most recent 500 events.';
  return null;
}
