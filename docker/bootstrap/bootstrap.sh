#!/bin/sh
# One-shot bootstrap for the Better Stack Logs fixture (idempotent: never sends data, rewrites
# /bootstrap/env atomically on every run).
# 1. waits for both stub listeners, 2. proves the source token is accepted by the ingest host
#    (empty JSON array = 0 events, 202) and the SQL credentials by the query host,
# 3. writes KEY=value lines to /bootstrap/env for the test containers.
set -eu

out=/bootstrap/env
tries=0
until curl -fsS -o /dev/null "$BS_INGEST_URL/__stub/health" && curl -fsS -o /dev/null "$BS_QUERY_URL/ping"; do
  tries=$((tries + 1))
  [ "$tries" -ge 60 ] && { echo "bootstrap: stub not ready after 60 s" >&2; exit 1; }
  sleep 1
done

# Source token check: an empty array is a valid batch of zero events -> 202, no record stored.
code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BS_INGEST_URL/" \
  -H "Authorization: Bearer $BS_SOURCE_TOKEN" -H 'Content-Type: application/json' --data '[]')
[ "$code" = 202 ] || { echo "bootstrap: ingest token check returned $code" >&2; exit 1; }

# SQL credentials check (credentials via netrc-style -u; never echoed).
code=$(curl -sS -o /dev/null -w '%{http_code}' -u "$BS_QUERY_USERNAME:$BS_QUERY_PASSWORD" \
  -H 'Content-Type: text/plain' -X POST "$BS_QUERY_URL/" \
  --data "SELECT count() FROM remote(${BS_SOURCE_TABLE}_logs) FORMAT JSON")
[ "$code" = 200 ] || { echo "bootstrap: query credentials check returned $code" >&2; exit 1; }

tmp="$out.tmp.$$"
umask 022
cat >"$tmp" <<ENV
GROW_BETTER_STACK_LOGS_URL=$BS_INGEST_URL
GROW_BETTER_STACK_LOGS_QUERY_URL=$BS_QUERY_URL
BETTERSTACK_SOURCE_TOKEN=$BS_SOURCE_TOKEN
BETTERSTACK_INGESTING_HOST=${BS_INGEST_URL#http://}
BETTERSTACK_INGESTING_URL=$BS_INGEST_URL
BETTERSTACK_QUERY_HOST=${BS_QUERY_URL#http://}
BETTERSTACK_QUERY_URL=$BS_QUERY_URL
BETTERSTACK_QUERY_USERNAME=$BS_QUERY_USERNAME
BETTERSTACK_QUERY_PASSWORD=$BS_QUERY_PASSWORD
BETTERSTACK_SOURCE_TABLE=$BS_SOURCE_TABLE
BETTERSTACK_SOURCE_TOKEN_B=$BS_SOURCE_TOKEN_B
BETTERSTACK_SOURCE_TABLE_B=$BS_SOURCE_TABLE_B
BETTERSTACK_STUB_INGEST_CONTROL_URL=$BS_INGEST_URL/__stub
BETTERSTACK_STUB_QUERY_CONTROL_URL=$BS_QUERY_URL/__stub
ENV
mv "$tmp" "$out"
echo "bootstrap: wrote $out ($(wc -l <"$out") keys)"
