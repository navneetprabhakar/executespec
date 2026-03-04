import { useEffect, useState } from 'react';
import * as api from '@/lib/api';

interface Props {
  value: string;
  onChange: (id: string) => void;
  required?: boolean;
}

export default function ProjectSelector({ value, onChange, required }: Props) {
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.getProjects()
      .then(setProjects)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="h-9 animate-pulse rounded-lg bg-slate-700" />;

  return (
    <div>
      <label className="mb-1 block text-sm text-slate-400">Project {required && <span className="text-red-400">*</span>}</label>
      <select
        value={value}
        onChange={e => onChange(e.target.value)}
        required={required}
        className="w-full rounded-lg border border-slate-600 bg-slate-800 px-3 py-2 text-white focus:border-purple-500 focus:outline-none"
      >
        <option value="">Select a project…</option>
        {projects
          .filter(p => p.status === 'ACTIVE')
          .map(p => (
            <option key={p.id} value={p.id}>{p.name}</option>
          ))}
      </select>
    </div>
  );
}
