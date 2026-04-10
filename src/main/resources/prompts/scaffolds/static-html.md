STATIC_HTML — complexity LOW (single task for single pages, split for multi-section pages):

SINGLE-PAGE RULE: If the user requests a single HTML page (landing page, calculator,
portfolio, single-page app without framework), use a SINGLE task:

{
  "task_id": "project_setup",
  "task_layer": "FRONTEND",
  "title": "Create static HTML page",
  "objective": "Create the complete index.html with all inline or referenced CSS and JS. Include ALL files referenced in index.html (style.css, script.js) in this same task — do NOT split HTML, CSS, and JS across separate tasks. If the page has ≤3 content sections and total code is <200 lines, create the complete page in this one task.",
  "boundaries": "Only this page's files. No build tools, no npm, no frameworks.",
  "output_format": "index.html (and style.css, script.js if externalized) at workspace root.",
  "acceptance_criteria": "index.html exists and is a complete, valid HTML document. All referenced CSS/JS files exist.",
  "depends_on": [],
  "complexity": "LOW",
  "assigned_model": "{{WORKER_MODEL}}"
}

MULTI-SECTION RULE: For landing pages or sites with 4+ content sections (hero, about,
features, pricing, testimonials, contact, footer), SPLIT by section using shared_files:

  Task 1 (LOW): "Create page scaffold + hero section"
    owned_files: ["index.html", "style.css", "script.js"]
    Creates full page skeleton: head, nav, hero, empty placeholders for all other sections.

  Task 2 (LOW): "Add About and Features sections"
    shared_files: ["index.html", "style.css"]
    depends_on: ["project_setup"]

  Task 3 (LOW): "Add Pricing and Testimonials sections"
    shared_files: ["index.html", "style.css"]
    depends_on: ["task_2"]

  Task 4 (LOW): "Add Contact form and Footer"
    shared_files: ["index.html", "style.css", "script.js"]
    depends_on: ["task_3"]

DETECTION: Apply multi-section split whenever the spec mentions "landing page",
"portfolio", "company website", multiple named sections, or "single page with multiple areas".
