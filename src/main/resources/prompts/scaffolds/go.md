GO — complexity LOW:
{
  "task_id": "project_setup",
  "task_layer": "BACKEND",
  "title": "Create Go project scaffold",
  "objective": "Create go.mod with the module path (e.g., module github.com/user/appname) and Go version. Create cmd/main.go as the entry point with the HTTP server bound to 0.0.0.0. All subsequent tasks must import using the module path declared in go.mod — never use relative imports.",
  "boundaries": "Only create go.mod and cmd/main.go. No handler files, service files, or database code.",
  "output_format": "go.mod at workspace root, cmd/main.go as server entry point.",
  "acceptance_criteria": "go.mod exists with module path and go version. cmd/main.go exists with main() function and server bound to 0.0.0.0.",
  "depends_on": [],
  "complexity": "LOW",
  "assigned_model": "{{WORKER_MODEL}}"
}
