import groovy.transform.Field

/**
 * HRV / Ventilation Fan & Air Quality Manager
 * ============================================================
 * Automates any ventilation fan or HRV and room air purifiers
 * based on indoor air quality, outdoor conditions, freeze
 * protection, and household presence. Works without an HRV —
 * can operate as a standalone air quality manager using only
 * air purifiers.
 *
 * STANDARDS AND SOURCES CITED IN THIS APP:
 *
 *   ASHRAE 62.2-2022
 *     National standard for residential mechanical ventilation.
 *     Establishes minimum ventilation rates and demand-controlled
 *     ventilation principles used throughout this app.
 *     https://www.ashrae.org/technical-resources/bookstore/standards-62-1-62-2
 *
 *   ASHRAE Guideline 44-2024
 *     "Protecting Building Occupants from Smoke During Wildfire
 *      and Prescribed Burn Events." Key guidance: minimize outdoor
 *      air intake during smoke; use portable air cleaners as
 *      mitigation; acknowledge CO₂ tradeoff.
 *     https://www.ashrae.org/technical-resources/wildfire-response-resources
 *
 *   Washington State DOH / EPA Wildfire IAQ Guidance
 *     DOH 333-208: "During long-term smoke events, bring in outside
 *     air during periods of improved air quality. Monitor CO₂ with
 *     the goal of keeping levels below 800 ppm. If levels are
 *     frequently above 1000 ppm, consider increasing outdoor air
 *     ventilation." This is the basis for the CO₂ emergency override.
 *     https://doh.wa.gov/sites/default/files/legacy/Documents/Pubs/333-208.pdf
 *
 *   EPA AirNow API
 *     Official US government outdoor air quality data. Operated by
 *     EPA Office of Air and Radiation, jointly with NOAA, CDC, NASA,
 *     and National Park Service. Updated hourly, 2500+ stations.
 *     Free API key: https://docs.airnowapi.org
 *     Data catalogue: https://catalog.data.gov (search "AirNow")
 *
 *   EPA AQI Breakpoints
 *     (2024 revision, effective 2024-05-06)
 *     Good: 0-50 (PM2.5 0.0-9.0 µg/m³)
 *     Moderate: 51-100 (PM2.5 9.1-35.4 µg/m³)
 *     Unhealthy for Sensitive Groups: 101-150 (35.5-55.4 µg/m³)
 *     Unhealthy: 151-200 (55.5-125.4 µg/m³)
 *     Default outdoor guard: 35.4 µg/m³ (AQI 100 boundary, top of Moderate)
 *
 *   WHO Global Air Quality Guidelines 2021
 *     PM2.5 24-hour guideline 15 µg/m³, annual 5 µg/m³ — roughly twice as
 *     protective as the EPA 24-hour figure. Used as the indoor PM2.5 default,
 *     since indoor air is far more controllable than outdoor air.
 *     https://www.who.int/publications/i/item/9789240034228
 *     https://www.airnow.gov/aqi/aqi-basics/
 *
 *   CCHRC Cold Climate HRV Guidance
 *     Minimum operating temperature for heat exchanger core
 *     protection. General cold-climate industry guidance: 14-15°F
 *     (-10°C). App defaults to 15°F.
 *     https://yournorthernhome.cchrc.org/heat-recovery-ventilators
 *
 * HOW THIS APP WORKS — DECISION PRIORITY ORDER:
 *   See the "How This App Works" page in the app UI for a full
 *   plain-English explanation. Brief summary:
 *
 *   PRIORITY 1 — CO₂ EMERGENCY OVERRIDE (WA DOH DOH-333-208)
 *     Guard locked out but CO₂ critical → brief ventilation run
 *
 *   PRIORITY 2 — SAFETY GUARDS
 *     Wildfire: outdoor PM2.5 > threshold → fan OFF, purifiers ON
 *     Freeze: outdoor temp < threshold → fan OFF, purifiers ON
 *     (ASHRAE GL44-2024; CCHRC; manufacturer guidance)
 *
 *   PRIORITY 3 — PRESENCE (ASHRAE 62.2-2022 demand-controlled)
 *     Everyone away + disabled → fan OFF, purifiers OFF
 *
 *   PRIORITY 4 — AIR QUALITY RESPONSE
 *     CO₂ high → fan ON (only fresh air exchange fixes CO₂)
 *     PM2.5 high + outdoor OK → fan ON + purifiers
 *     PM2.5 high + guard active → purifiers only + alert
 *     All clear → fan OFF, purifiers OFF
 *
 *   PRIORITY 5 — TIMING GUARDS
 *     Min run, cooldown, max run
 *
 * MANUAL OVERRIDE BEHAVIOR (user-selectable):
 *   If the ventilation fan switch is changed by the user (not
 *   by this app), the app detects the mismatch and responds per
 *   the user's configured policy: resume after N minutes, pause
 *   until next sensor event, or pause indefinitely.
 *
 * Author:      Frank Tank (GitHub: FrankTank34567)
 * Version:     3.15.1 (2026-08-16)
 * License:     Apache 2.0
 *
 * Developed with Claude (Anthropic) — AI-assisted design and
 * implementation. Architecture, threshold values, guard logic,
 * AirNow integration, TTS/notification system, manual override
 * detection, per-device purifier policy engine, and all standards
 * citations were developed collaboratively through an extended
 * design session.
 *
 * Hardware context: an HRV or ventilation fan on a switched relay, indoor
 * CO2/PM2.5 sensors, an outdoor PM2.5 sensor and a local weather
 * station.
 *
 * CHANGELOG:
 *  3.15.1 - 2026-08-16 - Full-app audit (function + security). Verified the
 *           Apply Recommended Settings button writes only real input names
 *           read by the app; all 20 notification events have default TTS
 *           text, tuned cooldowns and complete token maps; every threshold
 *           pair is internally consistent under the 3.15 defaults (run>stop,
 *           emergency>run, guard release 25 above indoor run 15); no dead
 *           methods, unused constants, plaintext HTTP, sandbox-forbidden
 *           calls or key leakage; continuous mode returns before humidity
 *           logic so no stale humidity alerts; the emergency-vent PM monitor
 *           is unscheduled when the vent ends. Surfaced the last write-only
 *           state (indoor/outdoor dew points and the humidity decision
 *           reason now show on the diagnostics humidity line).
 *  3.15.0 - 2026-08-14 - Adopted the more health-protective end of current
 *           guidance as the shipped defaults:
 *             indoor PM2.5 run  35 -> 15  (WHO 2021 24-hour guideline)
 *             indoor PM2.5 stop 15 ->  9  (EPA 2024 'Good' ceiling)
 *             outdoor guard     55 -> 35  (AQI 100, top of Moderate)
 *             CO2 run         1000 -> 800 (WA DOH: keep below 800 ppm)
 *             CO2 stop         700 -> 600
 *             CO2 override after 240 -> 120 min
 *             override PM abort   5 -> 3 ug/m3
 *           The outdoor guard change is the consequential one: measured
 *           against logged wildfire smoke-season readings it raises fan
 *           lockout from roughly 38% to 62% of the time. That is the intended
 *           trade per ASHRAE Guideline 44-2024 (minimise outdoor air during
 *           smoke, rely on portable air cleaners), but it means CO2 climbs
 *           with no way to vent more often — hence the emergency override
 *           becoming available at 120 minutes instead of 240, and the tighter
 *           3 ug/m3 abort margin so an override that does import particulate
 *           is cut short sooner.
 *           Because Hubitat keeps saved settings across app updates, these
 *           new defaults do NOT reach existing installs on their own. Added
 *           an 'Apply Recommended Settings' button on the main page that
 *           rewrites exactly these seven values and nothing else.
 *  3.14.0 - 2026-08-14 - Best-practice audit. CORRECTED THE EPA AQI
 *           BREAKPOINTS, which were obsolete. EPA revised them effective
 *           2024-05-06 alongside the annual PM2.5 NAAQS change (12.0 ->
 *           9.0 ug/m3): Good became 0.0-9.0 (was 0.0-12.0), Moderate 9.1-35.4
 *           (was 12.1-35.4), Unhealthy 55.5-125.4 (was 55.5-150.4). The app
 *           still used the pre-2024 table AND capped output at 150.5 above
 *           AQI 200, so severe smoke was badly understated: a logged AQI of
 *           317 was converted to 150.5 ug/m3 instead of ~233. aqiToPm25() now
 *           uses the 2024 breakpoints and covers the full range to AQI 500.
 *           Also documented the two threshold defaults where the standards
 *           genuinely disagree: the indoor PM2.5 default of 35 matches the
 *           EPA 24-hour figure but is roughly half as protective as the WHO
 *           2021 guideline of 15, and the outdoor guard default of 55 means
 *           the fan runs until air is nearly Unhealthy (AQI 150). Both are
 *           now explained in the UI with the trade-offs, rather than being
 *           silently changed.
 *  3.13.1 - 2026-08-14 - Made the air-quality-over-humidity precedence
 *           explicit. ASHRAE separates these deliberately: the ventilation
 *           rate required for IAQ 'does not allow for humidity control.
 *           Additional ventilation or dehumidification may be required to
 *           remove moisture.' CO2/PM ventilation protects occupants now and
 *           is never reduced or delayed to manage moisture; the standards'
 *           answer to ventilation-imported humidity is supplemental
 *           dehumidification. The code already behaved this way but only
 *           implicitly; air-quality demand is now evaluated first as its own
 *           named condition, and humidity is documented as an opportunistic
 *           add-on that can start the fan but never blocks or shortens an
 *           air-quality run. When CO2 forces ventilation into humid outdoor
 *           air, that moisture-import trade is now logged (max once per 6h)
 *           so it is visible rather than silent. Documented the interaction
 *           on the Humidity page and added humidity to the priority list in
 *           'How This App Works'. Confirmed the new humidityCannotVent alert
 *           is a first-class event with per-event push/TTS toggles, editable
 *           wording and a 12-hour cooldown, like every other notification.
 *  3.13.0 - 2026-08-14 - Humidity control now reasons about ABSOLUTE
 *           moisture (dew point), not just relative humidity.
 *           THE FLAW: the feature compared indoor RH against an outdoor-
 *           TEMPERATURE table. That is correct for winter condensation but
 *           wrong in mild weather. Observed in the field: indoor 62% RH at
 *           70F (dew point 56F) with outdoor 56F at 96% RH during a rain
 *           event (dew point 55F). The app wanted to run the fan to 'dry the
 *           air' while the incoming air was just as wet — removing nothing,
 *           and adding moisture had the outdoor air warmed while saturated.
 *           THE FIX: dew point is computed for indoor and outdoor air
 *           (Magnus-Tetens) and the fan runs for humidity only when outdoor
 *           air is genuinely drier by a configurable margin (default 3F).
 *           Below 45F outdoor the check is skipped since cold air is always
 *           absolutely dry. In mild weather with no outdoor humidity sensor
 *           the app refuses to vent rather than guess. Added an absolute
 *           indoor dew point ceiling (default 55F, the ASHRAE hot-and-humid
 *           guide and EPA prudent maximum) which triggers drying even when
 *           RH looks acceptable. When humidity is high but ventilating cannot
 *           help, the app now says so and points to dehumidification instead
 *           of silently doing nothing. New optional outdoor humidity sensor
 *           input; feature renamed from 'Winter Humidity Control' since it
 *           now governs all seasons.
 *           Sources: ASHRAE Guide for Buildings in Hot and Humid Climates and
 *           EPA Moisture Control in Public and Commercial Buildings (55F max
 *           indoor dew point); Building Science / GreenBuildingAdvisor
 *           guidance that ventilation dries only while outdoor dew point is
 *           at or below ~55F.
 *  3.12.0 - 2026-08-06 - Anti-flapping and reporting fixes, all driven by
 *           logged field data from a real smoke event.
 *           FLAPPING: the wildfire guard tripped and cleared twice in 90
 *           minutes (57->47->57->47), switching the fan and all four
 *           purifiers each time — 16 relay operations. Root cause: the two
 *           outdoor sources disagreed by 35-47 ug/m3, so the worse-of-the-two
 *           jumped as they alternated, and the 5 ug/m3 deadband (release at
 *           50) let 47 through. Deadband raised to 10 (release at 45) and a
 *           minimum guard hold time added (default 20 min). Both are now
 *           user-configurable. Replayed against the logged sequence: 16 relay
 *           operations become 4.
 *           SOURCE LABEL: outdoorPmHandler hardcoded the source to 'local'
 *           before resolveOutdoorPm25() decided which reading actually won,
 *           so diagnostics reported 'source: local' while displaying AirNow's
 *           value. The label is now set by whichever reading is used.
 *           AIRNOW GLITCH: a logged sample collapsed from AQI 53 to 8 and
 *           back to 43 within two hours. Hourly regional data does not drop
 *           that far in one sample, and a spurious low value could falsely
 *           clear the guard. Such a collapse is now rejected.
 *           ROUNDING: guard log lines and the diagnostics effective-PM value
 *           no longer print raw floats (67.12041, 25.889795).
 *  3.11.0 - 2026-07-28 - Full cleanup, security and function audit.
 *           CLEANUP: removed two dead methods (resumeFromPurifierOverride,
 *           startPurifiers) that were superseded and never called; removed
 *           the duplicate co2Rising throttle now handled centrally by
 *           notify(); every previously write-only state key now serves a
 *           purpose (AirNow category shown in diagnostics, action-day flag
 *           surfaced, last-service date shown, local-sensor timestamp used).
 *           SECURITY: hub coordinates are explicitly coerced to Double before
 *           entering the request URL; API-supplied category and message
 *           strings are sanitized and length-capped before being stored,
 *           logged or rendered. Confirmed: API key is never logged, never
 *           rendered, format-validated before use, and sent only over HTTPS.
 *           FUNCTION: verified no code path can bypass the guards or the
 *           manual-override wrappers; added a stale-local-sensor fallback so
 *           a dead outdoor sensor no longer pins the effective PM2.5 reading
 *           forever (falls back to AirNow after 60 min); tuned notification
 *           cooldowns for the five events that were falling to the default.
 *  3.10.0 - 2026-07-28 - Notification redesign to prevent alert fatigue.
 *           A single guard event previously sent 6+ pushes (one per purifier
 *           plus the guard itself), with raw values like 43.216328 and a
 *           standards citation burying the actual action.
 *           Now: (a) messages lead with WHAT HAPPENED and WHAT THE APP DID
 *           on the first line, with the reason second; (b) all notification
 *           numbers are rounded to whole units; (c) purifier starts from one
 *           guard event are batched into a single summary instead of one per
 *           device; (d) routine chatter (fan start/stop, per-purifier) is now
 *           OFF by default since the guard message already states what was
 *           turned on or off; (e) TTS defaults narrowed to urgent events only;
 *           (f) a central per-event cooldown in notify() structurally prevents
 *           a flapping condition from burying the phone.
 *   3.9.2 - 2026-07-27 - Fixed false 'stuck reading' sensor faults. Root
 *           cause: validSensorReadings() is invoked 6+ times per
 *           evaluateConditions() (maxIndoorCo2, indoorPm25High, and several
 *           conditionsRequireFan calls), and EVERY call appended to the
 *           stuck-detection history — so the detector counted repeated reads
 *           of the same cached value instead of actual sensor updates. With
 *           re-evaluation every 10 min, 30 identical entries accumulated in
 *           under an hour on a perfectly healthy sensor. validateSensorReading
 *           now takes the attribute's last-update timestamp and only advances
 *           the stuck history and the recovery counter when the reading is
 *           genuinely new. Existing polluted history is cleared on update.
 *   3.9.1 - 2026-07-27 - Fixed a compile error introduced in 3.9.0: an
 *           edit had stripped a newline, welding the wfTime assignment onto
 *           the following paragraph statement in the diagnostics page
 *           ("expecting '}', found 'paragraph'"). Also documented that the
 *           ventAborted flag is an intentional last-result latch reset at the
 *           start of each override, not a control gate that could stick.
 *   3.9.0 - 2026-07-27 - Three new features.
 *           (1) WINTER HUMIDITY CONTROL (opt-in): runs the fan to dry
 *           the air when indoor RH exceeds a temperature-indexed ceiling
 *           (colder outside = lower safe indoor RH), preventing window
 *           condensation and mold. Auto table by default (40F/45% down to
 *           -20F/15%, per U of MN Extension and CMHC guidance) with an
 *           optional simple custom threshold. Respects all guards.
 *           (2) MAINTENANCE REMINDERS (opt-in): accumulates fan runtime
 *           (daily tick covers continuous mode) and alerts at a settable
 *           interval, default 2160 runtime hours, with a reset button.
 *           (3) DASHBOARD STATUS DEVICE (opt-in): optional child device
 *           exposing status text, PM2.5, CO2, humidity and fan state for
 *           Hubitat Dashboard tiles; removed on uninstall.
 *           Also: CO2 EMERGENCY OVERRIDE IS NOW ADAPTIVE. It samples
 *           indoor PM2.5 each minute during the run and aborts early if
 *           PM rises past a settable margin (default 5 ug/m3), i.e. if
 *           the HRV filter is not holding outdoor particulate back. The
 *           measured rise is logged and shown in diagnostics, so the
 *           override doubles as a real test of filter performance.
 *   3.8.1 - 2026-07-27 - Fixed sensor fault/recovery flapping. Fault
 *           state was keyed per-device but raised and recovered per
 *           attribute, so a stuck CO2 reading would fault, then a valid
 *           PM2.5 reading on the same device would 'recover' it, then
 *           CO2 (still stuck) re-faulted — looping dozens of times and
 *           spamming the log/notifications. Fault state is now
 *           per-attribute: a stuck CO2 sensor stays faulted (CO2 only,
 *           excluded from automation) while PM2.5 keeps working, and it
 *           clears only when CO2 itself recovers. Diagnostics now names
 *           which attribute is faulted.
 *   3.8.0 - 2026-06-24 - Full-app audit hardening. Guard hysteresis added:
 *           the wildfire guard now clears only after outdoor PM2.5 drops
 *           5 ug/m3 below the threshold, and the freeze guard clears only
 *           after temp rises 2F above its threshold. Previously a reading
 *           hovering at the boundary flipped the guard (and its push/TTS
 *           notifications) on and off with every sample. Numeric safety:
 *           replaced .toInteger() on raw CO2 readings with BigDecimal
 *           comparisons (a decimal string like '532.0' would have thrown
 *           NumberFormatException); outdoor PM/temp handlers and
 *           validSensorReadings() now ignore non-numeric sensor values
 *           instead of aborting on them. Audited all stopFan paths, all
 *           scheduled/subscribed handler names, time-window parsing,
 *           token coverage, and sandbox-forbidden calls; all clean.
 *   3.7.7 - 2026-06-24 - Fixed continuous mode wrongly stopping after an
 *           arrival. Arrival ventilation is a demand-mode concept (run
 *           the fan a while when someone gets home); in continuous mode
 *           it was running a 30-min timed cycle that then STOPPED the
 *           fan. Now, on arrival in continuous mode the app just
 *           re-evaluates (fan keeps running), and arrivalVentStop()
 *           refuses to stop the fan in continuous mode even if reached by
 *           a stale timer or mid-cycle mode change. Arrival UI now notes
 *           it doesn't apply in continuous mode.
 *   3.7.6 - 2026-06-20 - Adaptive forecast poll cadence. When an area has
 *           no forecast coverage, polling backs off from twice daily to a
 *           weekly heartbeat (Mondays) — light enough to notice if
 *           coverage ever appears, without hammering a dead endpoint. The
 *           moment a real forecast returns, the no-coverage flag clears
 *           and twice-daily polling resumes automatically. Cadence is set
 *           by scheduleForecastPolling(), called from initialize() and
 *           whenever the coverage state flips.
 *   3.7.5 - 2026-06-20 - Handle AirNow 'no forecast coverage' areas. Some
 *           reporting areas have no forecasts at
 *           all — AirNow returns a {Message: 'Error ...'} object. The app
 *           now detects this, sets a no-coverage flag, and surfaces it in
 *           the forecast settings page and diagnostics so the feature
 *           isn't silently inert. Current-conditions polling is
 *           unaffected and continues to drive the wildfire guard.
 *   3.7.4 - 2026-06-20 - Fixed observation category showing 'Unknown'
 *           (the field is aqiCategoryName, confirmed from a live
 *           response; added it as the primary lookup). Added a 30-second
 *           timeout to both AirNow calls — the forecast endpoint was
 *           occasionally hitting the default ~10s timeout ('Read timed
 *           out'). A timed-out or missing forecast is harmless: it only
 *           affects the proactive-flush threshold, never the guards.
 *   3.7.3 - 2026-06-20 - More robust AirNow parameter matching. PM2.5 is
 *           now matched by normalizing the parameter name (PM2.5 / PM25
 *           / pm2.5 all match), and extra field-name candidates are
 *           checked for the parameter, AQI, and category. Added debug
 *           logging of the first response row's keys so the actual field
 *           names returned by the endpoint can be confirmed. Note: if a
 *           forecast row for PM2.5 simply isn't issued (ozone-only day),
 *           that is expected, not an error.
 *   3.7.2 - 2026-06-20 - Removed getClass() from the two payload-shape
 *           fallback log messages; the Hubitat Groovy sandbox forbids
 *           getClass() and rejected the app at compile time. The log
 *           now names the condition without the runtime type.
 *   3.7.1 - 2026-06-20 - Fixed forecast poll crash ('No such property:
 *           parameterName'). The forecast endpoint can return a Map-
 *           shaped payload, not just a List; iterating a Map yielded
 *           Map.Entry objects with no parameterName. Both the forecast
 *           and observation parsers now normalize the payload to a List
 *           of rows first, handling List, Map-wrapping-a-list, and
 *           single-row-Map shapes. Diagnostics now flags 'enabled but
 *           not yet polled — tap Done' so the feature isn't silently
 *           inactive until the next scheduled poll.
 *   3.7.0 - 2026-06-17 - Optional forecast-based proactive ventilation
 *           (opt-in, off by default). Polls AirNow 'Current Forecasts'
 *           twice daily; when poor air is forecast for the reporting
 *           area (PM2.5 AQI over threshold, or an action day), the app
 *           lowers the CO2 run threshold so it flushes CO2 proactively
 *           while outdoor air is still acceptable, ahead of forecast
 *           smoke. Never drives the wildfire guard and never forces the
 *           fan regardless of CO2 — current readings still gate the
 *           lockout. No effect in continuous mode (fan already runs).
 *           Per WA DOH DOH-333-208 (ventilate during better-air periods).
 *   3.6.0 - 2026-06-17 - AirNow API migration. EPA retired the
 *           /aq/observation/latLong/current/ endpoint on 2026-09-30;
 *           switched to the replacement 'Current Observations by Zip
 *           Code or Lat/Long' service (/aq/observation/current/
 *           ziplatLong). New JSON schema: parameterName / nowcastAQI /
 *           AQICategoryName (was ParameterName / AQI / CategoryName).
 *           Parser reads the new fields with a fallback to the legacy
 *           names for the transition window, matches PM2.5 case-
 *           insensitively, and skips AQI=-1 (no-valid-reading) responses.
 *           Dropped the unsupported distance parameter.
 *   3.5.0 - 2026-06-01 - Editable voice announcements. A configurable
 *           TTS prefix (default 'Attention, attention, attention...')
 *           is prepended to every spoken message to defeat the
 *           first-word clipping common on Sonos/Echo/Google as they
 *           wake. Every announcement is now viewable and fully editable
 *           on a new Announcement Messages page, using {token}
 *           placeholders for live values (CO2, temps, fan name, etc.).
 *           Editing wording never breaks playback — a removed token
 *           just omits that value.
 *   3.4.0 - 2026-06-01 - HRV operating modes: continuous (24/7, the
 *           ASHRAE 62.2 model and default), demand (run on air-quality
 *           trigger), and scheduled (continuous during set hours).
 *           Continuous-mode presence behavior is selectable: keep
 *           running / drop to demand / turn off when away. Removed spike
 *           detection entirely (these sensors are naturally spiky — it
 *           only produced false alarms). Stuck count, stuck min-value,
 *           and stale window are now user-configurable on a new Sensor
 *           Validation page, each individually disable-able. Max-run
 *           safety net is skipped in continuous/scheduled modes (the fan
 *           is supposed to run indefinitely there).
 *   3.3.1 - 2026-06-01 - Fixed false sensor-fault alarms. Spike check
 *           no longer raises a fault — a single large jump is almost
 *           always real (cooking, breathing near sensor, fan cycle),
 *           so outliers are accepted as the new baseline, not flagged.
 *           Stuck detection now requires 30 identical readings AND a
 *           value above 20 µg/m³ (clean air is legitimately stable at
 *           low values — that is not a fault). Stale window raised to
 *           30 min for battery sensors. Sensor faults are informational
 *           (push-only by default, respect quiet hours), never safety
 *           alarms that override quiet hours.
 *   3.3.0 - 2026-06-01 - Bug fixes + security hardening: app label
 *           now rebuilds from a stored clean base name (fixes badge
 *           stacking); stale detection moved to a scheduled check
 *           (per-reading validation can't detect a silent sensor);
 *           stuck-detection rounds values to avoid float serialization
 *           mismatches; AirNow API key format-validated before use.
 *   3.2.0 - 2026-06-01 - Indoor sensor validation engine: range,
 *           spike, stuck-reading, and stale detection. Faulted
 *           sensors excluded from automation; auto-recover after
 *           3 valid readings. Outdoor source disagreement check:
 *           uses worse reading, notifies when local vs AirNow
 *           differ by >35 µg/m³. Dynamic app label in Hubitat
 *           installed-apps list showing highest-severity status.
 *   3.1.0 - 2026-06-01 - Per-device purifier policy engine:
 *           auto, manual override respected, always on (never
 *           turn off), and scheduled (app controls during set
 *           hours only, leaves alone outside). Per-device manual
 *           override detection with timed/nexteval/indefinite
 *           resume. Safety guard overrides schedule for wildfire.
 *   3.0.0 - 2026-06-01 - Added TTS + push notification system
 *           with quiet hours and per-event selection; manual
 *           override detection with user-selectable policy;
 *           HRV-optional mode (air quality manager without fan);
 *           app renamed to reflect support for any ventilation fan
 *   2.0.0 - 2026-06-01 - Air purifier control, AirNow fallback,
 *           CO₂ emergency override, presence support
 *   1.0.0 - 2026-06-01 - Initial release
 * ============================================================
 */

definition(
    name:           "HRV / Ventilation Fan & Air Quality Manager",
    namespace:      "franktank",
    author:         "Frank Tank",
    description:    "Automates any ventilation fan (HRV, ERV, inline fan, or bath fan) and " +
                    "room air purifiers based on CO₂, PM2.5, outdoor air quality, freeze " +
                    "protection, and presence. Works without a fan as a standalone air " +
                    "quality manager. Implements ASHRAE 62.2, ASHRAE GL44-2024, and EPA/WA DOH guidance.",
    category:       "Green Living",
    iconUrl:        "",
    iconX2Url:      "",
    importUrl:      "https://raw.githubusercontent.com/FrankTank34567/hubitat-hrv/main/HRVSmartVentilationManager.groovy",
    singleInstance: false
)

// ============================================================
// CONSTANTS — all defaults based on cited standards
// ============================================================

@Field static final String  APP_VERSION             = "3.15.1"

// Indoor CO₂ (ASHRAE 62.2; WA DOH)
@Field static final Integer DEFAULT_CO2_HIGH        = 800   // ppm — run fan (WA DOH goal: keep below 800)
@Field static final Integer DEFAULT_CO2_LOW         = 600   // ppm — stop fan
@Field static final Integer DEFAULT_CO2_EMERGENCY   = 1500  // ppm — override guard

// Indoor PM2.5 (EPA AQI breakpoints)
@Field static final Integer DEFAULT_PM25_IN_HIGH    = 15    // µg/m³ — run fan/purifiers (WHO 2021 24-hour guideline)
@Field static final Integer DEFAULT_PM25_IN_LOW     = 9     // µg/m³ — stop (EPA 2024 "Good" ceiling / annual NAAQS)

