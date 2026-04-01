# Frontend Separation Guide

> Step-by-step guide to extract the ExecuteSpec frontend into its own repository while keeping the same deployment strategy (single JAR with embedded frontend).

## Current Architecture

```
executespec/                          # Monorepo
  frontend/                           # React 18 + Vite 5 + Tailwind + TS 5.3
    src/
    package.json
    vite.config.ts                    # build.outDir: '../src/main/resources/static'
    tsconfig.json
  src/main/
    java/                             # Spring Boot backend
    resources/
      static/                         # <-- Vite build output lands here
        index.html
        assets/
  .github/workflows/deploy.yml       # Builds frontend → JAR → deploys to GCE
```

**How it works today:**
1. CI runs `cd frontend && npm ci && npm run build` (output: `src/main/resources/static/`)
2. CI runs `mvn package -DskipTests` (packages static assets inside the JAR)
3. JAR deployed to GCE VM, Spring Boot serves static files from `classpath:/static/`

---

## Step 1: Create the Frontend Repository

### 1.1 Create new repo

```bash
# On GitHub
gh repo create executespec-frontend --private

# Clone it locally
git clone git@github.com:<your-org>/executespec-frontend.git
```

### 1.2 Copy frontend files

```bash
# From the monorepo
cp -r executespec/frontend/* executespec-frontend/
cp executespec/frontend/.eslintrc* executespec-frontend/ 2>/dev/null
cp executespec/frontend/.prettierrc* executespec-frontend/ 2>/dev/null

cd executespec-frontend
git add .
git commit -m "Initial frontend extraction from monorepo"
git push origin main
```

### 1.3 Files to include in the new repo

```
executespec-frontend/
  src/                    # All React source
  public/                 # Static assets (if any)
  index.html              # SPA entry point
  package.json
  package-lock.json
  vite.config.ts
  tsconfig.json
  tsconfig.node.json
  tailwind.config.ts
  postcss.config.js
  .gitignore
  README.md               # New: frontend-specific docs
```

### 1.4 Update vite.config.ts in the new repo

The `outDir` must change since it's no longer relative to the backend:

```typescript
// executespec-frontend/vite.config.ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',  // Proxy to backend during local dev
    },
  },
  build: {
    outDir: 'dist',        // <-- Changed from '../src/main/resources/static'
    emptyOutDir: true,
  },
});
```

### 1.5 Add a .gitignore

```gitignore
node_modules/
dist/
*.local
```

---

## Step 2: Update CI/CD Pipeline

### 2.1 Update `.github/workflows/deploy.yml`

Replace the current frontend build step with a checkout from the frontend repo:

```yaml
# BEFORE (monorepo):
- name: Build frontend
  run: |
    cd frontend
    npm ci
    npm run build

# AFTER (separate repo):
- name: Checkout frontend
  uses: actions/checkout@v4
  with:
    repository: <your-org>/executespec-frontend
    ref: ${{ github.ref_name }}    # Track same branch (main/dev)
    path: frontend-repo
    token: ${{ secrets.FRONTEND_REPO_PAT }}

- name: Build frontend
  run: |
    cd frontend-repo
    npm ci
    npm run build

- name: Copy frontend build to backend static dir
  run: |
    rm -rf src/main/resources/static/*
    cp -r frontend-repo/dist/* src/main/resources/static/
```

### 2.2 Add the `FRONTEND_REPO_PAT` secret

1. Create a **Fine-grained PAT** on GitHub with `Contents: Read` access to `executespec-frontend`
2. Add it as a secret in the backend repo:
   - Go to `executespec` repo > Settings > Secrets > Actions
   - Add `FRONTEND_REPO_PAT` = the PAT value

### 2.3 Branch strategy

| Backend branch | Frontend branch | Deploy env |
|----------------|-----------------|------------|
| `main`         | `main`          | Production |
| `dev`          | `dev`           | Development |

The CI uses `${{ github.ref_name }}` to check out the matching frontend branch. If the frontend developer is working on a feature branch, they merge to `dev` first.

### 2.4 Alternative: Git Submodule approach

If you prefer submodules over CI-level cloning:

```bash
# In the backend repo
cd executespec
git submodule add git@github.com:<your-org>/executespec-frontend.git frontend

# Update .github/workflows/deploy.yml
# Replace checkout step with:
- name: Checkout with submodules
  uses: actions/checkout@v4
  with:
    submodules: recursive
    token: ${{ secrets.FRONTEND_REPO_PAT }}

- name: Build frontend
  run: |
    cd frontend
    npm ci
    npm run build

- name: Copy frontend build to backend static dir
  run: |
    rm -rf src/main/resources/static/*
    cp -r frontend/dist/* src/main/resources/static/
```

**Trade-offs:**

| Approach | Pros | Cons |
|----------|------|------|
| CI clone | No submodule complexity, clean separation | Extra PAT secret, slightly longer CI |
| Submodule | Git-native tracking, pinned commits | Submodule update ceremony, merge conflicts |

