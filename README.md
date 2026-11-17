# hubitat-code

Custom Hubitat apps and drivers, distributed to my hubs through Hubitat Package Manager (HPM).
This repo's `main` branch is the source of truth: change code here, bump the version, and every hub
that installed the package gets the update from HPM.

## Install on a hub (HPM)

1. Hubitat Package Manager > Package Manager Settings > **Add a Custom Repository**:
   `https://raw.githubusercontent.com/FrankTank34567/hubitat-code/main/repository.json`
2. **Install** > Browse by Tags (or search) and pick the packages that hub needs.
   If the code is already installed by hand, run **Match Up** instead so HPM adopts it.
3. Optional: Package Manager Settings > "Install updates automatically when" > *Always* or *Only those I list*.

## Releasing a change

1. Edit the `.groovy` file and bump its package `version` in `hpm_config.json` (plain `MAJOR.MINOR.PATCH`).
2. Run `python -I build_hpm.py`. It rewrites `repository.json` and `hpm/*/packageManifest.json`,
   and refuses to build if a file changed without a version bump (HPM would never offer it).
3. Commit and push to `main`. `python -I build_hpm.py --check` verifies nothing is stale.

## Apps

- **Curtain Scheduler** – schedules curtains/blinds per room on sunrise/sunset or fixed times, with presence/event triggers and a keep-closed mode.
- **Curtain Scheduler Room** – child app: one room controlled by Curtain Scheduler.
- **Frame Bridge** – publishes selected devices' attributes to the e-paper frame service (blocks credential-like attributes).
- **Home Smoke and CO Response** – three-tier emergency response to smoke/CO alarms and PM2.5 spikes.
- **HRV / Ventilation Fan & Air Quality Manager** – automates ventilation fans and air purifiers from CO2, PM2.5, outdoor air quality, freeze protection and presence.
- **Local Irrigation Manager V3** – local-first irrigation using FAO-56 Penman-Monteith ET, per-zone soil gating and valve monitoring.
- **Modem Power Cycle Watchdog** – restarts the modem via a smart plug when the internet is down and the modem is at fault, with restart-loop guards.
- **Starling Connect** – parent app for Starling Home Hub devices via the documented Starling API (moved here from the Hubitat-Starling repo).
- **UniFi Protect Event Watchdog** – detects a silently dead UniFi Protect event stream from motion-event staleness and recovers it with a rate-limited, capped `initialize()` on the Protect controller.

## Drivers

- **Frame Notifier** – sends notification text to the e-paper frame (configured via its "Frame address" preference).
- **Starling Home Away Control, Lock, Protect, Temperature Sensor, Thermostat, Weather** – child drivers for Starling Connect.
- **Valetudo Robot Vacuum** – MQTT + REST driver for Valetudo robots (Dreame X40 Master/Ultra, MOVA V50); learns each robot's capabilities and blocks unsupported commands. Off-hub tests: `tests/valetudo/run_tests.sh`.

## Archive

- `archive/valetudo-hubitat-driver/` – the former standalone Valetudo repo (driver 3.12.0, 3.11.0 original, its diff, and its separate offline test suite). Kept for history; the live driver is `drivers/Valetudo Robot Vacuum.groovy` (3.13.0).

## Hub sync

`python -I sync_from_hub.py` pulls the code ids in `sync_config.json` from the hub with read-only GETs and commits if anything differs.
The hub address is a per-user setting, not part of the repo: set `HUBITAT_HUB=http://<hub-ip>` or put `{"hub": "http://<hub-ip>"}` in `sync_config.local.json` (git-ignored). `sync_config.json` keeps only the code ids and a placeholder.
Now that GitHub is the source of truth, use it to detect drift (code edited on a hub but not here), not as the normal way to change code.
`MANIFEST.md` lists each file's namespace, header version, hub code id and date pulled.
The sync rewrites `MANIFEST.md` from `sync_config.json` only, so add a file's hub code id there or its row is dropped.
If a sync changes a packaged file, `build_hpm.py` will refuse to build until its version is bumped, which flags the drift.

## Licensing

- No repo-wide license: all rights reserved unless a file says otherwise.
- Starling files are GPL-3.0, as published in the original Hubitat-Starling repo (`licenses/Starling-GPL-3.0.txt`).
- `importUrl` values inside some files point at older/incorrect repo locations; code is kept exactly as the hub has it.

## Linked to epaper-dashboard

**Frame Bridge** and **Frame Notifier** are also shipped by the private epaper-dashboard repo
(`hubitat/FrameBridge.groovy`, `hubitat/FrameNotifierDriver.groovy`), which serves them from its setup page and tests against them.
The two copies must stay identical. After changing either one, update the other and run
`python -I check_epaper_drift.py` (exits 1 on drift).
