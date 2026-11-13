#!/usr/bin/env bash
# Proof run 4 — kernel collector integration. See run4.log for the narrative.
# Phase A: bounded run; no-data state, plain POST, malformed POST, gzip POST.
# Phase B: --kernel-file fallback mode. Phase C: --seconds 0 + SIGINT.
set -u
cd "$(dirname "$0")/../.."
export JAVA_HOME=~/workspace/tools/jdk21
JAVA="$JAVA_HOME/bin/java"
RUN="proof/run4"
FIXTURE="$RUN/kernel-fixture.json"
APPRAAN=("$JAVA" --add-modules jdk.attach --add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED -jar dist/appraan.jar)
BASE=http://127.0.0.1:7676

echo "== starting LoadApp =="
"$JAVA" -Xmx256m -XX:+UseG1GC -cp dist/appraan.jar dev.appraan.demo.LoadApp > "$RUN/loadapp.log" 2>&1 &
LOADAPP_JOB=$!
for i in $(seq 1 30); do grep -q "pid=" "$RUN/loadapp.log" 2>/dev/null && break; sleep 0.5; done
LPID=$(sed -n 's/.*pid=\([0-9]*\).*/\1/p' "$RUN/loadapp.log" | head -1)
echo "LoadApp pid=$LPID"

wait_for_server() {
  for i in $(seq 1 40); do
    curl -s -o /dev/null "$BASE/api/summary" && return 0
    sleep 0.5
  done
  echo "SERVER DID NOT COME UP"; return 1
}

echo "== Phase A: bounded 50s run, push path =="
"${APPRAAN[@]}" "$LPID" --seconds 50 --interval 5 > "$RUN/appraan-push.log" 2>&1 &
APID=$!
wait_for_server
sleep 3
curl -s "$BASE/api/kernel" > "$RUN/kernel-before.json"
curl -s "$BASE/" > "$RUN/dashboard.html"
curl -s "$BASE/kernel" > "$RUN/kernel-page-nodata.html"
echo "-- plain POST --"
curl -s -X POST --data-binary @"$FIXTURE" -H "Content-Type: application/json" "$BASE/api/kernel/ingest" > "$RUN/ingest-plain-response.json"
echo
echo "-- malformed POST (expect 400) --"
curl -s -o "$RUN/ingest-malformed-response.json" -w "http=%{http_code}\n" -X POST --data-binary '{not json' -H "Content-Type: application/json" "$BASE/api/kernel/ingest"
curl -s "$BASE/api/kernel" > "$RUN/kernel-after-plain.json"
curl -s "$BASE/api/summary" > "$RUN/summary-after.json"
curl -s "$BASE/kernel" > "$RUN/kernel-page.html"
echo "-- gzip POST (max compression) --"
gzip -9 -c "$FIXTURE" > /tmp/kernel-fixture.json.gz
wc -c "$FIXTURE" /tmp/kernel-fixture.json.gz | head -2 > "$RUN/payload-sizes.txt"
cat "$RUN/payload-sizes.txt"
curl -s -X POST --data-binary @/tmp/kernel-fixture.json.gz -H "Content-Type: application/json" -H "Content-Encoding: gzip" "$BASE/api/kernel/ingest" > "$RUN/ingest-gzip-response.json"
echo
curl -s "$BASE/api/kernel" > "$RUN/kernel-after-gzip.json"
wait $APID; echo "Phase A Appraan exit=$?"

echo "== Phase B: --kernel-file fallback (25s) =="
"${APPRAAN[@]}" "$LPID" --seconds 25 --interval 5 --kernel-file "$FIXTURE" > "$RUN/appraan-file.log" 2>&1 &
APID=$!
wait_for_server
sleep 3
curl -s "$BASE/api/kernel" > "$RUN/kernel-file-mode.json"
wait $APID; echo "Phase B Appraan exit=$?"

echo "== Phase C: --seconds 0 (UI mode) + SIGINT =="
"${APPRAAN[@]}" "$LPID" --seconds 0 --interval 5 > "$RUN/appraan-forever.log" 2>&1 &
APID=$!
wait_for_server
sleep 12
curl -s "$BASE/api/summary" > "$RUN/forever-summary-1.json"
sleep 6
curl -s "$BASE/api/summary" > "$RUN/forever-summary-2.json"
curl -s "$BASE/api/kernel" > "$RUN/forever-kernel.json"
kill -INT $APID
wait $APID; echo "Phase C Appraan exit=$? (130 = shell convention for SIGINT-terminated)"

kill $LOADAPP_JOB 2>/dev/null
echo "== proof run complete =="
