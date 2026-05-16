/**
 * v4 — embed wrapper for the secondary surfaces (account / billing /
 * integrations / team / trust).
 *
 * Each of these v3 page components is already self-contained — it owns
 * its own data fetch and renders its own form / table / panel. We
 * render the corresponding component inside the v4 chrome (top-bar +
 * bottom-nav + agent-input) so the user gets full v3 feature parity
 * without leaving /v4/*.
 *
 * Future iteration: rebuild each surface natively with v4 design tokens.
 * For the A/B test, embedding is the right call — it gets feature parity
 * shipped without duplicating 5000+ lines of forms.
 */
import {Link, useParams} from 'react-router-dom';
import {ChevronLeft} from 'lucide-react';
import V3BillingPage from '@/v3/pages/BillingPage';
import V3TeamPage from '@/v3/pages/TeamPage';
import V3TrustPage from '@/v3/pages/TrustPage';
import V3InsightsPage from '@/v3/pages/InsightsPage';
import V2SettingsPage from '@/pages/SettingsPage';
import V2IntegrationsPage from '@/pages/IntegrationsPage';
import V2PricingPage from '@/pages/PricingPage';
import LargeTitle from '../components/LargeTitle';

type Surface = 'account' | 'billing' | 'integrations' | 'team' | 'trust' | 'insights' | 'pricing';

const SURFACE_META: Record<Surface, {title: string; subtitle: string; Comp: () => JSX.Element}> = {
  account: {
    title: 'Account',
    subtitle: 'Profile · password · MFA · email preferences',
    Comp: V2SettingsPage,
  },
  billing: {
    title: 'Billing',
    subtitle: 'Plan · usage · invoices · top-up credits',
    Comp: V3BillingPage,
  },
  integrations: {
    title: 'Integrations',
    subtitle: 'GitHub · Bitbucket · Slack · webhooks',
    Comp: V2IntegrationsPage,
  },
  team: {
    title: 'Team',
    subtitle: 'Members · invites · roles',
    Comp: V3TeamPage,
  },
  trust: {
    title: 'Trust & compliance',
    subtitle: 'Security · privacy · DPA · data export',
    Comp: V3TrustPage,
  },
  insights: {
    title: 'Insights',
    subtitle: 'Run · cost · score trends',
    Comp: V3InsightsPage,
  },
  pricing: {
    title: 'Pricing',
    subtitle: 'Per-model token rates · provider pricing',
    Comp: V2PricingPage,
  },
};

export default function EmbedPage() {
  const {surface} = useParams<{surface: Surface}>();
  const meta = surface ? SURFACE_META[surface] : undefined;

  if (!meta) {
    return (
      <div className='v4-card' style={{padding: 24}}>
        Unknown surface: <code>{surface}</code>.
      </div>
    );
  }

  const {Comp, title, subtitle} = meta;

  return (
    <>
      <Link
        to='/v4/settings'
        className='v4-press'
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 4,
          padding: '4px 8px 4px 4px',
          marginBottom: 6,
          fontSize: 'var(--v4-text-sm)',
          color: 'var(--v4-text-muted)',
          textDecoration: 'none',
          borderRadius: 8,
        }}
      >
        <ChevronLeft size={16} aria-hidden='true' />
        Settings
      </Link>
      <LargeTitle title={title} subtitle={subtitle} />
      <div className='v4-card' style={{padding: 'clamp(12px, 3vw, 18px)', marginBottom: 24}}>
        <Comp />
      </div>
    </>
  );
}
