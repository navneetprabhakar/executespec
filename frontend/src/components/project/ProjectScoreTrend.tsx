import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid, Legend } from 'recharts';
import type { ScoreEntry } from '@/types/project';

export default function ProjectScoreTrend({ scores }: { scores: ScoreEntry[] }) {
  if (!scores || scores.length === 0) {
    return <div className="text-sm text-gray-500">No scoring data yet — complete a run with a validation report.</div>;
  }

  const data = scores.map((s) => ({
    date: new Date(s.createdAt).toLocaleDateString(),
    overall: s.overallScore ?? null,
    quality: s.codeQualityScore ?? null,
    security: s.securityScore ?? null,
    architecture: s.architectureScore ?? null,
  }));

  return (
    <div className="border rounded-lg bg-white p-4 h-72">
      <h3 className="text-sm font-medium mb-2">Score trend</h3>
      <ResponsiveContainer width="100%" height="90%">
        <LineChart data={data}>
          <CartesianGrid strokeDasharray="3 3" stroke="#eee" />
          <XAxis dataKey="date" tick={{ fontSize: 11 }} />
          <YAxis domain={[0, 100]} tick={{ fontSize: 11 }} />
          <Tooltip />
          <Legend wrapperStyle={{ fontSize: 11 }} />
          <Line type="monotone" dataKey="overall" stroke="#2563eb" strokeWidth={2} dot={false} />
          <Line type="monotone" dataKey="quality" stroke="#10b981" strokeWidth={1} dot={false} />
          <Line type="monotone" dataKey="security" stroke="#f59e0b" strokeWidth={1} dot={false} />
          <Line type="monotone" dataKey="architecture" stroke="#8b5cf6" strokeWidth={1} dot={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