// Outdoor guards
@Field static final Integer DEFAULT_PM25_OUT_MAX    = 35    // µg/m³ — wildfire lockout (AQI 100, top of Moderate)
@Field static final Integer DEFAULT_TEMP_MIN        = 15    // °F    — freeze lockout
// Guard hysteresis (deadband). Guards activate AT the threshold but only clear
// after moving past it by this margin, so a reading hovering at the boundary
// doesn't flip the guard (and its notifications) on/off with every sample.
// Deadband sized from observed field data: during a smoke event the effective
// reading swung 57 -> 47 -> 57 -> 47 within 90 minutes (the two outdoor sources
// disagreed by 35-47 µg/m³, so the "worse of the two" jumped as they alternated).
// A 5 µg/m³ deadband released at 50 and let that through; 10 releases at 45.
@Field static final Integer GUARD_HYST_PM25         = 10    // µg/m³ below threshold to clear wildfire guard
@Field static final Integer DEFAULT_GUARD_MIN_MIN   = 20    // min the wildfire guard stays active before it may clear
@Field static final Integer GUARD_HYST_TEMP         = 2     // °F above threshold to clear freeze guard

// Forecast-based proactive ventilation (opt-in)
@Field static final Integer DEFAULT_FORECAST_AQI    = 101   // forecast PM2.5 AQI ≥ this = bad-air day (USG boundary)

// Winter humidity control (opt-in). Temperature-indexed indoor RH ceiling:
// colder outside → lower allowed indoor RH before condensation/mold risk.
// Sources: University of Minnesota Extension "Excess Moisture in the Home";
// Canadian CMHC / Householder's Guide condensation tables. Values are the
// recommended MAX indoor RH% at or above the given outdoor temperature (°F).
// When indoor RH exceeds the target for the current outdoor temp, ventilate.
@Field static final List HUMIDITY_TABLE = [
    [tempF:  40, maxRh: 45],   // 40°F and warmer: up to 45%
    [tempF:  30, maxRh: 40],   // 30–39°F: 40%
    [tempF:  20, maxRh: 35],   // 20–29°F: 35%
    [tempF:  10, maxRh: 30],   // 10–19°F: 30%
    [tempF:   0, maxRh: 25],   // 0–9°F: 25%
    [tempF: -10, maxRh: 20],   // -10–-1°F: 20%
    [tempF: -20, maxRh: 15],   // -20°F and colder: 15%
]
@Field static final Integer HUMIDITY_HYST_RH        = 3     // RH% below target to clear humidity-vent demand
// Ventilation only DRIES the house when outdoor air holds less absolute
// moisture than indoor air. Relative humidity cannot tell you that — dew point
// can. Outdoor air must be at least this many °F drier (by dew point) before
// running the fan is worth it. Sources: ASHRAE Guide for Buildings in Hot and
// Humid Climates and EPA "Moisture Control in Public and Commercial Buildings"
// (55°F prudent maximum indoor dew point); Building Science / GreenBuilding
// Advisor guidance that ventilation dries only while outdoor dew point <= 55°F.
@Field static final Integer DEWPOINT_MARGIN_F       = 3     // °F outdoor must be drier before venting for humidity
@Field static final Integer MAX_INDOOR_DEWPOINT_F   = 55    // °F — ASHRAE/EPA prudent indoor maximum
@Field static final Integer DEWPOINT_CHECK_ABOVE_F  = 45    // °F outdoor — below this, cold air is always drier; skip the check

// Filter / core maintenance reminder (opt-in). Default interval in runtime hours.
@Field static final Integer DEFAULT_VENT_PM_ABORT   = 3     // ug/m3 indoor PM2.5 rise during a CO2 override that aborts the run
@Field static final Integer DEFAULT_MAINT_HOURS     = 2160  // ~90 days of continuous run; typical HRV filter/core check

// Timing
@Field static final Integer DEFAULT_MIN_RUN         = 10    // min
@Field static final Integer DEFAULT_COOLDOWN        = 20    // min
@Field static final Integer DEFAULT_MAX_RUN         = 240   // min

// CO₂ emergency override (WA DOH)
@Field static final Integer DEFAULT_EMERGENCY_AFTER = 120   // min — guard active before override (shortened: the tighter outdoor guard locks out more often)
@Field static final Integer DEFAULT_EMERGENCY_DUR   = 20    // min — override run duration

// Sensor validation thresholds (based on WH46D spec + real-world residential limits)
@Field static final Integer CO2_MIN_PLAUSIBLE       = 300   // ppm  — below outdoor baseline; impossible indoors
@Field static final Integer CO2_MAX_PLAUSIBLE       = 8000  // ppm  — above this in residence = sensor fault
@Field static final Integer PM25_MIN_PLAUSIBLE      = 0     // µg/m³
@Field static final Integer PM25_MAX_PLAUSIBLE      = 500   // µg/m³ — above this indoors = sensor fault (not structure fire)
@Field static final Integer DEFAULT_STUCK_COUNT     = 30    // identical readings = stuck (clean air is stable, so must be long)
@Field static final Integer DEFAULT_STUCK_MIN_VALUE = 20    // only flag stuck above this; low stable values are normal
@Field static final Integer DEFAULT_STALE_MINUTES   = 30    // min — no update = stale (user-configurable)
@Field static final Integer SENSOR_RECOVER_COUNT    = 3     // consecutive valid readings to un-flag

// Outdoor sensor disagreement threshold
@Field static final Integer OUTDOOR_DISAGREE_MARGIN = 35    // µg/m³ — ~1 AQI category; triggers notification

// App label badge strings — single highest-severity badge shown at a time
// Priority order (high → low): guards > manual override > sensor fault > running > normal
@Field static final String LABEL_WILDFIRE    = " <font color='red'>🔥 Wildfire Guard</font>"
@Field static final String LABEL_FREEZE      = " <font color='red'>❄️ Freeze Guard</font>"
@Field static final String LABEL_SENSOR_FAULT= " <font color='red'>⚠️ Sensor Fault</font>"
@Field static final String LABEL_MANUAL_FAN  = " <font color='orange'>🖐️ Manual Override</font>"
@Field static final String LABEL_CO2_OVERRIDE= " <font color='orange'>🆘 CO₂ Override</font>"
@Field static final String LABEL_RUNNING     = " <font color='blue'>💨 Running</font>"
@Field static final String LABEL_PURIFIERS   = " <font color='blue'>🌀 Purifiers</font>"
@Field static final String LABEL_DISABLED    = " <font color='gray'>⏸ Disabled</font>"
@Field static final String LABEL_OK          = " <font color='green'>✅ OK</font>"

// AirNow
// AirNow — "Current Observations by Zip Code or Lat/Long" service.
// Replaces the legacy /aq/observation/latLong/current/ endpoint, which AirNow
// retired on 2026-09-30. New service base URL and JSON field names differ
// (parameterName / nowcastAQI / AQICategoryName); see pollAirNow().
@Field static final String  AIRNOW_URL              = "https://www.airnowapi.org/aq/observation/current/ziplatLong"

// AirNow "Current Forecasts" service — optional proactive look-ahead.
// Returns the agency's predicted daily AQI for the reporting area. Used only
// (when enabled) to flush CO₂ proactively before forecast bad-air arrives;
// it never drives the wildfire guard — current readings always decide that.
@Field static final String  AIRNOW_FORECAST_URL     = "https://www.airnowapi.org/aq/forecast/current/"

// HRV operating modes (user-selectable base behavior)
@Field static final String  MODE_DEMAND      = "demand"      // off until air quality triggers (original behavior)
@Field static final String  MODE_CONTINUOUS  = "continuous"  // runs 24/7; app only stops for guards/away
@Field static final String  MODE_SCHEDULED   = "scheduled"   // runs continuously during set hours

// Continuous-mode away behavior (what presence does when everyone leaves)
@Field static final String  CONT_AWAY_OFF    = "off"         // turn HRV fully off when away
@Field static final String  CONT_AWAY_DEMAND = "demand"      // drop to demand-only when away
@Field static final String  CONT_AWAY_KEEP   = "keep"        // keep running continuously when away

// Manual override policies
@Field static final String  MANUAL_RESUME_TIMED     = "timed"     // resume after N minutes
@Field static final String  MANUAL_RESUME_NEXT_EVAL = "nexteval"  // resume on next sensor event
@Field static final String  MANUAL_RESUME_INDEFINITE= "indefinite" // pause until re-enabled in app

// Notification event keys — used for both push and TTS selection
@Field static final List    NOTIF_EVENTS = [
    [key: "fanStart",        label: "Ventilation fan starts"],
    [key: "fanStop",         label: "Ventilation fan stops"],
    [key: "wildfireOn",      label: "Wildfire guard activates"],
    [key: "wildfireOff",     label: "Wildfire guard clears"],
    [key: "freezeOn",        label: "Freeze guard activates"],
    [key: "freezeOff",       label: "Freeze guard clears"],
    [key: "co2Rising",       label: "CO₂ rising during guard lockout"],
    [key: "co2Override",     label: "CO₂ emergency override runs"],
    [key: "manualOverride",  label: "Manual override detected"],
    [key: "manualResume",    label: "Resuming after manual override"],
    [key: "noOutdoorData",   label: "Outdoor air quality data unavailable"],
    [key: "maxRun",          label: "Max run time safety net triggers"],
    [key: "purifierOn",      label: "Purifiers start (guard mode)"],
    [key: "arrivalVent",     label: "Arrival ventilation starts"],
    [key: "sensorFault",     label: "Indoor sensor fault detected or recovered"],
    [key: "outdoorDisagree", label: "Outdoor PM2.5 sources disagree significantly"],
    [key: "forecastBadAir",  label: "Forecast predicts poor air (proactive ventilation)"],
    [key: "maintenanceDue",  label: "Filter / core maintenance due (runtime-based)"],
    [key: "ventAborted",     label: "CO₂ override stopped early (particulate got inside)"],
    [key: "humidityCannotVent", label: "Humidity high but ventilating would not help"],
]

// ============================================================
// PREFERENCES
// ============================================================

preferences {
    page(name: "mainPage")
    page(name: "howItWorksPage")
    page(name: "fanPage")
    page(name: "sensorPage")
    page(name: "outdoorPage")
    page(name: "purifierPage")
    page(name: "purifierDevicePage")
    page(name: "thresholdPage")
    page(name: "sensorValidationPage")
    page(name: "humidityPage")
    page(name: "maintenancePage")
    page(name: "presencePage")
    page(name: "timingPage")
    page(name: "notificationPage")
    page(name: "ttsPage")
    page(name: "ttsMessagesPage")
    page(name: "manualOverridePage")
    page(name: "diagnosticsPage")
}

// ── Main Page ─────────────────────────────────────────────────────────────────

def mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {

        section(h("💨 HRV / Ventilation Fan & Air Quality Manager v${APP_VERSION}")) {
            paragraph """Controls any ventilation fan — HRV, ERV, inline exhaust fan, bath fan — \
or works without a fan as a <b>standalone air quality manager</b> using only air purifiers.<br><br>
Implements <b>ASHRAE 62.2-2022</b>, <b>ASHRAE Guideline 44-2024</b> (wildfire smoke), \
and <b>EPA / WA DOH wildfire IAQ guidance</b>. Tap <b>How This App Works</b> for \
a plain-English explanation of the decision logic and the "ventilation catch-22." """
        }

        section(h("📋 Configuration")) {
            href "howItWorksPage",     title: "📖 How This App Works",              description: "Priority order, standards, the ventilation catch-22"
            href "fanPage",            title: "💨 Ventilation Fan",                 description: fanSummary(),          state: fanSwitch ? "complete" : null
            href "sensorPage",         title: "📡 Indoor Sensors",                  description: sensorSummary(),       state: indoorSensors ? "complete" : null
            href "outdoorPage",        title: "🌿 Outdoor Air Quality",             description: outdoorSummary(),      state: (outdoorPmSensor || airnowApiKey) ? "complete" : null
            href "purifierPage",       title: "🌀 Air Purifiers",                   description: purifierSummary(),     state: purifierSwitches ? "complete" : null
            href "thresholdPage",      title: "🎚️ Thresholds & Guards",             description: "CO₂, PM2.5, freeze, wildfire, CO₂ emergency override", state: "complete"
            href "sensorValidationPage", title: "🔬 Sensor Validation",              description: sensorValidationSummary(), state: "complete"
            href "humidityPage",       title: "💧 Humidity Control",          description: humiditySummary(), state: (settings.humidityControlEnabled ? "complete" : null)
            href "maintenancePage",    title: "🔧 Maintenance & Runtime",           description: maintenanceSummary(), state: (settings.maintenanceEnabled ? "complete" : null)
            href "presencePage",       title: "🏠 Presence",                        description: presenceSummary(),     state: presenceEnabled ? "complete" : null
            href "timingPage",         title: "⏱️ Timing",                          description: "Min run, cooldown, max run", state: "complete"
            href "manualOverridePage", title: "🖐️ Manual Override Policy",          description: manualOverrideSummary(), state: "complete"
            href "notificationPage",   title: "📲 Push Notifications",              description: pushSummary(),         state: notifyDevices ? "complete" : null
            href "ttsPage",            title: "🔊 Voice Announcements (TTS)",       description: ttsSummary(),          state: ttsDevices ? "complete" : null
            href "diagnosticsPage",    title: "🩺 Diagnostics & Status",            description: "Live readings, guards, last run"
        }

        section(h("🩺 Recommended Health-Protective Settings")) {
            paragraph """This app ships with defaults drawn from the more protective end of \
current guidance: WHO 2021 for indoor particulate, the EPA 2024 AQI revision, and WA DOH for CO₂.<br><br>
<b>Saved settings are not changed by an app update.</b> If you installed before these defaults \
changed, tap below to adopt them. It only rewrites the seven threshold values listed; nothing \
else is touched.<br><br>
<b>Indoor PM2.5</b> run 15 / stop 9 &nbsp;•&nbsp; <b>Outdoor guard</b> 35 &nbsp;•&nbsp; \
<b>CO₂</b> run 800 / stop 600 &nbsp;•&nbsp; <b>CO₂ override after</b> 120 min &nbsp;•&nbsp; \
<b>Override abort margin</b> 3<br><br>
${warn("The outdoor guard at 35 locks the fan out considerably more often than 55 during smoke season. That is the intent — but it leans harder on your purifiers and the CO₂ override.")}"""
            input "btnApplyHealthDefaults", "button", title: "🩺 Apply Recommended Settings"
        }

        section(h("⚙️ App Settings")) {
            input "enableApp", "bool", title: "App enabled", defaultValue: true, submitOnChange: true
            if (!enableApp) paragraph warn("App is disabled. Fan and purifiers will not be automatically controlled.")
            input "statusDeviceEnabled", "bool",
                  title: "Create a dashboard status device",
                  description: "Adds an 'HRV Status' child device exposing status, PM2.5, CO\u2082, humidity and fan state for Dashboard tiles",
                  defaultValue: false, submitOnChange: true
            if (settings.statusDeviceEnabled) {
                def sd = getChildDevice("hrv-status-${app.id}")
                paragraph sd ?
                    "<font color='green'>\u2705 Status device active: <b>${sd.displayName}</b>. Add it to a Dashboard and use the <i>variable</i> attribute for the status text.</font>" :
                    warn("Status device will be created when you tap Done.")
            }
            input "logLevel", "enum",
                  title: "Log level",
                  options: ["OFF":"Off","INFO":"Info (recommended)","DEBUG":"Debug (verbose)"],
                  defaultValue: "INFO", required: true
        }
    }
}

// ── How It Works Page ─────────────────────────────────────────────────────────

def howItWorksPage() {
    dynamicPage(name: "howItWorksPage", title: "📖 How This App Works", install: false, uninstall: false) {

        section(h("The Ventilation Catch-22")) {
            paragraph """A ventilation fan improves air quality by exchanging stale indoor air \
with fresh outdoor air. But during wildfire smoke events or extreme cold, running the fan \
makes things worse — it pumps smoke-laden or dangerously cold air inside.<br><br>
<b>The solution (per ASHRAE Guideline 44-2024 and EPA guidance):</b><br>
Stop the fan when outdoor air is poor or dangerous. Run HEPA air purifiers instead to \
clean recirculated indoor air. Monitor CO₂ — if it rises too high during prolonged lockout, \
briefly ventilate when outdoor air improves, or as a last resort per WA DOH guidance.<br><br>
<b>Key limitation:</b> Air purifiers cannot reduce CO₂. Only fresh air exchange (running \
the fan) achieves that. If CO₂ rises while the fan is locked out, the app will alert you \
and — if configured — briefly run the fan anyway per WA DOH DOH-333-208.<br><br>
<i>Sources: WA DOH DOH-333-208; ASHRAE Guideline 44-2024; Stiebel-Eltron 'Air Purifiers vs HRV.'</i>"""
        }

        section(h("Decision Priority Order")) {
            paragraph """The app evaluates in this strict order. Higher priority always wins.<br><br>

<b>1 — CO₂ Emergency Override</b> <i>(WA DOH DOH-333-208)</i><br>
Guard locked out for extended time AND CO₂ above emergency threshold → run fan briefly \
to flush CO₂, then return to guard lockout. Reset guard timer after each override.<br><br>

<b>2 — Safety Guards</b> <i>(ASHRAE GL44-2024; CCHRC; manufacturer guidance)</i><br>
• Wildfire: Outdoor PM2.5 > threshold → fan OFF immediately, purifiers ON<br>
• Freeze: Outdoor temp &lt; minimum → fan OFF immediately (protects heat exchanger core)<br>
These override everything except the CO₂ emergency.<br><br>

<b>3 — Presence</b> <i>(ASHRAE 62.2-2022 demand-controlled ventilation)</i><br>
Everyone away + behavior=disabled → fan OFF, purifiers OFF. Guards remain active.<br><br>

<b>4 — Air Quality Response</b> <i>(ASHRAE 62.2-2022; EPA AQI)</i> — outranks humidity<br>
• CO₂ high + outdoor OK → fan ON<br>
• PM2.5 high + outdoor OK → fan ON + purifiers (if configured)<br>
• PM2.5 high + guard active → purifiers only + CO₂ alert<br>
• All clear → fan OFF, purifiers OFF<br><br>

<b>5 — Humidity (opportunistic)</b> <i>(ASHRAE; EPA moisture control)</i><br>
Can start the fan on its own, but only when outdoor air is genuinely drier by dew point,
and never blocks a run that air quality requires.<br><br>

<b>6 — Timing Guards</b> <i>(manufacturer best practice)</i><br>
Minimum run, cooldown, maximum continuous run."""
        }

        section(h("Outdoor Air Quality Data Sources")) {
            paragraph """<b>Priority order for outdoor PM2.5:</b><br>
1. Local sensor (WH41, etc.) — real-time, most accurate for your exact location<br>
2. EPA AirNow API — hourly, regional. Official US government program (EPA + NOAA + \
CDC + NASA + NPS). Free API key at https://docs.airnowapi.org<br>
3. No data → wildfire guard disabled with notification<br><br>
<b>Local-only mode</b> available if you prefer no external API calls."""
        }

        section(h("Works Without a Fan")) {
            paragraph """If no ventilation fan switch is configured, the app operates as a \
<b>standalone air quality manager</b>:<br>
• Purifiers respond to indoor PM2.5 and the wildfire/freeze guards<br>
• CO₂ monitoring still works — alerts are sent when CO₂ is high \
(even though purifiers can't fix it, you need to know)<br>
• All outdoor guards and AirNow integration still apply"""
        }

        section(h("Manual Override")) {
            paragraph """If you manually turn the fan on or off (not via this app), the app \
detects the mismatch between what it expects and what the switch reports. It responds \
per your configured policy: resume automation after N minutes, after the next sensor \
event, or pause indefinitely until you re-enable it here. See the <b>Manual Override \
Policy</b> page for options."""
        }
    }
}

// ── Fan Page ──────────────────────────────────────────────────────────────────

def fanPage() {
    dynamicPage(name: "fanPage", title: "💨 Ventilation Fan", install: false, uninstall: false) {

        section(h("Fan / HRV Switch Device")) {
            paragraph """Select the switch or outlet device controlling your ventilation fan. \
This can be any ventilation device:<br>
• <b>HRV or ERV</b> (e.g. Lifebreath, Broan, Renewaire) wired via smart outlet<br>
• <b>Inline duct fan</b> (e.g. Panasonic WhisperLine) via smart switch<br>
• <b>Bath exhaust fan</b> or <b>whole-house fan</b><br>
• <b>Any 120V ventilation device</b> controlled by a Hubitat switch<br><br>
<b>No fan?</b> Leave this blank to use the app as a standalone air quality manager \
controlling only air purifiers."""
            input "fanSwitch", "capability.switch",
                  title: "Ventilation fan switch (optional)",
                  description: "Tap to select — or leave blank for purifier-only mode",
                  required: false, multiple: false, submitOnChange: true

            if (fanSwitch) {
                def s = fanSwitch.currentValue("switch") ?: "unknown"
                paragraph "Current state: <b>${s.toUpperCase()}</b>"
                if (atomicState.manualOverrideActive) {
                    paragraph warn("Manual override is currently active. Automation is paused.")
                }
            } else {
                paragraph "<font color='green'>ℹ️ No fan selected — running in purifier-only / air quality manager mode.</font>"
            }
        }

        if (fanSwitch) {
            section(h("Operating Mode")) {
                paragraph """How should the fan run as its <b>base behavior</b>? This is the most \
important setting — it determines whether the fan runs all the time or only on demand.<br><br>
<b>Continuous:</b> The fan runs 24/7, as most HRVs/ERVs are designed to. This is the \
ASHRAE 62.2 model of continuous background ventilation. The app's job becomes turning \
the fan <i>off</i> for safety guards (wildfire, freeze) and optionally when away, then \
back on. <b>Recommended for whole-house HRVs/ERVs.</b><br><br>
<b>Demand:</b> The fan stays off until indoor CO₂ or PM2.5 rises above your thresholds, \
then runs until air quality recovers. Best for exhaust/spot-ventilation fans, or to \
minimize runtime/energy.<br><br>
<b>Scheduled:</b> The fan runs continuously during the hours you set, and is off (or \
demand-only) outside them. Good for running ventilation during the day or overnight only.<br><br>
<i>Source: ASHRAE 62.2-2022 continuous ventilation model; Lifebreath RNC200 is \
designed for continuous operation.</i>"""
                input "operatingMode", "enum",
                      title: "Fan operating mode",
                      options: [
                          (MODE_CONTINUOUS): "Continuous — run 24/7 (recommended for HRV/ERV)",
                          (MODE_DEMAND):     "Demand — run only when air quality triggers it",
                          (MODE_SCHEDULED):  "Scheduled — run continuously during set hours"
                      ],
                      defaultValue: MODE_CONTINUOUS, required: true, submitOnChange: true

                if (operatingMode == MODE_SCHEDULED) {
                    input "scheduleStart", "time", title: "Continuous-run hours START", required: true
                    input "scheduleEnd",   "time", title: "Continuous-run hours END",   required: true
                    input "scheduledOutsideDemand", "bool",
                          title: "Outside scheduled hours, still run on demand (CO₂/PM triggers)?",
                          defaultValue: true
                    paragraph "During scheduled hours the fan runs continuously. Outside them, " +
                              "${(settings.scheduledOutsideDemand != false) ? "it runs only if air quality triggers it." : "it stays off (except safety overrides)."}"
                }

                if (operatingMode == MODE_CONTINUOUS) {
                    paragraph "In continuous mode, the fan runs whenever conditions are safe. " +
                              "Safety guards still stop it (wildfire, freeze). Presence behavior is set below."
                }
            }

            section(h("Fan Type (for TTS announcements)")) {
                paragraph "Used to make voice announcements more natural. Select the type that best describes your device."
                input "fanTypeName", "enum",
                      title: "Fan type label",
                      options: [
                          "HRV":              "HRV (Heat Recovery Ventilator)",
                          "ERV":              "ERV (Energy Recovery Ventilator)",
                          "ventilation fan":  "Ventilation fan",
                          "exhaust fan":      "Exhaust fan",
                          "whole-house fan":  "Whole-house fan",
                          "fresh air system": "Fresh air system"
                      ],
                      defaultValue: "HRV", required: true
            }
        }
    }
}

// ── Indoor Sensors Page ───────────────────────────────────────────────────────

def sensorPage() {
    dynamicPage(name: "sensorPage", title: "📡 Indoor Sensors", install: false, uninstall: false) {

        section(h("CO₂ / Air Quality Sensors")) {
            paragraph """Select your indoor air quality sensors. Each should expose \
<code>carbonDioxide</code> (ppm) and/or <code>pm25</code> (µg/m³) attributes.<br><br>
The app triggers on <b>any</b> sensor exceeding a threshold — worst zone wins, \
not an average. A stuffy bedroom matters even if other rooms are fine.<br><br>
<b>Ecowitt users:</b> Select the child devices for each WH46/WH46D unit."""
            input "indoorSensors", "capability.carbonDioxideMeasurement",
                  title: "Indoor air quality sensor(s)",
                  description: "Tap to select — multiple supported",
                  required: false, multiple: true, submitOnChange: true
        }

        if (indoorSensors) {
            section(h("Current Indoor Readings")) {
                indoorSensors.each { s ->
                    def co2raw  = s.currentValue("carbonDioxide")
                    def pm25raw = s.currentValue("pm25")
                    def co2  = co2raw  ?: "--"
                    def pm25 = pm25raw ?: "--"
                    def pm10 = s.currentValue("pm10")        ?: "--"
                    def temp = s.currentValue("temperature") ?: "--"
                    def hum  = s.currentValue("humidity")    ?: "--"
                    String pmNote = ""
                    if (pm25raw == null && co2raw != null) {
                        // CO₂ is reporting but PM isn't yet — normal for battery-powered
                        // WH46D (PM updates every 10 min on battery vs 1 min on USB power)
                        pmNote = " <font color='gray'>(PM not yet reported — normal on battery power; PM updates every ~10 min vs CO₂ every 30 s. Plug in via USB for 1-min PM updates.)</font>"
                    }
                    paragraph "<b>${s.displayName}</b> — " +
                              "CO₂: <b>${co2} ppm</b> | PM2.5: <b>${pm25} µg/m³</b> | " +
                              "PM10: <b>${pm10} µg/m³</b> | ${temp}°F | ${hum}%${pmNote}"
                }
            }
        }
    }
}

// ── Outdoor Page ──────────────────────────────────────────────────────────────

