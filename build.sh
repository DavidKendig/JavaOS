#!/usr/bin/env bash
# Builds JavaOS into out/ and packages javaos.jar.
set -euo pipefail
cd "$(dirname "$0")"

# Some installers put java and javac on PATH but not jar; fall back to the bin
# directory of whichever JDK is running.
find_tool() {
    if command -v "$1" >/dev/null 2>&1; then
        command -v "$1"
        return
    fi
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/$1" ]; then
        echo "$JAVA_HOME/bin/$1"
        return
    fi
    local home
    home=$(java -XshowSettings:properties -version 2>&1 \
        | awk -F'= ' '/java\.home/ {print $2; exit}')
    if [ -n "$home" ] && [ -x "$home/bin/$1" ]; then
        echo "$home/bin/$1"
    fi
}

rm -rf out && mkdir -p out
mapfile -t sources < <(find src -name '*.java')
echo "Compiling ${#sources[@]} source files..."
javac -d out "${sources[@]}"

jar_tool=$(find_tool jar)
if [ -z "$jar_tool" ]; then
    echo "Compiled to out/. (jar was not found, so no jar was packaged.)"
    echo "Run it with:  ./run.sh"
    exit 0
fi

"$jar_tool" --create --file javaos.jar --main-class javaos.Boot -C out .
echo "Built javaos.jar. Run it with:  java -jar javaos.jar"
