#!/usr/bin/env bash
#
# 真实测试验证：Anthropic Messages API + tool use（非流式 & 流式）
#
# 用法：
#   export ANTHROPIC_API_KEY=sk-ant-...
#   bash verify-anthropic-chat.sh
#
# 可选环境变量：
#   ANTHROPIC_BASE_URL  默认 https://api.anthropic.com
#   ANTHROPIC_MODEL     默认 claude-3-5-sonnet-latest
#
set -euo pipefail

: "${ANTHROPIC_API_KEY:?请先 export ANTHROPIC_API_KEY=...}"
BASE="${ANTHROPIC_BASE_URL:-https://api.anthropic.com}"
MODEL="${ANTHROPIC_MODEL:-claude-3-5-sonnet-latest}"
VERSION="2023-06-01"

TOOLS='[{"name":"get_weather","description":"查询指定城市的当前天气","input_schema":{"type":"object","properties":{"city":{"type":"string","description":"城市名"}},"required":["city"]}}]'
MESSAGES='[{"role":"user","content":"杭州今天天气怎么样？"}]'

echo "=================================================================="
echo "① 非流式：请求模型调用 get_weather 工具"
echo "=================================================================="
RESP=$(curl -s "$BASE/v1/messages" \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: $VERSION" \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"$MODEL\",\"max_tokens\":1024,\"system\":\"你是一个简洁的助手。\",\"messages\":$MESSAGES,\"tools\":$TOOLS,\"tool_choice\":{\"type\":\"auto\"}}")
echo "$RESP"

echo
echo "=================================================================="
echo "② 解析 tool_use（需要 jq）"
echo "=================================================================="
if command -v jq >/dev/null 2>&1; then
  echo "$RESP" | jq '.content[] | select(.type=="tool_use")'
  echo "stop_reason = $(echo "$RESP" | jq -r '.stop_reason')"
else
  echo "（未安装 jq，跳过解析）"
fi

echo
echo "=================================================================="
echo "③ 流式：观察 input_json_delta 增量（event/data 帧）"
echo "=================================================================="
curl -sN "$BASE/v1/messages" \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: $VERSION" \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"$MODEL\",\"max_tokens\":1024,\"stream\":true,\"messages\":$MESSAGES,\"tools\":$TOOLS,\"tool_choice\":{\"type\":\"auto\"}}"

echo
echo "完成。"
