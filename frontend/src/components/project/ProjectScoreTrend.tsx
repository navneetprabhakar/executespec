import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid, Legend } from 'recharts';
import type { ScoreEntry } from '@/types/project';

/** Dark-theme score-trend chart. Uses CSS variables so the chart follows the
 *  app's theme (gray-100 card, muted text, subtle grid). */
export default function ProjectScoreTrend({ scores }: { scores: ScoreEntry[] }) {
  if (!scores || scores.length === 0) {
    return (
      <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 text-xs text-muted">
        No scoring data yet — complete a run with a validation report.
      </div>
    );
  }

  const data = scores.map((s) => ({
    date: new Date(s.createdAt).toLocaleDateString(),
    overall: s.overallScore ?? null,
    quality: s.codeQualityScore ?? null,
    security: s.securityScore ?? null,
    architecture: s.architectureScore ?? null,
  }));

  return (
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 h-72">
      <h3 className="text-[11px] uppercase tracking-wide text-muted mb-2">Score trend</h3>
      <ResponsiveContainer width="100%" height="88%">
        <LineChart data={data} margin={{ top: 4, right: 8, left: -12, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border-default)" />
          <XAxis dataKey="date" tick={{ fontSize: 10, fill: 'var(--text-muted)' }}
                 stroke="var(--border-default)" />
          <YAxis domain={[0, 100]} tick={{ fontSize: 10, fill: 'var(--text-muted)' }}
                 stroke="var(--border-default)" />
          <Tooltip
            contentStyle={{
              background: 'var(--gray-200)',
              border: '1px solid var(--border-default)',
              borderRadius: 2,
              fontSize: 11,
            }}
            labelStyle={{ color: 'var(--text-secondary)' }}
            itemStyle={{ color: 'var(--text-primary)' }}
          />
          <Legend wrapperStyle={{ fontSize: 10, color: 'var(--text-muted)' }} />
          <Line type="monotone" dataKey="overall"      stroke="#60A5FA" strokeWidth={1.5} dot={false} />
          <Line type="monotone" dataKey="quality"      stroke="#34D399" strokeWidth={1}   dot={false} />
          <Line type="monotone" dataKey="security"     stroke="#F59E0B" strokeWidth={1}   dot={false} />
          <Line type="monotone" dataKey="architecture" stroke="#A78BFA" strokeWidth={1}   dot={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
