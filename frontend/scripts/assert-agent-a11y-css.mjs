import {readFileSync, readdirSync, statSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const frontendRoot = join(scriptDir, '..');
const srcRoot = join(frontendRoot, 'src');
const ideCss = readFileSync(join(srcRoot, 'ide/ide.css'), 'utf8');
const consoleCss = readFileSync(join(srcRoot, 'console/console.css'), 'utf8');
const css = `${ideCss}\n${consoleCss}`;
const cssWithoutComments = css.replace(/\/\*[\s\S]*?\*\//g, '');
const ideFiles = sourceFiles(join(srcRoot, 'ide'));
const consoleFiles = sourceFiles(join(srcRoot, 'console'));

assertContains(rule('.agent-bar-btn'), 'min-height: 32px', '.agent-bar-btn target size');
assertContains(
  rule('.agent-approval-pill'),
  'min-height: 32px',
  '.agent-approval-pill target size',
);
assertContains(rule('.agent-bar-icon'), 'min-width: 32px', '.agent-bar-icon width');
assertContains(rule('.agent-bar-icon'), 'min-height: 32px', '.agent-bar-icon height');
assertContains(
  rule('.agent-artifact-actions .ide-btn'),
  'min-height: 32px',
  '.agent-artifact-actions target size',
);
assertContains(
  rule('.agent-artifact-handoff a'),
  'min-height: 32px',
  '.agent-artifact-handoff link target size',
);
assertContains(
  rule('.ide-root .agent-empty-chips button'),
  'min-height: 32px',
  '.agent-empty-chips target size',
);
assertContains(rule('.agent-attach-btn'), 'min-width: 32px', '.agent-attach-btn width');
assertContains(rule('.agent-attach-btn'), 'min-height: 32px', '.agent-attach-btn height');
assertContains(rule('.agent-send'), 'width: 36px', '.agent-send width');
assertContains(rule('.agent-send'), 'height: 36px', '.agent-send height');
assertContains(rule('.agent-card-title'), 'min-height: 32px', '.agent-card-title target size');
assertContains(rule('.con-skip-link'), 'min-height: 32px', '.con-skip-link target size');
assertContains(
  rule('.con-skip-link:focus-visible'),
  'transform: translateY(0)',
  'skip link visible on focus',
);
assertContains(rule('.con-brand'), 'min-height: 32px', '.con-brand target size');
assertContains(rule('.con-root .con-banner-x'), 'width: 32px', '.con-banner-x width');
assertContains(rule('.con-root .con-banner-x'), 'height: 32px', '.con-banner-x height');
assertContains(rule('.ide-root .con-newrun'), 'min-height: 32px', '.con-newrun target size');
assertContains(rule('.con-side-collapse'), 'width: 32px', '.con-side-collapse width');
assertContains(rule('.con-side-collapse'), 'height: 32px', '.con-side-collapse height');
assertContains(rule('.con-navitem'), 'min-height: 32px', '.con-navitem target size');
assertContains(rule('.con-runitem'), 'min-height: 32px', '.con-runitem target size');
assertContains(rule('.con-side-mini'), 'min-width: 32px', '.con-side-mini width');
assertContains(rule('.con-side-mini'), 'min-height: 32px', '.con-side-mini height');
assertContains(rule('.con-section-link'), 'min-height: 32px', '.con-section-link target size');
assertContains(rule('.con-inline-link'), 'min-height: 32px', '.con-inline-link target size');
assertContains(
  rule('.ide-badge.interactive'),
  'min-height: 32px',
  '.ide-badge interactive target size',
);
assertContains(
  rule('.ide-compose-advanced summary'),
  'min-height: 32px',
  '.ide-compose-advanced summary target size',
);
assertContains(
  rule('.agent-pop-diagnostics summary'),
  'min-height: 32px',
  '.agent diagnostics summary target size',
);

assertContains(
  css,
  '.ide-root .agent-chat-page button:focus-visible',
  'agent button focus selector',
);
assertContains(css, '.ide-root .agent-chat-page a:focus-visible', 'agent link focus selector');
assertContains(css, '.ide-root .agent-attach-btn:focus-within', 'agent attach focus selector');
assertContains(
  groupedRuleContaining('.ide-root .agent-chat-page button:focus-visible'),
  'outline: 2px solid var(--ide-accent)',
  'agent focus outline',
);

for (const file of [...ideFiles, ...consoleFiles]) {
  const source = readFileSync(file, 'utf8');
  assertNotContains(source, "<div className='ide-h1'", `${file} must not use div.ide-h1`);
  assertNotContains(source, '<div className="ide-h1"', `${file} must not use div.ide-h1`);
}

const agentPanels = readFileSync(join(srcRoot, 'ide/agentPanels.tsx'), 'utf8');
assertContains(agentPanels, "<h1 className='ide-sr-only'>Agent</h1>", 'agent hidden h1');
assertContains(agentPanels, "aria-label='Message the agent'", 'agent composer label');

const consoleShell = readFileSync(join(srcRoot, 'console/ConsoleShell.tsx'), 'utf8');
assertContains(consoleShell, "className='con-skip-link'", 'console skip link');
assertContains(consoleShell, 'Skip to main content', 'console skip link copy');
assertContains(consoleShell, "id='main-content'", 'main content skip target');

const composeSurface = readFileSync(join(srcRoot, 'ide/IdeSurfaces.tsx'), 'utf8');
assertContains(composeSurface, "aria-label='Work request'", 'compose work request textarea label');
assertContains(
  composeSurface,
  "aria-labelledby='compose-provider-label'",
  'compose provider select label',
);
assertContains(composeSurface, "aria-labelledby='compose-mode-label'", 'compose mode select label');
assertContains(composeSurface, "aria-label='Run cost cap in USD'", 'compose cap input label');

const projectCompose = readFileSync(join(srcRoot, 'ide/IdeProject.tsx'), 'utf8');
assertContains(
  projectCompose,
  "aria-label='Project run provider'",
  'project provider select label',
);
assertContains(
  projectCompose,
  "aria-label='Project run approval mode'",
  'project mode select label',
);
assertContains(
  projectCompose,
  "className='ide-badge interactive'",
  'project repo chips target size',
);

const projectSettings = readFileSync(join(srcRoot, 'ide/IdeProjectSettings.tsx'), 'utf8');
for (const label of [
  'Project name',
  'Project description',
  'Default AI provider',
  'Target branch',
  'Branch naming pattern',
  'Default GitHub owner',
]) {
  assertContains(projectSettings, `aria-label='${label}'`, `project settings label: ${label}`);
}

const projectMembers = readFileSync(join(srcRoot, 'ide/IdeProjectMembers.tsx'), 'utf8');
assertContains(
  projectMembers,
  "aria-label='Organization member to add'",
  'project org member select label',
);
assertContains(
  projectMembers,
  "aria-label='External collaborator email'",
  'project external invite email label',
);
assertContains(projectMembers, 'minHeight: 32', 'project member action target size');

const projectMore = readFileSync(join(srcRoot, 'ide/IdeProjectMore.tsx'), 'utf8');
assertContains(projectMore, 'style={{minHeight: 32}}', 'project chat memory toggle target size');

const systemSurfaces = readFileSync(join(srcRoot, 'ide/IdeSystem.tsx'), 'utf8');
assertContains(
  systemSurfaces,
  "aria-label='Search work, projects, and files'",
  'search input accessible label',
);
assertContains(systemSurfaces, 'minHeight: 32', 'search input target size');

const settingsSurfaces = readFileSync(join(srcRoot, 'ide/IdeSettings.tsx'), 'utf8');
assertContains(settingsSurfaces, 'minHeight: 32', 'integration action target size');

const orgSettings = readFileSync(join(srcRoot, 'ide/IdeOrg.tsx'), 'utf8');
assertContains(orgSettings, "aria-label='Organization invite email'", 'org invite email label');
assertContains(orgSettings, "aria-label='Organization invite role'", 'org invite role label');
assertContains(
  orgSettings,
  'aria-label={`Organization role for ${mAny.email || displayName}`}',
  'org member role select label',
);
assertContains(orgSettings, "aria-label='Organization name'", 'org name input label');
assertContains(
  orgSettings,
  "aria-label='Organization authorized email domains'",
  'org domains input label',
);
assertContains(
  orgSettings,
  "aria-label='Allow external project collaborators'",
  'external collaborators switch label',
);
assertContains(orgSettings, 'width: 44,\n              height: 32,', 'external switch target size');
assertContains(orgSettings, "aria-label='Team seat count'", 'team seat count input label');
assertContains(orgSettings, "aria-label='SSO metadata URL'", 'SSO metadata URL input label');
assertContains(orgSettings, "aria-label='SSO login domain'", 'SSO login domain input label');
assertContains(
  orgSettings,
  "aria-label='Enforce SSO for organization'",
  'SSO enforcement checkbox label',
);
assertContains(
  orgSettings,
  'aria-label={`${row.label} organization provider key`}',
  'org provider key input label',
);
assertContains(
  orgSettings,
  'aria-label={`${label} personal provider key`}',
  'personal provider key input label',
);
assertContains(
  orgSettings,
  'aria-label={`${t} ${row.provider} model override`}',
  'org model matrix select label',
);

console.log('Agent a11y guardrail passed.');

function rule(selector) {
  const blocks = cssWithoutComments.match(/[^{}]+\{[^}]*\}/g) ?? [];
  const block = blocks.find((candidate) =>
    candidate
      .split('{')[0]
      .split(',')
      .map((part) => part.trim())
      .includes(selector),
  );
  if (!block) throw new Error(`Missing CSS rule for ${selector}`);
  return block.slice(block.indexOf('{') + 1, block.lastIndexOf('}'));
}

function groupedRuleContaining(selector) {
  const blocks = css.match(/[^{}]+\{[^}]*\}/g) ?? [];
  const block = blocks.find((candidate) => candidate.split('{')[0].includes(selector));
  if (!block) throw new Error(`Missing CSS rule containing ${selector}`);
  return block;
}

function assertContains(value, expected, label) {
  if (!value.includes(expected)) {
    throw new Error(`${label}: expected to find "${expected}"`);
  }
}

function assertNotContains(value, unexpected, label) {
  if (value.includes(unexpected)) {
    throw new Error(`${label}: found "${unexpected}"`);
  }
}

function sourceFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    const stat = statSync(path);
    if (stat.isDirectory()) out.push(...sourceFiles(path));
    if (stat.isFile() && /\.(tsx|ts)$/.test(entry)) out.push(path);
  }
  return out;
}
