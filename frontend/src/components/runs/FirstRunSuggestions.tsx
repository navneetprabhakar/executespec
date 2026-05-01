import {CreditCard, GitMerge, Boxes, Zap} from 'lucide-react';

interface Suggestion {
  icon: React.ReactNode;
  title: string;
  description: string;
  goal: string;
}

// R14 (2026-05-01 follow-up) — replaced four CS101-tier templates
// (Portfolio Website / Expense Tracker / REST API / SaaS Landing Page)
// with recipe-shaped templates that demonstrate ExecuteSpec's actual
// strength: migrations + Library compositions across real repos.
// Each template seeds the run goal with a specific, ship-able task
// rather than a "build me a generic web app" prompt.
const SUGGESTIONS: Suggestion[] = [
  {
    icon: <CreditCard className='h-5 w-5 text-accent-blue' />,
    title: 'Add Stripe Checkout',
    description: 'Wire Stripe-hosted Checkout into your auth + DB stack',
    goal: 'Add Stripe Checkout to the existing app: a /checkout endpoint that creates a Checkout Session with line items + customer email, a /webhook handler that listens for checkout.session.completed and marks orders paid in the database, and an env-var section in README for STRIPE_SECRET_KEY + STRIPE_WEBHOOK_SECRET. Use the Stripe Node SDK.',
  },
  {
    icon: <GitMerge className='h-5 w-5 text-status-success' />,
    title: 'Migrate Express → Fastify',
    description: '2-3× throughput; same routes, different framework',
    goal: "Migrate the Express server to Fastify. Convert every existing route to Fastify's plugin + handler pattern, port middleware (auth, CORS, body parsing) to Fastify equivalents, update the test suite to use Fastify's inject() helper instead of supertest, and update the start script + Dockerfile entrypoint.",
  },
  {
    icon: <Boxes className='h-5 w-5 text-c-info' />,
    title: 'Convert REST → tRPC',
    description: 'End-to-end type-safety with no schema drift',
    goal: 'Convert the REST API to tRPC. Define a router with the existing endpoints as procedures (queries vs mutations as appropriate), generate the typed client for the frontend, replace each fetch() call with the tRPC client, and remove the REST endpoints + their tests.',
  },
  {
    icon: <Zap className='h-5 w-5 text-c-pop-amber' />,
    title: 'Add feature flags via GrowthBook',
    description: 'A/B-test-ready gating with a self-hosted option',
    goal: 'Integrate GrowthBook for feature flag gating. Install the SDK, add a GrowthBookProvider to the React tree, create a useFeature("flag-key") hook, gate one example feature behind a flag, and document how to rollout flags via the GrowthBook dashboard. Include the self-hosted option in the README.',
  },
];

interface FirstRunSuggestionsProps {
  onSelect: (goal: string) => void;
}

export default function FirstRunSuggestions({onSelect}: FirstRunSuggestionsProps) {
  return (
    <div className='flex flex-col items-center justify-center py-12 px-4 gap-6 text-center'>
      <div>
        <p className='text-base font-semibold text-primary'>Launch your first run</p>
        <p className='text-sm text-muted mt-1'>
          Pick a template to get started, or create your own.
        </p>
      </div>
      <div className='grid grid-cols-1 sm:grid-cols-2 gap-3 w-full max-w-lg'>
        {SUGGESTIONS.map((s) => (
          <button
            key={s.title}
            onClick={() => onSelect(s.goal)}
            className='flex items-start gap-3 p-4 rounded-md border border-[var(--border-default)] bg-[var(--gray-100)] hover:border-[var(--border-hover)] hover:bg-[var(--gray-200)] transition-colors text-left'
          >
            <div className='flex-shrink-0 mt-0.5'>{s.icon}</div>
            <div className='min-w-0'>
              <p className='text-sm font-medium text-primary'>{s.title}</p>
              <p className='text-xs text-muted mt-0.5 line-clamp-2'>{s.description}</p>
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}
