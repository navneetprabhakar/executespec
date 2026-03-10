import { useEffect, useState } from 'react';
import { ChevronDown, ChevronRight, HelpCircle, ExternalLink } from 'lucide-react';
import IntegrationCard from '@/components/integrations/IntegrationCard';
import GlassPanel from '@/components/shared/GlassPanel';
import { getIntegrationStatus, getIntegrationRuns } from '@/lib/api';
import { relativeTime } from '@/lib/utils';
import type { RunSummary } from '@/lib/api';

const INTEGRATIONS = [
  { key: 'JIRA',        name: 'Jira',         description: 'Issue-triggered runs'    },
  { key: 'CONFLUENCE',  name: 'Confluence',    description: 'Page-triggered runs'     },
  { key: 'SERVICENOW',  name: 'ServiceNow',    description: 'Incident response runs'  },
  { key: 'PAGERDUTY',   name: 'PagerDuty',     description: 'Alert-triggered runs'    },
];

interface SetupGuide {
  title: string;
  url: string;
  steps: string[];
}

const SETUP_GUIDES: Record<string, SetupGuide> = {
  JIRA: {
    title: 'Jira API Token',
    url: 'https://id.atlassian.com/manage-profile/security/api-tokens',
    steps: [
      'Go to Atlassian Account → Security → API tokens',
      'Click "Create API token" and give it a label (e.g. "ExecuteSpec")',
      'Copy the generated token immediately (it won\'t be shown again)',
      'Paste the token into Settings → Integrations → Jira API Token',
      'Set your Jira base URL (e.g. https://yoursite.atlassian.net)',
    ],
  },
  CONFLUENCE: {
    title: 'Confluence API Token',
    url: 'https://id.atlassian.com/manage-profile/security/api-tokens',
    steps: [
      'Uses the same Atlassian API token as Jira (same account)',
      'Go to Atlassian Account → Security → API tokens',
      'Create or reuse your existing API token',
      'Paste the token into Settings → Integrations → Confluence API Token',
      'Set your Confluence base URL (same as Jira, e.g. https://yoursite.atlassian.net)',
    ],
  },
  GITHUB: {
    title: 'GitHub Personal Access Token',
    url: 'https://github.com/settings/tokens',
    steps: [
      'Go to GitHub → Settings → Developer settings → Personal access tokens',
      'Click "Generate new token (classic)" or "Fine-grained token"',
      'Required scopes: repo (full control of private repositories)',
      'Set an expiration (90 days recommended) and click "Generate token"',
      'Copy the token and paste it into Settings → GitHub → Personal Access Token',
    ],
  },
  SERVICENOW: {
    title: 'ServiceNow Credentials',
    url: 'https://docs.servicenow.com/csh?topicname=c_RESTAPI.html',
    steps: [
      'In your ServiceNow instance, create a dedicated integration user',
      'Assign the user the "rest_api_explorer" and "web_service_admin" roles',
      'Use Basic Auth with the integration user\'s username and password',
      'Alternatively, set up OAuth: System OAuth → Application Registry → Create an OAuth API endpoint',
      'Paste credentials into Settings → Integrations → ServiceNow',
    ],
  },
  PAGERDUTY: {
    title: 'PagerDuty API Key',
    url: 'https://support.pagerduty.com/docs/api-access-keys',
    steps: [
      'Go to PagerDuty → Integrations → API Access Keys',
      'Click "Create New API Key"',
      'Give it a description (e.g. "ExecuteSpec Integration")',
      'Choose "Read/Write" access level',
      'Copy the key and paste it into Settings → Integrations → PagerDuty API Key',
    ],
  },
};