def outdoorPage() {
    dynamicPage(name: "outdoorPage", title: "🌿 Outdoor Air Quality", install: false, uninstall: false) {

        section(h("Local Outdoor PM2.5 Sensor")) {
            paragraph "Most accurate source — reads every 60 seconds from your exact location. Ecowitt WH41 or similar."
            input "outdoorPmSensor", "capability.sensor",
                  title: "Outdoor PM2.5 sensor (recommended)",
                  description: "Must expose a 'pm25' attribute",
                  required: false, multiple: false, submitOnChange: true
            if (outdoorPmSensor) {
                def pm = outdoorPmSensor.currentValue("pm25") ?: "--"
                paragraph "Current outdoor PM2.5: <b>${pm} µg/m³</b>"
            }
        }

        section(h("Outdoor Temperature Sensor")) {
            paragraph "Used for freeze protection — prevents running the fan in extreme cold."
            input "outdoorTempSensor", "capability.temperatureMeasurement",
                  title: "Outdoor temperature sensor (recommended)",
                  required: false, multiple: false, submitOnChange: true
            if (outdoorTempSensor) {
                def t = outdoorTempSensor.currentValue("temperature") ?: "--"
                paragraph "Current outdoor temp: <b>${t}°F</b>"
            }
        }

        section(h("EPA AirNow API (US Government Fallback)")) {
            paragraph """Official US government outdoor air quality data. Operated by EPA, \
NOAA, CDC, NASA, and the National Park Service. Updated hourly from 2,500+ stations.<br><br>
<b>To get your free API key (2 minutes):</b><br>
1. Visit <b>https://docs.airnowapi.org</b><br>
2. Click "Log In" → "Sign Up" (email only, no personal info required)<br>
3. Key is emailed immediately — paste it below.<br><br>
<i>Source: US EPA Office of Air and Radiation, data.gov catalog, stable since 1998.</i>"""

            input "localOnlyMode", "bool",
                  title: "Local-only mode (no external API calls)",
                  defaultValue: false, submitOnChange: true

            if (!localOnlyMode) {
                input "airnowApiKey", "string",
                      title: "AirNow API key (free — leave blank to disable)",
                      required: false, submitOnChange: true
                if (airnowApiKey) {
                    def lastAqi  = atomicState.airnowLastAqi  ?: "--"
                    def lastTime = atomicState.airnowLastPoll ?
                                   new Date(atomicState.airnowLastPoll).format("h:mm a", location.timeZone) : "never"
                    paragraph "✅ AirNow configured. Last reading: <b>AQI ${lastAqi}</b> at <b>${lastTime}</b>"
                } else {
                    paragraph warn("No AirNow key. If no local PM sensor is configured, wildfire guard will be disabled.")
                }
            } else {
                paragraph "<font color='green'>✅ Local-only mode. No external calls will be made.</font>"
            }
        }

        if (!localOnlyMode && airnowApiKey) {
            section(h("📅 Forecast-Based Proactive Ventilation (optional)")) {
                paragraph """When AirNow forecasts poor air for today, the app can ventilate \
<b>proactively</b> — flushing CO₂ while outdoor air is still acceptable, before forecast \
smoke arrives and forces the fan into lockout. This implements the WA DOH guidance to \
bring in outside air during periods of better air quality.<br><br>
<b>How it works:</b> On a bad-air forecast, the app lowers the CO₂ run threshold so the \
fan starts earlier than it normally would. It does <b>not</b> force the fan on regardless \
of CO₂, and it <b>never</b> overrides the wildfire guard — if smoke is already here, current \
readings still lock the fan out. This is purely a head-start while the air is still clean.<br><br>
<b>Limitations to know:</b> Forecasts are a once-daily prediction for a regional reporting \
area, not your exact location, and can be wrong during fast-moving smoke events. Your local \
sensor remains the authority for current conditions. This feature only changes <i>when</i> \
the app pre-ventilates, never the safety guards."""
                input "forecastEnabled", "bool",
                      title: "Enable forecast-based proactive ventilation",
                      defaultValue: false, submitOnChange: true
                if (forecastEnabled) {
                    input "forecastAqiThreshold", "number",
                          title: "Forecast PM2.5 AQI considered 'bad air' — default ${DEFAULT_FORECAST_AQI} (USG boundary)",
                          defaultValue: DEFAULT_FORECAST_AQI, range: "51..300", required: true
                    input "forecastCo2Flush", "number",
                          title: "Lowered CO₂ run threshold on bad-air days (ppm) — default ${DEFAULT_CO2_LOW}",
                          defaultValue: DEFAULT_CO2_LOW, range: "500..1500", required: true
                if (atomicState.forecastNoCoverage) {
                    paragraph warn("AirNow reports no forecast coverage for your reporting area. " +
                                   "This feature can't activate here — AirNow only forecasts for about 500 cities, " +
                                   "and your area isn't one of them. Current-conditions monitoring (which drives the " +
                                   "wildfire guard) still works normally. You can leave this off.")
                } else {
                    def fAqi  = atomicState.forecastLastAqi != null ? atomicState.forecastLastAqi : "--"
                    def fWhen = atomicState.forecastLastPoll ?
                                new Date(atomicState.forecastLastPoll).format("MMM d h:mm a", location.timeZone) : "never"
                    def fBad  = (atomicState.forecastBadAir ?: false) ? "⚠️ bad-air day" : "clear"
                    paragraph "Last forecast: <b>PM2.5 AQI ${fAqi}</b> (${fBad}) at <b>${fWhen}</b>"
                }
                paragraph "<font color='gray'>Note: in continuous mode the fan already runs 24/7, so this setting has no effect there — it applies to demand and scheduled modes.</font>"
                }
            }
        }
    }
}

// ── Purifier Page ─────────────────────────────────────────────────────────────

def purifierPage() {
    dynamicPage(name: "purifierPage", title: "🌀 Air Purifiers", install: false, uninstall: false) {

        section(h("Room Air Purifier Switches")) {
            paragraph """Select the switch devices controlling your air purifiers. \
Smart outlets, native purifier devices, or any Hubitat switch.<br><br>
<b>What purifiers do:</b> Filter recirculated indoor air — removes PM2.5, PM10, \
allergens, VOCs, wildfire smoke particles. <b>Cannot reduce CO₂</b> — that requires \
fresh air exchange from a ventilation fan.<br><br>
<i>Source: ASHRAE Guideline 44-2024: 'integrate portable air cleaners' during smoke events.</i>"""
            input "purifierSwitches", "capability.switch",
                  title: "Air purifier switch(es)",
                  description: "Tap to select — multiple rooms supported",
                  required: false, multiple: true, submitOnChange: true
        }

        if (purifierSwitches) {
            section(h("Global Purifier Settings")) {
                input "purifiersOnWhenGuard", "bool",
                      title: "Run purifiers when wildfire or freeze guard blocks the fan",
                      description: "Overrides individual device schedules during safety events",
                      defaultValue: true
                input "purifiersParallel", "bool",
                      title: "Also run purifiers while the fan is running (parallel operation)",
                      defaultValue: false
            }

            section(h("Per-Device Settings")) {
                paragraph """Configure each purifier individually — set its operating policy, \
manual override behavior, and schedule. Tap each device below."""
                purifierSwitches.each { p ->
                    def policy  = settings["purifier_policy_${p.id}"] ?: "auto"
                    def sw      = p.currentValue("switch") ?: "unknown"
                    def moActive = atomicState["purifier_mo_${p.id}"] ?: false
                    def policyLabel = ["auto": "Auto", "manualRespected": "Manual override respected",
                                       "alwaysOn": "Always on", "scheduled": "Scheduled"][policy] ?: "Auto"
                    def statusIcon = sw == "on" ? "🟢" : "⚪"
                    def overrideNote = moActive ? " ⚠️ manual override active" : ""
                    href "purifierDevicePage_${p.id}",
                         title: "${statusIcon} ${p.displayName}",
                         description: "Policy: ${policyLabel}${overrideNote} | Switch: ${sw.toUpperCase()}",
                         params: [deviceId: p.id],
                         page: "purifierDevicePage"
                }
            }
        }
    }
}

// ── Per-Device Purifier Settings Page ────────────────────────────────────────

def purifierDevicePage(Map params) {
    // Hubitat passes params when navigating to a dynamic page
    def deviceId = params?.deviceId
    def device   = purifierSwitches?.find { it.id == deviceId }

    // Fallback: if params lost (re-render), try to find from any configured device
    if (!device && purifierSwitches?.size() == 1) device = purifierSwitches[0]

    dynamicPage(name: "purifierDevicePage", title: "🌀 ${device?.displayName ?: "Purifier Settings"}", install: false, uninstall: false) {

        if (!device) {
            section { paragraph warn("Device not found. Return to the Air Purifiers page and tap the device again.") }
            return
        }

        def id        = device.id
        def sw        = device.currentValue("switch") ?: "unknown"
        def moActive  = atomicState["purifier_mo_${id}"] ?: false
        def policy    = settings["purifier_policy_${id}"] ?: "auto"

        section(h("📊 Current Status")) {
            paragraph "Switch: <b>${sw.toUpperCase()}</b>${moActive ? " | " + warn("Manual override active") : ""}"
        }

        section(h("Operating Policy")) {
            paragraph """<b>Auto:</b> Fully app-controlled. App turns on/off based on air quality conditions.<br><br>
<b>Manual override respected:</b> App controls normally but detects if you turn it on/off \
outside the app, then pauses per the override policy below.<br><br>
<b>Always on — never turn off:</b> App can turn it on when air quality warrants it, but \
will never turn it off. You control when it stops. Ideal for purifiers used as sound \
machines — they keep running overnight regardless of air quality.<br><br>
<b>Scheduled:</b> App controls the purifier only during the configured hours. Outside \
those hours the app leaves it entirely alone. Ideal for bedroom purifiers — app manages \
air quality during the day, leaves it alone overnight for you to control as a sound machine."""
            input "purifier_policy_${id}", "enum",
                  title: "Policy for ${device.displayName}",
                  options: [
                      "auto":             "Auto — fully app-controlled",
                      "manualRespected":  "Manual override respected",
                      "alwaysOn":         "Always on — app never turns off",
                      "scheduled":        "Scheduled — app controls during set hours only"
                  ],
                  defaultValue: "auto", required: true, submitOnChange: true
        }

        // Manual override policy — only shown for manualRespected
        if (policy == "manualRespected") {
            section(h("Manual Override Policy")) {
                paragraph """When you turn this purifier on or off outside the app, \
the app detects the mismatch and responds per the policy below."""
                input "purifier_moPolicy_${id}", "enum",
                      title: "When manual override is detected on ${device.displayName}:",
                      options: [
                          "timed":      "Resume automation after N minutes",
                          "nexteval":   "Resume on next sensor event",
                          "indefinite": "Pause indefinitely until re-enabled here"
                      ],
                      defaultValue: "timed", required: true, submitOnChange: true

                def moPolicy = settings["purifier_moPolicy_${id}"] ?: "timed"
                if (moPolicy == "timed") {
                    input "purifier_moMinutes_${id}", "number",
                          title: "Pause for this many minutes",
                          defaultValue: 30, range: "5..480", required: true
                }

                if (moActive) {
                    paragraph warn("Manual override is currently active on this device.")
                    input "purifier_resumeBtn_${id}", "button",
                          title: "▶️ Resume Automation Now for ${device.displayName}"
                }
            }
        }

        // Schedule settings — only shown for scheduled
        if (policy == "scheduled") {
            section(h("Schedule")) {
                paragraph """The app will control this purifier only between the start \
and end times below. Outside these hours, the app ignores the device entirely — \
it will not turn it on or off. This lets you use it as a sound machine overnight \
without the app interfering.<br><br>
<b>Safety exception:</b> If a wildfire guard activates, the app will turn this \
purifier on even outside the schedule (per ASHRAE GL44-2024 guidance). This is \
controlled by the global 'Run purifiers when guard is active' setting."""
                input "purifier_schedStart_${id}", "time",
                      title: "App-controlled hours start",
                      required: true
                input "purifier_schedEnd_${id}", "time",
                      title: "App-controlled hours end",
                      required: true

                // Show current schedule status if times set
                def schedStart = settings["purifier_schedStart_${id}"]
                def schedEnd   = settings["purifier_schedEnd_${id}"]
                if (schedStart && schedEnd) {
                    boolean inWindow = inPurifierSchedule(id)
                    paragraph "Currently: <b>${inWindow ? "within schedule window — app is controlling" : "outside schedule window — app is not controlling"}</b>"
                }
            }
        }

        // Always on note
        if (policy == "alwaysOn") {
            section(h("Always On Behavior")) {
                paragraph """The app will turn this purifier on whenever air quality \
warrants it, but will <b>never turn it off automatically</b>. You decide when to \
turn it off.<br><br>
This is the recommended policy for purifiers used as sound machines. \
The purifier will run continuously once started — either by the app responding \
to elevated PM2.5, or by you turning it on manually."""
            }
        }
    }
}

// ── Threshold Page ────────────────────────────────────────────────────────────

def thresholdPage() {
    dynamicPage(name: "thresholdPage", title: "🎚️ Thresholds & Guards", install: false, uninstall: false) {

        section(h("🫁 CO₂ Thresholds")) {
            paragraph """CO₂ is the primary indicator of stale air. Normal outdoor air \
is ~420 ppm. Occupied rooms typically reach 600-800 ppm. Above 1,000 ppm, \
occupants may feel drowsy or unfocused.<br>
<i>Source: ASHRAE 62.2-2022; WA DOH DOH-333-208.</i>"""
            input "co2High", "number", title: "CO₂ RUN threshold (ppm) — default ${DEFAULT_CO2_HIGH}", defaultValue: DEFAULT_CO2_HIGH, range: "500..2000", required: true
            input "co2Low",  "number", title: "CO₂ STOP threshold (ppm) — default ${DEFAULT_CO2_LOW}",  defaultValue: DEFAULT_CO2_LOW,  range: "400..1500", required: true
        }

        section(h("🌫️ Indoor PM2.5 Thresholds")) {
            paragraph """Fine particulate matter. EPA 'Good' AQI ends at 9 µg/m³ under the 2024 revision \
(was 12). 'Moderate' runs 9.1 to 35.4.<br><i>Source: EPA AQI — https://www.airnow.gov/aqi/aqi-basics/</i>"""
            paragraph """<b>Where this default comes from:</b><br>
• <b>15 µg/m³</b> (this app's default) is the <b>WHO 2021 24-hour guideline</b>.<br>
• <b>9 µg/m³</b> is the EPA 2024 annual standard — the ceiling for "Good" air.<br>
• <b>35 µg/m³</b> is the EPA 24-hour outdoor standard; permissive for indoor air, and what this \
app used to default to.<br><br>
Indoors you have far more control than outdoors and running purifiers costs little, so the \
protective value is the sensible default — particularly with children, older adults, or anyone \
with asthma in the house. Raise it toward 35 if the purifiers run more than you want."""
            input "pm25IndoorHigh", "number", title: "Indoor PM2.5 RUN threshold (µg/m³) — default ${DEFAULT_PM25_IN_HIGH}", defaultValue: DEFAULT_PM25_IN_HIGH, range: "5..150", required: true
            input "pm25IndoorLow",  "number", title: "Indoor PM2.5 STOP threshold (µg/m³) — default ${DEFAULT_PM25_IN_LOW}",  defaultValue: DEFAULT_PM25_IN_LOW,  range: "5..100",  required: true
        }

        section(h("🔥 Outdoor Wildfire / Pollution Guard")) {
            paragraph """When outdoor PM2.5 exceeds this, the fan stops immediately and \
purifiers run instead. Default is EPA AQI 100 boundary (55.4 µg/m³) — last level \
considered acceptable for the general population.<br>
<i>Source: ASHRAE Guideline 44-2024; EPA AQI.</i>"""
            paragraph """<b>The default is 35 µg/m³</b> — the AQI 100 boundary, top of the EPA \
"Moderate" band. The fan stops bringing in outside air once conditions pass Moderate, which \
follows ASHRAE Guideline 44-2024: minimise outdoor air during smoke and lean on portable air \
cleaners instead.<br><br>
<b>Know the trade-off.</b> This is materially more restrictive than the older 55 default (the top \
of "Unhealthy for Sensitive Groups"). Measured against real logged readings from a wildfire smoke \
season, 55 locked the fan out about 38% of the time and 35 locks it out about 62%. More lockout \
means indoor CO₂ climbs faster with no way to vent — which is exactly why the CO₂ emergency \
override now becomes available after 120 minutes rather than 240.<br><br>
Raise this toward 55 if you find the fan is locked out more than you can tolerate."""
            input "pm25OutdoorMax", "number", title: "Outdoor PM2.5 guard (µg/m³) — default ${DEFAULT_PM25_OUT_MAX}", defaultValue: DEFAULT_PM25_OUT_MAX, range: "20..200", required: true
            paragraph """<b>Anti-flapping controls.</b> During a smoke event the reading hovers around \
the threshold, which would otherwise switch the fan and every purifier on and off repeatedly \
(relay wear, notification spam). Two protections:<br><br>
<b>Release margin:</b> once the guard is active it clears only after PM2.5 drops this far \
<i>below</i> the threshold. With the defaults, it trips above ${DEFAULT_PM25_OUT_MAX} and \
clears below ${DEFAULT_PM25_OUT_MAX - GUARD_HYST_PM25}.<br>
<b>Minimum hold:</b> once tripped, the guard stays on at least this long regardless."""
            input "guardHystPm25", "number",
                  title: "Release margin below threshold (µg/m³) — default ${GUARD_HYST_PM25}",
                  defaultValue: GUARD_HYST_PM25, range: "0..40", required: true
            input "guardMinMinutes", "number",
                  title: "Minimum time the guard stays active (minutes) — default ${DEFAULT_GUARD_MIN_MIN}",
                  defaultValue: DEFAULT_GUARD_MIN_MIN, range: "0..240", required: true
        }

        section(h("🥶 Freeze Protection")) {
            paragraph """Prevents running the fan in extreme cold — protects HRV/ERV heat \
exchanger cores from condensate freeze damage. Default 15°F is consistent with \
cold-climate industry guidance.<br>
<i>Source: CCHRC cold climate HRV guide; Lifebreath RNC200 installation manual.</i>"""
            input "outdoorTempMin", "number", title: "Minimum outdoor temperature (°F) — default ${DEFAULT_TEMP_MIN}", defaultValue: DEFAULT_TEMP_MIN, range: "-20..40", required: true
        }

        section(h("⚠️ CO₂ Emergency Override During Guard Lockout")) {
            paragraph """When a safety guard has blocked the fan for an extended period and \
CO₂ has risen above the emergency threshold, the app briefly runs the fan to flush CO₂, \
then returns to lockout. This implements WA DOH DOH-333-208: <i>"If levels are frequently \
above 1000 ppm, consider increasing outdoor air ventilation."</i><br><br>
This accepts short-term PM2.5 exposure to prevent prolonged CO₂ buildup. \
The tradeoff is documented by WA DOH. You may disable if you prefer the fan to remain \
off regardless of CO₂.<br>
<i>Source: WA DOH DOH-333-208; EPA IAQ wildfire guidance.</i>"""
            input "co2EmergencyEnabled", "bool",
                  title: "Enable CO₂ emergency override during guard lockout",
                  defaultValue: true, submitOnChange: true
            if (co2EmergencyEnabled) {
                input "co2Emergency",           "number", title: "Emergency CO₂ threshold (ppm) — default ${DEFAULT_CO2_EMERGENCY}",  defaultValue: DEFAULT_CO2_EMERGENCY,   range: "800..3000", required: true
                input "emergencyAfterMinutes",  "number", title: "Override after guard active this many minutes — default ${DEFAULT_EMERGENCY_AFTER}", defaultValue: DEFAULT_EMERGENCY_AFTER, range: "30..480",  required: true
                input "emergencyVentMinutes",   "number", title: "Run fan for this many minutes on override — default ${DEFAULT_EMERGENCY_DUR}",       defaultValue: DEFAULT_EMERGENCY_DUR,   range: "5..60",    required: true
                paragraph """<b>Particulate abort (safety check):</b> During an override the app \
watches indoor PM2.5. If it rises more than the margin below, outdoor particulate is getting \
through the fan's filter, so the run is <b>stopped early</b> and the guard resumes.<br><br>
This also tells you how well your filter actually performs: after each override the log records \
the measured indoor PM2.5 rise. A rise near zero means the filter held at that outdoor level.<br><br>
<i>Note: a stock HRV filter is coarse media and does not remove PM2.5 well. Expect aborts \
during heavy smoke — that is the check working, not a fault.</i>"""
                input "ventPmAbortMargin", "number",
                      title: "Abort override if indoor PM2.5 rises more than this (µg/m³) — default ${DEFAULT_VENT_PM_ABORT}",
                      defaultValue: DEFAULT_VENT_PM_ABORT, range: "1..50", required: true
            }
        }
    }
}

// ── Sensor Validation Page ────────────────────────────────────────────────────

def sensorValidationPage() {
    dynamicPage(name: "sensorValidationPage", title: "🔬 Sensor Validation", install: false, uninstall: false) {

        section(h("About Sensor Validation")) {
            paragraph """These checks protect the app from acting on bad sensor data. They are \
intentionally conservative — air quality sensors (like the WH46D/WH41) naturally swing \
between low values from reading to reading, and that is normal, not a fault.<br><br>
<b>Spike detection has been removed</b> — these sensors legitimately jump (cooking, the \
sensor's own fan cycling, normal activity), so spike alarms were always false. Only \
truly impossible values, genuinely stuck sensors, and silent (stale) sensors are flagged."""
        }

        section(h("Range Check (always on)")) {
            paragraph """Flags physically impossible values: CO₂ below ${CO2_MIN_PLAUSIBLE} ppm \
or above ${CO2_MAX_PLAUSIBLE} ppm; PM2.5 below ${PM25_MIN_PLAUSIBLE} or above \
${PM25_MAX_PLAUSIBLE} µg/m³. These bounds are fixed — a reading outside them indicates \
a genuine sensor malfunction, not real air quality."""
        }

        section(h("Stuck-Reading Detection")) {
            paragraph """Flags a sensor reporting the <i>identical</i> value for many consecutive \
readings — but only above a minimum value, because clean air legitimately holds steady at \
low PM2.5 for hours. Disable this if your sensors report on a fixed interval that produces \
many repeats."""
            input "sensorStuckEnabled", "bool",
                  title: "Enable stuck-reading detection",
                  defaultValue: true, submitOnChange: true
            if (settings.sensorStuckEnabled != false) {
                input "sensorStuckCount", "number",
                      title: "Identical readings before 'stuck' — default ${DEFAULT_STUCK_COUNT}",
                      defaultValue: DEFAULT_STUCK_COUNT, range: "5..200", required: true
                input "sensorStuckMinValue", "number",
                      title: "Only flag stuck above this value (µg/m³ or ppm) — default ${DEFAULT_STUCK_MIN_VALUE}",
                      defaultValue: DEFAULT_STUCK_MIN_VALUE, range: "0..200", required: true
            }
        }

        section(h("Stale-Sensor Detection")) {
            paragraph """Flags a sensor that has stopped reporting entirely (battery dead, \
device offline, hub lost the radio). Checked on a 5-minute schedule."""
            input "sensorStaleEnabled", "bool",
                  title: "Enable stale-sensor detection",
                  defaultValue: true, submitOnChange: true
            if (settings.sensorStaleEnabled != false) {
                input "sensorStaleMinutes", "number",
                      title: "Minutes with no update before 'stale' — default ${DEFAULT_STALE_MINUTES}",
                      defaultValue: DEFAULT_STALE_MINUTES, range: "5..240", required: true
                paragraph "Set this comfortably longer than your sensors' normal reporting interval. " +
                          "Ecowitt sensors typically report every 1-5 minutes, so 30 is safe."
            }
        }

        section(h("Fault Notifications")) {
            paragraph """Sensor faults are reported through the normal notification system. \
You control whether they send push and/or voice alerts on the <b>Push Notifications</b> \
and <b>Voice Announcements</b> pages — look for the <i>'Indoor sensor fault detected or \
recovered'</i> event. By default, faults send a push notification but do <b>not</b> speak \
aloud, and they never override quiet hours."""
        }
    }
}

// ── Winter Humidity Control Page ──────────────────────────────────────────────

def humidityPage() {
    dynamicPage(name: "humidityPage", title: "💧 Humidity Control", install: false, uninstall: false) {

        section(h("What This Does")) {
            paragraph """In cold weather, indoor air that's too humid condenses on windows and \
inside wall cavities, causing mold and rot. The safe indoor humidity level depends on how \
cold it is outside — the colder it gets, the lower your indoor RH must be. This feature \
runs the ventilation fan to dry the air out when indoor humidity exceeds the limit for the \
current outdoor temperature.<br><br>
<b>An HRV/ERV is the right tool for this</b> — it exhausts humid indoor air and brings in \
cold, dry outdoor air (which becomes low-RH once heated). Requires an outdoor temperature \
sensor (already used for freeze protection) and at least one indoor humidity sensor.<br><br>
<i>Sources: University of Minnesota Extension, "Excess Moisture in the Home"; Canadian \
CMHC condensation guidance.</i>"""
            input "humidityControlEnabled", "bool",
                  title: "Enable humidity control",
                  defaultValue: false, submitOnChange: true
        }

        if (settings.humidityControlEnabled) {
            section(h("Indoor Humidity Sensor(s)")) {
                paragraph "Select sensors that report indoor relative humidity. Your Ecowitt " +
                          "WH46D sensors report humidity. The highest reading across all sensors is used."
                input "humiditySensors", "capability.relativeHumidityMeasurement",
                      title: "Indoor humidity sensor(s)",
                      required: false, multiple: true, submitOnChange: true
                paragraph """<b>Outdoor humidity sensor (strongly recommended).</b> Ventilation only \
dries the house when outdoor air holds <i>less absolute moisture</i> than indoor air. Relative \
humidity can't tell you that — dew point can. Without an outdoor humidity reading the app cannot \
compare, so in mild weather it will refuse to ventilate rather than risk making things wetter \
(during a rain event, outdoor air near saturation would <b>add</b> moisture)."""
                input "outdoorHumiditySensor", "capability.relativeHumidityMeasurement",
                      title: "Outdoor humidity sensor",
                      required: false, multiple: false, submitOnChange: true
                if (!outdoorHumiditySensor) {
                    paragraph warn("No outdoor humidity sensor. Humidity venting will only run when it is below ${DEWPOINT_CHECK_ABOVE_F}°F outside (cold air is always dry). In mild weather it will stay off.")
                }
                if (!outdoorTempSensor) {
                    paragraph warn("No outdoor temperature sensor is configured (set it on the Outdoor Air Quality page). Humidity control needs it to pick the right RH target and can't run without it.")
                }
            }

            section(h("RH Target Method")) {
                input "humidityUseCustom", "bool",
                      title: "Use my own simple threshold instead of the automatic table",
                      defaultValue: false, submitOnChange: true

                if (!settings.humidityUseCustom) {
                    paragraph """<b>Automatic table (recommended)</b> — the indoor RH ceiling tracks \
the outdoor temperature:<br>
• 40°F and warmer → 45%<br>
• 30–39°F → 40%<br>
• 20–29°F → 35%<br>
• 10–19°F → 30%<br>
• 0–9°F → 25%<br>
• -10 to -1°F → 20%<br>
• -20°F and colder → 15%<br><br>
When indoor RH rises above the ceiling for the current outdoor temp, the fan runs until \
RH drops back below it."""
                } else {
                    paragraph "Simple mode: enforce one RH ceiling, but only when it's cold enough outside to matter."
                    input "humidityColdBelowTemp", "number",
                          title: "Only control humidity when outdoor temp is below (°F)",
                          defaultValue: 32, range: "-20..60", required: true
                    input "humidityCustomMaxRh", "number",
                          title: "Maximum indoor RH% allowed below that temperature",
                          defaultValue: 35, range: "10..60", required: true
                }
            }

            section(h("Moisture Balance (advanced)")) {
                paragraph """These govern <i>when ventilating actually helps</i>.<br><br>
<b>Dew point margin:</b> outdoor air must be at least this many °F drier (by dew point) before \
the fan runs for humidity. Prevents pointless or counterproductive ventilation.<br>
<b>Maximum indoor dew point:</b> ${MAX_INDOOR_DEWPOINT_F}°F is the prudent ceiling used by ASHRAE's \
hot-and-humid guide and the EPA. Above it, the app will ventilate to dry even if RH looks acceptable."""
                input "dewPointMargin", "number",
                      title: "Outdoor must be drier by at least (°F dew point) — default ${DEWPOINT_MARGIN_F}",
                      defaultValue: DEWPOINT_MARGIN_F, range: "0..15", required: true
                input "maxIndoorDewPoint", "number",
                      title: "Maximum indoor dew point (°F) — default ${MAX_INDOOR_DEWPOINT_F}",
                      defaultValue: MAX_INDOOR_DEWPOINT_F, range: "45..70", required: true
            }

            section(h("Current Status")) {
                def rh = maxIndoorHumidity()
                def t  = currentOutdoorTemp()
                Integer tgt = humidityTargetRh()
                Map dry = ventilationWouldDry()
                Float inT = maxIndoorTemp()
                Float inDp  = (inT != null && rh != null) ? dewPointF(inT, rh) : null
                Float outRh = currentOutdoorHumidity()
                Float outDp = (t != null && outRh != null) ? dewPointF(t, outRh) : null
                paragraph "Dew point — indoor: <b>${inDp != null ? inDp.toInteger().toString() + "°F" : "--"}</b> | " +
                          "outdoor: <b>${outDp != null ? outDp.toInteger().toString() + "°F" : "--"}</b>" +
                          "${outRh != null ? " (outdoor ${outRh.toInteger()}% RH)" : ""}"
                paragraph dry.ok ?
                    "<font color='green'>✅ Ventilating would dry the house: ${dry.reason}</font>" :
                    "<font color='orange'>⚠️ Ventilating would NOT help: ${dry.reason}</font>"
                String rhStr = rh != null ? "${rh.toInteger()}%" : "--"
                String tStr  = t != null ? "${t.toInteger()}°F" : "--"
                String tgtStr = tgt != null ? "${tgt}%" : "n/a (warm enough, or data missing)"
                paragraph "Indoor RH: <b>${rhStr}</b> | Outdoor temp: <b>${tStr}</b> | Current RH ceiling: <b>${tgtStr}</b>"
                if (tgt != null && rh != null) {
                    boolean over = (atomicState.humidityVenting ?: false)
                    paragraph over ?
                        "<font color='orange'>⚠️ Indoor humidity is above the ceiling — fan will run to dry the air.</font>" :
                        "<font color='green'>✅ Indoor humidity is within the safe range for this temperature.</font>"
                }
            }

            section(h("How It Interacts With Everything Else")) {
                paragraph """<b>Air quality outranks humidity — always.</b> ASHRAE treats these as \
separate problems: the ventilation rate required for indoor air quality <i>"does not allow for \
humidity control. Additional ventilation or dehumidification may be required to remove \
moisture."</i> CO₂ and PM2.5 protect the people in the house right now; humidity is a slower \
building-durability issue. So:<br><br>
• If <b>CO₂ or indoor PM2.5</b> calls for the fan, it runs — even if outdoor air is humid and \
running imports some moisture. That trade is deliberate and is what the standards call for. \
The log notes it when it happens.<br>
• Humidity can start the fan <b>on its own</b> when air quality doesn't need it, but it never \
blocks or shortens a run that air quality requires.<br>
• Humidity venting <b>respects the wildfire and freeze guards</b>. You can't dry the air by \
pulling in smoke or dangerously cold air; the moisture waits until conditions clear.<br>
• Humidity venting also <b>respects the dew point check</b> — if outdoor air isn't actually \
drier, the fan won't run for humidity at all, because it would remove nothing.<br><br>
<b>In continuous mode</b> the fan already runs 24/7, so this feature adds nothing there. Worth \
knowing: during a mild rain event, continuous ventilation is itself importing humid air. That \
is the correct ASHRAE-compliant behavior — the remedy is a dehumidifier, not shutting off \
required ventilation — but it explains why indoor RH climbs during wet weather."""
            }
        }
    }
}

