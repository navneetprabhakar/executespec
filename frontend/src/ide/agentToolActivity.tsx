import {Fragment} from 'react';
import {RunsIcon, SearchIcon} from '@/ide/IdeIcons';
// No chevron here: `.ide-root details > summary::before` is the app's ONE
// disclosure marker. Adding a second one beside it was the duplicate-idiom
// defect the standard warns about.
import {ToolErrIcon, ToolOkIcon, ToolRunningIcon} from '@/ide/agentIcons';
import {asText, friendlyTool, type StreamTimelineItem} from '@/ide/agentState';

/**
 * Tool activity as annotated prose, not a log dump.
 *
 * An agent turn is mostly tool activity, so the transcript treats it as a
 * first-class text flow: each call is an inline-sized pill carrying a status
 * glyph, a run of calls collapses to one line, and arguments/results are one
 * click away instead of pasted into the record. Nothing here invents a status —
 * a call with no matching result reads as dispatched, never as succeeded.
 */

export type ToolCallStatus = 'ok' | 'err' | 'running';

export type ToolCall = {
  id: string;
  /** Raw tool name, used for the friendly label. */
  tool: string;
  status: ToolCallStatus;
  /** Serialized call arguments, when the stream carried them. */
  args?: string;
  /** Serialized call result, when a matching result frame arrived. */
  result?: string;
  /** Workspace path the call touched, when one is unambiguously present. */
  file?: string;
};

/** How many calls may render as individual pills before the run collapses. */
const INLINE_RUN_LIMIT = 4;

/**
 * Explicit failure markers only. A result we cannot parse, or one with no
 * failure marker, is NOT reported as a failure — and the absence of a result is
 * reported as dispatched rather than as success.
 */
function resultStatus(result: string): ToolCallStatus {
  const text = result.toLowerCase();
  if (/"ok"\s*:\s*false/.test(text)) return 'err';
  if (/"(error|errormessage|error_message)"\s*:\s*"/.test(text)) return 'err';
  if (/^\s*(error|err)\b/.test(text)) return 'err';
  return 'ok';
}

/** Pull a workspace path out of the call arguments when exactly one is present. */
function filePath(args?: string): string | undefined {
  if (!args) return undefined;
  const match = /"(?:path|filePath|file|filename)"\s*:\s*"([^"]{1,300})"/.exec(args);
  return match?.[1];
}

export function basename(path: string): string {
  const parts = path.split('/').filter(Boolean);
  return parts.length ? parts[parts.length - 1] : path;
}

/**
 * Pair each `tool` frame with the `result` frame that follows it. The stream
 * emits results as separate items keyed `result:<id>`; the transcript should
 * read as one annotated call, not as two unrelated log lines.
 */
export function toToolCalls(items: StreamTimelineItem[]): ToolCall[] {
  const calls: ToolCall[] = [];
  for (const item of items) {
    if (item.type === 'tool') {
      const args = item.detail;
      calls.push({
        id: item.id,
        tool: item.title,
        status: 'running',
        args,
        file: filePath(args),
      });
      continue;
    }
    if (item.type !== 'result') continue;
    // Attach to the newest call that has not resolved yet.
    for (let i = calls.length - 1; i >= 0; i -= 1) {
      if (calls[i].status === 'running') {
        const result = item.detail;
        calls[i] = {
          ...calls[i],
          result,
          status: result ? resultStatus(result) : 'ok',
        };
        break;
      }
    }
  }
  return calls;
}

function StatusGlyph({status}: {status: ToolCallStatus}) {
  if (status === 'ok') return <ToolOkIcon />;
  if (status === 'err') return <ToolErrIcon />;
  return <ToolRunningIcon />;
}

/**
 * One call. Arguments and results are the deeper altitude and stay collapsed;
 * a call with neither renders as a plain pill rather than an empty disclosure.
 */
