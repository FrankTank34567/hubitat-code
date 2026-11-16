# Valetudo Robot Vacuum — Hubitat driver

Hubitat Elevation device driver for Valetudo-flashed robot vacuums. MQTT is primary
(real-time state and commands); REST is the fallback and is used for quirks, settings
and segments. All automation lives in Rule Machine.

Current version: **3.12.0** (Valetudo 2026.10.0, Dreame X40 Master + X40 Ultra, firmware 1782).
The full changelog, attribute reference and command list are in the header of
`valetudo_driver.groovy`.

## Files

| File | What it is |
|---|---|
| `valetudo_driver.groovy` | The driver. Paste into Hubitat > Drivers Code. |
| `valetudo_driver_3.11.0_original.groovy` | The previous release, kept for comparison. |
| `valetudo_driver_3.11.0_to_3.12.0.diff` | Unified diff between the two. |
| `tests/` | Offline test harness (no hub, broker or robot needed). |

## Running the tests

Needs a JDK 17 and Groovy 3.x. The harness runs the driver inside a simulated Hubitat
runtime (`tests/HubitatSim.groovy`) using a fake `TESTBOT` robot, replaying recorded
topics from both robots and injecting alerts and commands.

```sh
cd tests
groovy -cp .:stub RunTests.groovy ../valetudo_driver.groovy fixtures
groovy -cp .:stub RunTests.groovy ../valetudo_driver_3.11.0_original.groovy fixtures   # baseline
```

Before any change, run both and diff the replay event streams (pass a third argument
to write them): existing attributes must produce identical events.

## Rule Machine contract

These names are used by live rules and must never be renamed or removed:
`pushAlerts` (notification trigger, compared against `none`), `alertSeverity`
(`fault` = skip the scheduled run), `setFanSpeed`, `setWaterLevel`,
`setOperationMode`, `setSpeakerVolume`, `setFanSpeedTurbo`, `start`, `on`, `dock`.