// ── Winter Humidity Control Page End ──────────────────────────────────────────

// ── Maintenance & Runtime Page ────────────────────────────────────────────────

def maintenancePage() {
    dynamicPage(name: "maintenancePage", title: "🔧 Maintenance & Runtime", install: false, uninstall: false) {

        section(h("What This Does")) {
            paragraph """Tracks how long the ventilation fan has run and reminds you when it's \
time to check the filters and heat-exchanger core. HRV/ERV filters need periodic cleaning \
and the core benefits from a seasonal check; runtime is a better trigger than the calendar \
because it reflects actual use.<br><br>
Runtime accumulates whenever the fan runs (including continuous mode, tallied daily)."""
            input "maintenanceEnabled", "bool",
                  title: "Enable maintenance reminders",
                  defaultValue: false, submitOnChange: true
        }

        if (settings.maintenanceEnabled) {
            section(h("Reminder Interval")) {
                paragraph "Default is 2160 runtime hours (about 90 days of continuous running). " +
                          "Adjust to your unit's filter guidance — the Lifebreath RNC200 manual " +
                          "recommends checking filters roughly every 3 months."
                input "maintenanceHours", "number",
                      title: "Remind me after this many runtime hours",
                      defaultValue: DEFAULT_MAINT_HOURS, range: "100..17520", required: true
            }

            section(h("Current Runtime")) {
                long totalMin = (atomicState.runtimeTotalMin ?: 0L) as Long
                long sinceMin = (atomicState.runtimeSinceMaintMin ?: 0L) as Long
                int intervalH = (settings.maintenanceHours ?: DEFAULT_MAINT_HOURS) as Integer
                int sinceH = (sinceMin / 60L) as Integer
                int totalH = (totalMin / 60L) as Integer
                int pct = intervalH > 0 ? Math.min(100, (sinceH * 100 / intervalH) as Integer) : 0
                String lastReset = atomicState.lastMaintenanceReset ?
                    new Date(atomicState.lastMaintenanceReset).format("MMM d, yyyy", location.timeZone) : "never"
                paragraph "Since last service: <b>${sinceH} hours</b> of ${intervalH} (${pct}%)<br>" +
                          "Lifetime total: <b>${totalH} hours</b><br>" +
                          "Last serviced: <b>${lastReset}</b>"
                if (atomicState.maintenanceNotified) {
                    paragraph warn("Maintenance is due. After servicing, tap the reset button below.")
                }
            }

            section(h("After Servicing")) {
                paragraph "Once you've cleaned the filters and checked the core, reset the counter."
                input "btnResetMaintenance", "button", title: "🔧 Reset Maintenance Counter"
            }
        }
    }
}

// ── Presence Page ─────────────────────────────────────────────────────────────

def presencePage() {
    dynamicPage(name: "presencePage", title: "🏠 Presence", install: false, uninstall: false) {

        section(h("Presence Sensors")) {
            paragraph """Works with any Hubitat <code>presenceSensor</code> — Hubitat mobile \
app geofence, Life360, combined presence sensors, virtual switches, etc.<br>
Someone home = ANY sensor present. Everyone away = ALL sensors not present.<br>
<b>Safety guards always apply regardless of presence.</b><br>
<i>Source: ASHRAE 62.2-2022 demand-controlled ventilation.</i>"""
            input "presenceEnabled", "bool", title: "Enable presence-based behavior", defaultValue: false, submitOnChange: true
            if (presenceEnabled) {
                input "presenceSensors", "capability.presenceSensor",
                      title: "Presence sensor(s)", required: false, multiple: true, submitOnChange: true
                if (presenceSensors) {
                    presenceSensors.each { s ->
                        def v = s.currentValue("presence")?.toString() ?: "unknown"
                        paragraph "${v == "present" ? "🟢" : "🔴"} <b>${s.displayName}:</b> ${v}"
                    }
                    paragraph "Household: <b>${someoneHome() ? "Someone home" : "Everyone away"}</b>"
                }
            }
        }

        if (presenceEnabled && presenceSensors) {
            section(h("Away Behavior")) {
                if (settings.operatingMode == MODE_CONTINUOUS) {
                    paragraph "Your fan is in <b>Continuous</b> mode. Choose what happens to continuous " +
                              "operation when everyone is away:"
                    input "continuousAwayBehavior", "enum",
                          title: "When everyone is away (continuous mode):",
                          options: [
                              (CONT_AWAY_KEEP):   "Keep running continuously (fresh air even when away)",
                              (CONT_AWAY_DEMAND): "Drop to demand-only (run only if CO₂/PM high)",
                              (CONT_AWAY_OFF):    "Turn fan off entirely until someone returns"
                          ],
                          defaultValue: CONT_AWAY_DEMAND, required: true, submitOnChange: true
                    paragraph "<b>Safety guards always apply</b> regardless of this setting — wildfire " +
                              "and freeze guards will still stop the fan."
                } else {
                    paragraph "Configure behavior for when everyone is away (${settings.operatingMode ?: "demand"} mode)."
                    input "awayBehavior", "enum",
                          title: "When everyone is away:",
                          options: [
                              "normal":   "Normal — same thresholds as home",
                              "relaxed":  "Relaxed CO₂ trigger (CO₂ builds slowly without occupants)",
                              "disabled": "Fan and purifiers OFF when away"
                          ],
                          defaultValue: "relaxed", required: true, submitOnChange: true
                    if (awayBehavior == "relaxed") {
                        input "co2HighAway", "number",
                              title: "Away CO₂ run threshold (ppm) — default 1500",
                              defaultValue: 1500, range: "800..3000", required: true
                        paragraph "Away mode uses normal PM2.5 thresholds — fire risk doesn't care if you're home."
                    }
                    if (awayBehavior == "disabled") {
                        paragraph warn("Fan and purifiers OFF when away. Guards remain active and will stop the fan if running when last person leaves.")
                    }
                }
            }

            section(h("Arrival Behavior")) {
                if ((settings.operatingMode ?: MODE_CONTINUOUS) == MODE_CONTINUOUS) {
                    paragraph "<font color='gray'>Your fan is in continuous mode, so it already runs whenever someone is home — there's no separate arrival cycle. These settings apply only in demand or scheduled mode.</font>"
                }
                input "runOnArrival", "bool",
                      title: "Run fan when someone arrives home",
                      defaultValue: true, submitOnChange: true
                if (runOnArrival) {
                    input "arrivalRunMinutes", "number",
                          title: "Run for this many minutes on arrival (0 = until normal stop condition)",
                          defaultValue: 30, range: "0..120", required: true
                }
            }
        }
    }
}

// ── Timing Page ───────────────────────────────────────────────────────────────

def timingPage() {
    dynamicPage(name: "timingPage", title: "⏱️ Timing", install: false, uninstall: false) {
        section(h("Fan Run Time Controls")) {
            paragraph """<b>Min run:</b> Once started, fan runs at least this long — prevents short-cycling.<br>
<b>Cooldown:</b> After stopping, waits before starting again — allows readings to stabilize.<br>
<b>Max run:</b> Safety net — stops fan after this long and re-evaluates."""
            input "minRunMinutes",   "number", title: "Min run time (min) — default ${DEFAULT_MIN_RUN}",  defaultValue: DEFAULT_MIN_RUN,  range: "1..60",   required: true
            input "cooldownMinutes", "number", title: "Cooldown after stop (min) — default ${DEFAULT_COOLDOWN}", defaultValue: DEFAULT_COOLDOWN, range: "5..120",  required: true
            input "maxRunMinutes",   "number", title: "Max continuous run (min) — default ${DEFAULT_MAX_RUN}",   defaultValue: DEFAULT_MAX_RUN,  range: "30..480", required: true
        }
    }
}

// ── Manual Override Page ──────────────────────────────────────────────────────

def manualOverridePage() {
    dynamicPage(name: "manualOverridePage", title: "🖐️ Manual Override Policy", install: false, uninstall: false) {

        section(h("How the App Detects Manual Control")) {
            paragraph """The app tracks every fan state change it causes. If the fan switch \
changes to a state the app did not initiate — someone turned it on or off manually, \
via a physical switch, another app, or a voice assistant — the app detects the mismatch \
and responds per the policy you select below.<br><br>
<b>Example:</b> App thinks fan should be OFF (CO₂ is fine), but the fan switches ON. \
That's a manual override — you turned it on for some reason the app doesn't know about."""
        }

        section(h("Manual Override Policy")) {
            paragraph "What should the app do when it detects a manual override?"
            input "manualOverridePolicy", "enum",
                  title: "When manual override is detected:",
                  options: [
                      (MANUAL_RESUME_TIMED):      "Pause automation for N minutes, then resume",
                      (MANUAL_RESUME_NEXT_EVAL):  "Pause until the next sensor event triggers re-evaluation",
                      (MANUAL_RESUME_INDEFINITE): "Pause indefinitely — only resume when I manually re-enable here"
                  ],
                  defaultValue: MANUAL_RESUME_TIMED, required: true, submitOnChange: true

            if (manualOverridePolicy == MANUAL_RESUME_TIMED) {
                input "manualOverrideMinutes", "number",
                      title: "Pause for this many minutes before resuming",
                      defaultValue: 30, range: "5..480", required: true
                paragraph "After ${settings.manualOverrideMinutes ?: 30} minutes, the app re-evaluates " +
                          "conditions and resumes normal operation. The fan will be corrected to match " +
                          "what the sensors say is needed."
            }

            if (manualOverridePolicy == MANUAL_RESUME_NEXT_EVAL) {
                paragraph "The app will resume when the next CO₂, PM2.5, outdoor PM, or temperature " +
                          "sensor event fires. This is typically within 60 seconds."
            }

            if (manualOverridePolicy == MANUAL_RESUME_INDEFINITE) {
                paragraph warn("Automation stays paused until you tap 'Resume Automation' below. " +
                               "Safety guards (wildfire, freeze) still apply and will stop the fan if triggered.")
            }
        }

        if (atomicState.manualOverrideActive) {
            section(h("Manual Override Currently Active")) {
                paragraph warn("Automation is currently paused due to manual override. Fan is not being controlled by this app.")
                input "btnResumeNow", "button", title: "▶️ Resume Automation Now"
            }
        }

        section(h("Safety Override Behavior")) {
            paragraph """<b>Safety guards always apply during manual override.</b><br>
If outdoor PM2.5 or temperature exceeds guard thresholds while manual override is active, \
the app will still stop the fan — protecting your home from wildfire smoke or equipment \
freeze damage is more important than respecting a manual override."""
            input "guardsDuringManual", "bool",
                  title: "Safety guards apply even during manual override (recommended)",
                  defaultValue: true
        }
    }
}

// ── Push Notification Page ────────────────────────────────────────────────────

def notificationPage() {
    dynamicPage(name: "notificationPage", title: "📲 Push Notifications", install: false, uninstall: false) {

        section(h("Notification Devices")) {
            paragraph """Select devices that receive push or text notifications — Hubitat mobile app, \
Pushover, SMS, etc.<br><br>
<b>Notifications are designed not to overload your phone:</b><br>
• Each message says what happened, what the app did, and why — in one glance<br>
• A guard event sends <b>one</b> notification, not one per device<br>
• Every event has a cooldown, so a flapping sensor can't bury you<br>
• Routine chatter (fan start/stop, individual purifiers) is <b>off by default</b> — \
the guard message already tells you what was turned on or off"""
            input "notifyDevices", "capability.notification",
                  title: "Push notification device(s)",
                  description: "Tap to select", required: false, multiple: true, submitOnChange: true
        }

        if (notifyDevices) {
            section(h("Which Events Send Push Notifications")) {
                paragraph "Select the events that should send a push notification to your devices."
                NOTIF_EVENTS.each { evt ->
                    input "push_${evt.key}", "bool",
                          title: evt.label,
                          defaultValue: defaultNotifState(evt.key)
                }
            }
        }
    }
}

// ── TTS Page ──────────────────────────────────────────────────────────────────

def ttsPage() {
    dynamicPage(name: "ttsPage", title: "🔊 Voice Announcements (TTS)", install: false, uninstall: false) {

        section(h("TTS / Speech Devices")) {
            paragraph """Select speakers for voice announcements — Sonos, IKEA Symfonisk, \
HomePod, Google Home, Echo Speaks, AirPlay speakers, or any Hubitat \
<code>SpeechSynthesis</code> device.<br><br>
<b>Works with:</b> Sonos (built-in integration), IKEA Symfonisk (Sonos-compatible), \
Apple AirPlay speakers (built-in AirPlay integration), Google Home/Nest (community \
drivers), Amazon Echo (Echo Speaks app).<br><br>
Voice messages are detailed and natural-sounding, e.g.: \
<i>'Indoor CO₂ has reached 1,050 parts per million. The ventilation fan has started. \
Air quality should improve in 10 to 15 minutes.'</i>"""
            input "ttsDevices", "capability.speechSynthesis",
                  title: "TTS / speech device(s)",
                  description: "Tap to select", required: false, multiple: true, submitOnChange: true
        }

        if (ttsDevices) {
            section(h("TTS Volume")) {
                paragraph "Optional. Leave blank to use each speaker's current volume."
                input "ttsVolume", "number",
                      title: "Announcement volume (0-100, blank = use current volume)",
                      range: "0..100", required: false
            }

            section(h("Announcement Prefix (fixes clipped first words)")) {
                paragraph """Many TTS devices (Sonos, Echo, Google) clip the first word or two while \
they wake up and connect to Hubitat. A short repeating prefix gives the speaker time to \
catch up, so the actual message is heard in full.<br><br>
The prefix is spoken before <i>every</i> announcement. Default: \
<i>"Attention, attention, attention..."</i> — by the time it finishes, the device is \
streaming cleanly and the real message lands. A trailing space is added automatically.<br><br>
Set to blank if your speakers don't clip."""
                input "ttsPrefix", "text",
                      title: "Spoken prefix before every message",
                      defaultValue: "Attention, attention, attention... ",
                      required: false
            }

            section(h("Customize Announcement Wording")) {
                paragraph "View and edit the exact words spoken for each event."
                href "ttsMessagesPage",
                     title: "✏️ Edit Announcement Messages",
                     description: "See and change what each announcement says"
            }

            section(h("Quiet Hours")) {
                paragraph "Suppress voice announcements during these hours (e.g. overnight). " +
                          "Push notifications still send during quiet hours."
                input "quietHoursEnabled", "bool",
                      title: "Enable quiet hours", defaultValue: false, submitOnChange: true
                if (quietHoursEnabled) {
                    input "quietStart", "time", title: "Quiet hours start", required: true
                    input "quietEnd",   "time", title: "Quiet hours end",   required: true
                    paragraph "During quiet hours, voice announcements are suppressed. " +
                              "<b>Exception:</b> Safety guard activations (wildfire, freeze) " +
                              "still announce at all times — these are urgent health/equipment alerts."
                    input "quietAllowSafetyAlerts", "bool",
                          title: "Allow safety guard announcements during quiet hours",
                          defaultValue: true
                }
            }

            section(h("Which Events Trigger Voice Announcements")) {
                paragraph "Select the events that should speak aloud."
                NOTIF_EVENTS.each { evt ->
                    input "tts_${evt.key}", "bool",
                          title: evt.label,
                          defaultValue: defaultTtsState(evt.key)
                }
            }
        }
    }
}

// ── TTS Messages Editing Page ─────────────────────────────────────────────────

def ttsMessagesPage() {
    dynamicPage(name: "ttsMessagesPage", title: "✏️ Announcement Messages", install: false, uninstall: false) {

        section(h("How This Works")) {
            paragraph """Each event below shows the words currently spoken. Leave a field blank to \
use the built-in default (shown as the placeholder/help text). Type your own wording to \
override it.<br><br>
<b>Dynamic values</b> use tokens in curly braces — these are replaced with live values \
when the message is spoken. If you remove a token, that value simply won't be included.<br><br>
The spoken <b>prefix</b> ("${(settings.ttsPrefix ?: "Attention, attention, attention...").trim()}") \
is added before every message automatically — don't repeat it here.<br><br>
<b>Available tokens by event:</b><br>
• Fan start/stop: <code>{fan}</code> (your fan type), <code>{reason}</code>, <code>{minutes}</code><br>
• Max run: <code>{fan}</code>, <code>{minutes}</code><br>
• Wildfire: <code>{pm25}</code>, <code>{threshold}</code>, <code>{fan}</code><br>
• Freeze: <code>{temp}</code>, <code>{threshold}</code>, <code>{fan}</code><br>
• CO₂ events: <code>{co2}</code>, <code>{threshold}</code>, <code>{minutes}</code>, <code>{guard}</code>, <code>{fan}</code><br>
• Arrival: <code>{fan}</code>, <code>{minutes}</code><br>
• Manual override: <code>{device}</code>, <code>{state}</code> (on/off), <code>{policy}</code><br>
• Purifier start: <code>{purifier}</code>, <code>{reason}</code><br>
• Sensor fault: <code>{sensor}</code>, <code>{detail}</code><br>
• Outdoor disagree: <code>{worse}</code>, <code>{better}</code>, <code>{worseSource}</code>, <code>{betterSource}</code><br>
• Forecast bad air: <code>{aqi}</code>"""
        }

        section(h("Messages")) {
            NOTIF_EVENTS.each { evt ->
                paragraph "<b>${evt.label}</b><br><i>Default:</i> ${defaultTtsText(evt.key)}"
                input "ttsMsg_${evt.key}", "text",
                      title: "Custom wording for '${evt.label}' (blank = use default)",
                      required: false
            }
        }
    }
}

/**
 * Reference text showing the default spoken wording for each event.
 * Used only for display on the TTS Messages page so the user can see
 * what they're overriding. The live messages are still built at each
 * notify() call site; this mirrors them with {tokens} for readability.
 */
String defaultTtsText(String key) {
    Map<String,String> defaults = [
        fanStart:        "The {fan} has started. Reason: {reason}.",
        fanStop:         "The {fan} has stopped after {minutes} minutes. Reason: {reason}.",
        wildfireOn:      "Alert: outdoor air quality is poor. Outdoor PM 2.5 is {pm25} micrograms, above the {threshold} threshold. The {fan} has been stopped. Running air purifiers.",
        wildfireOff:     "Good news: outdoor air quality has improved. PM 2.5 is now {pm25} micrograms. Resuming normal ventilation.",
        freezeOn:        "Alert: outdoor temperature is {temp} degrees, below the {threshold} degree minimum. The {fan} has been stopped to protect the heat exchanger.",
        freezeOff:       "Outdoor temperature is now {temp} degrees. Resuming normal ventilation.",
        co2Rising:       "Warning: indoor CO 2 is rising to {co2} parts per million. The {fan} is locked out due to {guard}. Air purifiers are running. Emergency ventilation will start at {threshold} parts per million.",
        co2Override:     "Emergency ventilation override. Indoor CO 2 has reached {co2} parts per million. Running the {fan} for {minutes} minutes despite {guard}. The guard will resume after ventilation completes.",
        manualOverride:  "Manual override detected. {device} was turned {state} outside of automation. {policy}",
        manualResume:    "Resuming automation.",
        noOutdoorData:   "Warning: outdoor air quality data is unavailable. The wildfire guard is disabled.",
        maxRun:          "The {fan} has been running for the maximum allowed time of {minutes} minutes. Stopping now to re-evaluate air quality.",
        purifierOn:      "{purifier} is now running. {reason}",
        arrivalVent:     "Welcome home. Starting the {fan} for {minutes} minutes to bring in fresh air.",
        sensorFault:     "Warning: {sensor} has a sensor fault. The reading is {detail}.",
        outdoorDisagree: "Warning: outdoor air quality sensors disagree. {worseSource} reads {worse} micrograms, {betterSource} reads {better}. Using the worse reading for safety.",
        forecastBadAir:  "Air quality forecast alert. Poor air is expected today, with a forecast index of {aqi}. The system will bring in fresh air proactively to lower CO 2 before conditions worsen.",
        ventAborted:     "Ventilation stopped early. Indoor particulate rose {rise} micrograms while the {fan} was running, so outdoor smoke is getting inside. Returning to lockout and relying on the air purifiers.",
        humidityCannotVent: "Indoor humidity is {rh} percent, above the target of {target}. However outdoor air is not drier than indoor air right now, so ventilating would not remove moisture. A dehumidifier is needed instead.",
        maintenanceDue:  "Maintenance reminder. The {fan} has run for {hours} hours since its last service. It's time to check the filters and heat exchanger core."
    ]
    return defaults[key] ?: "(spoken when this event occurs)"
}

// ── Diagnostics Page ──────────────────────────────────────────────────────────

def diagnosticsPage() {
    dynamicPage(name: "diagnosticsPage", title: "🩺 Diagnostics & Status", install: false, uninstall: false) {

        section(h("Fan Status")) {
            if (fanSwitch) {
                def sw      = fanSwitch.currentValue("switch") ?: "unknown"
                def running = atomicState.fanRunning ?: false
                def started = atomicState.fanStartedAt  ? new Date(atomicState.fanStartedAt).format("MMM d h:mm a", location.timeZone) : "never"
                def stopped = atomicState.fanStoppedAt  ? new Date(atomicState.fanStoppedAt).format("MMM d h:mm a", location.timeZone) : "never"
                def mode    = atomicState.lastRunMode   ?: "—"
                def manual  = atomicState.manualOverrideActive ?: false
                paragraph "Switch: <b>${sw.toUpperCase()}</b> | App tracking: <b>${running ? "RUNNING" : "STOPPED"}</b><br>" +
                          "Last started: <b>${started}</b> | Last stopped: <b>${stopped}</b><br>" +
                          "Last mode: <b>${mode}</b><br>" +
                          "${manual ? warn("Manual override active — automation paused") : "Manual override: none"}"
            } else {
                paragraph "<font color='green'>ℹ️ No fan configured — purifier-only mode</font>"
            }
        }

        section(h("Active Guards")) {
            def wf     = atomicState.wildfireGuardActive ?: false
            def fr     = atomicState.freezeGuardActive   ?: false
            def cd     = isCooldownActive()
            def mr     = isMinRunActive()
            def wfTime = atomicState.wildfireGuardSince ? new Date(atomicState.wildfireGuardSince).format("MMM d h:mm a", location.timeZone) : "—"
            paragraph "${wf ? "🔴" : "🟢"} Wildfire guard: ${wf ? "<b>ACTIVE since ${wfTime}</b>" : "clear"}<br>" +
                      "${fr ? "🔴" : "🟢"} Freeze guard: ${fr ? "<b>ACTIVE</b>" : "clear"}<br>" +
                      "${cd ? "🟡" : "🟢"} Cooldown: ${cd ? "<b>active</b>" : "clear"}<br>" +
                      "${mr ? "🟡" : "🟢"} Min run: ${mr ? "<b>active</b>" : "clear"}"
            if (atomicState.lastVentPmRise != null || atomicState.ventAborted) {
                Float r = atomicState.lastVentPmRise as Float
                paragraph (atomicState.ventAborted ?
                    warn("Last CO₂ override was ABORTED — indoor PM2.5 climbed past the abort margin. Outdoor particulate is getting through the fan filter.") :
                    "Last CO₂ override filtration result: indoor PM2.5 rose <b>${r?.toInteger() ?: 0} µg/m³</b> during the run" +
                    (r != null && r <= 2 ? " <font color='green'>(filter held well)</font>" : ""))
            }
        }

        section(h("Outdoor Data")) {
            def src       = atomicState.outdoorPmSource ?: "none"
            def effective = atomicState.currentOutdoorPm25 != null ? "${atomicState.currentOutdoorPm25} µg/m³" : "--"
            def localPm   = atomicState.localOutdoorPm25  != null ? "${(atomicState.localOutdoorPm25 as float).toInteger()} µg/m³" : "--"
            def airnowPm  = atomicState.airnowOutdoorPm25 != null ? "${(atomicState.airnowOutdoorPm25 as float).toInteger()} µg/m³ (AQI ${atomicState.airnowLastAqi ?: "--"})" : "--"
            def at        = atomicState.airnowLastPoll ? new Date(atomicState.airnowLastPoll).format("h:mm a", location.timeZone) : "never"
            def cat = atomicState.airnowLastCategory ? " — ${atomicState.airnowLastCategory}" : ""
            paragraph "Effective outdoor PM2.5: <b>${effective instanceof Number ? (effective as Float).toInteger() : effective} µg/m³</b> (source: ${src})<br>" +
                      "Local sensor: <b>${localPm}</b> | AirNow: <b>${airnowPm}</b>${cat} at <b>${at}</b>"
            // Show disagreement warning if both present and differ
            Float lpm = atomicState.localOutdoorPm25  as Float
            Float apm = atomicState.airnowOutdoorPm25 as Float
            if (lpm != null && apm != null && Math.abs(lpm - apm) > OUTDOOR_DISAGREE_MARGIN) {
                paragraph warn("Local sensor and AirNow disagree by ${Math.abs(lpm - apm).toInteger()} µg/m³ — using worse reading. Check sensor or investigate local air quality event.")
            }
            if (settings.forecastEnabled) {
                def fAqi  = atomicState.forecastLastAqi != null ? atomicState.forecastLastAqi : "--"
                def fBad  = (atomicState.forecastBadAir ?: false)
                if (atomicState.forecastNoCoverage) {
                    paragraph "Forecast: <font color='gray'>no AirNow forecast coverage for this area — feature inactive</font>"
                } else if (atomicState.forecastLastPoll == null) {
                    paragraph warn("Forecast enabled but not yet polled. Tap Done on the main app page to schedule it — the first poll runs about a minute after.")
                } else {
                    def fWhen = new Date(atomicState.forecastLastPoll).format("MMM d h:mm a", location.timeZone)
                    String ad = (atomicState.forecastActionDay ?: false) ? " <b>[Action Day]</b>" : ""
                    paragraph "Forecast: <b>PM2.5 AQI ${fAqi}</b>${ad} ${fBad ? "<font color='orange'>⚠️ bad-air day — proactive ventilation armed</font>" : "(clear)"} at ${fWhen}"
                }
            }
        }

        section(h("Indoor Readings")) {
            indoorSensors?.each { s ->
                def co2      = s.currentValue("carbonDioxide") ?: "--"
                def pm25     = s.currentValue("pm25")          ?: "--"
                def co2T     = settings.co2High        ?: DEFAULT_CO2_HIGH
                def pm25T    = settings.pm25IndoorHigh ?: DEFAULT_PM25_IN_HIGH
                boolean co2Fault  = atomicState["sensor_fault_carbonDioxide_${s.id}"] ?: false
                boolean pm25Fault = atomicState["sensor_fault_pm25_${s.id}"] ?: false
                def cf       = (co2  != "--" && (co2 as BigDecimal)  > co2T)  ? " ⚠️" : ""
                def pf       = (pm25 != "--" && (pm25 as BigDecimal) > pm25T) ? " ⚠️" : ""
                String faultStr = ""
                if (co2Fault || pm25Fault) {
                    List f = []
                    if (co2Fault)  f << "CO₂"
                    if (pm25Fault) f << "PM2.5"
                    faultStr = " <font color='red'>🚨 FAULT (${f.join(', ')}) — excluded from automation</font>"
                }
                paragraph "<b>${s.displayName}:</b> CO₂ <b>${co2}${cf}</b> ppm | PM2.5 <b>${pm25}${pf}</b> µg/m³${faultStr}"
            }
            if (settings.humidityControlEnabled && humiditySensors) {
                def rh = maxIndoorHumidity()
                def t  = currentOutdoorTemp()
                Integer tgt = humidityTargetRh()
                if (tgt != null && rh != null) {
                    boolean venting = atomicState.humidityVenting ?: false
                    Float hIn  = atomicState.humidityIndoorDp  as Float
                    Float hOut = atomicState.humidityOutdoorDp as Float
                    String dps = (hIn != null || hOut != null) ?
                        " | dew point in <b>${hIn != null ? hIn.toInteger() + "°F" : "--"}</b> / out <b>${hOut != null ? hOut.toInteger() + "°F" : "--"}</b>" : ""
                    String why = atomicState.humidityVentReason ? "<br><font color='gray'>${atomicState.humidityVentReason}</font>" : ""
                    paragraph "💧 Humidity: <b>${rh.toInteger()}%</b> indoor, ceiling <b>${tgt}%</b> at ${t?.toInteger()}°F outdoor${dps} " +
                              (venting ? "<font color='orange'>⚠️ venting to dry</font>" : "<font color='green'>✅ within range</font>") + why
                } else if (rh != null) {
                    paragraph "💧 Humidity: <b>${rh.toInteger()}%</b> indoor — no RH ceiling active (warm enough outside, or outdoor temp unavailable)"
                }
            }
        }

        section(h("Purifiers")) {
            if (purifierSwitches) {
                purifierSwitches.each { p ->
                    def sw       = p.currentValue("switch") ?: "unknown"
                    def policy   = settings["purifier_policy_${p.id}"] ?: "auto"
                    def moActive = atomicState["purifier_mo_${p.id}"] ?: false
                    def policyLabel = ["auto": "Auto", "manualRespected": "Manual override respected",
                                       "alwaysOn": "Always on", "scheduled": "Scheduled"][policy] ?: "Auto"
                    def schedNote = ""
                    if (policy == "scheduled") {
                        boolean inWin = inPurifierSchedule(p.id)
                        schedNote = " | ${inWin ? "in window" : "outside window"}"
                    }
                    def moNote = moActive ? " | ⚠️ manual override" : ""
                    paragraph "${sw == "on" ? "🟢" : "⚪"} <b>${p.displayName}:</b> ${sw.toUpperCase()} " +
                              "| Policy: ${policyLabel}${schedNote}${moNote}"
                }
            } else {
                paragraph "No purifiers configured."
            }
        }
    }
}

