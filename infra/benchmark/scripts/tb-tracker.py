#!/usr/bin/env python3
"""
TB eval tracker — renders a self-contained, auto-refreshing visual dashboard from a Harbor job dir,
optionally loops, syncs the HTML to GCS, and pushes a Telegram ping each cycle.

Usage:
  python3 tb-tracker.py <job_dir> [--name "v4-pro · TB k=5"] [--total 89] [--out dash.html]
                        [--loop 1800] [--telegram] [--gcs gs://bucket/path] [--tg-script /path/tg-send.py]

Data sources (Harbor): <job_dir>/**/reward.txt (1=pass,0=fail) + <job_dir>/result.json (stats).
Self-contained HTML (data baked in + <meta refresh>), so a GCS-hosted copy needs no CORS/fetch.
"""
import argparse, glob, json, os, subprocess, sys, time, collections, html

PALETTE = dict(bg="#0e0f12", bg2="#15161b", bg3="#1c1e26", line="#2a2d38", fg="#fbfaf6",
               mut="#9aa0b0", dim="#6b7180", mint="#7ce3b4", yellow="#f4d35e", lav="#c4b8e0", red="#ff8f86")


def gather(job_dir):
    """task -> [rewards]; plus stats from result.json."""
    rw = collections.defaultdict(list)
    for f in glob.glob(os.path.join(job_dir, "**", "reward.txt"), recursive=True):
        rel = os.path.relpath(os.path.dirname(f), job_dir)
        task = rel.split(os.sep)[0].split("__")[0]
        try:
            rw[task].append(1 if open(f).read().strip().startswith("1") else 0)
        except Exception:
            pass
    stats = {}
    try:
        stats = json.load(open(os.path.join(job_dir, "result.json"))).get("stats", {})
    except Exception:
        pass
    return rw, stats


def cell(task, rewards):
    p, n = sum(rewards), len(rewards)
    if n == 0:
        bg, mark = PALETTE["bg3"], "·"
    elif p == n:
        bg, mark = PALETTE["mint"], "✓"          # all pass
    elif p == 0:
        bg, mark = PALETTE["red"], "✗"            # all fail
    else:
        bg, mark = PALETTE["yellow"], f"{p}/{n}"  # mixed (k>1)
    fg = "#0e0f12" if bg in (PALETTE["mint"], PALETTE["yellow"]) else PALETTE["fg"]
    return (f'<div class="cell" style="background:{bg};color:{fg}" title="{html.escape(task)}: {p}/{n}">'
            f'<span class="m">{mark}</span><span class="t">{html.escape(task)}</span></div>')


def render(rw, stats, name, total, running):
    tasks = sorted(rw)
    scored = len(tasks)
    trials = sum(len(v) for v in rw.values())
    passed = sum(sum(v) for v in rw.values())
    solved = sum(1 for v in rw.values() if any(v))
    pr = (100.0 * passed / trials) if trials else 0
    cost = stats.get("cost_usd", 0) or 0
    errored = stats.get("n_errored_trials", "?")
    nrun = stats.get("n_running_trials", 0) or 0
    pct = int(scored / total * 100) if total else 0
    status = "RUNNING" if running else ("DONE" if scored >= total - 3 else "STOPPED")
    sc_color = PALETTE["mint"] if running else PALETTE["lav"]
    cells = "".join(cell(t, rw[t]) for t in tasks)
    P = PALETTE
    t = TEMPLATE
    repl = {
        "__NAME__": html.escape(name), "__STATUS__": status, "__SC_COLOR__": sc_color,
        "__PCT__": str(pct), "__SCORED__": str(scored), "__TOTAL__": str(total),
        "__PASSED__": str(passed), "__TRIALS__": str(trials), "__PR__": f"{pr:.0f}",
        "__SOLVED__": str(solved), "__COST__": f"{cost:.2f}", "__ERR__": str(errored),
        "__RUN__": str(nrun), "__CELLS__": cells, "__TS__": time.strftime("%H:%M:%S"),
        "__DATE__": time.strftime("%Y-%m-%d"),
    }
    for k, v in {**P, **repl}.items():
        t = t.replace("__" + k.upper() + "__" if k in P else k, v)
    return t


