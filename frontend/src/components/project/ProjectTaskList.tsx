import { Link } from 'react-router-dom';
import type { TaskSummary } from '@/types/project';

export default function ProjectTaskList({ tasks }: { tasks: TaskSummary[] }) {
  if (!tasks || tasks.length === 0) {
    return <div className="text-sm text-gray-500">No runs yet.</div>;
  }
  return (
    <div className="border rounded-lg overflow-hidden bg-white">
      <table className="w-full text-sm">
        <thead className="bg-gray-50 text-xs uppercase text-gray-500">
          <tr>
            <th className="text-left px-3 py-2">Run</th>
            <th className="text-left px-3 py-2">Status</th>
            <th className="text-right px-3 py-2">Score</th>
            <th className="text-right px-3 py-2">Started</th>
          </tr>
        </thead>
        <tbody>
          {tasks.map((t) => (
            <tr key={t.taskId} className="border-t">
              <td className="px-3 py-2 truncate max-w-md">
                <Link to={`/?runId=${t.runId}`} className="text-blue-600 hover:underline">
                  {t.name || t.runId.slice(0, 8)}
                </Link>
              </td>
              <td className="px-3 py-2 text-xs text-gray-600">{t.status}</td>
              <td className="px-3 py-2 text-right font-mono">
                {t.score != null ? `${t.score}` : '—'}
              </td>
              <td className="px-3 py-2 text-right text-xs text-gray-500">
                {t.createdAt ? new Date(t.createdAt).toLocaleString() : '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