// ============================================================
// LIFECYCLE
// ============================================================

def uninstalled() {
    // Remove the dashboard status child device on app uninstall
    getChildDevices()?.each { d ->
        try { deleteChildDevice(d.deviceNetworkId) } catch (e) { logW "Child cleanup: ${e.message}" }
    }
}

def installed() {
    logI "v${APP_VERSION} installed"
    initState()
    initialize()
}

def updated() {
    logI "v${APP_VERSION} updated"
    unsubscribe()
    unschedule()
    initState()
    initialize()
}

def initialize() {
    if (indoorSensors) {
        subscribe(indoorSensors, "carbonDioxide", "sensorHandler")
        subscribe(indoorSensors, "pm25",          "sensorHandler")
        logI "Subscribed: ${indoorSensors.size()} indoor sensor(s)"
    }
    if (outdoorPmSensor)   { subscribe(outdoorPmSensor,   "pm25",        "outdoorPmHandler")   }
    if (outdoorTempSensor) { subscribe(outdoorTempSensor, "temperature", "outdoorTempHandler") }
    if ((settings.humidityControlEnabled ?: false) && humiditySensors) {
        subscribe(humiditySensors, "humidity", "sensorHandler")
        logI "Subscribed: ${humiditySensors.size()} humidity sensor(s) for winter humidity control"
    }
    if (presenceEnabled && presenceSensors) {
        subscribe(presenceSensors, "presence", "presenceHandler")
    }
    // Subscribe to fan switch to detect manual overrides
    if (fanSwitch) {
        subscribe(fanSwitch, "switch", "fanSwitchHandler")
        logI "Subscribed: fan switch (manual override detection)"
    }
    // Subscribe to each purifier switch for per-device manual override detection
    if (purifierSwitches) {
        subscribe(purifierSwitches, "switch", "purifierSwitchHandler")
        logI "Subscribed: ${purifierSwitches.size()} purifier switch(es) (manual override detection)"
    }
    if (!localOnlyMode && airnowApiKey) {
        schedule("0 5 * * * ?", "pollAirNow")
        runIn(30, "pollAirNow")
        logI "AirNow polling scheduled"
        // Forecast is updated once daily by agencies; poll twice (morning +
        // afternoon) to catch same-day updates. Opt-in only. If a prior run
        // found no coverage for this area, scheduleForecastPolling() backs off
        // to a weekly heartbeat instead.
        if (forecastEnabled) {
            scheduleForecastPolling()
            runIn(45, "pollAirNowForecast")
        }
    }
    // Periodic sensor staleness check (every 5 min) — catches sensors that
    // stop reporting entirely (per-reading validation can't detect silence)
    if (indoorSensors) {
        runEvery5Minutes("checkSensorStaleness")
        logI "Sensor staleness check scheduled (every 5 min)"
    }
    // Daily runtime accumulation for maintenance tracking (catches continuous
    // mode where the fan rarely stops). Anchor starts now.
    atomicState.runtimeTickAnchor = now()
    schedule("0 30 0 * * ?", "runtimeTick")  // 00:30 daily
    // Periodic re-evaluation — needed for scheduled-mode window boundaries and
    // as a continuous-mode recovery net (re-asserts fan-on if it drifted off).
    // Every 10 min is frequent enough for schedule edges, light on the hub.
    if (fanSwitch && (settings.operatingMode ?: MODE_CONTINUOUS) != MODE_DEMAND) {
        runEvery10Minutes("evaluateConditions")
        logI "Periodic re-evaluation scheduled (every 10 min) for ${settings.operatingMode ?: MODE_CONTINUOUS} mode"
    }
    runIn(5, "evaluateConditions")
    captureBaseName()
    // Reset per-sensor validation state on every update. Prior versions counted
    // repeated reads of the same cached value toward the stuck-detector, so any
    // history carried over would be polluted and could fire a false fault.
    clearSensorValidationState()
    ensureStatusDevice()
    updateAppLabel()
}

/**
 * Capture and store the clean app name as the label base.
 * Called during initialize() so user renames are respected.
 * Strips any existing badge (HTML or bare emoji) so re-running
 * after an update never stacks badges.
 */
void captureBaseName() {
    String raw = app.label ?: "HRV / Air Quality Manager"
    // Cut at the HTML badge marker if present
    int idx = raw.indexOf(' <font')
    if (idx > 0) raw = raw.substring(0, idx)
    // Cut at any known badge emoji (covers labels where Hubitat already
    // rendered/stripped the HTML, leaving bare emoji text)
    ["🔥","❄️","⚠️","🖐️","🆘","💨","🌀","⏸","✅"].each { emoji ->
        int e = raw.indexOf(emoji)
        if (e > 0 && e < raw.length()) raw = raw.substring(0, e)
    }
    String base = raw.replaceAll('<[^>]+>', '').trim()
    if (!base) base = "HRV / Air Quality Manager"
    atomicState.appBaseName = base
    logD "Captured app base name: '${base}'"
}

/**
 * Scheduled staleness check — runs periodically. A sensor that stops
 * reporting entirely won't fire sensorHandler, so per-reading validation
 * can't catch it. This compares each sensor's last-valid timestamp against
 * now and flags stale sensors. Scheduled from initialize().
 */
def checkSensorStaleness() {
    if (!indoorSensors) return
    if (settings.sensorStaleEnabled == false) return  // user can disable entirely
    int staleMin = (settings.sensorStaleMinutes ?: DEFAULT_STALE_MINUTES) as Integer
    indoorSensors.each { s ->
        String id = s.id?.toString()
        if (!id) return
        ["carbonDioxide", "pm25"].each { attr ->
            String key = "sensor_${attr}_${id}"
            Long lastTs = atomicState["${key}_ts"] as Long
            // Only flag attributes the sensor has actually reported before
            if (lastTs != null && (now() - lastTs) > (staleMin * 60 * 1000L)) {
                if (!(atomicState["sensor_fault_${attr}_${id}"] ?: false)) {
                    flagSensor(id, s.displayName, attr,
                               "stale: no update in over ${staleMin} minutes", false)
                }
            }
        }
    }
}

/**
 * Wipe per-sensor validation state (stuck history, fault flags, recovery and
 * timestamp trackers). Called from initialize() so a settings change or app
 * update starts validation from a clean slate.
 */
void clearSensorValidationState() {
    if (!indoorSensors) return
    indoorSensors.each { s ->
        String id = s.id?.toString()
        if (!id) return
        ["carbonDioxide", "pm25"].each { attr ->
            String key = "sensor_${attr}_${id}"
            atomicState["${key}_history"]      = []
            atomicState["${key}_recoverCount"] = 0
            atomicState["${key}_lastEventTs"]  = null
            atomicState["${key}_lastSeenVal"]  = null
            atomicState["sensor_fault_${attr}_${id}"] = false
        }
    }
    logD "Sensor validation state cleared"
}

def initState() {
    if (atomicState.fanRunning           == null) atomicState.fanRunning           = false
    if (atomicState.wildfireGuardActive  == null) atomicState.wildfireGuardActive  = false
    if (atomicState.freezeGuardActive    == null) atomicState.freezeGuardActive    = false
    if (atomicState.emergencyVentRunning == null) atomicState.emergencyVentRunning = false
    if (atomicState.manualOverrideActive == null) atomicState.manualOverrideActive = false
    if (atomicState.appControlledChange  == null) atomicState.appControlledChange  = false
}

// ============================================================
// APP BUTTON HANDLER (manual resume button)
// ============================================================

void appButtonHandler(String buttonName) {
    if (buttonName == "btnApplyHealthDefaults") {
        logI "Applying recommended health-protective threshold defaults"
        app.updateSetting("pm25IndoorHigh",       [type: "number", value: DEFAULT_PM25_IN_HIGH])
        app.updateSetting("pm25IndoorLow",        [type: "number", value: DEFAULT_PM25_IN_LOW])
        app.updateSetting("pm25OutdoorMax",       [type: "number", value: DEFAULT_PM25_OUT_MAX])
        app.updateSetting("co2High",              [type: "number", value: DEFAULT_CO2_HIGH])
        app.updateSetting("co2Low",               [type: "number", value: DEFAULT_CO2_LOW])
        app.updateSetting("emergencyAfterMinutes",[type: "number", value: DEFAULT_EMERGENCY_AFTER])
        app.updateSetting("ventPmAbortMargin",    [type: "number", value: DEFAULT_VENT_PM_ABORT])
        runIn(2, "evaluateConditions")
        return
    }
    if (buttonName == "btnResetMaintenance") {
        logI "Maintenance counter reset via app UI"
        atomicState.runtimeSinceMaintMin = 0L
        atomicState.maintenanceNotified  = false
        atomicState.lastMaintenanceReset = now()
        updateAppLabel()
        return
    }
    if (buttonName == "btnResumeNow") {
        logI "Manual resume triggered from app UI"
        atomicState.manualOverrideActive = false
        notify("manualResume",
               "Resuming automation — manual override cleared via app.",
               "Resuming automation. Manual override has been cleared.")
        runIn(2, "evaluateConditions")
        return
    }
    // Per-device purifier resume buttons: "purifier_resumeBtn_{deviceId}"
    if (buttonName.startsWith("purifier_resumeBtn_")) {
        String deviceId = buttonName.replace("purifier_resumeBtn_", "")
        def device = purifierSwitches?.find { it.id == deviceId }
        if (device) {
            logI "Purifier manual override cleared via app for ${device.displayName}"
            atomicState["purifier_mo_${deviceId}"] = false
            notify("manualResume",
                   "Resuming automation for ${device.displayName}.",
                   "Resuming automation for ${device.displayName}. Manual override cleared.")
            runIn(2, "evaluateConditions")
        }
    }
}

// ============================================================
// EVENT HANDLERS
// ============================================================

def sensorHandler(evt) {
    logD "Indoor sensor: ${evt.device.displayName} ${evt.name}=${evt.value}"
    // Clear fan manual override if policy is nexteval
    if (atomicState.manualOverrideActive &&
        (settings.manualOverridePolicy == MANUAL_RESUME_NEXT_EVAL)) {
        logI "Fan manual override cleared by sensor event"
        atomicState.manualOverrideActive = false
        notify("manualResume",
               "Resuming fan automation after sensor event.",
               "Resuming fan automation. Manual override cleared by new air quality reading.")
    }
    // Clear any purifier nexteval overrides
    if (purifierSwitches) {
        purifierSwitches.each { p ->
            String deviceId = p.id?.toString()
            if (deviceId && (atomicState["purifier_mo_${deviceId}"] ?: false)) {
                String moPolicy = settings["purifier_moPolicy_${deviceId}"] ?: "timed"
                if (moPolicy == "nexteval") {
                    logI "Purifier override cleared by sensor event: ${p.displayName}"
                    atomicState["purifier_mo_${deviceId}"] = false
                    notify("manualResume",
                           "Resuming automation for ${p.displayName} after sensor event.",
                           "Resuming automation for ${p.displayName}.")
                }
            }
        }
    }
    runIn(2, "evaluateConditions")
}

def fanSwitchHandler(evt) {
    // Ignore changes the app caused
    if (atomicState.appControlledChange) {
        logD "fanSwitchHandler: app-controlled change — ignoring"
        atomicState.appControlledChange = false
        return
    }
    // App didn't cause this — it's a manual override
    boolean fanNowOn  = (evt.value == "on")
    boolean appWantsOn = atomicState.fanRunning ?: false

    if (fanNowOn != appWantsOn) {
        logW "Manual override detected: fan is ${evt.value} but app expects ${appWantsOn ? "on" : "off"}"
        handleManualOverride(fanNowOn)
    }
}

void handleManualOverride(boolean fanNowOn) {
    atomicState.manualOverrideActive = true
    atomicState.fanRunning = fanNowOn  // sync tracking to reality

    String policyDesc = [
        (MANUAL_RESUME_TIMED):      "Will resume in ${settings.manualOverrideMinutes ?: 30} minutes.",
        (MANUAL_RESUME_NEXT_EVAL):  "Will resume on next sensor reading.",
        (MANUAL_RESUME_INDEFINITE): "Will remain paused until you resume in the app."
    ][settings.manualOverridePolicy ?: MANUAL_RESUME_TIMED]

    String pushMsg = "Manual override detected: fan was turned ${fanNowOn ? "ON" : "OFF"} externally. " +
                     "Automation paused. ${policyDesc}"
    String ttsMsg  = "Manual override detected. The ${fanName()} was turned ${fanNowOn ? "on" : "off"} " +
                     "outside of automation. ${policyDesc}"

    notify("manualOverride", pushMsg, ttsMsg,
           [device: fanName(), fan: fanName(), state: (fanNowOn ? "on" : "off"), policy: policyDesc])

    if ((settings.manualOverridePolicy ?: MANUAL_RESUME_TIMED) == MANUAL_RESUME_TIMED) {
        int mins = settings.manualOverrideMinutes ?: 30
        runIn(mins * 60, "resumeFromManualOverride")
        logI "Manual override: will auto-resume in ${mins} minutes"
    }
    // NEXT_EVAL is handled in sensorHandler
    // INDEFINITE requires user action in the UI
}

def resumeFromManualOverride() {
    if (!atomicState.manualOverrideActive) return
    logI "Manual override timed out — resuming automation"
    atomicState.manualOverrideActive = false
    notify("manualResume",
           "Resuming automation after manual override timeout.",
           "Resuming automation. Manual override has expired. Checking air quality now.")
    runIn(2, "evaluateConditions")
}

def purifierSwitchHandler(evt) {
    String deviceId = evt.deviceId?.toString()
    if (!deviceId) return

    // Ignore changes the app caused
    if (atomicState["purifier_appControlled_${deviceId}"]) {
        logD "purifierSwitchHandler: app-controlled change on ${evt.device.displayName} — ignoring"
        atomicState["purifier_appControlled_${deviceId}"] = false
        return
    }

    String policy = settings["purifier_policy_${deviceId}"] ?: "auto"

    // alwaysOn and scheduled: manual changes are expected and not overridden by app
    if (policy == "alwaysOn" || policy == "scheduled") {
        logD "purifierSwitchHandler: ${evt.device.displayName} policy=${policy} — manual change accepted"
        return
    }

    // auto and manualRespected: check for mismatch
    // For "auto" we still track manual changes as informational — we don't pause
    // For "manualRespected" we pause automation per the device's policy
    boolean deviceNowOn = (evt.value == "on")

    if (policy == "manualRespected") {
        logW "Purifier manual override: ${evt.device.displayName} turned ${evt.value} externally"
        handlePurifierManualOverride(evt.device, deviceId, deviceNowOn)
    }
    // "auto" policy: log but let next evaluateConditions correct if needed
}

void handlePurifierManualOverride(device, String deviceId, boolean deviceNowOn) {
    atomicState["purifier_mo_${deviceId}"] = true

    String moPolicy = settings["purifier_moPolicy_${deviceId}"] ?: "timed"
    String policyDesc = [
        "timed":      "Will resume in ${settings["purifier_moMinutes_${deviceId}"] ?: 30} minutes.",
        "nexteval":   "Will resume on next sensor reading.",
        "indefinite": "Will remain paused until you resume in the app."
    ][moPolicy] ?: "Will resume in 30 minutes."

    notify("manualOverride",
           "Purifier manual override: ${device.displayName} turned ${deviceNowOn ? "ON" : "OFF"} externally. ${policyDesc}",
           "${device.displayName} was turned ${deviceNowOn ? "on" : "off"} manually. Automation paused. ${policyDesc}",
           [device: device.displayName, fan: device.displayName, state: (deviceNowOn ? "on" : "off"), policy: policyDesc])

    if (moPolicy == "timed") {
        int mins = (settings["purifier_moMinutes_${deviceId}"] ?: 30) as Integer
        // Track which devices are pending timed resume using atomicState map
        // Single scheduled handler checks all pending devices
        Map pending = (atomicState.purifierResumePending ?: [:]) as Map
        pending[deviceId] = now() + (mins * 60 * 1000L)
        atomicState.purifierResumePending = pending
        // Schedule the check slightly after the shortest pending timeout
        long soonest = pending.values().min() as Long
        int secsUntil = Math.max(10, ((soonest - now()) / 1000).toInteger())
        runIn(secsUntil + 5, "checkPurifierResumeTimers")
        logI "Purifier override timed resume scheduled in ${mins} min for ${device.displayName}"
    }
    // nexteval is cleared in sensorHandler
    // indefinite requires app UI button
}

def checkPurifierResumeTimers() {
    // Check all pending timed purifier overrides and clear expired ones
    Map pending = (atomicState.purifierResumePending ?: [:]) as Map
    if (pending.isEmpty()) return

    boolean anyResumed = false
    List<String> toRemove = []

    pending.each { String deviceId, Object resumeAt ->
        long resumeAtMs = resumeAt as Long
        if (now() >= resumeAtMs) {
            def device = purifierSwitches?.find { it.id == deviceId }
            String name = device?.displayName ?: "purifier (id:${deviceId})"
            logI "Purifier timed override expired — resuming ${name}"
            atomicState["purifier_mo_${deviceId}"] = false
            toRemove << deviceId
            anyResumed = true
            notify("manualResume",
                   "Resuming automation for ${name} after manual override timeout.",
                   "Resuming automation for ${name}. Manual override has expired.")
        }
    }

    toRemove.each { pending.remove(it) }
    atomicState.purifierResumePending = pending

    // If any more pending, schedule next check
    if (!pending.isEmpty()) {
        long soonest = pending.values().min() as Long
        int secsUntil = Math.max(10, ((soonest - now()) / 1000).toInteger())
        runIn(secsUntil + 5, "checkPurifierResumeTimers")
    }

    if (anyResumed) runIn(2, "evaluateConditions")
}

def outdoorPmHandler(evt) {
    logD "Outdoor PM: ${evt.device.displayName} pm25=${evt.value}"
    if (evt.value == null || !"${evt.value}".isNumber()) {
        logW "Outdoor PM sensor sent non-numeric value '${evt.value}' — ignoring"
        return
    }
    float pm25 = evt.value.toFloat()
    atomicState.localOutdoorPm25   = pm25
    atomicState.localOutdoorPmTs   = now()
    // NOTE: do NOT label the source here — resolveOutdoorPm25() decides which
    // reading actually wins (it uses the worse of local vs AirNow) and sets the
    // label itself. Setting it here reported "local" even when AirNow's higher
    // value was the one being used.

    // Resolve effective PM2.5 (worse of local vs AirNow if both available)
    float effective = resolveOutdoorPm25()
    if (effective >= 0) {
        atomicState.currentOutdoorPm25 = effective
        updateWildfireGuard(effective)
    }
    runIn(2, "evaluateConditions")
}

def outdoorTempHandler(evt) {
    logD "Outdoor temp: ${evt.device.displayName} temp=${evt.value}"
    if (evt.value == null || !"${evt.value}".isNumber()) {
        logW "Outdoor temp sensor sent non-numeric value '${evt.value}' — ignoring"
        return
    }
    updateFreezeGuard(evt.value.toFloat())
    runIn(2, "evaluateConditions")
}

def presenceHandler(evt) {
    logI "Presence: ${evt.device.displayName}=${evt.value}"
    if (evt.value == "present" && (settings.runOnArrival != false)) {
        runIn(5, "handleArrival")
    } else {
        runIn(2, "evaluateConditions")
    }
}

def handleArrival() {
    // Arrival ventilation is a demand-mode concept ("run the fan a while when
    // someone gets home"). In continuous mode the fan is meant to run anyway,
    // so a timed arrival cycle that then stops the fan is wrong — just
    // re-evaluate, which restores continuous running.
    String mode = settings.operatingMode ?: MODE_CONTINUOUS
    if (mode == MODE_CONTINUOUS) {
        logI "Arrival in continuous mode — resuming continuous ventilation (no timed arrival cycle)"
        runIn(2, "evaluateConditions")
        return
    }
    if (!canStartFan()) {
        logI "Arrival: fan blocked by guard"
        runIn(2, "evaluateConditions")
        return
    }
    def mins = settings.arrivalRunMinutes ?: 30
    logI "Arrival ventilation: running fan for ${mins} min"
    startFan("arrival ventilation")
    notify("arrivalVent",
           "Someone arrived home. Running ${fanName()} for ${mins} minutes to freshen the air.",
           "Welcome home. Starting the ${fanName()} for ${mins} minutes to bring in fresh air.",
           [fan: fanName(), minutes: mins])
    if (mins > 0) runIn(mins * 60, "arrivalVentStop")
}

def arrivalVentStop() {
    // Never stop the fan for arrival-complete in continuous mode (a mode change
    // or stale timer could otherwise reach here and wrongly stop it).
    String mode = settings.operatingMode ?: MODE_CONTINUOUS
    if (mode == MODE_CONTINUOUS) {
        logI "Arrival vent timer fired in continuous mode — leaving fan running"
        runIn(2, "evaluateConditions")
        return
    }
    if (!conditionsRequireFan()) stopFan("arrival ventilation complete")
    else logI "Arrival vent timer done but conditions still need fan — continuing"
}

// ============================================================
// AIRNOW API
// ============================================================

def pollAirNow() {
    if (localOnlyMode || !airnowApiKey) return
    // Validate API key format — AirNow keys are GUIDs (hex + hyphens).
    // Reject anything with URL-breaking characters to prevent malformed
    // requests or parameter injection via the key field.
    String key = (airnowApiKey ?: "").trim()
    if (!(key ==~ /[A-Za-z0-9\-]{8,64}/)) {
        logW "AirNow API key has invalid format — expected alphanumeric/hyphen GUID. Skipping poll."
        return
    }
    // Coerce coordinates to numbers before they enter the URL. Hub location is
    // admin-set, but explicit numeric coercion guarantees no arbitrary string can
    // be interpolated into the request (defense in depth).
    Double lat, lon
    try {
        lat = (location.latitude  as Double)
        lon = (location.longitude as Double)
    } catch (e) {
        logW "Hub location is not numeric — cannot poll AirNow."
        return
    }
    if (lat == null || lon == null) {
        logW "Hub location not set — cannot poll AirNow. Set in Hub Settings → Location."
        return
    }
    def url = "${AIRNOW_URL}?format=application%2Fjson&latitude=${lat}&longitude=${lon}&API_KEY=${key}"
    logD "Polling AirNow: ${lat},${lon}"
    try {
        // 30s timeout — guards against occasional AirNow slowness
        httpGet([uri: url, timeout: 30]) { resp ->
            if (resp.status == 200 && resp.data) {
                // Normalize to a List of observation rows (see forecast parser
                // for the rationale — guards against a Map-shaped payload that
                // would otherwise throw "No such property" when iterated).
                def rows
                if (resp.data instanceof List) {
                    rows = resp.data
                } else if (resp.data instanceof Map) {
                    def listVal = resp.data.values().find { it instanceof List }
                    rows = (listVal != null) ? listVal : [resp.data]
                } else {
                    logW "AirNow: unexpected payload shape (neither List nor Map)"
                    handleNoOutdoorData()
                    return
                }
                // Diagnostic: log first row's keys to confirm actual field names
                if (rows && rows[0] instanceof Map) {
                    logD "AirNow obs: first row keys = ${(rows[0] as Map).keySet()}"
                }
                // New "ziplatLong" endpoint uses parameterName / nowcastAQI /
                // AQICategoryName. Match PM2.5 robustly (PM2.5 / PM25 / pm2.5).
                // Fall back to legacy field names for the transition window.
                def pm25Entry = rows.find {
                    if (!(it instanceof Map)) return false
                    String pn = (it.parameterName ?: it.ParameterName ?: it.Parameter ?: it.parameter ?: "").toString()
                    pn.toUpperCase().replaceAll("[^A-Z0-9]", "") == "PM25"
                }
                if (pm25Entry) {
                    def aqiRaw   = (pm25Entry.nowcastAQI != null) ? pm25Entry.nowcastAQI :
                                   (pm25Entry.AQI != null ? pm25Entry.AQI : pm25Entry.aqi)
                    int    aqi      = (aqiRaw != null ? aqiRaw : -1) as Integer
                    // Sanitize the API-supplied category before storing/displaying it.
                    // It is rendered into the app UI, so strip anything that is not
                    // plain text (defense in depth against a malformed/hostile response).
                    String category = (pm25Entry.aqiCategoryName ?: pm25Entry.AQICategoryName ?:
                                       pm25Entry.CategoryName ?: pm25Entry.categoryName ?:
                                       pm25Entry.category ?: "Unknown").toString()
                    category = category.replaceAll("[^A-Za-z0-9 ,._-]", "").take(60)
                    if (!category) category = "Unknown"
                    // AirNow returns -1 when no valid reading exists for the area
                    if (aqi < 0) {
                        logW "AirNow: PM2.5 entry present but AQI is ${aqi} (no valid reading) — skipping update"
                        return
                    }
                    float  pm25approx = aqiToPm25(aqi)
                    // Reject an implausible single-sample collapse. Observed in the
                    // field: AQI 53 -> 8 -> 43 within two hours. AirNow is hourly
                    // regional data and does not legitimately drop ~85% in one
                    // sample, and a spurious low value could falsely clear the
                    // wildfire guard. Skip it and keep the previous reading.
                    Integer prevAqi = atomicState.airnowLastAqi as Integer
                    // Only ONE sample is rejected: a second low reading in a row is a real
                    // clearing (otherwise the old high value would be kept forever).
                    if (prevAqi != null && prevAqi >= 50 && aqi < (prevAqi * 0.25) && !(atomicState.airnowDropRejected ?: false)) {
                        atomicState.airnowDropRejected = true
                        logW "AirNow: ignoring implausible drop ${prevAqi} -> ${aqi} AQI (likely a feed glitch); keeping previous reading"
                        return
                    }
                    atomicState.airnowDropRejected = false
                    atomicState.airnowLastAqi      = aqi
                    atomicState.airnowLastCategory = category
                    atomicState.airnowLastPoll     = now()
                    atomicState.airnowOutdoorPm25  = pm25approx  // store separately for disagreement check
                    logI "AirNow: PM2.5 AQI=${aqi} (${category}) ≈ ${pm25approx.toInteger()} µg/m³"

                    // Resolve effective PM2.5 — uses worse of local vs AirNow
                    float effective = resolveOutdoorPm25()
                    if (effective >= 0) {
                        atomicState.currentOutdoorPm25 = effective
                        // source label is set by resolveOutdoorPm25()
                        updateWildfireGuard(effective)
                        runIn(5, "evaluateConditions")
                    }
                } else {
                    logW "AirNow: no PM2.5 entry in response for this location"
                }
            } else {
                logW "AirNow poll failed: HTTP ${resp.status}"
                handleNoOutdoorData()
            }
        }
    } catch (e) {
        logW "AirNow poll exception: ${e.message}"
        handleNoOutdoorData()
    }
}

