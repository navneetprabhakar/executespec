#!/usr/bin/env python3
"""
slice-telegram.py — sends per-slice progress updates to Telegram.

Drops into the same per-slice workflow as scripts/slice-email.py.
Reads bot token + chat id from the first available source:

    1. Env vars TELEGRAM_BOT_TOKEN + TELEGRAM_CHAT_ID  (highest priority)
    2. ~/.executespec/telegram.json                    (per-user secrets)
    3. src/main/resources/application-local.yml        (telegram.bot-token / telegram.chat-id)

Add either the env vars OR a ~/.executespec/telegram.json file like:

    { "bot_token": "1234567890:AA...", "chat_id": "987654321" }

Then run after each slice (or have the agent run it):

    python3 scripts/slice-telegram.py --metadata /tmp/slice-E2.11.json

The metadata JSON is the same shape used by slice-email.py — see that
file's docstring for the schema. This script renders a compact text
message (Telegram caps at ~4096 chars; HTML mode used for bold/code).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
LOCAL_YML = REPO_ROOT / "src" / "main" / "resources" / "application-local.yml"
USER_CONFIG = Path.home() / ".executespec" / "telegram.json"


# ── Config loading ────────────────────────────────────────────────────

def load_config() -> dict:
    """Resolve bot_token + chat_id with explicit priority order."""
    # 1. Env vars
    env_token = os.environ.get("TELEGRAM_BOT_TOKEN")
    env_chat = os.environ.get("TELEGRAM_CHAT_ID")
    if env_token and env_chat:
        return {"bot_token": env_token, "chat_id": env_chat, "source": "env"}

    # 2. User config file
    if USER_CONFIG.exists():
        try:
            data = json.loads(USER_CONFIG.read_text())
            if data.get("bot_token") and data.get("chat_id"):
                return {
                    "bot_token": data["bot_token"],
                    "chat_id": str(data["chat_id"]),
                    "source": str(USER_CONFIG),
                }
        except Exception as ex:
            print(f"warn: could not parse {USER_CONFIG}: {ex}", file=sys.stderr)

    # 3. application-local.yml — telegram.bot-token / telegram.chat-id
    if LOCAL_YML.exists():
        text = LOCAL_YML.read_text()
        token = _yaml_extract(text, "telegram.bot-token")
        chat = _yaml_extract(text, "telegram.chat-id")
        if token and chat:
            return {"bot_token": token, "chat_id": chat, "source": str(LOCAL_YML)}

    return {"bot_token": "", "chat_id": "", "source": "(none)"}


def _yaml_extract(text: str, dotted: str) -> str | None:
    """Tiny YAML reader for `a.b.c: value` lookups (no external deps)."""
    parts = dotted.split(".")
    indent_stack: list[tuple[int, str]] = []
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        stripped = raw.lstrip()
        indent = len(raw) - len(stripped)
        while indent_stack and indent_stack[-1][0] >= indent:
            indent_stack.pop()
        if ":" not in stripped:
            continue
        key, _, rest = stripped.partition(":")
        key = key.strip()
        rest = rest.strip()
        scope = [s for _, s in indent_stack] + [key]
        if scope == parts and rest:
            if (rest.startswith('"') and rest.endswith('"')) or (
                rest.startswith("'") and rest.endswith("'")
            ):
                rest = rest[1:-1]
            m = re.match(r"\$\{([^:}]+):([^}]*)\}", rest)
            if m:
                return os.environ.get(m.group(1), m.group(2))
            return rest
        if not rest:
            indent_stack.append((indent, key))
    return None


# ── HTML rendering (Telegram supports a subset) ───────────────────────
# Allowed tags: <b>, <i>, <u>, <s>, <code>, <pre>, <a>, <tg-spoiler>.
# Everything else is stripped by Telegram, so we keep the message tight.

def _esc(s: str) -> str:
    return (
        s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )


def render_message(meta: dict) -> str:
    sid = meta.get("slice_id", "?")
    title = meta.get("title", "")
    status = meta.get("status", "?")
    sha = meta.get("commit_sha", "?")
    summary = meta.get("summary", "") or ""
    next_slice = meta.get("next_slice", "") or ""
    next_eta = meta.get("next_eta", "") or ""
    files = meta.get("files_changed", []) or []
    migrations = meta.get("migrations", []) or []
    build = meta.get("build", {}) or {}
    tests = meta.get("tests", {}) or {}

    # Telegram caps messages at 4096 chars — keep summary trimmed.
    if len(summary) > 1600:
        summary = summary[:1600].rsplit(" ", 1)[0] + " …(truncated; see email for full)"

    file_lines = "\n".join(
        f"  • <code>{_esc(f.get('path', ''))}</code>  +{f.get('added', 0)} -{f.get('deleted', 0)}"
        for f in files[:8]
    )
    if len(files) > 8:
        file_lines += f"\n  • <i>… and {len(files) - 8} more</i>"

    status_emoji = "✅" if status == "PASS" else "❌"

    msg = f"""<b>{status_emoji} ExecuteSpec slice {_esc(sid)}</b>
<b>{_esc(title)}</b>

<b>commit</b>  <code>{_esc(sha)}</code>
<b>build</b>   {_esc(build.get('status', '?'))}  ({_esc(str(build.get('seconds', '?')))}s)
<b>tests</b>   {_esc(tests.get('status', '?'))}  passed={_esc(str(tests.get('passed', '?')))} failed={_esc(str(tests.get('failed', '?')))}
<b>migs</b>    {_esc(', '.join(migrations) if migrations else '—')}

{_esc(summary)}

<b>files changed</b>
{file_lines if file_lines else '  • <i>(none)</i>'}

<b>next</b>  {_esc(next_slice)}  (ETA {_esc(next_eta)})"""
    return msg


# ── Send ──────────────────────────────────────────────────────────────

def send(meta: dict, dry_run: bool = False) -> None:
    cfg = load_config()
    text = render_message(meta)
    if dry_run:
        print(f"--- dry-run (config source: {cfg['source']}) ---\n{text}")
        return
    if not cfg["bot_token"] or not cfg["chat_id"]:
        print("ERROR: no Telegram credentials. Set TELEGRAM_BOT_TOKEN + "
              "TELEGRAM_CHAT_ID env vars, OR drop a JSON at "
              f"{USER_CONFIG} like: "
              "{ \"bot_token\": \"...\", \"chat_id\": \"...\" }",
              file=sys.stderr)
        sys.exit(2)

    url = f"https://api.telegram.org/bot{cfg['bot_token']}/sendMessage"
    payload = urllib.parse.urlencode({
        "chat_id": cfg["chat_id"],
        "text": text,
        "parse_mode": "HTML",
        "disable_web_page_preview": "true",
    }).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = resp.read().decode("utf-8")
            data = json.loads(body)
            if not data.get("ok"):
                print(f"ERROR: Telegram API said: {body}", file=sys.stderr)
                sys.exit(3)
            print(f"OK sent slice {meta.get('slice_id', '?')} to chat "
                  f"{cfg['chat_id']} (config source: {cfg['source']})")
    except Exception as ex:
        print(f"ERROR: send failed: {ex}", file=sys.stderr)
        sys.exit(4)


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--metadata", required=True, help="path to slice metadata JSON")
    p.add_argument("--dry-run", action="store_true",
                   help="render to stdout, don't send")
    args = p.parse_args()
    meta = json.loads(Path(args.metadata).read_text())
    send(meta, dry_run=args.dry_run)


if __name__ == "__main__":
    main()
