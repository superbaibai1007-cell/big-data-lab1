#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/classes
javac -encoding UTF-8 --release 11 -classpath "$(hadoop classpath --glob)" -d build/classes src/lab/*.java
jar cf build/lab1.jar -C build/classes .
echo 'BUILD PASS: build/lab1.jar'
