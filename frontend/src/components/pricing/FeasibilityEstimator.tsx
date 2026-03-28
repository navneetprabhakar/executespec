import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Wand2, ChevronRight } from 'lucide-react';
import { feasibility } from '@/lib/api';
import { fmtCost, cn } from '@/lib/utils';
import type { FeasibilityReport } from '@/lib/api';

const VERDICT_COLORS: Record<string, string> = {
  FEASIBLE:   'text-accent-emerald',
  MARGINAL:   'text-accent-amber',
  INFEASIBLE: 'text-accent-rose',
  ESTIMATED:  'text-accent-cyan',
};

const COMPLEXITY_COLORS: Record<string, string> = {
  LOW:    'text-accent-emerald bg-accent-emerald/10',
  MEDIUM: 'text-accent-amber bg-accent-amber/10',
  HIGH:   'text-accent-rose bg-accent-rose/10',
};

export default function FeasibilityEstimator() {
  const [goal, setGoal] = useState('');
  const [model, setModel] = useState('');
  const [budget, setBudget] = useState('');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<FeasibilityReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleEstimate = async () => {
    if (!goal.trim()) return;
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const r = await feasibility({
        goal,
        ...(model ? { model } : {}),
        ...(budget ? { budgetUsd: parseFloat(budget) } : {}),
      });
      setResult(r);
    } catch (e: any) {
      setError(e.message);
    }
    setLoading(false);
  };

  return (
    <div className="space-y-4">
      <h2 className="text-base font-semibold text-zinc-200">Feasibility Estimator</h2>
      <div className="space-y-3">
        <textarea
          value={goal}
          onChange={(e) => setGoal(e.target.value)}
          placeholder="Describe your goal..."
          rows={3}
          className="w-full font-mono text-sm bg-surface-1 border border-white/[0.08] rounded-sm px-4 py-3 text-zinc-200 placeholder:text-zinc-600 focus:outline-none focus:border-accent-violet/40 resize-none"
        />
        <div className="flex gap-3">
          <select
            value={model}
            onChange={(e) => setModel(e.target.value)}
            className="flex-1 bg-surface-1 border border-white/[0.08] rounded-sm px-3 py-2 text-sm text-zinc-300 focus:outline-none"
          >
            <option value="">Default model</option>
            <option value="claude-opus-4-6">Claude Opus 4.6</option>
            <option value="claude-sonnet-4-6">Claude Sonnet 4.6</option>
            <option value="claude-haiku-4-5-20251001">Claude Haiku 4.5</option>
            <option value="gpt-5.4">GPT-5.4</option>
            <option value="gemini-2.5-pro">Gemini 2.5 Pro</option>
          </select>
          <input
            type="number"
            value={budget}
            onChange={(e) => setBudget(e.target.value)}
            placeholder="Budget ($)"
            className="w-32 bg-surface-1 border border-white/[0.08] rounded-sm px-3 py-2 text-sm text-zinc-300 focus:outline-none"
          />
          <button
            onClick={handleEstimate}
            disabled={!goal.trim() || loading}
            className={cn(
              'flex items-center gap-2 px-4 py-2 rounded-sm text-sm font-medium text-white transition-all',
              'bg-accent-violet hover:bg-accent-violet/80 disabled:opacity-40 disabled:cursor-not-allowed'
            )}
          >
            {loading ? <span className="h-4 w-4 rounded-full border-2 border-white/30 border-t-white animate-spin" /> : <Wand2 className="h-4 w-4" />}
            Estimate
          </button>
        </div>
      </div>

      <AnimatePresence>
        {error && (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
            className="p-3 rounded-sm bg-accent-rose/10 border border-accent-rose/30 text-sm text-accent-rose">
            {error}
          </motion.div>
        )}
        {result && (
          <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}
            className="space-y-4 border-t border-white/[0.06] pt-4">
            {/* Summary */}
            <div className="flex items-center justify-between flex-wrap gap-3">
              <div className="flex items-center gap-3">
                <span className={cn('text-sm font-medium', VERDICT_COLORS[result.verdict] ?? 'text-zinc-300')}>
                  {result.verdict === 'FEASIBLE' ? '✓ Feasible' :
                   result.verdict === 'MARGINAL' ? '⚠ Marginal' :
                   result.verdict === 'INFEASIBLE' ? '✗ Not feasible' : '~ Estimated'}
                </span>
              </div>
              <div className="flex items-center gap-4 text-sm text-zinc-300">
                <span>{fmtCost(result.estimatedLowUsd)} – {fmtCost(result.estimatedHighUsd)}</span>
                {result.budgetUsd && (
                  <span className="text-zinc-500">Budget: {fmtCost(result.budgetUsd)}</span>
                )}
              </div>
            </div>

            {/* Task breakdown */}
            {result.tasks?.length > 0 && (
              <div className="space-y-2">
                <h4 className="text-xs font-medium text-zinc-500 uppercase tracking-wider">Task Breakdown</h4>
                {result.tasks.map((t, i) => (
                  <div key={i} className="flex items-center justify-between px-3 py-2 rounded-sm bg-white/[0.02] border border-white/[0.06]">
                    <div className="flex items-center gap-2 min-w-0">
                      <ChevronRight className="h-3 w-3 text-zinc-600 flex-shrink-0" />
                      <span className="text-sm text-zinc-300 truncate">{t.title}</span>
                      <span className={cn('text-xs px-1.5 py-0.5 rounded flex-shrink-0', COMPLEXITY_COLORS[t.complexity] ?? 'text-zinc-500 bg-zinc-500/10')}>
                        {t.complexity}
                      </span>
                    </div>
                    <span className="text-xs text-zinc-500 flex-shrink-0 ml-2">
                      {fmtCost(t.estimatedLowUsd)}–{fmtCost(t.estimatedHighUsd)}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