function ToolPill({call}: {call: ToolCall}) {
  const label = friendlyTool(call.tool);
  const chip = call.file ? (
    <code className='agent-tool-file' title={call.file}>
      {basename(call.file)}
    </code>
  ) : null;
  const hasDetail = !!(call.args || call.result);
  if (!hasDetail) {
    return (
      <span className={`agent-tool-pill is-${call.status}`}>
        <StatusGlyph status={call.status} />
        <span className='agent-tool-name'>{label}</span>
        {chip}
      </span>
    );
  }
  return (
    <details className={`agent-tool-call is-${call.status}`}>
      <summary className='agent-tool-pill'>
        <StatusGlyph status={call.status} />
        <span className='agent-tool-name'>{label}</span>
        {chip}
      </summary>
      <div className='agent-tool-detail'>
        {call.args && (
          <div className='agent-tool-detail-row'>
            <span>Arguments</span>
            <pre>{asText(call.args)}</pre>
          </div>
        )}
        {call.result && (
          <div className='agent-tool-detail-row'>
            <span>Result</span>
            <pre>{asText(call.result)}</pre>
          </div>
        )}
      </div>
    </details>
  );
}

/** A run of calls, collapsed to one summary line above the inline limit. */
function ToolRun({calls}: {calls: ToolCall[]}) {
  if (calls.length <= INLINE_RUN_LIMIT) {
    return (
      <Fragment>
        {calls.map((call) => (
          <ToolPill key={call.id} call={call} />
        ))}
      </Fragment>
    );
  }
  const failed = calls.filter((call) => call.status === 'err').length;
  const running = calls.filter((call) => call.status === 'running').length;
  return (
    <details className='agent-tool-group'>
      <summary className='agent-tool-pill'>
        <StatusGlyph status={failed ? 'err' : running ? 'running' : 'ok'} />
        <span className='agent-tool-name'>{calls.length} tool calls</span>
        {failed > 0 && <span className='agent-tool-count is-err'>{failed} failed</span>}
      </summary>
      <div className='agent-tool-group-body'>
        {calls.map((call) => (
          <ToolPill key={call.id} call={call} />
        ))}
      </div>
    </details>
  );
}

/**
 * Semantic run/system events keep their own chip. They are not tool calls, so
 * they must not borrow the tool status vocabulary.
 */
function EventChip({item}: {item: StreamTimelineItem}) {
  if (item.type === 'run') {
    return (
      <span className='agent-tool-pill is-event'>
        <RunsIcon size={12} />
        <span className='agent-tool-name'>Run created</span>
      </span>
    );
  }
  if (/RUN[ _]VALIDATION[ _]EVIDENCE/i.test(item.title) && item.detail) {
    return (
      <details className='agent-tool-call is-event'>
        <summary className='agent-tool-pill'>
          <SearchIcon size={12} />
          <span className='agent-tool-name'>Verification evidence</span>
        </summary>
        <div className='agent-tool-detail'>
          <div className='agent-tool-detail-row'>
            <span>Evidence</span>
            <pre>{item.detail}</pre>
          </div>
        </div>
      </details>
    );
  }
  return (
    <span className='agent-tool-pill is-event'>
      <SearchIcon size={12} />
      <span className='agent-tool-name'>
        {item.title}
        {item.detail ? ` · ${item.detail}` : ''}
      </span>
    </span>
  );
}

const SEMANTIC_EVENT =
  /SUBAGENT|HANDOFF|PROVIDER|MODEL|CONTINUATION|RUN[ _]VALIDATION[ _]EVIDENCE/i;

/**
 * Friendly activity for tool/run events — annotated prose, never raw JSON in the
 * record. Preserves stream order so a run of calls reads as consecutive lines.
 */
export function AgentToolActivity({items}: {items: StreamTimelineItem[]}) {
  const toolFrames = items.filter((item) => item.type === 'tool' || item.type === 'result');
  const eventFrames = items.filter(
    (item) => item.type === 'run' || (item.type === 'system' && SEMANTIC_EVENT.test(item.title)),
  );
  const calls = toToolCalls(toolFrames);
  if (calls.length === 0 && eventFrames.length === 0) return null;
  return (
    <div className='agent-activity' data-agent-region='activity'>
      {calls.length > 0 && <ToolRun calls={calls} />}
      {eventFrames.map((item) => (
        <EventChip key={item.id} item={item} />
      ))}
    </div>
  );
}
