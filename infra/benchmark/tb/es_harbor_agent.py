"""
ExecuteSpec Harbor agent — runs ExecuteSpec's OWN worker on Terminal-Bench 2.0.

Model: "remote brain / local hands". The ExecuteSpec server (localhost) runs the worker loop (the
brain); every tool call is emitted as a TOOL_CALL SSE event; THIS adapter executes it inside the
Harbor task container (the hands) via `environment.exec` / file ops, and POSTs the result back. The
worker is provider-agnostic — DeepSeek first (cheap), then Anthropic/OpenAI/Gemini via env config.

Validated core: this is the async port of tb_pump_test.py (the keystone-verified pump), with the
tool-execution backend swapped from local subprocess/fs to the Harbor `environment`.

Invoke (from ~/tbench, with the backend running on localhost:8080):
    ES_PROVIDER=deepseek ES_MODEL=deepseek-v4-flash-native \
    ES_ADMIN_EMAIL=... ES_ADMIN_PW=... \
    harbor run --dataset terminal-bench@2.0 \
        --agent-import-path es_harbor_agent:ExecuteSpecAgent --model deepseek/deepseek-v4-flash

Config (env vars):
    ES_BASE          ExecuteSpec server base (default http://localhost:8080)
    ES_PROVIDER      ExecuteSpec provider id (deepseek|anthropic|openai|gemini)  [required]
    ES_MODEL         ExecuteSpec model name (e.g. deepseek-v4-flash-native)      [required]
    ES_MAX_ITERS     worker iteration cap (default 50)
    ES_PAT           es_pat_... bearer (preferred); else ES_ADMIN_EMAIL/ES_ADMIN_PW login
    ES_TOOL_TIMEOUT  per-tool command timeout sec in the container (default 300)
    ES_CANCEL_ON_ABORT cancel the ExecuteSpec run if Harbor aborts the adapter (default true)
"""
import asyncio
import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shlex
import uuid

import httpx

from harbor.agents.base import BaseAgent
from harbor.environments.base import BaseEnvironment
from harbor.models.agent.context import AgentContext

READ_LIMIT, SHELL_LIMIT, DIFF_LIMIT = 8000, 6000, 5000
TRAJECTORY_LIMIT = 12000
TOOL_OUTPUT_DIR = "/tmp/es-tool-output"


def _trunc(s: str, n: int) -> str:
    return s if len(s) <= n else s[:n] + f"\n…[truncated {len(s) - n} chars]"


