import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Wand2, ChevronRight } from 'lucide-react';
import { feasibility } from '@/lib/api';
import { fmtCost, cn } from '@/lib/utils';
import type { FeasibilityReport } from '@/lib/api';

const VERDICT_COLORS: Record<string, string> = {
  FEASIBLE:   'text-accent-green',
  MARGINAL:   'text-accent-amber',
  INFEASIBLE: 'text-accent-red',
  ESTIMATED:  'text-secondary',
};

const COMPLEXITY_COLORS: Record<string, string> = {
  LOW:    'text-accent-green bg-accent-green/10',
  MEDIUM: 'text-accent-amber bg-accent-amber/10',
  HIGH:   'text-accent-red bg-accent-red/10',
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
      <h2 className="text-base font-semibold text-primary">Feasibility Estimator</h2>
      <div className="space-y-3">
        <textarea
          value={goal}
          onChange={(e) => setGoal(e.target.value)}
          placeholder="Describe your goal..."
          rows={3}
          className="w-full font-mono text-sm bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-4 py-3 text-primary placeholder:text-muted focus:outline-none focus:border-accent-blue/40 resize-none"
        />
        <div className="flex gap-3">
          <select
            value={model}
            onChange={(e) => setModel(e.target.value)}
            className="flex-1 bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-secondary focus:outline-none"
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
            className="w-32 bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-secondary focus:outline-none"
          />
          <button
            onClick={handleEstimate}
            disabled={!goal.trim() || loading}
            className={cn(
              'flex items-center gap-2 px-4 py-2 rounded-sm text-sm font-medium text-primary transition-all',
              'bg-accent-blue hover:bg-accent-blue/80 disabled:opacity-40 disabled:cursor-not-allowed'
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
            className="p-3 rounded-sm bg-accent-red/10 border border-accent-red/30 text-sm text-accent-red">
            {error}
          </motion.div>
        )}
        {result && (
          <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}
            className="space-y-4 border-t border-[var(--border-default)] pt-4">
            {/* Summary */}
            <div className="flex items-center justify-between flex-wrap gap-3">
              <div className="flex items-center gap-3">
                <span className={cn('text-sm font-medium', VERDICT_COLORS[result.verdict] ?? 'text-secondary')}>
                  {result.verdict === 'FEASIBLE' ? '✓ Feasible' :
                   result.verdict === 'MARGINAL' ? '⚠ Marginal' :
                   result.verdict === 'INFEASIBLE' ? '✗ Not feasible' : '~ Estimated'}
                </span>
              </div>
              <div className="flex items-center gap-4 text-sm text-secondary">
                <span>{fmtCost(result.estimatedLowUsd)} – {fmtCost(result.estimatedHighUsd)}</span>
                {result.budgetUsd && (
                  <span className="text-muted">Budget: {fmtCost(result.budgetUsd)}</span>
                )}
              </div>
            </div>

            {/* Task breakdown */}
            {result.tasks?.length > 0 && (
              <div className="space-y-2">
                <h4 className="text-xs font-medium text-muted uppercase tracking-wider">Task Breakdown</h4>
                {result.tasks.map((t, i) => (
                  <div key={i} className="flex items-center justify-between px-3 py-2 rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)]">
                    <div className="flex items-center gap-2 min-w-0">
                      <ChevronRight className="h-3 w-3 text-muted flex-shrink-0" />
                      <span className="text-sm text-secondary truncate">{t.title}</span>
                      <span className={cn('text-xs px-1.5 py-0.5 rounded flex-shrink-0', COMPLEXITY_COLORS[t.complexity] ?? 'text-muted bg-[var(--gray-200)]')}>
                        {t.complexity}
                      </span>
                    </div>
                    <span className="text-xs text-muted flex-shrink-0 ml-2">
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
