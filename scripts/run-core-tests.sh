#!/usr/bin/env bash
# Compila el núcleo en Java (sin Android) y ejecuta sus pruebas automáticas.
# Se compila con nivel de API de Java 8 para detectar funciones que no existen en Android.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${TMPDIR:-/tmp}/gp-core-tests"
rm -rf "$OUT"
mkdir -p "$OUT"
javac --release 8 -Xlint:-options -encoding UTF-8 -d "$OUT" \
  app/src/main/java/com/gestorproxy/core/*.java \
  tests/core/com/gestorproxy/core/*.java
java -cp "$OUT" com.gestorproxy.core.CoreTests
