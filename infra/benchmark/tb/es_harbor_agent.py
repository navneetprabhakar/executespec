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
    ES_TB_LLM_READ_TIMEOUT_SECONDS  backend per-call LLM read timeout (default 120)
    ES_TB_LLM_RETRY_ATTEMPTS        backend transient retry attempts (default 2)
    ES_TB_STARTUP_RETRIES           whole-run retries after zero-tool ERROR (default 1)
"""
import asyncio
import base64
import glob
import json
import os
import re
import shlex

import httpx

from harbor.agents.base import BaseAgent
from harbor.environments.base import BaseEnvironment
from harbor.models.agent.context import AgentContext

READ_LIMIT, SHELL_LIMIT, DIFF_LIMIT = 8000, 6000, 5000

# Per-task wall clock (RL-2026-06, TB 2.0 full-89 autopsy): Harbor kills the agent phase at the
# TASK'S OWN [agent] timeout_sec (900s for most TB2 tasks, up to 3600s for heavy ones like
# regex-chess) — NOT a flat 1350s. A flat 900s server cap raced Harbor's kill on 900s tasks (the
# salvage finalizer fired exactly when Harbor killed the trial) and surrendered 75% of the budget
# on 3600s tasks. We resolve the task's timeout from the local harbor cache by matching the
# instruction text to the task package's instruction.md, then pass a derived wallClockSeconds so
# the ExecuteSpec worker (and its artifact-writing finalizer) finishes BEFORE Harbor's kill.
TASKS_ROOT = os.environ.get(
    "ES_TB_TASKS_ROOT",
    os.path.expanduser("~/.cache/harbor/tasks/packages/terminal-bench"),
)
_TASK_TIMEOUT_INDEX = None  # lazily built: normalized-instruction -> [agent] timeout_sec


def _norm_text(text: str) -> str:
    return re.sub(r"\s+", " ", (text or "").strip())


def _build_timeout_index() -> dict:
    idx = {}
    for toml_path in glob.glob(os.path.join(TASKS_ROOT, "*", "*", "task.toml")):
        inst_path = os.path.join(os.path.dirname(toml_path), "instruction.md")
        if not os.path.exists(inst_path):
            continue
        try:
            inst = _norm_text(open(inst_path, errors="replace").read())
            raw = open(toml_path, errors="replace").read()
            m = re.search(r"(?ms)^\[agent\][^\[]*?^\s*timeout_sec\s*=\s*([0-9.]+)", raw)
            if m and inst:
                idx[inst] = float(m.group(1))
        except Exception:  # noqa: BLE001 — best-effort index; misses fall back to server default
            continue
    return idx


def _wall_clock_for(instruction: str):
    """Derived wallClockSeconds for this task, or None (→ server default 900s).

    ES_TB_WALL_CLOCK forces a value. Otherwise: harbor [agent] timeout − 200s (one slow in-flight
    LLM/tool tail plus the terminal finalizer must land the artifact BEFORE Harbor's kill),
    floored at 300s, capped at 3420s.
    """
    forced = os.environ.get("ES_TB_WALL_CLOCK")
    if forced:
        try:
            return int(forced)
        except ValueError:
            pass
    global _TASK_TIMEOUT_INDEX
    if _TASK_TIMEOUT_INDEX is None:
        _TASK_TIMEOUT_INDEX = _build_timeout_index()
    t = _TASK_TIMEOUT_INDEX.get(_norm_text(instruction))
    if not t:
        return None
    return int(min(max(t - 80, 300), 3420))


def _trunc(s: str, n: int) -> str:
    return s if len(s) <= n else s[:n] + f"\n…[truncated {len(s) - n} chars]"


def _ok(content: str) -> dict:
    return {"ok": True, "content": content}


def _err(kind: str, msg: str) -> dict:
    return {"ok": False, "kind": kind, "message": msg}


class ExecuteSpecAgent(BaseAgent):
    """Harbor BaseAgent that drives the ExecuteSpec worker with tools executed in the TB container."""

    SUPPORTS_ATIF = False
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
        self.llm_read_timeout = int(os.environ.get("ES_TB_LLM_READ_TIMEOUT_SECONDS", "120"))
        self.llm_retry_attempts = int(os.environ.get("ES_TB_LLM_RETRY_ATTEMPTS", "2"))
        self.startup_retries = int(os.environ.get("ES_TB_STARTUP_RETRIES", "1"))
        if not self.provider or not self.model:
            raise ValueError("ES_PROVIDER and ES_MODEL env vars are required")

    async def setup(self, environment: BaseEnvironment) -> None:
        # Brain runs on the host; nothing to install in the container. base64/cat/grep are coreutils.
        return

    async def run(self, instruction: str, environment: BaseEnvironment, context: AgentContext) -> None:
        async with httpx.AsyncClient(timeout=httpx.Timeout(60.0, read=None)) as client:
            token = await self._auth(client)
            wall = _wall_clock_for(instruction)
            retry_of = None
            for attempt in range(self.startup_retries + 1):
                run_id = None
                tools = 0
                final = None
                try:
                    final, tools, run_id = await self._run_once(
                        client, token, instruction, environment, context, wall, retry_of
                    )
                    if self._should_retry_startup_failure(final, tools) and attempt < self.startup_retries:
                        self.logger.warning(
                            f"[es] run={run_id} terminal={final} tools=0; retrying startup"
                            f" ({attempt + 1}/{self.startup_retries})")
                        retry_of = run_id
                        continue

                    try:
                        cost = await self._get(client, f"/api/runs/{run_id}/cost", token)
                        context.n_input_tokens = cost.get("inputTokens") or cost.get("totalInputTokens")
                        context.n_output_tokens = cost.get("outputTokens") or cost.get("totalOutputTokens")
                        context.cost_usd = cost.get("costUsd") or cost.get("actualCostUsd")
                    except Exception as e:
                        self.logger.warning(f"[es] cost fetch failed: {e}")
                    metadata = {"es_run_id": run_id, "es_terminal": final, "tools": tools}
                    if retry_of:
                        metadata["startup_retry_of"] = retry_of
                    context.metadata = metadata
                    return
                except BaseException as e:
                    if run_id:
                        context.metadata = {
                            "es_run_id": run_id,
                            "es_terminal": final or "INTERRUPTED",
                            "tools": tools,
                            "error": type(e).__name__,
                        }
                        await self._cancel_backend_run(client, token, run_id, type(e).__name__)
                    raise

    async def _run_once(
        self,
        client: httpx.AsyncClient,
        token: str,
        instruction: str,
        environment: BaseEnvironment,
        context: AgentContext,
        wall,
        retry_of,
    ):
        tools = 0
        final = None
        # Scale the iteration cap with the per-task clock (a 3300s task supports far more
        # turns than the 120 floor); the server keeps its own floor for small values.
        iters = self.max_iters if wall is None else max(self.max_iters, min(wall // 7, 400))
        payload = {
            "instruction": instruction,
            "provider": self.provider,
            "model": self.model,
            "maxIterations": iters,
            "llmReadTimeoutSeconds": self.llm_read_timeout,
            "transientRetryMaxAttempts": self.llm_retry_attempts,
        }
        if wall is not None:
            payload["wallClockSeconds"] = wall
        started = await self._post(
            client, "/api/v2/test/terminal-bench/start", token, payload,
        )
        run_id, sse_token = started["runId"], started["sseToken"]
        metadata = {"es_run_id": run_id, "es_terminal": "RUNNING", "tools": 0}
        if retry_of:
            metadata["startup_retry_of"] = retry_of
        context.metadata = metadata
        self.logger.info(
            f"[es] run={run_id} provider={self.provider} model={self.model}"
            f" wall={wall or 'default'} iters={iters}"
            + (f" startup_retry_of={retry_of}" if retry_of else ""))

        url = f"{self.base}/api/runs/{run_id}/events?token={sse_token}"
        headers = {"Accept": "text/event-stream", "Last-Event-ID": "0"}
        try:
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
                            env_result = await self._exec_tool(tname, targs, environment)
                            await self._post(client, f"/api/runs/{run_id}/tool-results", token,
                                             {"callId": call_id, "result": env_result})
                        elif etype == "COMPLETE":
                            final = ev.get("payload")
                            break
                    else:
                        buf.append(line)
        except BaseException as e:
            context.metadata = {
                "es_run_id": run_id,
                "es_terminal": final or "INTERRUPTED",
                "tools": tools,
                "error": type(e).__name__,
            }
            await self._cancel_backend_run(client, token, run_id, type(e).__name__)
            raise

        if final is None:
            context.metadata = {
                "es_run_id": run_id,
                "es_terminal": "NO_COMPLETE",
                "tools": tools,
            }
            await self._cancel_backend_run(client, token, run_id, "NO_COMPLETE")
            raise RuntimeError(f"ExecuteSpec run {run_id} ended SSE without COMPLETE")

        self.logger.info(f"[es] run={run_id} terminal={final} tools={tools}")
        return final, tools, run_id

    @staticmethod
    def _should_retry_startup_failure(final, tools: int) -> bool:
        return tools == 0 and isinstance(final, str) and final.startswith("FAILED|ERROR")

    # ---------------- tool execution in the TB container ----------------
    async def _exec_tool(self, name: str, args: dict, env: BaseEnvironment) -> dict:
        try:
            if name in ("shell", "buildProject", "runTests"):
                cmd = args.get("command")
                if not cmd:
                    return _err("INVALID_ARGS", f"{name}: no command")
                r = await asyncio.wait_for(
                    env.exec(cmd, timeout_sec=self.tool_timeout),
                    timeout=self.tool_timeout + 15,
                )
                out = (r.stdout or "") + (r.stderr or "")
                body = _trunc(out, SHELL_LIMIT)
                return _ok(body + (f"\n[exit code {r.return_code}]" if r.return_code else ""))
            if name == "readFile":
                path = args["path"]
                r = await env.exec(f"cat -- {shlex.quote(path)}", timeout_sec=30)
                if r.return_code != 0:
                    return _err("NOT_FOUND", f"cannot read {path}: {(r.stderr or '').strip()}")
                return _ok(_trunc(r.stdout or "", READ_LIMIT))
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
        except asyncio.TimeoutError:
            return _err("TIMEOUT", f"{name}: command timed out after {self.tool_timeout}s")
        except Exception as e:
            return _err("INTERNAL", f"{type(e).__name__}: {e}")

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

    async def _delete(self, client, path, token) -> dict:
        r = await client.delete(f"{self.base}{path}", headers={"Authorization": f"Bearer {token}"})
        r.raise_for_status()
        return r.json() if r.content else {}

    async def _cancel_backend_run(self, client, token, run_id, reason) -> None:
        try:
            try:
                await self._delete(client, f"/api/v2/test/terminal-bench/{run_id}", token)
            except Exception:
                await self._delete(client, f"/api/runs/{run_id}", token)
            self.logger.warning(f"[es] run={run_id} cancelled after adapter interruption: {reason}")
        except Exception as e:
            self.logger.warning(f"[es] run={run_id} cancel failed after {reason}: {e}")

    @staticmethod
    def _parse_frame(lines):
        data = "\n".join(l[5:].lstrip() for l in lines if l.startswith("data:"))
        if not data:
            return None
        try:
            return json.loads(data)
        except Exception:
            return None
