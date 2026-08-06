type Requirement = {id?: string; text?: string; kind?: string};

type PlannedTask = {
  id?: string;
  title?: string;
  description?: string;
  complexity?: string;
  touchesFiles?: string[];
  dependsOn?: string[];
  hardConstraints?: string[];
  meetsRequirements?: string[];
};

type ReviewPlan = {
  planTitle?: string;
  planSummary?: string;
  projectType?: string;
  buildCommand?: string | null;
  smokeTestStrategy?: string;
  runtimeSurface?: string;
  requirements?: Requirement[];
  tasks?: PlannedTask[];
};

function parsePlan(planJson: string): ReviewPlan | null {
  try {
    const parsed: unknown = JSON.parse(planJson);
    return parsed && typeof parsed === 'object' ? (parsed as ReviewPlan) : null;
  } catch {
    return null;
  }
}

function BulletList({items}: {items?: string[]}) {
  if (!items?.length) return <span className='gf-none'>None</span>;
  return (
    <ul className='gf-bullets'>
      {items.map((item, index) => (
        <li key={`${item}-${index}`}>{item}</li>
      ))}
    </ul>
  );
}

/**
 * The plan the approver is about to authorise, step by step.
 *
 * Version B: no px font literals (the phone ramp owns type), and each step is a
 * disclosure using the app's one summary marker. The task text itself is the
 * exact planner contract and is rendered verbatim — nothing here summarises or
 * abbreviates a constraint the worker will be held to.
 */
export function PlanReviewDetails({planJson}: {planJson: string}) {
  const plan = parsePlan(planJson);
  if (!plan?.tasks?.length) return null;
  const requirements = new Map(
    (plan.requirements ?? []).map((requirement) => [requirement.id, requirement]),
  );

  return (
    <div data-testid='plan-review-details'>
      <div className='ide-card gf-plan-body' style={{marginTop: 0}}>
        <div className='ide-kv'>
          <span>Runtime</span>
          <span>{[plan.projectType, plan.runtimeSurface].filter(Boolean).join(' · ') || '—'}</span>
        </div>
        <div className='ide-kv'>
          <span>Verification</span>
          <span>{plan.smokeTestStrategy || '—'}</span>
        </div>
        <div className='ide-kv'>
          <span>Build command</span>
          <span className='ide-mono'>{plan.buildCommand || 'Auto-detect'}</span>
        </div>
      </div>

      {plan.tasks.map((task, index) => {
        const covered = (task.meetsRequirements ?? []).map((id) => {
          const requirement = requirements.get(id);
          return requirement?.text ? `${id}: ${requirement.text}` : id;
        });
        return (
          <details
            className='ide-card'
            key={task.id || index}
            style={{marginBottom: 'var(--ide-s2)'}}
          >
            <summary className='gf-plan-summary'>
              {index + 1}. {task.title || task.id || 'Untitled step'}
              {task.complexity ? ` · ${task.complexity}` : ''}
            </summary>
            <div className='gf-plan-body'>
              <div>{task.description || 'No task description.'}</div>
              <div className='gf-plan-section'>
                <strong>Depends on</strong>
                <BulletList items={task.dependsOn} />
              </div>
              <div className='gf-plan-section'>
                <strong>Requirements covered</strong>
                <BulletList items={covered} />
              </div>
              <div className='gf-plan-section'>
                <strong>Non-negotiable constraints</strong>
                <BulletList items={task.hardConstraints} />
              </div>
              <div className='gf-plan-section'>
                <strong>Expected file scope</strong>
                <BulletList items={task.touchesFiles} />
              </div>
            </div>
          </details>
        );
      })}
    </div>
  );
}
