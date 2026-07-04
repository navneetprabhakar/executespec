#!/bin/bash
# Internal Terminal-Bench v2.1 one-pass matrix.
#
# Scope for the 2026-07 internal benchmark:
#   - k=1 only
#   - no leaderboard submission
#   - models: DeepSeek V4 native Pro + Flash, Claude Sonnet 5, Claude Sonnet 4.6,
#     GLM 5.2 via OpenRouter
#   - explicitly excludes Gemini, GPT, and Sarvam
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_TB="$SCRIPT_DIR/run-tb.sh"
TB_DATASET="${TB_DATASET:-terminal-bench/terminal-bench-2-1}"
BENCHMARK_DATASET="${BENCHMARK_DATASET:-terminal-bench-2-1}"
CONCURRENCY="${CONCURRENCY:-4}"
COMPACTION="${COMPACTION:-on}"
BRIDGE="${BRIDGE:-on}"
SUBMIT_TB=off
K=1
export TB_DATASET BENCHMARK_DATASET CONCURRENCY COMPACTION BRIDGE SUBMIT_TB K

run_one() {
  local provider="$1"
  local model="$2"
  local harbor_model="$3"
  local job_suffix="$4"

  case "$provider:$model" in
    deepseek:deepseek-v4-pro-native|deepseek:deepseek-v4-flash-native|anthropic:claude-sonnet-5|anthropic:claude-sonnet-4-6|openrouter:glm-5.2) ;;
    *)
      echo "Refusing unapproved benchmark route: provider=$provider model=$model" >&2
      exit 2
      ;;
  esac

  ES_PROVIDER="$provider" \
  ES_MODEL="$model" \
  HARBOR_MODEL="$harbor_model" \
  JOB_NAME="tb21-internal-${job_suffix}-k1" \
    bash "$RUN_TB"
}

run_one deepseek deepseek-v4-pro-native deepseek/deepseek-v4-pro deepseek-v4-pro-native
run_one deepseek deepseek-v4-flash-native deepseek/deepseek-v4-flash deepseek-v4-flash-native
run_one anthropic claude-sonnet-5 anthropic/claude-sonnet-5 claude-sonnet-5
run_one anthropic claude-sonnet-4-6 anthropic/claude-sonnet-4.6 claude-sonnet-4-6
run_one openrouter glm-5.2 z-ai/glm-5.2 glm-5-2-openrouter
