PYTHON — complexity LOW (FastAPI/Flask) or MEDIUM (Django):
{
  "task_id": "project_setup",
  "task_layer": "BACKEND",
  "title": "Create Python project scaffold",
  "objective": "Create the project scaffold appropriate for the framework:\n\nFastAPI: Create requirements.txt with all dependencies (fastapi, uvicorn, sqlalchemy, alembic, pydantic, etc.), and main.py with the FastAPI() app instance, CORS middleware (allow_origins=['*']), and uvicorn.run bound to 0.0.0.0.\n\nFlask: Create requirements.txt, and app.py with Flask(__name__), CORS setup (flask-cors), and app.run(host='0.0.0.0').\n\nDjango: Create ONLY these 5 files:\n  1) requirements.txt with all dependencies\n  2) manage.py\n  3) <project>/settings.py (ALLOWED_HOSTS=['*'], CORS_ALLOW_ALL_ORIGINS=True, database config: host='db', user='preview', password='preview', database='preview')\n  4) <project>/urls.py\n  5) <project>/wsgi.py\n  Do NOT create Django app files (__init__.py, apps.py, admin.py, models.py, views.py, tests.py, migrations/) in this task — those belong in the first app-layer task that depends on project_setup.\n\nThe entry point file (main.py / app.py / manage.py) is MANDATORY — without it the preview cannot start.",
  "boundaries": "Only create build manifest, entry point, and base config. For Django: do NOT create app directories or __init__.py / apps.py / admin.py / models.py boilerplate — those go in the first domain task.",
  "output_format": "requirements.txt + entry point file. Django: manage.py + <project>/settings.py + <project>/urls.py + <project>/wsgi.py (5 files max).",
  "acceptance_criteria": "requirements.txt exists with all dependencies. Entry point file exists with app instance and CORS configured.",
  "depends_on": [],
  "complexity": "LOW for FastAPI/Flask, MEDIUM for Django",
  "assigned_model": "{{WORKER_MODEL}}"
}

DJANGO APP BOOTSTRAP: The first task that depends on project_setup for a Django project
should run `django-admin startapp` or manually create the app directory with __init__.py,
apps.py, models.py, admin.py, and migrations/__init__.py. This keeps project_setup ≤5 files.
