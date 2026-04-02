import { Briefcase, Receipt, Server, Globe } from 'lucide-react';

interface Suggestion {
  icon: React.ReactNode;
  title: string;
  description: string;
  goal: string;
}

const SUGGESTIONS: Suggestion[] = [
  {
    icon: <Briefcase className="h-5 w-5 text-accent-blue" />,
    title: 'Portfolio Website',
    description: 'Personal portfolio with projects, about, and contact sections',
    goal: 'Build a modern personal portfolio website with a hero section, projects grid, about page, and contact form. Use a clean dark theme with smooth animations.',
  },
  {
    icon: <Receipt className="h-5 w-5 text-accent-green" />,
    title: 'Expense Tracker',
    description: 'Track spending with categories, charts, and monthly summaries',
    goal: 'Build an expense tracker app with add/edit/delete transactions, category tagging, monthly summary dashboard with charts, and local storage persistence.',
  },
  {
    icon: <Server className="h-5 w-5 text-accent-amber" />,
    title: 'REST API',
    description: 'CRUD API with authentication, validation, and docs',
    goal: 'Build a RESTful API with user authentication (JWT), CRUD endpoints for a "tasks" resource, input validation, error handling, and auto-generated API documentation.',
  },
  {
    icon: <Globe className="h-5 w-5 text-secondary" />,
    title: 'SaaS Landing Page',
    description: 'Marketing page with pricing, features, and testimonials',
    goal: 'Build a modern SaaS landing page with hero section, feature grid, pricing table (3 tiers), testimonials carousel, FAQ accordion, and a call-to-action footer. Responsive design.',
  },
];

interface FirstRunSuggestionsProps {
  onSelect: (goal: string) => void;
}

export default function FirstRunSuggestions({ onSelect }: FirstRunSuggestionsProps) {
  return (
    <div className="flex flex-col items-center justify-center py-12 px-4 gap-6 text-center">
      <div>
        <p className="text-base font-semibold text-primary">Launch your first run</p>
        <p className="text-sm text-muted mt-1">Pick a template to get started, or create your own.</p>
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 w-full max-w-lg">
        {SUGGESTIONS.map((s) => (
          <button
            key={s.title}
            onClick={() => onSelect(s.goal)}
            className="flex items-start gap-3 p-4 rounded-md border border-[var(--border-default)] bg-[var(--gray-100)] hover:border-[var(--border-hover)] hover:bg-[var(--gray-200)] transition-colors text-left"
          >
            <div className="flex-shrink-0 mt-0.5">{s.icon}</div>
            <div className="min-w-0">
              <p className="text-sm font-medium text-primary">{s.title}</p>
              <p className="text-xs text-muted mt-0.5 line-clamp-2">{s.description}</p>
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}
