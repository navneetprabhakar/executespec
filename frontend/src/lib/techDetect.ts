/**
 * Detects the technology stack from a plain-text run request.
 * Used to render tech logos on RunCard.
 */

export interface TechDef {
  id: string;
  label: string;
  /** Tailwind text-color class */
  textCls: string;
  /** Tailwind bg class (translucent) */
  bgCls: string;
  /** Hex dot / icon fill colour */
  hex: string;
  /** Keywords that trigger detection (matched on lowercase text) */
  keywords: string[];
  /** Priority — lower number shown first; used for dedup ordering */
  priority: number;
}

export const TECH_DEFS: TechDef[] = [
  // ── Frontend frameworks ──────────────────────────────────────────────────
  {
    id: 'react',
    label: 'React',
    textCls: 'text-[#61DAFB]',
    bgCls: 'bg-[#61DAFB]/10',
    hex: '#61DAFB',
    keywords: ['react', 'reactjs', 'react.js', 'create-react-app', 'react 18', 'react hooks'],
    priority: 10,
  },
  {
    id: 'nextjs',
    label: 'Next.js',
    textCls: 'text-primary',
    bgCls: 'bg-[var(--border-default)]',
    hex: '#ffffff',
    keywords: ['next.js', 'nextjs', 'next js', '"next"'],
    priority: 11,
  },
  {
    id: 'vue',
    label: 'Vue',
    textCls: 'text-[#4FC08D]',
    bgCls: 'bg-[#4FC08D]/10',
    hex: '#4FC08D',
    keywords: ['vuejs', 'vue.js', 'nuxt', 'vue 3', 'vue 2'],
    priority: 12,
  },
  {
    id: 'angular',
    label: 'Angular',
    textCls: 'text-[#DD0031]',
    bgCls: 'bg-[#DD0031]/10',
    hex: '#DD0031',
    keywords: ['angular', 'angularjs', 'angular.js'],
    priority: 13,
  },
  {
    id: 'svelte',
    label: 'Svelte',
    textCls: 'text-[#FF3E00]',
    bgCls: 'bg-[#FF3E00]/10',
    hex: '#FF3E00',
    keywords: ['svelte', 'sveltekit', 'svelte kit'],
    priority: 14,
  },
  // ── Vanilla web ────────────────────────────────────────────────────────
  {
    id: 'html',
    label: 'HTML/CSS/JS',
    textCls: 'text-[#E34F26]',
    bgCls: 'bg-[#E34F26]/10',
    hex: '#E34F26',
    keywords: ['html', 'css', 'vanilla js', 'vanilla javascript', 'plain html', 'static html', 'index.html'],
    priority: 15,
  },
  // ── Type systems ─────────────────────────────────────────────────────────
  {
    id: 'typescript',
    label: 'TypeScript',
    textCls: 'text-[#3178C6]',
    bgCls: 'bg-[#3178C6]/10',
    hex: '#3178C6',
    keywords: ['typescript', ' ts ', '.ts', 'tsx', 'tsconfig'],
    priority: 20,
  },
  // ── Backend / runtime ────────────────────────────────────────────────────
  {
    id: 'nodejs',
    label: 'Node.js',
    textCls: 'text-[#68A063]',
    bgCls: 'bg-[#68A063]/10',
    hex: '#68A063',
    keywords: ['nodejs', 'node.js', 'express', 'fastify', 'koa', 'hapi', 'nestjs', 'nest.js'],
    priority: 30,
  },
  {
    id: 'java',
    label: 'Java',
    textCls: 'text-[#ED8B00]',
    bgCls: 'bg-[#ED8B00]/10',
    hex: '#ED8B00',
    keywords: ['java', 'jvm', 'maven', 'gradle', 'jar', 'pom.xml'],
    priority: 31,
  },
  {
    id: 'spring',
    label: 'Spring Boot',
    textCls: 'text-[#6DB33F]',
    bgCls: 'bg-[#6DB33F]/10',
    hex: '#6DB33F',
    keywords: ['spring boot', 'springboot', 'spring-boot', '@springbootapplication', 'spring framework'],
    priority: 32,
  },
  {
    id: 'python',
    label: 'Python',
    textCls: 'text-[#FFD43B]',
    bgCls: 'bg-[#FFD43B]/10',
    hex: '#FFD43B',
    keywords: ['python', 'django', 'fastapi', 'flask', 'pip', 'pypi', 'celery', 'pandas', 'numpy'],
    priority: 33,
  },
  {
    id: 'go',
    label: 'Go',
    textCls: 'text-[#00ADD8]',
    bgCls: 'bg-[#00ADD8]/10',
    hex: '#00ADD8',
    keywords: ['golang', 'go lang', ' go ', 'gin framework', 'fiber', 'echo framework'],
    priority: 34,
  },
  {
    id: 'rust',
    label: 'Rust',
    textCls: 'text-[#CE422B]',
    bgCls: 'bg-[#CE422B]/10',
    hex: '#CE422B',
    keywords: ['rust', 'cargo', 'tokio', 'actix', 'axum'],
    priority: 35,
  },
  {
    id: 'php',
    label: 'PHP',
    textCls: 'text-[#A67EDB]',
    bgCls: 'bg-[#A67EDB]/10',
    hex: '#A67EDB',
    keywords: ['php', 'laravel', 'symfony', 'wordpress', 'composer'],
    priority: 36,
  },
  {
    id: 'ruby',
    label: 'Ruby',
    textCls: 'text-[#CC342D]',
    bgCls: 'bg-[#CC342D]/10',
    hex: '#CC342D',
    keywords: ['ruby', 'rails', 'ruby on rails', 'sinatra', 'bundler'],
    priority: 37,
  },
  {
    id: 'dotnet',
    label: '.NET',
    textCls: 'text-[#9A5CC6]',
    bgCls: 'bg-[#9A5CC6]/10',
    hex: '#9A5CC6',
    keywords: ['.net', 'dotnet', 'csharp', 'c#', 'asp.net', 'blazor', 'nuget'],
    priority: 38,
  },
  // ── Databases ────────────────────────────────────────────────────────────
  {
    id: 'postgres',
    label: 'PostgreSQL',
    textCls: 'text-[#4169E1]',
    bgCls: 'bg-[#4169E1]/10',
    hex: '#4169E1',
    keywords: ['postgresql', 'postgres', 'psql'],
    priority: 50,
  },
  {
    id: 'mongodb',
    label: 'MongoDB',
    textCls: 'text-[#47A248]',
    bgCls: 'bg-[#47A248]/10',
    hex: '#47A248',
    keywords: ['mongodb', 'mongoose', 'mongo db'],
    priority: 51,
  },
  {
    id: 'mysql',
    label: 'MySQL',
    textCls: 'text-[#4479A1]',
    bgCls: 'bg-[#4479A1]/10',
    hex: '#4479A1',
    keywords: ['mysql', 'mariadb'],
    priority: 52,
  },
  {
    id: 'redis',
    label: 'Redis',
    textCls: 'text-[#DC382D]',
    bgCls: 'bg-[#DC382D]/10',
    hex: '#DC382D',
    keywords: ['redis', 'redisearch', 'valkey'],
    priority: 53,
  },
  {
    id: 'sqlite',
    label: 'SQLite',
    textCls: 'text-[#44A4C8]',
    bgCls: 'bg-[#44A4C8]/10',
    hex: '#44A4C8',
    keywords: ['sqlite', 'sqlite3'],
    priority: 54,
  },
  // ── Infra / cloud ────────────────────────────────────────────────────────
  {
    id: 'docker',
    label: 'Docker',
    textCls: 'text-[#2496ED]',
    bgCls: 'bg-[#2496ED]/10',
    hex: '#2496ED',
    keywords: ['docker', 'dockerfile', 'container', 'docker-compose'],
    priority: 60,
  },
];

