# Valetudo driver tests (off-hub)

`./run_tests.sh` compiles `drivers/Valetudo Robot Vacuum.groovy` with plain Groovy and runs
`Harness.groovy`: the Hubitat runtime (`sendEvent`, `state`, `settings`, `httpGet`, MQTT) is mocked,
and REST GETs are answered from `fixtures/`, captured read-only on 2026-11-14 from the real robots
(`v50.json` = MOVA V50 Ultra, `x40u.json` = Dreame X40 Ultra, both Valetudo 2026.10.0).
`stub/` stands in for `groovyx.net.http.HttpResponseException`, which the hub provides.

Covers discovery, blocked/allowed commands and values, Fan Speed Turbo per robot, the
Charging Limit "charged" logic over MQTT and REST, alert classification, enforceSupported off,
and skipped capabilities. It does not exercise the real hub (scheduling, state persistence).

Requires Java 17+ and Groovy 4 (tested with Temurin 21.0.13 + Groovy 4.0.27).
