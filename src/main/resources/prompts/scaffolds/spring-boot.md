SPRING_BOOT — complexity MEDIUM (4 files, ~130 lines):
{
  "task_id": "project_setup",
  "task_layer": "BACKEND",
  "title": "Create Spring Boot project scaffold",
  "objective": "Write the project scaffold so every other task has a compilable base. Create IN ORDER: 1) pom.xml with ALL dependencies (spring-boot-starter-web, spring-boot-starter-data-jpa, h2/postgresql driver, lombok, etc.). 2) @SpringBootApplication main class (e.g. src/main/java/com/example/XxxApplication.java) — MANDATORY entry point. 3) src/main/resources/application.yml with server port, datasource config (host 'db', user 'preview', database 'preview'), JPA settings. 4) @Configuration CORS class with allowedOriginPatterns(\"*\") — never hardcode localhost URLs.",
  "boundaries": "Only create pom.xml, Application main class, application.yml, and CORS config. No controllers, services, models, or UI.",
  "output_format": "pom.xml at workspace root, Application.java + CORS config under src/main/java/..., application.yml under src/main/resources/.",
  "acceptance_criteria": "pom.xml exists with all dependencies. @SpringBootApplication main class exists. application.yml exists. CORS config class exists.",
  "depends_on": [],
  "complexity": "MEDIUM",
  "assigned_model": "{{WORKER_MODEL}}"
}