/**
 * Poll the AirNow "Current Forecasts" service (opt-in). Sets
 * atomicState.forecastBadAir = true when the forecast PM2.5 AQI for today
 * meets or exceeds the user threshold, or the agency flags an action day.
 *
 * This NEVER drives the wildfire guard. Its only effect is to let
 * evaluateConditions() lower the CO₂ trigger so the app can flush CO₂
 * proactively, while outdoor air is still clean, ahead of forecast smoke.
 */
/**
 * Set the forecast poll cadence based on coverage. Normal areas poll twice
 * daily (6am + 2pm). Areas AirNow doesn't forecast for back off to a weekly
 * heartbeat — enough to notice if coverage ever appears, without the waste of
 * polling a dead endpoint 14 times a week. The cadence self-corrects: a
 * successful forecast clears forecastNoCoverage and the next call here restores
 * twice-daily polling.
 */
void scheduleForecastPolling() {
    unschedule("pollAirNowForecast")
    if (!forecastEnabled || localOnlyMode || !airnowApiKey) return
    if (atomicState.forecastNoCoverage) {
        // Weekly heartbeat — Mondays at 6:15am
        schedule("0 15 6 ? * MON", "pollAirNowForecast")
        logI "AirNow forecast: no coverage for this area — backed off to weekly check"
    } else {
        schedule("0 15 6,14 * * ?", "pollAirNowForecast")
        logI "AirNow forecast polling scheduled (6am + 2pm)"
    }
}

def pollAirNowForecast() {
    if (!forecastEnabled || localOnlyMode || !airnowApiKey) return
    String key = (airnowApiKey ?: "").trim()
    if (!(key ==~ /[A-Za-z0-9\-]{8,64}/)) {
        logW "AirNow forecast: API key invalid format — skipping"
        return
    }
    Double lat, lon
    try {
        lat = (location.latitude  as Double)
        lon = (location.longitude as Double)
    } catch (e) {
        logW "AirNow forecast: hub location is not numeric — skipping"
        return
    }
    if (lat == null || lon == null) { logW "AirNow forecast: hub location not set — skipping"; return }

    def url = "${AIRNOW_FORECAST_URL}?format=application%2Fjson&latitude=${lat}&longitude=${lon}&API_KEY=${key}"
    logD "Polling AirNow forecast: ${lat},${lon}"
    try {
        // 30s timeout — the forecast endpoint is occasionally slow; the default
        // (~10s) was timing out. Forecast is non-urgent, so a longer wait is fine.
        httpGet([uri: url, timeout: 30]) { resp ->
            if (resp.status == 200 && resp.data) {
                // Normalize the payload to a List of forecast rows. The forecast
                // endpoint may hand HTTPBuilder a List (like the observation
                // endpoint) OR a Map. Iterating a Map with findAll yields
                // Map.Entry objects (no parameterName) — the cause of the
                // "No such property: parameterName" error. Handle both shapes.
                def rows
                if (resp.data instanceof List) {
                    rows = resp.data
                } else if (resp.data instanceof Map) {
                    // Either the rows are nested under a key whose value is a List,
                    // or the Map itself is a single forecast row.
                    def listVal = resp.data.values().find { it instanceof List }
                    if (listVal != null) {
                        rows = listVal
                    } else {
                        rows = [resp.data]  // single row as a Map
                    }
                } else {
                    logW "AirNow forecast: unexpected payload shape (neither List nor Map) — skipping"
                    return
                }
                logD "AirNow forecast: parsed ${rows?.size() ?: 0} row(s)"

                // Detect AirNow's "no forecasts available" error response. Some
                // reporting areas have no forecast
                // coverage at all — AirNow returns a single {Message: "Error ..."}
                // object instead of forecast rows. Flag this so the UI can tell
                // the user their area isn't covered, and stop polling noise.
                def msgRow = rows.find { it instanceof Map && (it.Message ?: it.message) }
                if (msgRow) {
                    String m = (msgRow.Message ?: msgRow.message).toString()
                    m = m.replaceAll("[^A-Za-z0-9 ,.:;'()\\-]", "").take(200)
                    boolean noCoverage = m.toLowerCase().contains("no current forecast")
                    boolean wasCovered = !(atomicState.forecastNoCoverage ?: false)
                    atomicState.forecastNoCoverage = noCoverage
                    atomicState.forecastLastPoll   = now()
                    atomicState.forecastBadAir     = false  // cannot be a bad-air day with no data
                    logI "AirNow forecast: ${m}"
                    // If this is the first time we've seen no coverage, back off to weekly
                    if (noCoverage && wasCovered) scheduleForecastPolling()
                    return
                }
                // Coverage exists. If we were previously backed off to weekly,
                // restore normal twice-daily polling.
                boolean wasNoCoverage = atomicState.forecastNoCoverage ?: false
                atomicState.forecastNoCoverage = false
                if (wasNoCoverage) {
                    logI "AirNow forecast: coverage now available — resuming twice-daily polling"
                    scheduleForecastPolling()
                }

                // Consider only PM2.5 rows. Field casing varies by endpoint
                // version, so read both lowercase-first and capitalized names.
                // Match "PM2.5" with or without separators (PM2.5 / PM25 / pm2.5).
                def pm25Rows = rows.findAll { row ->
                    if (!(row instanceof Map)) return false
                    String pn = (row.parameterName ?: row.ParameterName ?: row.Parameter ?: row.parameter ?: "").toString()
                    String norm = pn.toUpperCase().replaceAll("[^A-Z0-9]", "")  // "PM2.5" -> "PM25"
                    norm == "PM25"
                }
                if (!pm25Rows) {
                    logD "AirNow forecast: no PM2.5 rows (area may forecast ozone only today)"
                    atomicState.forecastLastPoll = now()
                    return
                }
                // Worst forecast AQI across returned days; action day if any row flags it.
                int worstAqi = -1
                boolean actionDay = false
                pm25Rows.each { row ->
                    def aqiRaw = (row.aqi != null) ? row.aqi : row.AQI
                    int a = (aqiRaw != null ? aqiRaw : -1) as Integer
                    if (a > worstAqi) worstAqi = a
                    def ad = (row.actionDay != null) ? row.actionDay : row.ActionDay
                    if (ad?.toString()?.equalsIgnoreCase("true")) actionDay = true
                }
                int thr = (settings.forecastAqiThreshold ?: DEFAULT_FORECAST_AQI) as Integer
                boolean badAir = (worstAqi >= thr) || actionDay
                boolean wasBad = atomicState.forecastBadAir ?: false
                atomicState.forecastBadAir     = badAir
                atomicState.forecastLastAqi    = worstAqi
                atomicState.forecastActionDay  = actionDay
                atomicState.forecastLastPoll   = now()
                logI "AirNow forecast: worst PM2.5 AQI=${worstAqi}, actionDay=${actionDay} → badAir=${badAir}"

                if (badAir && !wasBad) {
                    notify("forecastBadAir",
                           "📅 Air quality forecast: poor air expected (PM2.5 AQI ${worstAqi}${actionDay ? ", action day" : ""}). " +
                           "The app will ventilate proactively to lower CO₂ while outdoor air is still acceptable.",
                           "Air quality forecast alert. Poor air is expected today, with a forecast P M 2.5 index of ${worstAqi}. " +
                           "The system will bring in fresh air proactively to lower CO 2 before conditions worsen.",
                           [aqi: worstAqi])
                    runIn(5, "evaluateConditions")
                }
            } else {
                logW "AirNow forecast poll failed: HTTP ${resp.status}"
            }
        }
    } catch (e) {
        logW "AirNow forecast poll exception: ${e.message}"
    }
}

float aqiToPm25(int aqi) {
    // EPA breakpoint linear interpolation: AQI → PM2.5 µg/m³
    //
    // Uses the 2024 breakpoints, effective 2024-05-06, from EPA's "Final Updates
    // to the Air Quality Index (AQI) for Particulate Matter" fact sheet, issued
    // alongside the revised annual PM2.5 NAAQS (12.0 → 9.0 µg/m³):
    //     Good              0-50    0.0 - 9.0    (was 0.0 - 12.0)
    //     Moderate         51-100   9.1 - 35.4   (was 12.1 - 35.4)
    //     USG             101-150  35.5 - 55.4   (unchanged)
    //     Unhealthy       151-200  55.5 - 125.4  (was 55.5 - 150.4)
    //     Very Unhealthy  201-300 125.5 - 225.4
    //     Hazardous       301-500 225.5 - 325.4
    //
    // The previous implementation used the pre-2024 breakpoints AND capped at
    // 150.5 above AQI 200, which badly understated severe smoke — an observed
    // AQI of 317 was reported as 150.5 µg/m³ instead of ~233.
    if (aqi <= 0)   return 0.0f
    if (aqi <= 50)  return (aqi / 50.0 * 9.0).toFloat()
    if (aqi <= 100) return (9.1   + (aqi - 51)  / 49.0  * 26.3).toFloat()
    if (aqi <= 150) return (35.5  + (aqi - 101) / 49.0  * 19.9).toFloat()
    if (aqi <= 200) return (55.5  + (aqi - 151) / 49.0  * 69.9).toFloat()
    if (aqi <= 300) return (125.5 + (aqi - 201) / 99.0  * 99.9).toFloat()
    if (aqi <= 500) return (225.5 + (aqi - 301) / 199.0 * 99.9).toFloat()
    return 325.4f
}

void handleNoOutdoorData() {
    if (!outdoorPmSensor) {
        atomicState.outdoorPmSource    = "none"
        atomicState.currentOutdoorPm25 = null
        boolean wasActive = atomicState.wildfireGuardActive ?: false
        if (wasActive) atomicState.wildfireGuardActive = false
        notify("noOutdoorData",
               "⚠️ Outdoor air quality data unavailable (no local sensor, AirNow failed). " +
               "Wildfire guard is DISABLED. Check AirNow API key or add a local PM2.5 sensor.",
               "Warning: outdoor air quality data is unavailable. The wildfire guard is disabled.")
    }
}

// ============================================================
// GUARD UPDATERS
// ============================================================

/** User-configurable wildfire-guard deadband (µg/m³). */
Integer guardHystPm25() {
    return (settings.guardHystPm25 ?: GUARD_HYST_PM25) as Integer
}

void updateWildfireGuard(float pm25) {
    float   thr       = (settings.pm25OutdoorMax ?: DEFAULT_PM25_OUT_MAX).toFloat()
    boolean wasActive = atomicState.wildfireGuardActive ?: false
    // Hysteresis: activate at thr, but once active only clear when the reading
    // drops below thr minus the deadband. Prevents guard/notification flapping
    // when PM hovers at the boundary (common during smoke events).
    int hyst = guardHystPm25()
    boolean nowActive = wasActive ? (pm25 > (thr - hyst)) : (pm25 > thr)

    // Minimum dwell: once the guard trips, hold it for at least this long before
    // allowing a clear. Deadband alone is not enough when the two outdoor sources
    // disagree sharply and the worse-of-the-two alternates between them.
    if (wasActive && !nowActive) {
        int minMin = (settings.guardMinMinutes ?: DEFAULT_GUARD_MIN_MIN) as Integer
        Long since = atomicState.wildfireGuardSince as Long
        if (since != null && (now() - since) < (minMin * 60 * 1000L)) {
            long heldMin = (now() - since) / 60000L
            logD "Wildfire guard would clear (PM2.5=${pm25.toInteger()}) but has only been active ${heldMin} of ${minMin} min — holding"
            nowActive = true
        }
    }
    atomicState.wildfireGuardActive = nowActive

    if (nowActive && !wasActive) {
        atomicState.wildfireGuardSince = now()
        logW "🔥 WILDFIRE GUARD ON — PM2.5=${pm25.toInteger()} µg/m³ > ${thr.toInteger()}"
        notify("wildfireOn",
               "🔥 Smoke outside — fan STOPPED, purifiers ON.\n" +
               "Outdoor PM2.5 ${pm25.toInteger()} µg/m³ (limit ${thr.toInteger()}). " +
               "Not pulling outside air in; cleaning indoor air instead.",
               "Smoke alert. Outdoor particulate is ${pm25.toInteger()}, above the limit of ${thr.toInteger()}. " +
               "${fanSwitch ? "The ${fanName()} has been stopped so it doesn't pull smoke inside. " : ""}" +
               "${purifierSwitches ? "Air purifiers are running to clean the indoor air." : ""}",
               [pm25: pm25.toInteger(), threshold: thr.toInteger(), fan: fanName()])
        updateAppLabel()
    } else if (!nowActive && wasActive) {
        logI "✅ Wildfire guard cleared — PM2.5=${pm25.toInteger()} µg/m³ (release point ${(thr - guardHystPm25()).toInteger()})"
        notify("wildfireOff",
               "✅ Air cleared — fan back ON.\n" +
               "Outdoor PM2.5 down to ${pm25.toInteger()} µg/m³. Normal ventilation resumed.",
               "Good news. Outdoor air has cleared to ${pm25.toInteger()}. " +
               "${fanSwitch ? "Normal ventilation has resumed." : ""}",
               [pm25: pm25.toInteger(), fan: fanName()])
        updateAppLabel()
    }
}

void updateFreezeGuard(float temp) {
    float   thr       = (settings.outdoorTempMin ?: DEFAULT_TEMP_MIN).toFloat()
    boolean wasActive = atomicState.freezeGuardActive ?: false
    // Hysteresis: activate below thr, but once active only clear when temp
    // rises above thr plus the deadband. Prevents flapping on cold mornings
    // when the temperature hovers at the threshold.
    boolean nowActive = wasActive ? (temp < (thr + GUARD_HYST_TEMP)) : (temp < thr)
    atomicState.freezeGuardActive = nowActive

    if (nowActive && !wasActive) {
        atomicState.freezeGuardSince = now()
        logW "❄️ FREEZE GUARD ON — temp=${temp.toInteger()}°F < ${thr.toInteger()}°F"
        notify("freezeOn",
               "❄️ Too cold — fan STOPPED.\n" +
               "Outdoor ${temp.toInteger()}°F (limit ${thr.toInteger()}°F). " +
               "Stopped to protect the heat exchanger from freezing.",
               "Freeze alert. It's ${temp.toInteger()} degrees outside, below the ${thr.toInteger()} degree limit. " +
               "${fanSwitch ? "The ${fanName()} has been stopped to protect the heat exchanger." : ""}",
               [temp: temp.toInteger(), threshold: thr.toInteger(), fan: fanName()])
        updateAppLabel()
    } else if (!nowActive && wasActive) {
        logI "✅ Freeze guard cleared — temp=${temp.toInteger()}°F"
        notify("freezeOff",
               "✅ Warmed up — fan back ON.\n" +
               "Outdoor ${temp.toInteger()}°F. Normal ventilation resumed.",
               "It's warmed up to ${temp.toInteger()} degrees. ${fanSwitch ? "Normal ventilation has resumed." : ""}",
               [temp: temp.toInteger(), fan: fanName()])
        updateAppLabel()
    }
}

// ============================================================
// CORE EVALUATION ENGINE
// ============================================================

def evaluateConditions() {
    if (!enableApp) { logD "App disabled"; return }
    if (!indoorSensors && !fanSwitch && !purifierSwitches) { logD "Nothing configured"; return }

    // Respect manual override (except safety guards)
    if (atomicState.manualOverrideActive) {
        logD "Manual override active — only safety guards apply"
        // Safety guards still run even during manual override if configured
        if (settings.guardsDuringManual != false) {
            boolean anyGuard = (atomicState.wildfireGuardActive ?: false) || (atomicState.freezeGuardActive ?: false)
            if (anyGuard && (atomicState.fanRunning ?: false)) {
                logW "Safety guard triggered during manual override — stopping fan"
                stopFan("safety guard override during manual override")
            }
        }
        return
    }

    boolean running  = atomicState.fanRunning ?: false
    boolean wfGuard  = atomicState.wildfireGuardActive ?: false
    boolean frGuard  = atomicState.freezeGuardActive   ?: false
    boolean anyGuard = wfGuard || frGuard

    // ── PRIORITY 1: CO₂ EMERGENCY OVERRIDE ───────────────────────────────────
    if (fanSwitch && anyGuard && (settings.co2EmergencyEnabled != false) &&
        !atomicState.emergencyVentRunning && co2EmergencyNeeded()) {
        startEmergencyVent()
        return
    }

    // CO₂ rising notification (not yet at emergency, but climbing)
    if (anyGuard && indoorSensors) {
        float maxCo2      = maxIndoorCo2()
        int   co2Thr      = settings.co2High      ?: DEFAULT_CO2_HIGH
        int   emergThr    = settings.co2Emergency ?: DEFAULT_CO2_EMERGENCY
        // Throttling is handled centrally by notify() (see notifyCooldownMinutes:
        // co2Rising = 60 min). No local gate needed.
        if (maxCo2 > co2Thr && maxCo2 < emergThr) {
            String guardType = wfGuard ? "wildfire smoke" : "cold outdoor temperature"
            notify("co2Rising",
                   "⚠️ CO₂ rising: ${maxCo2.toInteger()} ppm. Fan locked out due to ${guardType}. " +
                   "Emergency override at ${emergThr} ppm. Source: WA DOH DOH-333-208.",
                   "Warning: indoor CO 2 is rising to ${maxCo2.toInteger()} parts per million. " +
                   "The ${fanSwitch ? fanName() : "ventilation fan"} is locked out due to ${guardType}. " +
                   "Air purifiers are running. Emergency ventilation will start at ${emergThr} parts per million.",
                   [co2: maxCo2.toInteger(), threshold: emergThr, guard: guardType, fan: fanName()])
        }
    }

    // ── PRIORITY 2: SAFETY GUARDS ─────────────────────────────────────────────
    if (anyGuard) {
        // A CO2 emergency vent in progress is allowed to run despite the guard until
        // endEmergencyVent fires. If that timer was lost (settings saved mid-run wipes
        // schedules), end the vent here once it is overdue.
        boolean ventActive = (atomicState.emergencyVentRunning ?: false) && running
        long ventMaxMs = (((settings.emergencyVentMinutes ?: DEFAULT_EMERGENCY_DUR) as Integer) + 2) * 60000L
        if (ventActive && (!atomicState.fanStartedAt || (now() - (atomicState.fanStartedAt as Long)) >= ventMaxMs)) {
            unschedule("endEmergencyVent")
            endEmergencyVent()
        } else if (running && !ventActive) {
            stopFan("${wfGuard ? "wildfire" : "freeze"} guard active")
        }
        controlPurifiers(indoorPm25High())
        return
    }

    // ── Determine effective operating mode (accounting for presence) ─────────
    String mode = settings.operatingMode ?: MODE_CONTINUOUS
    boolean away = presenceEnabled && presenceSensors && !someoneHome()

    // ── PRIORITY 3: PRESENCE ─────────────────────────────────────────────────
    if (away) {
        if (mode == MODE_CONTINUOUS) {
            String contAway = settings.continuousAwayBehavior ?: CONT_AWAY_DEMAND
            if (contAway == CONT_AWAY_OFF) {
                if (running) stopFan("everyone away — continuous mode set to off when away")
                stopPurifiers()
                return
            } else if (contAway == CONT_AWAY_DEMAND) {
                mode = MODE_DEMAND  // fall through to demand logic below
            }
            // CONT_AWAY_KEEP: leave mode = continuous
        } else {
            // demand/scheduled mode: legacy away behavior
            if ((settings.awayBehavior ?: "relaxed") == "disabled") {
                if (running) stopFan("everyone away — fan disabled when away")
                stopPurifiers()
                return
            }
        }
    }

    // ── PRIORITY 4: TIMING GUARDS ────────────────────────────────────────────
    if (running && isMinRunActive()) { logD "Min run active — fan stays on"; return }
    if (!running && isCooldownActive()) { logD "Cooldown active — cannot start"; return }

    // ── PRIORITY 5: RUN/STOP DECISION BY MODE ────────────────────────────────
    int co2Stop = settings.co2Low        ?: DEFAULT_CO2_LOW
    int pmStop  = settings.pm25IndoorLow ?: DEFAULT_PM25_IN_LOW
    boolean pm25High = indoorPm25High()

    // CONTINUOUS mode: fan should simply be ON (guards already cleared above)
    if (mode == MODE_CONTINUOUS && fanSwitch) {
        if (!running) startFan("continuous mode — base ventilation")
        // never stops here; only guards/presence/manual stop it
        boolean parallel = (settings.purifiersParallel ?: false)
        evaluateAllPurifiers(pm25High, false, parallel)
        return
    }

    // SCHEDULED mode: continuous during window, else demand (or off)
    if (mode == MODE_SCHEDULED && fanSwitch) {
        boolean inWindow = inFanSchedule()
        if (inWindow) {
            if (!running) startFan("scheduled mode — within run window")
            boolean parallel = (settings.purifiersParallel ?: false)
            evaluateAllPurifiers(pm25High, false, parallel)
            return
        }
        // outside window
        if (settings.scheduledOutsideDemand == false) {
            if (running) stopFan("scheduled mode — outside run window")
            evaluateAllPurifiers(pm25High, false, false)
            return
        }
        // else fall through to demand logic
    }

    // DEMAND mode (also used by continuous-away-demand and scheduled-outside-demand)
    boolean awayRelaxed = away && (settings.awayBehavior == "relaxed") && (mode == MODE_DEMAND)
    int co2Thr  = awayRelaxed ? (settings.co2HighAway ?: 1500) : (settings.co2High ?: DEFAULT_CO2_HIGH)
    int pm25Thr = settings.pm25IndoorHigh ?: DEFAULT_PM25_IN_HIGH

    // Proactive forecast flush: if bad air is forecast AND no guard is active
    // (outdoor air still acceptable right now), lower the CO₂ run threshold so
    // the fan flushes CO₂ early — before forecast smoke arrives and locks the
    // fan out. Only lowers, never raises; current readings still gate everything.
    boolean forecastFlush = (settings.forecastEnabled ?: false) &&
                            (atomicState.forecastBadAir ?: false) && !anyGuard
    if (forecastFlush) {
        int flushThr = (settings.forecastCo2Flush ?: settings.co2Low ?: DEFAULT_CO2_LOW) as Integer
        if (flushThr < co2Thr) {
            logD "Forecast bad-air: lowering CO₂ run threshold ${co2Thr} → ${flushThr} for proactive flush"
            co2Thr = flushThr
        }
    }

    // ── AIR QUALITY OUTRANKS HUMIDITY ────────────────────────────────────────
    // ASHRAE separates these deliberately: the ventilation rate required for
    // indoor air quality "does not allow for humidity control. Additional
    // ventilation or dehumidification may be required to remove moisture."
    // CO₂/PM ventilation protects occupant health and is NOT reduced or delayed
    // to manage moisture; ASHRAE's own answer to ventilation-imported humidity
    // is supplemental dehumidification, not less ventilation.
    //
    // So: airQualityDemand is evaluated first and independently. Humidity is an
    // opportunistic ADD-ON that can start the fan on its own, but never blocks
    // or stops a run that air quality requires.
    boolean airQualityDemand = conditionsRequireFan(co2Thr, pm25Thr)
    boolean humidityVent     = humidityRequiresVent()

    boolean shouldRun  = fanSwitch && (airQualityDemand || humidityVent)
    boolean shouldStop = !conditionsRequireFan(co2Stop, pmStop) && !humidityVent

    if (!running && shouldRun) {
        String reason
        if (airQualityDemand) {
            reason = buildRunReason(co2Thr, pm25Thr, awayRelaxed)
            // Transparency: if we're ventilating for air quality while outdoor
            // air is NOT drier, we are knowingly importing moisture. That is the
            // correct trade (health first) but the user should be able to see it.
            if (!(atomicState.humidityVenting ?: false) && humidityImportWarned() == false) {
                Map dry = ventilationWouldDry()
                if (dry?.ok == false && dry?.outdoorDp != null) {
                    logI "Ventilating for air quality while outdoor air is not drier " +
                         "(${dry.reason}) — importing some moisture; health takes priority per ASHRAE."
                    atomicState.humidityImportNotedAt = now()
                }
            }
        } else {
            reason = "indoor humidity ${atomicState.humidityCurrentRh?.toInteger()}% over ${atomicState.humidityTargetRh}% target"
        }
        startFan(reason + (forecastFlush ? " [forecast pre-vent]" : "") + (humidityVent && !airQualityDemand ? " [humidity]" : ""))
    } else if (running && shouldStop) {
        stopFan("all sensors below stop thresholds")
    }

    // Evaluate all purifiers per their individual policies
    boolean fanNowRunning = atomicState.fanRunning ?: false
    boolean parallelMode  = fanNowRunning && (settings.purifiersParallel ?: false)
    evaluateAllPurifiers(pm25High, false, parallelMode)
}

// Fan schedule window check (for MODE_SCHEDULED)
boolean inFanSchedule() {
    def s = settings.scheduleStart
    def e = settings.scheduleEnd
    if (!s || !e) return true
    try {
        Date now    = new Date()
        Date qStart = timeToday(s, location.timeZone)
        Date qEnd   = timeToday(e, location.timeZone)
        if (qStart.before(qEnd)) return now.after(qStart) && now.before(qEnd)
        return now.after(qStart) || now.before(qEnd)  // spans midnight
    } catch (ex) {
        logW "inFanSchedule parse error: ${ex.message}"
        return true
    }
}

// ============================================================
// EMERGENCY VENT
// ============================================================

boolean co2EmergencyNeeded() {
    if (!(settings.co2EmergencyEnabled != false)) return false
    // Only the ACTIVE guard's start time counts (wildfireGuardSince is never cleared,
    // so a past smoke event must not shorten the delay for a later freeze lockout).
    long guardSince = 0L
    if (atomicState.wildfireGuardActive)    guardSince = (atomicState.wildfireGuardSince ?: 0L) as Long
    else if (atomicState.freezeGuardActive) guardSince = (atomicState.freezeGuardSince   ?: 0L) as Long
    if (guardSince == 0) return false
    long guardMinutes = (now() - guardSince) / 60000
    if (guardMinutes < (settings.emergencyAfterMinutes ?: DEFAULT_EMERGENCY_AFTER)) return false
    return maxIndoorCo2() >= (settings.co2Emergency ?: DEFAULT_CO2_EMERGENCY)
}

