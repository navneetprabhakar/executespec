Build a Python FastAPI REST API for a simple to-do list, in a single `main.py`, runnable with `uvicorn main:app`.

Requirements:
- `GET /health` returns `{"status": "ok"}`.
- `GET /todos` returns the list of todos (start empty).
- `POST /todos` accepts `{"title": "..."}`, creates a todo with an auto-incrementing integer `id` and `done=false`, and returns it.
- `DELETE /todos/{id}` deletes the todo with that id and returns `{"deleted": true}`; returns 404 if it doesn't exist.
- Use an in-memory store (a list/dict) — no database.
- Include a `requirements.txt` with `fastapi` and `uvicorn`.

Keep it minimal and idiomatic.
