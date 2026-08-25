#!/usr/bin/env bash
# Compiles and runs the JavaOS checks. No test framework: plain main methods
# that print one line per assertion and exit non-zero on failure.
set -euo pipefail
cd "$(dirname "$0")"

[ -f out/javaos/Boot.class ] || ./build.sh >/dev/null

# Windows JVMs split the classpath on ';' even when bash is the shell.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) sep=';' ;;
    *) sep=':' ;;
esac

mkdir -p out-test
javac -cp out -d out-test test/*.java

status=0
for suite in CoreTest OfficeInteropTest; do
    echo "=== $suite ==="
    java -cp "out${sep}out-test" "$suite" || status=1
    echo
done
exit $status
