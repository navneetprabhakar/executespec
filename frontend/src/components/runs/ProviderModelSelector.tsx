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
        <label className="block text-sm text-zinc-400 mb-1.5">AI Provider</label>
        <div className="flex gap-2">
          {providers.map((p) => (
            <button
              key={p.id}
              type="button"
              onClick={() => handleProviderChange(p.id)}
              className={cn(
                'flex-1 py-1.5 px-3 rounded-sm text-sm font-medium transition-all border',
                provider === p.id
                  ? 'border-accent-violet/50 bg-accent-violet/10 text-accent-violet'
                  : 'border-white/[0.08] bg-surface-1 text-zinc-400 hover:text-zinc-200 hover:border-white/[0.15]',
              )}
            >
              {p.label}
            </button>
          ))}
        </div>
      </div>

      {/* Model dropdown — only show if provider is selected and has >1 model */}
      {provider && models.length > 0 && (
        <div>
          <label className="block text-sm text-zinc-400 mb-1.5">Model override</label>
          <select
            value={model}
            onChange={(e) => handleModelChange(e.target.value)}
            className="w-full bg-surface-1 border border-white/[0.08] rounded-sm px-3 py-2 text-sm text-zinc-300 focus:outline-none focus:border-accent-violet/40"
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
