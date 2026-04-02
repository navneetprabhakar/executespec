import { useEffect, useState } from 'react';
import { getAvailableModels, ProviderEntry } from '@/lib/api';
import { cn } from '@/lib/utils';

const LS_PROVIDER = 'es:preferredProvider';
const LS_MODEL = 'es:preferredModel';

interface Props {
  provider: string;
  model: string;
  onProviderChange: (provider: string) => void;
  onModelChange: (model: string) => void;
}

export default function ProviderModelSelector({ provider, model, onProviderChange, onModelChange }: Props) {
  const [providers, setProviders] = useState<ProviderEntry[]>([]);

  useEffect(() => {
    getAvailableModels()
      .then((res) => setProviders(res.providers))
      .catch(() => {});
  }, []);

  const selectedProvider = providers.find((p) => p.id === provider);
  const models = selectedProvider?.models ?? [];

  const handleProviderChange = (id: string) => {
    onProviderChange(id);
    localStorage.setItem(LS_PROVIDER, id);
    // Reset model to auto when switching provider
    onModelChange('');
    localStorage.removeItem(LS_MODEL);
  };

  const handleModelChange = (id: string) => {
    onModelChange(id);
    if (id) {
      localStorage.setItem(LS_MODEL, id);
    } else {
      localStorage.removeItem(LS_MODEL);
    }
  };

  return (
    <div className="space-y-3">
      {/* Provider pills */}
      <div>
        <label className="block text-sm text-secondary mb-1.5">AI Provider</label>
        <div className="flex gap-2">
          {providers.map((p) => (
            <button
              key={p.id}
              type="button"
              onClick={() => handleProviderChange(p.id)}
              className={cn(
                'flex-1 py-1.5 px-3 rounded-sm text-sm font-medium transition-all border',
                provider === p.id
                  ? 'border-accent-blue/50 bg-accent-blue/10 text-accent-blue'
                  : 'border-[var(--border-default)] bg-[var(--gray-100)] text-secondary hover:text-primary hover:border-[var(--border-default)]',
              )}
            >
              {p.label}
              {p.tag && (
                <span className="ml-1.5 text-[10px] font-normal text-muted opacity-70">{p.tag}</span>
              )}
            </button>
          ))}
        </div>
      </div>

      {/* Model dropdown — only show if provider is selected and has >1 model */}
      {provider && models.length > 0 && (
        <div>
          <label className="block text-sm text-secondary mb-1.5">Model override</label>
          <select
            value={model}
            onChange={(e) => handleModelChange(e.target.value)}
            className="w-full bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-secondary focus:outline-none focus:border-accent-blue/40"
          >
            <option value="">Auto (best for each agent)</option>
            {models.map((m) => (
              <option key={`${m.id}-${m.tier}`} value={m.id}>
                {m.label} ({m.tier === 'PLANNING' ? 'Power' : 'Capable'})
              </option>
            ))}
          </select>
        </div>
      )}
    </div>
  );
}

/** Read persisted provider/model from localStorage. */
export function getPersistedSelection(): { provider: string; model: string } {
  return {
    provider: localStorage.getItem(LS_PROVIDER) ?? 'ANTHROPIC',
    model: localStorage.getItem(LS_MODEL) ?? '',
  };
}
