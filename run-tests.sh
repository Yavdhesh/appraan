#!/usr/bin/env bash
# Appraan manual tests — plain Java, no test framework, zero dependencies.
# Compiles main + test sources and runs every *Test class's main().
set -euo pipefail
cd "$(dirname "$0")"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAVA="$JAVA_HOME/bin/java"
else
  JAVAC="$(command -v javac)"
  JAVA="$(command -v java)"
fi

rm -rf build/test-classes
mkdir -p build/test-classes
find src/main/java src/test/java -name '*.java' > build/test-sources.txt
"$JAVAC" -source 24 -target 24 \
  --add-modules jdk.attach,jdk.jfr,jdk.management,jdk.httpserver \
  --add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED \
  -d build/test-classes @build/test-sources.txt

status=0
while IFS= read -r f; do
  cls="${f#src/test/java/}"
  cls="${cls%.java}"
  cls="${cls//\//.}"
  echo "== $cls =="
  "$JAVA" --add-modules jdk.attach,jdk.jfr,jdk.management,jdk.httpserver \
    --add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED \
    -cp build/test-classes "$cls" || status=1
done < <(grep -rl 'public static void main' src/test/java --include='*Test.java' | sort)

exit $status