TEMPLATE = """<!doctype html><html lang=en><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<meta http-equiv=refresh content=30>
<title>__NAME__ — TB tracker</title>
<style>
@import url('https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;600;700&display=swap');
*{box-sizing:border-box;margin:0;padding:0}
body{background:__BG__;color:__FG__;font-family:'JetBrains Mono',ui-monospace,Menlo,monospace;padding:22px 26px;-webkit-font-smoothing:antialiased}
.tag{color:__MINT__;font-size:10px;letter-spacing:3px;text-transform:uppercase;font-weight:600}
h1{font-size:21px;font-weight:700;margin:4px 0 2px}
.sub{color:__DIM__;font-size:11px;margin-bottom:16px}
.badge{display:inline-block;padding:2px 10px;border-radius:20px;font-size:11px;font-weight:700;background:rgba(124,227,180,.14);color:__SC_COLOR__;margin-left:8px;vertical-align:middle}
.bar{height:12px;border-radius:7px;background:__BG3__;overflow:hidden;margin:12px 0 4px}
.bar>i{display:block;height:100%;background:linear-gradient(90deg,__MINT__,__YELLOW__);width:__PCT__%;transition:width .4s}
.barlbl{color:__MUT__;font-size:11px;margin-bottom:18px}
.cards{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:20px}
.card{flex:1;min-width:96px;background:__BG2__;border:1px solid __LINE__;border-radius:10px;padding:12px 14px}
.card .k{color:__DIM__;font-size:9px;letter-spacing:1px;text-transform:uppercase}
.card .v{font-size:24px;font-weight:700;margin-top:3px;line-height:1}
.mint{color:__MINT__}.yel{color:__YELLOW__}.lav{color:__LAV__}.red{color:__RED__}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(120px,1fr));gap:5px}
.cell{border-radius:6px;padding:7px 8px;font-size:9px;overflow:hidden;display:flex;flex-direction:column;gap:1px;min-height:34px}
.cell .m{font-weight:700;font-size:11px}
.cell .t{opacity:.85;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.legend{display:flex;gap:16px;margin:16px 0 8px;font-size:10px;color:__MUT__}
.dot{display:inline-block;width:10px;height:10px;border-radius:3px;vertical-align:middle;margin-right:4px}
.foot{margin-top:18px;border-top:1px solid __LINE__;padding-top:10px;color:__DIM__;font-size:9px;display:flex;justify-content:space-between}
</style></head><body>
<div class=tag>ExecuteSpec · Terminal-Bench</div>
<h1>__NAME__ <span class=badge>__STATUS__</span></h1>
<div class=sub>updated __TS__ · __DATE__ · auto-refresh 30s</div>
<div class=bar><i></i></div>
<div class=barlbl>__SCORED__ / __TOTAL__ scored (__PCT__%)</div>
<div class=cards>
<div class=card><div class=k>Passed</div><div class="v mint">__PASSED__<span style="font-size:12px;color:__DIM__">/__TRIALS__</span></div></div>
<div class=card><div class=k>Pass rate</div><div class="v yel">__PR__%</div></div>
<div class=card><div class=k>Solved ≥1</div><div class="v lav">__SOLVED__</div></div>
<div class=card><div class=k>Cost</div><div class=v>$__COST__</div></div>
<div class=card><div class=k>Running</div><div class=v>__RUN__</div></div>
<div class=card><div class=k>Errored</div><div class="v red">__ERR__</div></div>
</div>
<div class=legend>
<span><span class=dot style="background:__MINT__"></span>pass</span>
<span><span class=dot style="background:__RED__"></span>fail</span>
<span><span class=dot style="background:__YELLOW__"></span>mixed (k&gt;1)</span>
<span><span class=dot style="background:__BG3__"></span>pending</span>
</div>
<div class=grid>__CELLS__</div>
<div class=foot><span>ExecuteSpec × Terminal-Bench · live tracker</span><span>__DATE__ __TS__</span></div>
</body></html>"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("job_dir")
    ap.add_argument("--name", default="Terminal-Bench")
    ap.add_argument("--total", type=int, default=89)
    ap.add_argument("--k", type=int, default=1, help="attempts per task — ping a task once it has k results in")
    ap.add_argument("--out", default=None)
    ap.add_argument("--loop", type=int, default=0, help="seconds between cycles (0 = one-shot)")
    ap.add_argument("--telegram", action="store_true")
    ap.add_argument("--tg-script", default="/tmp/tg-send.py")
    ap.add_argument("--gcs", default=None, help="gs://bucket/path.html to sync to")
    ap.add_argument("--done-marker", default=None)
    ap.add_argument("--baseline", default=None, help="prior best for the publish-decision hint, e.g. '12/20'")
    ap.add_argument("--heartbeat", type=int, default=6, help="digest ping every Nth cycle (0=off) so you know it's alive")
    a = ap.parse_args()
    out = a.out or os.path.join(os.path.expanduser("~"), "tb-tracker.html")

    def ping(msg):
        if not a.telegram:
            return
        if not os.path.exists(a.tg_script):
            print(f"telegram_send=missing tg_script={a.tg_script}", flush=True)
            return
        proc = subprocess.run(["python3", a.tg_script, msg], capture_output=True, text=True)
        if proc.returncode == 0:
            print(f"telegram_send=ok chars={len(msg)}", flush=True)
        else:
            err = (proc.stderr or proc.stdout or "").strip().replace("\n", " ")[:300]
            print(f"telegram_send=failed code={proc.returncode} error={err}", flush=True)

    seen = set()      # tasks already pinged (k results in)
    cyc = [0]

    def cycle():
        cyc[0] += 1
        running = not (a.done_marker and os.path.exists(a.done_marker))
        rw, stats = gather(a.job_dir)
        open(out, "w").write(render(rw, stats, a.name, a.total, running))
        if a.gcs:
            subprocess.run(["gsutil", "-h", "Content-Type:text/html", "-h",
                            "Cache-Control:no-cache", "cp", out, a.gcs], capture_output=True)
        scored = len(rw)
        resolved = sum(1 for v in rw.values() if any(v))
        cost = stats.get("cost_usd", 0) or 0
        # PER-TASK signal — ping the moment a task finishes its k attempts (real-time, decision-grade)
        for t in sorted(rw):
            if len(rw[t]) >= a.k and t not in seen:
                seen.add(t)
                p, n = sum(rw[t]), len(rw[t])
                ping(f"{'✅ PASS' if p > 0 else '❌ FAIL'}  {t}  ({p}/{n})\n"
                     f"→ {resolved}/{scored} resolved · {a.total} total · ${cost:.2f}")
        # heartbeat digest (alive signal between completions)
        if a.heartbeat and (cyc[0] == 1 or cyc[0] % a.heartbeat == 0):
            pct = (100.0 * resolved / scored) if scored else 0
            ping(
                "ExecuteSpec TB Update\n\n"
                f"Run: {a.name}\n"
                f"Progress: {scored}/{a.total} tasks scored\n"
                f"Resolved: {resolved}/{scored} ({pct:.1f}%)\n"
                f"Cost: ${cost:.2f}\n"
                f"State: {'running' if running else 'done'}\n"
                "Dashboard: https://benchmark.executespec.ai/benchmark"
            )
        return running

    if a.loop:
        while cycle():
            time.sleep(a.loop)
        cycle()  # final render
    else:
        cycle()

    # FINAL publish-decision summary
    rw, stats = gather(a.job_dir)
    scored = len(rw)
    resolved = sum(1 for v in rw.values() if any(v))
    cost = stats.get("cost_usd", 0) or 0
    pct = (100.0 * resolved / scored) if scored else 0
    hint = ""
    if a.baseline:
        hint = f"\nbaseline {a.baseline}"
        try:
            bn = int(str(a.baseline).split("/")[0])
            hint += " → " + ("✅ matches/beats — consider publishing"
                             if resolved >= bn else "⚠️ below baseline — review first")
        except Exception:
            pass
    ping(
        "ExecuteSpec TB Complete\n\n"
        f"Run: {a.name}\n"
        f"Resolved: {resolved}/{scored} ({pct:.1f}%)\n"
        f"Cost: ${cost:.2f}"
        f"{hint}\n"
        "Next: review, upload, and submit only after approval."
    )
    print("dashboard:", out, ("→ " + a.gcs) if a.gcs else "")


if __name__ == "__main__":
    main()
