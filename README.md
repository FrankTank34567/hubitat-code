# hubitat-code

Custom Hubitat apps and drivers for my Hubitat C-8 Pro, pulled from the hub (the hub is the source of truth).
Private repo. Refresh with `python -I sync_from_hub.py` (read-only GETs against the hub; commits only if something changed).
`MANIFEST.md` lists each file's namespace, header version, hub code id and date pulled.

## Apps

- **Curtain Scheduler** – schedules curtains/blinds per room on sunrise/sunset or fixed times, with presence/event triggers and a keep-closed mode.
- **Curtain Scheduler Room** – child app: one room controlled by Curtain Scheduler.
- **Day Lights** – adjusts device color temperature, color and/or brightness during daylight hours.
- **Frame Bridge** – publishes selected devices' attributes to the e-paper frame service (blocks credential-like attributes).
- **Home Smoke and CO Response** – three-tier emergency response to smoke/CO alarms and PM2.5 spikes.
- **HRV / Ventilation Fan & Air Quality Manager** – automates ventilation fans and air purifiers from CO2, PM2.5, outdoor air quality, freeze protection and presence.
- **Local Irrigation Manager V3** – local-first irrigation using FAO-56 Penman-Monteith ET, per-zone soil gating and valve monitoring.
- **Modem Power Cycle Watchdog** – restarts the modem via a smart plug when the internet is down and the modem is at fault, with restart-loop guards.
- **UniFi Protect Event Watchdog** – detects a silently dead UniFi Protect event stream from motion-event staleness and recovers it with a rate-limited, capped `initialize()` on the Protect controller. v3.0.0 was written in this repo from an audit of v2 and is not on the hub yet: after installing it, add its code id to `sync_config.json` so the hub becomes the source of truth again.

## Drivers

- **Frame Notifier** – sends notification text to the e-paper frame (configured via its "Frame address" preference).
- **Valetudo Robot Vacuum** – MQTT + REST driver for Valetudo robots (Dreame X40 Master/Ultra, MOVA V50); learns each robot's capabilities and blocks unsupported commands. Off-hub tests: `tests/valetudo/run_tests.sh`.

## Notes

- `importUrl` values inside some files point at older/incorrect repo locations; code is kept exactly as the hub has it.
- No third-party code is included, so there are no upstream attributions. No license (private repo).

## Linked to epaper-dashboard

**Frame Bridge** and **Frame Notifier** are also shipped by
[epaper-dashboard](https://github.com/FrankTank34567/epaper-dashboard) (`hubitat/FrameBridge.groovy`,
`hubitat/FrameNotifierDriver.groovy`), which serves them from its setup page and tests against them.
The two copies must stay identical. After changing either one, update the other and run
`python -I check_epaper_drift.py` (exits 1 on drift).
