#!/usr/bin/env bash
# Render the complete model-eval table: score, tasks, tokens (in/out), cost, latency, pinOK.
# Re-fetches tokens/cost (/api/runs/{id}/cost) + timing (/api/runs/{id}) per rid, so it works
# regardless of which script version produced the result rows. Dedups by rid across all sources.
set -uo pipefail
BASE="${BASE_URL:-http://localhost:8080}"
TOKEN="$(cat /tmp/es-token.txt)"
D="$(cd "$(dirname "$0")" && pwd)/model-sample-results"
RAW="$(ls -td "$D"/raw-* 2>/dev/null | head -1)"

gather(){
  cat "$D"/sample-*.jsonl 2>/dev/null
  cat /tmp/es-baselines-midhigh.jsonl 2>/dev/null
  cat "$D"/raw-*/*.json 2>/dev/null
}

echo "| Model | Band | Score | Tasks | In tok | Out tok | Cost \$ | Latency(s) | pinOK |"
echo "|---|---|---|---|---|---|---|---|---|"
gather \
  | jq -c 'select(.rid)' \
  | jq -rc '[.rid,.label,.band,((.verifiedScore // .error // "-")|tostring),(((.tasksDone//"-")|tostring)+"/"+((.tasksTotal//"-")|tostring)),(.pinOK//"-")]|@tsv' \
  | sort -t"$(printf '\t')" -u -k1,1 \
  | sort -t"$(printf '\t')" -k3,3 -k2,2 \
  | while IFS="$(printf '\t')" read -r rid label band score tasks pinok; do
      c=$(curl -s "$BASE/api/runs/$rid/cost" -H "Authorization: Bearer $TOKEN" 2>/dev/null)
      r=$(curl -s "$BASE/api/runs/$rid" -H "Authorization: Bearer $TOKEN" 2>/dev/null)
      intok=$(jq -r '.inputTokens // 0' <<<"$c" 2>/dev/null); outtok=$(jq -r '.outputTokens // 0' <<<"$c" 2>/dev/null)
      cost=$(jq -r '.costUsd // 0' <<<"$c" 2>/dev/null)
      dur=$(jq -r '(.completedAt) as $e|(.startedAt) as $s| if ($e and $s) then (($e|sub("\\.[0-9]+Z$";"Z")|fromdateiso8601)-($s|sub("\\.[0-9]+Z$";"Z")|fromdateiso8601))|tostring else "-" end' <<<"$r" 2>/dev/null)
      printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s |\n" "$label" "$band" "$score" "$tasks" "$intok" "$outtok" "$cost" "${dur}" "$pinok"
    done
