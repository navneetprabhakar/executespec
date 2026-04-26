#!/usr/bin/env python3
"""
slice-email.py — sends per-slice progress emails for the
enterprise-readiness execution branch.

Reads SMTP credentials from src/main/resources/application-local.yml
(app.mail.smtp.password default value) and sends a mobile-readable
HTML email to the configured recipient.

Usage:
    python3 scripts/slice-email.py --metadata /tmp/slice-E1.1.json

The metadata JSON file must contain (all required unless noted):
    {
      "slice_id":   "E1.1",
      "title":      "folder-tracking schema",
      "commit_sha": "abc1234",
      "status":     "PASS" | "FAIL",
      "summary":    "one-paragraph narrative",
      "files_changed": [{"path": "...", "added": N, "deleted": N}, ...],
      "migrations":     ["V131"],                   // optional
      "build": {"command": "mvn compile -q", "status": "PASS", "seconds": 12},
      "tests": {"command": "mvn test -q",   "status": "PASS",
                "passed": 214, "failed": 0, "skipped": 0, "seconds": 32},
      "deferred_items_md":  "markdown table of §13 (live)",
      "remaining_slices_md": "markdown table of §8 not-yet-complete",
      "next_slice":         "E1.4 — folder-writer chokepoint",
      "next_eta":           "1 day"
    }

Recipient is hardcoded to keep the script audit-friendly. To change
recipient, edit RECIPIENT below.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import smtplib
import ssl
import sys
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from pathlib import Path

# ── configuration ─────────────────────────────────────────────────────
RECIPIENT = "navneet.prabhakar007@gmail.com"
REPO_ROOT = Path(__file__).resolve().parent.parent
LOCAL_YML = REPO_ROOT / "src" / "main" / "resources" / "application-local.yml"
APP_YML = REPO_ROOT / "src" / "main" / "resources" / "application.yml"


def _yaml_extract(text: str, dotted_key: str) -> str | None:
    """
    Tiny YAML reader for `a.b.c: value` style lookups. Avoids PyYAML.
    Handles `${ENV:default}` placeholders by extracting the default.
    """
    parts = dotted_key.split(".")
    indent_stack: list[tuple[int, str]] = []
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        stripped = raw.lstrip()
        indent = len(raw) - len(stripped)
        # pop deeper-or-equal scopes
        while indent_stack and indent_stack[-1][0] >= indent:
            indent_stack.pop()
        if ":" not in stripped:
            continue
        key, _, rest = stripped.partition(":")
        key = key.strip()
        rest = rest.strip()
        scope = [s for _, s in indent_stack] + [key]
        if scope == parts and rest:
            # strip surrounding quotes
            if (rest.startswith('"') and rest.endswith('"')) or (
                rest.startswith("'") and rest.endswith("'")
            ):
                rest = rest[1:-1]
            # ${ENV:default} → default
            m = re.match(r"\$\{([^:}]+):([^}]*)\}", rest)
            if m:
                return os.environ.get(m.group(1), m.group(2))
            return rest
        if not rest:
            indent_stack.append((indent, key))
    return None


def load_smtp_config() -> dict[str, str | int]:
    app_text = APP_YML.read_text() if APP_YML.exists() else ""
    local_text = LOCAL_YML.read_text() if LOCAL_YML.exists() else ""

    def lookup(key: str, default: str = "") -> str:
        return (
            _yaml_extract(local_text, key)
            or _yaml_extract(app_text, key)
            or default
        )

    return {
        "host": lookup("app.mail.smtp.host", "smtp.gmail.com"),
        "port": int(lookup("app.mail.smtp.port", "587")),
        "username": lookup("app.mail.smtp.username", "admin@executespec.ai"),
        "password": os.environ.get("SMTP_PASSWORD")
        or lookup("app.mail.smtp.password")
        or "",
        "from": lookup("app.mail.from", "admin@executespec.ai"),
    }


# ── HTML rendering ────────────────────────────────────────────────────
# Inline styles only — most email clients strip <style> blocks.
# Single column, 16px body, neutral palette (works in dark + light
# mode), max-width 640 with side padding for thumb scroll on mobile.

CSS_BODY = (
    "margin:0;padding:0;background:#f4f5f7;"
    "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;"
    "color:#1a1d23;font-size:16px;line-height:1.5;"
)
CSS_CONTAINER = (
    "max-width:640px;margin:0 auto;padding:24px 16px;"
)
CSS_CARD = (
    "background:#ffffff;border:1px solid #e3e5e8;border-radius:8px;"
    "padding:20px;margin:16px 0;"
)
CSS_H1 = "font-size:22px;margin:0 0 8px;color:#0b0d10;font-weight:600;"
CSS_H2 = "font-size:17px;margin:24px 0 8px;color:#0b0d10;font-weight:600;"
CSS_META = "font-size:13px;color:#6b7280;margin:0;"
CSS_BADGE_PASS = (
    "display:inline-block;padding:4px 10px;border-radius:12px;"
    "background:#d1f4e0;color:#0a6b3a;font-size:12px;font-weight:600;"
    "letter-spacing:0.3px;"
)
CSS_BADGE_FAIL = CSS_BADGE_PASS.replace("#d1f4e0", "#fde0e0").replace("#0a6b3a", "#9b1c1c")
CSS_TABLE = (
    "width:100%;border-collapse:collapse;font-size:14px;margin:8px 0 0;"
)
CSS_TH = (
    "text-align:left;padding:8px 6px;border-bottom:1px solid #e3e5e8;"
    "font-weight:600;color:#374151;font-size:13px;"
)
CSS_TD = "padding:8px 6px;border-bottom:1px solid #f1f2f4;color:#1a1d23;"
CSS_KV_LABEL = "color:#6b7280;font-size:13px;width:38%;padding:4px 0;"
CSS_KV_VALUE = "color:#1a1d23;font-size:14px;padding:4px 0;"
CSS_FOOTER = (
    "color:#9ca3af;font-size:12px;text-align:center;margin:20px 0 0;"
)


def md_to_html_table(md: str) -> str:
    """
    Minimal markdown-table → HTML converter. Handles pipe tables only.
    Anything that isn't a pipe-table line passes through as a <p>.
    """
    if not md.strip():
        return f"<p style='{CSS_META}'>(none)</p>"
    out: list[str] = []
    in_table = False
    for line in md.splitlines():
        if "|" in line and re.match(r"^\s*\|", line):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            # separator row like |---|---|
            if all(re.match(r"^:?-+:?$", c) for c in cells):
                continue
            if not in_table:
                out.append(
                    f"<table style='{CSS_TABLE}' role='presentation' "
                    "cellpadding='0' cellspacing='0'>"
                )
                in_table = True
                row_html = "".join(
                    f"<th style='{CSS_TH}'>{_escape(c)}</th>" for c in cells
                )
                out.append(f"<tr>{row_html}</tr>")
            else:
                row_html = "".join(
                    f"<td style='{CSS_TD}'>{_escape(c)}</td>" for c in cells
                )
                out.append(f"<tr>{row_html}</tr>")
        else:
            if in_table:
                out.append("</table>")
                in_table = False
            if line.strip():
                out.append(f"<p style='margin:8px 0;'>{_escape(line.strip())}</p>")
    if in_table:
        out.append("</table>")
    return "\n".join(out)


def _escape(s: str) -> str:
    return (
        s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def render_html(meta: dict) -> str:
    badge_css = CSS_BADGE_PASS if meta.get("status") == "PASS" else CSS_BADGE_FAIL
    files = meta.get("files_changed", [])
    files_table = ""
    if files:
        rows = "".join(
            f"<tr>"
            f"<td style='{CSS_TD};font-family:Menlo,Consolas,monospace;font-size:12px;'>"
            f"{_escape(f['path'])}</td>"
            f"<td style='{CSS_TD};text-align:right;color:#0a6b3a;'>+{f.get('added', 0)}</td>"
            f"<td style='{CSS_TD};text-align:right;color:#9b1c1c;'>-{f.get('deleted', 0)}</td>"
            f"</tr>"
            for f in files
        )
        files_table = (
            f"<table style='{CSS_TABLE}' role='presentation' cellpadding='0' cellspacing='0'>"
            f"<tr><th style='{CSS_TH}'>file</th>"
            f"<th style='{CSS_TH};text-align:right;'>+</th>"
            f"<th style='{CSS_TH};text-align:right;'>-</th></tr>"
            f"{rows}</table>"
        )
    else:
        files_table = f"<p style='{CSS_META}'>(no files in metadata)</p>"

    build = meta.get("build", {})
    tests = meta.get("tests", {})
    migrations = ", ".join(meta.get("migrations", [])) or "—"

    def kv(label: str, value: str) -> str:
        return (
            f"<tr><td style='{CSS_KV_LABEL}'>{_escape(label)}</td>"
            f"<td style='{CSS_KV_VALUE}'>{_escape(value)}</td></tr>"
        )

    build_str = "{}  ({}s)".format(build.get("status", "?"), build.get("seconds", "?"))
    tests_str = "{}  passed={} failed={} ({}s)".format(
        tests.get("status", "?"),
        tests.get("passed", "?"),
        tests.get("failed", "?"),
        tests.get("seconds", "?"),
    )
    next_str = "{}  (ETA {})".format(meta.get("next_slice", "?"), meta.get("next_eta", "?"))
    summary_table = (
        f"<table style='width:100%;border-collapse:collapse;' role='presentation' "
        f"cellpadding='0' cellspacing='0'>"
        f"{kv('slice', meta.get('slice_id', '?'))}"
        f"{kv('commit', meta.get('commit_sha', '?'))}"
        f"{kv('migration(s)', migrations)}"
        f"{kv('build', build_str)}"
        f"{kv('tests', tests_str)}"
        f"{kv('next', next_str)}"
        f"</table>"
    )

    deferred_html = md_to_html_table(meta.get("deferred_items_md", ""))
    remaining_html = md_to_html_table(meta.get("remaining_slices_md", ""))

    return f"""<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ExecuteSpec slice {_escape(meta.get('slice_id', '?'))}</title>
