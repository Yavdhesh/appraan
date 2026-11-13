#!/usr/bin/env bash
# Proof run 4, Phase D3b — HTTPS rerun with the PEM cert export for curl
# (keytool's default export is DER; curl --cacert needs PEM. The agent's
# --tls-cert accepts either.) Plus: /flame recaptured after real rounds
# (the D2 capture hit the empty startup snapshot).
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
SBASE=https://127.0.0.1:7676
PEM="$SEC/appraan.pem"

echo "== D3b: HTTPS mode (PEM cacert) =="
APPRAAN_HTTPS_PASSWORD=appraan-proof-pw "${APPRAAN[@]}" "$LPID" --seconds 40 --interval 5 \
  --https-keystore /tmp/appraan.p12 --https-keystore-password env > "$SEC/appraan-https2.log" 2>&1 &
APID=$!
for i in $(seq 1 60); do curl -s -o /dev/null --cacert "$PEM" "$SBASE/api/summary" && break; sleep 0.5; done
sleep 2
echo "-- HTTPS GET /api/kernel (PEM cacert, expect 200):"
curl -s -o "$SEC/d3b-kernel.json" -w "http=%{http_code}\n" --cacert "$PEM" "$SBASE/api/kernel"
echo "-- HTTPS POST fixture (expect ok):"
curl -s --cacert "$PEM" -X POST --data-binary @"$FIXTURE" -H "Content-Type: application/json" "$SBASE/api/kernel/ingest" > "$SEC/d3b-ingest.json"
cat "$SEC/d3b-ingest.json"; echo
curl -s --cacert "$PEM" "$SBASE/api/kernel" > "$SEC/d3b-kernel-after.json"
echo "-- HTTPS dashboard over TLS (expect 200 + HTML):"
curl -s -o "$SEC/d3b-dashboard.html" -w "http=%{http_code}\n" --cacert "$PEM" "$SBASE/"
echo "-- HTTPS without trusting the cert (expect curl exit 60):"
curl -s -o /dev/null "$SBASE/api/kernel" || echo "curl exit=$?"
echo "-- plain HTTP to the HTTPS port (expect curl exit 52):"
curl -s -o /dev/null --max-time 5 "$BASE/api/kernel" || echo "curl exit=$?"
wait $APID; echo "D3b Appraan exit=$?"

echo "== Flame recapture (plain mode, populated stacks) =="
"${APPRAAN[@]}" "$LPID" --seconds 25 --interval 5 > "$SEC/appraan-flame.log" 2>&1 &
APID=$!
for i in $(seq 1 60); do curl -s -o /dev/null "$BASE/api/summary" && break; sleep 0.5; done
sleep 14
curl -s "$BASE/flame" > "$SEC/d2-flame.html"
wait $APID; echo "flame Appraan exit=$?"
echo "== D3b complete =="
