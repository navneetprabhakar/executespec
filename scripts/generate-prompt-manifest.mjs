#!/usr/bin/env node

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const promptRoot = path.join(root, 'src/main/resources/prompts');
const output = path.join(root, 'infra/prompts/prompt-manifest.json');

function walk(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, {withFileTypes: true}).flatMap((entry) => {
    const file = path.join(dir, entry.name);
    return entry.isDirectory() ? walk(file) : [file];
  });
}

const files = walk(promptRoot).filter((file) => /\.(?:md|txt|json)$/.test(file)).sort();

// Dynamic prompt paths cannot be recovered from the resource walk alone. Keep the small,
// executable-path contract here so a renamed provider fragment, scaffold, or role profile fails
// the offline manifest check before it reaches a worker. This is deliberately an inventory (not a
// second runtime router): Java remains the source of runtime behaviour; the sourceFile/sourceToken
// fields tie each row back to the resolver that must honour it.
const runtimeCoverage = [
  {
    id: 'worker-build',
    caller: 'harness.SequentialExecutor.composeSystemPrompt',
    sourceFile: 'src/main/java/com/navneet/executespec/harness/SequentialExecutor.java',
    sourceTokens: ['loadProviderFragment', 'loadProjectTypeFragments', 'loadScaffoldForProjectType'],
    role: 'WORKER',
    mode: 'BUILD',
    profile: 'worker-build',
    tools: ['readFile', 'writeFile', 'editFile', 'listFiles', 'searchFiles', 'shell', 'buildProject', 'completeTask'],
    providers: ['anthropic', 'deepseek', 'fireworks', 'gemini', 'openai', 'openrouter'],
    providerModes: ['build'],
    projectTypes: ['fullstack', 'node-backend', 'node-frontend', 'spring-boot', 'python', 'go', 'rust'],
    scaffolds: ['fullstack', 'node-backend', 'node-frontend', 'spring-boot', 'python', 'go', 'rust'],
    fragments: ['api-url-rules', 'backend-robustness', 'fullstack-directory', 'module-system', 'preview-defaults', 'router-isolation'],
  },
  {
    id: 'patch-worker',
    caller: 'engine.CodeGenEngineImpl.buildSystemPrompt',
    sourceFile: 'src/main/java/com/navneet/executespec/engine/CodeGenEngineImpl.java',
    sourceTokens: ['loadProviderPatchFragment', 'prompts/fragments/provider/'],
    role: 'WORKER',
    mode: 'PATCH',
    profile: 'patch-worker',
    tools: ['readFile', 'writeFile', 'editFile', 'listFiles', 'searchFiles', 'shell', 'runTests', 'completeTask'],
    providers: ['anthropic', 'deepseek', 'fireworks', 'gemini', 'openai', 'openrouter'],
    providerModes: ['patch'],
  },
  {
    id: 'terminal-worker',
    caller: 'terminalbench.TerminalBenchRunService.systemPromptForProvider',
    sourceFile: 'src/main/java/com/navneet/executespec/terminalbench/TerminalBenchRunService.java',
    sourceTokens: ['loadTerminalProviderFragment', 'prompts/fragments/provider/'],
    role: 'WORKER',
    mode: 'TERMINAL_BENCH',
    profile: 'terminal-worker',
    tools: ['remoteToolDispatcher'],
    providers: ['anthropic', 'deepseek', 'openrouter'],
    providerModes: ['terminal'],
  },
  {
    id: 'readymade-scaffold-reference',
    caller: 'readymade.entity.ReadymadeVariant',
    sourceFile: 'src/main/java/com/navneet/executespec/readymade/entity/ReadymadeVariant.java',
    sourceTokens: ['prompts/scaffolds/<key>.md', 'scaffold_key'],
    role: 'PLANNER',
    mode: 'BUILD',
    profile: 'readymade-recipe',
    tools: ['submitPlan'],
    scaffolds: ['all', 'fullstack', 'go', 'java', 'node-backend', 'node-frontend', 'python', 'react-native-expo', 'rust', 'spring-boot', 'static-html'],
  },
];

const prompts = files.map((file) => {
  const relativePath = path.relative(root, file).split(path.sep).join('/');
  const content = fs.readFileSync(file);
  const text = content.toString('utf8');
  return {
    id: relativePath.replace(/^src\/main\/resources\/prompts\//, '').replace(/\.[^.]+$/, ''),
    path: relativePath,
    sha256: crypto.createHash('sha256').update(content).digest('hex'),
    bytes: content.length,
    active: !/(?:archive|rollback|legacy|deprecated)/i.test(relativePath),
    fragments: [...text.matchAll(/\{\{include:([^}]+)\}\}/g)].map((match) => match[1]).sort(),
    variables: [...text.matchAll(/\{\{([A-Z][A-Z0-9_]*)\}\}/g)].map((match) => match[1]).filter((value, index, values) => values.indexOf(value) === index).sort(),
  };
});

const manifest = {
  schemaVersion: 1,
  source: 'src/main/resources/prompts',
  policy: 'Prompt hashes are evidence identities; prompt tuning must record provider/model, tool-schema, harness, task slice, flags, and budgets separately.',
  prompts,
  runtimeCoverage,
};
fs.mkdirSync(path.dirname(output), {recursive: true});
fs.writeFileSync(output, `${JSON.stringify(manifest, null, 2)}\n`);
process.stdout.write(`${output}\n`);
