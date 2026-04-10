FULL-STACK DIRECTORY STRUCTURE — MANDATORY:
When a project has BOTH a compiled backend (Java/Spring Boot, Go, Rust) AND a frontend:
- Java: frontend files under frontend/ (e.g., frontend/package.json, frontend/src/App.jsx)
- Go / Rust: frontend files under frontend/ or client/
NEVER place frontend files at project root src/ — collides with backend's src/main/java/.
Node.js (Express + React single-repo): both can share root or use src/ — no conflict.
