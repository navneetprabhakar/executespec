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
  if (!items?.length) return <span style={{color: 'var(--ide-fg-muted)'}}>None</span>;
  return (
    <ul style={{margin: '4px 0 0', paddingLeft: 20}}>
      {items.map((item, index) => (
        <li key={`${item}-${index}`}>{item}</li>
      ))}
    </ul>
  );
}

export function PlanReviewDetails({planJson}: {planJson: string}) {
  const plan = parsePlan(planJson);
  if (!plan?.tasks?.length) return null;
  const requirements = new Map(
    (plan.requirements ?? []).map((requirement) => [requirement.id, requirement]),
  );

  return (
    <div data-testid='plan-review-details'>
      <div className='ide-card' style={{fontSize: 12.5, lineHeight: 1.55}}>
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
          <details className='ide-card' key={task.id || index} style={{marginBottom: 8}}>
            <summary style={{cursor: 'pointer', color: 'var(--ide-fg-bright)'}}>
              {index + 1}. {task.title || task.id || 'Untitled step'}
              {task.complexity ? ` · ${task.complexity}` : ''}
            </summary>
            <div style={{fontSize: 12.5, lineHeight: 1.55, marginTop: 10}}>
              <div style={{marginBottom: 10}}>{task.description || 'No task description.'}</div>
              <strong>Depends on</strong>
              <BulletList items={task.dependsOn} />
              <div style={{marginTop: 10}}>
                <strong>Requirements covered</strong>
                <BulletList items={covered} />
              </div>
              <div style={{marginTop: 10}}>
                <strong>Non-negotiable constraints</strong>
                <BulletList items={task.hardConstraints} />
              </div>
              <div style={{marginTop: 10}}>
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
