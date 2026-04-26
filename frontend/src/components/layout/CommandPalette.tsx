import { Command } from 'cmdk';
import { useNavigate } from 'react-router-dom';
import { useUiStore } from '@/stores/useUiStore';
import { useRunStore } from '@/stores/useRunStore';
import { LayoutDashboard, BarChart3, DollarSign, Plug, Settings, Plus, Wand2 } from 'lucide-react';
import { useState } from 'react';
import NewRunModal from '@/components/runs/NewRunModal';

const NAV_COMMANDS = [
  // Phase 1.1 #16 — copy-only "Missions" rename (URL paths unchanged).
  { label: 'Go to Missions',     path: '/',              icon: LayoutDashboard },
  { label: 'Go to Projects',     path: '/projects',      icon: BarChart3 },
  { label: 'Go to Plans',        path: '/plans',         icon: DollarSign },
  { label: 'Go to Pricing',      path: '/pricing',       icon: DollarSign },
  { label: 'Go to Integrations', path: '/integrations',  icon: Plug },
  { label: 'Go to Invoices',     path: '/invoices',      icon: DollarSign },
  { label: 'Go to Settings',     path: '/settings',      icon: Settings },
  { label: 'Go to Org settings', path: '/org/settings',  icon: Settings },
];

export default function CommandPalette() {
  const { commandPaletteOpen, setCommandPaletteOpen } = useUiStore();
  const runs = useRunStore((s) => s.runs);
  const setActiveRunId = useRunStore((s) => s.setActiveRunId);
  const navigate = useNavigate();
  const [showNewRun, setShowNewRun] = useState(false);

  return (
    <>
      {commandPaletteOpen && (
        <div
          className="fixed inset-0 z-50 flex items-start justify-center pt-[15vh]"
          style={{ background: 'rgba(0,0,0,0.7)' }}
          onClick={() => setCommandPaletteOpen(false)}
        >
          <div
            className="w-full max-w-xl mx-2 sm:mx-4 rounded-md border border-[var(--border-default)] bg-[var(--gray-100)] overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            <Command className="[&_[cmdk-input]]:h-12 [&_[cmdk-input]]:px-4 [&_[cmdk-input]]:text-sm [&_[cmdk-input]]:bg-transparent [&_[cmdk-input]]:outline-none [&_[cmdk-input]]:text-[var(--text-primary)] [&_[cmdk-input]]:placeholder:text-[var(--text-muted)]">
              <Command.Input placeholder="Search commands, navigate, find runs..." />
              <Command.List className="max-h-[50vh] sm:max-h-80 overflow-y-auto border-t border-[var(--border-default)] py-2">
                <Command.Empty className="py-6 text-center text-sm text-muted">
                  No results found.
                </Command.Empty>

                <Command.Group heading="Actions" className="[&_[cmdk-group-heading]]:px-4 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:text-[var(--text-muted)] [&_[cmdk-group-heading]]:font-medium">
                  <CmdItem
                    icon={<Plus className="h-4 w-4" />}
                    label="New Run"
                    onSelect={() => { setCommandPaletteOpen(false); setShowNewRun(true); }}
                  />
                  <CmdItem
                    icon={<Wand2 className="h-4 w-4" />}
                    label="Estimate Feasibility"
                    onSelect={() => { setCommandPaletteOpen(false); navigate('/pricing'); }}
                  />
                </Command.Group>

                <Command.Group heading="Navigation" className="[&_[cmdk-group-heading]]:px-4 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:text-[var(--text-muted)] [&_[cmdk-group-heading]]:font-medium">
                  {NAV_COMMANDS.map((cmd) => (
                    <CmdItem
                      key={cmd.path}
                      icon={<cmd.icon className="h-4 w-4" />}
                      label={cmd.label}
                      onSelect={() => { setCommandPaletteOpen(false); navigate(cmd.path); }}
                    />
                  ))}
                </Command.Group>

                {runs.length > 0 && (
                  <Command.Group heading="Recent Runs" className="[&_[cmdk-group-heading]]:px-4 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:text-[var(--text-muted)] [&_[cmdk-group-heading]]:font-medium">
                    {runs.slice(0, 5).map((r) => (
                      <CmdItem
                        key={r.runId}
                        icon={<LayoutDashboard className="h-4 w-4" />}
                        label={`Run ${r.runId.slice(0, 8)} — ${(r.userRequest ?? '').slice(0, 40)}`}
                        onSelect={() => {
                          setCommandPaletteOpen(false);
                          navigate('/');
                          setActiveRunId(r.runId);
                        }}
                      />
                    ))}
                  </Command.Group>
                )}
              </Command.List>
            </Command>
          </div>
        </div>
      )}
      {showNewRun && <NewRunModal onClose={() => setShowNewRun(false)} />}
    </>
  );
}

function CmdItem({ icon, label, onSelect }: { icon: React.ReactNode; label: string; onSelect: () => void }) {
  return (
    <Command.Item
      onSelect={onSelect}
      className="flex items-center gap-3 px-4 py-2.5 min-h-[44px] text-sm text-secondary cursor-pointer data-[selected=true]:bg-[var(--gray-200)] data-[selected=true]:text-primary"
    >
      <span className="text-muted">{icon}</span>
      {label}
    </Command.Item>
  );
}
