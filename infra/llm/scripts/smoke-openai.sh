#!/bin/sh
set -eu

base_url="${1:-http://127.0.0.1:11434/v1}"
model="${OLLAMA_MODEL:-qwen3:4b-q8_0}"
payload_file="$(mktemp)"
response_file="$(mktemp)"
trap 'rm -f "$payload_file" "$response_file"' EXIT HUP INT TERM

jq -n --arg model "$model" '{
  model: $model,
  stream: false,
  temperature: 0.1,
  seed: 0,
  max_tokens: 128,
  response_format: {type: "json_object"},
  messages: [
    {role: "system", content: "/no_think JSON 객체만 반환하세요. 정확히 {\"questions\":[{\"rank\":1,\"sentence\":\"확인할까요?\"}]} 형식입니다."},
    {role: "user", content: "{\"questions\":[{\"rank\":1,\"templateSentence\":\"확인할까요?\"}]}"}
  ]
}' >"$payload_file"

status="$(curl --silent --show-error \
  --connect-timeout 5 --max-time 120 \
  --output "$response_file" --write-out '%{http_code}' \
  --header 'Content-Type: application/json' \
  --data-binary "@$payload_file" \
  "${base_url%/}/chat/completions")"

if [ "$status" != "200" ]; then
  printf '%s\n' "OpenAI smoke failed with HTTP $status" >&2
  exit 1
fi

if ! jq -e '
  (.choices | type == "array" and length > 0) and
  (.choices[0].message.content | type == "string") and
  ((.choices[0].message.content | fromjson) as $content |
    ($content | keys == ["questions"]) and
    ($content.questions | type == "array" and length == 1) and
    ($content.questions[0] | type == "object" and keys == ["rank", "sentence"]) and
    ($content.questions[0].rank == 1) and
    ($content.questions[0].sentence | type == "string" and endswith("?")))
' "$response_file" >/dev/null 2>&1; then
  printf '%s\n' "OpenAI smoke returned an invalid envelope" >&2
  exit 1
fi

printf '%s\n' "OpenAI-compatible smoke passed"
