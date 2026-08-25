#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
[ -f out/javaos/Boot.class ] || ./build.sh
java -cp out javaos.Boot "$@"
