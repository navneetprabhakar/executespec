# ExecuteSpec — Comprehensive Test Matrix

> Generated: 2026-03-19 | Updated with results: 2026-03-20
> Covers: Code Generation + Preview for all project type × database combinations

---

## Actual Test Results (Phase 1 Execution — 2026-03-20)

### Code Generation

| # | Type | Goal | Status | Files | Languages | Report | Issues |
|---|------|------|--------|-------|-----------|--------|--------|
| 1 | NODE_FRONTEND | React+Tailwind dashboard | COMPLETE | 3 | JS | PASSED_WITH_FIXES | 1 |
| 2 | NODE_FRONTEND | React+Vite+TS task mgmt | COMPLETE | 32 | TS, JS | PASSED_WITH_FIXES | 4 |
| 3 | NODE_BACKEND | Express bookmark API (memory) | COMPLETE | 5 | JS | PASSED_WITH_FIXES | 2 |
| 4 | NODE_BACKEND | Express bookmark API + PG | PARTIAL | - | JS | N/A | - |
| 5 | PYTHON | Flask URL shortener | COMPLETE | 17 | Python | PASSED_WITH_FIXES | 5 |
| 6 | PYTHON | Flask bookstore + SQLAlchemy | COMPLETE | - | Python | pre-report | - |
| 7 | PYTHON | Flask blog + SQLite | COMPLETE | - | Python | pre-report | - |
| 8 | SPRING_BOOT | Todo + H2 | COMPLETE | - | Java | pre-report | - |
| 9 | SPRING_BOOT | Notes + H2 + JPA | WORKING (stuck) | - | Java | - | - |
| 10 | FULL_STACK | Task mgr (React+Express) | COMPLETE | - | JS, TS | pre-report | - |
| 11 | FULL_STACK | Notes (Express+React) | COMPLETE | - | JS | pre-report | - |

### Preview

| # | Type | Preview Status | Detected As | DB | HTTP | Issue |
|---|------|---------------|-------------|-----|------|-------|
| 1 | NODE_FRONTEND | RUNNING | NODE_FRONTEND | None | **200** ✅ | |
| 2 | NODE_FRONTEND (32 files) | TIMEOUT | NODE_FRONTEND | None | N/A ❌ | npm install too slow |
| 3 | NODE_BACKEND | RUNNING | NODE_FRONTEND | None | **200** ✅ | Misdetected type |
| 5 | PYTHON (Flask) | RUNNING→CRASH | PYTHON | None | **404** ❌ | Flask 127.0.0.1 binding |
| 8 | SPRING_BOOT + H2 | N/A | - | - | - | Run still working |

### Frontend Fixes Applied

| File | Fix |
|------|-----|
| `RunCard.tsx:49` | `truncate(run.userRequest ?? '', 120)` — null guard |
| `ActivityFeed.tsx:52` | `(run.userRequest ?? '').slice(0, 60)` — null guard |
| `DeployWizardModal.tsx:315` | `{run.userRequest ?? ''}` — null guard |

### Issues Found

1. **CRITICAL — Python Flask host binding**: Flask defaults to 127.0.0.1 in Docker. Env var fix exists but incomplete.
2. **MEDIUM — Large project build timeout**: 32-file Node project exceeds 5-min health check.
3. **MEDIUM — NODE_BACKEND misdetected as NODE_FRONTEND**: Vite devDeps trump Express deps in detection.
4. **LOW — `goal` vs `userRequest` field**: Submitting `{"goal": "..."}` silently ignored → null userRequest.
5. **LOW — Spring Boot cold build**: Maven download exceeds deadline on first run.

---

## Dimensions

### Project Types (6)
| Type | Container Port | Dockerfile Method | Weight |
|------|---------------|-------------------|--------|
| `STATIC_HTML` | 8080 (nginx) | `staticHtmlDockerfile()` | Light |
| `NODE_FRONTEND` | 5173 (Vite) | `nodeFrontendDockerfile()` | Light |
| `NODE_BACKEND` | 3000 | `nodeBackendDockerfile()` | Light |
| `PYTHON` | 8000 | `pythonDockerfile()` | Light |
| `SPRING_BOOT` | 8080 | `springBootDockerfile()` | Heavy |
| `FULL_STACK` | 5173 (frontend) | `fullStackDockerfile()` → 3 sub-variants | Heavy |

