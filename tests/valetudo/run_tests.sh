#!/bin/bash
# Compile the Valetudo driver off-hub and run the mock-based tests against real robot replies.
# Needs Java 17+ and Groovy 4 on PATH (or set JAVA_HOME / GROOVY_HOME).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -n "${GROOVY_HOME:-}" ] && PATH="$GROOVY_HOME/bin:$PATH"
[ -n "${JAVA_HOME:-}" ] && PATH="$JAVA_HOME/bin:$PATH"
B="$(mktemp -d)"; trap 'rm -rf "$B"' EXIT
cp "$HERE/../../drivers/Valetudo Robot Vacuum.groovy" "$B/ValetudoDriver.groovy"
groovyc -d "$B/classes" "$HERE/stub/groovyx/net/http/HttpResponseException.groovy"
groovyc -cp "$B/classes" -d "$B/classes" "$B/ValetudoDriver.groovy"
cd "$HERE" && groovy -cp "$B/classes" Harness.groovy
