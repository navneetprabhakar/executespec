import { Command } from 'cmdk';
import { useNavigate } from 'react-router-dom';
import { useUiStore } from '@/stores/useUiStore';
import { useRunStore } from '@/stores/useRunStore';
import { LayoutDashboard, BarChart3, DollarSign, Plug, Settings, Plus, Wand2 } from 'lucide-react';
import { useState } from 'react';
import NewRunModal from '@/components/runs/NewRunModal';

const NAV_COMMANDS = [
  { label: 'Go to Runs',        path: '/',             icon: LayoutDashboard },
  { label: 'Go to Dashboard',   path: '/dashboard',    icon: BarChart3 },
  { label: 'Go to Pricing',     path: '/pricing',      icon: DollarSign },
  { label: 'Go to Integrations',path: '/integrations', icon: Plug },
  { label: 'Go to Settings',    path: '/settings',     icon: Settings },
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
          style={{ backdropFilter: 'blur(4px)', background: 'rgba(0,0,0,0.5)' }}
          onClick={() => setCommandPaletteOpen(false)}
        >
          <div
            className="w-full max-w-xl mx-4 rounded-sm border border-white/[0.10] bg-surface-2  overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            <Command className="[&_[cmdk-input]]:h-12 [&_[cmdk-input]]:px-4 [&_[cmdk-input]]:text-sm [&_[cmdk-input]]:bg-transparent [&_[cmdk-input]]:outline-none [&_[cmdk-input]]:text-zinc-100 [&_[cmdk-input]]:placeholder:text-zinc-500">
              <Command.Input placeholder="Search commands, navigate, find runs..." />
              <Command.List className="max-h-80 overflow-y-auto border-t border-white/[0.06] py-2">
                <Command.Empty className="py-6 text-center text-sm text-zinc-500">
                  No results found.
                </Command.Empty>

                <Command.Group heading="Actions" className="[&_[cmdk-group-heading]]:px-4 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:text-zinc-500 [&_[cmdk-group-heading]]:font-medium">
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

                <Command.Group heading="Navigation" className="[&_[cmdk-group-heading]]:px-4 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:text-zinc-500 [&_[cmdk-group-heading]]:font-medium">
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
                  <Command.Group heading="Recent Runs" className="[&_[cmdk-group-heading]]:px-4 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:text-zinc-500 [&_[cmdk-group-heading]]:font-medium">
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
      className="flex items-center gap-3 px-4 py-2.5 text-sm text-zinc-300 cursor-pointer data-[selected=true]:bg-white/[0.06] data-[selected=true]:text-white"
    >
      <span className="text-zinc-500">{icon}</span>
      {label}
    </Command.Item>
  );
}