/** Max badges shown to keep card compact */
const MAX_BADGES = 5;

/** Build a lookup map of tech ID → TechDef for O(1) resolution */
const TECH_BY_ID: Record<string, TechDef> = Object.fromEntries(
  TECH_DEFS.map((t) => [t.id, t]),
);

/**
 * Resolves a comma-separated tech stack string (from the backend) into TechDef[].
 * Falls back to keyword-based detection from userRequest if techStack is null/empty.
 */
export function resolveTechStack(techStack: string | null | undefined, userRequest: string): TechDef[] {
  if (techStack) {
    const ids = techStack.split(',').map((s) => s.trim()).filter(Boolean);
    const resolved = ids.map((id) => TECH_BY_ID[id]).filter(Boolean) as TechDef[];
    if (resolved.length > 0) return resolved.slice(0, MAX_BADGES);
  }
  // Fallback: keyword detection from user request text
  return detectTechStack(userRequest);
}

/**
 * Returns the top-N detected tech stacks from `text`, ordered by priority.
 * Deduplication ensures each tech appears at most once.
 */
/** Keywords that must match as whole words (not substrings) to avoid false positives. */
const WORD_BOUNDARY_IDS = new Set(['java', 'go', 'rust', 'ruby', 'nodejs']);

export function detectTechStack(text: string): TechDef[] {
  if (!text) return [];
  const lower = text.toLowerCase();
  const found: TechDef[] = [];

  for (const tech of TECH_DEFS) {
    const useWordBoundary = WORD_BOUNDARY_IDS.has(tech.id);
    const matched = tech.keywords.some((kw) => {
      if (useWordBoundary) {
        // Word-boundary match: prevent "java" matching inside "javascript"
        const re = new RegExp(`\\b${kw.trim().replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`);
        return re.test(lower);
      }
      return lower.includes(kw);
    });
    if (matched) {
      found.push(tech);
    }
  }

  // Sort by priority then cap
  found.sort((a, b) => a.priority - b.priority);

  // If Spring Boot detected, suppress the generic "Java" badge to avoid redundancy
  const hasSpring = found.some((t) => t.id === 'spring');
  const deduplicated = hasSpring ? found.filter((t) => t.id !== 'java') : found;

  // If Next.js detected, suppress React (it implies React)
  const hasNext = deduplicated.some((t) => t.id === 'nextjs');
  const final = hasNext ? deduplicated.filter((t) => t.id !== 'react') : deduplicated;

  return final.slice(0, MAX_BADGES);
}
