RUST — complexity LOW:
{
  "task_id": "project_setup",
  "task_layer": "BACKEND",
  "title": "Create Rust project scaffold",
  "objective": "Create Cargo.toml with the crate name, edition (2021), and all required dependencies (actix-web / axum / etc.). Create src/main.rs as the entry point with the HTTP server bound to 0.0.0.0. Declare the module structure in Cargo.toml. All subsequent tasks must reference types using crate::module::Type — never mix super:: and crate:: imports.",
  "boundaries": "Only create Cargo.toml and src/main.rs. No handler files, models, or database code.",
  "output_format": "Cargo.toml at workspace root, src/main.rs as server entry point.",
  "acceptance_criteria": "Cargo.toml exists with crate name and all dependencies. src/main.rs exists with main() function and server bound to 0.0.0.0.",
  "depends_on": [],
  "complexity": "LOW",
  "assigned_model": "{{WORKER_MODEL}}"
}
