#!/bin/bash
# Compile the Advanced Severe Weather Detector app off-hub and run the freeze tests.
# Needs Java 17+ and Groovy 4 on PATH (or set JAVA_HOME / GROOVY_HOME).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -n "${GROOVY_HOME:-}" ] && PATH="$GROOVY_HOME/bin:$PATH"
[ -n "${JAVA_HOME:-}" ] && PATH="$JAVA_HOME/bin:$PATH"
B="$(mktemp -d)"; trap 'rm -rf "$B"' EXIT
cp "$HERE/../../apps/Advanced Severe Weather Detector.groovy" "$B/SevereWeatherApp.groovy"
groovyc -d "$B/classes" "$B/SevereWeatherApp.groovy"
cd "$HERE" && groovy -cp "$B/classes" Harness.groovy
