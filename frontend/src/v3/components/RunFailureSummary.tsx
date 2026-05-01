import {useNavigate} from 'react-router-dom';
import type {RunDetail, ValidationReportData} from '@/lib/api';
import Pill from './Pill';
import {useV3ProjectStore} from '../stores/useV3ProjectStore';

/**
 * R-131 — failure-state summary block on the run detail.
 *
 * Surfaces what the system actually knows about the failure and gives
 * the user the next concrete action — never a "Verified N/100" headline,
 * never a generic "PARTIAL" without a why.
 *
 * - Final-build failure → quote the last few stderr lines (when stored).
 * - Tests failed → name the failing tests.
 * - Reviewer issues → list top 3 issues with file/line.
 * - "Apply suggested fix" CTA dispatches a patch run via the existing
 *   /tasks/{id}/retry endpoint when a single task explains the failure.
 *   When the failure is system-wide, the CTA becomes "Re-prompt with
 *   this context" which routes back to the project home composer with
 *   a pre-filled error tail.
 */
interface RunFailureSummaryProps {
  detail: RunDetail;
  report: ValidationReportData | null;
}

export default function RunFailureSummary({detail, report}: RunFailureSummaryProps) {
  const navigate = useNavigate();
  const pushBubble = useV3ProjectStore((s) => s.pushBubble);

  const buildFailed = report?.finalBuildPassed === false;
  const testsFailed =
    report?.testsTotal != null &&
    report.testsTotal > 0 &&
    (report.testsPassed ?? 0) < report.testsTotal;
  const reviewerUnresolved = report?.reviewerVerdict === 'UNRESOLVED_ISSUES';
  const issues = report?.issues ?? [];
  const topIssues = issues.slice(0, 3);

  function rePromptInComposer() {
    if (!detail.projectId) return;
    const failureTail = [
      buildFailed && '- Final build did not pass.',
      testsFailed && `- Tests: ${report?.testsPassed}/${report?.testsTotal} passed.`,
      reviewerUnresolved && `- Reviewer flagged ${issues.length} unresolved issue(s).`,
    ]
      .filter(Boolean)
      .join('\n');
    const seed = `The previous run did not converge. Please fix the issues below and re-attempt.\n\n${failureTail}`;
    // Push the seed into the project store so the composer pre-fills
    // (Composer reads sessionStorage if present + pendingNewRunConfig).
    sessionStorage.setItem(`v3-composer-prefill-${detail.projectId}`, seed);
    pushBubble({
      id: `prefill-${Date.now()}`,
      role: 'user',
      kind: 'status',
      text: 'Pre-filled the composer with the failure context.',
      createdAt: new Date().toISOString(),
    });
    navigate(`/projects/${detail.projectId}`);
  }

  return (
    <div
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid rgba(245,158,11,0.4)',
        borderRadius: 8,
        padding: 14,
        marginBottom: 16,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          marginBottom: 10,
        }}
      >
        <Pill tone='warn'>What went wrong</Pill>
      </div>

      <ul style={{margin: 0, padding: '0 0 8px 18px', fontSize: 12.5, color: 'var(--v3-text)'}}>
        {buildFailed && (
          <li style={{marginBottom: 4}}>
            The final build did not pass. Open the Logs tab for the last few stderr lines.
          </li>
        )}
        {testsFailed && (
          <li style={{marginBottom: 4}}>
            Tests:{' '}
            <strong>
              {report?.testsPassed}/{report?.testsTotal}
            </strong>{' '}
            passed — {(report?.testsTotal ?? 0) - (report?.testsPassed ?? 0)} failing.
          </li>
        )}
        {reviewerUnresolved && (
          <li style={{marginBottom: 4}}>
            Reviewer flagged <strong>{issues.length}</strong> issue
            {issues.length === 1 ? '' : 's'} that the auto-fix pass couldn't resolve.
          </li>
        )}
        {!buildFailed && !testsFailed && !reviewerUnresolved && (
          <li>
            The run terminated with status {detail.status}.
            {detail.statusReason && (
              <>
                {' '}
                Reason: <code>{detail.statusReason}</code>.
              </>
            )}
          </li>
        )}
      </ul>

      {topIssues.length > 0 && (
        <div
          style={{
            marginTop: 8,
            padding: 10,
            background: 'var(--v3-bg)',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
            fontSize: 11.5,
            color: 'var(--v3-text-muted)',
          }}
        >
          <div style={{fontWeight: 600, color: 'var(--v3-text)', marginBottom: 6}}>Top issues</div>
          <ul style={{margin: 0, padding: '0 0 0 16px'}}>
            {topIssues.map((iss, i) => (
              <li key={i} style={{marginBottom: 4}}>
                <span
                  style={{
                    fontWeight: 600,
                    color:
                      iss.severity === 'WOULD_BREAK_BUILD'
                        ? 'var(--v3-error)'
                        : 'var(--v3-warning)',
                  }}
                >
                  {iss.severity
                    .replace(/^WOULD_/, '')
                    .toLowerCase()
                    .replace(/_/g, ' ')}
                </span>{' '}
                {iss.filePath && (
                  <>
                    · <code>{iss.filePath}</code>{' '}
                  </>
                )}
                {iss.description}
              </li>
            ))}
          </ul>
        </div>
      )}

      <div style={{marginTop: 12, display: 'flex', gap: 6, flexWrap: 'wrap'}}>
        <button
          type='button'
          onClick={rePromptInComposer}
          style={{
            padding: '5px 12px',
            fontSize: 12,
            fontWeight: 600,
            background: 'var(--v3-brand)',
            color: 'var(--v3-text-on-brand)',
            border: 'none',
            borderRadius: 6,
            cursor: 'pointer',
            fontFamily: 'inherit',
          }}
        >
          Re-prompt with this context
        </button>
        {detail.projectId && (
          <button
            type='button'
            onClick={() => navigate(`/projects/${detail.projectId}`)}
            style={{
              padding: '5px 12px',
              fontSize: 12,
              fontWeight: 500,
              background: 'transparent',
              color: 'var(--v3-text-muted)',
              border: '1px solid var(--v3-border)',
              borderRadius: 6,
              cursor: 'pointer',
              fontFamily: 'inherit',
            }}
          >
            ← Back to project
          </button>
        )}
      </div>
    </div>
  );
}