### Database Types (5, including "none")
| Type | Image | Port | Healthcheck |
|------|-------|------|-------------|
| None | — | — | — |
| `POSTGRESQL` | `postgres:16-alpine` | 5432 | `pg_isready` |
| `MYSQL` | `mysql:8-oracle` | 3306 | `mysqladmin ping` |
| `MONGODB` | `mongo:7` | 27017 | `mongosh ping` |
| `REDIS` | `redis:7-alpine` | 6379 | `redis-cli ping` |

### Full-Stack Sub-Variants (3)
| Variant | Frontend | Backend |
|---------|----------|---------|
| Node+Node | React/Vite (5173) | Express/Fastify (3000) |
| Node+Spring | React/Vite (5173) | Spring Boot (8080) |
| Node+Python | React/Vite (5173) | Flask/FastAPI (8000) |

### Embedded DB (skip sidecar)
| Type | Indicators | Expected Behavior |
|------|-----------|-------------------|
| H2 | `com.h2database`, `jdbc:h2:` | DB sidecar NOT injected |
| SQLite | `sqlite-jdbc`, `better-sqlite3`, `sqlite3` | DB sidecar NOT injected |
| HSQLDB | `hsqldb`, `jdbc:hsqldb:` | DB sidecar NOT injected |

---

## Full Test Matrix

### Legend
- **Phase**: Which testing phase this belongs to (for cost-phased execution)
- **Goal**: Natural language prompt to submit as a run
- **Code Check**: What to verify in generated code
- **Preview Check**: What to verify when preview is started
- **DB Check**: Whether DatabaseDetector should detect a DB, and which type
- **Priority**: P0 = must test, P1 = should test, P2 = nice to have

---

## Phase 1: Core Project Types (No Database)
*Cost: ~5 runs. Tests basic code gen + preview for each project type without DB complexity.*

| # | Type | Goal Prompt | Code Check | Preview Check | DB Expected | Priority |
|---|------|-------------|------------|---------------|-------------|----------|
| 1.1 | STATIC_HTML | "Create a portfolio website with hero section, projects grid, about page, and contact form using only HTML, CSS, and vanilla JavaScript" | index.html, styles.css, script.js | HTTP 200 on nginx, pages render | None | P0 |
| 1.2 | NODE_FRONTEND | "Build a React dashboard with Tailwind CSS. Include: sidebar navigation, stats cards, a data table component, and dark mode toggle" | package.json (react, tailwind), src/App.tsx, vite.config | HTTP 200 on Vite dev server (:5173) | None | P0 |
| 1.3 | NODE_BACKEND | "Create an Express.js REST API for a bookmark manager. Include: CRUD endpoints for bookmarks with in-memory storage, request validation with Joi, error middleware, and Swagger docs" | package.json (express, joi, swagger-ui-express), routes/, middleware/ | HTTP 200 on Express (:3000), GET /api-docs works | None | P0 |
| 1.4 | PYTHON | "Build a FastAPI service for a URL shortener. Include: shorten endpoint, redirect endpoint, click tracking, and in-memory storage using a dict" | requirements.txt (fastapi, uvicorn), main.py or app.py | HTTP 200 on uvicorn (:8000), /docs renders | None | P0 |
| 1.5 | SPRING_BOOT | "Create a Spring Boot REST API for a note-taking app. Use H2 in-memory database, JPA, and include CRUD endpoints with pagination" | pom.xml (spring-boot-starter-data-jpa, h2), entity/, controller/ | HTTP 200 on Tomcat (:8080), Swagger or /api/notes works | None (H2 = embedded, skip sidecar) | P0 |

### Phase 1 Validation Checklist
- [ ] 1.1: nginx serves static files, SPA routing works (try_files)
- [ ] 1.2: Vite dev server starts, HMR websocket connects, Tailwind classes work
- [ ] 1.3: Express starts, API routes respond, Swagger UI renders
- [ ] 1.4: Uvicorn starts, FastAPI /docs renders, endpoints respond
- [ ] 1.5: Spring Boot starts (H2 embedded), no DB sidecar injected, JPA tables auto-created

---

## Phase 2: Database Combinations — PostgreSQL
*Cost: ~4 runs. PostgreSQL is the default fallback and most common DB.*

