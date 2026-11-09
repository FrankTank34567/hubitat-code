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

## Drivers

- **Frame Notifier** – sends notification text to the e-paper frame (configured via its "Frame address" preference).

## Notes

- `importUrl` values inside some files point at older/incorrect repo locations; code is kept exactly as the hub has it.
- No third-party code is included, so there are no upstream attributions. No license (private repo).
