#!/usr/bin/env bash
set -euo pipefail

# 用法:
#   LLM_API_KEY=... nix-shell --run "bash eval/run_eval.sh"
# 跑完 3 个真实故障用例(01-deadlock / 02-heap-leak / 03-cpu-spin),
# 输出 eval/results.jsonl 与 eval/results.md。

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$ROOT/build/install/jvm-diagnosis-agent/bin/jvm-diagnosis-agent"
OUT_JSONL="$ROOT/eval/results.jsonl"
OUT_MD="$ROOT/eval/results.md"

if [[ -z "${LLM_API_KEY:-}" ]]; then
  echo "缺少环境变量 LLM_API_KEY" >&2
  exit 1
fi

if [[ ! -x "$BIN" ]]; then
  (cd "$ROOT" && gradle installDist)
fi

rm -f "$OUT_JSONL"

CURRENT_PID=""
cleanup() {
  if [[ -n "$CURRENT_PID" ]]; then
    kill "$CURRENT_PID" 2>/dev/null || true
    for _ in $(seq 1 10); do
      kill -0 "$CURRENT_PID" 2>/dev/null || break
      sleep 0.1
    done
    # 满堆 OOM 抖动的 JVM 可能无法响应 SIGTERM 优雅停机,超时直接 SIGKILL
    if kill -0 "$CURRENT_PID" 2>/dev/null; then
      kill -9 "$CURRENT_PID" 2>/dev/null || true
    fi
    wait "$CURRENT_PID" 2>/dev/null || true
    CURRENT_PID=""
  fi
}
trap cleanup EXIT

run_case() {
  local case_id="$1"
  local java_file="$2"
  local jvm_opts="$3"
  local warmup_sec="$4"
  local tmp_out="/tmp/opencode/eval-${case_id}.out"
  rm -f "$tmp_out"

  # shellcheck disable=SC2086
  java $jvm_opts "$ROOT/$java_file" > "$tmp_out" 2>&1 &
  CURRENT_PID=$!

  local target_pid=""
  for _ in $(seq 1 50); do
    if grep -q "^pid=" "$tmp_out" 2>/dev/null; then
      target_pid="$(sed -n 's/^pid=//p' "$tmp_out" | head -n 1)"
      break
    fi
    sleep 0.2
  done

  if [[ -z "$target_pid" ]]; then
    echo "启动故障程序失败: $java_file" >&2
    cat "$tmp_out" >&2 || true
    exit 1
  fi

  sleep "$warmup_sec"
  (cd "$ROOT" && "$BIN" eval "$case_id" "$target_pid" "$OUT_JSONL")
  cleanup
}

run_case "01-deadlock"  "fault-cases/01-deadlock/Deadlock.java"  ""         1
run_case "02-heap-leak" "fault-cases/02-heap-leak/HeapLeak.java" "-Xmx128m" 4
run_case "03-cpu-spin"  "fault-cases/03-cpu-spin/CpuSpin.java"   ""         2

# 生成 Markdown 对照表
{
  echo "# Agent vs 固定 Workflow 评估结果"
  echo ""
  echo "- 时间: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "- 模型: \`$(jq -r '.model' "$OUT_JSONL" | head -n 1)\`"
  echo ""
  echo "## 分用例明细"
  echo ""
  echo "| 用例 | 模式 | 判对 | 步数 | 工具数 | 触发 L2 | 误触 L2 | 重复拦截 | Tokens (reasoning) | 耗时 (s) |"
  echo "|---|---|---|---:|---:|---|---|---:|---:|---:|"
  jq -r '
    def yn(b): if b then "是" else "否" end;
    def mis_l2: (.l2Triggered and (.expectedL2Needed | not));
    "| \(.caseId) | \(.mode) | \(if .passed then "✅" else "❌" end) | \(.steps) | \(.toolCalls) | \(yn(.l2Triggered)) | \(if mis_l2 then "⚠️ 是" else "否" end) | \(.duplicateBlocked) | \(.totalTokens) (\(.reasoningTokens)) | \((.durationMs / 100 | round) / 10) |"
  ' "$OUT_JSONL"
  echo ""
  echo "## 模式汇总"
  echo ""
  echo "| 模式 | 通过率 | 平均步数 | 平均工具数 | 误触 L2 次数 | 平均 Tokens | 平均耗时 (s) |"
  echo "|---|---:|---:|---:|---:|---:|---:|"
  jq -rs '
    group_by(.mode) | map({
      mode: .[0].mode,
      n: length,
      pass: (map(select(.passed)) | length),
      steps: ((map(.steps) | add) / length),
      tools: ((map(.toolCalls) | add) / length),
      mis_l2: (map(select(.l2Triggered and (.expectedL2Needed | not))) | length),
      tokens: (((map(.totalTokens) | add) / length) | round),
      sec: ((((map(.durationMs) | add) / length) / 100 | round) / 10)
    }) | .[] |
    "| \(.mode) | \(.pass)/\(.n) | \((.steps * 10 | round) / 10) | \((.tools * 10 | round) / 10) | \(.mis_l2) | \(.tokens) | \(.sec) |"
  ' "$OUT_JSONL"
} > "$OUT_MD"

echo ""
echo "==> 评估报告已生成: $OUT_MD"
cat "$OUT_MD"