| # | Type | Goal Prompt | Code Check | Preview Check | DB Expected | Priority |
|---|------|-------------|------------|---------------|-------------|----------|
| 2.1 | NODE_BACKEND + PG | "Create an Express.js REST API for a task manager with PostgreSQL. Use Prisma ORM, include user and task models with relations, CRUD endpoints, and database migrations" | package.json (prisma, @prisma/client, express), prisma/schema.prisma (provider=postgresql) | HTTP 200, DB connected, POST /tasks works | POSTGRESQL | P0 |
| 2.2 | SPRING_BOOT + PG | "Build a Spring Boot REST API for an employee directory with PostgreSQL. Include: Employee entity with JPA, department relation, CRUD endpoints with pagination, and Flyway migrations" | pom.xml (postgresql, spring-boot-starter-data-jpa, flyway), entity/, repository/ | HTTP 200, DB connected, GET /api/employees works | POSTGRESQL | P0 |
| 2.3 | PYTHON + PG | "Build a Django REST API for a blog platform with PostgreSQL. Include: Post and Comment models, serializers, viewsets, and CORS headers" | requirements.txt (django, djangorestframework, psycopg2), models.py, views.py | HTTP 200, Django admin renders, API endpoints work | POSTGRESQL | P1 |
| 2.4 | NODE_FRONTEND + PG | "Build a Next.js app for a product catalog with PostgreSQL. Use Prisma for data access, include product listing page, search, and category filtering" | package.json (next, prisma), prisma/schema.prisma | HTTP 200 on Vite/Next dev, DB query works | POSTGRESQL | P1 |

### Phase 2 Validation Checklist
- [ ] 2.1: Prisma schema detected → PG sidecar injected, `DATABASE_URL` env var set, migrations run
- [ ] 2.2: Spring datasource auto-configured via `SPRING_DATASOURCE_URL`, Flyway runs, JPA works
- [ ] 2.3: Django `DATABASES` configured, `python manage.py migrate` runs, admin renders
- [ ] 2.4: Prisma connects to PG, SSR data fetching works

---

## Phase 3: Database Combinations — MySQL, MongoDB, Redis
*Cost: ~4 runs. Tests non-PG database detection and connection injection.*

| # | Type | Goal Prompt | Code Check | Preview Check | DB Expected | Priority |
|---|------|-------------|------------|---------------|-------------|----------|
| 3.1 | SPRING_BOOT + MySQL | "Create a Spring Boot REST API for an inventory system with MySQL. Include: Product and Category entities with JPA, CRUD endpoints, and stock tracking" | pom.xml (mysql-connector), application.yml (mysql) | HTTP 200, MySQL sidecar healthy, CRUD works | MYSQL | P1 |
| 3.2 | NODE_BACKEND + MongoDB | "Build an Express.js API for a recipe sharing app with MongoDB. Use Mongoose, include Recipe and User models, text search, and image URL storage" | package.json (mongoose, express), models/ with Mongoose schemas | HTTP 200, Mongo connected, POST /recipes works | MONGODB | P1 |
| 3.3 | PYTHON + MongoDB | "Create a FastAPI service for a chat application with MongoDB. Use Motor (async driver), include message and room models, and WebSocket support" | requirements.txt (motor, fastapi), models.py | HTTP 200, Mongo sidecar running, API responds | MONGODB | P2 |
| 3.4 | NODE_BACKEND + Redis | "Build an Express.js API for a rate limiter service with Redis. Include: configurable rate limiting middleware, token bucket algorithm, and rate limit headers" | package.json (ioredis or redis), middleware/rateLimiter.js | HTTP 200, Redis connected, rate limiting works | REDIS | P2 |

### Phase 3 Validation Checklist
- [ ] 3.1: MySQL sidecar starts, `SPRING_DATASOURCE_URL` has jdbc:mysql://, JPA tables created
- [ ] 3.2: MongoDB sidecar starts, `MONGO_URI` injected, Mongoose models connect
- [ ] 3.3: Motor async driver connects, `MONGO_URI` or `MONGODB_URI` used
- [ ] 3.4: Redis sidecar starts, `REDIS_URL` injected, rate limiter middleware functional

---

## Phase 4: Full-Stack Combinations
*Cost: ~3 runs. Tests multi-service preview (frontend + backend in same compose).*

