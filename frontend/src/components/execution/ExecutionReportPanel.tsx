import { useEffect, useState } from 'react';
import { MarkdownContent } from '@/components/shared/MarkdownContent';
import { executionApi } from '@/lib/execution-api';

export default function ExecutionReportPanel({ executionId }: { executionId: string }) {
  const [markdown, setMarkdown] = useState<string | null>(null);

  useEffect(() => {
    executionApi.getReport(executionId)
      .then((r) => setMarkdown(r.markdown))
      .catch(() => setMarkdown('# Report unavailable'));
  }, [executionId]);

  if (!markdown) return <div className="text-xs text-muted">Loading report…</div>;
  return (
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 text-sm">
      <MarkdownContent content={markdown} />
    </div>
  );
}