void startEmergencyVent() {
    int mins = settings.emergencyVentMinutes ?: DEFAULT_EMERGENCY_DUR
    float co2 = maxIndoorCo2()
    String guardType = (atomicState.wildfireGuardActive) ? "wildfire smoke" : "cold outdoor temperature"
    logW "🆘 CO₂ EMERGENCY VENT: ${co2.toInteger()} ppm — running fan ${mins} min despite ${guardType}"
    atomicState.emergencyVentRunning = true
    atomicState.lastRunMode = "CO₂ emergency override — WA DOH DOH-333-208"
    // Capture the indoor PM2.5 baseline so we can measure whether running the
    // fan actually pulls particulate in. If indoor PM climbs past the abort
    // margin during the run, we cut it short (see monitorEmergencyVentPm).
    Float pmBase = maxIndoorPm25()
    atomicState.ventPmBaseline = pmBase
    atomicState.ventPmPeak     = pmBase
    atomicState.ventAborted    = false
    fanOn()
    atomicState.fanRunning   = true
    atomicState.fanStartedAt = now()
    atomicState.runtimeTickAnchor = now()
    runIn(mins * 60, "endEmergencyVent")
    // Sample indoor PM every minute during the override
    if (pmBase != null) runIn(60, "monitorEmergencyVentPm")
    updateAppLabel()
    notify("co2Override",
           "🆘 CO₂ Emergency Override: Indoor CO₂=${co2.toInteger()} ppm. Running fan ${mins} min despite ${guardType} guard. " +
           "Per WA DOH DOH-333-208. Guard resumes after flush.",
           "Emergency ventilation override. Indoor CO 2 has reached ${co2.toInteger()} parts per million. " +
           "Running the ${fanName()} for ${mins} minutes to reduce CO 2, despite ${guardType}. " +
           "This is recommended by Washington State Department of Health guidance. " +
           "The guard will resume after ventilation completes.",
           [co2: co2.toInteger(), minutes: mins, guard: guardType, fan: fanName()])
}

/**
 * Sample indoor PM2.5 during a CO₂ emergency override. The override runs the
 * fan while a guard is active, so this is the one time we deliberately bring
 * in potentially-bad outdoor air. If indoor PM2.5 rises more than the abort
 * margin above where it started, the HRV's filter clearly isn't holding the
 * particulate back and we cut the run short. Runs once a minute during the
 * override; reschedules itself until the vent ends.
 */
def monitorEmergencyVentPm() {
    if (!(atomicState.emergencyVentRunning ?: false)) return
    Float base = atomicState.ventPmBaseline as Float
    Float now_ = maxIndoorPm25()
    if (base == null || now_ == null) return
    if (now_ > ((atomicState.ventPmPeak ?: base) as Float)) atomicState.ventPmPeak = now_

    int margin = (settings.ventPmAbortMargin ?: DEFAULT_VENT_PM_ABORT) as Integer
    float rise = now_ - base
    if (rise > margin) {
        // NOTE: ventAborted is a deliberate "last result" latch, not a control
        // gate. It stays true after this early return so diagnostics can show the
        // outcome of the most recent override, and it is reset to false at the
        // start of the next one (see startEmergencyVent). It never gates fan
        // control, so it cannot disable the app if it stays set.
        atomicState.ventAborted = true
        logW "🛑 Emergency vent ABORTED — indoor PM2.5 rose ${rise.toInteger()} µg/m³ " +
             "(${base.toInteger()} → ${now_.toInteger()}), past the ${margin} µg/m³ abort margin. " +
             "The HRV filter is not holding back outdoor particulate."
        notify("ventAborted",
               "🛑 CO₂ override stopped early: indoor PM2.5 rose ${rise.toInteger()} µg/m³ " +
               "(${base.toInteger()} → ${now_.toInteger()}) while ventilating. Outdoor particulate is " +
               "getting through the ${fanName()} filter. Returning to guard lockout; purifiers continue.",
               "Ventilation stopped early. Indoor particulate rose ${rise.toInteger()} micrograms while the " +
               "${fanName()} was running, so outdoor smoke is getting inside. Returning to lockout and " +
               "relying on the air purifiers.",
               [rise: rise.toInteger(), fan: fanName()])
        endEmergencyVent()
        return
    }
    // Still clean — keep sampling
    runIn(60, "monitorEmergencyVentPm")
}

/** Highest current indoor PM2.5 across valid sensors, or null if none. */
Float maxIndoorPm25() {
    if (!indoorSensors) return null
    Map valid = validSensorReadings()
    List vals = valid.collect { name, r -> r.pm25 }.findAll { it != null }
    return vals ? (vals.max() as Float) : null
}

def endEmergencyVent() {
    logI "Emergency vent complete — returning to guard lockout"
    atomicState.emergencyVentRunning = false
    unschedule("monitorEmergencyVentPm")
    // Report what we measured: did running the fan actually let particulate in?
    Float base = atomicState.ventPmBaseline as Float
    Float peak = atomicState.ventPmPeak as Float
    if (base != null && peak != null && !(atomicState.ventAborted ?: false)) {
        float rise = peak - base
        atomicState.lastVentPmRise = rise
        logI "Emergency vent filtration result: indoor PM2.5 ${base.toInteger()} → peak ${peak.toInteger()} " +
             "µg/m³ (rise ${rise.toInteger()}). ${rise <= 2 ? "Filter held well at this outdoor level." : "Some particulate got through."}"
    }
    if (atomicState.wildfireGuardActive) atomicState.wildfireGuardSince = now()
    if (atomicState.freezeGuardActive)   atomicState.freezeGuardSince   = now()
    stopFan("emergency vent complete — returning to guard lockout")
    updateAppLabel()
}

// ============================================================
// CONDITION CHECKS
// ============================================================

boolean conditionsRequireFan(int co2Thr = (settings.co2High ?: DEFAULT_CO2_HIGH),
                              int pm25Thr = (settings.pm25IndoorHigh ?: DEFAULT_PM25_IN_HIGH)) {
    if (!indoorSensors) return false
    Map<String, Map<String, Float>> valid = validSensorReadings()
    if (valid.isEmpty()) return false
    return valid.any { name, readings ->
        (readings.co2  != null && readings.co2  > co2Thr)  ||
        (readings.pm25 != null && readings.pm25 > pm25Thr)
    }
}

boolean indoorPm25High() {
    if (!indoorSensors) return false
    int thr = settings.pm25IndoorHigh ?: DEFAULT_PM25_IN_HIGH
    Map valid = validSensorReadings()
    return valid.any { name, readings -> readings.pm25 != null && readings.pm25 > thr }
}

float maxIndoorCo2() {
    if (!indoorSensors) return 0.0f
    Map valid = validSensorReadings()
    if (valid.isEmpty()) return 0.0f
    return valid.collect { name, readings -> readings.co2 ?: 0.0f }.max() ?: 0.0f
}

// ── Maintenance / runtime tracking ────────────────────────────────────────────

/**
 * Accumulate runtime for a fan that's currently running but hasn't stopped
 * (important in continuous mode, where stopFan rarely fires). Called daily.
 * Adds the elapsed time since the last tick, then resets the tick anchor.
 */
def runtimeTick() {
    if (!(atomicState.fanRunning ?: false)) {
        atomicState.runtimeTickAnchor = now()
        return
    }
    long anchor = (atomicState.runtimeTickAnchor ?: atomicState.fanStartedAt ?: now()) as Long
    long elapsedMin = (now() - anchor) / 60000L
    if (elapsedMin > 0) {
        atomicState.runtimeTotalMin      = ((atomicState.runtimeTotalMin ?: 0L) as Long) + elapsedMin
        atomicState.runtimeSinceMaintMin = ((atomicState.runtimeSinceMaintMin ?: 0L) as Long) + elapsedMin
    }
    atomicState.runtimeTickAnchor = now()
    checkMaintenanceDue()
}

/**
 * If maintenance tracking is on and accumulated runtime since the last reset
 * meets the interval, notify once (a flag prevents repeat alerts until reset).
 */
void checkMaintenanceDue() {
    if (!(settings.maintenanceEnabled ?: false)) return
    long sinceMin = (atomicState.runtimeSinceMaintMin ?: 0L) as Long
    int intervalHours = (settings.maintenanceHours ?: DEFAULT_MAINT_HOURS) as Integer
    long intervalMin  = intervalHours * 60L
    if (sinceMin >= intervalMin && !(atomicState.maintenanceNotified ?: false)) {
        atomicState.maintenanceNotified = true
        int hrs = (sinceMin / 60L) as Integer
        notify("maintenanceDue",
               "🔧 ${fanName()} maintenance due: ${hrs} runtime hours since last service. " +
               "Check/clean the filters and heat-exchanger core. Reset the counter on the Maintenance page after servicing.",
               "Maintenance reminder. The ${fanName()} has run for ${hrs} hours since its last service. " +
               "It's time to check the filters and heat exchanger core.",
               [fan: fanName(), hours: hrs])
        updateAppLabel()
    }
}



/**
 * Dew point in °F from temperature (°F) and relative humidity (%),
 * via the Magnus-Tetens approximation. Dew point expresses ABSOLUTE moisture,
 * which is what determines whether ventilating actually dries the house.
 */
Float dewPointF(Float tempF, Float rh) {
    if (tempF == null || rh == null || rh <= 0) return null
    double t = (tempF - 32.0d) * 5.0d / 9.0d               // to °C
    double gamma = Math.log(rh / 100.0d) + (17.625d * t) / (243.04d + t)
    double dpC = (243.04d * gamma) / (17.625d - gamma)
    return ((dpC * 9.0d / 5.0d) + 32.0d) as Float
}

/** Current outdoor relative humidity, or null if no sensor is configured. */
Float currentOutdoorHumidity() {
    if (!outdoorHumiditySensor) return null
    def h = outdoorHumiditySensor.currentValue("humidity")
    if (h == null || !"${h}".isNumber()) return null
    return h as Float
}

/** Highest current indoor temperature across the humidity sensors, or null. */
Float maxIndoorTemp() {
    if (!humiditySensors) return null
    List vals = []
    humiditySensors.each { s ->
        def t = s.currentValue("temperature")
        if (t != null && "${t}".isNumber()) vals << (t as float)
    }
    return vals ? (vals.max() as Float) : null
}

/**
 * Would running the fan actually DRY the house right now?
 * Returns a map: [ok: boolean, indoorDp: Float, outdoorDp: Float, reason: String]
 *
 * Cold outdoor air always holds less moisture, so below DEWPOINT_CHECK_ABOVE_F
 * the answer is yes without needing an outdoor humidity sensor. In mild or warm
 * weather the comparison matters, and without an outdoor humidity reading we
 * refuse rather than guess — ventilating into humid air makes things worse.
 */
Map ventilationWouldDry() {
    Float outT = currentOutdoorTemp()
    if (outT == null) return [ok: false, reason: "no outdoor temperature reading"]

    // Cold outside: absolute moisture is necessarily low. Safe to ventilate.
    if (outT < DEWPOINT_CHECK_ABOVE_F) {
        return [ok: true, reason: "outdoor air is cold (${outT.toInteger()}°F) and therefore dry"]
    }

    Float outRh = currentOutdoorHumidity()
    if (outRh == null) {
        return [ok: false,
                reason: "mild outdoor temp (${outT.toInteger()}°F) and no outdoor humidity sensor — " +
                        "cannot confirm outdoor air is drier, so not ventilating"]
    }

    Float inT  = maxIndoorTemp()
    Float inRh = maxIndoorHumidity()
    if (inT == null || inRh == null) return [ok: false, reason: "no indoor temperature/humidity reading"]

    Float inDp  = dewPointF(inT, inRh)
    Float outDp = dewPointF(outT, outRh)
    if (inDp == null || outDp == null) return [ok: false, reason: "could not compute dew point"]

    int margin = (settings.dewPointMargin ?: DEWPOINT_MARGIN_F) as Integer
    boolean drier = outDp < (inDp - margin)
    return [ok: drier, indoorDp: inDp, outdoorDp: outDp,
            reason: drier ?
                "outdoor dew point ${outDp.toInteger()}°F is below indoor ${inDp.toInteger()}°F — ventilating will dry" :
                "outdoor dew point ${outDp.toInteger()}°F vs indoor ${inDp.toInteger()}°F — outdoor air is not drier, " +
                "ventilating would not help (dehumidification needed instead)"]
}

/**
 * The maximum indoor RH% recommended for the current outdoor temperature.
 * Uses the user's custom breakpoints if provided, else the built-in
 * temperature-indexed table. Returns null if humidity control can't run
 * (feature off, no humidity sensor, or no outdoor temperature available).
 */
Integer humidityTargetRh() {
    if (!(settings.humidityControlEnabled ?: false)) return null
    if (!humiditySensors) return null
    def t = currentOutdoorTemp()
    if (t == null) return null
    float temp = t as float

    // Custom single-threshold mode: one RH ceiling, only enforced below a
    // user-set outdoor temp (simpler than the full table for those who want it).
    if (settings.humidityUseCustom) {
        Integer coldBelow = (settings.humidityColdBelowTemp ?: 32) as Integer
        if (temp >= coldBelow) return null  // warm enough, no condensation risk
        return (settings.humidityCustomMaxRh ?: 35) as Integer
    }

    // Auto table: find the first row whose tempF the current temp meets or exceeds.
    // Table is ordered warmest → coldest.
    for (row in HUMIDITY_TABLE) {
        if (temp >= (row.tempF as float)) return row.maxRh as Integer
    }
    // Colder than the coldest row → use the coldest ceiling
    return HUMIDITY_TABLE.last().maxRh as Integer
}

/** Highest current indoor RH across all selected humidity sensors, or null. */
Float maxIndoorHumidity() {
    if (!humiditySensors) return null
    List vals = []
    humiditySensors.each { s ->
        def h = s.currentValue("humidity")
        if (h != null && "${h}".isNumber()) vals << (h as float)
    }
    return vals ? (vals.max() as Float) : null
}

/** Current outdoor temperature from the configured sensor, or null. */
Float currentOutdoorTemp() {
    if (!outdoorTempSensor) return null
    def t = outdoorTempSensor.currentValue("temperature")
    if (t == null || !"${t}".isNumber()) return null
    return t as Float
}

/**
 * True when indoor humidity exceeds the temperature-indexed target and the
 * fan should run to dry the air. Uses hysteresis so it doesn't flap at the
 * boundary. Only meaningful when it's cold enough outside for the target to
 * apply (humidityTargetRh returns null otherwise).
 */
/** True if we've already logged the moisture-import trade recently (6h). */
boolean humidityImportWarned() {
    Long t = atomicState.humidityImportNotedAt as Long
    return t != null && (now() - t) < (6 * 60 * 60 * 1000L)
}

boolean humidityRequiresVent() {
    Integer target = humidityTargetRh()
    if (target == null) return false
    Float rh = maxIndoorHumidity()
    if (rh == null) return false

    // ── Does ventilating actually dry the house right now? ──────────────────
    // RH alone cannot answer this. If outdoor air holds as much or more
    // absolute moisture (dew point) as indoor air, running the fan removes
    // nothing and can make things worse — the classic case being a mild rain
    // event, where outdoor RH is near saturation. In that situation the correct
    // response is dehumidification (AC or a dehumidifier), not ventilation.
    Map dry = ventilationWouldDry()
    atomicState.humidityIndoorDp  = dry.indoorDp
    atomicState.humidityOutdoorDp = dry.outdoorDp
    atomicState.humidityVentReason = dry.reason
    if (!dry.ok) {
        if (rh > target) {
            // High humidity we cannot fix by ventilating — tell the user once,
            // rather than silently doing nothing.
            logI "Humidity ${rh.toInteger()}% is above the ${target}% ceiling, but ${dry.reason}"
            notify("humidityCannotVent",
                   "💧 Indoor humidity ${rh.toInteger()}% is above the ${target}% target, but ventilating won't help.\n" +
                   "${dry.reason}. A dehumidifier or AC is the right tool here; the fan stays off.",
                   "Indoor humidity is ${rh.toInteger()} percent, above the target. However, outdoor air is not drier " +
                   "than indoor air right now, so ventilating would not remove moisture. A dehumidifier is needed instead.",
                   [rh: rh.toInteger(), target: target])
        }
        atomicState.humidityVenting = false
        return false
    }

    boolean wasVenting = atomicState.humidityVenting ?: false
    // Activate at target; once venting, keep going until RH drops below
    // target minus the deadband.
    // Trigger on either the RH ceiling (winter condensation protection) or the
    // absolute dew-point ceiling (ASHRAE/EPA 55°F prudent indoor maximum).
    Float inDp = dry.indoorDp
    int dpMax = (settings.maxIndoorDewPoint ?: MAX_INDOOR_DEWPOINT_F) as Integer
    boolean overDp = (inDp != null && inDp > dpMax)
    boolean overRh = wasVenting ? (rh > (target - HUMIDITY_HYST_RH)) : (rh > target)
    boolean venting = overRh || overDp
    atomicState.humidityVenting  = venting
    atomicState.humidityTargetRh = target
    atomicState.humidityCurrentRh = rh
    return venting
}

boolean canStartFan() {
    if (!fanSwitch) return false
    if (atomicState.wildfireGuardActive) return false
    if (atomicState.freezeGuardActive)   return false
    if (isCooldownActive())              return false
    if (atomicState.manualOverrideActive) return false
    return true
}

boolean someoneHome() {
    if (!presenceEnabled || !presenceSensors) return true
    return presenceSensors.any { it.currentValue("presence")?.toString() == "present" }
}

boolean isCooldownActive() {
    if (!atomicState.fanStoppedAt) return false
    return (now() - atomicState.fanStoppedAt) < ((settings.cooldownMinutes ?: DEFAULT_COOLDOWN) * 60 * 1000L)
}

boolean isMinRunActive() {
    if (!atomicState.fanRunning || !atomicState.fanStartedAt) return false
    return (now() - atomicState.fanStartedAt) < ((settings.minRunMinutes ?: DEFAULT_MIN_RUN) * 60 * 1000L)
}

// ============================================================
// FAN CONTROL
// ============================================================

void fanOn() {
    atomicState.appControlledChange = true
    fanSwitch.on()
}

void fanOff() {
    atomicState.appControlledChange = true
    fanSwitch.off()
}

void startFan(String reason) {
    if (!fanSwitch) { logD "startFan: no fan configured"; return }
    if (atomicState.fanRunning) { logD "startFan: already running"; return }
    logI "▶️ FAN START — ${reason}"
    fanOn()
    atomicState.fanRunning   = true
    atomicState.fanStartedAt = now()
    atomicState.runtimeTickAnchor = now()
    atomicState.lastRunMode  = reason
    // Max-run safety net only applies in demand mode. In continuous/scheduled
    // modes the fan is SUPPOSED to run indefinitely, so the safety net would
    // wrongly stop it. Guards, presence, and manual override still stop it.
    String mode = settings.operatingMode ?: MODE_CONTINUOUS
    boolean indefiniteOk = (mode == MODE_CONTINUOUS) ||
                           (mode == MODE_SCHEDULED && inFanSchedule())
    if (!indefiniteOk) {
        runIn(((settings.maxRunMinutes ?: DEFAULT_MAX_RUN) * 60), "maxRunSafetyNet")
    }
    notify("fanStart",
           "💨 ${fanName()} started. Reason: ${reason}.",
           "The ${fanName()} has started. Reason: ${reason}.",
           [fan: fanName(), reason: reason])
    updateAppLabel()
    if (settings.purifiersParallel && purifierSwitches) evaluateAllPurifiers(indoorPm25High(), false, true)
}

void stopFan(String reason) {
    if (!fanSwitch) return
    if (!atomicState.fanRunning) { fanOff(); return }
    long ran = atomicState.fanStartedAt ? (now() - atomicState.fanStartedAt) : 0L
    int  min = (ran / 60000L).toInteger()
    // Accumulate lifetime + since-maintenance runtime (in minutes) for the
    // filter/core maintenance reminder.
    atomicState.runtimeTotalMin = ((atomicState.runtimeTotalMin ?: 0L) as Long) + ran / 60000L
    atomicState.runtimeSinceMaintMin = ((atomicState.runtimeSinceMaintMin ?: 0L) as Long) + ran / 60000L
    checkMaintenanceDue()
    logI "⏹️ FAN STOP — ${reason} (ran ${min} min)"
    fanOff()
    atomicState.fanRunning   = false
    atomicState.fanStoppedAt = now()
    unschedule("maxRunSafetyNet")
    runIn(((settings.cooldownMinutes ?: DEFAULT_COOLDOWN) * 60) + 10, "evaluateConditions")
    notify("fanStop",
           "⏹️ ${fanName()} stopped after ${min} min. Reason: ${reason}.",
           "The ${fanName()} has stopped after ${min} minutes. Reason: ${reason}.",
           [fan: fanName(), minutes: min, reason: reason])
    updateAppLabel()
}

def maxRunSafetyNet() {
    if (!(atomicState.fanRunning ?: false)) return
    logW "Max run safety net: stopping fan after ${settings.maxRunMinutes ?: DEFAULT_MAX_RUN} min"
    notify("maxRun",
           "⚠️ ${fanName()} ran for max allowed time (${settings.maxRunMinutes ?: DEFAULT_MAX_RUN} min). " +
           "Stopped to re-evaluate. Check stop conditions.",
           "The ${fanName()} has been running for the maximum allowed time of ${settings.maxRunMinutes ?: DEFAULT_MAX_RUN} minutes. " +
           "Stopping now to re-evaluate air quality.",
           [fan: fanName(), minutes: (settings.maxRunMinutes ?: DEFAULT_MAX_RUN)])
    stopFan("max run safety net")
}

// ============================================================
// PURIFIER CONTROL — PER-DEVICE POLICY ENGINE
// ============================================================

/**
 * Evaluate all purifiers against current air quality conditions.
 * Each device handled independently per its configured policy.
 *
 * @param pm25High     true if indoor PM2.5 exceeds run threshold
 * @param guardActive  true if wildfire or freeze guard is active
 * @param parallelMode true if fan is running and parallel mode is enabled
 */
void evaluateAllPurifiers(boolean pm25High, boolean guardActive, boolean parallelMode) {
    if (!purifierSwitches) return
    purifierSwitches.each { device -> evaluatePurifier(device, pm25High, guardActive, parallelMode) }
}

void evaluatePurifier(device, boolean pm25High, boolean guardActive, boolean parallelMode) {
    String deviceId = device.id?.toString()
    if (!deviceId) return
    String policy = settings["purifier_policy_${deviceId}"] ?: "auto"

    switch (policy) {
        case "alwaysOn":
            // App turns on when warranted but NEVER turns off — user controls shutoff
            boolean turnOn = pm25High || (guardActive && (settings.purifiersOnWhenGuard != false)) || parallelMode
            if (turnOn) purifierOn(device, deviceId, pm25High ? "PM2.5 elevated" : guardActive ? "guard active" : "parallel mode")
            // Never call purifierOff for alwaysOn
            break

        case "scheduled":
            boolean inWindow      = inPurifierSchedule(deviceId)
            boolean safetyOverride = guardActive && (settings.purifiersOnWhenGuard != false)
            if (safetyOverride && !inWindow) {
                // Safety event fires even outside scheduled window — ASHRAE GL44-2024
                purifierOn(device, deviceId, "safety guard override (outside schedule window)")
            } else if (inWindow) {
                evaluatePurifierAuto(device, deviceId, pm25High, guardActive, parallelMode)
            }
            // Outside window with no safety event: leave device entirely alone
            break

        case "manualRespected":
            if (atomicState["purifier_mo_${deviceId}"] ?: false) {
                logD "${device.displayName}: manual override active — skipping"
                return
            }
            evaluatePurifierAuto(device, deviceId, pm25High, guardActive, parallelMode)
            break

        case "auto":
        default:
            evaluatePurifierAuto(device, deviceId, pm25High, guardActive, parallelMode)
            break
    }
}

void evaluatePurifierAuto(device, String deviceId, boolean pm25High, boolean guardActive, boolean parallelMode) {
    boolean turnOn     = pm25High || (guardActive && (settings.purifiersOnWhenGuard != false)) || parallelMode
    boolean currentOn  = device.currentValue("switch") == "on"
    if (turnOn && !currentOn) {
        String reason = [pm25High ? "PM2.5 elevated" : null,
                         guardActive ? "guard active" : null,
                         parallelMode ? "parallel mode" : null].findAll { it }.join(", ")
        purifierOn(device, deviceId, reason)
    } else if (!turnOn && currentOn) {
        purifierOff(device, deviceId)
    }
}

void purifierOn(device, String deviceId, String reason) {
    if (device.currentValue("switch") == "on") return
    logI "🌀 PURIFIER ON: ${device.displayName} — ${reason}"
    atomicState["purifier_appControlled_${deviceId}"] = true
    device.on()
    if (reason.contains("guard")) {
        // Queue rather than notify per-device. A guard event starts every
        // purifier at once, which previously produced one push/TTS per device
        // (4+ notifications for a single event). flushPurifierNotifications()
        // sends one summary instead.
        List q = (atomicState.purifierNotifyQueue ?: []) as List
        if (!q.contains(device.displayName)) q << device.displayName
        atomicState.purifierNotifyQueue  = q
        atomicState.purifierNotifyReason = reason
        runIn(8, "flushPurifierNotifications")
    }
}

/**
 * Send a single summary notification for all purifiers started by the same
 * guard event. Scheduled a few seconds after the first start so every device
 * in the batch is included.
 */
def flushPurifierNotifications() {
    List q = (atomicState.purifierNotifyQueue ?: []) as List
    if (!q) return
    String reason = atomicState.purifierNotifyReason ?: "guard active"
    atomicState.purifierNotifyQueue = []

    String names = q.size() == 1 ? q[0] :
                   (q.size() == purifierSwitches?.size() ? "All ${q.size()} air purifiers" :
                    "${q.size()} air purifiers (${q.join(', ')})")
    notify("purifierOn",
           "🌀 ${names} started: ${reason}.",
           "${names} ${q.size() == 1 ? 'is' : 'are'} now running. ${reason}.",
           [purifier: names, reason: reason, count: q.size()])
}

void purifierOff(device, String deviceId) {
    if (device.currentValue("switch") != "on") return
    logI "⚪ PURIFIER OFF: ${device.displayName}"
    atomicState["purifier_appControlled_${deviceId}"] = true
    device.off()
}

void stopPurifiers() {
    if (!purifierSwitches) return
    purifierSwitches.each { device ->
        String deviceId = device.id?.toString()
        if (!deviceId) return
        String policy = settings["purifier_policy_${deviceId}"] ?: "auto"
        if (policy == "alwaysOn" || policy == "scheduled") return
        if (policy == "manualRespected" && (atomicState["purifier_mo_${deviceId}"] ?: false)) return
        purifierOff(device, deviceId)
    }
}

void controlPurifiers(boolean pm25High) {
    boolean guardActive = (atomicState.wildfireGuardActive ?: false) || (atomicState.freezeGuardActive ?: false)
    evaluateAllPurifiers(pm25High, guardActive, false)
}

boolean inPurifierSchedule(String deviceId) {
    def schedStart = settings["purifier_schedStart_${deviceId}"]
    def schedEnd   = settings["purifier_schedEnd_${deviceId}"]
    if (!schedStart || !schedEnd) return true
    try {
        Date now    = new Date()
        Date qStart = timeToday(schedStart, location.timeZone)
        Date qEnd   = timeToday(schedEnd,   location.timeZone)
        if (qStart.before(qEnd)) {
            return now.after(qStart) && now.before(qEnd)
        } else {
            return now.after(qStart) || now.before(qEnd)  // spans midnight
        }
    } catch (e) {
        logW "inPurifierSchedule error for ${deviceId}: ${e.message}"
        return true
    }
}

// ============================================================
// NOTIFICATION ENGINE
// ============================================================