| # | Type | Goal Prompt | Code Check | Preview Check | DB Expected | Priority |
|---|------|-------------|------------|---------------|-------------|----------|
| 4.1 | FULL_STACK (Node+Node) + PG | "Create a full-stack expense tracker. Frontend: React with Tailwind, charts. Backend: Express.js with Prisma and PostgreSQL. Include: expense CRUD, category management, monthly summary" | frontend/ (React), backend/ (Express+Prisma), prisma/schema.prisma | Frontend HTTP 200 (:5173), Backend API responds, PG sidecar running | POSTGRESQL | P0 |
| 4.2 | FULL_STACK (Node+Spring) | "Build a full-stack project management tool. Frontend: React+Vite. Backend: Spring Boot with H2. Include: project CRUD, task boards, and team assignment" | frontend/ (React), backend/ (Spring Boot), pom.xml (h2) | Frontend renders, Spring Boot starts, H2 embedded (no sidecar) | None (H2) | P1 |
| 4.3 | FULL_STACK (Node+Python) + PG | "Create a full-stack job board. Frontend: React with Tailwind. Backend: FastAPI with SQLAlchemy and PostgreSQL. Include: job listings CRUD, company profiles, and search" | frontend/ (React), backend/ (FastAPI, sqlalchemy), requirements.txt | Frontend renders, FastAPI docs at /docs, PG sidecar running | POSTGRESQL | P1 |

### Phase 4 Validation Checklist
- [ ] 4.1: Two containers (frontend + backend) + PG sidecar = 3 services total, API proxy works
- [ ] 4.2: Two containers, H2 embedded detected → no sidecar, Spring Boot starts, frontend fetches from API
- [ ] 4.3: Two containers + PG sidecar, SQLAlchemy connected, frontend fetches job listings

---

## Phase 5: Edge Cases & Embedded DB
*Cost: ~4 runs. Tests detection edge cases and fallback behavior.*

| # | Type | Goal Prompt | Code Check | Preview Check | DB Expected | Priority |
|---|------|-------------|------------|---------------|-------------|----------|
| 5.1 | SPRING_BOOT + SQLite | "Create a Spring Boot REST API for a simple todo app using SQLite. Include: Todo entity, CRUD endpoints, and application.properties with sqlite config" | pom.xml (sqlite-jdbc), application.properties (jdbc:sqlite:) | HTTP 200, SQLite file created, no sidecar injected | None (embedded) | P1 |
| 5.2 | NODE_BACKEND + SQLite | "Build an Express.js API for a contacts app using better-sqlite3. Include: contact CRUD, search, and export to CSV" | package.json (better-sqlite3), db.js with SQLite setup | HTTP 200, SQLite works, no sidecar | None (embedded) | P1 |
| 5.3 | PYTHON + Flask (no DB) | "Create a Flask API for a unit converter. Include: length, weight, temperature conversions with validation and error handling" | requirements.txt (flask), app.py or main.py | HTTP 200 on Flask (:8000), API responds | None | P1 |
| 5.4 | PYTHON + Django + PG | "Build a Django REST API for a library management system with PostgreSQL. Include: Book, Author, Borrower models, serializers, and admin panel" | requirements.txt (django, psycopg2), manage.py, models.py | HTTP 200, Django admin, PG sidecar | POSTGRESQL | P2 |

### Phase 5 Validation Checklist
- [ ] 5.1: `usesEmbeddedDb()` returns true for sqlite-jdbc → no sidecar
- [ ] 5.2: `usesEmbeddedDb()` returns true for better-sqlite3 → no sidecar
- [ ] 5.3: Flask detected via `hasDependency()`, `findPythonEntryPoint()` finds correct file
- [ ] 5.4: Django with `psycopg2` → PG detected, Django manages its own migrations

---

## Phase 6: Build System Variants
*Cost: ~2 runs. Tests Gradle detection and monorepo patterns.*

| # | Type | Goal Prompt | Code Check | Preview Check | DB Expected | Priority |
|---|------|-------------|------------|---------------|-------------|----------|
| 6.1 | SPRING_BOOT (Gradle) + PG | "Create a Spring Boot REST API using Gradle (Kotlin DSL) for a user management system with PostgreSQL. Include: User entity, authentication with Spring Security, and CRUD endpoints" | build.gradle.kts (spring-boot, postgresql), entity/, controller/ | HTTP 200, Gradle wrapper runs, PG connected | POSTGRESQL | P1 |
| 6.2 | NODE_BACKEND (monorepo) | "Create a Node.js monorepo with npm workspaces. Include: shared utils package, REST API package using Express, and a CLI tool package" | package.json (workspaces), packages/ dir structure | HTTP 200 if API package runs, workspace deps resolved | None | P2 |