def _trunc_head_tail(s: str, n: int) -> str:
    if len(s) <= n:
        return s
    head = max(1, n // 2)
    tail = max(1, n - head)
    omitted = len(s) - head - tail
    return s[:head] + f"\n…[truncated middle {omitted} chars]\n" + s[-tail:]


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _ok(content: str) -> dict:
    return {"ok": True, "content": content}


def _err(kind: str, msg: str) -> dict:
    return {"ok": False, "kind": kind, "message": msg}


class ExecuteSpecAgent(BaseAgent):
    """Harbor BaseAgent that drives the ExecuteSpec worker with tools executed in the TB container."""

    SUPPORTS_ATIF = True
    SUPPORTS_WINDOWS = False

    @staticmethod
    def name() -> str:
        return "executespec"

    def version(self) -> str:
        return "0.1.0"

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.base = os.environ.get("ES_BASE", "http://localhost:8080").rstrip("/")
        self.provider = os.environ.get("ES_PROVIDER")
        self.model = os.environ.get("ES_MODEL")
        self.max_iters = int(os.environ.get("ES_MAX_ITERS", "50"))
        self.pat = os.environ.get("ES_PAT")
        self.admin_email = os.environ.get("ES_ADMIN_EMAIL")
        self.admin_pw = os.environ.get("ES_ADMIN_PW")
        self.tool_timeout = int(os.environ.get("ES_TOOL_TIMEOUT", "300"))
        self.cancel_on_abort = os.environ.get("ES_CANCEL_ON_ABORT", "true").lower() != "false"
        self._trajectory_steps = []
        if not self.provider or not self.model:
            raise ValueError("ES_PROVIDER and ES_MODEL env vars are required")

    async def setup(self, environment: BaseEnvironment) -> None:
        # Brain runs on the host; nothing to install in the container. base64/cat/grep are coreutils.
        return

    async def run(self, instruction: str, environment: BaseEnvironment, context: AgentContext) -> None:
        self._trajectory_steps = [
            {
                "step_id": 1,
                "timestamp": _utc_now(),
                "source": "user",
                "message": instruction,
            }
        ]
        run_id = None
        tools = 0
        final = None
        async with httpx.AsyncClient(timeout=httpx.Timeout(60.0, read=None)) as client:
            try:
                token = await self._auth(client)
                started = await self._post(
                    client, "/api/v2/test/terminal-bench/start", token,
                    {"instruction": instruction, "provider": self.provider,
                     "model": self.model, "maxIterations": self.max_iters},
                )
                run_id, sse_token = started["runId"], started["sseToken"]
                self.logger.info(f"[es] run={run_id} provider={self.provider} model={self.model}")

                url = f"{self.base}/api/runs/{run_id}/events?token={sse_token}"
                headers = {"Accept": "text/event-stream", "Last-Event-ID": "0"}
                async with client.stream("GET", url, headers=headers, timeout=None) as resp:
                    buf = []
                    async for line in resp.aiter_lines():
                        if line == "":  # frame boundary
                            ev = self._parse_frame(buf)
                            buf = []
                            if not ev:
                                continue
                            etype = ev.get("type")
                            if etype == "TOOL_CALL":
                                frame = json.loads(ev["payload"])
                                call_id, tname = frame["callId"], frame["name"]
                                targs = frame.get("args", {})
                                if isinstance(targs, str):
                                    try:
                                        targs = json.loads(targs)
                                    except Exception:
                                        targs = {}
                                tools += 1
                                step = self._add_tool_call_step(call_id, tname, targs)
                                env_result = await self._exec_tool(tname, targs, environment)
                                self._attach_tool_result(step, call_id, env_result)
                                await self._post(client, f"/api/runs/{run_id}/tool-results", token,
                                                 {"callId": call_id, "result": env_result})
                            elif etype == "COMPLETE":
                                final = ev.get("payload")
                                break
                        else:
                            buf.append(line)

                self.logger.info(f"[es] run={run_id} terminal={final} tools={tools}")
                # populate context cost/tokens from the run cost snapshot
                try:
                    cost = await self._get(client, f"/api/runs/{run_id}/cost", token)
                    context.n_input_tokens = cost.get("inputTokens") or cost.get("totalInputTokens")
                    context.n_output_tokens = cost.get("outputTokens") or cost.get("totalOutputTokens")
                    context.cost_usd = cost.get("costUsd") or cost.get("actualCostUsd")
                except Exception as e:
                    self.logger.warning(f"[es] cost fetch failed: {e}")

                context.metadata = {
                    "es_run_id": run_id,
                    "es_terminal": final,
                    "tools": tools,
                    "atif_trajectory": "trajectory.json",
                }
                self._add_final_step(run_id, final, tools, context)
                self._write_trajectory(run_id or str(uuid.uuid4()), context)
            except asyncio.CancelledError as e:
                self._add_error_step(run_id, e, tools)
                await self._cancel_run_on_abort(client, token if "token" in locals() else None, run_id, final)
                self._write_trajectory(run_id or str(uuid.uuid4()), context)
                raise
            except Exception as e:
                self._add_error_step(run_id, e, tools)
                await self._cancel_run_on_abort(client, token if "token" in locals() else None, run_id, final)
                self._write_trajectory(run_id or str(uuid.uuid4()), context)
                raise

    # ---------------- tool execution in the TB container ----------------
    async def _exec_tool(self, name: str, args: dict, env: BaseEnvironment) -> dict:
        try:
            name, args = self._normalize_tool_call(name, args)
            if name in ("shell", "buildProject", "runTests"):
                cmd = args.get("command")
                if not cmd:
                    return _err("INVALID_ARGS", f"{name}: no command")
                r = await env.exec(cmd, timeout_sec=self.tool_timeout)
                out = (r.stdout or "") + (r.stderr or "")
                body = await self._bounded_shell_output(env, name, out)
                if r.return_code != 0:
                    return _err(
                        "COMMAND_FAILED",
                        f"{name} exited {r.return_code}\n{body}".strip(),
                    )
                return _ok(body)
            if name == "readFile":
                return await self._read(env, args)
            if name == "writeFile":
                return await self._write(env, args["path"], args.get("content", ""))
            if name == "editFile":
                path = args["path"]
                r = await env.exec(f"cat -- {shlex.quote(path)}", timeout_sec=30)
                if r.return_code != 0:
                    return _err("NOT_FOUND", f"cannot read {path}")
                txt = r.stdout or ""
                old, new = args["oldString"], args["newString"]
                if old not in txt:
                    return _err("INVALID_ARGS", f"oldString not found in {path}")
                cnt = txt.count(old)
                if not args.get("replaceAll") and cnt > 1:
                    return _err("INVALID_ARGS", f"oldString not unique in {path} ({cnt}); pass replaceAll")
                updated = txt.replace(old, new) if args.get("replaceAll") else txt.replace(old, new, 1)
                w = await self._write(env, path, updated)
                return _ok(f"edited {path}") if w["ok"] else w
            if name == "listFiles":
                path = args.get("path", ".")
                r = await env.exec(f"ls -la {shlex.quote(path)}", timeout_sec=30)
                return _ok(_trunc(r.stdout or "(empty)", READ_LIMIT))
            if name == "searchFiles":
                q = args.get("query") or args.get("pattern") or args.get("regex")
                if not q:
                    return _err("INVALID_ARGS", "'query' required")
                path = args.get("path", ".")
                r = await env.exec(
                    f"grep -rnI -- {shlex.quote(q)} {shlex.quote(path)} 2>/dev/null | head -100",
                    timeout_sec=60)
                return _ok(_trunc(r.stdout or "no matches", READ_LIMIT))
            if name == "gitStatus":
                r = await env.exec("git status --porcelain=v1 -b 2>/dev/null", timeout_sec=30)
                return _ok(_trunc((r.stdout or "").strip() or "(clean)", READ_LIMIT))
            if name == "gitDiff":
                r = await env.exec("git diff 2>/dev/null", timeout_sec=30)
                return _ok(_trunc((r.stdout or "").strip() or "(no changes)", DIFF_LIMIT))
            if name == "completeTask":
                return _ok(args.get("summary", "task marked complete"))
            return _err("INVALID_ARGS", f"unknown tool: {name}")
        except Exception as e:
            return _err("INTERNAL", f"{type(e).__name__}: {e}")

    def _normalize_tool_call(self, name: str, args: dict) -> tuple[str, dict]:
        """Accept common Claude-Code-style aliases advertised by server-side worker bindings."""
        aliases = {
            "bash": "shell",
            "executeCommand": "shell",
            "runCommand": "shell",
            "terminalCommand": "shell",
            "read_file": "readFile",
            "grep": "searchFiles",
            "glob": "listFiles",
        }
        normalized = aliases.get(name, name)
        if normalized == "shell" and "command" not in args:
            command = args.get("cmd") or args.get("script") or args.get("input")
            if command:
                args = {**args, "command": command}
        if normalized == "readFile" and "path" not in args and args.get("file"):
            args = {**args, "path": args["file"]}
        if normalized == "searchFiles" and "query" not in args:
            query = args.get("pattern") or args.get("regex")
            if query:
                args = {**args, "query": query}
        return normalized, args

    async def _bounded_shell_output(self, env: BaseEnvironment, tool_name: str, out: str) -> str:
        if len(out) <= SHELL_LIMIT:
            return out
        spill = f"{TOOL_OUTPUT_DIR}/{tool_name}-{uuid.uuid4().hex}.txt"
        written = await self._write(env, spill, out)
        note = (
            f"\n[full output saved at {spill}; use readFile with startLine/endLine to inspect it]"
            if written.get("ok")
            else "\n[full output spill failed]"
        )
        return _trunc_head_tail(out, SHELL_LIMIT) + note

    async def _read(self, env: BaseEnvironment, args: dict) -> dict:
        path = args["path"]
        start = args.get("startLine")
        end = args.get("endLine")
        try:
            start = int(start) if start is not None else None
            end = int(end) if end is not None else None
        except Exception:
            return _err("INVALID_ARGS", "startLine/endLine must be integers")
        if start is not None and start < 1:
            return _err("INVALID_ARGS", "startLine must be >= 1")
        if end is not None and end < 1:
            return _err("INVALID_ARGS", "endLine must be >= 1")
        if start is not None and end is not None and start > end:
            return _err("INVALID_ARGS", f"startLine ({start}) > endLine ({end})")

        quoted = shlex.quote(path)
        if start is not None or end is not None:
            lo = start or 1
            hi = str(end) if end is not None else "$"
            cmd = f"sed -n {shlex.quote(f'{lo},{hi}p')} {quoted}"
            r = await env.exec(cmd, timeout_sec=30)
            if r.return_code != 0:
                return _err("NOT_FOUND", f"cannot read {path}: {(r.stderr or '').strip()}")
            return _ok(r.stdout or "")

        r = await env.exec(f"cat -- {quoted}", timeout_sec=30)
        if r.return_code != 0:
            return _err("NOT_FOUND", f"cannot read {path}: {(r.stderr or '').strip()}")
        content = r.stdout or ""
        if len(content) <= READ_LIMIT:
            return _ok(content)
        lc = await env.exec(f"wc -l < {quoted}", timeout_sec=30)
        line_count = (lc.stdout or "").strip() if lc.return_code == 0 else "unknown"
        return _ok(
            _trunc(content, READ_LIMIT)
            + f"\n[file truncated at {READ_LIMIT} chars; total lines: {line_count}; "
            + "use startLine/endLine to read later ranges]"
        )

    async def _write(self, env: BaseEnvironment, path: str, content: str) -> dict:
        """Write arbitrary content to a container path via base64 (newline/quote-safe)."""
        b64 = base64.b64encode(content.encode()).decode()
        cmd = (
            f"mkdir -p \"$(dirname {shlex.quote(path)})\" 2>/dev/null; "
            f"printf %s {shlex.quote(b64)} | base64 -d > {shlex.quote(path)}"
        )
        r = await env.exec(cmd, timeout_sec=60)
        if r.return_code != 0:
            return _err("INTERNAL", f"write {path} failed: {(r.stderr or '').strip()}")
        return _ok(f"wrote {len(content.encode())} bytes to {path}")

    # ---------------- ATIF trajectory helpers ----------------
    def _add_tool_call_step(self, call_id: str, name: str, args: dict) -> dict:
        step = {
            "step_id": len(self._trajectory_steps) + 1,
            "timestamp": _utc_now(),
            "source": "agent",
            "model_name": self.model,
            "message": f"Tool call: {name}",
            "tool_calls": [
                {
                    "tool_call_id": call_id,
                    "function_name": name,
                    "arguments": args,
                    "extra": {"source": "ExecuteSpec SSE TOOL_CALL"},
                }
            ],
        }
        self._trajectory_steps.append(step)
        return step

    def _attach_tool_result(self, step: dict, call_id: str, result: dict) -> None:
        content = result.get("content") or result.get("message") or json.dumps(result, default=str)
        step["observation"] = {
            "results": [
                {
                    "source_call_id": call_id,
                    "content": _trunc(str(content), TRAJECTORY_LIMIT),
                    "extra": {"is_error": not bool(result.get("ok"))},
                }
            ]
        }

    def _add_final_step(self, run_id, final, tools: int, context: AgentContext) -> None:
        self._trajectory_steps.append(
            {
                "step_id": len(self._trajectory_steps) + 1,
                "timestamp": _utc_now(),
                "source": "agent",
                "model_name": self.model,
                "message": "ExecuteSpec Terminal-Bench run completed.",
                "observation": {
                    "results": [
                        {
                            "content": json.dumps(
                                {
                                    "es_run_id": run_id,
                                    "terminal": final,
                                    "tools": tools,
                                    "input_tokens": context.n_input_tokens,
                                    "output_tokens": context.n_output_tokens,
                                    "cost_usd": context.cost_usd,
                                },
                                default=str,
                            ),
                            "extra": {"is_error": False},
                        }
                    ]
                },
            }
        )

    def _add_error_step(self, run_id, error: Exception, tools: int) -> None:
        self._trajectory_steps.append(
            {
                "step_id": len(self._trajectory_steps) + 1,
                "timestamp": _utc_now(),
                "source": "agent",
                "model_name": self.model,
                "message": "ExecuteSpec Terminal-Bench run failed.",
                "observation": {
                    "results": [
                        {
                            "content": json.dumps(
                                {
                                    "es_run_id": run_id,
                                    "tools": tools,
                                    "error_type": type(error).__name__,
                                    "error": str(error),
                                },
                                default=str,
                            ),
                            "extra": {"is_error": True},
                        }
                    ]
                },
            }
        )

    def _write_trajectory(self, run_id: str, context: AgentContext) -> None:
        session_id = str(run_id)
        trajectory = {
            "schema_version": "ATIF-v1.7",
            "session_id": session_id,
            "trajectory_id": str(uuid.uuid5(uuid.NAMESPACE_URL, f"executespec-terminal-bench:{session_id}")),
            "agent": {
                "name": self.name(),
                "version": self.version(),
                "model_name": self.model,
                "extra": {"provider": self.provider},
            },
            "steps": self._trajectory_steps,
            "final_metrics": {
                "total_prompt_tokens": context.n_input_tokens,
                "total_completion_tokens": context.n_output_tokens,
                "total_cost_usd": context.cost_usd,
                "total_steps": len(self._trajectory_steps),
            },
            "extra": {
                "es_base": self.base,
                "es_model": self.model,
                "es_provider": self.provider,
            },
        }
        out_dir = Path(getattr(self, "logs_dir", ".") or ".")
        out_dir.mkdir(parents=True, exist_ok=True)
        out = out_dir / "trajectory.json"
        out.write_text(json.dumps(trajectory, ensure_ascii=False, indent=2), encoding="utf-8")
        self.logger.info(f"[es] ATIF trajectory written: {out}")

    # ---------------- ExecuteSpec REST helpers ----------------
    async def _auth(self, client: httpx.AsyncClient) -> str:
        if self.pat:
            return self.pat
        if not (self.admin_email and self.admin_pw):
            raise ValueError("provide ES_PAT or ES_ADMIN_EMAIL/ES_ADMIN_PW")
        r = await client.post(f"{self.base}/api/auth/login",
                              json={"email": self.admin_email, "password": self.admin_pw})
        r.raise_for_status()
        return r.json()["accessToken"]

    async def _post(self, client, path, token, body) -> dict:
        r = await client.post(f"{self.base}{path}", json=body,
                             headers={"Authorization": f"Bearer {token}"})
        r.raise_for_status()
        return r.json() if r.content else {}

    async def _get(self, client, path, token) -> dict:
        r = await client.get(f"{self.base}{path}", headers={"Authorization": f"Bearer {token}"})
        r.raise_for_status()
        return r.json() if r.content else {}

    async def _delete(self, client, path, token) -> None:
        r = await client.delete(f"{self.base}{path}", headers={"Authorization": f"Bearer {token}"})
        if r.status_code not in (200, 202, 204, 404):
            r.raise_for_status()

    async def _cancel_run_on_abort(self, client, token, run_id, final) -> None:
        if not self.cancel_on_abort or not token or not run_id or final is not None:
            return
        try:
            await asyncio.wait_for(
                asyncio.shield(self._delete(client, f"/api/runs/{run_id}", token)),
                timeout=10,
            )
            self.logger.warning(f"[es] cancelled backend run after adapter abort: {run_id}")
        except Exception as e:
            self.logger.warning(f"[es] backend cancel after adapter abort failed for {run_id}: {e}")

    @staticmethod
    def _parse_frame(lines):
        data = "\n".join(l[5:].lstrip() for l in lines if l.startswith("data:"))
        if not data:
            return None
        try:
            return json.loads(data)
        except Exception:
            return None
