import {describe, expect, it} from 'vitest';

import {extractAgentArtifacts} from './agentArtifacts';

describe('agentArtifacts', () => {
  it('extracts named artifacts from fenced code blocks', () => {
    const artifacts = extractAgentArtifacts(`
Here is \`src/App.tsx\`:

\`\`\`tsx
export function App() {
  return <main />;
}
\`\`\`

\`\`\`css filename=src/app.css
main { display: grid; }
\`\`\`
`);

    expect(artifacts).toMatchObject([
      {
        filename: 'src/App.tsx',
        language: 'tsx',
        lineCount: 3,
        previewable: false,
      },
      {
        filename: 'src/app.css',
        language: 'css',
        lineCount: 1,
        previewable: false,
      },
    ]);
  });

  it('falls back to stable names and marks browser-previewable artifacts', () => {
    const artifacts = extractAgentArtifacts(`
\`\`\`html
<h1>Hello</h1>
\`\`\`
`);

    expect(artifacts).toEqual([
      {
        id: '0:artifact-1.html',
        filename: 'artifact-1.html',
        language: 'html',
        content: '<h1>Hello</h1>',
        lineCount: 1,
        previewable: true,
      },
    ]);
  });
});