### Phase 6 Validation Checklist
- [ ] 6.1: Gradle wrapper detected, `springBootDockerfile()` uses `./gradlew`, PG sidecar injected
- [ ] 6.2: Monorepo structure detected, correct entry point found

---

## Summary Matrix (All Combinations)

| | No DB | PostgreSQL | MySQL | MongoDB | Redis | H2/SQLite (embedded) |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| **STATIC_HTML** | 1.1 ✅ | — | — | — | — | — |
| **NODE_FRONTEND** | 1.2 ✅ | 2.4 | — | — | — | — |
| **NODE_BACKEND** | 1.3 ✅ | 2.1 ✅ | — | 3.2 | 3.4 | 5.2 |
| **PYTHON** | 1.4 ✅ / 5.3 | 2.3 / 5.4 | — | 3.3 | — | — |
| **SPRING_BOOT** | 1.5 ✅ | 2.2 ✅ | 3.1 | — | — | 5.1 |
| **FULL_STACK** | 4.2 | 4.1 ✅ / 4.3 | — | — | — | — |

**Legend:** ✅ = P0 (must test first), numbered = test ID from phases above

### Untested Combinations (Low Priority — P2/P3)
These are valid but less common in real-world usage:
- STATIC_HTML + any DB (unlikely — static sites don't use databases)
- NODE_FRONTEND + MySQL/MongoDB/Redis (frontends rarely have direct DB access)
- PYTHON + MySQL (less common than PG in Python ecosystem)
- PYTHON + Redis (usually a caching add-on, not primary DB)
- SPRING_BOOT + MongoDB (valid but niche for Spring Boot)
- SPRING_BOOT + Redis (usually a cache, not primary DB)
- FULL_STACK + MySQL/MongoDB/Redis (valid, but PG covers the compose wiring test)

---

## Execution Plan

### Recommended Order (Cost-Optimized)

| Phase | Runs | Est. Cost | Covers | Blocking? |
|-------|------|-----------|--------|-----------|
| **Phase 1** | 5 | ~$0.50 | All 6 project types, basic preview | YES — foundational |
| **Phase 2** | 4 | ~$0.40 | PostgreSQL (most common DB) | YES — DB sidecar critical path |
| **Phase 3** | 4 | ~$0.40 | MySQL, MongoDB, Redis | No — only if Phase 2 passes |
| **Phase 4** | 3 | ~$0.45 | Full-stack multi-service compose | No — highest complexity |
| **Phase 5** | 4 | ~$0.40 | Edge cases, embedded DB detection | No — regression safety |
| **Phase 6** | 2 | ~$0.20 | Build system variants | No — low priority |
| **Total** | **22** | **~$2.35** | **22 unique combinations** | |

*Cost estimates based on Haiku worker model at ~$0.10/run average.*

### Per-Phase Exit Criteria
- **Phase 1 PASS**: All 5 project types generate valid code AND preview starts with HTTP 200
- **Phase 2 PASS**: PG sidecar starts, healthcheck passes, app connects via injected env vars
- **Phase 3 PASS**: MySQL/Mongo/Redis sidecars start, connection strings correct
- **Phase 4 PASS**: Multi-service compose works, frontend can reach backend API
- **Phase 5 PASS**: Embedded DB detection prevents sidecar injection, Flask/Django entry points found
- **Phase 6 PASS**: Gradle build works, monorepo workspace resolution works

---

## Known Issues to Watch For

| Issue | Affected | Status | Workaround |
|-------|----------|--------|------------|
| Spring Boot cold Maven build timeout | SPRING_BOOT, FULL_STACK (Node+Spring) | **OPEN** | Pre-cache .m2 or increase timeout to 10min |
| HTTP/2 health check failure | ALL project types | **FIXED** | `HttpClient.Version.HTTP_1_1` forced |
| nginx `$uri` escaping | STATIC_HTML | **FIXED** | `echo` instead of `printf` |
| Vite not in deps | NODE_FRONTEND, FULL_STACK | **FIXED** | Auto-install vite in Dockerfile |
| Flask host binding | PYTHON (Flask) | **FIXED** | All Flask env vars set |
| Python entry point not found | PYTHON | **FIXED** | `findPythonEntryPoint()` scans common names |
| Embedded DB gets sidecar | SPRING_BOOT (H2), NODE (SQLite) | **FIXED** | `usesEmbeddedDb()` check |
| Port conflict multi-user | ALL | **FIXED** | Ephemeral port via `ServerSocket(0)` |