export default function IntegrationsPage() {
  const [enabledSources, setEnabledSources] = useState<string[]>([]);
  const [runs, setRuns] = useState<RunSummary[]>([]);
  const [showGuide, setShowGuide] = useState(false);
  const [openGuides, setOpenGuides] = useState<Record<string, boolean>>({});

  useEffect(() => {
    getIntegrationStatus().then((r) => setEnabledSources(r.enabledSources)).catch(() => {});
    getIntegrationRuns().then(setRuns).catch(() => {});
  }, []);

  const toggleGuide = (key: string) => {
    setOpenGuides((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  return (
    <div className="h-full overflow-y-auto p-4 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-lg font-semibold text-white">Integrations</h1>
          <p className="text-xs text-zinc-500 mt-0.5">External tools that can trigger agent runs</p>
        </div>
        <button
          onClick={() => setShowGuide(!showGuide)}
          className="flex items-center gap-1.5 text-xs text-zinc-400 hover:text-accent-violet transition-colors px-3 py-1.5 rounded-lg border border-white/[0.08] hover:border-accent-violet/30"
        >
          <HelpCircle className="h-3.5 w-3.5" />
          Setup Guide
        </button>
      </div>

      {/* Integration grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        {INTEGRATIONS.map((int) => (
          <IntegrationCard
            key={int.key}
            integrationKey={int.key}
            name={int.name}
            description={int.description}
            enabled={enabledSources.includes(int.key)}
          />
        ))}
      </div>

      {/* Access Token Setup Guide */}
      {showGuide && (
        <GlassPanel className="p-4">
          <h3 className="text-sm font-semibold text-zinc-200 mb-3 flex items-center gap-2">
            <HelpCircle className="h-4 w-4 text-accent-violet" />
            How to Get Access Tokens
          </h3>
          <p className="text-xs text-zinc-500 mb-4">
            Each integration requires an API token or credentials. Follow the steps below to configure access for each service.
          </p>
          <div className="space-y-1">
            {Object.entries(SETUP_GUIDES).map(([key, guide]) => (
              <div key={key} className="border border-white/[0.06] rounded-lg overflow-hidden">
                <button
                  onClick={() => toggleGuide(key)}
                  className="w-full flex items-center gap-2 px-3 py-2.5 text-left hover:bg-white/[0.02] transition-colors"
                >
                  {openGuides[key]
                    ? <ChevronDown className="h-3.5 w-3.5 text-zinc-500 flex-shrink-0" />
                    : <ChevronRight className="h-3.5 w-3.5 text-zinc-500 flex-shrink-0" />
                  }
                  <span className="text-sm font-medium text-zinc-300">{guide.title}</span>
                  <a
                    href={guide.url}
                    target="_blank"
                    rel="noopener noreferrer"
                    onClick={(e) => e.stopPropagation()}
                    className="ml-auto text-xs text-accent-violet hover:text-accent-violet/80 flex items-center gap-1"
                  >
                    Open <ExternalLink className="h-3 w-3" />
                  </a>
                </button>
                {openGuides[key] && (
                  <div className="px-3 pb-3 pt-0">
                    <ol className="list-decimal list-inside space-y-1.5 text-xs text-zinc-400 ml-5">
                      {guide.steps.map((step, i) => (
                        <li key={i} className="leading-relaxed">{step}</li>
                      ))}
                    </ol>
                  </div>
                )}
              </div>
            ))}
          </div>
        </GlassPanel>
      )}

      {/* Integration runs */}
      {runs.length > 0 && (
        <GlassPanel className="overflow-hidden">
          <div className="px-4 py-3 border-b border-white/[0.06]">
            <h3 className="text-sm font-semibold text-zinc-200">Integration-triggered Runs</h3>
          </div>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-white/[0.04]">
                {['Run ID', 'Status', 'Goal', 'Created'].map((h) => (
                  <th key={h} className="text-left px-4 py-2.5 text-xs font-medium text-zinc-500">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {runs.slice(0, 20).map((r) => (
                <tr key={r.runId} className="border-b border-white/[0.04] hover:bg-white/[0.02]">
                  <td className="px-4 py-2.5 font-mono text-xs text-zinc-400">{r.runId.slice(0, 12)}</td>
                  <td className="px-4 py-2.5 text-xs text-zinc-300">{r.status}</td>
                  <td className="px-4 py-2.5 text-xs text-zinc-400 max-w-xs truncate">{r.userRequest.slice(0, 60)}</td>
                  <td className="px-4 py-2.5 text-xs text-zinc-600">{relativeTime(r.startedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </GlassPanel>
      )}
    </div>
  );
}
