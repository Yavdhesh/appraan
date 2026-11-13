#!/usr/bin/env bash
# Appraan build — plain javac + jar, zero dependencies. Works on Linux and macOS.
set -euo pipefail
cd "$(dirname "$0")"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAR="$JAVA_HOME/bin/jar"
else
  JAVAC="$(command -v javac)"
  JAR="$(command -v jar)"
fi

echo "Using javac: $JAVAC"
rm -rf build/classes dist
mkdir -p build/classes dist

# shellcheck disable=SC2046
find src/main/java -name '*.java' > build/sources.txt
"$JAVAC" -source 21 -target 21 \
  --add-modules jdk.attach,jdk.jfr,jdk.management \
  --add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED \
  -d build/classes @build/sources.txt

"$JAR" --create --file dist/appraan.jar \
  --main-class dev.appraan.cli.Appraan \
  -C build/classes .

echo "Built dist/appraan.jar"
