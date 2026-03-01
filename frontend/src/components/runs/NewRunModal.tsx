import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { Rocket, ChevronDown, X } from 'lucide-react';
import { useRunStore } from '@/stores/useRunStore';
import { useUiStore } from '@/stores/useUiStore';
import { cn } from '@/lib/utils';

interface NewRunModalProps {
  onClose: () => void;
}

const MODELS = [
  { value: 'claude-opus-4-5-20251101', label: 'Claude Opus (Architect)' },
  { value: 'claude-sonnet-4-5-20250929', label: 'Claude Sonnet (Capable)' },
  { value: 'gpt-4o', label: 'GPT-4o' },
  { value: 'gemini-2.5-pro', label: 'Gemini 2.5 Pro' },
];

export default function NewRunModal({ onClose }: NewRunModalProps) {
  const [goal, setGoal] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [skipApproval, setSkipApproval] = useState(false);
  const [model, setModel] = useState('');
  const [loading, setLoading] = useState(false);
  const { createRun, setActiveRunId } = useRunStore();
  const { addToast } = useUiStore();
  const navigate = useNavigate();

  const handleSubmit = async () => {
    if (!goal.trim()) return;
    setLoading(true);
    try {
      const runId = await createRun({
        userRequest: goal,
        skipApproval,
        ...(model ? { modelName: model } : {}),
      });
      addToast({ type: 'success', message: `Run launched: ${runId.slice(0, 8)}` });
      navigate('/');
      setActiveRunId(runId);
      onClose();
    } catch (e: any) {
      addToast({ type: 'error', message: e.message });
      setLoading(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ backdropFilter: 'blur(4px)', background: 'rgba(0,0,0,0.6)' }}
      onClick={onClose}
    >
      <motion.div
        initial={{ opacity: 0, scale: 0.96 }}
        animate={{ opacity: 1, scale: 1 }}
        exit={{ opacity: 0, scale: 0.96 }}
        transition={{ duration: 0.15 }}
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-xl rounded-2xl bg-surface-2 border border-white/[0.08] shadow-2xl overflow-hidden"
      >
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06]">
          <h2 className="text-base font-semibold text-white flex items-center gap-2">
            <Rocket className="h-4 w-4 text-accent-violet" />
            Launch New Run
          </h2>
          <button onClick={onClose} className="text-zinc-500 hover:text-zinc-300">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="p-6 space-y-4">
          <textarea
            autoFocus
            value={goal}
            onChange={(e) => setGoal(e.target.value)}
            placeholder="Describe what you want to build..."
            rows={5}
            className={cn(
              'w-full font-mono text-sm bg-surface-1 border border-white/[0.08] rounded-lg',
              'px-4 py-3 text-zinc-200 placeholder:text-zinc-600',
              'focus:outline-none focus:border-accent-violet/40 resize-none'
            )}
          />

          {/* Advanced toggle */}
          <button
            onClick={() => setShowAdvanced(!showAdvanced)}
            className="flex items-center gap-1.5 text-xs text-zinc-500 hover:text-zinc-300"
          >
            <ChevronDown className={cn('h-3.5 w-3.5 transition-transform', showAdvanced && 'rotate-180')} />
            Advanced options
          </button>

          <AnimatePresence>
            {showAdvanced && (
              <motion.div
                initial={{ height: 0, opacity: 0 }}
                animate={{ height: 'auto', opacity: 1 }}
                exit={{ height: 0, opacity: 0 }}
                className="overflow-hidden space-y-3"
              >
                <label className="flex items-center justify-between">
                  <span className="text-sm text-zinc-400">Skip approval gate</span>
                  <button
                    role="switch"
                    aria-checked={skipApproval}
                    onClick={() => setSkipApproval(!skipApproval)}
                    className={cn(
                      'relative h-5 w-9 rounded-full transition-colors',
                      skipApproval ? 'bg-accent-violet' : 'bg-white/[0.10]'
                    )}
                  >
                    <span className={cn(
                      'absolute top-0.5 h-4 w-4 rounded-full bg-white shadow transition-transform',
                      skipApproval ? 'translate-x-4' : 'translate-x-0.5'
                    )} />
                  </button>
                </label>
                <div>
                  <label className="block text-sm text-zinc-400 mb-1.5">Model override</label>
                  <select
                    value={model}
                    onChange={(e) => setModel(e.target.value)}
                    className="w-full bg-surface-1 border border-white/[0.08] rounded-lg px-3 py-2 text-sm text-zinc-300 focus:outline-none focus:border-accent-violet/40"
                  >
                    <option value="">Default (Claude Opus)</option>
                    {MODELS.map((m) => (
                      <option key={m.value} value={m.value}>{m.label}</option>
                    ))}
                  </select>
                </div>
              </motion.div>
            )}
          </AnimatePresence>

          <button
            onClick={handleSubmit}
            disabled={!goal.trim() || loading}
            className={cn(
              'w-full py-2.5 rounded-lg font-medium text-sm text-white transition-all',
              'bg-accent-violet hover:bg-accent-violet/80',
              'disabled:opacity-40 disabled:cursor-not-allowed',
              'flex items-center justify-center gap-2'
            )}
          >
            {loading ? (
              <span className="h-4 w-4 rounded-full border-2 border-white/30 border-t-white animate-spin" />
            ) : (
              <Rocket className="h-4 w-4" />
            )}
            {loading ? 'Launching...' : 'Launch Run'}
          </button>
        </div>
      </motion.div>
    </div>
  );
}