/**
 * Send push and/or TTS notifications for an event.
 * Checks user's per-event selection and quiet hours.
 *
 * TTS message selection order:
 *   1. User override template for this event (TTS Messages page), if set
 *   2. The ttsMsg passed by the caller (the built-in default)
 *   3. The pushMsg (last resort)
 * The configurable prefix (default "Attention, attention, attention...")
 * is prepended to defeat TTS devices that clip the opening words while
 * they wake/connect.
 *
 * @param eventKey  Key from NOTIF_EVENTS (e.g. "fanStart")
 * @param pushMsg   Text for push notification
 * @param ttsMsg    Built-in default natural-language TTS text (caller-supplied)
 */
void notify(String eventKey, String pushMsg, String ttsMsg = null, Map tokens = [:]) {
    // ── Rate limiting ────────────────────────────────────────────────────────
    // Structural protection against notification overload: the same event key
    // will not fire again within its cooldown window. Guard on/off events use a
    // short window (you want to know promptly when conditions change), advisory
    // events a long one. Prevents a flapping sensor from burying the phone.
    Integer cooldownMin = notifyCooldownMinutes(eventKey)
    if (cooldownMin > 0) {
        Long last = atomicState["notifyLast_${eventKey}"] as Long
        if (last != null && (now() - last) < (cooldownMin * 60 * 1000L)) {
            logD "Notification [${eventKey}] suppressed — within ${cooldownMin} min cooldown"
            return
        }
        atomicState["notifyLast_${eventKey}"] = now()
    }

    // Push notification
    if (notifyDevices && settings["push_${eventKey}"] != false) {
        logD "Push [${eventKey}]: ${pushMsg}"
        notifyDevices.each { it.deviceNotification(pushMsg) }
    }

    // TTS announcement
    if (ttsDevices && settings["tts_${eventKey}"] != false) {
        // User override template wins; otherwise the caller's default; otherwise push text
        String override = settings["ttsMsg_${eventKey}"]
        String body
        if (override != null && override.trim() != "") {
            // Substitute {tokens} in the user's custom wording with live values
            body = substituteTokens(override.trim(), tokens)
        } else {
            body = ttsMsg ?: pushMsg
        }

        if (isQuietHours(eventKey)) {
            logD "TTS [${eventKey}] suppressed (quiet hours)"
        } else {
            String prefix = (settings.ttsPrefix != null) ? settings.ttsPrefix : "Attention, attention, attention... "
            if (prefix && !prefix.endsWith(" ")) prefix = prefix + " "
            String msg = "${prefix}${body}"
            logD "TTS [${eventKey}]: ${msg}"
            Integer vol = settings.ttsVolume ?: null
            ttsDevices.each { dev ->
                try {
                    if (vol != null) dev.speak(msg, vol)
                    else             dev.speak(msg)
                } catch (e) {
                    logW "TTS speak() failed on ${dev.displayName}: ${e.message}"
                }
            }
        }
    }
}

/** Replace {token} placeholders in a user template with live values. */
String substituteTokens(String template, Map tokens) {
    String result = template
    tokens.each { k, v ->
        result = result.replace("{${k}}", v?.toString() ?: "")
    }
    return result
}

/**
 * Minutes before the same event may notify again. 0 = no limit.
 * Tuned so a user's phone never gets buried: state-change events are allowed
 * through promptly, advisory/repeating events are throttled hard.
 */
Integer notifyCooldownMinutes(String key) {
    switch (key) {
        // Real state changes — you want these promptly, but not repeatedly.
        case "wildfireOn": case "wildfireOff":
        case "freezeOn":   case "freezeOff":
            return 15
        // Urgent / rare — never throttle.
        case "co2Override": case "ventAborted":
            return 0
        // Advisory and repeating — throttle hard.
        case "co2Rising":        return 60
        case "outdoorDisagree":  return 180
        case "noOutdoorData":    return 120
        case "humidityCannotVent": return 720
        case "maintenanceDue":   return 1440
        case "forecastBadAir":   return 720
        // Routine chatter if the user opts in.
        case "fanStart": case "fanStop": case "purifierOn":
            return 10
        // Sensor faults can recur while a sensor misbehaves — throttle hard so a
        // flaky sensor cannot spam the phone.
        case "sensorFault":      return 120
        // Override/resume pairs: enough to report the state change, not a stream.
        case "manualOverride":   return 15
        case "manualResume":     return 15
        case "maxRun":           return 60
        case "arrivalVent":      return 30
        default:                 return 5
    }
}

boolean isQuietHours(String eventKey) {
    if (!(settings.quietHoursEnabled ?: false)) return false
    // Safety alerts always announce if configured
    boolean isSafetyEvent = eventKey in ["wildfireOn", "freezeOn", "co2Override", "ventAborted"]
    if (isSafetyEvent && (settings.quietAllowSafetyAlerts != false)) return false
    if (!settings.quietStart || !settings.quietEnd) return false
    try {
        Date now      = new Date()
        Date qStart   = timeToday(settings.quietStart, location.timeZone)
        Date qEnd     = timeToday(settings.quietEnd,   location.timeZone)
        if (qStart.before(qEnd)) {
            return now.after(qStart) && now.before(qEnd)
        } else {
            // Spans midnight
            return now.after(qStart) || now.before(qEnd)
        }
    } catch (e) {
        logW "isQuietHours parse error: ${e.message}"
        return false
    }
}

// Default notification states — push on for important events, off for routine
boolean defaultNotifState(String key) {
    // Default PUSH events. Deliberately excludes purifierOn/fanStart/fanStop:
    // the guard notifications already state what was turned on or off, so
    // separate per-device messages are duplication. Users can enable them.
    return key in ["wildfireOn","wildfireOff","freezeOn","freezeOff","co2Override","co2Rising","noOutdoorData","manualOverride","maintenanceDue","ventAborted","humidityCannotVent"]
}

// Default TTS states — only the most important events speak by default
boolean defaultTtsState(String key) {
    // Default TTS events: only urgent, action-worthy ones. Speaking is far
    // more intrusive than a push, so this set is deliberately small.
    return key in ["wildfireOn","freezeOn","co2Override","ventAborted"]
}

// ============================================================
// APP LABEL — dynamic status badge in Hubitat installed-apps list
// ============================================================
// One badge at a time; highest-severity wins. Called from every
// state-changing method so the list always reflects current status.
// Priority: guards > sensor fault > manual override > CO₂ override
//           > running > purifiers only > disabled > OK

// ── Dashboard status child device ─────────────────────────────────────────────
// Creates an optional child device exposing app status as attributes so it can
// be shown on Hubitat Dashboard tiles. Uses the built-in "Virtual Omni Sensor"
// style attributes via a generic component driver.

private String statusDeviceDni() { return "hrv-status-${app.id}" }

void ensureStatusDevice() {
    if (!(settings.statusDeviceEnabled ?: false)) return
    def dev = getChildDevice(statusDeviceDni())
    if (!dev) {
        try {
            dev = addChildDevice("hubitat", "Virtual Omni Sensor", statusDeviceDni(),
                                 [name: "HRV Status", label: "HRV Status", isComponent: false])
            logI "Created dashboard status device: HRV Status"
        } catch (e) {
            logW "Could not create status device: ${e.message}. Dashboard tiles unavailable."
            return
        }
    }
}

void updateStatusDevice() {
    if (!(settings.statusDeviceEnabled ?: false)) return
    def dev = getChildDevice(statusDeviceDni())
    if (!dev) return
    try {
        String state = statusText()
        dev.sendEvent(name: "variable",    value: state)
        Float opm = atomicState.currentOutdoorPm25 as Float
        if (opm != null) dev.sendEvent(name: "pm25", value: opm.toInteger(), unit: "ug/m3")
        Float co2 = maxIndoorCo2()
        if (co2 != null && co2 > 0) dev.sendEvent(name: "carbonDioxide", value: co2.toInteger(), unit: "ppm")
        Float rh = maxIndoorHumidity()
        if (rh != null) dev.sendEvent(name: "humidity", value: rh.toInteger(), unit: "%")
        dev.sendEvent(name: "switch", value: (atomicState.fanRunning ?: false) ? "on" : "off")
    } catch (e) {
        logW "Status device update failed: ${e.message}"
    }
}

/** Short human-readable app status, same priority order as the app label badge. */
String statusText() {
    if (!enableApp)                          return "Disabled"
    if (atomicState.wildfireGuardActive)     return "Wildfire Guard"
    if (atomicState.freezeGuardActive)       return "Freeze Guard"
    if (anySensorFaulted())                  return "Sensor Fault"
    if (atomicState.emergencyVentRunning)    return "CO2 Override"
    if (atomicState.manualOverrideActive)    return "Manual Override"
    if (atomicState.humidityVenting)         return "Drying (humidity)"
    if (atomicState.fanRunning)              return "Running"
    if (anyPurifierOn())                     return "Purifiers Only"
    return "OK"
}

void updateAppLabel() {
    // Robust approach: use the stored clean base name, always rebuild from it.
    // The base name is (re)captured in captureBaseName() during install/update,
    // so user renames are respected and we never parse a rendered label back.
    String base = atomicState.appBaseName ?: "HRV / Air Quality Manager"

    String badge = ""

    if (!enableApp) {
        badge = LABEL_DISABLED

    } else if (atomicState.wildfireGuardActive) {
        badge = LABEL_WILDFIRE

    } else if (atomicState.freezeGuardActive) {
        badge = LABEL_FREEZE

    } else if (anySensorFaulted()) {
        badge = LABEL_SENSOR_FAULT

    } else if (atomicState.emergencyVentRunning) {
        badge = LABEL_CO2_OVERRIDE

    } else if (atomicState.manualOverrideActive) {
        badge = LABEL_MANUAL_FAN

    } else if (atomicState.fanRunning) {
        badge = LABEL_RUNNING

    } else if (anyPurifierOn()) {
        badge = LABEL_PURIFIERS

    } else {
        badge = LABEL_OK
    }

    app.updateLabel("${base}${badge}")
    updateStatusDevice()
}

boolean anySensorFaulted() {
    if (!indoorSensors) return false
    return indoorSensors.any { s ->
        (atomicState["sensor_fault_carbonDioxide_${s.id}"] ?: false) ||
        (atomicState["sensor_fault_pm25_${s.id}"] ?: false)
    }
}

boolean anyPurifierOn() {
    if (!purifierSwitches) return false
    return purifierSwitches.any { it.currentValue("switch") == "on" }
}

// ============================================================
// SENSOR VALIDATION ENGINE
// ============================================================
// Guards against stuck readings, implausible spikes, out-of-range
// values, and stale data from indoor air quality sensors.
// Per-sensor state stored in atomicState keyed by sensor id.
// A faulted sensor is excluded from triggering the HRV/purifiers.
// Auto-recovers after SENSOR_RECOVER_COUNT consecutive valid readings.

/**
 * Validate a single sensor reading before acting on it.
 * Returns true if reading is valid and should be used.
 * Updates per-sensor fault tracking in atomicState.
 *
 * @param sensorId   device id as String
 * @param sensorName display name for logging/notifications
 * @param attribute  "carbonDioxide" or "pm25"
 * @param value      the reported float value
 * @return true if valid
 */
boolean validateSensorReading(String sensorId, String sensorName, String attribute, float value, Long readingTs = null) {
    String key = "sensor_${attribute}_${sensorId}"

    // Is this a genuinely NEW reading, or a repeat read of the same cached
    // value? validSensorReadings() runs several times per evaluation, so
    // without this gate the stuck-detector and recovery counter would advance
    // on reads rather than on actual sensor updates — which produced false
    // "stuck" faults on healthy sensors.
    boolean isNewReading
    if (readingTs != null) {
        Long lastTs = atomicState["${key}_lastEventTs"] as Long
        isNewReading = (lastTs == null) || (readingTs != lastTs)
        if (isNewReading) atomicState["${key}_lastEventTs"] = readingTs
    } else {
        // No timestamp available: fall back to treating an unchanged value as
        // a repeat read. Conservative — never invents a new reading.
        Float prev = atomicState["${key}_lastSeenVal"] as Float
        isNewReading = (prev == null) || (Math.abs(value - prev) > 0.001f)
        atomicState["${key}_lastSeenVal"] = value
    }

    // ── Range check ──────────────────────────────────────────────────────────
    float minVal, maxVal
    if (attribute == "carbonDioxide") {
        minVal = CO2_MIN_PLAUSIBLE; maxVal = CO2_MAX_PLAUSIBLE
    } else {
        minVal = PM25_MIN_PLAUSIBLE; maxVal = PM25_MAX_PLAUSIBLE
    }

    if (value < minVal || value > maxVal) {
        flagSensor(sensorId, sensorName, attribute,
                   "out-of-range value ${value.toInteger()} (valid: ${minVal.toInteger()}-${maxVal.toInteger()})",
                   value < minVal)  // stuck-low is safety concern
        return false
    }

    // ── Spike check REMOVED in v3.4.0 ────────────────────────────────────────
    // These PM sensors (WH46D/WH41) legitimately swing 0→20+ µg/m³ between
    // consecutive readings — cooking, the sensor's own fan cycling, normal
    // household activity. A spike and a glitch are indistinguishable in one
    // sample, and real spikes vastly outnumber glitches, so spike detection
    // produced only false alarms. Range and stale checks are the real signals.

    // ── Stuck check ──────────────────────────────────────────────────────────
    // A stuck sensor reports an identical value for a very long time. But clean
    // indoor air legitimately produces stable low PM2.5 (1-2 µg/m³) for hours,
    // and a well-ventilated room holds steady CO₂ — those are NOT faults.
    // So we only flag "stuck" when the value is high enough that variation
    // would be expected, AND it hasn't moved across many readings. Both bounds
    // are user-configurable on the Sensor Validation page.
    int stuckCount    = (settings.sensorStuckCount    ?: DEFAULT_STUCK_COUNT)     as Integer
    int stuckMinValue = (settings.sensorStuckMinValue ?: DEFAULT_STUCK_MIN_VALUE) as Integer
    if (settings.sensorStuckEnabled != false && isNewReading) {
    List history = (atomicState["${key}_history"] ?: []) as List
    BigDecimal rounded = (value as BigDecimal).setScale(1, java.math.RoundingMode.HALF_UP)
    history = (history.takeRight(stuckCount - 1) + [rounded.toString()])
    atomicState["${key}_history"] = history
    boolean highEnoughToExpectVariation = value > stuckMinValue
    if (highEnoughToExpectVariation &&
        history.size() >= stuckCount &&
        (history as Set).size() == 1) {
        flagSensor(sensorId, sensorName, attribute,
                   "stuck reading: ${value.toInteger()} unchanged for ${stuckCount} readings",
                   false)  // stuck-high is efficiency, not safety
        return false
    }
    }

    // ── Valid reading ─────────────────────────────────────────────────────────
    // NOTE: Stale detection is handled separately by checkSensorStaleness()
    // on a schedule — it cannot live here because this method only runs when
    // a fresh reading arrives, at which point the sensor is by definition not stale.
    atomicState["${key}_lastValid"] = value
    atomicState["${key}_ts"]        = now()

    // Check for recovery from fault (per-attribute — a healthy PM2.5 reading
    // must not clear a stuck-CO₂ fault on the same device, and vice versa)
    if (atomicState["sensor_fault_${attribute}_${sensorId}"] ?: false) {
        // Only count genuinely new readings toward recovery — otherwise the
        // several reads inside a single evaluation would clear a fault instantly.
        if (!isNewReading) return true
        int recoverCount = ((atomicState["${key}_recoverCount"] ?: 0) as Integer) + 1
        atomicState["${key}_recoverCount"] = recoverCount
        if (recoverCount >= SENSOR_RECOVER_COUNT) {
            atomicState["sensor_fault_${attribute}_${sensorId}"] = false
            atomicState["${key}_recoverCount"]       = 0
            logI "✅ Sensor recovered: ${sensorName} ${attribute} — ${recoverCount} consecutive valid readings"
            notify("sensorFault",
                   "✅ Sensor recovered: ${sensorName} ${attribute} is reporting valid data again.",
                   "Good news: ${sensorName} is reporting valid ${attribute == "carbonDioxide" ? "CO 2" : "PM 2.5"} data again.",
                   [sensor: sensorName, detail: "reporting valid data again"])
            updateAppLabel()
        }
    } else {
        atomicState["${key}_recoverCount"] = 0
    }

    return true
}

void flagSensor(String sensorId, String sensorName, String attribute, String reason, boolean safetyRisk) {
    boolean wasAlreadyFaulted = atomicState["sensor_fault_${attribute}_${sensorId}"] ?: false
    atomicState["sensor_fault_${attribute}_${sensorId}"] = true

    if (!wasAlreadyFaulted) {
        String safety = safetyRisk ?
            " ⚠️ SAFETY RISK: stuck-low reading may prevent HRV from running when needed." :
            " Efficiency impact: may cause unnecessary HRV runs."
        logW "🚨 Sensor fault: ${sensorName} ${attribute} — ${reason}.${safety}"
        notify("sensorFault",
               "🚨 Sensor fault: ${sensorName} ${attribute} — ${reason}.${safety} " +
               "Sensor excluded from automation until ${SENSOR_RECOVER_COUNT} valid readings received.",
               "Warning: ${sensorName} has a sensor fault. ${attribute == "carbonDioxide" ? "CO 2" : "PM 2.5"} reading is ${reason}. " +
               "${safetyRisk ? "This is a safety concern — the ventilation fan may not start when needed." : ""} " +
               "The sensor has been excluded from automation.",
               [sensor: sensorName, detail: reason])
        updateAppLabel()
    }
}

/**
 * Validate all indoor sensors and return a map of valid readings only.
 * Called at the top of conditionsRequireFan() and maxIndoorCo2().
 */
Map<String, Map<String, Float>> validSensorReadings() {
    Map result = [:]
    if (!indoorSensors) return result
    indoorSensors.each { s ->
        String id = s.id?.toString()
        if (!id) return
        Map readings = [:]
        def co2  = s.currentValue("carbonDioxide")
        def pm25 = s.currentValue("pm25")
        // Pass the attribute's last-update timestamp. validateSensorReading()
        // uses it to tell a genuinely NEW reading from a repeat read of the same
        // cached value — this method is called several times per evaluation, and
        // without this the stuck-detector counted reads instead of updates.
        Long co2Ts = null, pm25Ts = null
        try { co2Ts  = s.currentState("carbonDioxide")?.date?.getTime() } catch (e) { }
        try { pm25Ts = s.currentState("pm25")?.date?.getTime() }          catch (e) { }
        if (co2  != null && "${co2}".isNumber()  && validateSensorReading(id, s.displayName, "carbonDioxide", co2.toFloat(),  co2Ts))  readings.co2  = co2.toFloat()
        if (pm25 != null && "${pm25}".isNumber() && validateSensorReading(id, s.displayName, "pm25",           pm25.toFloat(), pm25Ts)) readings.pm25 = pm25.toFloat()
        if (readings) result[s.displayName] = readings
    }
    return result
}

// ============================================================
// OUTDOOR SENSOR DISAGREEMENT CHECK
// ============================================================

/**
 * Compare local outdoor PM2.5 sensor against AirNow reading.
 * Uses the worse (higher) reading for guard decisions.
 * Notifies when readings disagree by more than OUTDOOR_DISAGREE_MARGIN.
 *
 * Called from outdoorPmHandler (when local sensor fires) and
 * pollAirNow (when AirNow data arrives), only when both sources
 * are configured and have recent data.
 *
 * @return the effective PM2.5 to use for guard decisions (higher of two)
 */
float resolveOutdoorPm25() {
    Float localPm25  = atomicState.localOutdoorPm25  as Float
    Float airnowPm25 = atomicState.airnowOutdoorPm25 as Float

    // Ignore a local outdoor reading that has gone stale (sensor offline/battery
    // dead). 60 min is generous for a WH41 that normally reports every minute.
    Long lts = atomicState.localOutdoorPmTs as Long
    if (localPm25 != null && lts != null && (now() - lts) > (60 * 60 * 1000L)) {
        logW "Local outdoor PM2.5 sensor has not reported in over 60 minutes — using AirNow only"
        localPm25 = null
    }

    if (localPm25 == null && airnowPm25 == null) return -1.0f
    if (localPm25 == null)  { atomicState.outdoorPmSource = "airnow"; return airnowPm25 }
    if (airnowPm25 == null) { atomicState.outdoorPmSource = "local:${outdoorPmSensor?.displayName ?: 'sensor'}"; return localPm25 }

    // Both available — check for disagreement
    float diff = Math.abs(localPm25 - airnowPm25)
    if (diff > OUTDOOR_DISAGREE_MARGIN) {
        // NOTE: notify() also enforces a central per-event cooldown (see
        // notifyCooldownMinutes). This local hourly gate is kept because it also
        // guards the logW below; the stricter of the two windows governs.
        long lastDisagreeNotif = atomicState.lastDisagreeNotif ?: 0L
        if (now() - lastDisagreeNotif > 60 * 60 * 1000L) {
            atomicState.lastDisagreeNotif = now()
            float worse  = Math.max(localPm25, airnowPm25)
            float better = Math.min(localPm25, airnowPm25)
            String worseSource  = (localPm25 >= airnowPm25) ? outdoorPmSensor?.displayName : "AirNow"
            String betterSource = (localPm25 < airnowPm25)  ? outdoorPmSensor?.displayName : "AirNow"
            String direction    = (localPm25 > airnowPm25) ?
                "Local sensor reads worse than AirNow. " +
                "Possible causes: sensor needs cleaning, nearby local source (neighbor burning), or local wildfire front." :
                "AirNow reads worse than local sensor. " +
                "Possible causes: regional wildfire front not yet reached your location, or local sensor needs calibration."
            logW "⚠️ Outdoor PM2.5 disagreement: ${worseSource}=${worse.toInteger()} vs ${betterSource}=${better.toInteger()} µg/m³ (diff: ${diff.toInteger()}). Using worse reading: ${worse.toInteger()} µg/m³."
            notify("outdoorDisagree",
                   "⚠️ Outdoor PM2.5 sources disagree: ${worseSource}=${worse.toInteger()} µg/m³ vs ${betterSource}=${better.toInteger()} µg/m³ (${diff.toInteger()} µg/m³ apart). " +
                   "Using worse reading for safety. ${direction}",
                   "Warning: outdoor air quality sensors disagree. ${worseSource} reads ${worse.toInteger()} micrograms, " +
                   "${betterSource} reads ${better.toInteger()} micrograms per cubic meter. " +
                   "Using the worse reading of ${worse.toInteger()} for safety. ${direction}",
                   [worse: worse.toInteger(), better: better.toInteger(), worseSource: worseSource, betterSource: betterSource])
        }
    }

    // Always return the worse (higher) reading — conservative/safe
    float worst = Math.max(localPm25, airnowPm25)
    atomicState.outdoorPmSource = (localPm25 >= airnowPm25) ?
        "local:${outdoorPmSensor?.displayName ?: 'sensor'}" : "airnow"
    return worst
}

// ============================================================
// HELPERS
// ============================================================

String buildRunReason(int co2Thr, int pm25Thr, boolean awayRelaxed) {
    List<String> reasons = []
    indoorSensors?.each { s ->
        def co2  = s.currentValue("carbonDioxide")
        def pm25 = s.currentValue("pm25")
        if (co2  != null && (co2 as BigDecimal)  > co2Thr)  reasons << "${s.displayName} CO₂=${co2}ppm"
        if (pm25 != null && (pm25 as BigDecimal) > pm25Thr) reasons << "${s.displayName} PM2.5=${pm25}µg/m³"
    }
    return "${awayRelaxed ? "[away-relaxed] " : ""}${reasons.join(", ")}"
}

String fanName() {
    return settings.fanTypeName ?: (fanSwitch ? fanSwitch.displayName : "ventilation fan")
}

String fanSummary() {
    if (!fanSwitch) return "Not configured — purifier-only mode"
    def s = fanSwitch.currentValue("switch") ?: "unknown"
    def modeLabel = [(MODE_CONTINUOUS):"continuous", (MODE_DEMAND):"demand", (MODE_SCHEDULED):"scheduled"][settings.operatingMode ?: MODE_CONTINUOUS]
    return "${fanSwitch.displayName} | ${s.toUpperCase()} | mode: ${modeLabel}"
}
String sensorValidationSummary() {
    List parts = ["range ✅"]
    parts << ((settings.sensorStuckEnabled != false) ? "stuck ✅" : "stuck off")
    parts << ((settings.sensorStaleEnabled != false) ? "stale ${settings.sensorStaleMinutes ?: DEFAULT_STALE_MINUTES}m" : "stale off")
    return parts.join(" | ")
}
String humiditySummary() {
    if (!(settings.humidityControlEnabled ?: false)) return "Off — tap to enable winter condensation protection"
    if (!humiditySensors) return "Enabled — no humidity sensor selected"
    def rh = maxIndoorHumidity()
    Integer tgt = humidityTargetRh()
    String mode = settings.humidityUseCustom ? "custom" : "auto table"
    return "${humiditySensors.size()} sensor(s), ${mode}${rh != null ? " | now ${rh.toInteger()}%" : ""}${tgt != null ? " ≤ ${tgt}%" : ""}"
}
String maintenanceSummary() {
    if (!(settings.maintenanceEnabled ?: false)) return "Off — tap to enable runtime-based reminders"
    long sinceMin = (atomicState.runtimeSinceMaintMin ?: 0L) as Long
    int sinceH = (sinceMin / 60L) as Integer
    int intervalH = (settings.maintenanceHours ?: DEFAULT_MAINT_HOURS) as Integer
    String due = (atomicState.maintenanceNotified ?: false) ? " ⚠️ DUE" : ""
    return "${sinceH} / ${intervalH} runtime hrs since service${due}"
}
String sensorSummary()       { indoorSensors ? "${indoorSensors.size()} sensor(s)" : "Tap to configure" }
String outdoorSummary()      {
    List<String> p = []
    if (outdoorPmSensor) p << "local PM ✅"
    if (!localOnlyMode && airnowApiKey) p << "AirNow ✅"
    if (localOnlyMode) p << "local-only"
    if (outdoorTempSensor) p << "temp ✅"
    return p ? p.join(" | ") : "Tap to configure (recommended)"
}
String purifierSummary() {
    if (!purifierSwitches) return "Optional — tap to configure"
    def counts = purifierSwitches.countBy { p ->
        (settings["purifier_policy_${p.id}"] ?: "auto")
    }
    def parts = counts.collect { k, v ->
        def label = ["auto":"auto","manualRespected":"manual","alwaysOn":"always on","scheduled":"scheduled"][k] ?: k
        "${v} ${label}"
    }
    return "${purifierSwitches.size()} purifier(s): ${parts.join(', ')}"
}
String presenceSummary()     { !presenceEnabled ? "Disabled" : presenceSensors ? "${presenceSensors.size()} sensor(s) | away: ${settings.awayBehavior ?: "relaxed"}" : "Enabled — no sensors" }
String manualOverrideSummary() {
    def policy = [
        (MANUAL_RESUME_TIMED):      "Resume after ${settings.manualOverrideMinutes ?: 30} min",
        (MANUAL_RESUME_NEXT_EVAL):  "Resume on next sensor event",
        (MANUAL_RESUME_INDEFINITE): "Pause indefinitely"
    ][settings.manualOverridePolicy ?: MANUAL_RESUME_TIMED]
    return "${atomicState.manualOverrideActive ? "⚠️ ACTIVE — " : ""}${policy}"
}
String pushSummary()         { notifyDevices ? "${notifyDevices.size()} device(s)" : "None configured" }
String ttsSummary()          {
    if (!ttsDevices) return "None configured"
    String quiet = (settings.quietHoursEnabled ?: false) ? " | quiet ${settings.quietStart?.substring(11,16) ?: "?"}-${settings.quietEnd?.substring(11,16) ?: "?"}" : ""
    return "${ttsDevices.size()} speaker(s)${quiet}"
}

String h(String text) { "<b>${text}</b>" }
String warn(String t) { "<font color='orange'>⚠️ ${t}</font>" }

void logI(String msg) { if (logLevel != "OFF") log.info  "[AirQuality] ${msg}" }
void logD(String msg) { if (logLevel == "DEBUG") log.debug "[AirQuality] ${msg}" }
void logW(String msg) { log.warn "[AirQuality] ${msg}" }