JAVA (without Spring Boot) — complexity LOW:
{
  "task_id": "project_setup",
  "task_layer": "BACKEND",
  "title": "Create Java project scaffold",
  "objective": "Create pom.xml (or build.gradle) with required dependencies and the Main class entry point. Declare the base package in pom.xml groupId (e.g., com.example.appname) and create the Main class at the matching path.",
  "boundaries": "Only create pom.xml and the Main entry-point class. No domain models, services, or utilities.",
  "output_format": "pom.xml at workspace root, Main.java under src/main/java/<base-package>/.",
  "acceptance_criteria": "pom.xml exists with required dependencies. Main.java exists with a main() method.",
  "depends_on": [],
  "complexity": "LOW",
  "assigned_model": "{{WORKER_MODEL}}"
}
