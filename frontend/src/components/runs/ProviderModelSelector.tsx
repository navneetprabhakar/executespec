import { useEffect, useState } from 'react';
import { Lock } from 'lucide-react';
import { getAvailableModels, ProviderEntry } from '@/lib/api';
import { useAuthStore } from '@/stores/useAuthStore';
import { cn } from '@/lib/utils';

const LS_PROVIDER = 'es:preferredProvider';
const LS_MODEL = 'es:preferredModel';

/** Maps provider ID to the user key flag on AppUser */
// v2.5.14 — backend enum was renamed GOOGLE → GEMINI (V119 migration). Keep
// GOOGLE aliased to the same key for backwards-compatibility with any
// already-persisted runs that haven't rolled over yet.
const PROVIDER_KEY_MAP: Record<string, 'hasAnthropicKey' | 'hasOpenAiKey' | 'hasGeminiKey'> = {
  ANTHROPIC: 'hasAnthropicKey',
  OPENAI: 'hasOpenAiKey',
  GEMINI: 'hasGeminiKey',
  GOOGLE: 'hasGeminiKey',
};

interface Props {
  provider: string;
  model: string;
  onProviderChange: (provider: string) => void;
  onModelChange: (model: string) => void;
}

export default function ProviderModelSelector({ provider, model, onProviderChange, onModelChange }: Props) {
  const [providers, setProviders] = useState<ProviderEntry[]>([]);
  const user = useAuthStore((s) => s.user);
  // V141 — TEAM is the renamed MAX tier (BYOK).
  const isByokTier = user?.tier === 'TEAM' || user?.tier === 'ENTERPRISE';

  useEffect(() => {
    getAvailableModels()
      .then((res) => setProviders(res.providers))
      .catch(() => {});
  }, []);

  const selectedProvider = providers.find((p) => p.id === provider);
  const models = selectedProvider?.models ?? [];

  const hasKeyForProvider = (providerId: string): boolean => {
    if (!isByokTier) return true; // Managed tiers use system keys — all available
    const field = PROVIDER_KEY_MAP[providerId];
    return field ? !!user?.[field] : true;
  };

  const handleProviderChange = (id: string) => {
    if (!hasKeyForProvider(id)) return; // Block click on keyless provider
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
      {/* Provider pills — stack on mobile to avoid horizontal overflow */}
      <div>
        <label className="block text-sm text-secondary mb-1.5">AI Provider</label>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
          {providers.map((p) => {
            const hasKey = hasKeyForProvider(p.id);
            return (
              <button
                key={p.id}
                type="button"
                onClick={() => handleProviderChange(p.id)}
                disabled={!hasKey}
                title={!hasKey ? `Add your ${p.label} API key in Settings to use this provider` : undefined}
                className={cn(
                  'w-full py-2 px-3 rounded-sm text-sm font-medium transition-all border',
                  'flex items-center justify-center gap-1.5',
                  !hasKey
                    ? 'border-[var(--border-default)] bg-[var(--gray-200)] text-muted opacity-50 cursor-not-allowed'
                    : provider === p.id
                      ? 'border-accent-blue/50 bg-accent-blue/10 text-accent-blue'
                      : 'border-[var(--border-default)] bg-[var(--gray-100)] text-secondary hover:text-primary hover:border-[var(--border-default)]',
                )}
              >
                <span>{p.label}</span>
                {!hasKey && <Lock className="h-3 w-3" />}
                {hasKey && p.tag && (
                  <span className="text-[10px] font-normal text-muted opacity-70">{p.tag}</span>
                )}
              </button>
            );
          })}
        </div>
        {isByokTier && (
          <p className="text-[11px] text-muted mt-1">
            Only providers with configured API keys are available.{' '}
            <a href="/settings#ai-keys" className="text-accent-blue underline">Manage keys</a>
          </p>
        )}
      </div>

    </div>
  );
}

/** Read persisted provider/model from localStorage. */
export function getPersistedSelection(): { provider: string; model: string } {
  return {
    provider: 'OPENAI',
    model: '',
  };
}
