NODE_BACKEND — complexity LOW:
{
  "task_id": "project_setup",
  "task_layer": "BACKEND",
  "title": "Create Node.js backend scaffold",
  "objective": "Create package.json with all required dependencies (express, cors, dotenv, etc.). Declare whether the project uses ESM ('type': 'module') or CommonJS — all subsequent tasks MUST use the same module system consistently.",
  "boundaries": "Only create package.json (and optionally .env.example). No server entry point, routes, or middleware.",
  "output_format": "package.json at workspace root.",
  "acceptance_criteria": "package.json exists with all server dependencies.",
  "depends_on": [],
  "complexity": "LOW",
  "assigned_model": "{{WORKER_MODEL}}"
}
