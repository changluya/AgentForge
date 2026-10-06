#!/usr/bin/env bash
#
# 真实测试验证：OpenAI Chat Completions + function calling（非流式 & 流式）
#
# 用法：
#   export OPENAI_API_KEY=sk-...
#   bash verify-openai-chat.sh
#
# 可选环境变量：
#   OPENAI_BASE_URL  默认 https://api.openai.com/v1
#   OPENAI_MODEL     默认 gpt-4o-mini
#
set -euo pipefail

: "${OPENAI_API_KEY:?请先 export OPENAI_API_KEY=...}"
BASE="${OPENAI_BASE_URL:-https://api.openai.com/v1}"
MODEL="${OPENAI_MODEL:-gpt-4o-mini}"

TOOLS='[{"type":"function","function":{"name":"get_weather","description":"查询指定城市的当前天气","parameters":{"type":"object","properties":{"city":{"type":"string","description":"城市名"}},"required":["city"]}}}]'
USER='[{"role":"user","content":"杭州今天天气怎么样？"}]'

echo "=================================================================="
echo "① 非流式：请求模型调用 get_weather 工具"
echo "=================================================================="
RESP=$(curl -s "$BASE/chat/completions" \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"$MODEL\",\"messages\":$USER,\"tools\":$TOOLS,\"tool_choice\":\"auto\"}")
echo "$RESP"

echo
echo "=================================================================="
echo "② 解析 tool_calls（需要 jq）"
echo "=================================================================="
if command -v jq >/dev/null 2>&1; then
  echo "$RESP" | jq '.choices[0].message.tool_calls'
  echo "finish_reason = $(echo "$RESP" | jq -r '.choices[0].finish_reason')"
else
  echo "（未安装 jq，跳过解析）"
fi

echo
echo "=================================================================="
echo "③ 流式：观察 tool_calls 增量（data: 帧）"
echo "=================================================================="
curl -sN "$BASE/chat/completions" \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"$MODEL\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"messages\":$USER,\"tools\":$TOOLS,\"tool_choice\":\"auto\"}"

echo
echo "完成。"