</head>
<body style="{CSS_BODY}">
<div style="{CSS_CONTAINER}">
  <div style="{CSS_CARD}">
    <p style="{CSS_META}">ExecuteSpec — enterprise-readiness branch</p>
    <h1 style="{CSS_H1}">Slice {_escape(meta.get('slice_id', '?'))} — {_escape(meta.get('title', ''))}</h1>
    <p style="margin:8px 0 0;"><span style="{badge_css}">{_escape(meta.get('status', '?'))}</span></p>
    {summary_table}
  </div>

  <div style="{CSS_CARD}">
    <h2 style="{CSS_H2}">Updates from this slice</h2>
    <p style="margin:8px 0;">{_escape(meta.get('summary', ''))}</p>
    <h2 style="{CSS_H2}">Files changed</h2>
    {files_table}
  </div>

  <div style="{CSS_CARD}">
    <h2 style="{CSS_H2}">Deferred items (live log)</h2>
    {deferred_html}
  </div>

  <div style="{CSS_CARD}">
    <h2 style="{CSS_H2}">Remaining slices</h2>
    {remaining_html}
  </div>

  <p style="{CSS_FOOTER}">
    Sent by scripts/slice-email.py · branch feature/enterprise-readiness-eval
  </p>
</div>
</body></html>
"""


def send(meta: dict) -> None:
    smtp = load_smtp_config()
    if not smtp["password"]:
        print("ERROR: no SMTP password (set SMTP_PASSWORD or "
              "app.mail.smtp.password in application-local.yml)", file=sys.stderr)
        sys.exit(2)

    msg = MIMEMultipart("alternative")
    status = meta.get("status", "?")
    msg["Subject"] = (
        f"ExecuteSpec slice {meta.get('slice_id', '?')} — "
        f"{meta.get('title', '')} — {status}"
    )
    msg["From"] = smtp["from"]
    msg["To"] = RECIPIENT

    plain = (
        f"Slice {meta.get('slice_id')} — {meta.get('title')} — {status}\n"
        f"Commit: {meta.get('commit_sha')}\n"
        f"Build: {meta.get('build', {}).get('status')} | "
        f"Tests: {meta.get('tests', {}).get('status')}\n\n"
        f"{meta.get('summary', '')}\n\n"
        f"(View in HTML for the deferred-items log and remaining-slices table.)"
    )
    msg.attach(MIMEText(plain, "plain", "utf-8"))
    msg.attach(MIMEText(render_html(meta), "html", "utf-8"))

    ctx = ssl.create_default_context()
    with smtplib.SMTP(smtp["host"], smtp["port"]) as s:
        s.ehlo()
        s.starttls(context=ctx)
        s.login(smtp["username"], smtp["password"])
        s.send_message(msg)
    print(f"OK sent to {RECIPIENT} via {smtp['host']}:{smtp['port']}")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--metadata", required=True, help="path to slice metadata JSON")
    p.add_argument("--dry-run", action="store_true", help="render HTML to stdout, don't send")
    args = p.parse_args()
    meta = json.loads(Path(args.metadata).read_text())
    if args.dry_run:
        print(render_html(meta))
        return
    send(meta)


if __name__ == "__main__":
    main()
