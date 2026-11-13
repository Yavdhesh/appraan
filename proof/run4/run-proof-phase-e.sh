#!/usr/bin/env bash
# Proof run 4, Phase E — Amendment-10 default mode + final envelope.
# E1: DEFAULT mode (no flags): startup mode line, plain POST of the
#     agent's verbatim default body, numbers match.
# E2: key mode with the FINAL envelope layout ([0x01][flags][nonce][ct+tag]):
#     plain/gzip posts rejected; plain-inside envelope AND gzip-inside
#     envelope (both built by the production .NET BuildBody) accepted.
set -u
cd "$(dirname "$0")/../.."
export JAVA_HOME=~/workspace/tools/jdk21
JAVA="$JAVA_HOME/bin/java"
RUN="proof/run4"
SEC="$RUN/security"
FIXTURE="$RUN/kernel-fixture.json"
LPID=6953
APPRAAN=("$JAVA" --add-modules jdk.attach --add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED -jar dist/appraan.jar)
BASE=http://127.0.0.1:7676

wait_for_server() {
  for i in $(seq 1 60); do curl -s -o /dev/null "$BASE/api/summary" && return 0; sleep 0.5; done
  echo "SERVER DID NOT COME UP"; return 1
}

echo "== E1: default mode (plain JSON / HTTP / no encryption) =="
"${APPRAAN[@]}" "$LPID" --seconds 30 --interval 5 > "$SEC/appraan-default.log" 2>&1 &
APID=$!
wait_for_server
sleep 2
grep -E "Kernel feed security|Web UI:" "$SEC/appraan-default.log" > "$SEC/e1-mode-lines.txt"
cat "$SEC/e1-mode-lines.txt"
echo "-- POST the agent's default body (verbatim JSON, no Content-Encoding):"
curl -s -w "\nhttp=%{http_code}\n" -X POST --data-binary @"$SEC/body-default.bin" -H "Content-Type: application/json" "$BASE/api/kernel/ingest" > "$SEC/e1-ingest.txt"
cat "$SEC/e1-ingest.txt"
curl -s "$BASE/api/kernel" > "$SEC/e1-kernel.json"
wait $APID; echo "E1 Appraan exit=$?"

echo "== E2: key mode, final envelope layout =="
"${APPRAAN[@]}" "$LPID" --seconds 40 --interval 5 --kernel-key-file /tmp/testkey.b64 > "$SEC/appraan-keymode2.log" 2>&1 &
APID=$!
wait_for_server
sleep 2
grep -E "Kernel feed security" "$SEC/appraan-keymode2.log" > "$SEC/e2-mode-lines.txt"
cat "$SEC/e2-mode-lines.txt"
echo "-- plain POST (expect 400):"
curl -s -o /dev/null -w "http=%{http_code}\n" -X POST --data-binary @"$FIXTURE" -H "Content-Type: application/json" "$BASE/api/kernel/ingest"
echo "-- gzip POST (expect 400):"
curl -s -o /dev/null -w "http=%{http_code}\n" -X POST --data-binary @"$SEC/body-gzip.bin" -H "Content-Type: application/json" -H "Content-Encoding: gzip" "$BASE/api/kernel/ingest"
echo "-- envelope, plain JSON inside (expect 200):"
curl -s -w "\nhttp=%{http_code}\n" -X POST --data-binary @"$SEC/envelope-plain.bin" -H "Content-Type: application/octet-stream" "$BASE/api/kernel/ingest" > "$SEC/e2-env-plain.txt"
cat "$SEC/e2-env-plain.txt"
curl -s "$BASE/api/kernel" > "$SEC/e2-kernel-after-plain.json"
echo "-- envelope, gzipped inside (expect 200):"
curl -s -w "\nhttp=%{http_code}\n" -X POST --data-binary @"$SEC/envelope-gzip.bin" -H "Content-Type: application/octet-stream" "$BASE/api/kernel/ingest" > "$SEC/e2-env-gzip.txt"
cat "$SEC/e2-env-gzip.txt"
curl -s "$BASE/api/kernel" > "$SEC/e2-kernel-after-gzip.json"
wait $APID; echo "E2 Appraan exit=$?"
echo "== Phase E complete =="
