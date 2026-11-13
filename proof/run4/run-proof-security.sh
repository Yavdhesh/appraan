#!/usr/bin/env bash
# Proof run 4, Phase D — feed security modes + host/labels + flame zoom.
# D1: --kernel-key-file fail-closed ingest, envelope built by the REAL
#     appraanagent KernelPush code (.NET AesGcm) -> cross-stack interop.
# D2: plain mode: host/labels round-trip, /kernel host line, /flame zoom.
# D3: --https-keystore: the whole UI over HTTPS; plain HTTP refused.
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

wait_for_server() { # $1 = full URL
  for i in $(seq 1 60); do curl -s -o /dev/null "$1" && return 0; sleep 0.5; done
  echo "SERVER DID NOT COME UP: $1"; return 1
}

echo "== D1: key mode (fail-closed) =="
"${APPRAAN[@]}" "$LPID" --seconds 45 --interval 5 --kernel-key-file /tmp/testkey.b64 > "$SEC/appraan-keymode.log" 2>&1 &
APID=$!
wait_for_server "$BASE/api/summary"
sleep 2
echo "-- plain POST (expect 400):"
curl -s -o "$SEC/d1-plain.json" -w "http=%{http_code}\n" -X POST --data-binary @"$FIXTURE" -H "Content-Type: application/json" "$BASE/api/kernel/ingest"
echo "-- gzipped plain POST (expect 400):"
curl -s -o "$SEC/d1-gzip.json" -w "http=%{http_code}\n" -X POST --data-binary @/tmp/kernel-fixture.json.gz -H "Content-Type: application/json" -H "Content-Encoding: gzip" "$BASE/api/kernel/ingest"
echo "-- envelope with wrong content type (expect 400):"
curl -s -o "$SEC/d1-envtype.json" -w "http=%{http_code}\n" -X POST --data-binary @"$SEC/envelope.bin" -H "Content-Type: application/json" "$BASE/api/kernel/ingest"
echo "-- envelope POST, octet-stream (expect 200):"
curl -s -w "\nhttp=%{http_code}\n" -X POST --data-binary @"$SEC/envelope.bin" -H "Content-Type: application/octet-stream" "$BASE/api/kernel/ingest" > "$SEC/d1-envelope-response.txt"
cat "$SEC/d1-envelope-response.txt"
curl -s "$BASE/api/kernel" > "$SEC/d1-kernel-after.json"
wait $APID; echo "D1 Appraan exit=$?"

echo "== D2: host/labels + flame zoom (plain mode) =="
python3 -c "
import json
d = json.load(open('$FIXTURE'))
d['host'] = 'proof-node-1'
d['labels'] = {'pod': 'demo', 'env': 'test'}
json.dump(d, open('$SEC/fixture-host.json', 'w'))
print('host fixture written')"
"${APPRAAN[@]}" "$LPID" --seconds 30 --interval 5 > "$SEC/appraan-host.log" 2>&1 &
APID=$!
wait_for_server "$BASE/api/summary"
sleep 2
curl -s -X POST --data-binary @"$SEC/fixture-host.json" -H "Content-Type: application/json" "$BASE/api/kernel/ingest" > "$SEC/d2-ingest.json"
echo
curl -s "$BASE/api/kernel" > "$SEC/d2-kernel.json"
curl -s "$BASE/kernel" > "$SEC/d2-kernel-page.html"
curl -s "$BASE/flame" > "$SEC/d2-flame.html"
wait $APID; echo "D2 Appraan exit=$?"

echo "== D3: HTTPS mode =="
"$JAVA_HOME/bin/keytool" -genkeypair -alias appraan -keyalg RSA -keysize 2048 -validity 365 \
  -keystore /tmp/appraan.p12 -storetype PKCS12 -storepass appraan-proof-pw -keypass appraan-proof-pw \
  -dname "CN=localhost" -ext "SAN=dns:localhost,ip:127.0.0.1" 2>"$SEC/keytool.log"
"$JAVA_HOME/bin/keytool" -exportcert -alias appraan -keystore /tmp/appraan.p12 \
  -storepass appraan-proof-pw -file "$SEC/appraan.cer" 2>>"$SEC/keytool.log"
echo "keystore + cert exported"
APPRAAN_HTTPS_PASSWORD=appraan-proof-pw "${APPRAAN[@]}" "$LPID" --seconds 35 --interval 5 \
  --https-keystore /tmp/appraan.p12 --https-keystore-password env > "$SEC/appraan-https.log" 2>&1 &
APID=$!
for i in $(seq 1 60); do curl -s -o /dev/null --cacert "$SEC/appraan.cer" "$SBASE/api/summary" && break; sleep 0.5; done
sleep 2
echo "-- HTTPS GET /api/kernel (with exported cert, expect 200):"
curl -s -o "$SEC/d3-kernel.json" -w "http=%{http_code}\n" --cacert "$SEC/appraan.cer" "$SBASE/api/kernel"
echo "-- HTTPS POST fixture (expect ok):"
curl -s --cacert "$SEC/appraan.cer" -X POST --data-binary @"$FIXTURE" -H "Content-Type: application/json" "$SBASE/api/kernel/ingest" > "$SEC/d3-ingest.json"
cat "$SEC/d3-ingest.json"; echo
curl -s --cacert "$SEC/appraan.cer" "$SBASE/api/kernel" > "$SEC/d3-kernel-after.json"
echo "-- HTTPS without the cert (self-signed, expect TLS failure):"
curl -s -o /dev/null -w "http=%{http_code}\n" "$SBASE/api/kernel" || echo "curl exit=$? (TLS verify failed, as expected)"
echo "-- plain HTTP to the HTTPS port (expect failure):"
curl -s -o /dev/null -w "http=%{http_code}\n" --max-time 5 "$BASE/api/kernel" || echo "curl exit=$? (plain HTTP refused, as expected)"
wait $APID; echo "D3 Appraan exit=$?"
echo "== Phase D complete =="
