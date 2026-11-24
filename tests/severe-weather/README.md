# Advanced Severe Weather Detector tests

Off-hub tests for the fork (1.0.0 freeze changes and Notification Center, 2.0.0 NWS product
table, station-pressure/elevation math, per-sensor quality control, lightning counting, location
moves, °C hubs and the 1.x upgrade). `Harness.groovy` mocks the Hubitat app runtime (settings,
state, clock, notification device) and drives `doEvaluateMatrix()` and the HTTP handlers.

    JAVA_HOME=/path/to/jdk17+ GROOVY_HOME=/path/to/groovy4 ./run_tests.sh

`fixtures/nws_forecast_greatfalls.json` is a real api.weather.gov gridpoint forecast
(TFX 98,144, Great Falls city centre) captured 2026-11-21, tonight's low 3°F.

Pressure checks use Rob's console reading of 2026-11-21 (station pressure 26.62 inHg against
KGFA's 30.12 inHg altimeter, which implies about 1033 m / 3390 ft).
