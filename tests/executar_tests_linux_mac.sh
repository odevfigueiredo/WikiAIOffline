#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'Instale o compilador Kotlin'; exit 1; }
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
SRC='app/src/main/java/com/wikiaioffline'
kotlinc "$SRC/SearchEngine.kt" "$SRC/BilingualSearch.kt" "$SRC/WikiRag.kt" \
    tests/TestRag.kt tests/TestBilingual.kt -include-runtime -d "$TMP_DIR/testes.jar"
java -cp "$TMP_DIR/testes.jar" TestRagKt
java -cp "$TMP_DIR/testes.jar" TestBilingualKt
