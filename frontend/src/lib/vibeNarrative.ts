import type { LogEntry } from '@/stores/useSseStore';

export interface NarrativeItem {
  text: string;
  variant: 'progress' | 'done' | 'warning';
}

/**
 * Maps an SSE log entry to a human-readable narrative item for Vibe mode.
 * Returns null for events that should be hidden from casual users.
 */
export function mapEventToNarrative(entry: LogEntry): NarrativeItem | null {
  const { type, data } = entry;

  // Planning
  if (type === 'PLANNING_STARTED') {
    return { text: 'Analyzing requirements...', variant: 'progress' };
  }
  if (type === 'RUN_STATUS' && data.status === 'PLANNING') {
    return { text: 'Analyzing requirements...', variant: 'progress' };
  }

  // Working
  if (type === 'RUN_STATUS' && data.status === 'WORKING') {
    return { text: 'Building...', variant: 'progress' };
  }

  // Task events — hidden (counter updated externally)
  if (type === 'TASK_STARTED' || type === 'TASK_COMPLETED') {
    return null;
  }

  // Review
  if (type === 'RUN_STATUS' && data.status === 'REVIEWING') {
    return { text: 'Reviewing the code...', variant: 'progress' };
  }

  // Compiling
  if (type === 'COMPILATION_STARTED' || (type === 'RUN_STATUS' && data.status === 'COMPILING')) {
    return { text: 'Checking the build...', variant: 'progress' };
  }

  // Merging
  if (type === 'RUN_STATUS' && data.status === 'MERGING') {
    return { text: 'Assembling the pieces...', variant: 'progress' };
  }

  // Preview launching (matches "Code ready — starting preview..." log message)
  if (type === 'LOG' && typeof data.message === 'string' && data.message.includes('Code ready')) {
    return { text: 'Launching preview...', variant: 'progress' };
  }

  // Preview live
  if (type === 'PREVIEW_READY') {
    return { text: 'Preview is live!', variant: 'done' };
  }

  // Complete
  if (type === 'RUN_STATUS' && data.status === 'COMPLETE') {
    return { text: 'Ready.', variant: 'done' };
  }

  // Failed
  if (type === 'RUN_STATUS' && data.status === 'FAILED') {
    return { text: 'Execution halted.', variant: 'warning' };
  }

  // Test generation
  if (type === 'TEST_GEN_STARTED') {
    return { text: 'Running tests...', variant: 'progress' };
  }
  if (type === 'TEST_REPORT_READY') {
    return { text: 'Tests complete.', variant: 'done' };
  }
  if (type === 'TEST_PIPELINE_FAILED') {
    return { text: 'Some tests failed.', variant: 'warning' };
  }

  // Everything else hidden
  return null;
}
