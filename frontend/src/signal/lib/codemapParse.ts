/**
 * Parse a codemap markdown blob into a file list + per-file detail map so
 * the Project → Code → Browse view can render a folder tree without needing
 * a separate backend endpoint.
 *
 * Codemap shape (see CodeMapGenerator.generate):
 *
 *   # owner/repo
 *
 *   **Files**: N
 *
 *   ## Top-level layout
 *   - dir1
 *   - dir2
 *   ...
 *
 *   ## Routes
 *   - `GET /api/foo`
 *   ...
 *
 *   ## Files (listing)
 *   - `path/to/file.ts`
 *   - `another/file.java`
 *   ...
 *
 *   ## Functions
 *
 *   ### `path/to/file.ts`
 *
 *   <optional 1-line purpose summary>
 *
 *   - func1
 *   - func2
 *   ...
 *
 * The full listing under `## Files (listing)` is the source of truth for
 * what's in the repo — it carries every path the GitHub Trees API returned
 * (the same source the function-extractor uses). The per-file `## Functions`
 * sections only cover whatever subset the regex pass succeeded on (bounded
 * by MAX_FUNCTION_FILES on the backend). We merge both: every path becomes
 * a file in the tree, and the `## Functions` section enriches matching
 * paths with their function list + optional purpose summary.
 */

export interface CodemapFile {
  path: string;
  // Function names extracted from the file (regex pass on the backend).
  functions: string[];
  // Optional one-line purpose summary (PR-N2 feature flag).
  purpose: string | null;
  // Raw markdown content of this section, for the file-detail panel.
  rawMd: string;
}

export interface CodemapSummary {
  repoSlug: string | null;
  fileCount: number;
  topLevelDirs: string[];
  routes: string[];
  files: CodemapFile[];
}

/**
 * Parse the codemap markdown body. Returns an empty summary if the input
 * is empty or not in the expected shape — the caller just shows an empty
 * state in that case.
 */
export function parseCodemap(md: string): CodemapSummary {
  if (!md || typeof md !== 'string') {
    return {repoSlug: null, fileCount: 0, topLevelDirs: [], routes: [], files: []};
  }

  const lines = md.split('\n');
  let repoSlug: string | null = null;
  let fileCount = 0;
  const topLevelDirs: string[] = [];
  const routes: string[] = [];
  // The full file listing under `## Files (listing)` (added 2026-05-16). The
  // browser uses this as the master file tree; the per-file `## Functions`
  // sections then enrich matching paths with function + purpose details.
  const allFilePaths = new Set<string>();
  const fileDetails = new Map<string, CodemapFile>();

  // Sections are H2 headings; file blocks are H3 headings inside ## Functions.
  let section: 'preamble' | 'layout' | 'routes' | 'models' | 'files' | 'functions' | 'other' =
    'preamble';
  let currentFile: CodemapFile | null = null;
  let currentFileBuf: string[] = [];

  function flushFile() {
    if (currentFile) {
      currentFile.rawMd = currentFileBuf.join('\n').trim();
      // Extract purpose summary: the first non-empty, non-bullet line in the
      // body. The summariser writes a single-line markdown blockquote or
      // plain sentence at the top of each section.
      const firstNonBullet = currentFileBuf
        .map((l) => l.trim())
        .find((l) => l.length > 0 && !l.startsWith('-'));
      if (firstNonBullet) currentFile.purpose = firstNonBullet.replace(/^>\s*/, '');
      fileDetails.set(currentFile.path, currentFile);
      allFilePaths.add(currentFile.path);
    }
    currentFile = null;
    currentFileBuf = [];
  }

  for (const rawLine of lines) {
    const line = rawLine.trimEnd();
    // H1 = repo slug
    if (line.startsWith('# ') && repoSlug == null) {
      repoSlug = line.slice(2).trim() || null;
      continue;
    }
    // Files: N
    const filesMatch = line.match(/^\*\*Files\*\*\s*[:：]\s*(\d+)/);
    if (filesMatch) {
      fileCount = parseInt(filesMatch[1], 10);
      continue;
    }
    // H2 section boundary
    if (line.startsWith('## ')) {
      flushFile();
      const heading = line.slice(3).trim().toLowerCase();
      if (heading.startsWith('top-level')) section = 'layout';
      else if (heading.startsWith('routes')) section = 'routes';
      else if (heading.startsWith('models') || heading.startsWith('entities')) section = 'models';
      else if (heading.startsWith('files')) section = 'files';
      else if (heading.startsWith('functions')) section = 'functions';
      else section = 'other';
      continue;
    }
    // H3 file header inside ## Functions — flush previous file and start new
    if (section === 'functions' && line.startsWith('### ')) {
      flushFile();
      // ### `path/to/file.ts` — strip the backticks
      const path = line.slice(4).trim().replace(/^`|`$/g, '');
      currentFile = {path, functions: [], purpose: null, rawMd: ''};
      continue;
    }
    // Section bodies
    if (section === 'layout' && line.startsWith('- ')) {
      topLevelDirs.push(line.slice(2).trim());
    } else if (section === 'routes' && line.startsWith('- ')) {
      // routes formatted as `- \`METHOD /path\``
      routes.push(line.slice(2).trim().replace(/^`|`$/g, ''));
    } else if (section === 'files' && line.startsWith('- ')) {
      // ## Files (listing) bullets: `- \`path/to/file.ext\``
      // The `- … (+N more)` overflow marker is the only non-path bullet; ignore it.
      const raw = line.slice(2).trim();
      if (raw.startsWith('…') || raw.startsWith('...')) continue;
      const path = raw.replace(/^`|`$/g, '').trim();
      if (path) allFilePaths.add(path);
    } else if (section === 'functions' && currentFile) {
      currentFileBuf.push(rawLine);
      if (line.startsWith('- ')) {
        currentFile.functions.push(line.slice(2).trim().replace(/^`|`$/g, ''));
      }
    }
  }
  flushFile();

  // Merge: every path from `## Files (listing)` becomes an entry; the
  // function-section data (when present) enriches the matching path.
  const files: CodemapFile[] = Array.from(allFilePaths)
    .sort()
    .map(
      (path) =>
        fileDetails.get(path) ?? {
          path,
          functions: [],
          purpose: null,
          rawMd: '',
        },
    );

  return {repoSlug, fileCount: fileCount || files.length, topLevelDirs, routes, files};
}
