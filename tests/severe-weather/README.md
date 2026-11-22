# Advanced Severe Weather Detector tests

Off-hub tests for the freeze changes in fork 1.0.0. `Harness.groovy` mocks the Hubitat app
runtime (settings, state, clock, notification device) and drives `doEvaluateMatrix()`.

    JAVA_HOME=/path/to/jdk17+ GROOVY_HOME=/path/to/groovy4 ./run_tests.sh

`fixtures/nws_forecast_greatfalls.json` is a real api.weather.gov gridpoint forecast
(TFX 98,144, Great Falls city centre) captured 2026-11-21, tonight's low 3°F.

Limit: the app reads the calendar date with `new Date()`, so day-rollover tests seed
`freezeLogDate` with yesterday instead of moving the mock clock across midnight.