**Recommendation:** CI clone (simpler, no submodule headaches).

---

## Step 3: Remove Frontend from Backend Repo

### 3.1 Delete the frontend directory

```bash
cd executespec
rm -rf frontend/
git add -A
git commit -m "Remove frontend directory (moved to executespec-frontend repo)"
```

### 3.2 Ensure `src/main/resources/static/` is gitignored

Add to `executespec/.gitignore`:

```gitignore
# Frontend build output (generated by CI from frontend repo)
src/main/resources/static/
```

### 3.3 Keep a placeholder for local dev

Create `src/main/resources/static/.gitkeep` so Spring Boot doesn't complain:

```bash
mkdir -p src/main/resources/static
touch src/main/resources/static/.gitkeep
echo '!src/main/resources/static/.gitkeep' >> .gitignore
```

---

## Step 4: Frontend Developer Setup

### 4.1 Local development workflow

The frontend developer only needs:

```bash
# Clone frontend repo
git clone git@github.com:<your-org>/executespec-frontend.git
cd executespec-frontend
npm install

# Start dev server (proxies /api to backend)
npm run dev
# Opens http://localhost:5173
```

They need the backend running locally on `:8080` for API calls. Options:

**Option A: Run backend locally** (if they have Java 21 + PostgreSQL):
```bash
cd executespec
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run
```

**Option B: Point to dev environment** (no backend setup needed):
```typescript
// vite.config.ts — temporary local override
server: {
  proxy: {
    '/api': 'https://dev.executespec.ai',  // Point to dev server
  },
},
```

**Option C: Mock API** (fully isolated):
- Use MSW (Mock Service Worker) or a JSON mock server
- Only for UI-only work that doesn't need real API responses

### 4.2 What the frontend developer gets access to

| Has access | No access |
|------------|-----------|
| React source (`src/`) | Java backend code |
| TypeScript types/interfaces | LLM prompts |
| API client (`lib/api.ts`) | Agent logic |
| UI components | Billing/auth backend |
| Vite/Tailwind config | Database migrations |
| CI workflow (frontend only) | Infrastructure (Terraform) |
| | Secrets/API keys |

### 4.3 Shared contract: API types

The frontend defines TypeScript interfaces for API responses in `src/lib/api.ts`. These must stay in sync with backend DTOs.

**Coordination strategy:**
1. Backend developer documents API changes in the OpenAPI spec (see `docs/openapi.yaml`)
2. Frontend developer updates TypeScript types accordingly
3. Breaking changes communicated via PR description or Slack

---

## Step 5: Frontend CI (Optional)

Add a CI workflow in the frontend repo for lint/type-check/build validation:

```yaml
# executespec-frontend/.github/workflows/ci.yml
name: Frontend CI
on:
  pull_request:
    branches: [main, dev]
  push:
    branches: [main, dev]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: lts/*
          cache: npm

      - run: npm ci
      - run: npx tsc --noEmit          # Type check
      - run: npm run build             # Build check
```

---

## Step 6: Verify Everything Works

### 6.1 Checklist

- [ ] Frontend repo created with all source files
- [ ] `vite.config.ts` updated (`outDir: 'dist'`)
- [ ] Backend `deploy.yml` updated with frontend checkout + build + copy steps
- [ ] `FRONTEND_REPO_PAT` secret added to backend repo
- [ ] Frontend removed from backend repo
- [ ] `src/main/resources/static/` gitignored (with `.gitkeep`)
- [ ] Push to `dev` branch → verify dev deployment works
- [ ] Frontend developer can `npm run dev` with proxy to backend
- [ ] Push to `main` branch → verify prod deployment works

### 6.2 Rollback plan

If something goes wrong:
1. The frontend code is still in git history: `git revert <removal-commit>`
2. Revert `deploy.yml` changes
3. Everything is back to monorepo

---

## Deployment Flow (After Separation)

```
Frontend Developer                    Backend Developer
       |                                      |
  Push to dev                            Push to dev
       |                                      |
       v                                      v
executespec-frontend/dev             executespec/dev
                                              |
                                     deploy.yml triggers
                                              |
                                     +--------+--------+
                                     |                 |
                              Checkout frontend   Build backend
                              from frontend repo   (mvn package)
                                     |                 |
                                Build frontend         |
                               (npm ci && build)       |
                                     |                 |
                              Copy dist/ to            |
                              static/                  |
                                     |                 |
                                     +--------+--------+
                                              |
                                      Package into JAR
                                     (static/ embedded)
                                              |
                                      Upload to GCS
                                              |
                                      Deploy to GCE VM
                                              |
                                    Spring Boot serves
                                    static + API from
                                    single process
```

**Nothing changes in production.** Same JAR, same VM, same Caddy proxy, same systemd service. The only difference is where the frontend source code lives.
