/*
 * Local Irrigation Manager
 * Version: 3.4.0-dev-waterbalance  |  Date: 2026-06-25  |  BRANCH (not for production yet)
 *
 * Author:    FrankTank34567 (https://github.com/FrankTank34567)
 * Copyright (c) 2026 FrankTank34567. All rights reserved except as granted
 * under the GNU GPL v3 license below.
 *
 * Developed with the assistance of Claude (Anthropic, claude.ai) used as a
 * coding tool. The author directed the design and is the human author of this
 * work; AI-assisted output was reviewed, tested, and adopted by the author.
 *
 * Design and implementation session covering:
 *   - FAO-56 Penman-Monteith ET from local Hubitat weather station device
 *   - Dynamic solar noon via NOAA solar equations (pure math, no internet)
 *   - Seasonal ET baseline from latitude + month lookup table (no internet)
 *   - NOAA NWS fallback — explicit opt-in, prominently flagged in UI
 *   - Per-zone soil/plant/nozzle with FAO-56 agronomic coefficients
 *   - Event-driven valve sequencing via runIn callbacks (never blocks hub)
 *   - atomicState for thread-safe concurrent event handling
 *   - Six selectable log levels (Off/Error/Warn/Info/Debug/Trace) with timed auto-reset
 *   - Device-agnostic: any sensor, any valve or switch, any weather station
 *   - Manual valve activation monitoring with configurable credit window
 *   - Soil sensor failure tolerance: stale detection, age threshold, fallback behaviors
 *
 * ── Attribution ──────────────────────────────────────────────────────────────
 * The agronomic lookup values (crop coefficient Kc, management-allowed depletion
 * MAD, root depth, and soil available-water capacity AWC) are standard published
 * constants from the FAO-56 reference below — scientific facts, not original
 * authorship of any project.
 *
 * During development, WET-IT by Marc Hedish was reviewed as a reference, and this
 * app's agronomic tables were compared against and found to match WET-IT's values.
 * WET-IT is licensed under the Apache License, Version 2.0. In acknowledgment of
 * that reference and in keeping with Apache 2.0 §4:
 *   Portions adapted/verified against WET-IT — Copyright (c) Marc Hedish,
 *   licensed under the Apache License, Version 2.0
 *   (https://www.apache.org/licenses/LICENSE-2.0). No warranty; see the License.
 *   Changes: values re-expressed as Groovy @Field maps keyed by this app's own
 *   plant/soil category names and integrated into an independent FAO-56 ET engine;
 *   no WET-IT source code (logic, structure, or identifiers) was carried over.
 * Apache 2.0 code may be combined into this GPLv3 work (the licenses are
 * one-way compatible in that direction); the Apache-licensed portions retain the
 * notice above.
 *
 * Source of the underlying values:
 *   FAO Irrigation and Drainage Paper No. 56, "Crop Evapotranspiration",
 *   Tables 11, 12, and A.1.
 *
 * References:
 *   FAO-56 PM:     http://www.fao.org/3/X0490E/x0490e07.htm
 *   Hargreaves:    http://edis.ifas.ufl.edu/pdffiles/ae/ae45900.pdf
 *   NOAA Solar:    https://gml.noaa.gov/grad/solcalc/solareqns.PDF
 *
 * License: GNU General Public License v3.0
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program. If not, see https://www.gnu.org/licenses/.
 *   Full text: https://www.gnu.org/licenses/gpl-3.0.en.html
 *
 * Changelog:
 *  1.0.0  Initial release
 *  1.1.0  Non-blocking runIn, atomicState, per-zone pages, valve+switch, NOAA opt-in
 *  1.2.0  Dynamic solar noon math, seasonal baseline, 6-level logging, device-agnostic
 *  1.3.0  Manual valve monitoring with credit window; sensor failure tolerance
 *         (stale detection, age threshold, configurable fallback behaviors);
 *         solar noon delta bounds fix; sensor last-seen tracking
 *  1.4.0  CRITICAL FIXES — Fixed syntax error in fetchNoaaWeatherForEt that
 *         prevented compilation (operator precedence: `X as BigDecimal+Y` is
 *         parsed as `X as (BigDecimal+Y)`). Fixed device matching to use
 *         `device.id` (String, hub-internal) instead of `device.deviceId`
 *         (manufacturer fingerprint ID) — soil sensor tracking and manual
 *         valve monitoring previously never matched correctly. Replaced
 *         custom NOAA solar-equation math with Hubitat's built-in
 *         `getSunriseAndSunset()` midpoint. Added master LOCAL-ONLY toggle
 *         that blocks all internet calls regardless of other settings.
 *         Tightened `now() - X as Long` precedence with explicit parens.
 *  1.4.1  Hubitat sandbox compatibility fixes: replaced .clamp() (not available
 *         on BigDecimal in sandbox) with Math.max/Math.min throughout.
 *         Fixed seasonalBaseline integer division: Groovy's / operator on
 *         integers returns BigDecimal (not int) — replaced with intdiv().
 *         Replaced BigDecimal ** 5.26 in FAO-56 pressure equation with
 *         Math.pow() (Groovy's ** with non-integer exponent on BigDecimal
 *         is unsafe in Hubitat sandbox). Replaced BigDecimal ** 2 with
 *         explicit multiply to avoid ** on BigDecimal entirely.
 *  1.4.2  Hubitat best-practice compliance:
 *         - Fixed GroovyCastException on first install: settings.logLevel is null
 *           before the logging page is visited; `null as int` throws but
 *           `null as Integer` (boxed) returns null safely, letting ?: provide default.
 *         - Added location.temperatureScale awareness: hub may be configured for
 *           Celsius; station drivers report in hub scale; stationTempToF() converts
 *           to °F for ET equations only when needed (no double-conversion).
 *         - Solar noon now uses location.sunrise and location.sunset (direct Date
 *           properties on the Location object) instead of getSunriseAndSunset()
 *           — simpler, no extra method call, same Hubitat-provided data.
 *         - UI shows actual hub sunrise/sunset times and temperature scale.
 *  1.4.3  NOAA and logging fixes:
 *         - Added noaaFetch() shared helper using textParser+JsonSlurper for
 *           explicit JSON parsing — fixes "⚠️ HTTP 200" by not relying on
 *           Hubitat auto-parsing application/geo+json Content-Type.
 *         - Added noaaCoord() to format coordinates to 4 decimal places for
 *           NOAA URL compatibility.
 *         - NOAA test now shows city name on success (from relativeLocation).
 *         - isStationFresh() "never reported" log demoted from warn to debug
 *           since it fires from every UI render on first install; stale warning
 *           (actionable) remains at warn.
 *         - initialize() now seeds stationLastSeen from station's current
 *           attribute value so freshly-configured stations aren't immediately
 *           flagged as stale.
 *  1.4.4  NOAA daily forecast fetch (PoP/QPF/Tmax/Tmin at 2 PM); predictive
 *         rain-skip guard in guards page; Hargreaves uses cached Tmax/Tmin;
 *         dew point spread and pressure trend local guards added.
 *  1.5.0  "How It Works" plain-English explanation on main page; manual credit
 *         clarified as FULL ZONE SKIP; NOAA test result timestamped; soil
 *         sensor last-seen seeded on init (fixes stale on fresh install);
 *         plain-English labels for soil/plant/nozzle types with USDA link;
 *         sensor placement best-practice guidance (from WS90 and WH52 manuals);
 *         WH52 3-in-1 support: per-zone soil temperature and EC attribute
 *         mapping; cold soil guard from WH52 soil temperature; local frost
 *         guard from dew point spread; local storm guard from pressure trend.
 *  1.6.0  ET normals auto-refresh: when the embedded 1991-2020 NOAA normals
 *         table approaches 10 years old (year >= 2029), attempts a live
 *         refresh via 3-API chain (no API key required):
 *           1. NWS /points/{lat,lon} → nearest station ICAO ID
 *           2. NCEI HOMR API (ICAO → GHCND station ID)
 *           3. NCEI Data Service → MLY-TMAX-NORMAL + MLY-TMIN-NORMAL
 *         Hargreaves ET computed per month from normals; stored in
 *         atomicState.dynamicEtBaseline; preferred by seasonalBaseline().
 *         Fallback (Option B): cached observed Tmax/Tmin used to compute
 *         a bias-correction ratio applied to the static table's seasonal
 *         shape. Advanced page shows source, GHCND station, refresh date.
 *         Staleness check runs at startup and annually on Jan 15 at 3 AM.
 *         ICAO code validated as [A-Z0-9]{3,5} before URL insertion.
 *  1.7.0  Dynamic app label in Hubitat installed-apps list: single highest-
 *         severity badge shown at all times via updateAppLabel() helper.
 *         Badge priority (red→orange→blue→gray→green):
 *           🔴 Failsafe — failsafe handler force-closed valves
 *           🔴 Station Stale — weather station not reporting
 *           ⚠️ Sensor(s) Stale — one or more soil sensors silent
 *           ⚠️ Aborted — guard tripped while cycle was running
 *           ⚠️ ET Table Stale — normals old, NOAA off
 *           💧 Running — cycle in progress
 *           ⏭ Skipped — guard or inactive day prevented run
 *           ✅ Complete — last cycle finished normally
 *         updateAppLabel() called from: initialize(), failsafeClose(),
 *         midnightReset(), abortCycleNow(), irrigationCycle() (all skip
 *         paths + cycle start), startNextZone() (complete), and
 *         checkSensorHealth() (every 30 min). failsafeTriggered flag
 *         cleared by midnightReset() and at start of each new cycle.
 *         staleSoilZoneCount() helper counts stale soil sensors.
 *  1.8.0  Performance fixes (5 issues found by static audit):
 *         (1) updateAppLabel(): staleSoilZoneCount() was called twice
 *             causing a double zone-loop per label update; now computed
 *             once into a local variable before the priority chain.
 *         (2) resolveGhcndId() httpGet: explicit timeout:15 added to
 *             cap thread blockage if HOMR server is unresponsive.
 *         (3) fetchNceiNormals() httpGet: explicit timeout:20 added —
 *             NCEI can be slow; total worst-case ET refresh chain now
 *             bounded at ~55 s across all 4 sequential HTTP calls.
 *         (4) pressureHistory: O(n) findAll age-pruning on every
 *             barometer event (up to 240 items/deserialisation) replaced
 *             with a hard cap of 50 entries using remove(0); sufficient
 *             for the 30-min minimum trend window in calcPressureTrend().
 *         (5) calcPressureTrend(): .sort() removed — history list is
 *             already in chronological append order.
 *  1.9.0  Fix: failsafeClose() was unconditionally setting failsafeTriggered=true
 *         causing the red 🔴 Failsafe badge to appear every day when the
 *         scheduled failsafe time fired (even with no cycle running). Badge
 *         now only set when a cycle was actually active at failsafe time.
 *         Routine scheduled closes (no active cycle) log at INFO not WARN.
 *  1.9.1  Hotfix: initialize() now clears stale failsafeTriggered flag when
 *         no cycle is running, so the red badge self-heals on next app save
 *         rather than persisting until midnight reset.
 *  2.0.0  Bug audit — 7 bugs and 3 minor issues fixed:
 *         (1) noaaFetch(): added timeout:10 — the shared NWS helper was
 *             missing an explicit timeout unlike the NCEI helpers fixed
 *             in v1.8.0; up to 8 calls per cycle at default timeout.
 *         (2) isInSeason(): fixed MMDD comparison for seasons that cross
 *             year-end (e.g. Nov–Mar); previously always returned false
 *             for any date in the winter portion.
 *         (3) fToC() / cToF(): changed "if (!f)" to "if (f == null)" —
 *             0°F and 0°C are valid temperatures but evaluate falsy in
 *             Groovy, causing wrong ET results at exactly 0 degrees.
 *             Both now return null (not 0G) on null input; all callers
 *             already guard correctly against null.
 *         (4) initialize(): station heartbeat and guard subscriptions
 *             now use resolvedAttr() for temperature, rainRate, and
 *             rainDaily — previously hardcoded "temperature" was ignored
 *             if user mapped a different attribute name, causing the
 *             station to appear permanently stale.
 *         (5) manualStopZone(): removed unschedule("manualStopZoneHandler")
 *             which cancelled ALL zones' auto-stop timers, not just the
 *             target zone; replaced with targeted manualRunZone flag clear.
 *         (6) abortCycleNow(): added unschedule("startNextZone") to close
 *             the race window where a queued startNextZone could fire
 *             after the abort (safe but untidy).
 *         (7) btnCloseAll: added updateAppLabel() so the Running badge
 *             clears immediately instead of waiting up to 30 min.
 *         Minor: fmtCoord() now shows °E/°W correctly for both hemispheres.
 *         Minor: buildStatusParagraph() uses safeGetAttr() for soil
 *             humidity — consistent defensive pattern throughout app.
 *         Minor: interZoneDelay comment clarifies runIn(1) minimum.
 *  2.1.0  Three features from academic literature review:
 *         (1) Cycle-and-soak: splits zone runtimes into short bursts
 *             with soak breaks when nozzle precip rate exceeds soil
 *             infiltration rate, preventing runoff. SOIL_INFILTRATION
 *             table added (USDA/ASABE values). Per-zone enable toggle.
 *             Global soak time setting (default 30 min per university
 *             extension consensus). New handlers soakAndContinue() and
 *             resumeAfterSoak() extend the non-blocking runIn chain.
 *             abortCycleNow() unschedules both new handlers.
 *             Total cycle window estimated and logged at cycle start;
 *             warns if window may exceed failsafe time.
 *         (2) Dynamic soil moisture threshold: evaluateSoilForZone()
 *             now accepts ETo + baseline and adjusts the soil threshold
 *             ±8pp based on ET demand ratio. Per-zone opt-in toggle.
 *         (3) Water penetration depth estimate: calcPenetrationDepth()
 *             shows estimated inches wetted per zone based on precip
 *             rate, runtime, and AWC. Displayed in zone page with root
 *             depth comparison and screwdriver-test guidance.
 *         UI: zone page explains cycle-and-soak in plain English;
 *             dynamic threshold shows current value vs base; penetration
 *             depth shown with color-coded root depth comparison.
 *         UI: evening watering disease warning (≥18:00 start time).
 *         DRIP_NOZZLE_TYPES constant — drip/bubbler nozzles exempt
 *             from cycle-and-soak (they cannot cause surface runoff).
 *  2.2.0  Bug & security audit of v2.1.0 new features — 4 bugs, 1 security,
 *         3 minor issues fixed:
 *         Bug 1: calcTotalCycleWindowMin() re-derived cycle params from
 *             settings instead of queue entries; now reads entry.doSplit and
 *             entry.maxCycleMin directly. Off-by-one delay also fixed.
 *         Bug 2: buildStatusParagraph() showed fixed threshold regardless
 *             of dynamicThreshEnabled; now calls dynamicSoilThreshold().
 *         Bug 3: zoneLineSummary() same fix — shows base%→dynamic% when
 *             dynamic threshold is enabled and differs from base.
 *         Bug 4: calcPenetrationDepth() UI now shows runoff caveat when
 *             cycle-and-soak is needed but disabled for a zone.
 *         Security: added sanitizeHtml() helper; applied to zone name and
 *             plant name in all HTML paragraph rendering paths.
 *         Minor 1: dynamicSoilThreshold() null guard changed from falsy
 *             '!eto' to explicit 'eto == null' — consistent with v2.0.0
 *             fToC/cToF fix.
 *         Minor 2: soakMin now embedded in queue entry at cycle-build time;
 *             soakAndContinue reads from data map for intra-cycle consistency.
 *         Minor 3: calcTotalCycleWindowMin off-by-one corrected (subtract
 *             one inter-zone delay — last zone's delay doesn't extend window).
 *  2.3.0  Dynamic per-zone soak time (Option B):
 *         SOIL_SOAK_MINS constant — recommended soak by soil type derived
 *         from hydraulic conductivity: Sand/Loamy Sand/Sandy Loam=15 min,
 *         Loam=20, Clay Loam=33, Silty Clay=50, Clay=60.
 *         calcSoakMin(z) — returns auto soak from soil type; respects
 *         per-zone zoneSoakOverride_z setting when user sets it.
 *         Queue entry soakMin now uses calcSoakMin(z) instead of global
 *         cycleSoakMins. calcTotalCycleWindowMin reads entry.soakMin.
 *         Zone page: per-zone soak override dropdown (auto/15-60 min);
 *         auto value shown in description from soil type.
 *         Advanced page: soak section rewritten to explain auto calculation
 *         and show per-zone auto/override status summary.
 *         def→typed: 34 method declarations converted from def to explicit
 *         return types (void/Object) per Hubitat best-practices for larger
 *         apps — improves JVM type inference and reduces dynamic dispatch.
 *  2.3.1  How This App Works section fully rewritten to cover v2.x features:
 *         dynamic soil threshold, cycle-and-soak with explicit explanation
 *         that soak holds current zone (not advance to next), penetration
 *         depth estimate + screwdriver test, dynamic soak time by soil,
 *         NOAA local-first architecture, ET baseline auto-refresh, and
 *         app label live status badges.
 *  2.3.2  Tonight's cycle window shown on main page Live Status:
 *         Start time → estimated end time → failsafe time in one
 *         color-coded card (green = fits, red = will exceed failsafe).
 *         When end > failsafe: inline fix suggestions so user never
 *         has to dig into logs for the window-exceeded warning.
 *  3.0.0  Major feature release — 45 items from comprehensive feature review.
 *         See detailed notes in the sections below (Safety, UX, Lifecycle,
 *         Hardware, Agronomic, Data, Engineering, Misc).
 *         All features are additive — no existing behavior changed.
 *         New atomicState keys are all prefixed to avoid collisions.
 *         All new UI sections are hideable with collapsed defaults.
 *  3.0.1  Post-feature integration audit — fixed defects where v3.0.0 logic
 *         was present but not fully wired:
 *         • soilTempEtFactor() was defined but never called — now applied in
 *           calcZoneRuntime() so cold-soil ET correction actually reduces runtime.
 *         • Solar radiation sanity result (solarNoonSanityOk) was computed but
 *           never read — calculateETo() now skips suspect solar data and falls
 *           back to Hargreaves when the check fails.
 *         • Distribution uniformity (zoneDuPct) was read in a no-op block — now
 *           lengthens runtime (runtime ÷ DU, clamped 50–100%) so dry spots get
 *           enough water; gallons calc correctly uses base output rate.
 *         • Added the missing UI inputs/sections for features whose logic existed
 *           but could not be configured: wind guard, leaf wetness guard, TOU guard,
 *           water leak sensor, master valve/pump relay, winterization & season,
 *           heat-stress syringing, soil-temp ET correction, solar sanity check,
 *           drought protection toggle, plus zoneAreaFt2, zoneDuPct, zoneNotes.
 *         • Syringe (cooling) runs no longer count as irrigation: excluded from
 *           water-usage totals, cycle history, completion notification, and the
 *           DST double-run guard; isSyringe flag propagated through the run chain.
 *         • Master valve now opens for syringe runs too (was only opening for
 *           the main cycle).
 *         • Removed duplicate btnRunNow/btnCloseAll handlers (dead code after the
 *           first return) and the unused _btnHandlerCompat stub.
 *         • Sunrise-relative start no longer double-schedules with the fixed-time
 *           cron — initialize() uses one or the other, not both.
 *         • Fixed falsy-zero traps: sunriseOffsetMins==0 (start exactly at sunrise)
 *           and the drought-skip counter.
 *         • Implemented the drought-protection override (constant + pruning existed
 *           but logic did not): waters after DROUGHT_SKIP_LIMIT consecutive
 *           sensor-failure skip days, resets on any valid reading, notifies user.
 *         • Wired the dedicated rain-gauge device override into the daily rain guard.
 *         • Startup-inspection mode now makes manualStartZone use a 60s burst.
 * *         Verified: brackets balanced, zero sandbox violations, all methods typed,
 *         all runIn/schedule/subscribe targets resolve, mid-cycle freeze abort
 *         works via guardCheckHandler→checkAllGuards.
 *  3.0.2  Critical field-reported fixes:
 *         • COMPILE: removed escaped double-quotes inside ${} interpolations
 *           (runaway-valve notification line 2529, NOAA QPF display line 1939) —
 *           these are syntax errors that prevented the app from saving.
 *         • COMPILE: fixed "as BigDecimal + x" operator-precedence error in the
 *           monthly/all-time gallons accumulation — needs parens around the cast.
 *         • ET BUG (major): calcPenmanMonteith treated the instantaneous solar-noon
 *           peak (W/m²) as if it were a daily-average, then multiplied by 0.0864 —
 *           wildly overstating net radiation and ETo (a cold 45°F rainy day computed
 *           0.54 in/day, flagged "hot dry day"). Now converts the noon peak to a
 *           daily-total Rs (MJ/m²/day) via the daylight-period half-sine average and
 *           caps it at the clear-sky maximum (0.75×Ra). Added calcDaylightHours()
 *           and noonPeakToDailyRs() helpers.
 *         • STALE FLAPPING: soil sensors falsely flapped stale↔fresh because the app
 *           only tracked the Ecowitt "humidity" attribute, which the driver emits
 *           only when the integer moisture % changes — on stable soil that gap can
 *           exceed the stale threshold. Now also subscribes to the raw "soilAD"
 *           attribute (transmits every ~60-90s) as a liveness signal via the new
 *           soilLivenessHandler, so freshness reflects sensor transmission, not
 *           percentage-change frequency. True RF loss is still caught by "orphaned".
 *  3.0.3  Renamed app to "Local Irrigation Manager V3" so it installs alongside the
 *         prior version (kept disabled) without colliding. Cosmetic: blank NOAA QPF
 *         now reads "not provided by NWS (PoP drives the skip)" instead of a dash
 *         with a stray inch mark — the named-period /forecast endpoint frequently
 *         omits quantitativePrecipitation, which is normal NWS behavior. Documented
 *         the planned next-version enhancement to source QPF from the gridpoints
 *         data (forecastGridData) that fetchNoaaDailyForecast already retrieves.
 *  3.0.4  Runtime fix: Calendar.format(String, TimeZone) does not exist (only Date
 *         has that signature), causing "No signature of method GregorianCalendar.
 *         format()" and an Unexpected Error when rendering the main page and the
 *         diagnostics monthly-history. Converted all Calendar receivers to Date via
 *         .getTime().format(...) — affected the Live Status cycle-window projection
 *         (startCal/endCal/fsCal) and the monthly water-usage history (cal2). These
 *         are UI render paths, so the symptom was a page-load error rather than a
 *         cycle failure. (A type error like this is invisible to static/bracket
 * *         checks and to the Groovy compiler — only executing the page surfaces it.)
 *  3.1.0  Two fixes/features:
 *         • ETo DISPLAY: the main-page "ETo" went blank whenever recent cycles were
 *           skipped, because ET was computed AFTER the guard check and only stored
 *           on a watering night. ET is now computed before guards and stored by
 *           calculateETo() itself on every call (cycle, diagnostics view, manual
 *           recalc), so the displayed value is always current. (The calculation was
 *           always correct — diagnostics showed 0.0068 — only the stored/displayed
 *           lastEto was stale.) Zero values still never overwrite the last good one.
 *         • NOAA QPF from gridpoints (the saved enhancement): quantitativePrecipitation
 *           is now read from forecastGridData (which fetchNoaaDailyForecast already
 *           retrieves) by summing entries whose validTime window overlaps the next
 *           14 hours. The named-period /forecast QPF remains a fallback. Added
 *           parseNoaaValidTime() to parse ISO-8601 "start/duration" intervals using
 *           sandbox-safe Date.parse (not java.time). QPF should now populate instead
 *           of showing "not provided by NWS".
 *  3.1.1  Attribution correction: the prior one-line "values also appear in WET-IT
 *         — acknowledged" understated the actual development history. WET-IT (Marc
 *         Hedish, Apache 2.0) was reviewed as a reference during development and the
 *         agronomic tables were verified against it. Replaced with a proper Apache
 *         2.0 §4 acknowledgment in the header (copyright notice, license link, no-
 *         warranty, and statement of changes) and an updated inline comment. The
 *         underlying Kc/MAD/root/AWC values remain standard public FAO-56 constants;
 *         no WET-IT source code was carried over. No functional code change.
 *  3.1.2  Pre-share cleanup (no functional code change):
 *         • Restored the full GNU GPLv3 notice (free-software / redistribute /
 *           WITHOUT ANY WARRANTY / where to get the license) — the header had been
 *           trimmed to a one-line link during earlier rewrites, which is short of
 *           the FSF's per-file notice requirement for a public GPLv3 release.
 *         • Authorship clarified: the US Copyright Office does not recognize AI as
 *           an author, and the prior "Co-author: Claude AI" line could undercut the
 *           human copyright claim. Reworded to name FrankTank34567 as the human
 *           author with a copyright line, and to credit Claude as an AI coding tool
 *           whose output was reviewed, tested, and adopted by the author.
 *         Verified clean: no hardcoded IPs/credentials/coordinates/emails, no debug
 *         cruft (println/console), brand names used only nominatively, all UI copy
 *         original. importUrl still references LocalIrrigationManager.groovy — update
 *         it if V3 is published to its own repo path.
 *  3.1.3  Bug & security audit fixes:
 *         • Falsy-zero trap in refreshEtNormalsFromObserved(): `if (!tMaxF || !tMinF)`
 *           treated a valid 0°F cached temperature as missing data (0 is falsy in
 *           Groovy) — a real possibility in a cold-climate winter, which would wrongly skip
 *           the bias-correction. Changed to explicit `== null` checks; the existing
 *           `tMaxF <= tMinF` test still rejects degenerate/equal values.
 *         • XSS consistency: one zone-name render (advanced-page soak summary) went
 *           into HTML without sanitizeHtml(); now sanitized like every other user-text
 *           render. Low real risk (self-entered, local admin context) but made
 *           consistent for public release.
 *         Audit verified clean: brackets balanced; zero sandbox violations; zero
 *         runtime-error-class issues (escaped-quote-in-interpolation, as-cast
 *         precedence, Calendar.format); all 21 runIn/schedule + 14 subscribe + 10
 *         button targets resolve; all 3 division sites guarded against zero; HTTP
 *         calls injection-safe (coords via String.format %.4f, ICAO regex-validated,
 *         no user-supplied URLs/SSRF, all timeouts set); event handlers that cast
 *         device values are try/catch-wrapped. No functional behavior change.
 *  3.1.4  Notification clarity (prompted by real notifications being indistinguishable
 *         from unrelated hub alerts like "The laundry is done!"):
 *         • Every notification now carries a "💧 Sprinklers:" prefix (added centrally
 *           in sendNotification(), so all 15 call sites are covered) — they stand out
 *           and self-identify as irrigation.
 *         • Reworded the hub-restart notification: it previously read "missed cycle
 *           will run in 60s", which falsely implied watering. The catch-up run routes
 *           through the full guard stack (rain/frost/freeze/etc.) and only waters if
 *           guards pass — confirmed correct against the actual local weather
 *           during the June 3–5 reboots (rain event + frost spread correctly skipped).
 *           Now reads "re-checking conditions (will only water if guards pass)".
 *         • Streamlined two messages that would read redundantly with the new prefix.
 *         Reboot recovery validated: a restart-triggered catch-up cannot water without
 *         passing every guard — no unguarded watering is possible on reboot.
 *  3.1.5  Frost guard logic fix: the dew-point-spread frost guard fired on a small
 *         temp−dewpoint spread ALONE, with no temperature condition. A small spread
 *         only means moisture is condensing (fog/dew) — it is NOT frost unless the
 *         air is also cold. As written, a warm humid/foggy night (e.g. 68°F, 1°F
 *         spread) would have falsely skipped for "frost risk". The guard now requires
 *         BOTH a spread ≤ the spread threshold AND temperature ≤ a new frost-ceiling
 *         setting (dpSpreadMaxTempF, default 38°F). Added the UI input and updated the
 *         live preview to show when a low spread would NOT skip because it's too warm.
 *         (Last night's real skip was legitimate — 42°F with fog — but the logic was
 *         only correct by coincidence of it being cold; this makes it correct by design.)
 *  3.1.6  Guard audit (same class of "fires/doesn't fire when it shouldn't" review as
 *         the frost fix). Three issues found and fixed:
 *         • NOAA QPF guard was DEAD: the threshold is stored as decimal inches
 *           ("0.25") but the code did `as int`, and "0.25" as int = 0, so the
 *           `qpfMax > 0` test was always false — the rain-amount predictive guard
 *           never fired. Now read as BigDecimal and compared directly.
 *         • TOU peak-hours guard broke across midnight: it used now≥start AND now<end,
 *           which can never be true for a window like 21:00–06:00. A 3 AM cycle inside
 *           an overnight peak window was never caught. Now detects the wrap (start≥end)
 *           and matches now≥start OR now<end.
 *         • Wind guard only checked sustained wind, ignoring gusts — but gusts cause
 *           most drift/damage and can be several times the sustained reading (WS90
 *           showed 22mph gust vs 4mph sustained). Now skips if sustained OR gust
 *           exceeds the threshold; added optional attrWindGust mapping and updated the
 *           UI preview. Reviewed all other guards (freeze, rain rate/daily/event,
 *           pressure storm, leaf wetness, NOAA PoP, season) — each is correct as a
 *           single- or multi-condition test; no other unsound logic found.
 *  3.1.7  Wind guard refined to treat gusts as transient: only SUSTAINED wind cancels
 *         a cycle. The v3.1.6 version skipped on sustained OR gust, meaning one freak
 *         gust at the cycle-start check could cancel the whole night — wrong, since
 *         gusts are momentary and don't indicate unwaterable conditions. Now the
 *         sustained-wind threshold (guardWindMph) is the cancel criterion; gusts are
 *         ignored unless an OPTIONAL, much-higher "severe gust" threshold (guardGustMph,
 *         off by default) is set for genuine windstorms. Wind remains a start-of-cycle
 *         gate (not mid-cycle) — pre-dawn sustained wind is stable, so a single start
 *         check captures it without risking mid-cycle aborts on brief sustained bumps.
 *  3.1.8  Fixed persistent false "Sensor stale: never seen" on a healthy sensor.
 *         Root cause: the GW2000 raises the "orphaned" flag transiently during its own
 *         housekeeping even while the sensor is reporting soilAD/humidity every ~2 min
 *         (confirmed in device event logs — fresh battery, data flowing). The orphan
 *         handler erased sensorLastSeen on every such blip, and recovery only re-seeded
 *         on the next humidity event (which can be far off on stable soil), so one false
 *         blip left the zone permanently "never seen". Fixes: (1) orphan events no
 *         longer erase last-seen — soilAD liveness + the normal age threshold decide
 *         staleness, so a false blip with live data is a no-op while a real outage still
 *         goes stale on schedule; (2) orphan-recovery re-seeds last-seen immediately;
 *         (3) init seeds from the current humidity value even if the orphaned flag is
 *         set, instead of forcing stale. A genuinely dead sensor still reports stale
 *         because soilAD actually stops. Added orphanSince_ pruning.
 *  3.1.9  Display clarity: the main-page "Last skip" line now shows WHEN the skip
 *         happened (from lastRunTimestamp, which every skip path already sets). A
 *         stale skip reason stored before a settings change or app update could look
 *         like a fresh skip — e.g. a "Frost risk" reason in the old single-condition
 *         format lingering after the v3.1.5 two-condition fix. Dating it makes clear
 *         it's historical. Display-only; no logic change.
 *  3.2.0  NEW FEATURE — Grass-seed germination mode (per-zone, phase-based).
 *         Each zone can be put in a seeding program with a start date and seed type.
 *         Three auto-advancing phases (Germinate→Establish→Transition) run frequent
 *         short cycles to keep the seedbed moist, tapering over weeks, then auto-return
 *         to normal ET watering. Seed cycles run on their own intraday schedule
 *         (06:30/13:00/17:00 — morning/midday/late-afternoon, never night, per
 *         extension guidance) separate from the main pre-dawn cycle. Phase day-counts
 *         come from seed-type presets (cool-season fast/slow, warm-season, overseeding)
 *         and are per-zone overridable. Per university-extension agronomy, seeding
 *         cycles skip ONLY for rain and hard freeze, and intentionally OVERRIDE the
 *         wind, frost-dewpoint-spread, and soil-moisture guards (wind/dry air dry the
 *         surface faster, so seed needs more water then; the soil sensor reads the
 *         deeper zone while the surface dries). Seed cycles do not set the DST guard,
 *         are not counted as the main daily irrigation, and use 🌱-prefixed
 *         notifications. Schedule is re-evaluated nightly in midnightReset as phases
 *         advance/complete. New: SEED_PRESETS/SEED_CYCLE_TIMES constants, seedDayNumber/
 *         seedPhase/seedCyclesToday/seedRuntimeMin/anyZoneSeeding helpers, seedGuardCheck,
 *         seedCycleRun + three slot wrappers, per-zone UI with live phase status, and a
 *         main-page seeding indicator.
 *  3.2.1  Relocation support + docs. Added a "Reset water-usage totals" button and a
 *         "Moving / New Install" reset on the Advanced page. The new-house reset clears
 *         accumulated history and location-specific cache (water totals, cycle history,
 *         ET-normals, NOAA forecast cache, sensor last-seen, pressure history, solar-noon
 *         capture, seeding progress) while KEEPING zone/guard configuration, then re-inits.
 *         Both resets use a two-tap confirm. (Location itself — lat/lon/elevation — lives
 *         on the hub and is read live, so nothing in the app needs changing for a move;
 *         the reset notes this.) Avoided atomicState key enumeration (not relied on
 *         elsewhere in the sandbox) by clearing monthly buckets via month-walk. Also
 *         rewrote the "How This App Works" section to cover grass-seed germination mode,
 *         the two-condition frost guard (cold AND low spread), sustained-only wind
 *         handling, the new TOU/leaf/wind guards in Step 1, and the relocation reset.
 *  3.2.2  Hardened sanitizeHtml() (external code-review item). Replaced the regex
 *         tag-stripper (replaceAll(/<[^>]*>/, "")) — which a malformed/nested tag like
 *         "<<b>b>" can survive — with HTML entity escaping (& < > " ' → entities).
 *         Escaping can't be defeated by nesting, so user-entered text (zone names,
 *         restriction note) is rendered inert regardless of content. Input is capped to
 *         64 chars BEFORE escaping so a multi-char entity is never split mid-sequence.
 *         Name/signature unchanged, so all six call sites are unaffected. Low real
 *         severity (local, admin-only, self-XSS at most) but a clean correctness win.
 *         Note: a zone name containing literal HTML now renders as text rather than
 *         applying the tags — the correct, safe behavior.
 *  3.3.0  NEW FEATURE — Midday moisture top-up (per-zone, demand-based, fully dynamic).
 *         Opt in per zone (zone page) to have that zone checked again in the afternoon
 *         and watered ONLY if it's actually dry. The trigger reuses evaluateSoilForZone()
 *         — the same live-reading-vs-dynamic-threshold logic the morning cycle uses, so
 *         there's no separate threshold to set. The amount is calculated, not fixed:
 *         minutes scale with the MEASURED deficit (how far below threshold the soil is)
 *         and are capped at madBandRefillMinutes() — the minutes to refill one full MAD
 *         band (root × AWC × MAD ÷ nozzle precip), which is the physical ceiling on a
 *         single watering and a per-zone dynamic cap, so a misreading sensor can't
 *         overwater. Applies the full normal guard stack (rain/freeze/etc.); does NOT
 *         set the DST guard or count as the main daily cycle, but DOES count toward water
 *         usage (it's real irrigation). Runs at a configurable afternoon time (Advanced
 *         page) only if a zone is opted in. New: madBandRefillMinutes/middayTopUpMinutes
 *         helpers, middayMoistureCheck executor, isMidday flag through the run chain,
 *         per-zone opt-in UI with a live "what it would do now" preview, and the global
 *         time setting. Built for fast-draining soils / thirsty crops (e.g. a sandy-loam
 *         vegetable garden) in summer heat.
 *  3.3.1  HOTFIX — restored the zoneKc() helper, which was accidentally deleted when
 *         the v3.3.0 midday helpers were inserted (the str_replace boundary consumed the
 *         one-line definition). zonePage calls zoneKc(z) at the "what the app calculated"
 *         preview, so the whole app failed to load with "No signature of method zoneKc()".
 *         No logic change — the definition is identical to what it always was; it just
 *         went missing. Added a build-time check that every called zone / seed / midday /
 *         calc helper resolves to a definition, to catch this edit-boundary hazard.
 *  3.3.2  Midday top-up now requires a FRESH soil reading. evaluateSoilForZone() tags
 *         its genuinely-current return path with fresh:true; the midday check (and its
 *         zone-page preview) skip any zone whose sensor is stale or missing. Closes the
 *         one edge where a midday top-up could act on a stale value (e.g. a dead sensor
 *         on behavior:last) and conflict with the night cycle reading the same sensor.
 *         No-sensor zones were already skipped; this also excludes stale ones. The night
 *         cycle is unchanged — it keeps its configurable stale-behavior, since the main
 *         daily watering is not optional the way a midday top-up is.
 *  3.3.3  Fixed the "Tonight's cycle window" projection using the wrong start time.
 *         When sunrise-relative start is on, the cycle actually begins (sunrise−offset)
 *         — e.g. 3:29 AM — but the window display read the STATIC startTime field (4:00
 *         AM), projecting an end ~31 min too late and sometimes falsely warning that the
 *         window crosses the failsafe. The projection now uses the same sunrise-relative
 *         start the cycle uses (and labels it "(sunrise−Nmin)") so the displayed window
 *         matches reality and the failsafe warning is accurate. Display-only; the actual
 *         cycle timing was always correct. (Note: the window estimate still reflects a
 *         genuinely tight schedule when many zones water on one night — that warning,
 *         when shown against the correct start time, is real and its advice stands:
 *         reduce runtimes/soak/zones or push the failsafe later.)
 *  3.4.0-dev-waterbalance  BRANCH off 3.3.3 — soil water-balance / dry-down tracking.
 *         NOT production-tested yet; 3.3.3 remains the stable build. Adds:
 *         • updateDryDownRate(): learns each zone's drying slope (%/day) from the soil
 *           sensor stream, using only falling segments (watering/rain UP-jumps reset the
 *           anchor and are excluded). Smoothed with a fast EMA (working rate) and a slow
 *           EMA (baseline "normal"). State: ddLastVal_/ddLastTime_/ddRatePerDay_/
 *           ddRateBaseline_ per zone.
 *         • hoursUntilThreshold(): projects time-to-threshold from current reading + rate.
 *         • Predict-ahead watering (opt-in/zone): if a zone is above threshold now but
 *           will cross it within the look-ahead horizon, water tonight so it doesn't go
 *           dry mid-day. Only on a FRESH reading; never overrides a guard skip.
 *         • Anomaly alert (opt-in/zone, notify-only): warns if a zone's dry-down rate is
 *           ≥ a configurable multiple of its baseline — likely a leak or broken head.
 *         • Per-zone UI (learned-rate readout + both toggles), new-house-reset pruning.
 *         • Dry-down DURATION correction (opt-in/zone): nudges the ET-based runtime by up
 *           to ±15% (both directions) when the sensor shows a zone drying faster/slower
 *           than its OWN baseline. Self-referential (recent vs baseline rate, same units)
 *           so it needs no soil-%→inch calibration; requires a settled baseline (≥1%/day)
 *           before acting; a gentle multiplier on ET, never a replacement. Soak time is
 *           deliberately NOT touched — soak tracks soil INFILTRATION (water in), which is
 *           orthogonal to dry-down (water out); coupling them would cause runoff. Per
 *           irrigation-engineering practice, dry-down informs frequency/duration while
 *           infiltration governs soak.
 *         - Rain credit (sensor-fallback only): when a zone sensor is stale/failed/removed,
 *           it waters on ET-and-schedule and is otherwise blind to sub-threshold rain. With
 *           rainCreditEnabled on, such a zone subtracts today's measured rainfall from its
 *           runtime (rain_in / nozzle precip x 60). Gated STRICTLY on the non-fresh path
 *           (sr.fresh != true) — a healthy sensor already reflects rain in its reading, so
 *           crediting there would double-count. Auto-engages only while the sensor is down
 *           and stands down when it recovers. Stacks with the existing manual-run credit.
 *           Clean unit match (inches vs inches), no soil-percent-to-inch calibration.
 *         - Removed the Configuration Backup (export/import) feature entirely. Export only
 *           ever captured ~14 of 141 settings (a stale hand-maintained list) and import was
 *           a non-functional stub — a backup that silently dropped ~90% of config and
 *           restored nothing is worse than none (false confidence before a move). Removed
 *           UI section, exportConfiguration(), both button handlers, and the state key.
 *         - "How This App Works" updated to document the full water-balance suite (dry-down
 *           tracking, predict-ahead, anomaly alert, duration fine-tuning, rain credit) and
 *           the soak-vs-dry-down distinction; added matching "what adapts automatically"
 *           lines. Cross-feature integration verified: dry-down learner excludes watering
 *           rises (RISE_RESET); predict-ahead and midday self-suppress via the live sensor;
 *           duration-correction (fresh-only) and rain-credit (stale-only) are mutually
 *           exclusive by sensor state. Water Balance zone section now auto-expands when any
 *           of its toggles are on (UI consistency with the midday/seeding sections).
 *         - Full settings + feature consistency audit. Verified all 96 inputs are read and
 *           all notify toggles fire (no dead settings). Found and fixed a real consistency
 *           gap: the TOU (time-of-use electricity) peak-hours skip lived only inline in the
 *           main cycle, so the discretionary midday top-up and heat-stress syringe ignored
 *           it and could run during peak rates. Extracted isInTouPeak() and applied it to
 *           both — now every discretionary watering path (main cycle, midday, syringe)
 *           honors TOU. Confirmed the remaining trigger differences are intentional: seeding
 *           uses rain+freeze-only guards and overrides active-day (germination must run
 *           daily); syringe skips rain/soil guards (it is cooling, not irrigation).
 *         - Notification granularity: every alert the app sends is now individually
 *           selectable. The single "cycle skipped" toggle had SEVEN different alerts riding
 *           on it (rain/wind/TOU/sensor skips, plus midday-skip, seed-skip, hub-restart),
 *           so the daily midday-skip couldn't be silenced without losing rain-skip alerts.
 *           Split into notifyOnSkip (main watering skips, default ON), notifyOnMiddaySkip
 *           (default OFF — fires daily), notifyOnSeedSkip (default OFF), notifyOnInfoSkip
 *           (default OFF). Also moved two miscategorized alerts off generic keys to their
 *           own feature flags: pre-freeze warning (was notifyOnAbort → preFreezeAlertEnabled)
 *           and drought-override (was notifyOnSkip → droughtProtectEnabled). sendNotification
 *           gained a defaultOn param so default-OFF toggles suppress correctly when unset
 *           (null) rather than only when explicitly false — fixed the same latent null bug
 *           on the existing notifyOnComplete (defaults OFF in UI, was sending regardless).
 *         - Cumulative moisture ledger ("bucket") for sensor-fallback zones. A running
 *           per-zone rain-minus-ETc balance in inches, updated once daily (date-guarded so
 *           a re-run cannot double-count), that accumulates deficit across days and sets the
 *           fallback runtime from it — the general, multi-day form of rain credit. Active
 *           ONLY on the non-fresh path (same gate as rain credit); replaces rain credit when
 *           both are on. Deficit capped at one MAD band (root x AWC x MAD) so a bad-data
 *           streak or outage cannot run away; surplus capped at 0. On watering, the ledger
 *           is offset by applied inches (partial runs partially refill; leftover carries
 *           forward). Mutually exclusive with the dry-down duration correction by sensor
 *           state (that requires a fresh sensor; the bucket requires a stale one), so no
 *           double-counting. bucketIn_z state pruned on new-house reset.
 *           Bucket reset on sensor recovery: when a zone's stale/failed sensor comes back
 *           fresh, its ledger is zeroed (the sensor is ground truth again; the modeled
 *           deficit is discarded rather than converted, since no soil-pct-to-inch
 *           calibration exists). Detected via a per-zone bucketWasFresh_z transition flag
 *           so a continuously-healthy zone is never re-zeroed daily. Runs after the daily
 *           drift so recovery wins on a recovery day. Flag pruned on new-house reset.
 *         - Audit fixes: bucket ledger credit moved from queue-commit to zone COMPLETION
 *           (closeZoneAndAdvance), threaded via a fallback tag on the queue entry through
 *           startNextZone / soakAndContinue / resumeAfterSoak. Commit-time crediting
 *           charged the ledger for water an abort or failsafe never delivered; an aborted
 *           zone now correctly keeps its deficit (cap bounds any re-water). Sanitized zone
 *           names in the bucket ledger display and the close-retry valve notification to
 *           match the app's escapeHtml convention.
 *         - Valve state verification ("check_back"). After each open/close the app re-reads
 *           the valve's reported state after a short settle delay (default 3s, configurable);
 *           on mismatch it retries the command once, then alerts via notifyOnValve. Closes a
 *           real reliability gap — a dropped open command meant a zone silently got no water.
 *           Open-side verification is new; the pre-existing 90s close check gained a matching
 *           retry. Opt-in (default on), needs a state-reporting valve.
 *         - Dry-down learner v2 (field-data fix). The v1 learner re-anchored on every
 *           30-minute sample; the WH51s flicker +/-1 point between readings, so pure
 *           quantization noise read as 1pt/30min (about 48%/day) and, with rises floored
 *           at zero, inflated the EMA to phantom rates (field: Front Yard learned about
 *           22%/day vs a true ~6%/day, causing premature predict-ahead waterings on
 *           2026-07-05). v2 rules: a sample must show a drop >= DD_MIN_DROP_PTS (2) to be
 *           learned; smaller moves return WITHOUT re-anchoring so the window extends until
 *           real drying accumulates; at DD_MAX_INTERVAL_MS (now 24h, was 6h) the window
 *           expires and learns whatever accumulated (1-point uncertainty over 24h is only
 *           1%/day); each sample is capped at DD_RATE_CAP (30%/day) so drainage transients
 *           cannot swamp the EMA. One-time migration on save (ddLearnerVer flag) clears the
 *           contaminated per-zone learner state so rates re-learn cleanly; flag pruned on
 *           new-house reset. Rise-reset (>3pt) behavior unchanged.
 *         - Dry-down learner v3: post-watering settle blackout. Field data (2026-07-09,
 *           v2 confirmed loaded) showed rates re-learning to 17-24%/day within two days
 *           on watered zones while the unwatered Side Yard learned an accurate 3.1%/day.
 *           Cause: the ~24h post-irrigation DRAINAGE cliff (sensor spikes to 72-83 then
 *           falls steeply to field capacity) produces real multi-point drops that pass
 *           the v2 deadband but reflect gravitational drainage, not plant use, creating a
 *           self-reinforcing loop: water > cliff > high learned rate > predict-ahead fires
 *           > water again. Fix: the rise-reset now also starts a DD_SETTLE_MS (24h)
 *           blackout during which nothing is learned and the anchor is refreshed every
 *           DD_MIN_INTERVAL, so the first post-settle window measures only the ET-driven
 *           slope. 24h chosen from the observed cliff flattening point and clay-loam
 *           drainage-to-field-capacity time. Migration bumped to v3 (clears rate state
 *           including new ddSettleUntil_z; key also pruned on new-house reset). Expected
 *           result: turf zones re-learn to their true 2-6%/day and predict-ahead returns
 *           to firing only within ~a day of a genuine threshold crossing.
 *         - Per-soil settle time (was PLANNED, now implemented). DD_SETTLE_MS replaced by
 *           SOIL_SETTLE_HRS map (Sand 8h, Loamy Sand 10h, Sandy Loam 12h, Loam 18h,
 *           Clay Loam 24h, Silty Clay 30h, Clay 36h) with DD_SETTLE_DEFAULT_HRS 24
 *           fallback and a per-zone override input zoneSettleHrs_z (0..72h, 0 disables)
 *           in the Water Balance section showing the effective value. zoneSettleMs(z)
 *           resolves override > soil map > default. Rise-reset and anchor-refresh
 *           mechanics unchanged; only the blackout duration varies. No migration needed:
 *           existing ddSettleUntil timestamps simply expire on their old schedule.
 *         - Adaptive syringe window. The single fixed syringeTime (one temperature
 *           snapshot per day, missing days whose heat peaks later) is replaced by a
 *           checking window: a 15-minute cron (syringeWindowCheck, distinct handler
 *           name) gates on syringeWindowStart/End (defaults 1 PM to 6 PM, same-day
 *           window; inverted config disables) and a once-per-day latch
 *           (lastSyringeDate, set ONLY at queue commit so a below-threshold or TOU
 *           skip leaves later checks free to fire). heatStressSyringeRun and its full
 *           guard stack are unchanged. UI shows window inputs, adaptive description,
 *           and a ran-today status; syringeTime input removed. How This App Works
 *           gained a syringing section and a settle-pause note.
 *         - Morning skip digest. Main-cycle skip notices (TOU, wind, guard-reason) are
 *           now queued (atomicState.pendingSkipNotices, capped at 10, timestamped,
 *           reboot-safe) instead of pushed at the 3-4 AM decision moment, and delivered
 *           as ONE summary when failsafeClose runs at failsafe time, after the watering
 *           window has fully closed. Rationale: a pre-dawn push reports a decision the
 *           user cannot act on and, when rain later moves the soil sensors, invites
 *           misreading spikes as the cycle having run anyway. Toggle skipMorningDigest
 *           (default ON) in Notifications restores immediate delivery when off. Safety
 *           alerts (abort, failsafe, valve anomaly, runaway, leak, battery, stale) are
 *           never deferred. Midday and seeding skip notices keep their own daytime
 *           delivery and toggles. Queue pruned on new-house reset.
 *         - Predictive rain guard: cache-validity bug fixed + amount gate + verification.
 *           BUG (found from 2026-07-18 field logs): the guard only consulted the forecast
 *           when cacheDate == today, but the Tonight forecast is fetched at 2 PM and the
 *           cycle it protects runs after midnight, so the guard silently disarmed for
 *           every pre-dawn cycle and has never fired at cycle time (PoP=82% sat cached
 *           while the 7/18 cycle ran unchecked). Fix: cache is valid fetched today OR
 *           fetched yesterday before local noon.
 *           Amount gate (user-requested): probability alone no longer skips. The PoP skip
 *           now also requires forecast QPF >= noaaQpfMinForSkip (default 0.15 in; unknown
 *           QPF falls back to probability alone). Summer thunderstorm forecasts are
 *           routinely high-probability/low-volume (7/17: 82% and 0.14 in forecast, 0.08 in
 *           fell); reviving the guard without this gate would have wrongly skipped a
 *           needed watering. The legacy standalone large-amount trigger is preserved and
 *           its "0.25" as int = 0 disable bug fixed (BigDecimal compare).
 *           Forecast-skip verification: when the predictive guard skips, a recheck is
 *           scheduled forecastVerifyLeadMin (default 120, range 30-240) minutes before
 *           failsafe. If measured rain since midnight (gauge-preferred) is below the skip
 *           floor, the forecast busted: notify and run the cycle late via a one-shot
 *           verifyBypassPredictive so the same stale forecast cannot re-skip it; all other
 *           guards and fresh soil evaluation still apply, and the failsafe still hard-caps
 *           the window. A completed-only lastCycleDone means the DST double-run guard
 *           never blocks the late run (verified: skips do not set it). State keys pruned
 *           on new-house reset. UI: amount-floor + verification inputs in the NOAA guard
 *           section; How This App Works gained the forecast-verification line.
 *         - Full feature-interaction audit (pairwise, all triggers x guards x learners x
 *           modifiers). Verified safe by code inspection: bucket daily update runs before
 *           the guard skip (date-guarded, late run cannot double-count); failsafe has an
 *           explicit force-close-and-alert branch if the verification late run overruns;
 *           the late run passes fresh soil evaluation, TOU, and the one-shot bypass;
 *           bucket recovery-zero uses a transition flag so the second same-day cycle pass
 *           cannot re-zero; seed cycles remain rain+freeze-only (a rain FORECAST must not
 *           starve germinating seed); the syringe never consults checkAllGuards. Noted as
 *           intentional: the revived predictive guard now also defers the MIDDAY top-up
 *           when a qualifying rain forecast exists (water-saving; no verification is
 *           scheduled from midday, that belongs to the main cycle only). Two defects
 *           found and fixed: (1) forecastSkipVerify removed its pending state before the
 *           cycleRunning check, so a seed/manual cycle running at the recheck moment
 *           silently discarded the verification; it now re-arms and retries every 15 min
 *           until failsafe is within 20 min, then abandons with a warning. (2) No per-zone
 *           syringe control existed, so drip zones were misted (useless for canopy cooling
 *           through drip, and the probe spike can trigger the learner's settle blackout on
 *           every hot day); added zoneSyringe_z (default ON, backward compatible) with
 *           guidance to disable on drip.
 *         - OVER-WATERING FIXES (field data 2026-07-23: 11,135 gal month-to-date vs 4,153
 *           for all of June, a 3.5x rise against roughly 1.3x the ET demand, with all
 *           three turf zones firing predict-ahead every night).
 *           (1) Predict-ahead minimum interval. Predict-ahead is discretionary early
 *           watering (the zone is still ABOVE threshold), so it now refuses to fire if the
 *           zone was watered within predictMinIntervalHrs: per-zone override
 *           zonePredictMinHrs_z, else PREDICT_MIN_HRS by plant type (turf 48h, vegetables
 *           18h, shrubs 72h, trees 96h, default 36h), 0 disables. This caps the
 *           self-feeding loop regardless of rate accuracy and cannot under-water, because
 *           a zone genuinely below threshold still waters on the normal path. Needs a
 *           millis timestamp, so zoneLastWaterMs_z is set at zone start with syringe
 *           excluded (cooling mist is not irrigation).
 *           (2) Adaptive settle extension. The fixed blackout assumed drainage finishes
 *           inside it; Front Yard spikes to 82% and needs well over a day to reach its
 *           ~46% floor, so the 24h clay-loam settle expired mid-cliff and the learner
 *           sampled 24.6%/day. If the first post-settle sample is >= DD_DRAINAGE_SUSPECT
 *           (12%/day) the blackout extends by a quarter-settle and the sample is discarded,
 *           up to DD_SETTLE_MAX_EXT (4) extensions, so clay loam can reach 48h total while
 *           a genuinely fast zone still learns. Counter ddSettleExt_z clears on rise-reset
 *           and on acceptance. Migration bumped to v4 to clear the contaminated rates.
 *           (3) NOAA cache-validity display bug. The diagnostics page labelled a cache the
 *           guard considers VALID as "stale" (7/22 fetch shown stale during the 7/23
 *           pre-dawn cycle), because the UI tested cacheDate == today while the guard used
 *           the wider window. Validity extracted to isNoaaCacheUsable() and used by both,
 *           so display and behavior cannot diverge; the label now distinguishes today,
 *           in-use-from-yesterday, and genuinely stale with the guard inactive.
 *           NOTE on the 2026-07-17 history entry (aborted, NOAA predictive 82%, then a
 *           completed cycle the same date): that pair is the pre-fix cache-window bug in
 *           the field. An evening cycle attempt saw a same-day cache and aborted with a
 *           notification; the 3:46 AM run then saw the same forecast as "yesterday" and
 *           ran. The validity fix makes both decisions consistent.
 *         - Queue triage for the failsafe window. The app computed the projected window,
 *           warned that it exceeded the time before failsafe, then queued every zone
 *           anyway, guaranteeing that whichever zone happened to be last in numeric order
 *           was truncated mid-sequence (field case 2026-07-24: 366 min window against 336
 *           min available, all four zones queued, Garden certain to be cut during its
 *           soak). triageQueueForWindow() now runs before the queue is committed: entries
 *           carry predict and margin (threshold minus soil), the queue is sorted driest
 *           first so discretionary predict-ahead zones naturally fall to the tail, and
 *           while the projected window still exceeds the available time the tail is shed
 *           one predict-ahead zone at a time. A zone genuinely below threshold is never
 *           dropped; if only dry zones remain and it still does not fit, the app says so
 *           and the driest-first order means the failsafe truncates the least needy zone
 *           instead of an arbitrary one. Failsafe-window math extracted to
 *           availableMinutesBeforeFailsafe() and shared with the warning.
 *         - Predict-ahead interval seeding. zoneLastWaterMs_z is a new key, so the
 *           minimum-interval guard failed open on the first cycle after upgrade, which is
 *           the night it matters most (2026-07-24: all four zones passed unchecked).
 *           A one-time idempotent seed (predictIntervalSeeded) sets any missing timestamp
 *           to now on load, making the guard active immediately. Conservative and free:
 *           predict-ahead only fires on zones already above threshold, and genuinely dry
 *           zones still water on the normal path. Key pruned on new-house reset.
 *         - App-title badge now reflects deliberate-stop states. updateAppLabel() never
 *           checked systemPaused or winterizedMode, so pausing the app (field: a water-main
 *           restriction) left the stale "Complete" badge showing. Added both at top
 *           priority above failsafe, because a purposeful stop is the most important thing
 *           to see at a glance and a failsafe badge is moot while stopped. Paused shows the
 *           auto-resume date when pauseUntil is set ("Paused until Aug 2"). Toggling pause
 *           saves settings, so updated() -> initialize() -> updateAppLabel() already
 *           refreshes it; no separate trigger needed. Winterized (blue) outranks paused
 *           (orange) outranks failsafe (red).
 *         - Manual "Run Cycle Now" reworked into "Run Due Zones Now". The button called
 *           irrigationCycle() unmodified, so a midday press hit the full schedule stack
 *           (already-ran-today, TOU peak, active-day, season, rain forecast) and usually
 *           just logged its way to a skip. It now sets a one-shot atomicState flag
 *           (manualCycleRequest; Hubitat schedules by handler NAME so a parameter cannot
 *           ride through runIn) which irrigationCycle reads and clears. Gates split by WHY
 *           they exist: schedule-based ones are bypassed for a manual run, physical ones
 *           are kept. Bypassed: DST already-ran-today, TOU peak, active-day, season, and
 *           the NOAA rain FORECAST (checkAllGuards gained a manual param, default false,
 *           so the midday and other callers are unchanged). Kept: pause, winterize, rain
 *           actually falling, wind, leaf wetness, freeze, frost, soil temperature, storm,
 *           plus per-zone soil moisture, ET-scaled runtimes and cycle-and-soak. A zone
 *           above threshold is still skipped by design: the button waters what needs
 *           water now rather than flooding wet zones. Button renamed and the Manual
 *           Controls section documents exactly what is and is not bypassed.
 *         - Timed pause never resumed (field bug, 2026-08-27). The pauseUntil date input
 *           shipped with the pause feature but nothing in the app ever READ it: the value
 *           appeared only in the input, two UI strings and the title badge. A pause set
 *           "until Aug 24" was still active on Aug 27 with three zones below threshold in
 *           94F heat, and would have persisted indefinitely. Added checkPauseAutoResume(),
 *           which compares whole local days and clears systemPaused (and the now-spent
 *           pauseUntil) once the resume date is reached, logs and notifies. Called from
 *           irrigationCycle before the pause gate, from midnightReset, and from
 *           initialize, so it fires whichever comes first and a reboot cannot skip it.
 *           Pause UI now states that watering resumes automatically that morning.
 *         TODO before merge to stable: field-test the learned rates over several days;
 *         confirm predict-ahead doesn't cause over-eager watering; verify EMA seeds sanely
 *         on a fresh install; then fold into a normal minor version.
 */

import groovy.transform.Field

// ── App identity ─────────────────────────────────────────────────────────────
@Field static final String APP_NAME    = "Local Irrigation Manager V3"
@Field static final String APP_VERSION = "3.4.0-dev-waterbalance"
@Field static final String APP_NS      = "FrankTank34567"
@Field static final String REPO_RAW    =
    "https://raw.githubusercontent.com/FrankTank34567/LocalIrrigationManager/main"

// ── Hard limits ───────────────────────────────────────────────────────────────
@Field static final int MAX_ZONES     = 12
@Field static final int RUNTIME_FLOOR = 5
@Field static final int RUNTIME_CEIL  = 120

// ── Log level constants ───────────────────────────────────────────────────────
@Field static final int LOG_OFF   = 0
@Field static final int LOG_ERROR = 1
@Field static final int LOG_WARN  = 2
@Field static final int LOG_INFO  = 3
@Field static final int LOG_DEBUG = 4
@Field static final int LOG_TRACE = 5

// ── Sensor failure behavior options ──────────────────────────────────────────
@Field static final String SENSOR_FAIL_SKIP  = "skip"
@Field static final String SENSOR_FAIL_WATER = "water"
@Field static final String SENSOR_FAIL_LAST  = "last"

// ── FAO-56 agronomic lookup tables ───────────────────────────────────────────
// Source: FAO Irrigation & Drainage Paper No. 56, Tables 11, 12, A.1 — standard
// published scientific constants (facts, not original authorship).
// Reviewed against and verified to match WET-IT by Marc Hedish (Apache 2.0);
// see the Attribution block in the file header for the Apache 2.0 notice.
@Field static final Map SOIL_AWC = [
    "Sand":0.05G,"Loamy Sand":0.07G,"Sandy Loam":0.10G,
    "Loam":0.17G,"Clay Loam":0.20G,"Silty Clay":0.18G,"Clay":0.21G
]
@Field static final Map PLANT_KC = [
    "Cool Season Turf":0.80G,"Warm Season Turf":0.65G,"Annuals":0.90G,
    "Groundcover":0.75G,"Shrubs":0.60G,"Trees":0.55G,
    "Native Low Water":0.35G,"Vegetables":0.90G
]

// Minimum hours between PREDICT-AHEAD waterings, by plant type. Predict-ahead is
// discretionary early watering, so this enforces deep-and-infrequent practice: cool and
// warm season turf want 2 to 3 day intervals, vegetables tolerate near-daily, woody
// plants far longer. A zone that genuinely falls below threshold is still watered by the
// normal path, so this can only prevent over-watering, never under-watering.
@Field static final Map PREDICT_MIN_HRS = [
    "Cool Season Turf":48,"Warm Season Turf":48,"Annuals":24,
    "Groundcover":36,"Shrubs":72,"Trees":96,
    "Native Low Water":120,"Vegetables":18
]
@Field static final Map PLANT_ROOT_IN = [
    "Cool Season Turf":6.0G,"Warm Season Turf":8.0G,"Annuals":10.0G,
    "Groundcover":8.0G,"Shrubs":18.0G,"Trees":24.0G,
    "Native Low Water":18.0G,"Vegetables":12.0G
]
@Field static final Map PLANT_MAD = [
    "Cool Season Turf":0.40G,"Warm Season Turf":0.50G,"Annuals":0.40G,
    "Groundcover":0.50G,"Shrubs":0.50G,"Trees":0.55G,
    "Native Low Water":0.60G,"Vegetables":0.35G
]
@Field static final Map NOZZLE_PRECIP = [
    "Spray":1.6G,"Rotor":0.5G,"MP Rotator":0.4G,
    "Drip Emitter":0.25G,"Drip Line":0.6G,"Bubbler":1.0G
]

// ── Grass-seed germination presets ───────────────────────────────────────────
// Phase-based watering for new seed. Durations from university-extension guidance
// (e.g. U. Maryland Extension, U. Wisconsin) and consistent commercial agronomy:
//   Phase 1 (Germinate): keep top ~1.5" constantly moist — short cycles 3×/day.
//   Phase 2 (Establish):  seedlings up — taper to 2×/day, slightly longer.
//   Phase 3 (Transition): deeper/less often 1×/day, then hand back to normal ET.
// Each entry: [p1Days, p2Days, p3Days, p1PerDay, p2PerDay, p3PerDay, p1Min, p2Min, p3Min]
// (PerDay = cycles per day in that phase; Min = minutes per cycle in that phase.)
@Field static final Map SEED_PRESETS = [
    "Cool-season fast (rye, tall fescue)": [10, 11, 7,  3, 2, 1,  8, 12, 18],
    "Cool-season slow (KY bluegrass)":     [21, 14, 10, 3, 2, 1,  8, 12, 18],
    "Warm-season (bermuda, zoysia)":       [21, 14, 14, 3, 2, 1,  8, 12, 18],
    "Overseeding existing lawn":           [7,  7,  7,  2, 2, 1,  6, 10, 15]
]
// Default germination cycle clock times (HH:mm local). Morning + midday + late
// afternoon; never midday-peak or night (fungus risk). Index 0..2 used per phase
// based on cycles-per-day (1→[morning], 2→[morning,late-pm], 3→all three).
@Field static final List SEED_CYCLE_TIMES = ["06:30", "13:00", "17:00"]

// ── Soil infiltration rates (in/hr) ──────────────────────────────────────────
// How fast each soil type absorbs water. When a nozzle's precipitation rate
// exceeds this, water runs off rather than soaking in. Used by cycle-and-soak
// to split runtimes into cycles the soil can actually absorb.
// Source: USDA Natural Resources Conservation Service soil science references;
// consistent with ASABE EP558.1 and university extension values.
@Field static final Map SOIL_INFILTRATION = [
    "Sand":2.0G,"Loamy Sand":1.5G,"Sandy Loam":1.0G,
    "Loam":0.5G,"Clay Loam":0.3G,"Silty Clay":0.2G,"Clay":0.15G
]

// ── Recommended soak times (minutes) by soil type ──────────────────────────
// Derived from soil hydraulic conductivity (Ksat): soakMin ≈ (1/infiltration) × 10,
// clamped 15–60. Sandy soils drain fast so short soaks suffice; clay needs long
// soaks to let the top layer clear before the next cycle burst.
// Formula: soakMin = clamp(round(10 / infiltrationRate), 15, 60)
// Source: consistent with RainMachine, Hunter Hydrawise, and USDA NRCS guidance.
@Field static final Map SOIL_SOAK_MINS = [
    "Sand":15,"Loamy Sand":15,"Sandy Loam":15,
    "Loam":20,"Clay Loam":33,"Silty Clay":50,"Clay":60
]

// ── Drip/bubbler nozzles that never cause surface runoff ─────────────────────
// These deliver water slowly at or near the root zone — cycle-and-soak is
// unnecessary and counterproductive for them.
@Field static final Set DRIP_NOZZLE_TYPES = ["Drip Emitter","Drip Line","Bubbler"] as Set

// ── Seasonal ET baseline — latitude band × month (no internet) ───────────────
// Derived from NOAA climatological normals + FAO-56 reference ET.
// Key = 5-degree latitude band floor. Values = [Jan..Dec] in/day.
@Field static final Map ET_BASELINE_BY_LAT = [
    25:[0.10,0.12,0.15,0.18,0.20,0.22,0.24,0.24,0.20,0.16,0.12,0.10],
    30:[0.09,0.11,0.14,0.17,0.20,0.22,0.23,0.22,0.19,0.14,0.10,0.08],
    35:[0.07,0.10,0.13,0.17,0.20,0.23,0.24,0.22,0.18,0.13,0.09,0.07],
    40:[0.06,0.08,0.12,0.16,0.20,0.23,0.24,0.22,0.17,0.12,0.07,0.05],
    45:[0.05,0.07,0.11,0.15,0.19,0.22,0.23,0.21,0.16,0.10,0.06,0.04],
    50:[0.04,0.06,0.10,0.14,0.18,0.21,0.22,0.20,0.14,0.09,0.05,0.03]
]

// ── ET normals vintage tracking ───────────────────────────────────────────────
// The embedded ET_BASELINE_BY_LAT table was derived from the 1991-2020 NOAA
// normals (released May 2021). The next official 30-year set covers 2001-2030
// and is expected ~2031. We trigger an automatic refresh when the current year
// is within ET_NORMALS_STALE_THRESHOLD_YEARS of the vintage end year —
// i.e., 2020 + 9 = 2029 — giving a year of headroom before expiry.
@Field static final int ET_NORMALS_VINTAGE_YEAR          = 2020  // end-year of embedded normals
@Field static final int ET_NORMALS_STALE_THRESHOLD_YEARS = 9     // refresh when year >= 2029

// ── Feature constants ─────────────────────────────────────────────────────────
@Field static final String MIN_HE_VERSION       = "2.3.9"
@Field static final int    RUNAWAY_MULTIPLIER   = 3     // abort if zone open > N × base runtime
@Field static final int    DROUGHT_SKIP_LIMIT   = 7     // days of sensor-fail skips before override
@Field static final int    MAX_CYCLE_HISTORY    = 14    // days of cycle history to retain

definition(
    name:           APP_NAME,
    namespace:      APP_NS,
    author:         "FrankTank34567",
    description:    "Local-first smart irrigation: FAO-56 Penman-Monteith ET from your " +
                    "Hubitat weather station. Per-zone soil gating with sensor failure " +
                    "tolerance. Manual valve monitoring. NOAA only if you opt in.",
    category:       "Green Living",
    iconUrl:        "",
    iconX2Url:      "",
    importUrl:      "${REPO_RAW}/LocalIrrigationManager.groovy",
    singleInstance: true
)

preferences {
    page(name:"mainPage")
    page(name:"weatherPage")
    page(name:"zonePage")
    page(name:"guardsPage")
    page(name:"advancedPage")
    page(name:"loggingPage")
    page(name:"diagnosticsPage")
}

// ═══════════════════════════════════════════════════════════════════════════════
// MAIN PAGE
// ═══════════════════════════════════════════════════════════════════════════════

Object mainPage() {
    dynamicPage(name:"mainPage", title:"", install:true, uninstall:true) {

        section() {
            paragraph "<div style='text-align:center'><h2>💧 ${APP_NAME} v${APP_VERSION}</h2>" +
                "<small>Local-first · FAO-56 Penman-Monteith · No cloud required</small></div>"
        }

        section("<b>🌐 Data Source Mode</b>") {
            boolean localOnly = isLocalOnlyMode()
            paragraph localOnly ?
                "<div style='background:#d4edda;padding:8px;border-left:4px solid green'>" +
                "<b>✅ LOCAL-ONLY MODE — No internet access</b><br>" +
                "<small>All ET, guards, and solar noon are computed from local devices and hub math. " +
                "No data leaves your network.</small></div>" :
                "<div style='background:#fff3cd;padding:8px;border-left:4px solid orange'>" +
                "<b>⚠️ INTERNET FALLBACK ALLOWED</b><br>" +
                "<small>App may contact api.weather.gov when local station data is unavailable. " +
                "Configure in Weather Settings to switch back to LOCAL-ONLY.</small></div>"
            href "weatherPage", title:"⚙️ Weather & Sensor Settings",
                 description:weatherPageSummary()
        }

        section("<b>📊 Live Status</b>") {
            paragraph buildStatusParagraph()
        }

        section("<b>⏰ Schedule</b>") {
            input "startTime","time",
                  title:"Daily irrigation start time",
                  description:"Recommended: 04:00 AM (low wind, pre-dawn)",
                  defaultValue:"2000-01-01T04:00:00.000-0700", required:true, submitOnChange:true
            // Evening watering disease warning — university extension research is unanimous
            // that watering after ~6 PM leaves grass blades wet overnight, significantly
            // increasing fungal disease risk (brown patch, dollar spot, pythium blight).
            if (settings.startTime) {
                try {
                    def stCal = Calendar.getInstance(location.timeZone)
                    stCal.setTime(toDateTime(settings.startTime))
                    int stHour = stCal.get(Calendar.HOUR_OF_DAY)
                    if (stHour >= 18 || stHour < 4) {
                        paragraph "<div style='background:#f8d7da;padding:8px;border-left:4px solid red'>" +
                                  "<b>⚠️ Evening/night start time — disease risk</b><br>" +
                                  "<small>Watering after 6 PM leaves grass blades wet overnight, which " +
                                  "significantly increases fungal disease risk (brown patch, dollar spot, " +
                                  "pythium blight). University extension programs consistently recommend " +
                                  "watering between midnight and 10 AM — ideally 2–6 AM — so leaves dry " +
                                  "by mid-morning. Consider shifting to an early-morning start.</small></div>"
                    }
                } catch (Exception e) { /* ignore parse errors */ }
            }
            input "activeDays","enum",
                  title:"Active days",
                  options:["Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"],
                  multiple:true, required:true,
                  defaultValue:["Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"]
            // Sunrise-relative start
            input "sunriseRelativeStart","bool",
                  title:"Use sunrise-relative start time (adjusts seasonally)",
                  description:"Start N minutes before sunrise instead of a fixed clock time",
                  defaultValue:false, submitOnChange:true
            if (settings.sunriseRelativeStart) {
                input "sunriseOffsetMins","number",
                      title:"Start this many minutes BEFORE sunrise",
                      defaultValue:60, range:"15..240", required:true
                try {
                    def sr = location.sunrise
                    if (sr) {
                        def relStart = new Date(sr.time - ((settings.sunriseOffsetMins ?: 60) * 60000L))
                        paragraph "<small>Today that would be: <b>${relStart.format('h:mm a', location.timeZone)}</b></small>"
                    }
                } catch (Exception ignored) { }
            }
            // Watering restriction documentation
            input "wateringRestrictionNote","text",
                  title:"Local watering restriction note (optional)",
                  description:"e.g. Odd/even day rule; no watering 10AM–6PM by city ordinance",
                  required:false
            if (settings.wateringRestrictionNote) {
                paragraph "<div style='background:#e8f4f8;padding:6px;border-left:3px solid steelblue'>" +
                    "📋 <b>Restriction:</b> ${sanitizeHtml(settings.wateringRestrictionNote as String)}</div>"
            }
        }

        section("<b>🌱 Zones</b>") {
            input "zoneCount","number",
                  title:"Number of zones (1–${MAX_ZONES})",
                  range:"1..${MAX_ZONES}", defaultValue:4, required:true, submitOnChange:true
            int zc = safeZoneCount()
            (1..zc).each { z ->
                def name    = settings["zoneName_${z}"]    ?: "Zone ${z}"
                def enabled = settings["zoneEnabled_${z}"]  != false
                def valve   = settings["zoneValve_${z}"]
                def sensor  = settings["zoneSoilSensor_${z}"]
                def icons   = "${valve ? '💧' : '⚠️'}${sensor ? '🌡️' : ''}${isSensorStale(z) ? '⚠️' : ''}"
                def flag    = enabled ? "" : " <font color='red'>[Disabled]</font>"
                def credit  = getManualCreditMinutes(z)
                def creditStr = credit > 0 ? " <font color='blue'>[−${credit}min tonight]</font>" : ""
                href "zonePage", params:[zone:z],
                     title:"${icons} ${name}${flag}${creditStr}",
                     description:zoneLineSummary(z),
                     state: valve ? "complete" : null
            }
        }

        section("<b>🛡️ Guards</b>") {
            href "guardsPage", title:"Weather Guard Thresholds",
                 description:guardsLineSummary(), state:"complete"
        }
        section("<b>⚙️ ET & Advanced</b>") {
            href "advancedPage", title:"ET Tuning, Manual Monitoring, Failsafe",
                 description:advancedLineSummary(), state:"complete"
        }
        section("<b>📝 Logging</b>") {
            href "loggingPage", title:"Log Level & Auto-Reset",
                 description:"Level: ${logLevelName(activeLogLevel())}"
        }
        section("<b>⏸ System Pause</b>", hideable:true, hidden:!settings.systemPaused) {
            input "systemPaused","bool", title:"Pause all irrigation cycles",
                  description:"Turn on to suspend the system (vacation, maintenance, etc.)",
                  defaultValue:false, submitOnChange:true
            if (settings.systemPaused) {
                input "pauseUntil","date", title:"Resume automatically on (optional)",
                      description:"Leave blank to pause indefinitely", required:false
                paragraph "<div style='background:#fff3cd;padding:8px;border-left:4px solid orange'>" +
                    "<b>⏸ System is paused</b> — no cycles will run" +
                    (settings.pauseUntil ? " until ${settings.pauseUntil}. Watering resumes automatically that morning" : "") + ".</div>"
            }
        }

        section("<b>🏠 Presence-Based Control</b>", hideable:true, hidden:true) {
            paragraph "Optionally reduce irrigation when nobody is home."
            input "awayPresence","capability.presenceSensor",
                  title:"Away presence sensor", required:false
            input "awayMode","mode", title:"Away mode name (alternative to presence sensor)",
                  required:false
            input "awayEtFactor","decimal",
                  title:"ET scale factor when away (0.0–1.0)",
                  description:"0.6 = 60% of normal ET — keeps lawn alive but conserves water",
                  defaultValue:0.6, range:"0.1..1.0", required:false
            boolean isAway = (settings.awayPresence && settings.awayPresence.currentPresence == "not present") ||
                             (settings.awayMode && location.mode == settings.awayMode)
            if (isAway) paragraph "<font color='orange'><b>⚠️ Away mode active</b> — ET scaled to ${settings.awayEtFactor ?: 0.6}×</font>"
        }

        section("<b>🔁 Mode-Triggered Manual Run</b>", hideable:true, hidden:true) {
            paragraph "Run a specific zone when a Hubitat mode activates."
            input "modeRunMode","mode",   title:"When mode becomes…", required:false
            input "modeRunZone","number", title:"Run zone number",    required:false, range:"1..${MAX_ZONES}"
            input "modeRunMins","number", title:"For how many minutes", required:false, range:"1..120"
        }

        section("<b>🔔 Notifications</b>", hideable:true, hidden:!settings.notifyDevice) {
            paragraph "Choose a notification device for push alerts on important events."
            input "notifyDevice","capability.notification",
                  title:"Notification device (phone, Pushover, etc.)",
                  required:false, multiple:true
            if (settings.notifyDevice) {
                input "notifyOnComplete","bool", title:"Notify: cycle complete",       defaultValue:false
                input "notifyOnSkip",    "bool", title:"Notify: main watering skipped (rain, freeze, wind, etc.)", defaultValue:true
                input "skipMorningDigest","bool",
                      title:"Deliver skip notices as one morning summary (at failsafe time) instead of at the moment of the overnight decision",
                      defaultValue:true
                input "notifyOnMiddaySkip","bool", title:"Notify: midday top-up skipped (can fire daily)", defaultValue:false
                input "notifyOnSeedSkip","bool", title:"Notify: seeding cycle skipped",  defaultValue:false
                input "notifyOnInfoSkip","bool", title:"Notify: informational (hub restart re-check)", defaultValue:false
                input "notifyOnAbort",   "bool", title:"Notify: cycle aborted",        defaultValue:true
                input "notifyOnFailsafe","bool", title:"Notify: failsafe triggered",   defaultValue:true
                input "notifyOnStale",   "bool", title:"Notify: station stale >1 hr",  defaultValue:true
                input "notifyOnValve",   "bool", title:"Notify: valve anomaly",        defaultValue:true
                input "notifyOnRunaway", "bool", title:"Notify: runaway valve detected",defaultValue:true
                input "notifyOnBattery", "bool", title:"Notify: station battery low",  defaultValue:true
                input "notifyOnAnomaly", "bool", title:"Notify: zone drying abnormally fast (possible leak/broken head)", defaultValue:true
            }
        }

        section("<b>🔧 Manual Controls</b>") {
            input "btnRunNow",   "button", title:"▶ Run Due Zones Now", width:3
            input "btnCloseAll", "button", title:"⏹ Close All Valves", width:3
            input "btnCalcEt",   "button", title:"🔄 Recalculate ET",  width:3
            paragraph "<small><b>Run Due Zones Now</b> waters immediately, ignoring schedule-only checks " +
                      "(active day, time-of-use hours, rain forecast, season, already-ran-today). It still " +
                      "respects soil moisture, so zones already above threshold are skipped, and it still uses " +
                      "each zone's normal ET-scaled runtime with cycle-and-soak. Pause, winterize, rain falling " +
                      "now, wind, freeze and frost still stop it.</small>"
            href "diagnosticsPage", title:"📋 Diagnostics",
                 description:"ET inputs · guards · sensor health · activity · history · calibration"
        }

        section("ℹ️ How This App Works", hideable:true, hidden:true) {
            paragraph "<b>The simple version:</b> Every night at 4 AM, the app asks three questions:<br>" +
                      "1. Should I even water tonight? (guards)<br>" +
                      "2. Which zones actually need water? (soil moisture + dynamic threshold)<br>" +
                      "3. How long should each zone run, and how should it run? (ET + cycle-and-soak)<br><br>" +

                      "<b>Step 1 — Guards (should I water tonight?)</b><br>" +
                      "Before anything opens, the app checks conditions that would make watering pointless or harmful: " +
                      "Is it currently raining? Has it already rained enough today, or has a single rain event dropped " +
                      "enough? Is it freezing? Is there a cold-soil condition preventing roots from absorbing water? " +
                      "Is a storm approaching (falling pressure)? Is it outside your irrigation season? Is it a frost " +
                      "risk — meaning the temperature is near freezing AND the dew-point spread is small? (Both are " +
                      "required: a small spread on a warm night is just fog or dew, not frost, so it won't skip.) " +
                      "Is sustained wind too high (which blows water off-target)? Is it within your time-of-use peak " +
                      "electricity hours, or are the leaves already wet? If any guard fails, the entire night is " +
                      "skipped and the reason is logged with its time. If NOAA internet access is enabled, the app " +
                      "also checks tonight's rain forecast probability and predicted precipitation amount.<br><br>" +
                      "<b>About wind:</b> only <b>sustained</b> wind cancels a cycle — momentary gusts are transient " +
                      "and don't waste a whole night's watering. (You can optionally set a separate severe-gust limit " +
                      "for true windstorms.) Wind is checked once at the start, since pre-dawn wind is stable.<br><br>" +

                      "<b>Step 2 — Soil moisture check (which zones need water?)</b><br>" +
                      "Each zone with a soil sensor is checked against its threshold. If the soil is above the " +
                      "threshold, the zone is skipped — it doesn't need water yet. If the soil is dry, the zone " +
                      "gets queued. Zones without sensors always run (if guards pass).<br><br>" +
                      "The threshold can be <b>dynamic</b>: on a hot high-ET day the app raises it slightly so " +
                      "irrigation triggers sooner (the soil will dry faster); on a cool low-ET day it lowers the " +
                      "threshold so the soil can dry more between runs. This is based on the ratio of tonight's " +
                      "calculated ET to the seasonal average for your latitude — the adjustment is ±8% max.<br><br>" +

                      "<b>Step 3 — How long to run each zone (ET calculation)</b><br>" +
                      "ET (evapotranspiration) is how much water the lawn lost due to heat, sun, wind, and humidity. " +
                      "The app calculates this using the FAO-56 Penman-Monteith equation (the same method used by " +
                      "professional irrigation systems) and adjusts tonight's runtime up or down from your base setting. " +
                      "Each zone also has a crop coefficient (Kc) based on plant type — turf, vegetables, trees etc. — " +
                      "which scales the ET to match that plant's actual water needs.<br><br>" +

                      "<b>Cycle-and-soak (preventing runoff)</b><br>" +
                      "If your nozzle applies water faster than your soil can absorb it, water runs off rather than " +
                      "soaking in. The app detects this automatically by comparing your nozzle's precipitation rate " +
                      "to your soil's infiltration rate. When splitting is needed, it breaks the total runtime into " +
                      "short bursts with soak breaks in between.<br><br>" +
                      "<b>Important: during the soak break, the current zone's valve closes and the app waits — " +
                      "it does NOT move to the next zone.</b> All of a zone's bursts and soaks complete before the " +
                      "next zone starts. This is intentional: the water needs time to redistribute downward in the " +
                      "current zone's soil before the next burst. Moving to another zone during the soak would split " +
                      "water pressure and leave both zones under-watered.<br><br>" +
                      "Soak time is calculated automatically from your soil type (sandy soils drain in 15 min; clay " +
                      "needs up to 60 min) and can be overridden per zone if your slope or compaction requires it. " +
                      "The zone page always shows the estimated total elapsed window so you can set your failsafe time " +
                      "accordingly — a 4-zone clay yard with spray heads could take 3+ hours.<br><br>" +

                      "<b>Penetration depth estimate</b><br>" +
                      "The zone page shows an estimated depth your water reaches in the soil based on your nozzle " +
                      "output, runtime, and soil's available water capacity. Turfgrass roots need 4–6 inches wetted " +
                      "for healthy deep growth — shallow watering (under 2 inches) encourages shallow roots that are " +
                      "more susceptible to drought and disease. Validate the estimate with the screwdriver test: push " +
                      "a long screwdriver into the soil 30 min after watering — it slides easily through wet soil and " +
                      "stops at dry soil. That stopping point is your actual depth.<br><br>" +

                      "<b>Solar noon capture</b><br>" +
                      "The best ET calculation uses solar radiation — how sunny it was today. Your weather station " +
                      "measures this now, but the app needs the peak reading from midday (not 4 AM when it's dark). " +
                      "The app captures that reading once a day around solar noon and stores it for tonight's cycle. " +
                      "Solar noon time is calculated from your hub's location and shifts with the seasons — no internet " +
                      "needed for this.<br><br>" +

                      "<b>Manual watering credit</b><br>" +
                      "If you manually run a zone, the app notices and deducts that time from tonight's planned runtime. " +
                      "A 15-minute manual run on a zone planned for 45 minutes results in a 30-minute auto run — not a " +
                      "skip. The zone is only skipped entirely if your manual run already covered the full planned amount. " +
                      "The credit window sets how long ago a manual run still counts.<br><br>" +

                      "<b>NOAA internet fallback</b><br>" +
                      "The app is designed to be fully local-first. All calculations use only your Hubitat hub and " +
                      "weather station by default. If NOAA fallback is enabled (off by default), the app contacts " +
                      "api.weather.gov once daily to fetch forecast temperature and rain probability — used only when " +
                      "local data is stale. The ET baseline table (1991-2020 NOAA normals by latitude) is embedded " +
                      "locally and will auto-refresh from NCEI if it approaches 10 years old and NOAA is enabled.<br><br>" +

                      "<b>🌱 Grass-seed germination mode</b><br>" +
                      "New grass seed needs the opposite of established turf: the surface kept constantly moist with " +
                      "short, frequent watering — not deep, infrequent soaks. Turn on seeding mode for any zone, pick a " +
                      "seed type, and set the start date. The app then runs a phase-based program: <b>Germinate</b> " +
                      "(3 short cycles a day to keep the seedbed moist), <b>Establish</b> (tapers to 2 cycles as " +
                      "seedlings come up), and <b>Transition</b> (1 deeper cycle, easing back toward normal). When the " +
                      "program finishes, the zone automatically returns to normal ET-based watering. Each zone tracks its " +
                      "own day count and phase, shown live on the zone page and the main status.<br><br>" +
                      "Seeding cycles run on their own schedule (morning, midday, late afternoon — never at night, to " +
                      "avoid fungus), separate from your main pre-dawn cycle. Per turfgrass-extension guidance, they " +
                      "skip <b>only</b> for rain and hard freeze, and deliberately water through wind, low dew-point " +
                      "spread, and the soil-moisture threshold: wind and dry air dry the <i>surface</i> faster (so seed " +
                      "needs more water then, not less), and your soil sensor reads the deeper layer while the surface " +
                      "where the seed lives dries out. Seeding cycles don't count as your main daily irrigation.<br><br>" +

                      "<b>🏠 Moving to a new house</b><br>" +
                      "The Advanced page has a reset for relocating. It clears accumulated history and location-specific " +
                      "cache (water-usage totals, cycle history, ET normals, NOAA forecast cache, sensor history, seeding " +
                      "progress) while <b>keeping</b> your zone and guard configuration. There's also a standalone " +
                      "'Reset water-usage totals' button if you just want to zero the gallon counters. Both use a " +
                      "two-tap confirm. After moving, update your hub's Location (latitude/longitude/elevation) — the app " +
                      "reads those live from the hub for ET and NOAA, so there's nothing location-specific to change in " +
                      "the app itself.<br><br>" +

                      "<b>🌡️ Heat-stress syringing</b><br>" +
                      "On extreme-heat afternoons the app can briefly mist the lawn to cool it by " +
                      "evaporation (a turf-management practice, not irrigation). It checks the live " +
                      "temperature every 15 minutes across an afternoon window (default 1 to 6 PM) and " +
                      "fires at most once per day, the first time the threshold (default 95°F) is crossed, " +
                      "so it catches the real heat peak whichever hour it lands. Because it is cooling " +
                      "rather than watering, it ignores soil moisture and rain guards, is excluded from " +
                      "water totals, and respects time-of-use peak hours.<br><br>" +

                      "<b>📉 Water balance &amp; dry-down tracking</b><br>" +
                      "The app learns how fast each zone actually dries out by watching its soil sensor over " +
                      "time — only the falling stretches, since watering and rain show up as sharp jumps that " +
                      "are excluded. That learned dry-down rate (shown per zone) powers three optional, " +
                      "per-zone features, all off by default and all needing a working sensor:<br>" +
                      "• <b>Predict-ahead watering</b> — if a zone is above threshold tonight but the learned " +
                      "rate says it will go dry before the next cycle, it waters tonight so the zone doesn't " +
                      "run dry mid-day. Only acts on a fresh reading and never overrides a guard skip. It also " +
                      "respects a minimum interval between early waterings (from plant type, turf about two " +
                      "days) so it cannot water the same zone night after night; a zone that truly falls below " +
                      "threshold is still watered by the normal path.<br>" +
                      "• <b>Anomaly alert</b> — if a zone suddenly dries far faster than its own baseline " +
                      "(a likely leak or broken head), you get a notification. It only warns; it never " +
                      "changes watering. Per-zone sensitivity, with delivery governed by the global " +
                      "'drying abnormally fast' toggle in Notifications.<br>" +
                      "• <b>Duration fine-tuning</b> — nudges a zone's ET-based runtime by up to ±15% when " +
                      "the sensor shows it drying faster or slower than its own baseline. A gentle correction " +
                      "on top of ET, never a replacement, and only after several days of settled data.<br>" +
                      "After any watering or rain, the learner pauses for a soil-dependent settle period " +
                      "(sandy soils hours, clays a day or more) so the steep post-irrigation drainage is " +
                      "never mistaken for plant water use.<br>" +
                      "Soak time is deliberately <i>not</i> tied to dry-down: soak prevents runoff and depends " +
                      "on how fast water soaks <i>in</i> (infiltration), which is unrelated to how fast the " +
                      "soil dries <i>out</i>. They're kept separate on purpose.<br><br>" +

                      "<b>🌧️ Rain credit &amp; moisture ledger (sensor-fallback only)</b><br>" +
                      "When a zone has a working sensor, rain is already accounted for — wet soil shows up in " +
                      "the reading. But when a zone's sensor is stale or failed, it waters on ET-and-schedule " +
                      "and is otherwise blind to rain that didn't trip the skip guard. Two optional tools cover " +
                      "that gap, both active only while the sensor is down (so a healthy zone is never " +
                      "double-counted): <b>rain credit</b> subtracts today's measured rainfall from the fallback " +
                      "runtime, and the fuller <b>moisture ledger (\"bucket\")</b> keeps a running per-zone balance " +
                      "of rain minus evapotranspiration, accumulating the deficit across days and setting the " +
                      "fallback runtime from it. The bucket replaces rain credit when both are on. Its deficit is " +
                      "capped at one full refill so a bad-data streak can't run away. When a zone's sensor recovers, " +
                      "the ledger is zeroed on the spot, since the sensor is the ground truth again and the modeled " +
                      "estimate is no longer needed.<br><br>" +

                      "<b>🔁 Valve state verification</b><br>" +
                      "After each valve open or close, the app re-reads the valve's reported state to confirm the " +
                      "command actually took — wireless commands are occasionally dropped. If a valve didn't open " +
                      "(a zone silently getting no water) or won't close, it retries once and then alerts. Needs a " +
                      "valve that reports its state.<br><br>" +

                      "<b>What adapts automatically</b><br>" +
                      "• Runtime scales every night from actual ET vs the seasonal average<br>" +
                      "• Soil threshold adjusts ±8% based on ET demand (when dynamic threshold is on)<br>" +
                      "• Cycle burst length and soak time calculated from your soil and nozzle type<br>" +
                      "• Seasonal baseline updates by month (hotter months expect higher ET)<br>" +
                      "• Solar noon shifts with the seasons using your hub's location<br>" +
                      "• Sensor staleness handled per-zone with configurable fallback behavior<br>" +
                      "• Seeding zones auto-advance through germination phases, then return to normal<br>" +
                      "• Learned dry-down rate can predict-ahead, flag leaks, and fine-tune duration (opt-in)<br>" +
                      "• Predict-ahead honors a minimum interval between early waterings, so an over-estimated dry-down rate cannot water a zone nightly<br>" +
                      "• If the night cannot finish before the failsafe, discretionary predict-ahead zones are deferred and the driest zones run first<br>" +
                      "• Stale-sensor zones can credit rain or track a cumulative moisture ledger (opt-in)<br>" +
                      "• Valve open/close commands are verified and retried if they don't take (opt-in)<br>" +
                      "• Forecast rain skips need a meaningful predicted amount, and are verified: if the promised rain never arrives, the cycle runs late that morning<br>" +
                      "• Only sustained wind cancels a cycle; transient gusts are ignored<br>" +
                      "• App label shows live status: 💧 Running, ✅ Complete, ⚠️ Aborted, 🔴 Failsafe"
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// WEATHER PAGE
// ═══════════════════════════════════════════════════════════════════════════════

Object weatherPage() {
    dynamicPage(name:"weatherPage", title:"🌦️ Weather & Sensor Settings",
                install:false, uninstall:false) {

        section("<b>🌐 Internet Access Policy</b>") {
            paragraph "<div style='background:#fff3cd;padding:10px;border-left:4px solid orange'>" +
                "<b>⚠️ This app is designed to be 100% local.</b><br>" +
                "All ET and guard calculations use only devices on your Hubitat hub by default. " +
                "Solar noon is calculated by Hubitat itself from your hub's location.<br><br>" +
                "If you want the app to fall back to NOAA NWS when local data is unavailable, " +
                "turn OFF the master switch below. NOAA NWS is free and requires no API key, " +
                "but it does require contacting <code>api.weather.gov</code> over the internet.</div>"

            input "localOnlyMode","bool",
                  title:"🔒 LOCAL-ONLY mode (blocks ALL internet access)",
                  description:"ON = no internet calls ever. OFF = allows NOAA fallback (configured below).",
                  defaultValue:true, submitOnChange:true

            if (!isLocalOnlyMode()) {
                paragraph "<div style='background:#f8d7da;padding:8px;border-left:4px solid red'>" +
                    "<b>🌐 INTERNET ACCESS IS ALLOWED</b><br>" +
                    "<small>The master local-only switch is OFF. Configure NOAA fallback below. " +
                    "If NOAA fallback is also disabled, the cycle skips rather than watering with " +
                    "incomplete data — internet calls only happen if you explicitly enable NOAA too.</small></div>"

                input "noaaFallbackEnabled","bool",
                      title:"Enable NOAA NWS fallback?",
                      description:"Contacts api.weather.gov when local station is stale or unavailable",
                      defaultValue:false, submitOnChange:true

                if (settings.noaaFallbackEnabled) {
                    paragraph "Requests identify as '${APP_NAME}/${APP_VERSION}'."
                    input "stationStaleMins","enum",
                          title:"Use NOAA when local station hasn't updated in",
                          options:["5":"5 min","10":"10 min","15":"15 min",
                                   "30":"30 min (recommended)","45":"45 min","60":"60 min"],
                          defaultValue:"30", required:true
                    input "btnTestNoaa","button", title:"🌐 Test NOAA Connection", width:3
                    input "btnFetchNoaa","button", title:"☁️ Fetch Forecast Now",  width:3
                    if (atomicState.noaaTestResult) {
                        def testTs = atomicState.noaaTestTimestamp
                        paragraph "${atomicState.noaaTestResult}${testTs ? '<br><small>Tested: '+testTs+'</small>' : ''}"
                    }

                    paragraph "<b>What NOAA adds vs your local station:</b><br>" +
                              "✅ <b>Predictive rain skip</b> — % chance of rain tonight (local station can't forecast)<br>" +
                              "✅ <b>Tmax/Tmin</b> — improves Hargreaves ET when solar data is unavailable<br>" +
                              "❌ <b>Not needed for Penman-Monteith ET</b> — your station provides solar, temp, humidity, wind<br>" +
                              "❌ <b>Not for real-time rain guards</b> — local station rainRate/rainDaily are better<br>" +
                              "NOAA forecast is fetched once daily at 2 PM and cached. No calls at 4 AM cycle time."
                }
            } else {
                paragraph "<small>NOAA settings are hidden because LOCAL-ONLY mode is enabled. " +
                          "Toggle the master switch off to access them.</small>"
            }
        }

        section("<b>📡 Local Weather Station</b>") {
            paragraph "Select any Hubitat weather station device. Compatible with " +
                      "Ecowitt, Ambient Weather, AcuRite, Davis, or any driver exposing " +
                      "standard weather attributes. Map the attributes below.<br><br>" +
                      "<b>📍 Where to place your weather station for best results:</b><br>" +
                      "• In <b>open air</b> — at least 10× the height of any nearby obstacle (tree, building, fence). " +
                      "A 10-foot fence needs the station 100 feet away for accurate wind readings.<br>" +
                      "• <b>Not on the roof</b> — roofs get much hotter than your yard and skew temperature readings<br>" +
                      "• <b>Not under eaves or trees</b> — the rain sensor needs open sky directly above it<br>" +
                      "• <b>At the edge of the yard</b> near a fence post — not the center where you mow<br>" +
                      "• <b>4–6 feet high</b> on a mounting pole is ideal for accurate wind measurement<br>" +
                      "• <b>If using Ecowitt WS90:</b> align the NORTH arrow on the connector tube to face north " +
                      "(use your phone's compass app to check)"
            input "weatherStation","capability.sensor",
                  title:"Weather station device",
                  description:"Tap to select", required:false, multiple:false, submitOnChange:true
        }

        if (settings.weatherStation) {
            def dev   = settings.weatherStation
            def attrs = getDeviceAttrs(dev)
            def opts  = (["(not available)"] + attrs.sort()).collectEntries { [it,it] }
            def scale = location.temperatureScale  // "F" or "C" — from hub Settings → Location

            section("<b>📋 Attribute Mapping</b>") {
                paragraph "Hub temperature scale: <b>${scale == 'C' ? 'Celsius (°C)' : 'Fahrenheit (°F)'}</b> " +
                          "(set in Hubitat → Settings → Location). " +
                          "The app reads temperature in your hub's scale and converts internally for ET calculations."
                [   [k:"attrTemp",    label:"Temperature (°F)",                    guess:["temperature","temp","outdoorTemp"]],
                    [k:"attrHumidity",label:"Outdoor humidity (%)",                guess:["humidity","outdoorHumidity","relativeHumidity"]],
                    [k:"attrDewPoint",label:"Dew point (°F) — improves PM ET",     guess:["dewPoint","dew","dewpoint"]],
                    [k:"attrWind",    label:"Wind speed (mph)",                    guess:["windSpeed","wind","windSpeedMph"]],
                    [k:"attrWindGust",label:"Wind gust (mph) — optional, for wind guard", guess:["windGust","gust","windGustMph"]],
                    [k:"attrSolar",   label:"Solar radiation (W/m²) — enables PM", guess:["solarRadiation","solar","illuminance"]],
                    [k:"attrRainRate",label:"Rain rate (in/hr)",                   guess:["rainRate","rainRateIn","precipRate"]],
                    [k:"attrRainDay", label:"Rain today (in)",                     guess:["rainDaily","rainDay","dailyRain"]],
                    [k:"attrRainEvt",  label:"Rain event total (in)",               guess:["rainEvent","eventRain","precipEvent"]],
                ].each { m ->
                    def auto = attrs.find { a -> m.guess.any { g -> a.equalsIgnoreCase(g) } }
                    def cur  = settings[m.k] ?: auto ?: "(not available)"
                    input m.k,"enum", title:m.label, options:opts, defaultValue:cur,
                          required:false, width:6, submitOnChange:true
                    if (settings[m.k] && settings[m.k] != "(not available)") {
                        def live = safeGetAttr(dev, settings[m.k])
                        if (live != null) paragraph "<small>→ Now: <b>${live}</b></small>"
                    }
                }
            }
        }

        section("<b>🌧️ Dedicated Rain Gauge</b>", hideable:true, hidden:!settings.rainGaugeDevice) {
            paragraph "If you have a dedicated tipping-bucket rain gauge (e.g. Ecowitt WH40) " +
                      "separate from your weather station, select it here. Preferred over " +
                      "the station's cumulative rain attribute for guard calculations."
            input "rainGaugeDevice","capability.sensor",
                  title:"Dedicated rain gauge device (optional)", required:false
            if (settings.rainGaugeDevice) {
                input "rainGaugeAttr","string",
                      title:"Daily total attribute name", defaultValue:"rainDaily", required:false
            }
        }

        section("<b>🌧️ Rain Credit (sensor-fallback only)</b>", hideable:true, hidden:true) {
            paragraph "When a zone has a working soil sensor, rain is already accounted for — the " +
                      "wet soil shows up in the reading. But when a zone's sensor is <b>stale, failed, " +
                      "or removed</b>, it waters on ET-and-schedule and is otherwise blind to rain that " +
                      "fell below the skip threshold.<br><br>" +
                      "With this on, a zone running in that sensor-fallback mode subtracts today's measured " +
                      "rainfall from its runtime (rain inches ÷ nozzle rate). It applies <b>only</b> while a " +
                      "zone's sensor is down, and stands down automatically when the sensor recovers — so it " +
                      "never double-counts rain on a healthy, sensored zone."
            input "rainCreditEnabled","bool",
                  title:"Credit measured rainfall on sensor-fallback zones",
                  defaultValue:false, submitOnChange:true
            paragraph "<hr style='border:none;border-top:1px solid #ddd;margin:8px 0'>" +
                      "<b>Cumulative moisture ledger (\"bucket\").</b> A more complete version of the above: " +
                      "instead of only crediting today's rain, it keeps a running per-zone balance of " +
                      "rain minus evapotranspiration, accumulating the deficit across days and setting the " +
                      "fallback runtime from it. Also only active while a zone's sensor is down. When on, it " +
                      "<b>replaces</b> the simple rain-credit above on fallback zones (they'd otherwise do the " +
                      "same job). Deficit is capped at one full refill so a bad-data streak can't runaway."
            input "bucketEnabled","bool",
                  title:"Use cumulative ET-minus-rain ledger on sensor-fallback zones",
                  defaultValue:false, submitOnChange:true
            if (settings.bucketEnabled) {
                int zc = safeZoneCount()
                String rows = ""
                (1..zc).each { z ->
                    if (settings["zoneValve_${z}"]) {
                        def b = atomicState["bucketIn_${z}"]
                        String bs = (b == null) ? "0.000" : "${b}"
                        rows += "${sanitizeHtml((settings["zoneName_${z}"] ?: 'Zone '+z) as String)}: <b>${bs} in</b> (negative = deficit)<br>"
                    }
                }
                paragraph "<small>Current ledger balances:<br>${rows}</small>"
            }
        }

        section("<b>🔋 Station Battery Monitoring</b>", hideable:true, hidden:!settings.stationBatteryDevice) {
            paragraph "Monitor your WS90 battery level. Alerts when low so you can replace " +
                      "the AA backup batteries before the station goes offline."
            input "stationBatteryDevice","capability.battery",
                  title:"Station battery device (e.g. WS90 via Ecowitt integration)", required:false
            if (settings.stationBatteryDevice) {
                input "stationBatteryThreshold","number",
                      title:"Alert when battery below (%)", defaultValue:20, range:"5..50"
                def batLevel = safeGetAttrBD(settings.stationBatteryDevice, "battery")
                if (batLevel != null) {
                    String bc = batLevel < (settings.stationBatteryThreshold ?: 20) ? "red" : "green"
                    paragraph "<small>Current battery: <font color='${bc}'><b>${batLevel.toInteger()}%</b></font></small>"
                }
            }
        }

        section("<b>📉 Barometric Pressure Sensor (optional — for storm detection)</b>") {
            paragraph "On Ecowitt systems, barometric pressure comes from the <b>GW2000 gateway's " +
                      "built-in indoor sensor</b> — a <em>separate</em> Hubitat device from the outdoor " +
                      "weather station. Select that device here to enable the local storm approach guard.<br><br>" +
                      "<b>Why the indoor location is fine:</b> Barometric pressure is the weight of the " +
                      "air above you — it's the same indoors and outdoors. Unlike temperature, pressure " +
                      "doesn't care whether the sensor is in your office or on your roof.<br><br>" +
                      "<b>Which attribute to use:</b> Look for <code>pressure</code> (relative/sea-level " +
                      "corrected — changes with weather). Avoid <code>pressureAbs</code> or " +
                      "<code>Pressure Abs</code> (absolute at your elevation — always reads low at altitude " +
                      "regardless of weather, not useful for trends)."

            input "pressureSensor","capability.sensor",
                  title:"Barometric pressure device (usually your GW2000 / gateway indoor sensor)",
                  description:"On Ecowitt: this is typically a separate device from your outdoor station",
                  required:false, multiple:false, submitOnChange:true

            if (settings.pressureSensor) {
                def pDev  = settings.pressureSensor
                def pAttrs = getDeviceAttrs(pDev)
                def pOpts  = (["(not available)"] + pAttrs.sort()).collectEntries { [it,it] }
                def pGuess = pAttrs.find { it in ["pressure","baromrelin","Pressure"] }

                input "pressureAttr","enum",
                      title:"Pressure attribute to use (choose relative/sea-level corrected, not absolute)",
                      options:pOpts,
                      defaultValue: pGuess ?: "(not available)",
                      required:false, width:6, submitOnChange:true

                if (settings.pressureAttr && settings.pressureAttr != "(not available)") {
                    def liveP = safeGetAttrBD(pDev, settings.pressureAttr)
                    def pTrend = calcPressureTrend()
                    if (liveP != null) paragraph "Current: <b>${liveP} inHg</b>${pTrend != null ? ' | 3-hr change: '+String.format('%+.3f', pTrend as double)+' inHg' : ' (gathering trend history...)'}"
                }
            } else {
                paragraph "<small>Without a pressure sensor, the storm approach guard is unavailable. " +
                          "All other guards work fine without this.</small>"
            }
        }

        section("<b>☀️ Solar Radiation & ET Method</b>") {
            paragraph "Solar noon is derived from the hub's sunrise and sunset, which Hubitat " +
                      "calculates from your hub's configured location — <b>no internet needed.</b><br>" +
                      "• <b>Penman-Monteith</b> when solar radiation is available (most accurate)<br>" +
                      "• <b>Hargreaves</b> (temperature-only) when solar is unavailable"
            def lat  = location.latitude  as BigDecimal
            def lon  = location.longitude as BigDecimal
            // Use location.sunrise/sunset directly — hub-provided Date properties
            def riseStr = location.sunrise ? location.sunrise.format("h:mm a", location.timeZone) : "unknown"
            def setStr  = location.sunset  ? location.sunset.format("h:mm a", location.timeZone)  : "unknown"
            int snM  = calcSolarNoonMinutes()
            paragraph "Hub: <b>${fmtCoord(lat, lon)}</b><br>" +
                      "Today's sunrise: <b>${riseStr}</b> | Sunset: <b>${setStr}</b><br>" +
                      "Solar noon (midpoint): <b>${String.format('%02d:%02d', snM.intdiv(60), snM%60)} local</b>"
            if (atomicState.solarNoonReading != null) {
                paragraph "Last captured: <b>${atomicState.solarNoonReading} W/m²</b> (${atomicState.solarNoonDate})"
            }
            if (atomicState.solarReadingError) {
                paragraph "<div style='background:#f8d7da;padding:8px;border-left:4px solid red'>" +
                    "<b>⛔ Solar reading error:</b> ${atomicState.solarReadingError}<br>" +
                    "Check the Solar Radiation attribute mapping below — you may have " +
                    "<code>illuminance</code> (lux) selected instead of <code>solarRadiation</code> (W/m²). " +
                    "These are different measurements. The Ecowitt driver exposes both; use <b>solarRadiation</b>.</div>"
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ZONE PAGE
// ═══════════════════════════════════════════════════════════════════════════════

Object zonePage(def params) {
    int z = safeParamZone(params?.zone)
    dynamicPage(name:"zonePage",
                title:"🌱 Zone ${z} — ${settings["zoneName_${z}"] ?: 'Zone '+z}",
                install:false, uninstall:false) {

        boolean manualRunning = (atomicState.manualRunZone == z)
        boolean cycleRunning  = atomicState.cycleRunning ?: false
        boolean activeZone    = (atomicState.activeZone  == z)

        section("<b>Identity</b>") {
            input "zoneName_${z}","text", title:"Zone name", defaultValue:"Zone ${z}", required:false
            input "zoneEnabled_${z}","bool", title:"Zone enabled?", defaultValue:true, submitOnChange:true
            input "zoneSyringe_${z}","bool",
                  title:"Include in heat-stress syringe",
                  description:"Turn OFF for drip zones: misting through drip does nothing for canopy cooling, and the concentrated water at the probe can pause this zone's moisture learner for hours after every syringe.",
                  defaultValue:true
            if ((settings["zoneEnabled_${z}"] == false)) paragraph "<font color='orange'>⚠️ Disabled — skipped in all cycles</font>"
            input "zoneNotes_${z}","text",
                  title:"Notes (optional)",
                  description:"e.g. soil type reason, nozzle brand, last service date — for your future reference",
                  required:false
        }

        section("<b>💧 Valve or Switch Device</b>") {
            paragraph "Supports valve (open/close) or switch (on/off) devices. " +
                      "The app also monitors this device for manual activations."
            input "zoneValve_${z}","capability.valve,capability.switch",
                  title:"Valve or switch device", required:false, multiple:false, submitOnChange:true
            if (settings["zoneValve_${z}"]) {
                def dev       = settings["zoneValve_${z}"]
                def hasValve  = dev.hasCommand("open")
                def hasSwitch = dev.hasCommand("on")
                paragraph "Type: <b>${hasValve ? 'valve (open/close)' : hasSwitch ? 'switch (on/off)' : '⚠️ unsupported'}</b>"
                def curState  = hasValve ? safeGetAttr(dev,"valve") : safeGetAttr(dev,"switch")
                if (curState) paragraph "State: <b>${curState}</b>"
                input "btnManualStart_${z}","button", title:"🟢 Test Open Zone ${z}", width:3,
                      disabled:(manualRunning || cycleRunning || (settings["zoneEnabled_${z}"] == false))
                input "btnManualStop_${z}","button", title:"🔴 Stop Zone ${z}", width:3, disabled:!manualRunning
                if (manualRunning) paragraph "<font color='green'><b>⏱ Zone ${z} running manually</b></font>"
                if (activeZone)   paragraph "<font color='blue'><b>💧 Zone ${z} in auto cycle</b></font>"
            }
        }

        section("<b>🌡️ Soil Moisture Sensor (WH51, WH52, or any compatible sensor)</b>") {
            paragraph "Select the soil sensor buried in this zone's irrigation area. Any device " +
                      "that reports soil moisture as a percentage (0–100%) works here. " +
                      "Leave blank to water on every enabled cycle without checking soil.<br><br>" +
                      "<b>📍 Where to place your soil sensor:</b><br>" +
                      "• In the <b>root zone</b> of what you're watering — about 4–6\" deep for lawns, " +
                      "deeper for shrubs and trees<br>" +
                      "• Near the <b>edge of the zone</b>, not the center (you mow the center!). " +
                      "A spot along a fence line or garden border is ideal<br>" +
                      "• <b>Away from sprinkler heads</b> — placing it directly under a head will show " +
                      "false 'wet' readings right after watering<br>" +
                      "• On <b>level ground</b>, not in a low spot where water pools (that reads falsely wet) " +
                      "or on a slope where water runs off (reads falsely dry)<br>" +
                      "• Away from heat sources: sidewalks, driveways, and south-facing walls heat the soil " +
                      "and cause it to dry faster than the rest of the zone<br>" +
                      "• Dig a small hole first rather than forcing the probe — the WH52 manual " +
                      "specifically warns against forcing the sensor into hard dry soil"

            input "zoneSoilSensor_${z}","capability.relativeHumidityMeasurement",
                  title:"Soil moisture sensor (optional)", required:false, multiple:false, submitOnChange:true

            if (settings["zoneSoilSensor_${z}"]) {
                def sensor  = settings["zoneSoilSensor_${z}"]
                def reading = safeGetAttr(sensor, "humidity")  // defensive — consistent with rest of app
                def attrs   = getDeviceAttrs(sensor)

                // Check if sensor is currently orphaned (Ecowitt lost RF contact)
                def orphanedState = safeGetAttr(sensor, "orphaned")
                boolean isOrphaned = (orphanedState == true || orphanedState?.toString() == "true")
                if (isOrphaned) {
                    paragraph "<div style='background:#f8d7da;padding:8px;border-left:4px solid red'>" +
                        "<b>⚠️ SENSOR ORPHANED — GW2000 has lost contact with this sensor.</b><br>" +
                        "The moisture reading shown (${reading != null ? reading+'%' : '?'}) is from before it went offline and is NOT current.<br>" +
                        "<b>What to do:</b> Check the sensor battery (Battery Org < 1.5V is low). " +
                        "If the sensor is far from the gateway, try moving it closer. " +
                        "Re-pair if battery replacement doesn't restore contact.</div>"
                }

                paragraph "Current reading: <b>${reading != null ? reading+'%' : 'No data'}</b>  ${isOrphaned ? '⚠️ STALE (orphaned)' : sensorHealthDetail(z)}"
                paragraph "<small>Available attributes on this device: ${attrs.sort().take(12).join(', ')}${attrs.size()>12 ? ' ...' : ''}</small>"

                input "zoneSoilThreshold_${z}","enum",
                      title:"Water when soil moisture drops to or below (%)",
                      description:"The app skips this zone if soil is wetter than this",
                      options:(10..75).step(5).collectEntries {
                          def label = it == 45 ? "${it}% — good starting point for most lawns" :
                                      it == 30 ? "${it}% — let soil get quite dry (drought-tolerant plants)" :
                                      it == 60 ? "${it}% — keep soil consistently moist (vegetables, annuals)" :
                                      "${it}%"
                          [it.toString(), label]
                      },
                      defaultValue:"45", required:true, submitOnChange:true

                if (reading != null) {
                    int thresh = (settings["zoneSoilThreshold_${z}"] ?: "45") as int
                    paragraph reading <= thresh ?
                        "<font color='orange'>⚠️ Currently DRY (${reading}% ≤ ${thresh}%) — would water tonight if guards pass</font>" :
                        "<font color='green'>✓ Currently moist enough (${reading}% > ${thresh}%) — would skip tonight</font>"
                }

                // WH52 3-in-1 soil temperature support
                // The Hubitat Ecowitt driver (lgkahn/sburke781) exposes soil temp as "temperature"
                // on WH52 child devices. Check if available and offer mapping.
                def hasSoilTemp = attrs.any { it in ["temperature","soilTemperature","soilTemp"] }
                if (hasSoilTemp || true) {  // always show — user may have WH52
                    section("WH52 3-in-1 Sensor — Optional Extra Data", hideable:true, hidden:!hasSoilTemp) {
                        paragraph "If you have an Ecowitt WH52 (the 3-in-1 sensor that adds soil temperature " +
                                  "and electrical conductivity), map those attributes here for better decisions.<br><br>" +
                                  "<b>Soil temperature</b> matters for irrigation: grass roots barely absorb water " +
                                  "when soil is below about 50°F — watering cold soil just wastes water and can " +
                                  "damage turf. The WH52 measures this directly at root level.<br><br>" +
                                  "<b>EC (electrical conductivity)</b> measures dissolved salts in the soil — " +
                                  "useful for knowing if you're over-fertilizing, but not used for watering decisions."

                        def soilTempOpts = (["(not available)"] + attrs.sort()).collectEntries { [it,it] }
                        input "zoneSoilTempAttr_${z}","enum",
                              title:"Soil temperature attribute (WH52 only — leave blank if WH51)",
                              description:"Check your WH52 device events page for the attribute name. Usually 'temperature'.",
                              options:soilTempOpts,
                              defaultValue: hasSoilTemp ? (attrs.find { it == "temperature" } ?: "(not available)") : "(not available)",
                              required:false, width:6, submitOnChange:true

                        if (settings["zoneSoilTempAttr_${z}"] && settings["zoneSoilTempAttr_${z}"] != "(not available)") {
                            def soilTempNow = safeGetAttrBD(sensor, settings["zoneSoilTempAttr_${z}"])
                            if (soilTempNow != null) {
                                def tempF = stationTempToF(soilTempNow)
                                paragraph "Current soil temperature: <b>${tempF}°F</b>"
                            }

                            input "coldSoilGuardEnabled_${z}","bool",
                                  title:"Skip watering if soil is too cold (roots can't absorb water)?",
                                  description:"Recommended ON for lawns in climates with cold springs",
                                  defaultValue:true, submitOnChange:true

                            if (settings["coldSoilGuardEnabled_${z}"] != false) {
                                input "coldSoilTempF_${z}","enum",
                                      title:"Cold soil threshold — skip if soil is at or below (°F)",
                                      description:"Below 50°F grass roots slow down significantly; below 40°F they stop",
                                      options:["35":"35°F (frozen ground)","40":"40°F","45":"45°F",
                                               "50":"50°F (recommended for lawns) ←","55":"55°F","60":"60°F"],
                                      defaultValue:"50", required:true
                            }
                        }

                        def ecAttrOpts = (["(not available)"] + attrs.sort()).collectEntries { [it,it] }
                        def ecGuess    = attrs.find { it in ["conductivity","soilEC","soilConductivity","ec"] }
                        input "zoneSoilEcAttr_${z}","enum",
                              title:"Soil EC (electrical conductivity) attribute — informational only",
                              description:"Displayed in diagnostics. Not used for watering decisions.",
                              options:ecAttrOpts,
                              defaultValue: ecGuess ?: "(not available)",
                              required:false, width:6
                    }
                }
            }
        }

        if (settings["zoneSoilSensor_${z}"]) {
            section("<b>🔧 Sensor Failure Tolerance</b>") {
                paragraph "Inexpensive soil sensors can fail silently — reporting a " +
                          "stale frozen value or going offline entirely (Ecowitt WH51 and " +
                          "similar). Configure how the app responds when the sensor is suspected " +
                          "to be unreliable."
                input "sensorMaxAgeMins_${z}","enum",
                      title:"Consider sensor stale if not updated in",
                      options:["15":"15 min","30":"30 min","60":"1 hr (recommended)",
                               "90":"90 min","120":"2 hr","240":"4 hr"],
                      defaultValue:"60", required:true
                input "sensorFailBehavior_${z}","enum",
                      title:"When sensor is stale or has no data",
                      options:[
                          (SENSOR_FAIL_SKIP) :"⏭ Skip zone (safest — don't water when unsure)",
                          (SENSOR_FAIL_WATER):"💧 Water anyway (use base runtime, ignore threshold)",
                          (SENSOR_FAIL_LAST) :"📋 Use last known value (trust prior reading)"
                      ],
                      defaultValue:SENSOR_FAIL_SKIP, required:true
                def lastSeen = atomicState["sensorLastSeen_${z}"]
                def lastVal  = atomicState["sensorLastValue_${z}"]
                if (lastSeen) {
                    int ageMins = ((now() - (lastSeen as Long)) / 60000).toInteger()
                    paragraph "Last valid reading: <b>${lastVal}%</b> — ${ageMins} min ago"
                } else {
                    paragraph "<i>No reading recorded yet this session.</i>"
                }
                if ((settings["sensorFailBehavior_${z}"] ?: SENSOR_FAIL_SKIP) == SENSOR_FAIL_SKIP) {
                    int curSkips = (atomicState["consecutiveSensorSkips_${z}"] ?: 0) as int
                    paragraph "<small>🌵 <b>Drought protection:</b> if this sensor fails for " +
                              "${DROUGHT_SKIP_LIMIT} days straight, the app waters anyway to keep the " +
                              "lawn alive (configurable on the Guards page)." +
                              (curSkips > 0 ? " <font color='orange'>Currently ${curSkips}/${DROUGHT_SKIP_LIMIT} skip days.</font>" : "") +
                              "</small>"
                }
            }
        }

        section("<b>🌿 What Are You Watering? (Plant & Soil Type)</b>") {
            paragraph "These settings tell the app how thirsty your plants are and how well your " +
                      "soil holds water. Together they determine how long to run each zone.<br>" +
                      "Don't stress over being perfect — a close match is fine."

            input "zonePlant_${z}","enum",
                  title:"What type of plant or grass is here?",
                  options:[
                      "Cool Season Turf":"Lawn — Cool Season Grass (Bluegrass, Fescue, Ryegrass — grows best in spring/fall)",
                      "Warm Season Turf":"Lawn — Warm Season Grass (Bermuda, Zoysia, St. Augustine — grows best in summer)",
                      "Annuals":         "Flowers & Annuals (impatiens, petunias, vegetables that replant each year)",
                      "Groundcover":     "Ground Cover (low plants like ivy, pachysandra, clover)",
                      "Shrubs":          "Shrubs & Bushes (rose bushes, boxwood, lilac)",
                      "Trees":           "Trees (any established tree)",
                      "Native Low Water":"Drought-Tolerant / Native Plants (sagebrush, native grasses, xeriscape)",
                      "Vegetables":      "Vegetable Garden (tomatoes, peppers, cucumbers, etc.)"
                  ],
                  defaultValue:"Cool Season Turf", required:true, width:12, submitOnChange:true

            input "zoneSoil_${z}","enum",
                  title:"What type of soil do you have?",
                  description:"Not sure? Grab a handful when damp and squeeze it.",
                  options:[
                      "Sand":        "Sand — falls apart immediately, feels gritty (drains very fast, needs frequent watering)",
                      "Loamy Sand":  "Loamy Sand — mostly gritty, barely holds shape (drains fast)",
                      "Sandy Loam":  "Sandy Loam — gritty but holds a loose shape when squeezed (good drainage)",
                      "Loam":        "Loam — forms a smooth ball that crumbles easily (ideal garden soil, most lawns)",
                      "Clay Loam":   "Clay Loam — forms a firm ball, feels sticky when wet (drains slowly)",
                      "Silty Clay":  "Silty Clay — very smooth when wet, holds shape well (holds water a long time)",
                      "Clay":        "Clay — very hard when dry, slippery when wet, forms a firm ball (drains very slowly)"
                  ],
                  defaultValue:"Loam", required:true, width:12, submitOnChange:true

            paragraph "<small>Not sure which soil you have? The USDA has a helpful guide: " +
                      "<a href='https://www.nrcs.usda.gov/conservation-basics/natural-resource-concerns/soil/soil-health' target='_blank'>nrcs.usda.gov — Soil Basics</a>. " +
                      "The 'squeeze test': grab a damp handful. Sand falls apart. Clay holds a slick ball. " +
                      "Loam crumbles after squeezing. Most yards are loam or sandy loam.</small>"

            input "zoneNozzle_${z}","enum",
                  title:"What type of sprinkler head or watering method is in this zone?",
                  options:[
                      "Spray":         "Spray Heads — fixed fan spray pattern, fine mist (typical for small areas)",
                      "Rotor":         "Rotor Heads — rotating arc, single stream sweeping back and forth (most common for lawns)",
                      "MP Rotator":    "MP Rotator — slow rotating streams, very water-efficient (Hunter MP, Rain Bird R-VAN)",
                      "Drip Emitter":  "Drip Emitters — slow drip directly at each plant's roots (great for shrubs, gardens)",
                      "Drip Line":     "Drip Line — hose with many small holes along it (runs along garden rows)",
                      "Bubbler":       "Bubblers — slow flood at the base of a tree or shrub"
                  ],
                  defaultValue:"Rotor", required:true, width:12, submitOnChange:true

            def soil  = settings["zoneSoil_${z}"] ?: "Loam"
            BigDecimal kc=zoneKc(z), root=zoneRoot(z), mad=zoneMad(z), awc=SOIL_AWC[soil]?:0.17G
            BigDecimal taw=(root*awc).setScale(3,BigDecimal.ROUND_HALF_UP)
            BigDecimal madIn=(taw*mad).setScale(3,BigDecimal.ROUND_HALF_UP)
            BigDecimal precip=zonePrecip(z)
            paragraph "<small><b>What the app calculated from your selections:</b><br>" +
                      "Water use factor (Kc): <b>${kc}</b> — how thirsty this plant type is relative to a reference lawn<br>" +
                      "Root depth: <b>${root}\"</b> — how deep roots go (determines how much water the soil can store)<br>" +
                      "Watering trigger: <b>${(mad*100).toInteger()}% depletion</b> — app waters when soil uses this fraction of stored water<br>" +
                      "Total water storage in root zone: <b>${taw}\"</b> (triggers watering at <b>${madIn}\"</b> used)<br>" +
                      "Sprinkler output: <b>${precip} in/hr</b></small>"

            input "zoneAreaFt2_${z}","number",
                  title:"Zone area (square feet) — for water usage estimates",
                  description:"Approximate; used only to estimate gallons. Default 1000.",
                  defaultValue:1000, required:false, width:6
            input "zoneDuPct_${z}","number",
                  title:"Distribution uniformity (%) — optional",
                  description:"How evenly heads cover the zone. Leave blank if unsure. " +
                              "Lower values lengthen runtime so dry spots get enough (50–100%).",
                  required:false, width:6
        }

        section("<b>⏱️ Base Runtime</b>") {
            input "zoneBaseRuntime_${z}","enum",
                  title:"Base runtime (minutes) — scaled by nightly ET",
                  options:(5..90).step(5).collectEntries { [it.toString(), "${it} min"] },
                  defaultValue:"45", required:true
            def lastMin = atomicState["lastRuntime_${z}"]
            def lastEto = atomicState.lastEto
            if (lastMin != null) paragraph "Last calculated: <b>${lastMin} min</b>${lastEto ? ' (ETo='+String.format('%.3f',lastEto as double)+')' : ''}"
        }

        section("<b>💧 Soil Moisture Threshold</b>") {
            input "dynamicThreshEnabled_${z}","bool",
                  title:"Dynamic threshold (adjust based on today's ET demand)?",
                  description:"On = threshold rises on hot days (water sooner), lowers on cool days. Off = always use fixed value below.",
                  defaultValue:false, submitOnChange:true
            def fixedT = (settings["zoneSoilThreshold_${z}"] ?: "45") as int
            if (settings["dynamicThreshEnabled_${z}"]) {
                def eto2  = atomicState.lastEto as BigDecimal
                def base2 = atomicState.lastEtInputs?.baseline as BigDecimal
                int dynT  = (eto2 && base2) ? dynamicSoilThreshold(z, eto2 ?: 0G, base2 ?: 0G) : fixedT
                paragraph "<small>Base threshold: <b>${fixedT}%</b> | Tonight's dynamic threshold: <b>${dynT}%</b><br>" +
                          "Range: ±8% max from base. Requires ETo to be calculated first (tap Recalculate ET).<br>" +
                          "<b>Why this helps:</b> On a 95°F day the soil dries 2× faster than average — " +
                          "waiting until 45% means the grass was already stressed. The dynamic threshold " +
                          "raises it to ~53% so irrigation triggers earlier. On a 60°F overcast day it " +
                          "drops to ~37% so the system doesn't water unnecessarily.</small>"
            }
        }

        section("<b>🔄 Cycle and Soak</b>") {
            // Calculate whether this zone actually needs splitting
            Integer maxC2  = calcMaxCycleMin(z)
            BigDecimal inf = SOIL_INFILTRATION[settings["zoneSoil_${z}"] ?: "Loam"] ?: 0.5G
            BigDecimal pr2 = zonePrecip(z)
            boolean needsSplit = (maxC2 != null)

            if (!needsSplit) {
                paragraph "<div style='background:#d4edda;padding:8px;border-left:4px solid green'>" +
                          "<b>✅ No splitting needed for this zone.</b><br>" +
                          "<small>Your soil (${settings["zoneSoil_${z}"] ?: "Loam"}) absorbs water at " +
                          "${inf} in/hr, which is faster than or equal to your nozzle's output (${pr2} in/hr). " +
                          "Water soaks in as fast as it's applied — runoff is not a concern.</small></div>"
            } else {
                paragraph "<div style='background:#fff3cd;padding:8px;border-left:4px solid orange'>" +
                          "<b>⚠️ This zone may benefit from cycle-and-soak.</b><br>" +
                          "<small>Your nozzle applies water at <b>${pr2} in/hr</b> but your " +
                          "${settings["zoneSoil_${z}"] ?: "Loam"} soil only absorbs <b>${inf} in/hr</b>. " +
                          "Water applied faster than the soil can absorb it runs off rather than soaking in — " +
                          "wasting water and leaving the root zone drier than you think.<br><br>" +
                          "<b>How cycle-and-soak fixes this:</b> Instead of running the zone for one long burst, " +
                          "the app splits the runtime into ${maxC2}-minute cycles with a soak break in between. " +
                          "During the soak the water already in the soil redistributes downward, making room for " +
                          "the next cycle. The same total water is applied — it just goes where it's supposed to " +
                          "instead of down the street.</small></div>"

                input "cycleSoakEnabled_${z}","bool",
                      title:"Enable cycle-and-soak for this zone?",
                      description:"Splits runtime into ${maxC2}-min cycles with soak breaks — prevents runoff on this soil/nozzle combination",
                      defaultValue:true, submitOnChange:true

                if (settings["cycleSoakEnabled_${z}"] != false) {
                    // Per-zone soak override — auto is calculated from soil type
                    int autoSoak2 = calcSoakMin(z)
                    input "zoneSoakOverride_${z}","enum",
                          title:"Soak time between bursts",
                          description:"Auto = ${autoSoak2} min (calculated from ${settings["zoneSoil_${z}"] ?: "Loam"} soil drainage rate)",
                          options:["auto":"⚙️ Auto (${autoSoak2} min — from soil type)",
                                   "15":"15 min","20":"20 min","25":"25 min","30":"30 min",
                                   "35":"35 min","40":"40 min","45":"45 min","50":"50 min",
                                   "55":"55 min","60":"60 min"],
                          defaultValue:"auto", required:false, submitOnChange:true
                    // Show what tonight's cycle would look like
                    def lastRt = atomicState["lastRuntime_${z}"] as Integer
                    if (lastRt && lastRt > maxC2) {
                        int cycles2  = Math.ceil(lastRt / maxC2) as int
                        int soakMin2 = calcSoakMin(z)
                        int windowM  = lastRt + (cycles2 - 1) * soakMin2
                        paragraph "<small>Last runtime: <b>${lastRt} min</b> → <b>${cycles2} cycles</b> of ≤${maxC2} min " +
                                  "with <b>${soakMin2}-min</b> soaks = <b>${windowM} min total elapsed</b></small>"
                    }
                }
            }

            // Penetration depth estimate — always shown
            def baseRt3  = (settings["zoneBaseRuntime_${z}"] ?: "45") as int
            def lastRt3  = atomicState["lastRuntime_${z}"] as Integer ?: baseRt3
            BigDecimal depth = calcPenetrationDepth(z, lastRt3)
            BigDecimal rootD = zoneRoot(z)
            String depthColor = (depth >= rootD * 0.75G) ? "green" :
                                (depth >= rootD * 0.5G)  ? "orange" : "red"
            String depthAdvice = (depth >= rootD * 0.75G) ?
                "✅ Reaches the root zone — good deep penetration." :
                (depth >= rootD * 0.5G) ?
                "⚠️ Reaches about halfway into the root zone. Consider increasing runtime." :
                "🔴 Shallow — water likely staying in the top soil layer, encouraging shallow roots."
            // Runoff caveat: if C&S is needed but disabled, actual penetration is less
            // than the estimate because some water runs off rather than soaking in
            boolean csNeeded   = (calcMaxCycleMin(z) != null)
            boolean csDisabled = (settings["cycleSoakEnabled_${z}"] == false)
            String runoffNote  = (csNeeded && csDisabled) ?
                "<br><font color='orange'><b>⚠️ Cycle-and-soak is disabled</b> for this zone but " +
                "your soil/nozzle combination causes runoff. Actual penetration depth will be " +
                "less than this estimate. Enable cycle-and-soak above to reach this depth.</font>" : ""
            paragraph "<b>Estimated water penetration depth: " +
                      "<font color='${depthColor}'>${depth} in</font></b> " +
                      "(root zone target: ${rootD} in)<br>" +
                      "<small>${depthAdvice}<br><br>" +
                      "<b>This is an estimate</b> based on your soil type and nozzle output rate, " +
                      "assuming no runoff (cycle-and-soak enabled). Actual depth varies with " +
                      "initial soil moisture, slope, and soil uniformity. " +
                      "<b>Validate with the screwdriver test:</b> push a long screwdriver into " +
                      "the soil 30 min after watering — it slides easily through wet soil and " +
                      "stops at dry soil. That stopping point is your actual depth.</small>" +
                      runoffNote
        }

        section("📉 Water Balance (dry-down tracking)", hideable:true,
                hidden:!(settings["zonePredictAhead_${z}"] || settings["zoneAnomalyDetect_${z}"] || settings["zoneDryDownCorrect_${z}"])) {
            int settleH = (zoneSettleMs(z) / 3600000L) as int
            input "zoneSettleHrs_${z}","number",
                  title:"Post-watering learning pause (hours)",
                  description:"After watering or rain, the dry-down learner waits this long for drainage to finish before measuring again. Auto from soil type; currently ${settleH}h. Set 0 to disable.",
                  range:"0..72", required:false
            paragraph "The app learns how fast this zone dries out (from the soil sensor) and can use " +
                      "that two ways. Both are optional and need a working sensor."
            def rate = atomicState["ddRatePerDay_${z}"]
            def base = atomicState["ddRateBaseline_${z}"]
            if (rate != null) {
                paragraph "<small>Learned dry-down: <b>${rate}%/day</b>" +
                          (base != null ? " (baseline ${base}%/day)" : " (still learning baseline)") + "</small>"
            } else {
                paragraph "<small>Still learning this zone's dry-down rate — needs a few days of sensor data.</small>"
            }
            input "zonePredictAhead_${z}","bool",
                  title:"Predict-ahead watering — water just before it would go dry",
                  description:"If the zone will cross its threshold within the horizon below, water tonight instead of waiting until it's already dry.",
                  defaultValue:false, submitOnChange:true
            if (settings["zonePredictAhead_${z}"]) {
                input "zonePredictMinHrs_${z}","number",
                      title:"Minimum hours between predict-ahead waterings",
                      description:"Predict-ahead only fires when the zone is still above threshold, so this enforces deep-and-infrequent watering. Auto from plant type; currently ${predictMinIntervalHrs(z)}h. Set 0 to disable. A zone that actually falls below threshold is still watered normally.",
                      range:"0..168", required:false
                input "predictHorizonHrs_${z}","enum",
                      title:"Look-ahead horizon (hours)",
                      options:["12":"12 h","18":"18 h","24":"24 h (1 day) ←","36":"36 h","48":"48 h"],
                      defaultValue:"24", required:true
            }
            input "zoneAnomalyDetect_${z}","bool",
                  title:"Watch this zone for abnormal drying (possible leak)",
                  description:"Tracks this zone's dry-down vs its own baseline. Alerts are delivered only if 'Notify: zone drying abnormally fast' is on in the Notifications section — this just chooses which zones to watch and how sensitively.",
                  defaultValue:false, submitOnChange:true
            if (settings["zoneAnomalyDetect_${z}"]) {
                input "anomalyRatio_${z}","enum",
                      title:"Alert when drying this many times faster than baseline",
                      options:["2.0":"2.0×","2.5":"2.5× (recommended) ←","3.0":"3.0×","4.0":"4.0×"],
                      defaultValue:"2.5", required:true
            }
            input "zoneDryDownCorrect_${z}","bool",
                  title:"Fine-tune watering duration from measured dry-down",
                  description:"Nudges this zone's ET-based runtime by up to ±15% if the sensor shows it's drying faster or slower than its own baseline. A gentle correction on top of ET — never a replacement. Needs several days of data first.",
                  defaultValue:false, submitOnChange:true
            if (settings["zoneDryDownCorrect_${z}"]) {
                def recentR = atomicState["ddRatePerDay_${z}"]
                def baseR   = atomicState["ddRateBaseline_${z}"]
                if (recentR != null && baseR != null && (baseR as BigDecimal) >= 1.0G) {
                    BigDecimal ratio = ((recentR as BigDecimal) / (baseR as BigDecimal))
                    double cf = Math.max(0.85d, Math.min(1.15d, ratio.toDouble()))
                    int pct = Math.round((cf - 1.0d) * 100) as int
                    String dir = pct > 0 ? "+${pct}% (drying faster than normal)" : (pct < 0 ? "${pct}% (drying slower than normal)" : "no change (drying at baseline)")
                    paragraph "<small>Current correction: <b>${dir}</b> — recent ${recentR}%/day vs baseline ${baseR}%/day.</small>"
                } else {
                    paragraph "<small>Not enough settled data yet to correct — needs a stable baseline (a few days). Until then, runtime is pure ET.</small>"
                }
            }
        }

        section("🌡️ Midday Moisture Top-Up", hideable:true, hidden:!settings["zoneMiddayCheck_${z}"]) {
            paragraph "Check this zone again in the afternoon and water it ONLY if the soil has actually " +
                      "dropped below its (dynamic) threshold. The amount is calculated from the measured " +
                      "deficit and capped at one full refill — no overwatering. Good for fast-draining " +
                      "soil or thirsty crops in heat. Set the check time on the Advanced page."
            input "zoneMiddayCheck_${z}","bool",
                  title:"Enable midday moisture top-up for this zone?",
                  defaultValue:false, submitOnChange:true
            if (settings["zoneMiddayCheck_${z}"]) {
                // Live preview using current reading + dynamic threshold
                BigDecimal etoNow = (atomicState.lastEto ?: 0G) as BigDecimal
                BigDecimal baseNow = (atomicState.lastEtInputs?.baseline ?: 0G) as BigDecimal
                def ev = evaluateSoilForZone(z, etoNow, baseNow)
                int cap = madBandRefillMinutes(z)
                if (ev.fresh && ev.soilPct != null && ev.thresh != null) {
                    int sp = ev.soilPct as int, th = ev.thresh as int
                    if (sp <= th) {
                        int mins = middayTopUpMinutes(z, sp, th)
                        paragraph "<small>Right now: soil <b>${sp}%</b> ≤ threshold <b>${th}%</b> → " +
                                  "would top up <b>${mins} min</b> (max ${cap} min).</small>"
                    } else {
                        paragraph "<small>Right now: soil <b>${sp}%</b> > threshold <b>${th}%</b> → " +
                                  "no top-up needed. (Max if dry: ${cap} min.)</small>"
                    }
                } else {
                    paragraph "<small>⚠️ No <b>fresh</b> soil reading for this zone, so the midday top-up " +
                              "won't run — it only acts on a current reading, never a stale or missing one. " +
                              "(Would be capped at ${cap} min when a live reading is available.)</small>"
                }
            }
        }

        section("🌱 Grass-Seed Germination Mode", hideable:true, hidden:!settings["zoneSeedingEnabled_${z}"]) {
            paragraph "Switches this zone to a phase-based germination program: frequent short " +
                      "cycles to keep the seedbed moist, automatically tapering over weeks, then " +
                      "returning to normal ET watering. Seeding cycles run on their own schedule " +
                      "(${SEED_CYCLE_TIMES.join(', ')}) and only skip for rain or hard freeze — " +
                      "wind and dry air mean seed needs MORE water, not less."
            input "zoneSeedingEnabled_${z}","bool",
                  title:"Enable seeding mode for this zone?",
                  defaultValue:false, submitOnChange:true
            if (settings["zoneSeedingEnabled_${z}"]) {
                input "zoneSeedType_${z}","enum",
                      title:"Seed type",
                      options:SEED_PRESETS.keySet().toList(),
                      defaultValue:"Cool-season fast (rye, tall fescue)",
                      required:true, submitOnChange:true
                input "zoneSeedStartDate_${z}","date",
                      title:"Seeding start date (day 1)",
                      required:true, submitOnChange:true
                input "zoneSeedAdvanced_${z}","bool",
                      title:"Override phase lengths? (optional)",
                      defaultValue:false, submitOnChange:true
                if (settings["zoneSeedAdvanced_${z}"]) {
                    List pp = seedPreset(z)
                    input "zoneSeedP1Days_${z}","number", title:"Phase 1 (Germinate) days", defaultValue:(pp[0] as int), required:false, width:4
                    input "zoneSeedP2Days_${z}","number", title:"Phase 2 (Establish) days", defaultValue:(pp[1] as int), required:false, width:4
                    input "zoneSeedP3Days_${z}","number", title:"Phase 3 (Transition) days", defaultValue:(pp[2] as int), required:false, width:4
                }
                // Live status
                int day = seedDayNumber(z)
                int ph  = seedPhase(z)
                if (day <= 0) {
                    paragraph "<small>⏳ Set a start date on or before today to begin. " +
                              "(If the date is in the future, the program starts then.)</small>"
                } else if (ph == 0) {
                    paragraph "<small><font color='green'>✅ Program complete (day ${day}). " +
                              "This zone is back on normal ET-based watering.</font></small>"
                } else {
                    List pp = seedPreset(z)
                    int total = (pp[0] as int) + (pp[1] as int) + (pp[2] as int)
                    String phName = ["","Germinate","Establish","Transition"][ph]
                    int cyc = seedCyclesToday(z)
                    int rt  = seedRuntimeMin(z)
                    paragraph "<small><b>Day ${day} of ${total}</b> · Phase ${ph} (${phName})<br>" +
                              "Today: <b>${cyc} cycle(s)</b> × <b>${rt} min</b> at " +
                              "${cyc >= 3 ? SEED_CYCLE_TIMES.join(', ') : (cyc == 2 ? SEED_CYCLE_TIMES[0]+', '+SEED_CYCLE_TIMES[2] : SEED_CYCLE_TIMES[0])}<br>" +
                              "<i>Auto-advances through phases; returns to normal watering at day ${total}.</i></small>"
                }
            }
        }

        section("Advanced Parameter Overrides", hideable:true, hidden:true) {
            paragraph "Leave blank to use values from plant/soil/nozzle selections above."
            input "zoneKcOverride_${z}","decimal",    title:"Kc (0.10–1.50)",       range:"0.10..1.50", required:false, width:4
            input "zoneRootOverride_${z}","decimal",   title:"Root depth (in)",      range:"1.0..36.0",  required:false, width:4
            input "zonePrecipOverride_${z}","decimal", title:"Precip rate (in/hr)",  range:"0.05..3.0",  required:false, width:4
            input "zoneMadOverride_${z}","decimal",    title:"MAD (0.10–0.80)",      range:"0.10..0.80", required:false, width:4
            input "btnResetAdv_${z}","button", title:"↩️ Reset to auto-derived values"
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// GUARDS PAGE
// ═══════════════════════════════════════════════════════════════════════════════

Object guardsPage() {
    dynamicPage(name:"guardsPage", title:"🛡️ Weather Guard Thresholds",
                install:false, uninstall:false) {
        section("<b>About Guards</b>") {
            paragraph "All guards use your local station only (NOAA not used for rain guards). " +
                      "Evaluate at cycle start; re-evaluate mid-cycle if 'Cancel on fail' is on."
        }
        section("<b>🧊 Freeze Protection</b>") {
            input "guardTempMin","enum",
                  title:"Skip if temperature ≤ (°F)",
                  options:(28..55).step(1).collectEntries {
                      [it.toString(), "${it}°F${it==38?' ← freeze protection':it==32?' ← hard freeze':''}"]
                  },
                  defaultValue:"38", required:true
        }
        section("<b>🌧️ Rain Guards</b>") {
            input "guardRainRate","enum",
                  title:"Skip if rain rate ≥ (in/hr)",
                  options:["0.05":"0.05 (trace)","0.09":"0.09 (drizzle) ←","0.15":"0.15","0.25":"0.25","0.50":"0.50"],
                  defaultValue:"0.09", required:true
            input "guardRainDaily","enum",
                  title:"Skip if rain today ≥ (in)",
                  options:["0.10":"0.10\"","0.15":"0.15\"","0.20":"0.20\"","0.25":"0.25\" ←","0.35":"0.35\"","0.50":"0.50\"","0.75":"0.75\""],
                  defaultValue:"0.25", required:true
            input "guardRainEvent","enum",
                  title:"Skip if rain event ≥ (in)",
                  options:["0.25":"0.25\"","0.35":"0.35\"","0.50":"0.50\" ←","0.75":"0.75\"","1.00":"1.00\"","1.50":"1.50\""],
                  defaultValue:"0.50", required:true
            paragraph "<small>← = recommended defaults</small>"
        }
        section("<b>📅 Season Gate</b>") {
            paragraph "Hard date limit — no irrigation outside these dates. Local clock only."
            input "seasonStartMonth","enum", title:"Start month",
                  options:(1..12).collectEntries { [it.toString(), monthName(it)] },
                  defaultValue:"5", required:true, width:3
            input "seasonStartDay","enum", title:"Start day",
                  options:(1..31).collectEntries { [it.toString(), it.toString()] },
                  defaultValue:"1", required:true, width:3
            input "seasonEndMonth","enum", title:"End month",
                  options:(1..12).collectEntries { [it.toString(), monthName(it)] },
                  defaultValue:"10", required:true, width:3
            input "seasonEndDay","enum", title:"End day",
                  options:(1..31).collectEntries { [it.toString(), it.toString()] },
                  defaultValue:"15", required:true, width:3
            paragraph "Status: <b>${isInSeason() ? '<font color=\'green\'>In Season ✓</font>' : '<font color=\'gray\'>Out of Season</font>'}</b>"
        }
        section("<b>Guard Behavior</b>") {
            input "cancelOnGuardFail","bool",
                  title:"Cancel running cycle if a guard fails mid-run?",
                  description:"Rain starting mid-cycle aborts remaining zones",
                  defaultValue:true
        }

        section("<b>🌵 Drought Protection (sensor-failure safety net)</b>") {
            paragraph "If a zone is set to <b>Skip when sensor is stale</b> and its soil sensor " +
                      "fails for several days in a row (dead battery, lost signal, hardware fault), " +
                      "the zone would silently never water — and the lawn could die during a heat wave " +
                      "without any watering happening. This safety net waters the zone at its base " +
                      "runtime after <b>${DROUGHT_SKIP_LIMIT} consecutive skip days</b> and notifies you " +
                      "to check the sensor. The counter resets as soon as the sensor reports a valid reading."
            input "droughtProtectEnabled","bool",
                  title:"Enable drought protection override (recommended)",
                  description:"Water anyway after ${DROUGHT_SKIP_LIMIT} days of sensor-failure skips",
                  defaultValue:true
        }

        section("<b>🌫️ Local Frost Guard (Dew Point Spread — no internet)</b>") {
            paragraph "When the temperature is close to the dew point, frost is likely to form — " +
                      "even if the temperature is above 32°F. For example, 38°F with high humidity " +
                      "can frost just as badly as 34°F on a dry night. This guard is more accurate " +
                      "than a raw temperature threshold alone for frost detection.<br><br>" +
                      "<b>How it works:</b> The app subtracts dew point from temperature. A small gap " +
                      "(e.g., less than 4°F) means the air is nearly saturated — frost is likely. " +
                      "Requires both temperature AND dew point to be mapped from your weather station."

            input "dpSpreadGuardEnabled","bool",
                  title:"Enable frost guard (skip if temperature − dew point is very small)?",
                  defaultValue:false, submitOnChange:true

            if (settings.dpSpreadGuardEnabled) {
                input "dpSpreadMinF","enum",
                      title:"Skip if (temperature − dew point) is at or below (°F)",
                      description:"Less than 4°F spread = frost risk; less than 2°F = fog/frost very likely",
                      options:["2":"2°F (frost very likely)","3":"3°F","4":"4°F (frost likely) ←",
                               "5":"5°F","6":"6°F","8":"8°F"],
                      defaultValue:"4", required:true

                input "dpSpreadMaxTempF","enum",
                      title:"…AND only when temperature is at or below (°F)",
                      description:"Frost needs cold air. A small spread on a warm night is just fog/dew, not frost — this prevents false skips. 38°F recommended.",
                      options:["34":"34°F (very strict — near freezing)","36":"36°F","38":"38°F (recommended) ←",
                               "40":"40°F","45":"45°F (cautious)"],
                      defaultValue:"38", required:true

                def wx = settings.weatherStation
                if (wx && settings.attrTemp && settings.attrDewPoint &&
                    settings.attrTemp != "(not available)" && settings.attrDewPoint != "(not available)") {
                    def tempNow = safeGetAttrBD(wx, settings.attrTemp)
                    def dpNow   = safeGetAttrBD(wx, settings.attrDewPoint)
                    if (tempNow != null && dpNow != null) {
                        BigDecimal tF = stationTempToF(tempNow)
                        BigDecimal spread = (tF - stationTempToF(dpNow)).setScale(1, BigDecimal.ROUND_HALF_UP)
                        BigDecimal minSp = (settings.dpSpreadMinF ?: "4") as BigDecimal
                        BigDecimal maxT  = (settings.dpSpreadMaxTempF ?: "38") as BigDecimal
                        boolean wouldSkip = (spread <= minSp && tF <= maxT)
                        String status = wouldSkip ?
                            "<font color='orange'>⚠️ would skip (cold + low spread)</font>" :
                            (spread <= minSp ?
                                "<font color='green'>✓ low spread but ${tF}°F is above ${maxT}°F — no frost risk, would NOT skip</font>" :
                                "<font color='green'>✓ OK</font>")
                        paragraph "Current: <b>${tF}°F</b>, spread <b>${spread}°F</b> — ${status}"
                    }
                } else {
                    paragraph "<small>⚠️ This guard requires Temperature and Dew Point to be mapped in Weather Settings.</small>"
                }
            }
        }

        section("<b>📉 Local Storm Approach Guard (Pressure Drop — no internet)</b>") {
            paragraph "When a storm is approaching, barometric pressure drops — often hours before " +
                      "the rain arrives. This guard skips watering if pressure has been falling rapidly.<br><br>" +
                      "<b>How it works:</b> The app tracks pressure readings over 3 hours. A drop beyond " +
                      "your threshold skips tonight's cycle. Entirely local — no internet needed.<br><br>" +
                      "<b>Requires:</b> A barometric pressure sensor configured in Weather Settings " +
                      "(on Ecowitt systems, that's the GW2000 indoor sensor, <em>not</em> the outdoor WS90)."

            if (!settings.pressureSensor) {
                paragraph "<font color='orange'>⚠️ No pressure sensor configured — go to Weather Settings → " +
                          "Barometric Pressure Sensor to set this up first.</font>"
            }

            input "pressureTrendGuardEnabled","bool",
                  title:"Enable storm approach guard (skip if pressure is dropping fast)?",
                  defaultValue:false, submitOnChange:true

            if (settings.pressureTrendGuardEnabled) {
                input "pressureTrendThreshIn","enum",
                      title:"Skip if pressure has dropped more than (inches Hg over 3 hours)",
                      description:"A drop of ~0.10\" over 3 hours signals a meaningful storm approaching",
                      options:["0.06":"0.06\" (minor pressure drop)","0.10":"0.10\" ←","0.15":"0.15\" (strong storm likely)",
                               "0.20":"0.20\" (very strong storm)","0.30":"0.30\" (severe weather)"],
                      defaultValue:"0.10", required:true

                if (settings.pressureSensor && settings.pressureAttr && settings.pressureAttr != "(not available)") {
                    def liveP = safeGetAttrBD(settings.pressureSensor, settings.pressureAttr)
                    def trend = calcPressureTrend()
                    if (liveP != null) {
                        paragraph "Current pressure: <b>${liveP} inHg</b> (from ${settings.pressureSensor.displayName})<br>" +
                                  "<small>Sea-level adjusted. Standard = 29.92 inHg. Weather systems move pressure ±0.5–2.0 inHg over hours/days.</small>"
                    }
                    if (trend != null) {
                        def thresh = -((settings.pressureTrendThreshIn ?: "0.10") as BigDecimal)
                        paragraph "3-hour change: <b>${String.format('%+.3f', trend as double)}\" Hg</b>${pressureTrendContext(trend)}<br>" +
                                  (trend <= thresh ?
                                   "<font color='orange'>⚠️ DROPPING past threshold — would skip tonight's cycle</font>" :
                                   "<font color='green'>✓ Within normal range — no storm guard trigger</font>")
                    } else {
                        paragraph "<small>Still gathering pressure history — needs ~30 minutes of readings.</small>"
                    }
                }
            }
        }

        if (!isLocalOnlyMode() && settings.noaaFallbackEnabled) {
            section("<b>🌐 NOAA Predictive Guard (Rain Forecast)</b>") {
                paragraph "Your local station tells you what's raining <i>now</i>. NOAA tells " +
                          "you what's forecast for <i>tonight</i>. These guards skip watering " +
                          "when significant rain is predicted — preventing overwatering before " +
                          "a storm. Forecast data is fetched once daily at 2:00 PM and cached."

                def cacheDate = atomicState.noaaCacheDate
                def today     = new Date().format("yyyy-MM-dd", location.timeZone)
                def fresh     = (cacheDate == today)
                if (fresh) {
                    String qpfDisp = atomicState.noaaCacheQpf != null ?
                        "<b>${atomicState.noaaCacheQpf} in</b>" :
                        "<b>not provided by NWS</b> <small>(PoP drives the skip)</small>"
                    paragraph "<font color='green'>✅ Forecast cache: ${cacheDate}</font><br>" +
                              "Tonight PoP: <b>${atomicState.noaaCachePop ?: '—'}%</b> | " +
                              "QPF: ${qpfDisp} | " +
                              "Tmax: <b>${atomicState.noaaCacheTmax ?: '—'}°F</b> | " +
                              "Tmin: <b>${atomicState.noaaCacheTmin ?: '—'}°F</b>"
                } else {
                    paragraph "<font color='orange'>⚠️ No forecast cache yet (fetches daily at 2 PM, " +
                              "or tap Refresh ET in manual controls)</font>"
                }

                input "noaaPopGuardEnabled","bool",
                      title:"Skip if rain probability tonight ≥ threshold?",
                      defaultValue:true, submitOnChange:true

                if (settings.noaaPopGuardEnabled != false) {
                    input "noaaPopGuardThreshold","enum",
                          title:"Rain probability threshold (%)",
                          description:"Skip watering if NOAA forecasts at least this chance of rain tonight",
                          options:["30":"30% (any chance)","40":"40%","50":"50% (coin flip) ←",
                                   "60":"60%","70":"70% (likely rain)","80":"80%","90":"90%"],
                          defaultValue:"50", required:true
                    input "noaaQpfGuardThreshold","enum",
                          title:"Forecast rain amount threshold (inches)",
                          description:"Also skip if NOAA forecasts at least this much rain tonight",
                          options:["0.10":"0.10\"","0.25":"0.25\" (significant) ←","0.50":"0.50\"","1.00":"1.00\""],
                          defaultValue:"0.25", required:false
                    input "noaaQpfMinForSkip","enum",
                          title:"Only skip when expected rain is at least (inches)",
                          description:"Thunderstorm forecasts are often high-probability but small or missing amounts. The probability skip above only fires when the forecast AMOUNT also reaches this floor, so an 80% chance of a tenth of an inch no longer cancels a needed watering.",
                          options:["0.10":"0.10\"","0.15":"0.15\" (recommended) ←","0.25":"0.25\""],
                          defaultValue:"0.15", required:false
                    input "forecastVerifyEnabled","bool",
                          title:"Verify forecast skips: if the promised rain never arrives, run the cycle late (before failsafe)",
                          defaultValue:true, submitOnChange:true
                    if (settings.forecastVerifyEnabled != false) {
                        input "forecastVerifyLeadMin","number",
                              title:"Recheck this many minutes before failsafe",
                              description:"Leave enough window for the zones to water if the forecast busted. Default 120.",
                              defaultValue:120, range:"30..240", required:false
                    }
                }
            }
        }

        section("<b>💨 Wind Guard</b>", hideable:true, hidden:!settings.windGuardEnabled) {
            paragraph "High wind blows sprinkler water off-target and evaporates it before it " +
                      "reaches the soil. This guard skips the cycle when <b>sustained</b> wind exceeds " +
                      "your threshold. Gusts are transient — a single gust shouldn't cancel a whole " +
                      "night's watering — so gusts are ignored by default. You can optionally set a " +
                      "much higher 'severe gust' limit that still skips during a genuine windstorm."
            input "windGuardEnabled","bool",
                  title:"Enable wind guard?",
                  defaultValue:false, submitOnChange:true
            if (settings.windGuardEnabled) {
                input "guardWindMph","enum",
                      title:"Skip if SUSTAINED wind exceeds (mph)",
                      options:["10":"10 mph (strict)","12":"12 mph","15":"15 mph (recommended) ←",
                               "18":"18 mph","20":"20 mph","25":"25 mph"],
                      defaultValue:"15", required:true
                input "guardGustMph","enum",
                      title:"Optional: also skip on a SEVERE gust above (mph)",
                      description:"Leave blank to ignore gusts entirely (sustained-only). Set well above the sustained limit — only for true windstorms.",
                      options:["30":"30 mph","35":"35 mph","40":"40 mph","45":"45 mph","50":"50 mph"],
                      required:false
                if (settings.weatherStation) {
                    BigDecimal w = safeGetAttrBD(settings.weatherStation, resolvedAttr("attrWind","windSpeed"))
                    BigDecimal g = safeGetAttrBD(settings.weatherStation, resolvedAttr("attrWindGust","windGust"))
                    String wStr = (w != null) ? "<b>${w} mph</b> sustained" : "sustained n/a"
                    String gStr = (g != null) ? " · gusts ${g} mph" : ""
                    paragraph "<small>Current: ${wStr}${gStr}</small>"
                }
            }
        }

        section("<b>🍃 Leaf Wetness Guard</b>", hideable:true, hidden:!settings.leafWetGuardEnabled) {
            paragraph "If you have a leaf wetness sensor (e.g. Ecowitt WN35), this guard skips the " +
                      "cycle when leaves are already wet — adding irrigation when foliage is wet " +
                      "prolongs the wet period and increases fungal disease risk."
            input "leafWetGuardEnabled","bool",
                  title:"Enable leaf wetness guard?",
                  defaultValue:false, submitOnChange:true
            if (settings.leafWetGuardEnabled) {
                input "leafWetnessSensor","capability.sensor",
                      title:"Leaf wetness sensor device", required:false, submitOnChange:true
                if (settings.leafWetnessSensor) {
                    input "leafWetnessAttr","string",
                          title:"Leaf wetness attribute name",
                          defaultValue:"leafWetness", required:false
                    input "leafWetnessThreshold","number",
                          title:"Skip if leaf wetness above this value",
                          defaultValue:30, required:false
                }
            }
        }

        section("<b>⚡ Time-of-Use Electricity Guard</b>", hideable:true, hidden:!settings.touGuardEnabled) {
            paragraph "If your irrigation uses a well pump and you're on a time-of-use electricity " +
                      "rate, this guard prevents the cycle from running during expensive peak hours. " +
                      "Irrelevant if you use municipal water (no pump)."
            input "touGuardEnabled","bool",
                  title:"Enable time-of-use guard?",
                  defaultValue:false, submitOnChange:true
            if (settings.touGuardEnabled) {
                input "touPeakStart","time", title:"Peak hours start", required:false
                input "touPeakEnd","time",   title:"Peak hours end",   required:false
                paragraph "<small>The cycle will be skipped if its start time falls within this window.</small>"
            }
        }

        section("<b>🚱 Water Leak Sensor</b>", hideable:true, hidden:!settings.leakSensor) {
            paragraph "If you place a water leak sensor (e.g. Ecowitt WH55) in your irrigation valve " +
                      "box or near the backflow preventer, this will immediately close all valves and " +
                      "alert you when water is detected — catching a stuck valve before it floods."
            input "leakSensor","capability.waterSensor",
                  title:"Water leak sensor device", required:false, submitOnChange:true
            if (settings.leakSensor) {
                paragraph "<small>When this sensor reports wet, the app triggers a failsafe close " +
                          "and sends a notification (if notifications are enabled).</small>"
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ADVANCED PAGE
// ═══════════════════════════════════════════════════════════════════════════════

Object advancedPage() {
    dynamicPage(name:"advancedPage", title:"⚙️ ET Tuning & Advanced",
                install:false, uninstall:false) {

        section("<b>ET Calculation</b>") {
            paragraph "FAO-56 Penman-Monteith when solar radiation is available; " +
                      "Hargreaves (temperature only) as fallback. Both local only."
            input "etUseAdaptive","bool",
                  title:"Adaptive runtime (scale by tonight's ETo vs seasonal baseline)?",
                  description:"On = hot nights run longer, cool nights shorter",
                  defaultValue:true, submitOnChange:true
            if (settings.etUseAdaptive != false) {
                def lat  = location.latitude as BigDecimal
                def mon  = Calendar.getInstance(location.timeZone).get(Calendar.MONTH) + 1
                def auto = seasonalBaseline(lat, mon)
                String etSrc = (atomicState.etNormalsSource ?: "static_table") as String
                String etSrcLabel = [
                    "ncei_normals"      : "✅ NCEI 1991-2020 normals (live from API)",
                    "observed_rolling"  : "⚠️ Observed rolling correction (NCEI fetch failed)",
                    "static_table"      : "📋 Embedded static table (1991-2020, current)",
                    "static_table_stale": "🔴 Embedded static table — STALE (enable NOAA to refresh)",
                ][etSrc] ?: "📋 Embedded static table"
                String etStation  = atomicState.etNormalsGhcndId ?
                    "<br><small>Station: ${atomicState.etNormalsGhcndId}</small>" : ""
                String etRefresh  = atomicState.etNormalsRefreshedAt ?
                    "<br><small>Last refresh: ${new Date(atomicState.etNormalsRefreshedAt as long).format('yyyy-MM-dd', location.timeZone)}</small>" : ""
                paragraph "Seasonal baseline — lat ${String.format('%.1f',lat.doubleValue())}°N, " +
                          "month ${mon}: <b>${String.format('%.2f',auto as double)} in/day</b><br>" +
                          "<small>Source: ${etSrcLabel}${etStation}${etRefresh}</small>"
                input "etBaselineOverride","decimal",
                      title:"Manual baseline override (in/day) — blank = dynamic seasonal",
                      range:"0.05..0.80", required:false
            }
        }

        section("<b>Runtime Limits</b>") {
            input "runtimeMin","enum", title:"Minimum runtime (minutes)",
                  options:(5..40).step(5).collectEntries { [it.toString(), "${it} min"] },
                  defaultValue:"20", required:true
            input "runtimeMax","enum", title:"Maximum runtime (minutes)",
                  options:(20..120).step(5).collectEntries { [it.toString(), "${it} min"] },
                  defaultValue:"65", required:true
        }

        section("<b>👁️ Manual Valve Monitoring</b>") {
            paragraph "When you open a sprinkler zone manually — from a dashboard tile, " +
                      "voice command, or another app — this app notices it and adjusts " +
                      "tonight's automatic cycle accordingly.<br><br>" +
                      "<b>How it works (proportional reduction, not skip):</b><br>" +
                      "If the app plans to run a zone for 40 minutes tonight and you already " +
                      "ran it manually for 15 minutes, tonight's auto run will be <b>25 minutes</b> — " +
                      "the remaining deficit. The zone only gets skipped entirely if your manual " +
                      "run covered the full planned amount.<br><br>" +
                      "<b>Credit window:</b> How long ago a manual run still counts. Set this " +
                      "to at least 10 hours if your cycle runs at 4 AM and you typically water " +
                      "in the evening. A manual run outside this window is ignored.<br><br>" +
                      "<b>Short opens don't count:</b> A 30-second valve test to see if a head " +
                      "is working does not reduce tonight's runtime — only meaningful runs do."

            input "manualMonitorEnabled","bool",
                  title:"Monitor valves for external manual activations?",
                  description:"Recommended ON — reduces tonight's auto runtime by any manual watering done today",
                  defaultValue:true, submitOnChange:true

            if (settings.manualMonitorEnabled != false) {
                input "manualCreditHours","enum",
                      title:"How long ago a manual run still counts toward tonight's deficit",
                      description:"Should be longer than the gap between when you water manually and 4 AM. 12 hours covers evening watering.",
                      options:["1":"1 hr","2":"2 hr","4":"4 hr","6":"6 hr",
                               "8":"8 hr","12":"12 hr (recommended for evening watering) ←",
                               "16":"16 hr","24":"24 hr"],
                      defaultValue:"12", required:true

                input "manualCreditMinRun","enum",
                      title:"Minimum manual run time to count as real watering",
                      description:"Quick valve tests shorter than this are ignored",
                      options:["1":"1 min","2":"2 min","3":"3 min (recommended)","5":"5 min","10":"10 min"],
                      defaultValue:"3", required:true

                // Show current credit status
                int zc = safeZoneCount()
                def reduced = (1..zc).findAll { z -> getManualCreditMinutes(z) > 0 }
                if (reduced) {
                    def reducedStr = reduced.collect { z ->
                        def nm  = settings["zoneName_${z}"] ?: "Zone ${z}"
                        def min = getManualCreditMinutes(z)
                        "${nm}: −${min}min manual credit"
                    }.join("<br>")
                    paragraph "<font color='blue'><b>Zones with active runtime reduction tonight:</b><br>${reducedStr}</font>"
                }
            }
        }

        section("<b>Location</b>") {
            paragraph "Hub: <b>${fmtCoord(location.latitude as BigDecimal, location.longitude as BigDecimal)}</b> " +
                      "(update in Hubitat → Settings → Location)"
            input "locationElevationFt","decimal",
                  title:"Elevation above sea level (feet) — used in PM atmospheric pressure",
                  defaultValue:100.0, range:"0..14000", required:true
        }

        section("<b>Failsafe</b>") {
            input "failsafeTime","time",
                  title:"Unconditional close-all time every morning",
                  description:"Must be after worst-case finish. 4 zones × 65 min = 6:20 AM. Recommend 6:30.",
                  defaultValue:"2000-01-01T06:30:00.000-0700", required:true
        }

        section("<b>Inter-Zone Delay</b>") {
            input "interZoneDelaySec","enum",
                  title:"Pause between zones (pressure stabilization)",
                  options:["0":"None","5":"5s","10":"10s ←","15":"15s","30":"30s","60":"60s"],
                  defaultValue:"10", required:true
        }

        section("<b>🔄 Cycle-and-Soak Soak Time</b>") {
            paragraph "Soak time is now set automatically per zone based on soil type — " +
                      "sandy soils drain fast (15 min) while clay needs much longer (60 min) " +
                      "to clear the top layer before the next burst. You can override the " +
                      "auto value per zone in each zone's Cycle and Soak section.<br><br>" +
                      "<b>How soak time is calculated:</b> The app uses your soil's hydraulic " +
                      "conductivity (how fast water moves through it under gravity) to estimate " +
                      "how long is needed between bursts. A sandy loam drains in 15 minutes; " +
                      "clay takes 60 minutes. The screwdriver test tells you if the auto value " +
                      "is working: soil 2 inches below the surface should be moist (not saturated) " +
                      "when the next burst starts. If it's still saturated, increase the override."
            // Show auto soak time per zone
            int zc2 = safeZoneCount()
            (1..zc2).each { z2 ->
                if (settings["zoneEnabled_${z2}"] != false && settings["zoneValve_${z2}"] &&
                    settings["cycleSoakEnabled_${z2}"] != false && calcMaxCycleMin(z2) != null) {
                    String soilN = settings["zoneSoil_${z2}"] ?: "Loam"
                    int autoS = calcSoakMin(z2)
                    String override = settings["zoneSoakOverride_${z2}"] ?: "auto"
                    String label = (override == "auto" || override == null || override == "") ?
                        "⚙️ ${autoS} min (auto from ${soilN})" : "✏️ ${override} min (override)"
                    paragraph "<small><b>${sanitizeHtml(settings["zoneName_${z2}"] as String ?: "Zone ${z2}")}:</b> ${label}</small>"
                }
            }
            // Show total estimated window for all zones
            def q = atomicState.zoneQueue as List
            if (q) {
                int win = calcTotalCycleWindowMin(q)
                paragraph "<small>Estimated window for tonight's active zones: <b>${win} min</b> " +
                          "(${String.format('%.1f', win/60.0)} hrs)</small>"
            }
        }

        section("<b>🔧 Master Valve / Pump Relay</b>", hideable:true, hidden:!settings.masterValve) {
            paragraph "If your system has a master valve or pump start relay that must open before " +
                      "any zone can receive water, select it here. The app opens it before the first " +
                      "zone and closes it after the last zone (and on abort/failsafe)."
            input "masterValve","capability.valve",
                  title:"Master valve / pump relay device", required:false, submitOnChange:true
            if (settings.masterValve) {
                paragraph "<small>Opens at cycle start, closes when all zones finish or on abort.</small>"
            }
        }

        section("<b>🔁 Valve State Verification</b>", hideable:true, hidden:true) {
            paragraph "After each open/close command, the app re-reads the valve's reported state " +
                      "to confirm the command took. Zigbee/Z-Wave/WiFi commands are occasionally " +
                      "dropped — this catches a valve that never opened (a zone silently getting no " +
                      "water) or one that won't close. On a mismatch it retries once, then alerts via " +
                      "the 'valve anomaly' notification. Requires a valve device that reports its state."
            input "valveCheckBackEnabled","bool",
                  title:"Verify valve state after commands (retry once, then alert)",
                  defaultValue:true, submitOnChange:true
            if (settings.valveCheckBackEnabled != false) {
                input "valveCheckDelaySec","number",
                      title:"Settle delay before checking (seconds)",
                      description:"How long to wait for the valve to report its new state. Default 3. Increase for slow hardware.",
                      defaultValue:3, range:"1..30", required:false
            }
        }

        section("<b>❄️ Irrigation Season & Winterization</b>", hideable:true, hidden:true) {
            paragraph "Winterized mode suspends all watering until you turn it off in spring — use it " +
                      "after blowing out your lines for winter. The startup inspection runs each zone " +
                      "briefly so you can check for broken heads before the season begins."
            input "winterizedMode","bool",
                  title:"❄️ Winterized mode (suspend all watering)",
                  description:"Turn ON after fall blow-out; turn OFF in spring",
                  defaultValue:false, submitOnChange:true
            if (settings.winterizedMode) {
                paragraph "<div style='background:#cce5ff;padding:8px;border-left:4px solid #004085'>" +
                          "<b>❄️ System is winterized — all cycles suspended.</b></div>"
            }
            input "preFreezeAlertEnabled","bool",
                  title:"Pre-freeze alert (warn when hard freeze forecast and not winterized)",
                  description:"Requires NOAA fallback enabled",
                  defaultValue:false
            paragraph "<b>Spring startup inspection:</b>"
            if (atomicState.startupInspectionActive) {
                paragraph "<font color='green'>Inspection mode ACTIVE — manual zone runs use short bursts.</font>"
                input "btnEndStartupInspection","button", title:"✓ End Inspection", width:3
            } else {
                input "btnStartupInspection","button", title:"🔍 Start Inspection", width:3
            }
        }

        section("<b>🌡️ Midday Moisture Top-Up</b>", hideable:true, hidden:true) {
            paragraph "A second, <b>demand-based</b> check during the day for zones you opt in (enable " +
                      "per-zone on each zone's page). It runs the SAME soil evaluation as the morning " +
                      "cycle — live reading vs the dynamic threshold — and waters ONLY the zones that are " +
                      "actually dry. The amount is calculated from the measured deficit and capped at one " +
                      "full refill (so it can't overwater). Useful for fast-draining soils or thirsty " +
                      "crops in summer heat. Skips on the normal guards (rain, freeze, etc.).<br>" +
                      "<small>This is real watering (counts toward usage) but is not the main daily cycle.</small>"
            int zcm = safeZoneCount()
            def optInZones = (1..zcm).findAll { settings["zoneMiddayCheck_${it}"] }
                                     .collect { settings["zoneName_${it}"] ?: "Zone ${it}" }
            if (optInZones) {
                input "middayCheckTime","time",
                      title:"Midday check time (early-mid afternoon, e.g. 2:00 PM)", required:true
                paragraph "<small>Opted-in zones: <b>${optInZones.join(', ')}</b>. " +
                          "Enable/disable per zone on the individual zone pages.</small>"
            } else {
                paragraph "<small>⚠️ No zones opted in yet. Open a zone's page and enable " +
                          "'Midday moisture top-up' to use this.</small>"
            }
        }

        section("<b>🌡️ Heat-Stress Syringing</b>", hideable:true, hidden:!settings.syringeEnabled) {
            paragraph "On extreme-heat days, a brief midday misting can cool grass through evaporation. " +
                      "This is NOT irrigation — it's a short burst that doesn't count toward ET or soil " +
                      "moisture. Research support is mixed; most useful for stressed or shallow-rooted turf. " +
                      "The app checks the live temperature every 15 minutes across your afternoon window and " +
                      "fires at most once per day, the first time the threshold is crossed — so it catches " +
                      "the actual peak whether the day heats up early or late."
            input "syringeEnabled","bool",
                  title:"Enable heat-stress syringing?",
                  defaultValue:false, submitOnChange:true
            if (settings.syringeEnabled) {
                input "syringeWindowStart","time",
                      title:"Check window start (default 1:00 PM)", required:false
                input "syringeWindowEnd","time",
                      title:"Check window end (default 6:00 PM)", required:false
                input "syringeTempF","number",
                      title:"Only syringe if temperature is above (°F)",
                      defaultValue:95, required:false
                input "syringeMinsPerZone","number",
                      title:"Syringe duration per zone (minutes)",
                      defaultValue:3, required:false
                String sToday = new Date().format("yyyy-MM-dd", location.timeZone)
                if (atomicState.lastSyringeDate == sToday) {
                    paragraph "<small>✅ Syringe already ran today; next opportunity tomorrow.</small>"
                }
                paragraph "<small>Each enabled zone gets a short burst. Does not affect the nightly cycle.</small>"
            }
        }

        section("<b>🌡️ Soil Temperature ET Correction</b>", hideable:true, hidden:!settings.soilTempEtCorrEnabled) {
            paragraph "Cold soil slows root water uptake, so actual plant water use is lower than air " +
                      "temperature alone suggests. When enabled and a soil temperature sensor (WH52) is " +
                      "mapped, runtimes are reduced when soil is below 55°F (down to half at 35°F). " +
                      "Most relevant in spring and fall."
            input "soilTempEtCorrEnabled","bool",
                  title:"Enable soil temperature ET correction?",
                  defaultValue:false
        }

        section("<b>☀️ Solar Radiation Sanity Check</b>", hideable:true, hidden:!settings.solarSanityEnabled) {
            paragraph "The solar radiation sensor is the most important ET input. If it drifts or gets " +
                      "blocked (dust, debris, bird droppings), ET is silently miscalculated. This check " +
                      "compares the measured solar reading at solar noon against the theoretical clear-sky " +
                      "maximum for your latitude and date — if it's implausibly low, the app falls back to " +
                      "the temperature-only Hargreaves method instead of trusting bad data."
            input "solarSanityEnabled","bool",
                  title:"Enable solar radiation sanity check?",
                  defaultValue:false
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// LOGGING PAGE
// ═══════════════════════════════════════════════════════════════════════════════

Object loggingPage() {
    dynamicPage(name:"loggingPage", title:"📝 Logging", install:false, uninstall:false) {
        section("<b>Log Level</b>") {
            paragraph "Higher levels include all lower levels.<br>" +
                      "Off | Error | Warn | <b>Info ← recommended</b> | Debug | Trace"
            input "logLevel","enum",
                  title:"Log level",
                  options:[
                      (LOG_OFF.toString())  :"Off — no logging",
                      (LOG_ERROR.toString()):"Error — failures only",
                      (LOG_WARN.toString()) :"Warn — + unexpected conditions",
                      (LOG_INFO.toString()) :"Info — normal events (recommended) ←",
                      (LOG_DEBUG.toString()):"Debug — ET detail (auto-resets)",
                      (LOG_TRACE.toString()):"Trace — every attribute (auto-resets, very verbose)"
                  ],
                  defaultValue:LOG_INFO.toString(), required:true, submitOnChange:true
            int lv = activeLogLevel()
            if (lv >= LOG_DEBUG) {
                paragraph "<font color='orange'>⚠️ ${logLevelName(lv)} auto-resets to Info to prevent log spam.</font>"
                input "logAutoResetMins","enum",
                      title:"Auto-reset after",
                      options:["15":"15 min","30":"30 min ←","60":"1 hr","120":"2 hr"],
                      defaultValue:"30", required:true
            }
        }
        section() {
            paragraph "Active: <b>${logLevelName(activeLogLevel())}</b>" +
                      (atomicState.logAutoResetAt ?
                       " | Resets at: <b>${new Date(atomicState.logAutoResetAt as long).format('h:mm a', location.timeZone)}</b>" : "")
            input "btnResetLogNow","button", title:"↩️ Reset to Info Now", width:3
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DIAGNOSTICS PAGE
// ═══════════════════════════════════════════════════════════════════════════════

Object diagnosticsPage() {
    dynamicPage(name:"diagnosticsPage", title:"📋 Diagnostics", install:false, uninstall:false) {

        section("<b>Last ET Calculation</b>") {
            def inp = atomicState.lastEtInputs
            if (inp) {
                def method   = (inp.solarWm2 && inp.solarWm2 != "null") ? "Penman-Monteith FAO-56 (using solar data)" : "Hargreaves (temperature-only — no solar data)"
                def etoVal   = inp.etoIn ? (inp.etoIn as BigDecimal) : null
                def baseline = inp.baseline ? (inp.baseline as BigDecimal) : null

                paragraph "<b>Method:</b> ${method}<br>" +
                          "<b>Data source:</b> ${inp.source}<br>" +
                          "<b>Calculated at:</b> ${inp.timestamp}"

                paragraph "<b>Inputs used:</b><br>" +
                          "• Temperature: <b>${inp.tempF ?: '—'}°F</b>${inp.tempF ? tempContext(inp.tempF) : ''}<br>" +
                          "• Humidity: <b>${inp.humidity ?: '—'}%</b>${inp.humidity ? humidityContext(inp.humidity) : ''}<br>" +
                          "• Solar radiation: <b>${inp.solarWm2 ?: '—'} W/m²</b>${inp.solarWm2 && inp.solarWm2 != 'null' ? solarContext(inp.solarWm2) : ' <small>(not used — fell back to Hargreaves)</small>'}<br>" +
                          "• Wind: <b>${inp.windMph ?: '—'} mph</b>${inp.windMph ? windContext(inp.windMph) : ''}<br>" +
                          "• Dew point: <b>${inp.dewPointF ?: '—'}°F</b>"

                paragraph "• Seasonal baseline for your location/month: <b>${inp.baseline ?: '—'} in/day</b>" +
                          " <small style='color:gray'>(your location's average daily water loss for this time of year)</small>"

                if (etoVal != null) {
                    def etoCtx = (baseline != null) ? etoContext(etoVal, baseline) : ""
                    paragraph "<b>Result — Calculated ETo: <font color='${etoVal > 0.8G ? (etoVal > 1.5G ? 'red' : 'orange') : 'green'}'>${inp.etoIn} in/day</font></b>${etoCtx}<br>" +
                              "<small>ETo = how much water your lawn lost today due to heat, sun, wind, and humidity. " +
                              "Your zones will be scaled proportionally to this vs the seasonal average.</small>"
                }
            } else paragraph "<i>No ET calculation run yet. Tap 'Recalculate ET' on the main page to run one now.</i>"
        }

        section("<b>Last Guard Evaluation</b>") {
            def g = atomicState.lastGuardResult
            if (g) {
                paragraph "<font color='${g.passed ? 'green' : 'red'}'><b>${g.passed ? '✅ Passed' : '❌ Failed'}: ${g.reason}</b></font><br><small>${g.timestamp}</small>"
            } else paragraph "<i>No guard evaluation yet.</i>"
        }

        section("<b>☀️ Solar Noon</b>") {
            int snM = calcSolarNoonMinutes()
            def snReading = atomicState.solarNoonReading
            paragraph "Solar noon today (Hubitat sunrise/sunset midpoint): <b>${String.format('%02d:%02d', snM.intdiv(60), snM%60)}</b> local<br>" +
                      "Last capture: <b>${snReading ?: '—'}${snReading != null ? ' W/m²' : ''}</b> (${atomicState.solarNoonDate ?: 'never'})" +
                      (snReading != null ? solarContext(snReading) : "") +
                      "<br><small>Normal range: clear day 600–1100 W/m², partly cloudy 200–600, overcast 50–200. " +
                      "Physically impossible above 1,350 W/m² — if you see values near that, check your Solar Radiation attribute mapping.</small>"
            if (atomicState.solarReadingError) {
                paragraph "<font color='red'>⛔ <b>Solar error:</b> ${atomicState.solarReadingError}<br>" +
                          "ET calculation fell back to Hargreaves (temperature-only) — no solar data used.</font>"
            }
        }

        section("<b>🌡️ Sensor Health</b>") {
            int zc = safeZoneCount()
            (1..zc).each { z ->
                if (!settings["zoneSoilSensor_${z}"]) return
                def name      = settings["zoneName_${z}"] ?: "Zone ${z}"
                def lastSeen  = atomicState["sensorLastSeen_${z}"]
                def lastVal   = atomicState["sensorLastValue_${z}"]
                def stale     = isSensorStale(z)
                def behavior  = settings["sensorFailBehavior_${z}"] ?: SENSOR_FAIL_SKIP
                def ageStr    = lastSeen ? "${((now() - (lastSeen as Long))/60000).toInteger()} min ago" : "never"
                def creditMin = getManualCreditMinutes(z)
                def soilTempF = atomicState["soilTempF_${z}"]
                def orphanedState = safeGetAttr(settings["zoneSoilSensor_${z}"], "orphaned")
                boolean isOrphaned = (orphanedState == true || orphanedState?.toString() == "true")
                def statusStr = isOrphaned ? "⚠️ ORPHANED" : (stale ? "⚠️ STALE" : "✓ Fresh")

                paragraph "<b>${name}:</b> ${statusStr} | " +
                          "Moisture: ${lastVal ?: '—'}% | ${ageStr} | Fail behavior: ${behavior}" +
                          (soilTempF != null ? " | 🌡️ Soil: ${soilTempF}°F" : "") +
                          (creditMin > 0 ? " | <font color='blue'>−${creditMin}min credit active</font>" : "")
            }
        }

        section("<b>💧 Manual Valve Activity</b>") {
            int zc = safeZoneCount(); boolean any = false
            (1..zc).each { z ->
                def lastOpen   = atomicState["valveLastManualOpen_${z}"]
                def lastDurSec = atomicState["valveLastManualDurSec_${z}"]
                if (!lastOpen) return
                any = true
                def name      = settings["zoneName_${z}"] ?: "Zone ${z}"
                def ago       = ((now() - (lastOpen as Long)) / 60000).toInteger()
                def durStr    = lastDurSec ? "${lastDurSec}s (${(lastDurSec/60).toInteger()}min)" : "unknown"
                int creditMin = getManualCreditMinutes(z)
                def creditStr = creditMin > 0 ? " — <font color='blue'>−${creditMin}min credit active tonight</font>" :
                                (lastDurSec ? " — credit expired" : "")
                paragraph "<b>${name}:</b> ${ago} min ago, ran ${durStr}${creditStr}"
            }
            if (!any) paragraph "<i>No manual activations recorded.</i>"
        }

        section("<b>🌐 NOAA Status</b>") {
            if (isLocalOnlyMode()) {
                paragraph "<font color='green'>✅ LOCAL-ONLY mode — no internet calls</font>"
            } else if (settings.noaaFallbackEnabled) {
                def lastSeen = atomicState.stationLastSeen
                def ageStr   = lastSeen ? "${((now() - (lastSeen as Long)) / 60000).toInteger()} min ago" : "never"
                def fresh    = isStationFresh()
                paragraph "Local station last event: <b>${ageStr}</b> — ${fresh ? '<font color=\'green\'>Fresh ✓</font>' : '<font color=\'orange\'>⚠️ Stale</font>'}"

                def cacheDate = atomicState.noaaCacheDate
                def today     = new Date().format("yyyy-MM-dd", location.timeZone)
                def pop       = atomicState.noaaCachePop
                def qpf       = atomicState.noaaCacheQpf
                def tmax      = atomicState.noaaCacheTmax
                def tmin      = atomicState.noaaCacheTmin
                boolean cacheUsable = isNoaaCacheUsable()
                String cacheTag = (cacheDate == today) ? "<font color='green'>✓ today</font>" :
                                  (cacheUsable ? "<font color='green'>✓ in use</font> <small>(yesterday 2 PM fetch, valid for the pre-dawn cycle until noon)</small>"
                                               : "<font color='orange'>⚠️ stale</font> <small>(predictive guard inactive)</small>")
                paragraph "<b>Daily forecast cache (fetches at 2 PM):</b><br>" +
                          "Date: ${cacheDate ?: 'not yet fetched'} ${cacheTag}<br>" +
                          "Tonight PoP: <b>${pop!=null?pop+'%':'—'}</b>${pop!=null?popContext(pop):''}<br>" +
                          "Expected rain: <b>${qpf!=null?qpf+' in':'—'}</b>${qpf!=null?' <small>('+((qpf as BigDecimal)>0.25G?'significant':'light')+' rain)</small>':''}<br>" +
                          "Tmax: <b>${tmax!=null?tmax+'°F':'—'}</b>${tmax!=null?tempContext(tmax):''} | " +
                          "Tmin: <b>${tmin!=null?tmin+'°F':'—'}</b>${tmin!=null?tempContext(tmin):''}<br>" +
                          "<small>PoP = probability of measurable rain tonight. Used for the predictive rain skip guard.</small>"
                def n = atomicState.lastNoaaResult
                if (n) paragraph "<b>Last fetch:</b> ${n.timestamp}<br>${n.result}"
                // Show any NOAA/station disagreement warnings
                def disagree = atomicState.noaaDisagreement
                if (disagree) paragraph "<font color='orange'>⚠️ <b>NOAA/station disagreement:</b> ${disagree}<br>" +
                    "Local microclimate may differ from NOAA grid forecast. This is normal for valley locations " +
                    "or areas with significant terrain. Station data is preferred when available.</font>"
            } else {
                paragraph "<font color='gray'>NOAA fallback disabled (local-only mode off but NOAA not enabled)</font>"
            }
        }

        section("<b>Zone History</b>") {
            int zc = safeZoneCount()
            (1..zc).each { z ->
                def name    = settings["zoneName_${z}"] ?: "Zone ${z}"
                def lastRun = atomicState["zoneLastRun_${z}"]  ?: "Never"
                def lastMin = atomicState["lastRuntime_${z}"]
                def lastSoil= atomicState["zoneLastSoil_${z}"]
                def gal     = atomicState["lastGallons_${z}"]
                paragraph "<b>${name}:</b> ${lastRun}" +
                          (lastMin ? " | ${lastMin}min" : "") +
                          (gal != null ? " | ${String.format('%.0f', gal as double)}gal" : "") +
                          (lastSoil != null ? " | soil ${lastSoil}%" : "")
            }
        }

        section("<b>💧 Water Usage</b>") {
            def monthKey = new Date().format("yyyy-MM", location.timeZone)
            def monthGal = atomicState["monthlyGallons_${monthKey}"] ?: 0
            def totalGal = atomicState.totalGallonsAllTime ?: 0
            paragraph "<b>This month (${monthKey}):</b> " +
                      "${String.format('%.0f', monthGal as double)} gallons<br>" +
                      "<b>All-time total:</b> ${String.format('%.0f', totalGal as double)} gallons<br>" +
                      "<small>Estimated from nozzle precip rate, zone area, and runtime.</small>"
            def sbM = new StringBuilder("<br><b>Monthly history:</b><br>")
            (0..5).each { mo ->
                def cal2 = Calendar.getInstance(location.timeZone)
                cal2.add(Calendar.MONTH, -mo)
                def mk2 = cal2.getTime().format("yyyy-MM", location.timeZone)
                def mg2 = atomicState["monthlyGallons_${mk2}"]
                if (mg2 != null) sbM.append("&nbsp;&nbsp;${mk2}: <b>${String.format('%.0f', mg2 as double)} gal</b><br>")
            }
            paragraph sbM.toString()
            input "btnResetWaterUsage","button", title:"♻️ Reset water-usage totals", width:6
            if (settings.confirmResetWater) {
                paragraph "<small><font color='orange'>Tap the reset button again to confirm — this clears all monthly and all-time gallon totals.</font></small>"
            }
        }

        section("<b>🏠 Moving / New Install</b>", hideable:true, hidden:true) {
            paragraph "Moving to a new house? This clears accumulated <b>historical and " +
                      "location-specific data</b> so you start fresh, while <b>keeping your zone " +
                      "configuration</b> (names, plant/soil/nozzle, runtimes, thresholds, guard " +
                      "settings). Clears: water-usage totals, cycle history, last-ET and ET-normals " +
                      "cache, NOAA forecast cache, sensor last-seen, pressure history, and seeding " +
                      "progress.<br><br><b>What to do after moving:</b> update your hub's location " +
                      "(Settings → Location) so latitude/longitude/elevation match the new address — " +
                      "the app reads those live from the hub for ET and NOAA, so there's nothing to " +
                      "change in the app itself for location. Then re-select your weather station and " +
                      "valve devices if the device list changed."
            input "btnNewHouseReset","button", title:"🏠 Reset accumulated data for a new house", width:8
            if (settings.confirmNewHouse) {
                paragraph "<small><font color='orange'>Tap again to confirm. This clears history and " +
                          "cached data but keeps your zone/guard configuration.</font></small>"
            }
        }

        section("<b>📋 7-Day Cycle History</b>") {
            def hist = atomicState.cycleHistory as List
            if (!hist || hist.isEmpty()) {
                paragraph "<i>No cycle history yet — populates after first cycle.</i>"
            } else {
                def sb3 = new StringBuilder()
                hist.reverse().take(7).each { entry ->
                    String icon = entry.outcome == "complete" ? "✅" :
                                  entry.outcome == "skipped"  ? "⏭" :
                                  entry.outcome == "aborted"  ? "⚠️" : "—"
                    sb3.append("${icon} <b>${entry.date}</b> — ${entry.outcome}" +
                               (entry.eto  ? " | ETo ${entry.eto}" : "") +
                               (entry.note ? " | ${entry.note}"    : "") + "<br>")
                }
                paragraph sb3.toString()
            }
        }

        section("<b>🔧 Physical Calibration</b>", hideable:true, hidden:true) {
            paragraph "<b>Screwdriver depth validation</b><br>" +
                      "After a cycle, push a long screwdriver into the soil 30 minutes later. " +
                      "It slides through wet soil and stops at dry soil. Enter the measured " +
                      "depth below — the app compares to its estimate and shows a correction ratio."
            int zc2 = safeZoneCount()
            (1..zc2).each { z2 ->
                def nm2  = settings["zoneName_${z2}"] ?: "Zone ${z2}"
                def estD = atomicState["lastPenetrationDepth_${z2}"]
                if (estD != null) {
                    paragraph "<small>Zone ${z2} (${nm2}) — last est. depth: <b>${estD} in</b></small>"
                    input "calDepthMeasured_${z2}","decimal",
                          title:"Zone ${z2}: measured depth (inches)",
                          required:false, range:"0.5..12", submitOnChange:true
                    if (settings["calDepthMeasured_${z2}"]) {
                        BigDecimal meas = settings["calDepthMeasured_${z2}"] as BigDecimal
                        BigDecimal est2 = estD as BigDecimal
                        if (est2 > 0G) {
                            BigDecimal ratio = (meas / est2).setScale(2, BigDecimal.ROUND_HALF_UP)
                            String rColor = (ratio > 1.2G || ratio < 0.8G) ? "orange" : "green"
                            paragraph "<font color='${rColor}'><small>Depth ratio: ${ratio}× — " +
                                      (ratio < 0.8G ? "actual depth less than estimated → check precip rate or soil type" :
                                       ratio > 1.2G ? "actual depth more than estimated → precip rate may be too low" :
                                       "close match — settings look accurate") + "</small></font>"
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// UI HELPERS
// ═══════════════════════════════════════════════════════════════════════════════

private String weatherPageSummary() {
    def wx = settings.weatherStation
    if (!wx) return "⚠️ No station configured"
    def temp     = safeGetAttrBD(wx, resolvedAttr("attrTemp","temperature"))
    def lastSeen = atomicState.stationLastSeen
    def ageStr   = lastSeen ? "${((now() - (lastSeen as Long))/60000).toInteger()} min ago" : "never"
    def fresh    = isStationFresh() ? "" : " <font color='orange'>⚠️ stale</font>"
    return "Station: ${wx.displayName}${temp != null ? ' | '+temp+'°F' : ''} | updated ${ageStr}${fresh}"
}

private String buildStatusParagraph() {
    def sb = new StringBuilder()
    boolean running = atomicState.cycleRunning ?: false
    def activeZ     = atomicState.activeZone
    if (running && activeZ) sb.append("<font color='green'><b>💧 Running zone ${activeZ} (${sanitizeHtml(settings["zoneName_${activeZ}"] as String ?: 'Zone '+activeZ)})</b></font><br>")
    sb.append("<b>Last run:</b> ${atomicState.lastRunTimestamp ?: 'Never'}<br>")
    // ── Tonight's cycle window projection ─────────────────────────────────────
    // Build forward-looking start→end→failsafe display so the user never has to
    // dig into logs to know if the window fits before failsafe.
    // Uses last-known window (atomicState.lastCycleWindowMin) as the estimate;
    // re-calculated fresh at actual cycle start.
    def winMin = atomicState.lastCycleWindowMin
    if (winMin || settings.startTime) {
        try {
            // Determine the ACTUAL start time the cycle will use. When sunrise-relative
            // mode is on, the cycle starts (sunrise − offset), NOT the static startTime
            // field — so the projection must use the same value or it mis-reports the
            // end time and can falsely warn about the failsafe (static 4:00 AM vs real
            // 3:29 AM was a 31-min error that flipped a fitting window into a "fail").
            Calendar startCal = Calendar.getInstance(location.timeZone)
            boolean usedSunrise = false
            if (settings.sunriseRelativeStart && location.sunrise) {
                long offMs = ((settings.sunriseOffsetMins ?: 60) as long) * 60000L
                startCal.setTime(new Date(location.sunrise.time - offMs))
                usedSunrise = true
            } else if (settings.startTime) {
                startCal.setTime(toDateTime(settings.startTime))
            }
            // Project to today's date (keep H:M)
            Calendar now2 = Calendar.getInstance(location.timeZone)
            startCal.set(Calendar.YEAR,         now2.get(Calendar.YEAR))
            startCal.set(Calendar.MONTH,        now2.get(Calendar.MONTH))
            startCal.set(Calendar.DAY_OF_MONTH, now2.get(Calendar.DAY_OF_MONTH))
            String startStr = startCal.getTime().format("h:mm a", location.timeZone)
            String startNote = usedSunrise ? " <small>(sunrise−${settings.sunriseOffsetMins ?: 60}min)</small>" : ""

            if (winMin) {
                // Estimated end time
                Calendar endCal = (Calendar) startCal.clone()
                endCal.add(Calendar.MINUTE, winMin as int)
                String endStr = endCal.getTime().format("h:mm a", location.timeZone)

                // Failsafe time
                String fsStr   = null
                boolean exceed = false
                if (settings.failsafeTime) {
                    Calendar fsCal = Calendar.getInstance(location.timeZone)
                    fsCal.setTime(toDateTime(settings.failsafeTime))
                    fsCal.set(Calendar.YEAR,         startCal.get(Calendar.YEAR))
                    fsCal.set(Calendar.MONTH,        startCal.get(Calendar.MONTH))
                    fsCal.set(Calendar.DAY_OF_MONTH, startCal.get(Calendar.DAY_OF_MONTH))
                    fsStr   = fsCal.getTime().format("h:mm a", location.timeZone)
                    exceed  = endCal.timeInMillis > fsCal.timeInMillis
                }

                String hrs  = String.format("%.1f", winMin / 60.0)
                String rowColor  = exceed ? "#f8d7da" : "#d4edda"
                String borColor  = exceed ? "red"     : "green"
                String icon      = exceed ? "⚠️"      : "✅"
                String exceedMsg = exceed ?
                    "<br><b>⚠️ Estimated end (${endStr}) is after failsafe (${fsStr}). " +
                    "The failsafe will close valves mid-cycle.</b><br>" +
                    "<small>Fix: reduce base runtimes, reduce soak time per zone, " +
                    "reduce active zones, or push failsafe later.</small>" : ""

                sb.append("<div style='background:${rowColor};padding:6px;border-left:4px solid ${borColor};margin-bottom:4px'>" +
                    "${icon} <b>Tonight's cycle window</b> (estimated)<br>" +
                    "Start: <b>${startStr}</b>${startNote} · Duration: <b>${winMin} min</b> (${hrs} hrs) · Est. end: <b>${endStr}</b>" +
                    (fsStr ? " · Failsafe: <b>${fsStr}</b>" : "") +
                    "${exceedMsg}</div>")
            } else {
                // Window not yet calculated (no cycle has run) — show start time only
                sb.append("<b>Scheduled start:</b> ${startStr}${startNote} (window unknown until first cycle)<br>")
            }
        } catch (Exception e) { logD "Window display error: ${e.message}" }
    }
    def eto      = atomicState.lastEto
    def baseline = atomicState.lastEtInputs?.baseline
    if (eto != null) {
        def etoStr  = String.format('%.3f in/day', eto as double)
        def etoCtx  = (baseline != null) ? etoContext(eto, baseline) : ""
        sb.append("<b>ETo:</b> ${etoStr}${etoCtx}<br>")
    } else {
        sb.append("<b>ETo:</b> —<br>")
    }
    if (atomicState.lastSkipReason && atomicState.lastSkipReason != 'None') {
        // Include when it happened so an old reason (e.g. from before a settings change
        // or app update) is clearly dated and not mistaken for a fresh skip.
        String whenStr = atomicState.lastRunTimestamp ? " <small>(${atomicState.lastRunTimestamp})</small>" : ""
        sb.append("<b>Last skip:</b> ${atomicState.lastSkipReason}${whenStr}<br>")
    } else {
        sb.append("<b>Last skip:</b> None<br>")
    }
    int zc = safeZoneCount()
    // Active seeding zones indicator
    if (anyZoneSeeding()) {
        def seedingNotes = []
        (1..zc).each { z ->
            int ph = seedPhase(z)
            if (ph > 0) {
                String phName = ["","Germinate","Establish","Transition"][ph]
                seedingNotes << "${settings["zoneName_${z}"] ?: "Zone ${z}"} (day ${seedDayNumber(z)}, ${phName})"
            }
        }
        if (seedingNotes) sb.append("<b>🌱 Seeding:</b> ${seedingNotes.join('; ')}<br>")
    }
    if (zc > 0) {
        sb.append("<b>Soil:</b><br>")
        (1..zc).each { z ->
            def s = settings["zoneSoilSensor_${z}"]
            def n = settings["zoneName_${z}"] ?: "Zone ${z}"
            if (s) {
                def reading = safeGetAttr(s, "humidity")  // defensive — consistent with rest of app
                // Use the dynamic threshold when enabled so the display matches
                // the actual operative value used at cycle time
                BigDecimal _eto2  = atomicState.lastEto as BigDecimal
                BigDecimal _base2 = atomicState.lastEtInputs?.baseline as BigDecimal
                int thresh = dynamicSoilThreshold(z, _eto2 ?: 0G, _base2 ?: 0G)
                def stale   = isSensorStale(z) ? " ⚠️stale" : ""
                def orchk   = safeGetAttr(s, "orphaned")
                def orphStr = (orchk == true || orchk?.toString() == "true") ? " ⛔orphaned" : ""
                def mark    = reading != null ?
                    (reading <= thresh ? " <font color='orange'>dry — watering needed</font>" :
                     " <font color='green'>ok (above ${thresh}% threshold)</font>") : ""
                sb.append("&nbsp;&nbsp;<b>${n}:</b> ${reading != null ? reading+'%' : '—'}${mark}${stale}${orphStr}<br>")
            }
        }
    }
    return sb.toString()
}

private String zoneLineSummary(int z) {
    def plant  = settings["zonePlant_${z}"]  ?: "?"
    def nozzle = settings["zoneNozzle_${z}"] ?: "?"
    def rt     = settings["zoneBaseRuntime_${z}"] ?: "45"
    def sensor = settings["zoneSoilSensor_${z}"] != null
    def stale  = (sensor && isSensorStale(z)) ? " | <font color='orange'>sensor stale</font>" : ""
    int credit = getManualCreditMinutes(z)
    def creditStr = credit > 0 ? " | <font color='blue'>−${credit}min manual credit</font>" : ""
    // Show dynamic threshold label when enabled so zone list matches actual operative value
    String threshStr = ""
    if (sensor) {
        BigDecimal _e = atomicState.lastEto as BigDecimal
        BigDecimal _b = atomicState.lastEtInputs?.baseline as BigDecimal
        int dynT = dynamicSoilThreshold(z, _e ?: 0G, _b ?: 0G)
        int fixT = (settings["zoneSoilThreshold_${z}"] ?: "45") as int
        threshStr = (settings["dynamicThreshEnabled_${z}"] && dynT != fixT) ?
            " | thresh ${fixT}%→${dynT}%" : " | thresh ${fixT}%"
    }
    return "${sanitizeHtml(plant as String)} | ${nozzle} | ${rt}min${threshStr}${stale}${creditStr}"
}

private String guardsLineSummary() {
    "Temp>${settings.guardTempMin?:'38'}°F | Rate<${settings.guardRainRate?:'0.09'}\"/hr | " +
    "Daily<${settings.guardRainDaily?:'0.25'}\" | Event<${settings.guardRainEvent?:'0.50'}\""
}

private String advancedLineSummary() {
    def adapt = settings.etUseAdaptive != false ? "Adaptive" : "Fixed"
    def lat   = location.latitude as BigDecimal
    def mon   = Calendar.getInstance(location.timeZone).get(Calendar.MONTH) + 1
    def base  = settings.etBaselineOverride ?:
                String.format("%.2f", (seasonalBaseline(lat, mon) as double))
    "${adapt} | ${base} in/day | ${settings.manualMonitorEnabled!=false ? 'Monitor ✓' : 'Monitor off'}"
}

private String sensorHealthDetail(int z) {
    def lastSeen = atomicState["sensorLastSeen_${z}"]
    if (!lastSeen) return ""
    int ageMins = ((now() - (lastSeen as Long)) / 60000).toInteger()
    return isSensorStale(z) ?
        "<br><font color='orange'>⚠️ Stale — last updated ${ageMins} min ago</font>" :
        "<br><small>Updated ${ageMins} min ago</small>"
}

private String logLevelName(int l) {
    switch(l) {
        case LOG_OFF:   return "Off"
        case LOG_ERROR: return "Error"
        case LOG_WARN:  return "Warn"
        case LOG_INFO:  return "Info"
        case LOG_DEBUG: return "Debug"
        case LOG_TRACE: return "Trace"
        default:        return "Info"
    }
}

private String monthName(int m) {
    ["January","February","March","April","May","June",
     "July","August","September","October","November","December"][m-1]
}

private String fmtCoord(BigDecimal lat, BigDecimal lon) {
    "${String.format('%.4f',lat.doubleValue())}°N, ${String.format('%.4f',Math.abs(lon.doubleValue()))}°${lon >= 0G ? 'E' : 'W'}"
}

// ═══════════════════════════════════════════════════════════════════════════════
// LIFECYCLE
// ═══════════════════════════════════════════════════════════════════════════════

void installed()   { logI "Installed ${APP_NAME} v${APP_VERSION}"; initialize() }
void updated()     { logI "Updated — re-initializing"; unsubscribe(); unschedule(); initialize() }
void uninstalled() { logI "Uninstalled — safety close"; closeAllValves() }

void initialize() {
    logI "Initializing v${APP_VERSION}"

    // One-time dry-down learner migration (version-gated). v2 fixed quantization
    // flicker (the deadband); v3 adds the post-watering settle blackout, because field
    // data showed the v2 rates re-learning to 17-24%/day from the post-irrigation
    // DRAINAGE cliff (real multi-point drops that legitimately pass the deadband but
    // reflect gravitational drainage, not plant use). Stored rates from either earlier
    // learner are contaminated and the slow baseline would take weeks to forget, so
    // clear all learner state once and re-learn under the current rules. Anchors
    // re-seed on the next sensor event.
    if (atomicState.ddLearnerVer == null || (atomicState.ddLearnerVer as int) < 4) {
        int zcMig = safeZoneCount()
        (1..zcMig).each { z ->
            ["ddLastVal_","ddLastTime_","ddRatePerDay_","ddRateBaseline_","ddSettleUntil_","ddSettleExt_"].each { k ->
                atomicState.remove("${k}${z}")
            }
        }
        atomicState.ddLearnerVer = 4
        logI "Dry-down learner v4: cleared rate state; re-learning with adaptive settle extension"
    }

    // Seed the predict-ahead interval timestamps once. Without this the minimum-interval
    // guard fails open on the first cycle after an upgrade (no zone has a recorded
    // watering time yet), which is exactly the night it is most needed. Seeding to now
    // is conservative and costs nothing: predict-ahead only fires on zones already ABOVE
    // threshold, and any zone that is genuinely dry still waters on the normal path.
    // Idempotent, and it never overwrites a real timestamp.
    if (atomicState.predictIntervalSeeded != true) {
        int zcSeed = safeZoneCount()
        (1..zcSeed).each { z ->
            if (atomicState["zoneLastWaterMs_${z}"] == null) atomicState["zoneLastWaterMs_${z}"] = now()
        }
        atomicState.predictIntervalSeeded = true
        logI "Predict-ahead interval: seeded last-watered timestamps so the minimum interval is active immediately"
    }

    // Cycle start scheduling: when sunrise-relative start is enabled, the dynamic
    // schedule is set by scheduleSunriseRelativeStart() (recomputed nightly in
    // midnightReset). Only use the fixed-time cron when sunrise-relative is OFF,
    // so the cycle isn't double-scheduled.
    if (settings.sunriseRelativeStart) {
        runIn(20, "scheduleSunriseRelativeStart")
        logI "Cycle start: sunrise-relative (offset ${settings.sunriseOffsetMins ?: 0} min) — dynamic schedule"
    } else if (settings.startTime) {
        schedule(settings.startTime, "irrigationCycle"); logI "Cycle at ${fmtTime(settings.startTime)}"
    }
    if (settings.failsafeTime){ schedule(settings.failsafeTime, "failsafeClose");   logI "Failsafe at ${fmtTime(settings.failsafeTime)}" }

    // Solar noon capture: 12:30 PM triggers dynamic offset calculation (pure math)
    schedule("0 30 12 * * ?", "scheduleSolarCapture")
    logI "Solar noon capture: dynamic from hub location (no internet)"

    // Periodic health check and midnight reset
    schedule("0 */30 * * * ?", "checkSensorHealth")
    schedule("0 2 0 * * ?",    "midnightReset")

    // Grass-seed germination cycles — only scheduled when a zone is actively seeding.
    // Hubitat's schedule() replaces by handler NAME, so three crons all pointing at
    // "seedCycleRun" would keep only the last. We use three distinct wrapper handlers
    // (one per slot), each of which just calls seedCycleRun(). Re-evaluated nightly by
    // midnightReset as phases advance / programs complete.
    if (anyZoneSeeding()) {
        def p0 = (SEED_CYCLE_TIMES[0] as String).split(":")
        def p1 = (SEED_CYCLE_TIMES[1] as String).split(":")
        def p2 = (SEED_CYCLE_TIMES[2] as String).split(":")
        schedule("0 ${p0[1] as int} ${p0[0] as int} * * ?", "seedCycleSlot0")
        schedule("0 ${p1[1] as int} ${p1[0] as int} * * ?", "seedCycleSlot1")
        schedule("0 ${p2[1] as int} ${p2[0] as int} * * ?", "seedCycleSlot2")
        logI "🌱 Seed cycles scheduled at ${SEED_CYCLE_TIMES.join(', ')} (zones in germination)"
    }

    // Daily NOAA forecast fetch (PoP, QPF, Tmax, Tmin) — only if NOAA enabled
    // Scheduled at 2 PM: stable forecast, well before the 4 AM irrigation window
    if (!isLocalOnlyMode() && settings.noaaFallbackEnabled) {
        schedule("0 0 14 * * ?", "fetchNoaaDailyForecast")
        logI "NOAA daily forecast fetch scheduled at 2:00 PM"
        // Also fetch immediately on init so data is available right away
        runIn(15, "fetchNoaaDailyForecast")
        // Annual ET normals staleness check — Jan 15 at 3 AM
        // Offset from daily forecast fetch to avoid simultaneous NOAA API calls
        schedule("0 0 3 15 1 ?", "checkEtNormalsStaleness")
        logI "ET normals staleness check scheduled annually (Jan 15 03:00)"
    }
    // Run ET normals staleness check at startup (runs immediately, handles local-only gating internally)
    runIn(30, "refreshEtNormalsIfStale")

    // Subscribe to weather station for mid-cycle guard evaluation
    // AND to track last-seen timestamp for stationStaleMins check
    if (settings.weatherStation) {
        // Resolve actual attribute name from user mapping — may differ from "temperature"
        String tempAttrResolved = resolvedAttr("attrTemp", "temperature")
        if (settings.cancelOnGuardFail) {
            subscribe(settings.weatherStation, tempAttrResolved,  "guardCheckHandler")
            subscribe(settings.weatherStation, resolvedAttr("attrRainRate","rainRate"),  "guardCheckHandler")
            subscribe(settings.weatherStation, resolvedAttr("attrRainDay","rainDaily"),  "guardCheckHandler")
        }
        // Track mapped temperature attribute changes to know station is alive
        subscribe(settings.weatherStation, tempAttrResolved, "weatherStationHandler")

        // Seed stationLastSeen from the station's current attribute value
        // Pressure is tracked via a SEPARATE device (see pressureSensor below)
        def tempAttr = resolvedAttr("attrTemp", "temperature")
        def curTemp  = safeGetAttr(settings.weatherStation, tempAttr)
        if (curTemp != null) {
            atomicState.stationLastSeen = now()
            logI "Station seeded: current ${tempAttr} = ${curTemp}${location.temperatureScale == 'C' ? '°C' : '°F'}"
        } else {
            logD "Station not seeded — no current value for '${tempAttr}' (check attribute mapping)"
        }
    }

    // Barometric pressure subscription (separate device from weather station)
    // On Ecowitt: the GW2000 gateway has its own indoor pressure sensor — a different Hubitat device
    if (settings.pressureSensor && settings.pressureAttr &&
        settings.pressureAttr != "(not available)" && settings.pressureTrendGuardEnabled) {
        subscribe(settings.pressureSensor, settings.pressureAttr, "pressureEventHandler")
        // Seed pressure history with current reading
        def curP = safeGetAttrBD(settings.pressureSensor, settings.pressureAttr)
        if (curP != null && curP > 26G && curP < 32G) {
            def history = atomicState.pressureHistory ?: []
            history << [t: now(), p: curP]
            atomicState.pressureHistory = history
            logI "Pressure sensor subscribed: ${settings.pressureAttr} on '${settings.pressureSensor.displayName}' — seeded at ${curP} inHg"
        }
    } else if (settings.pressureTrendGuardEnabled && !settings.pressureSensor) {
        logW "Storm approach guard is enabled but no pressure sensor device is configured — go to Weather Settings"
    }

    // Subscribe to soil sensors to track last-seen timestamps
    // Also seed last-seen from current value to avoid "stale" on fresh install
    int zc = safeZoneCount()
    (1..zc).each { z ->
        def sensor = settings["zoneSoilSensor_${z}"]
        if (!sensor) return
        subscribe(sensor, "humidity", "soilSensorHandler")
        // Also subscribe to the raw analog reading (soilAD) as a LIVENESS signal.
        // The Ecowitt driver only emits a "humidity" event when the integer moisture
        // PERCENTAGE changes — on stable soil that can be far longer than the stale
        // threshold, causing false "sensor stale" flapping even though the sensor is
        // reporting fine. soilAD updates on every sensor transmission (~every 60-90s),
        // so it's the correct signal for "is the sensor alive?". We update last-seen
        // from it but do NOT treat it as a moisture value.
        def liveAttrs = getDeviceAttrs(sensor)
        if ("soilAD" in liveAttrs) {
            subscribe(sensor, "soilAD", "soilLivenessHandler")
            logD "Subscribed soilAD liveness zone ${z}"
        }
        logD "Subscribed soil sensor zone ${z}"

        // Subscribe to orphaned attribute — Ecowitt driver sets this true when
        // the gateway loses RF contact with a sensor. We mark it stale immediately
        // rather than waiting for the age threshold to expire.
        def sensorAttrs = getDeviceAttrs(sensor)
        if ("orphaned" in sensorAttrs) {
            subscribe(sensor, "orphaned", "soilOrphanedHandler")
            logD "Subscribed orphaned attribute zone ${z}"
        }

        // Seed last-seen from the current attribute value so the app doesn't show a
        // false stale immediately after install/reboot. We seed even if the "orphaned"
        // flag is currently set, because the GW2000 raises that flag transiently while
        // the sensor is still reporting — and the device still holds a current humidity
        // value we can trust. A genuinely-dead sensor will simply go stale on schedule
        // when no further soilAD/humidity events arrive.
        def orphanedNow = safeGetAttr(sensor, "orphaned")
        if (orphanedNow == true || orphanedNow == "true") {
            logD "Soil sensor zone ${z} shows orphaned on init — seeding from current value anyway (GW2000 flag is transient)"
        }

        def curHumidity = safeGetAttr(sensor, "humidity")
        if (curHumidity != null) {
            try {
                BigDecimal val = curHumidity as BigDecimal
                if (val >= 0G && val <= 100G) {
                    atomicState["sensorLastSeen_${z}"]  = now()
                    atomicState["sensorLastValue_${z}"] = val
                    logD "Soil sensor zone ${z} seeded: ${val}%"
                }
            } catch (e) { logD "Soil sensor zone ${z} seed failed: ${e.message}" }
        }

        // For WH52: also subscribe to soil temperature if mapped
        def stAttr = settings["zoneSoilTempAttr_${z}"]
        if (stAttr && stAttr != "(not available)") {
            subscribe(sensor, stAttr, "soilTempEventHandler")
            logD "Subscribed soil temp zone ${z}: attr=${stAttr}"
        }
    }

    // Subscribe to valve/switch devices for manual activation monitoring
    if (settings.manualMonitorEnabled != false) {
        (1..zc).each { z ->
            def valve = settings["zoneValve_${z}"]
            if (!valve) return
            if (valve.hasCommand("open")) subscribe(valve, "valve",  "valveEventHandler")
            else                          subscribe(valve, "switch", "valveEventHandler")
            logD "Subscribed valve zone ${z}: ${valve.displayName}"
        }
    }

    scheduleLogAutoReset()

    logI "Init complete — Mode: ${isLocalOnlyMode() ? 'LOCAL-ONLY' : (settings.noaaFallbackEnabled ? 'NOAA fallback' : 'station-only')} | " +
         "Manual monitor: ${settings.manualMonitorEnabled != false ? 'enabled' : 'disabled'}"

    // Clear any stale failsafe flag from before v1.9.0 or from a previous session
    if (!atomicState.cycleRunning) atomicState.failsafeTriggered = false

    // ── Hub reboot recovery: subscribe to systemStart ───────────────────────
    subscribe(location, "systemStart", "hubRestartHandler")

    // ── Leak sensor subscription ─────────────────────────────────────────────
    if (settings.leakSensor) {
        subscribe(settings.leakSensor, "water", "leakSensorHandler")
        logI "Leak sensor subscribed: ${settings.leakSensor.displayName}"
    }

    // ── Station battery subscription ─────────────────────────────────────────
    if (settings.stationBatteryDevice) {
        subscribe(settings.stationBatteryDevice, "battery", "stationBatteryHandler")
    }

    // ── Mode change subscription (for mode-triggered manual run) ────────────
    if (settings.modeRunMode && settings.modeRunZone) {
        subscribe(location, "mode", "modeChangedHandler")
        logI "Mode-triggered run: zone ${settings.modeRunZone} when mode → ${settings.modeRunMode}"
    }

    // ── Heat-stress syringing schedule ───────────────────────────────────────
    if (settings.syringeEnabled) {
        // Adaptive window: cheap 15-min checks all day; the handler no-ops outside the
        // afternoon window or once the daily latch is set. Distinct handler name so
        // schedule() replace-by-name cannot collide with heatStressSyringeRun itself.
        schedule("0 0/15 * * * ?", "syringeWindowCheck")
        logI "Heat-stress syringe: adaptive 15-min checks within the afternoon window"
    }

    // Midday demand-based moisture top-up — scheduled if any zone opted in.
    int zcMid = safeZoneCount()
    boolean anyMidday = (1..zcMid).any { settings["zoneMiddayCheck_${it}"] }
    if (anyMidday && settings.middayCheckTime) {
        schedule(settings.middayCheckTime, "middayMoistureCheck")
        logI "🌡️ Midday moisture check scheduled at ${fmtTime(settings.middayCheckTime)}"
    }

    // ── atomicState pruning — remove orphaned zone keys above current zoneCount
    (1..MAX_ZONES).each { z ->
        if (z > safeZoneCount()) {
            ["zoneLastRun_","lastRuntime_","zoneLastSoil_","sensorLastSeen_",
             "sensorLastValue_","soilTempF_","valveLastManualOpen_",
             "valveLastManualDurSec_","lastGallons_","lastPenetrationDepth_",
             "consecutiveSensorSkips_","orphanSince_"].each { k -> atomicState.remove("${k}${z}") }
        }
    }

    // ── Firmware version check ────────────────────────────────────────────────
    try {
        def hubVer = location.hub?.firmwareVersionString ?: ""
        if (hubVer && hubVer < MIN_HE_VERSION) {
            logW "Hub firmware ${hubVer} is below recommended minimum ${MIN_HE_VERSION}. " +
                 "Some features may not work correctly. Consider updating Hubitat firmware."
        }
    } catch (Exception e) { logD "Firmware check: ${e.message}" }

    checkPauseAutoResume()

    // Reflect current state in the installed-apps list label
    updateAppLabel()
}

// ═══════════════════════════════════════════════════════════════════════════════
// SENSOR HEALTH TRACKING
// ═══════════════════════════════════════════════════════════════════════════════

void soilSensorHandler(evt) {
    // Fired on every soil sensor humidity event — tracks last-seen per zone.
    String evtDevId = evt.deviceId?.toString()
    int zc = safeZoneCount()
    (1..zc).each { z ->
        def sensor = settings["zoneSoilSensor_${z}"]
        if (!sensor || sensor.id?.toString() != evtDevId) return
        try {
            def val = evt.value?.toBigDecimal()
            if (val != null && val >= 0G && val <= 100G) {
                atomicState["sensorLastSeen_${z}"]  = now()
                atomicState["sensorLastValue_${z}"] = val
                updateDryDownRate(z, val)   // water-balance: learn the drying slope
                logT "Soil zone ${z}: ${val}%"
            } else {
                logW "Soil zone ${z}: invalid humidity '${evt.value}' — not stored"
            }
        } catch (e) {
            logW "soilSensorHandler zone ${z}: ${e.message}"
        }
    }
}

// Liveness handler — fired on the raw soilAD analog reading, which transmits on every
// sensor cycle (~60-90s) regardless of whether the moisture percentage changed. This
// keeps sensorLastSeen current so a stable-moisture sensor isn't falsely flagged stale.
// It deliberately does NOT update sensorLastValue (that stays the calibrated %).
void soilLivenessHandler(evt) {
    String evtDevId = evt.deviceId?.toString()
    int zc = safeZoneCount()
    (1..zc).each { z ->
        def sensor = settings["zoneSoilSensor_${z}"]
        if (!sensor || sensor.id?.toString() != evtDevId) return
        atomicState["sensorLastSeen_${z}"] = now()
        logT "Soil zone ${z}: liveness (soilAD=${evt.value})"
    }
}

void soilTempEventHandler(evt) {
    // Fired when a WH52 soil temperature attribute changes — store per zone
    String evtDevId = evt.deviceId?.toString()
    int zc = safeZoneCount()
    (1..zc).each { z ->
        def sensor = settings["zoneSoilSensor_${z}"]
        if (!sensor || sensor.id?.toString() != evtDevId) return
        try {
            BigDecimal tempRaw = evt.value?.toBigDecimal()
            if (tempRaw != null) {
                // Convert from hub's scale to °F for consistent storage
                BigDecimal tempF = stationTempToF(tempRaw)
                atomicState["soilTempF_${z}"] = tempF
                logT "Soil temp zone ${z}: ${tempF}°F"
            }
        } catch (e) {
            logW "soilTempEventHandler zone ${z}: ${e.message}"
        }
    }
}

void soilOrphanedHandler(evt) {
    // The GW2000 raises "orphaned" when it thinks it lost RF contact — but in practice
    // it flips this transiently during normal housekeeping even while the sensor is
    // still reporting soilAD/humidity every ~2 min (confirmed in device event logs).
    // So we do NOT instantly erase last-seen on an orphan event (that produced false
    // "never seen" stales on a healthy sensor). Instead we let the real data flow be
    // the source of truth: soilLivenessHandler keeps last-seen current from soilAD, and
    // isSensorStale() applies the normal age threshold. A genuine outage still goes
    // stale on schedule because soilAD stops arriving; a false blip with live data does
    // not. We only record the orphan for visibility and to surface a hint if it persists.
    boolean isOrphaned = (evt.value == true || evt.value?.toString() == "true")
    String evtDevId = evt.deviceId?.toString()
    int zc = safeZoneCount()
    if (!isOrphaned) {
        // Recovery is itself proof of life — re-seed so any stale flag clears at once.
        (1..zc).each { z ->
            def sensor = settings["zoneSoilSensor_${z}"]
            if (!sensor || sensor.id?.toString() != evtDevId) return
            atomicState["sensorLastSeen_${z}"] = now()
            atomicState.remove("orphanSince_${z}")
        }
        logI "Sensor no longer orphaned: device ${evt.deviceId} — last-seen re-seeded"
        return
    }
    (1..zc).each { z ->
        def sensor = settings["zoneSoilSensor_${z}"]
        if (!sensor || sensor.id?.toString() != evtDevId) return
        def name = settings["zoneName_${z}"] ?: "Zone ${z}"
        // Record when the orphan started but do NOT erase last-seen. If soilAD keeps
        // flowing, soilLivenessHandler will keep the sensor fresh and this is a no-op.
        if (!atomicState["orphanSince_${z}"]) atomicState["orphanSince_${z}"] = now()
        logD "GW2000 reports Zone ${z} (${name}) orphaned — relying on soilAD liveness; " +
             "will go stale only if data actually stops (age threshold)."
    }
}

void checkSensorHealth() {
    int zc = safeZoneCount()
    (1..zc).each { z ->
        if (!settings["zoneSoilSensor_${z}"]) return
        if (isSensorStale(z)) {
            def lastSeen = atomicState["sensorLastSeen_${z}"]
            int ageMins  = lastSeen ? ((now() - (lastSeen as Long)) / 60000).toInteger() : -1
            def behavior = settings["sensorFailBehavior_${z}"] ?: SENSOR_FAIL_SKIP
            def name     = settings["zoneName_${z}"] ?: "Zone ${z}"
            logW "Sensor stale: Zone ${z} (${name}) — ${ageMins < 0 ? 'never seen' : ageMins+'min ago'} | behavior: ${behavior}"
        }
    }
    // Runaway valve detection: if activeZone has been open longer than expected, abort
    if (atomicState.cycleRunning && atomicState.activeZone) {
        int az = atomicState.activeZone as int
        def zoneOpenSince = atomicState["zoneOpenSince_${az}"]
        if (zoneOpenSince) {
            int baseRt = (settings["zoneBaseRuntime_${az}"] ?: "45") as int
            long maxOpen = (baseRt * RUNAWAY_MULTIPLIER * 60000L)
            if ((now() - (zoneOpenSince as Long)) > maxOpen) {
                logW "RUNAWAY VALVE DETECTED: Zone ${az} has been open ${(now()-(zoneOpenSince as Long))/60000} min " +
                     "(expected max ${baseRt*RUNAWAY_MULTIPLIER} min) — aborting"
                sendNotification("notifyOnRunaway",
                    "🚨 RUNAWAY VALVE: Zone ${az} (${settings["zoneName_${az}"] ?: 'Zone '+az}) open too long — closing all valves")
                atomicState.abortReason = "Runaway valve zone ${az}"
                atomicState.abortCycle  = true
                runIn(1, "abortCycleNow")
            }
        }
    }
    // Battery check
    if (settings.stationBatteryDevice && settings.stationBatteryThreshold) {
        def bat = safeGetAttrBD(settings.stationBatteryDevice, "battery")
        if (bat != null && bat < (settings.stationBatteryThreshold as BigDecimal)) {
            logW "Station battery low: ${bat}% (threshold ${settings.stationBatteryThreshold}%)"
            sendNotification("notifyOnBattery",
                "🔋 Station battery low: ${bat}% — replace batteries soon")
        }
    }
    // Station stale alert (once per hour max)
    if (settings.notifyDevice && settings.notifyOnStale && !isLocalOnlyMode() && settings.noaaFallbackEnabled) {
        def lastSeen = atomicState.stationLastSeen
        if (lastSeen && (now() - (lastSeen as Long)) > 3600000L) {
            long lastAlertedStale = atomicState.lastStaleNotification ?: 0L
            if ((now() - lastAlertedStale) > 3600000L) {
                sendNotification("notifyOnStale",
                    "⚠️ Weather station stale — last seen ${((now()-(lastSeen as Long))/60000).toInteger()} min ago")
                atomicState.lastStaleNotification = now()
            }
        }
    }
    // Also check weather station freshness — update label to reflect current state
    updateAppLabel()
}

private boolean isSensorStale(int z) {
    if (!settings["zoneSoilSensor_${z}"]) return false
    def lastSeen = atomicState["sensorLastSeen_${z}"]
    if (lastSeen == null) return true  // never reported = stale
    long maxAgeMs = ((settings["sensorMaxAgeMins_${z}"] ?: "60") as int) * 60000L
    return (now() - (lastSeen as Long)) > maxAgeMs
}

// Returns [shouldWater:bool, reason:String, soilPct:Number|null, thresh:int]
// eto + baseline are passed in so dynamicSoilThreshold() can adjust the threshold
// based on today's ET demand. Pass 0G for both on first run or when unavailable.
private Map evaluateSoilForZone(int z, BigDecimal eto = 0G, BigDecimal baseline = 0G) {
    def sensor = settings["zoneSoilSensor_${z}"]
    def name   = settings["zoneName_${z}"] ?: "Zone ${z}"
    int thresh = dynamicSoilThreshold(z, eto, baseline)

    if (!sensor) return [shouldWater:true, reason:"no sensor — watering", soilPct:null]

    // ── WH52 cold soil guard — skip if soil too cold for roots to absorb water ─
    if (settings["coldSoilGuardEnabled_${z}"] != false && settings["zoneSoilTempAttr_${z}"] &&
        settings["zoneSoilTempAttr_${z}"] != "(not available)") {
        BigDecimal soilTempF  = atomicState["soilTempF_${z}"]
        BigDecimal coldThresh = (settings["coldSoilTempF_${z}"] ?: "50") as BigDecimal
        if (soilTempF != null && soilTempF <= coldThresh) {
            return [shouldWater:false,
                    reason:"Soil too cold: ${soilTempF}°F ≤ ${coldThresh}°F — roots not absorbing well",
                    soilPct:null]
        }
    }

    def reading  = safeGetAttr(sensor, "humidity")  // defensive — avoids uncaught exception on unsupported attribute
    boolean stale = isSensorStale(z)
    def behavior  = settings["sensorFailBehavior_${z}"] ?: SENSOR_FAIL_SKIP

    // Fresh sensor with valid reading
    if (reading != null && !stale) {
        atomicState["zoneLastSoil_${z}"] = reading
        // Sensor is working — reset the consecutive sensor-fail skip counter (drought guard)
        if (atomicState["consecutiveSensorSkips_${z}"]) atomicState.remove("consecutiveSensorSkips_${z}")
        boolean dry = (reading as int) <= thresh
        String dynNote = (settings["dynamicThreshEnabled_${z}"] && thresh != ((settings["zoneSoilThreshold_${z}"] ?: "45") as int)) ?
            " [dynamic: base ${settings["zoneSoilThreshold_${z}"] ?: 45}% → ${thresh}%]" : ""
        return [shouldWater:dry,
                reason: dry ? "soil ${reading}% ≤ ${thresh}% (dry)${dynNote}" : "soil ${reading}% > ${thresh}% (wet)${dynNote}",
                soilPct:reading, thresh:thresh, fresh:true]
    }

    // Sensor failed — apply configured behavior
    logW "Zone ${z} (${name}): sensor stale/null — applying behavior: ${behavior}"

    // ── Drought protection override ──────────────────────────────────────────
    // Safety net against silent lawn death: if a SKIP-behavior zone has been
    // skipped due to sensor failure for DROUGHT_SKIP_LIMIT consecutive days,
    // override and water at base runtime so the lawn doesn't die while the
    // sensor is broken. Only applies to SKIP behavior (WATER already waters,
    // LAST uses prior data). Enabled by default; can be disabled per setting.
    if (behavior == SENSOR_FAIL_SKIP && settings.droughtProtectEnabled != false) {
        int skips = ((atomicState["consecutiveSensorSkips_${z}"] ?: 0) as int) + 1
        atomicState["consecutiveSensorSkips_${z}"] = skips
        if (skips >= DROUGHT_SKIP_LIMIT) {
            logW "Zone ${z} (${name}): DROUGHT OVERRIDE — sensor failed ${skips} days straight, watering to prevent lawn loss"
            sendNotification("droughtProtectEnabled",
                "🌵 Zone ${sanitizeHtml(name)}: soil sensor has failed for ${skips} days. " +
                "Watering anyway (drought protection). Check the sensor.")
            atomicState.remove("consecutiveSensorSkips_${z}")  // reset after override fires
            return [shouldWater:true,
                    reason:"DROUGHT OVERRIDE — sensor failed ${skips} days, watering to protect lawn",
                    soilPct:null]
        }
        return [shouldWater:false,
                reason:"sensor stale — skipping (safe) [${skips}/${DROUGHT_SKIP_LIMIT} before drought override]",
                soilPct:null]
    }

    switch (behavior) {
        case SENSOR_FAIL_SKIP:
            return [shouldWater:false, reason:"sensor stale — skipping (safe)", soilPct:null]
        case SENSOR_FAIL_WATER:
            return [shouldWater:true, reason:"sensor stale — watering (configured)", soilPct:null]
        case SENSOR_FAIL_LAST:
            def lastVal = atomicState["sensorLastValue_${z}"]
            if (lastVal != null) {
                boolean dry = (lastVal as int) <= thresh
                return [shouldWater:dry,
                        reason:"sensor stale — using last known ${lastVal}% vs ${thresh}%",
                        soilPct:lastVal]
            }
            return [shouldWater:false, reason:"sensor stale + no prior value — skipping", soilPct:null]
        default:
            return [shouldWater:false, reason:"sensor stale — unknown behavior", soilPct:null]
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MANUAL VALVE MONITORING
// ═══════════════════════════════════════════════════════════════════════════════

void valveEventHandler(evt) {
    boolean isOpen  = (evt.value in ["open","on"])
    boolean isClose = (evt.value in ["closed","off"])
    int z = findZoneForDevice(evt.deviceId)
    if (z < 1) { logD "valveEventHandler: device ${evt.deviceId} not matched to any zone"; return }
    def name = settings["zoneName_${z}"] ?: "Zone ${z}"

    if (isOpen) {
        // Ignore opens that are part of this app's own cycle or manual test
        if (atomicState.cycleRunning && atomicState.activeZone == z) {
            logD "valveEventHandler: zone ${z} auto-cycle open — ignoring"
            return
        }
        if (atomicState.manualRunZone == z) {
            logD "valveEventHandler: zone ${z} app manual open — ignoring"
            return
        }
        // External manual open detected
        atomicState["valveManualOpenAt_${z}"] = now()
        logI "External manual open detected: Zone ${z} (${name}) — tracking"
    }

    if (isClose) {
        def openAt = atomicState["valveManualOpenAt_${z}"]
        if (!openAt) return  // wasn't tracking this open
        long durMs   = now() - (openAt as long)
        int durSec   = (durMs / 1000).toInteger()
        int minRunSec= ((settings.manualCreditMinRun ?: "3") as int) * 60
        atomicState.remove("valveManualOpenAt_${z}")
        atomicState["valveLastManualOpen_${z}"]   = openAt
        atomicState["valveLastManualDurSec_${z}"] = durSec

        if (durSec >= minRunSec && settings.manualMonitorEnabled != false) {
            // Credit is granted — duration stored in valveLastManualDurSec_${z}.
            // At cycle time, getManualCreditMinutes() calculates remaining deficit.
            // No separate expiry timestamp needed — staleness is checked against
            // valveLastManualOpen_${z} vs the manualCreditHours window.
            logI "Manual credit: Zone ${z} (${name}) ran ${durSec}s (${(durSec/60).toInteger()}min) — " +
                 "will reduce tonight's runtime by up to ${(durSec/60).toInteger()}min " +
                 "if within the ${settings.manualCreditHours ?: 4}h window"
        } else {
            logD "Zone ${z} manual run ${durSec}s < min ${minRunSec}s — no credit"
        }
    }
}

private int findZoneForDevice(def evtDeviceId) {
    // evt.deviceId is a Long; device.id is a String — compare as strings
    String target = evtDeviceId?.toString()
    if (!target) return -1
    int zc = safeZoneCount()
    for (int z = 1; z <= zc; z++) {
        def valve = settings["zoneValve_${z}"]
        if (valve && valve.id?.toString() == target) return z
    }
    return -1
}

// Returns minutes already watered manually that count toward tonight's deficit.
// Returns 0 if no qualifying manual run, or if the run is outside the staleness window.
// The credit window (manualCreditHours) is how long ago a manual run still counts —
// not how long the zone is skipped. The actual skip/reduction decision is made in
// irrigationCycle() based on the minutes remaining after subtracting the credit.
private int getManualCreditMinutes(int z) {
    if (settings.manualMonitorEnabled == false) return 0
    def openAt  = atomicState["valveLastManualOpen_${z}"]
    def durSec  = atomicState["valveLastManualDurSec_${z}"]
    if (!openAt || !durSec) return 0

    int creditHours = (settings.manualCreditHours ?: "4") as int
    long windowMs   = creditHours * 3600000L
    if ((now() - (openAt as Long)) > windowMs) return 0  // run is too old to count

    int minRunSec = ((settings.manualCreditMinRun ?: "3") as int) * 60
    if ((durSec as int) < minRunSec) return 0  // too short to earn credit

    return Math.max(0, ((durSec as int) / 60).toInteger())
}

// Kept for UI display (badge on main page, diagnostics)
private boolean isZoneCredited(int z) { return getManualCreditMinutes(z) > 0 }

// ═══════════════════════════════════════════════════════════════════════════════
// SOLAR NOON SCHEDULING — dynamic, pure math, no internet
// ═══════════════════════════════════════════════════════════════════════════════

void scheduleSolarCapture() {
    // Triggered at 12:30 PM local. Schedules captureSolarNoon() at the precise
    // solar-noon time, which is the midpoint of Hubitat's sunrise/sunset for today.
    int snMin  = calcSolarNoonMinutes()  // minutes since midnight local
    def cal    = Calendar.getInstance(location.timeZone)
    int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    int deltaMin = snMin - nowMin  // minutes until solar noon (negative = already past)

    if (deltaMin >= -5 && deltaMin <= 120) {
        // Within 5 min past or 2 hours future — schedule precisely
        int delaySec = Math.max(10, deltaMin * 60)
        runIn(delaySec, "captureSolarNoon")
        logD "Solar noon in ${deltaMin} min — capture in ${delaySec}s"
    } else if (deltaMin > 120) {
        // More than 2 hours away — schedule for the gap
        runIn(deltaMin * 60, "captureSolarNoon")
        logD "Solar noon in ${deltaMin} min — scheduled via runIn"
    } else {
        // Solar noon already passed today (deltaMin < -5) — capture now
        logD "Solar noon passed ${Math.abs(deltaMin)} min ago — capturing current reading"
        runIn(5, "captureSolarNoon")
    }
}

void captureSolarNoon() {
    def wx = settings.weatherStation
    if (!wx) { logW "captureSolarNoon: no station"; return }
    def attrName = resolvedAttr("attrSolar", "solarRadiation")
    if (attrName == "(not available)") { logD "captureSolarNoon: solar not mapped"; return }
    def val = safeGetAttr(wx, attrName)
    if (val == null) { logW "captureSolarNoon: attribute '${attrName}' returned null"; return }

    BigDecimal wm2 = val as BigDecimal

    // Sanity check: the solar constant at top of atmosphere is 1,361 W/m².
    // At Earth's surface on the clearest day, never exceeds ~1,200 W/m².
    // If the reading exceeds 1,350 W/m², the wrong attribute is almost certainly
    // mapped — likely 'illuminance' (in lux) instead of 'solarRadiation' (W/m²).
    // Lux and W/m² are not interchangeable: 1 W/m² ≈ 120 lux.
    // An illuminance reading of 9,000 lux treated as 9,000 W/m² produces ~30× ET.
    if (wm2 > 1350G) {
        logE "⛔ Solar reading rejected: ${wm2} W/m² exceeds physical maximum (~1,350 W/m²). " +
             "Wrong attribute mapped — check Weather Settings → Attribute Mapping → Solar Radiation. " +
             "You may have 'illuminance' (lux) mapped instead of 'solarRadiation' (W/m²). " +
             "Convert: illuminance_lux ÷ 120 ≈ W/m². Do NOT use the illuminance attribute directly."
        atomicState.solarReadingError = "Rejected ${wm2} W/m² at ${ts()} — exceeds 1,350 limit. Check solar attribute mapping."
        return  // do not store — protect ET calculation
    }

    if (wm2 > 0G) {
        // Solar radiation sanity check (v3.0.0)
        int doy3 = Calendar.getInstance(location.timeZone).get(Calendar.DAY_OF_YEAR)
        boolean sanityOk = solarSanityCheck(wm2, doy3)
        atomicState.solarNoonReading  = wm2
        atomicState.solarNoonSanityOk = sanityOk
        atomicState.solarNoonDate     = new Date().format("yyyy-MM-dd", location.timeZone)
        atomicState.remove("solarReadingError")
        logI "Solar noon captured: ${wm2} W/m²${sanityOk ? '' : ' ⚠️ SUSPECT — Hargreaves fallback active'}"
    } else {
        logW "captureSolarNoon: value=${wm2} — not stored (zero or null)"
    }
}

// Solar noon = midpoint between hub's sunrise and sunset.
// location.sunrise and location.sunset are Date properties computed by the hub
// from its configured location — no internet, no custom math needed.
// Reference: https://docs2.hubitat.com/en/developer/location-object
private int calcSolarNoonMinutes(BigDecimal latDeg = null, BigDecimal lonDeg = null, int doy = 0) {
    try {
        Date rise = location.sunrise
        Date set  = location.sunset
        if (!rise || !set) {
            logW "calcSolarNoonMinutes: location.sunrise/sunset unavailable — defaulting to 13:00"
            return 780
        }
        long midpointMs = ((rise.time as long) + (set.time as long)) / 2L
        def cal = Calendar.getInstance(location.timeZone)
        cal.setTimeInMillis(midpointMs)
        int localMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        logT "Solar noon (location.sunrise/sunset midpoint): ${String.format('%02d:%02d', localMin.intdiv(60), localMin%60)} local"
        return localMin
    } catch (Exception e) {
        logW "calcSolarNoonMinutes: ${e.message} — defaulting to 13:00"
        return 780
    }
}

// Seasonal ET baseline from latitude + month — pure lookup, no internet
private BigDecimal seasonalBaseline(BigDecimal lat, int month) {
    // User manual override takes highest priority
    if (settings.etBaselineOverride) return settings.etBaselineOverride as BigDecimal

    // Dynamic baseline (fetched from NCEI normals API or built from observed data)
    // takes precedence over the embedded static table when available
    def dynMap = atomicState.dynamicEtBaseline as Map
    if (dynMap) {
        BigDecimal dynVal = dynMap[month.toString()] as BigDecimal
        if (dynVal && dynVal > 0G) {
            logT "Baseline [dynamic/${atomicState.etNormalsSource}]: month=${month} → ${dynVal} in/day"
            return dynVal
        }
    }

    // Static fallback — NOAA 1991-2020 normals embedded at compile time
    // intdiv() for true integer division (Groovy's / operator returns BigDecimal on int/int)
    // Math.max/min replaces .clamp() which is not available in Hubitat's Groovy sandbox
    int band = Math.max(25, Math.min(50, (lat.intValue().intdiv(5)) * 5))
    def row  = ET_BASELINE_BY_LAT[band] ?: ET_BASELINE_BY_LAT[45]
    BigDecimal base = row[month - 1] as BigDecimal
    logT "Baseline [static_table]: lat=${lat}°→band=${band}°N, month=${month} → ${base} in/day"
    return base
}

// ═══════════════════════════════════════════════════════════════════════════════
// ET NORMALS AUTO-REFRESH (v1.6.0)
// ═══════════════════════════════════════════════════════════════════════════════

// Checks whether the embedded 1991-2020 ET baseline table is approaching 10
// years old. If stale AND NOAA is enabled, attempts a live refresh via:
//   Step 1: NWS /points/{lat,lon} → observationStations URL
//   Step 2: First station in list  → ICAO code (e.g. "KORD")
//   Step 3: NCEI HOMR API          → GHCND station ID (e.g. "USW00024143")
//   Step 4: NCEI Data Service      → MLY-TMAX-NORMAL + MLY-TMIN-NORMAL (12 months)
//   Step 5: Hargreaves equation    → per-month ET in/day → atomicState.dynamicEtBaseline
//
// Fallback (Option B) if any step above fails:
//   Uses cached observed Tmax/Tmin (from daily NOAA fetch) to compute a
//   bias-correction ratio vs the static table, applied to all 12 months.
//
// seasonalBaseline() reads atomicState.dynamicEtBaseline first when set.
// Triggered at initialize() and annually each Jan 15 at 3 AM.

void refreshEtNormalsIfStale() {
    int currentYear = Calendar.getInstance(location.timeZone).get(Calendar.YEAR)
    boolean stale   = (currentYear >= (ET_NORMALS_VINTAGE_YEAR + ET_NORMALS_STALE_THRESHOLD_YEARS))
    logI "ET normals check: vintage=${ET_NORMALS_VINTAGE_YEAR}, year=${currentYear}, stale=${stale}"

    if (!stale) {
        if (!atomicState.dynamicEtBaseline) atomicState.etNormalsSource = "static_table"
        return
    }

    if (isLocalOnlyMode() || !settings.noaaFallbackEnabled) {
        logW "ET normals stale (vintage ${ET_NORMALS_VINTAGE_YEAR}) but NOAA disabled — using embedded table"
        atomicState.etNormalsSource = "static_table_stale"
        updateAppLabel()
        return
    }

    logI "ET normals stale — attempting NCEI refresh (Option C)"

    // Step 1: NWS points → observationStations URL
    def points = noaaFetch("https://api.weather.gov/points/${noaaCoord()}")
    String obsUrl = points?.properties?.observationStations
    if (!obsUrl) {
        logW "refreshEtNormalsIfStale: no observationStations URL from NWS — falling back to Option B"
        refreshEtNormalsFromObserved(); return
    }

    // Step 2: nearest station ICAO identifier
    def obsData  = noaaFetch(obsUrl)
    List features = obsData?.features as List
    if (!features) {
        logW "refreshEtNormalsIfStale: no station features in NWS response — falling back to Option B"
        refreshEtNormalsFromObserved(); return
    }
    String icaoId = null
    for (def feat : features) {
        String sid = feat?.properties?.stationIdentifier as String
        if (sid && sid.length() == 4) { icaoId = sid; break }
    }
    if (!icaoId) {
        logW "refreshEtNormalsIfStale: no valid ICAO ID in station list — falling back to Option B"
        refreshEtNormalsFromObserved(); return
    }
    logI "refreshEtNormalsIfStale: nearest station ICAO=${icaoId}"

    // Step 3: HOMR API → GHCND station ID
    String ghcndId = resolveGhcndId(icaoId)
    if (!ghcndId) {
        logW "refreshEtNormalsIfStale: HOMR could not resolve GHCND ID for ${icaoId} — falling back to Option B"
        refreshEtNormalsFromObserved(); return
    }
    logI "refreshEtNormalsIfStale: GHCND ID=${ghcndId}"
    atomicState.etNormalsGhcndId = "${icaoId} → ${ghcndId}"

    // Step 4: NCEI Data Service → monthly Tmax/Tmin normals
    String nceiUrl = "https://www.ncei.noaa.gov/access/services/data/v1" +
                     "?dataset=normals-monthly-1991-2020" +
                     "&stations=${ghcndId}" +
                     "&dataTypes=MLY-TMAX-NORMAL,MLY-TMIN-NORMAL" +
                     "&format=json" +
                     "&includeAttributes=false"
    List normalsRows = fetchNceiNormals(nceiUrl)
    if (!normalsRows) {
        logW "refreshEtNormalsIfStale: NCEI normals returned no data — falling back to Option B"
        refreshEtNormalsFromObserved(); return
    }

    // Step 5: convert Tmax/Tmin normals to per-month Hargreaves ET baseline
    Map dynBaseline = [:]
    BigDecimal lat = location.latitude as BigDecimal
    normalsRows.each { row ->
        try {
            String dateStr = (row?.DATE as String)?.trim()
            if (!dateStr) return
            int mon = dateStr.replaceAll(/^0/, "").toInteger()
            if (mon < 1 || mon > 12) return

            BigDecimal tMaxF = safeParseDecimal(row["MLY-TMAX-NORMAL"])
            BigDecimal tMinF = safeParseDecimal(row["MLY-TMIN-NORMAL"])
            if (tMaxF == null || tMinF == null || tMaxF <= tMinF) return

            // Representative DOY = 15th of the month
            Calendar cal = Calendar.getInstance(location.timeZone)
            cal.set(Calendar.MONTH, mon - 1)
            cal.set(Calendar.DAY_OF_MONTH, 15)
            int doy = cal.get(Calendar.DAY_OF_YEAR)

            BigDecimal Tmean = fToC((tMaxF + tMinF) / 2G)
            BigDecimal dT    = (fToC(tMaxF) - fToC(tMinF)).abs()
            BigDecimal Ra    = calcRa(degToRad(lat), doy)
            if (Ra <= 0G || dT <= 0G) return

            BigDecimal et0in = mmToIn(new BigDecimal(
                0.0023d * (Tmean + 17.8G).toDouble() *
                Math.sqrt(dT.toDouble()) * Ra.toDouble()
            ).setScale(3, BigDecimal.ROUND_HALF_UP).max(0G))

            dynBaseline[mon.toString()] = et0in
            logD "NCEI normals: month=${mon} Tmax=${tMaxF}°F Tmin=${tMinF}°F → ET₀=${et0in} in/day"
        } catch (Exception ex) {
            logW "refreshEtNormalsIfStale: row parse error: ${ex.message}"
        }
    }

    if (dynBaseline.size() < 10) {
        logW "refreshEtNormalsIfStale: only ${dynBaseline.size()}/12 months parsed — falling back to Option B"
        refreshEtNormalsFromObserved(); return
    }

    atomicState.dynamicEtBaseline    = dynBaseline
    atomicState.etNormalsSource      = "ncei_normals"
    atomicState.etNormalsRefreshedAt = now()
    logI "refreshEtNormalsIfStale: SUCCESS — ${dynBaseline.size()} months loaded from NCEI (${ghcndId})"
}

// Annual handler — scheduled Jan 15 at 3 AM
void checkEtNormalsStaleness() {
    logI "Annual ET normals staleness check triggered"
    refreshEtNormalsIfStale()
}

// Option B fallback: bias-correct the static table using observed Tmax/Tmin
private void refreshEtNormalsFromObserved() {
    BigDecimal tMaxF = atomicState.noaaCacheTmax as BigDecimal
    BigDecimal tMinF = atomicState.noaaCacheTmin as BigDecimal
    if (tMaxF == null || tMinF == null || tMaxF <= tMinF) {
        logW "refreshEtNormalsFromObserved: no valid cached Tmax/Tmin — keeping static table"
        atomicState.etNormalsSource = "static_table_stale"; return
    }

    BigDecimal lat = location.latitude as BigDecimal
    int currentMonth = Calendar.getInstance(location.timeZone).get(Calendar.MONTH) + 1
    int doy          = Calendar.getInstance(location.timeZone).get(Calendar.DAY_OF_YEAR)

    BigDecimal Tmean = fToC((tMaxF + tMinF) / 2G)
    BigDecimal dT    = (fToC(tMaxF) - fToC(tMinF)).abs()
    BigDecimal Ra    = calcRa(degToRad(lat), doy)
    if (Ra <= 0G || dT <= 0G) {
        logW "refreshEtNormalsFromObserved: invalid Ra/dT — keeping static table"
        atomicState.etNormalsSource = "static_table_stale"; return
    }

    BigDecimal observedEt = mmToIn(new BigDecimal(
        0.0023d * (Tmean + 17.8G).toDouble() *
        Math.sqrt(dT.toDouble()) * Ra.toDouble()
    ).setScale(3, BigDecimal.ROUND_HALF_UP).max(0G))

    // Bias-correction ratio: observed ET ÷ what the static table expected this month
    int band = Math.max(25, Math.min(50, (lat.intValue().intdiv(5)) * 5))
    def row  = ET_BASELINE_BY_LAT[band] ?: ET_BASELINE_BY_LAT[45]
    BigDecimal staticNow = row[currentMonth - 1] as BigDecimal
    if (!staticNow || staticNow == 0G) {
        logW "refreshEtNormalsFromObserved: static table month ${currentMonth} is zero — keeping static table"
        atomicState.etNormalsSource = "static_table_stale"; return
    }

    BigDecimal ratio = (observedEt / staticNow).setScale(4, BigDecimal.ROUND_HALF_UP)
    // Clamp to prevent bad data blowing up the baseline
    ratio = new BigDecimal(Math.max(0.5d, Math.min(2.0d, ratio.toDouble())))

    Map dynBaseline = [:]
    (1..12).each { mon ->
        BigDecimal sv     = row[mon - 1] as BigDecimal
        BigDecimal scaled = (sv * ratio).setScale(3, BigDecimal.ROUND_HALF_UP)
        dynBaseline[mon.toString()] = scaled
        logD "Observed fallback: month=${mon} ${sv} × ${ratio} = ${scaled} in/day"
    }

    atomicState.dynamicEtBaseline    = dynBaseline
    atomicState.etNormalsSource      = "observed_rolling"
    atomicState.etNormalsRefreshedAt = now()
    logI "refreshEtNormalsFromObserved: applied bias ratio ${ratio} to static table (observed ET=${observedEt} in/day)"
}

// NCEI HOMR: resolve ICAO code → GHCND station ID
// API docs: https://www.ncei.noaa.gov/access/homr/api — no key required
private String resolveGhcndId(String icaoCode) {
    if (!icaoCode) return null
    // Security: validate ICAO format before URL insertion
    if (!icaoCode.matches("[A-Z0-9]{3,5}")) {
        logW "resolveGhcndId: invalid ICAO format rejected: ${icaoCode}"; return null
    }
    try {
        String homrUrl = "https://www.ncei.noaa.gov/access/homr/services/station/search" +
                         "?qid=ICAO:${icaoCode}&headersOnly=false"
        Map homrData = null
        httpGet([uri    : homrUrl,
                 headers: ["User-Agent": "${APP_NAME}/${APP_VERSION}", "Accept": "application/json"],
                 timeout: 15,          // seconds — caps thread blockage if HOMR is slow
                 textParser: true]) { r ->
            if (r.status == 200) {
                homrData = new groovy.json.JsonSlurper().parseText(r.data.text) as Map
            } else {
                logW "resolveGhcndId: HOMR HTTP ${r.status} for ${icaoCode}"
            }
        }
        if (!homrData) return null
        List stations = homrData?.stationCollection?.stations as List
        if (!stations) { logW "resolveGhcndId: no stations in HOMR response for ${icaoCode}"; return null }
        for (def stn : stations) {
            List ids = stn?.identifiers as List
            if (!ids) continue
            def entry = ids.find { it?.idType == "GHCND" }
            if (entry?.id) return entry.id as String
        }
        logW "resolveGhcndId: no GHCND ID in HOMR response for ${icaoCode}"
        return null
    } catch (Exception e) { logW "resolveGhcndId: ${e.message}"; return null }
}

// NCEI Data Service: fetch monthly normals as JSON array
// API docs: https://www.ncei.noaa.gov/support/access-data-service-api-user-documentation
private List fetchNceiNormals(String url) {
    List result = null
    try {
        httpGet([uri    : url,
                 headers: ["User-Agent": "${APP_NAME}/${APP_VERSION}", "Accept": "application/json"],
                 timeout: 20,          // seconds — NCEI can be slow; caps worst-case thread blockage
                 textParser: true]) { r ->
            if (r.status == 200) {
                def parsed = new groovy.json.JsonSlurper().parseText(r.data.text)
                if (parsed instanceof List) {
                    result = parsed as List
                } else {
                    logW "fetchNceiNormals: unexpected response type (expected JSON array)"
                }
            } else {
                logW "fetchNceiNormals: NCEI HTTP ${r.status}"
            }
        }
    } catch (Exception e) { logW "fetchNceiNormals: ${e.message}" }
    return result
}

// Safe decimal parser — returns null for missing/fill values (NCEI uses -9999 etc.)
private BigDecimal safeParseDecimal(def val) {
    if (val == null) return null
    try {
        BigDecimal bd = new BigDecimal(val.toString().trim())
        if (bd <= -7000G) return null   // NCEI fill values: -9999, -8888, -7777
        return bd
    } catch (Exception e) { return null }
}

// ═══════════════════════════════════════════════════════════════════════════════
// APP LABEL — dynamic status badge in the Hubitat installed-apps list
// ═══════════════════════════════════════════════════════════════════════════════
// Convention: one badge at a time, highest-severity wins.
// Priority order (high → low):
//   1. Failsafe triggered     (red)    — hardware problem, valves force-closed
//   2. Weather station stale  (red)    — ET calculation is flying blind
//   3. Stale soil sensor(s)   (orange) — zone soil data unreliable
//   4. Cycle aborted mid-run  (orange) — guard fired while sprinklers were on
//   5. ET normals table stale (orange) — baseline data old, enable NOAA to fix
//   6. Cycle running now      (blue)   — informational, not a problem
//   7. Last cycle skipped     (gray)   — guard or inactive day prevented run
//   8. Last cycle complete    (green)  — all good
//   9. No badge               (clean)  — initialized but no cycle yet

private void updateAppLabel() {
    String base  = app.name ?: APP_NAME   // use the app's installed name as base
    String badge = ""
    // Compute stale sensor count once — staleSoilZoneCount() loops over all
    // zones and is referenced twice in the chain, so avoid the double traversal
    int staleSensors = staleSoilZoneCount()

    // Priority 0: deliberate stop states outrank everything. Paused and winterized mean
    // the user has told the system not to water on purpose, which is more important at a
    // glance than a failsafe from a prior run (that failsafe is moot while stopped). A red
    // failsafe badge over a paused system would actively mislead.
    if (settings.winterizedMode) {
        badge = " <font color='#004085'>❄️ Winterized</font>"

    } else if (settings.systemPaused) {
        String until = ""
        if (settings.pauseUntil) {
            try {
                until = " until " + toDateTime(settings.pauseUntil.toString()).format("MMM d", location.timeZone)
            } catch (Exception e) { logD "pause label date: ${e.message}" }
        }
        badge = " <font color='orange'>⏸ Paused${until}</font>"

    // Priority 1: failsafe (red) — valves were force-closed by failsafe handler
    } else if (atomicState.failsafeTriggered) {
        badge = " <font color='red'>🔴 Failsafe</font>"

    // Priority 2: weather station stale (red) — ET inputs unreliable
    } else if (settings.weatherStation && !isStationFresh()) {
        badge = " <font color='red'>🔴 Station Stale</font>"

    // Priority 3: any configured soil sensor has gone stale (orange)
    } else if (staleSensors > 0) {
        badge = " <font color='orange'>⚠️ Sensor${staleSensors > 1 ? 's' : ''} Stale (${staleSensors})</font>"

    // Priority 4: cycle aborted mid-run by guard (orange)
    } else if (atomicState.lastRunTimestamp?.contains("aborted")) {
        badge = " <font color='orange'>⚠️ Aborted</font>"

    // Priority 5: ET normals table is stale and NOAA is not enabled (orange)
    } else if (atomicState.etNormalsSource == "static_table_stale") {
        badge = " <font color='orange'>⚠️ ET Table Stale</font>"

    // Priority 6: cycle currently running (blue — informational)
    } else if (atomicState.cycleRunning) {
        badge = " <font color='blue'>💧 Running</font>"

    // Priority 7: last cycle was skipped (gray — informational)
    } else if (atomicState.lastRunTimestamp?.contains("skipped") ||
               atomicState.lastRunTimestamp?.contains("satisfied")) {
        badge = " <font color='gray'>⏭ Skipped</font>"

    // Priority 8: last cycle completed normally (green)
    } else if (atomicState.lastRunTimestamp?.contains("complete")) {
        badge = " <font color='green'>✅ Complete</font>"
    }

    app.updateLabel(base + badge)
}

// Returns the count of configured soil sensors that are currently stale
private int staleSoilZoneCount() {
    int zc    = safeZoneCount()
    int count = 0
    (1..zc).each { z ->
        if (settings["zoneSoilSensor_${z}"] && isSensorStale(z)) count++
    }
    return count
}

// ═══════════════════════════════════════════════════════════════════════════════
// CYCLE-AND-SOAK HELPERS
// ═══════════════════════════════════════════════════════════════════════════════

// Returns the maximum minutes a zone should run in one continuous burst before
// the soil is overwhelmed and water starts running off.
// Logic: maxCycleMin = (infiltrationRate / precipRate) * 60
// When precipRate <= infiltrationRate, no splitting is ever needed (returns null).
// Drip/bubbler nozzles always return null — they can't cause surface runoff.
private Integer calcMaxCycleMin(int z) {
    String nozzle = settings["zoneNozzle_${z}"] ?: "Rotor"
    if (nozzle in DRIP_NOZZLE_TYPES) return null  // drip never needs splitting
    BigDecimal precip = zonePrecip(z)
    if (precip <= 0G) return null
    String soil = settings["zoneSoil_${z}"] ?: "Loam"
    BigDecimal infiltration = SOIL_INFILTRATION[soil] ?: 0.5G
    if (precip <= infiltration) return null  // soil absorbs faster than nozzle applies
    // Convert to minutes and floor at 3 min (shorter cycles aren't useful)
    int maxMin = ((infiltration / precip) * 60G).setScale(0, BigDecimal.ROUND_DOWN) as int
    return Math.max(3, maxMin)
}

// Returns the soak time (minutes) for a zone between cycle-and-soak bursts.
// Option B: auto-calculates from soil type (SOIL_SOAK_MINS), but respects a
// per-zone user override (zoneSoakOverride_z) when set.
// Falls back to global cycleSoakMins if neither is available (legacy support).
private int calcSoakMin(int z) {
    // Per-zone override takes highest priority
    def override = settings["zoneSoakOverride_${z}"]
    if (override != null && override != "" && override != "auto") {
        return (override as int)
    }
    // Auto: derive from soil type
    String soil = settings["zoneSoil_${z}"] ?: "Loam"
    int autoMin = (SOIL_SOAK_MINS[soil] ?: 30) as int
    logD "Zone ${z} soak: auto ${autoMin} min (${soil})"
    return autoMin
}

// ═══════════════════════════════════════════════════════════════════════════════
// GRASS-SEED GERMINATION MODE
// ═══════════════════════════════════════════════════════════════════════════════
// A zone in seeding mode follows a phase-based program (Germinate→Establish→
// Transition) driven by a per-zone start date, then auto-returns to normal ET
// watering. Seeding cycles run on their own intraday schedule (separate from the
// main pre-dawn cycle) and use time-based runtimes, not ET/soil-threshold logic.

// Returns the day number (1-based) since this zone's seeding start, or 0 if not seeding.
private int seedDayNumber(int z) {
    if (!settings["zoneSeedingEnabled_${z}"]) return 0
    def startStr = settings["zoneSeedStartDate_${z}"]
    if (!startStr) return 0
    try {
        // Date input arrives as ISO yyyy-MM-dd (Hubitat "date" input)
        Date start = Date.parse("yyyy-MM-dd", (startStr as String).take(10))
        Calendar sc = Calendar.getInstance(location.timeZone)
        sc.setTime(start); sc.set(Calendar.HOUR_OF_DAY,0); sc.set(Calendar.MINUTE,0); sc.set(Calendar.SECOND,0); sc.set(Calendar.MILLISECOND,0)
        Calendar today = Calendar.getInstance(location.timeZone)
        today.set(Calendar.HOUR_OF_DAY,0); today.set(Calendar.MINUTE,0); today.set(Calendar.SECOND,0); today.set(Calendar.MILLISECOND,0)
        long diffMs = today.timeInMillis - sc.timeInMillis
        if (diffMs < 0) return 0  // start date is in the future
        return ((diffMs / 86400000L) as int) + 1
    } catch (Exception e) { logW "seedDayNumber zone ${z}: ${e.message}"; return 0 }
}

// Returns the preset row [p1Days,p2Days,p3Days,p1PerDay,p2PerDay,p3PerDay,p1Min,p2Min,p3Min]
// honoring any per-zone override of the phase day-counts.
private List seedPreset(int z) {
    String type = settings["zoneSeedType_${z}"] ?: "Cool-season fast (rye, tall fescue)"
    List base = (SEED_PRESETS[type] ?: SEED_PRESETS["Cool-season fast (rye, tall fescue)"]) as List
    // Per-zone day-count overrides (optional)
    int p1 = (settings["zoneSeedP1Days_${z}"] ?: base[0]) as int
    int p2 = (settings["zoneSeedP2Days_${z}"] ?: base[1]) as int
    int p3 = (settings["zoneSeedP3Days_${z}"] ?: base[2]) as int
    return [p1, p2, p3, base[3], base[4], base[5], base[6], base[7], base[8]]
}

// Returns the current phase for a seeding zone: 0=not seeding/complete, 1, 2, or 3.
private int seedPhase(int z) {
    int day = seedDayNumber(z)
    if (day <= 0) return 0
    List p = seedPreset(z)
    int p1 = p[0] as int, p2 = p[1] as int, p3 = p[2] as int
    if (day <= p1) return 1
    if (day <= p1 + p2) return 2
    if (day <= p1 + p2 + p3) return 3
    return 0  // program complete → normal watering resumes
}

// Cycles-per-day for the zone's current phase (0 if not seeding).
private int seedCyclesToday(int z) {
    int phase = seedPhase(z)
    if (phase == 0) return 0
    List p = seedPreset(z)
    return [0, p[3], p[4], p[5]][phase] as int
}

// Minutes-per-cycle for the zone's current phase (0 if not seeding).
private int seedRuntimeMin(int z) {
    int phase = seedPhase(z)
    if (phase == 0) return 0
    List p = seedPreset(z)
    return [0, p[6], p[7], p[8]][phase] as int
}

// True if ANY zone is actively seeding today (used to decide whether to schedule
// the intraday seed cycles at all).
private boolean anyZoneSeeding() {
    int zc = safeZoneCount()
    for (int z in (1..zc)) { if (seedPhase(z) > 0) return true }
    return false
}

// The set of clock times (HH:mm) at which seed cycles should run today, across all
// seeding zones — the union of each zone's phase cycle-count mapped onto
// SEED_CYCLE_TIMES. e.g. a 3×/day zone uses all three; a 2×/day zone uses the
// morning + late-afternoon slots; a 1×/day zone uses the morning slot.
private List seedCycleTimesToday() {
    int zc = safeZoneCount()
    Set<String> times = [] as Set
    (1..zc).each { z ->
        int n = seedCyclesToday(z)
        if (n >= 3)      times.addAll(SEED_CYCLE_TIMES)                       // morning, midday, late-pm
        else if (n == 2) times.addAll([SEED_CYCLE_TIMES[0], SEED_CYCLE_TIMES[2]])  // morning + late-pm
        else if (n == 1) times.add(SEED_CYCLE_TIMES[0])                       // morning only
    }
    return times.toList().sort()
}

// Returns estimated water penetration depth in inches for a given runtime.
// Formula: waterApplied (in) = precipRate (in/hr) × runtime (hr)
//          penetrationDepth (in) = waterApplied / AWC (in/in)
// This assumes no runoff (i.e., cycle-and-soak is in use when needed) and
// that the soil is at typical field moisture before watering.
// The result is an estimate for planning — actual depth varies with soil
// uniformity, initial moisture, and slope. Validate with the screwdriver test.
private BigDecimal calcPenetrationDepth(int z, int runtimeMin) {
    BigDecimal precip   = zonePrecip(z)
    String soil         = settings["zoneSoil_${z}"] ?: "Loam"
    BigDecimal awc      = SOIL_AWC[soil] ?: 0.17G
    if (precip <= 0G || awc <= 0G) return 0G
    BigDecimal waterIn  = (precip * runtimeMin / 60G).setScale(3, BigDecimal.ROUND_HALF_UP)
    BigDecimal depth    = (waterIn / awc).setScale(1, BigDecimal.ROUND_HALF_UP)
    return depth
}

// Returns total wall-clock minutes from cycle start to last valve close,
// accounting for cycle-and-soak splits and inter-zone delays.
// Used to warn the user if the total window extends past their failsafe time.
private int calcTotalCycleWindowMin(List queue) {
    if (!queue) return 0
    // soakMin is per-entry (set at queue-build time via calcSoakMin)
    // delayMin is global — same between all zones
    int delayMin  = Math.ceil(((settings.interZoneDelaySec ?: "10") as int) / 60.0) as int
    int total     = 0
    // Read cycle parameters directly from queue entries — they were embedded at
    // build time and are exactly what the cycle is executing. Re-deriving from
    // settings here would diverge if settings changed mid-cycle.
    queue.each { entry ->
        int rtMin    = entry.runtimeMin as int
        boolean doSp = (entry.doSplit ?: false) as boolean
        int maxC     = (entry.maxCycleMin ?: rtMin) as int
        if (doSp && maxC < rtMin) {
            int cycles   = Math.ceil(rtMin / maxC) as int
            int entrySoak = (entry.soakMin ?: calcSoakMin(entry.zone as int)) as int
            int soakTime = (cycles - 1) * entrySoak
            total += rtMin + soakTime
        } else {
            total += rtMin
        }
        total += delayMin  // inter-zone delay (including after last zone — negligible)
    }
    // Subtract one delay: the last zone's delay fires but startNextZone immediately
    // completes — it doesn't add meaningful wall-clock time to the user's window
    if (!queue.isEmpty()) total = Math.max(0, total - delayMin)
    return total
}

// Dynamic soil moisture threshold — adjusts the user's fixed threshold
// based on today's ET demand vs the seasonal baseline.
// Hot/high-ET days → raise threshold slightly (water sooner, more frequently)
// Cool/low-ET days → lower threshold slightly (let soil dry more between runs)
// The adjustment is ±8 percentage points max, keeping it agronomically conservative.
// If ETo or baseline are unavailable (e.g. first run, no station), returns fixed threshold.
private int dynamicSoilThreshold(int z, BigDecimal eto, BigDecimal baseline) {
    int fixedThresh = (settings["zoneSoilThreshold_${z}"] ?: "45") as int
    if (!settings["dynamicThreshEnabled_${z}"]) return fixedThresh
    // Explicit null check — avoid falsy-zero trap (0G is falsy in Groovy)
    if (eto == null || baseline == null || eto <= 0G || baseline <= 0G) return fixedThresh
    // Ratio > 1 = hotter than average, < 1 = cooler than average
    BigDecimal ratio = (eto / baseline).setScale(2, BigDecimal.ROUND_HALF_UP)
    // Clamp ratio to a sensible range before applying delta
    ratio = new BigDecimal(Math.max(0.2d, Math.min(3.0d, ratio.toDouble())))
    // Delta: +8pp at 2× ET, 0pp at 1× ET, -8pp at 0.2× ET
    // Linear interpolation: delta = (ratio - 1.0) * 8 / 1.0, clamped to ±8
    int delta = ((ratio - 1.0G) * 8.0G).setScale(0, BigDecimal.ROUND_HALF_UP) as int
    delta = Math.max(-8, Math.min(8, delta))
    int dynThresh = Math.max(20, Math.min(75, fixedThresh + delta))
    logD "Zone ${z} dynamic threshold: fixed=${fixedThresh}% + delta=${delta}pp (ETo ratio=${ratio}) → ${dynThresh}%"
    return dynThresh
}

// ─────────────────────────────────────────────────────────────────────────────
// sanitizeHtml() — make user-entered strings safe to insert into paragraph() HTML.
// Hubitat's local UI is hub-owner-only so the real risk is low (self-XSS at most),
// but we escape rather than strip: converting the HTML-significant characters to
// entities can't be defeated by malformed/nested tags the way a tag-stripping regex
// can (e.g. "<<b>b>" survives a single strip pass but is rendered inert by escaping).
// Order matters: '&' must be escaped first so we don't double-escape the entities we
// introduce. Length is capped to bound the rendered output. Name/signature kept so
// all existing call sites work unchanged.
private String sanitizeHtml(String s) {
    if (!s) return ""
    // Cap the raw input first so escaping never splits a multi-char entity (e.g.
    // truncating "&amp;" to "&am"); then escape the HTML-significant characters.
    return s.take(64)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace('"', "&quot;")
            .replace("'", "&#x27;")
}

// ═══════════════════════════════════════════════════════════════════════════════
// v3.0.0 NEW FEATURE HANDLERS
// ═══════════════════════════════════════════════════════════════════════════════

// ── Hub reboot recovery ───────────────────────────────────────────────────────
// Fired when Hubitat restarts. If today's scheduled cycle should have run but
// didn't (hub was down at cycle time), re-runs it after a short delay.
void hubRestartHandler(evt) {
    logI "Hub restarted (systemStart) — checking for missed cycle"
    try {
        String today = new Date().format("yyyy-MM-dd", location.timeZone)
        // If we have no record of today's cycle AND the scheduled start time has
        // already passed, queue a catch-up run
        if (settings.startTime && !atomicState.lastCycleDone) {
            Calendar nowCal   = Calendar.getInstance(location.timeZone)
            Calendar startCal = Calendar.getInstance(location.timeZone)
            startCal.setTime(toDateTime(settings.startTime))
            startCal.set(Calendar.YEAR,         nowCal.get(Calendar.YEAR))
            startCal.set(Calendar.MONTH,        nowCal.get(Calendar.MONTH))
            startCal.set(Calendar.DAY_OF_MONTH, nowCal.get(Calendar.DAY_OF_MONTH))
            if (nowCal.timeInMillis > startCal.timeInMillis &&
                atomicState.lastCycleDate != today) {
                logW "Hub restart: scheduled cycle at ${fmtTime(settings.startTime)} was missed — re-checking in 60s"
                sendNotification("notifyOnInfoSkip", "Hub restarted after a missed start time — re-checking conditions (will only water if guards pass)", false)
                runIn(60, "irrigationCycle")
            }
        }
        // Always re-initialize schedules after reboot
        runIn(5, "initialize")
    } catch (Exception e) { logW "hubRestartHandler: ${e.message}" }
}

// ── Valve post-close verification ─────────────────────────────────────────────
// Called 90s after closeValve(). If the valve still reports open, alert.
void verifyValveClosed(Map data) {
    if (atomicState.cycleRunning) return   // still cycling — normal, skip check
    int z = data.zone as int
    def dev = settings["zoneValve_${z}"]
    if (!dev) return
    try {
        def state = dev.currentValue("valve") ?: dev.currentValue("switch")
        if (state == "open" || state == "on") {
            // check_back: retry the close once before alerting (matches open-side behavior)
            if (settings.valveCheckBackEnabled != false && !atomicState["valveCloseRetried_${z}"]) {
                atomicState["valveCloseRetried_${z}"] = true
                logW "Zone ${z} still open 90s after close — retrying close command once"
                try {
                    if (dev.hasCommand("close"))    dev.close()
                    else if (dev.hasCommand("off")) dev.off()
                } catch (Exception e2) { logW "close retry zone ${z}: ${e2.message}" }
                runIn(valveCheckDelaySec(), "verifyValveClosed", [data:[zone:z]])
                return
            }
            atomicState.remove("valveCloseRetried_${z}")
            logW "VALVE ANOMALY: Zone ${z} still reports open after close command + retry — possible stuck valve"
            sendNotification("notifyOnValve",
                "🚰 Valve anomaly: Zone ${z} (${sanitizeHtml((settings["zoneName_${z}"] ?: 'Zone '+z) as String)}) still open after close + retry")
        } else {
            atomicState.remove("valveCloseRetried_${z}")
        }
    } catch (Exception e) { logD "verifyValveClosed zone ${z}: ${e.message}" }
}

// check_back open-side verification. Confirms the valve reported open after the
// command. On failure: retry the open once, re-verify; if still not open, alert.
// A valve that won't open means the zone gets no water while the app thinks it is
// running — worth a prompt alert so the user can check the device. Only runs while
// the cycle is still active and this zone is still the active one (a normal close
// during the settle window is not a failure).
void verifyValveOpen(Map data) {
    if (!settings.valveCheckBackEnabled && settings.valveCheckBackEnabled != null) return
    int z = data.zone as int
    String name = (data.name ?: settings["zoneName_${z}"] ?: "Zone ${z}") as String
    // If we've moved on from this zone (or stopped cycling), the open no longer matters.
    if (!atomicState.cycleRunning) return
    if ((atomicState.activeZone as Integer) != z) return
    def dev = settings["zoneValve_${z}"]
    if (!dev) return
    try {
        def state = dev.currentValue("valve") ?: dev.currentValue("switch")
        boolean isOpen = (state == "open" || state == "on")
        if (isOpen) { atomicState.remove("valveOpenRetried_${z}"); return }   // good
        if (!atomicState["valveOpenRetried_${z}"]) {
            atomicState["valveOpenRetried_${z}"] = true
            logW "Zone ${z} (${name}) did not report open — retrying open command once"
            try {
                if (dev.hasCommand("open"))    dev.open()
                else if (dev.hasCommand("on")) dev.on()
            } catch (Exception e2) { logW "open retry zone ${z}: ${e2.message}" }
            runIn(valveCheckDelaySec(), "verifyValveOpen", [data:[zone:z, name:name]])
            return
        }
        // Still not open after a retry — alert.
        atomicState.remove("valveOpenRetried_${z}")
        logW "VALVE ANOMALY: Zone ${z} (${name}) failed to open after command + retry — zone may be getting no water"
        sendNotification("notifyOnValve",
            "🚰 Valve anomaly: Zone ${sanitizeHtml(name)} did not open after command + retry — check the valve")
    } catch (Exception e) { logD "verifyValveOpen zone ${z}: ${e.message}" }
}

// Settle delay (seconds) before checking whether a valve command took effect.
// Valves report state with lag; too short gives false failures. Default 3s,
// user-adjustable for slower hardware.
private int valveCheckDelaySec() {
    def v = settings.valveCheckDelaySec
    int d = (v == null) ? 3 : (v as int)
    return Math.max(1, Math.min(30, d))
}

// ── Leak sensor handler ───────────────────────────────────────────────────────
void leakSensorHandler(evt) {
    if (evt.value == "wet") {
        logW "LEAK DETECTED by ${evt.displayName} — closing all valves immediately"
        closeAllValves()
        if (atomicState.cycleRunning) {
            atomicState.abortReason = "Water leak detected"
            atomicState.abortCycle  = true
            runIn(1, "abortCycleNow")
        }
        sendNotification("notifyOnValve",
            "💧 LEAK DETECTED: ${evt.displayName} — all valves closed. Check your valve box.")
    } else {
        logI "Leak sensor cleared: ${evt.displayName}"
    }
}

// ── Station battery handler ────────────────────────────────────────────────────
void stationBatteryHandler(evt) {
    try {
        BigDecimal bat = evt.value?.toBigDecimal()
        BigDecimal thr = (settings.stationBatteryThreshold ?: 20G) as BigDecimal
        if (bat != null && bat < thr) {
            logW "Station battery low: ${bat}% (threshold ${thr}%)"
            long lastAlert = atomicState.lastBatteryAlert ?: 0L
            if ((now() - lastAlert) > 86400000L) {   // max once per day
                sendNotification("notifyOnBattery",
                    "🔋 Station battery low: ${bat.toInteger()}% — replace batteries soon")
                atomicState.lastBatteryAlert = now()
            }
        }
    } catch (Exception e) { logD "stationBatteryHandler: ${e.message}" }
}

// ── Mode change handler (mode-triggered manual run) ────────────────────────────
void modeChangedHandler(evt) {
    if (!settings.modeRunMode || !settings.modeRunZone || !settings.modeRunMins) return
    if (evt.value == settings.modeRunMode) {
        logI "Mode changed to '${evt.value}' — triggering manual run on zone ${settings.modeRunZone}"
        manualStartZone(settings.modeRunZone as int)
    }
}

// ── Grass-seed germination cycle ───────────────────────────────────────────────
// Runs the intraday seeding cycles for any zone currently in a germination phase.
// Per academic/extension guidance, seeding applies ONLY the rain and hard-freeze
// guards (washing seed away / icing it is harmful), but intentionally OVERRIDES the
// wind, frost-dewpoint-spread, and soil-moisture guards: wind and dry air mean the
// surface dries FASTER, so seed needs MORE water then, not less; and the soil sensor
// (which sits deeper) reads "fine" while the surface the seed lives in dries out.
//
// Returns [passed:bool, reason:String] — only rain + hard freeze can block seeding.
private Map seedGuardCheck() {
    def wx = settings.weatherStation
    // Hard freeze: never water seed onto frozen/icing ground. Use the same freeze
    // threshold as the normal guard, read the same way.
    boolean stationFresh = isStationFresh()
    def tempNow = (wx && stationFresh) ? stationTempToF(safeGetAttrBD(wx, resolvedAttr("attrTemp","temperature"))) : null
    if (tempNow == null && !isLocalOnlyMode() && settings.noaaFallbackEnabled) tempNow = fetchNoaaCurrentTemp()
    def tempMin = (settings.guardTempMin ?: "38") as BigDecimal
    if (tempNow != null && tempNow <= tempMin) return [passed:false, reason:"Freeze: ${tempNow}°F ≤ ${tempMin}°F (seeding paused)"]

    // Rain: skip seeding if it's actively raining or enough has accumulated — rain
    // already wets the surface and extra water risks washing seed away.
    if (wx) {
        def rainRate = safeGetAttrBD(wx, resolvedAttr("attrRainRate","rainRate"))
        def maxRate  = (settings.guardRainRate ?: "0.09") as BigDecimal
        if (rainRate != null && rainRate >= maxRate) return [passed:false, reason:"Raining: ${rainRate} in/hr (seeding paused)"]

        def rainDay  = safeGetAttrBD(wx, resolvedAttr("attrRainDay","rainDaily"))
        if (settings.rainGaugeDevice) {
            def gaugeDay = safeGetAttrBD(settings.rainGaugeDevice, settings.rainGaugeAttr ?: "rainDaily")
            if (gaugeDay != null) rainDay = gaugeDay
        }
        def maxDay   = (settings.guardRainDaily ?: "0.25") as BigDecimal
        if (rainDay != null && rainDay >= maxDay) return [passed:false, reason:"Rain today: ${rainDay}\" (seeding paused)"]
    }
    return [passed:true, reason:"ok"]
}

// Slot wrappers — distinct handler names so all three cron schedules survive
// (Hubitat schedule() replaces by handler name). Each just runs the seed cycle;
// seedCycleRun() figures out which slot it is and which zones are due.
void seedCycleSlot0() { seedCycleRun() }
void seedCycleSlot1() { seedCycleRun() }
void seedCycleSlot2() { seedCycleRun() }

// Fired at each SEED_CYCLE_TIMES slot. Builds a queue of the zones whose phase calls
// for a cycle at this time of day and runs them through the normal valve chain.
void seedCycleRun() {
    if (atomicState.cycleRunning) { logD "Seed cycle skipped — another cycle running"; runIn(120,"seedCycleRun"); return }
    if (settings.systemPaused || settings.winterizedMode) { logD "Seed cycle skipped — paused/winterized"; return }
    if (!anyZoneSeeding()) { logD "Seed cycle: no zones seeding"; return }

    // Which clock slot are we in right now? Match current HH:mm to the nearest
    // SEED_CYCLE_TIMES entry (within a 30-min window) so we know which slot this is.
    Calendar now = Calendar.getInstance(location.timeZone)
    int nowMin = now.get(Calendar.HOUR_OF_DAY)*60 + now.get(Calendar.MINUTE)
    String slot = null
    SEED_CYCLE_TIMES.each { t ->
        def parts = (t as String).split(":")
        int tMin = (parts[0] as int)*60 + (parts[1] as int)
        if (Math.abs(nowMin - tMin) <= 30) slot = t
    }
    if (slot == null) { logD "Seed cycle fired outside a known slot — ignoring"; return }
    int slotIdx = SEED_CYCLE_TIMES.indexOf(slot)

    // Guard: rain + hard freeze only
    def g = seedGuardCheck()
    if (!g.passed) {
        logI "Seed cycle skipped: ${g.reason}"
        atomicState.lastSkipReason   = "Seeding: ${g.reason}"
        atomicState.lastRunTimestamp = ts() + " (seed skipped)"
        sendNotification("notifyOnSeedSkip", "🌱 Seeding cycle skipped — ${g.reason}", false)
        updateAppLabel(); return
    }

    // Build the queue: a zone runs at this slot if its phase's cycle count includes it.
    // 3×/day uses slots 0,1,2; 2×/day uses slots 0 and 2; 1×/day uses slot 0 only.
    int zc = safeZoneCount()
    def seedQueue = []
    (1..zc).each { z ->
        int n = seedCyclesToday(z)
        if (n <= 0) return
        boolean runThisSlot = (n >= 3) || (n == 2 && slotIdx in [0,2]) || (n == 1 && slotIdx == 0)
        if (!runThisSlot) return
        if (settings["zoneEnabled_${z}"] == false || !settings["zoneValve_${z}"]) return
        int mins = seedRuntimeMin(z)
        if (mins <= 0) return
        seedQueue << [zone:z, name:settings["zoneName_${z}"] ?: "Zone ${z}",
                      runtimeMin:mins, maxCycleMin:mins, doSplit:false, soakMin:0,
                      isSeed:true]
    }
    if (seedQueue.isEmpty()) { logD "Seed cycle: no zones due at slot ${slot}"; return }

    atomicState.cycleRunning     = true
    atomicState.abortCycle       = false
    atomicState.abortReason      = null
    atomicState.zoneQueue        = seedQueue
    atomicState.zoneQueueIdx     = 0
    atomicState.lastRunTimestamp = ts() + " (seeding)"
    atomicState.lastSkipReason   = "Seeding cycle running (${slot})"
    int seedCount = seedQueue.size()
    logI "🌱 Seed cycle @ ${slot}: ${seedCount} zone(s) due"
    if (settings.masterValve) {
        try {
            if (settings.masterValve.hasCommand("on")) settings.masterValve.on()
            else settings.masterValve.open()
        } catch (Exception e) { logW "Master valve open (seed): ${e.message}" }
    }
    updateAppLabel()
    runIn(1, "startNextZone")
}

// ── Midday moisture top-up cycle ───────────────────────────────────────────────
// A second daily, DEMAND-BASED check: for each opted-in zone, run the SAME soil
// evaluation the morning cycle uses (live reading vs the dynamic threshold) and water
// only the zones that are actually dry — with a dynamically-calculated top-up amount
// (scaled by the measured deficit, capped at one MAD-band refill). Applies the normal
// guard stack (rain/freeze/etc.) and does NOT set the DST guard or count as the main
// daily irrigation. Per-zone opt-in via zoneMiddayCheck_z.
void middayMoistureCheck() {
    if (atomicState.cycleRunning) { logD "Midday check skipped — another cycle running"; runIn(180,"middayMoistureCheck"); return }
    if (settings.systemPaused || settings.winterizedMode) { logD "Midday check skipped — paused/winterized"; return }
    if (!isTodayActiveDay()) { logD "Midday check skipped — not an active day"; return }
    // TOU peak hours — a midday top-up is discretionary, so respect the same time-of-use
    // restriction the main cycle does (the afternoon top-up window often overlaps peak rates).
    if (isInTouPeak()) {
        logI "Midday top-up skipped — within TOU peak hours"
        atomicState.lastSkipReason   = "Midday top-up: TOU peak hours"
        atomicState.lastRunTimestamp = ts() + " (midday skipped)"
        updateAppLabel(); return
    }

    // Any zones opted in?
    int zc = safeZoneCount()
    boolean anyOptIn = (1..zc).any { settings["zoneMiddayCheck_${it}"] }
    if (!anyOptIn) { logD "Midday check: no zones opted in"; return }

    // Same guard stack as the normal cycle — no point topping up before rain, etc.
    def gr = checkAllGuards()
    if (!gr.passed) {
        logI "Midday check skipped: ${gr.reason}"
        atomicState.lastSkipReason   = "Midday top-up: ${gr.reason}"
        atomicState.lastRunTimestamp = ts() + " (midday skipped)"
        sendNotification("notifyOnMiddaySkip", "🌡️ Midday top-up skipped — ${gr.reason}", false)
        updateAppLabel(); return
    }

    // ET/baseline for the dynamic threshold + deficit math (same inputs the cycle uses)
    BigDecimal eto      = (atomicState.lastEto ?: 0G) as BigDecimal
    BigDecimal baseline = (atomicState.lastEtInputs?.baseline ?: 0G) as BigDecimal

    def queue = []
    (1..zc).each { z ->
        if (!settings["zoneMiddayCheck_${z}"]) return
        if (settings["zoneEnabled_${z}"] == false || !settings["zoneValve_${z}"]) return
        def ev = evaluateSoilForZone(z, eto, baseline)
        // The midday top-up is purely demand-based — it only makes sense with a CURRENT
        // reading. Skip unless the sensor is genuinely fresh: a stale value (even via
        // behavior:last) or a missing sensor must NOT trigger an extra watering, because
        // there's no reliable way to know the zone is dry and acting on a stale reading
        // could conflict with what the night cycle already does off the same sensor.
        if (!ev.fresh) { logD "Midday zone ${z}: no fresh sensor reading — skipping top-up (${ev.reason})"; return }
        if (!ev.shouldWater) { logD "Midday zone ${z}: ${ev.reason} — no top-up"; return }
        Integer sp = (ev.soilPct != null) ? (ev.soilPct as int) : null
        Integer th = (ev.thresh  != null) ? (ev.thresh  as int) : null
        if (sp == null || th == null) { logD "Midday zone ${z}: no reliable reading — skipping top-up"; return }
        int mins = middayTopUpMinutes(z, sp, th)
        if (mins <= 0) return
        queue << [zone:z, name:settings["zoneName_${z}"] ?: "Zone ${z}",
                  runtimeMin:mins, maxCycleMin:mins, doSplit:false, soakMin:0,
                  isMidday:true, isSeed:false, isSyringe:false]
        logI "Midday zone ${z} (${settings["zoneName_${z}"]}): soil ${sp}% ≤ ${th}% → top-up ${mins} min (cap ${madBandRefillMinutes(z)})"
    }
    if (queue.isEmpty()) { logI "Midday check: all opted-in zones adequately moist — nothing to do"; return }

    atomicState.cycleRunning     = true
    atomicState.abortCycle       = false
    atomicState.abortReason      = null
    atomicState.zoneQueue        = queue
    atomicState.zoneQueueIdx     = 0
    atomicState.lastRunTimestamp = ts() + " (midday)"
    atomicState.lastSkipReason   = "Midday top-up running"
    int qn = queue.size()
    logI "🌡️ Midday top-up: ${qn} zone(s) dry"
    if (settings.masterValve) {
        try {
            if (settings.masterValve.hasCommand("on")) settings.masterValve.on()
            else settings.masterValve.open()
        } catch (Exception e) { logW "Master valve open (midday): ${e.message}" }
    }
    updateAppLabel()
    runIn(1, "startNextZone")
}

// ── Heat-stress syringe run ────────────────────────────────────────────────────
// Runs a short burst on each zone for cooling only. NOT counted as irrigation.
// Adaptive syringe window. Instead of one fixed clock time (which misses days whose
// heat peaks later than the check), the scheduler calls syringeWindowCheck every 15
// minutes; it gates on the afternoon window and a once-per-day latch, then hands off
// to heatStressSyringeRun, which applies the full guard stack (temp threshold, TOU,
// pause, cycle-running). Fires at most once per day, the FIRST time the live
// temperature crosses the threshold inside the window, so it catches the real peak
// whichever hour it lands.
private boolean isInSyringeWindow() {
    try {
        def nowCal = Calendar.getInstance(location.timeZone)
        def wS = Calendar.getInstance(location.timeZone)
        def wE = Calendar.getInstance(location.timeZone)
        if (settings.syringeWindowStart) wS.setTime(toDateTime(settings.syringeWindowStart))
        else { wS.set(Calendar.HOUR_OF_DAY, 13); wS.set(Calendar.MINUTE, 0) }
        if (settings.syringeWindowEnd) wE.setTime(toDateTime(settings.syringeWindowEnd))
        else { wE.set(Calendar.HOUR_OF_DAY, 18); wE.set(Calendar.MINUTE, 0) }
        [wS,wE].each { c ->
            c.set(Calendar.YEAR,  nowCal.get(Calendar.YEAR))
            c.set(Calendar.MONTH, nowCal.get(Calendar.MONTH))
            c.set(Calendar.DAY_OF_MONTH, nowCal.get(Calendar.DAY_OF_MONTH))
            c.set(Calendar.SECOND, 0)
        }
        long n = nowCal.timeInMillis
        // Same-day afternoon window; an inverted configuration disables it rather than wrapping.
        return (n >= wS.timeInMillis && n < wE.timeInMillis)
    } catch (Exception e) { logW "isInSyringeWindow: ${e.message}"; return false }
}

void syringeWindowCheck() {
    if (!settings.syringeEnabled) return
    String today = new Date().format("yyyy-MM-dd", location.timeZone)
    if (atomicState.lastSyringeDate == today) return   // already fired today
    if (!isInSyringeWindow()) return
    heatStressSyringeRun()
}

void heatStressSyringeRun() {
    if (!settings.syringeEnabled) return
    if (atomicState.cycleRunning) { logD "Syringe skipped — main cycle running"; return }
    if (settings.systemPaused || settings.winterizedMode) return
    // TOU peak hours — cooling is discretionary, so respect the time-of-use restriction
    // (the afternoon cooling window often overlaps peak electricity rates).
    if (isInTouPeak()) { logD "Syringe skipped — within TOU peak hours"; return }
    // Check temperature threshold
    BigDecimal tempF = null
    if (settings.weatherStation) {
        tempF = stationTempToF(safeGetAttrBD(settings.weatherStation,
                resolvedAttr("attrTemp","temperature")))
    }
    if (tempF == null) { // fall back to NOAA Tmax if available
        tempF = atomicState.noaaCacheTmax as BigDecimal
    }
    BigDecimal syringeThr = (settings.syringeTempF ?: 95G) as BigDecimal
    if (tempF == null || tempF < syringeThr) {
        logD "Syringe: temp ${tempF}°F below threshold ${syringeThr}°F — skipping"
        return
    }
    logI "Heat-stress syringe: temp ${tempF}°F ≥ threshold ${syringeThr}°F — running short bursts"
    int zc    = safeZoneCount()
    int mins  = (settings.syringeMinsPerZone ?: 4) as int
    def syringeQueue = []
    (1..zc).each { z ->
        if (settings["zoneEnabled_${z}"] != false && settings["zoneValve_${z}"] &&
            settings["zoneSyringe_${z}"] != false) {
            syringeQueue << [zone:z, name:settings["zoneName_${z}"] ?: "Zone ${z}",
                            runtimeMin:mins, maxCycleMin:mins, doSplit:false, soakMin:0,
                            isSyringe:true]
        }
    }
    if (syringeQueue.isEmpty()) return
    atomicState.cycleRunning     = true
    atomicState.abortCycle       = false
    atomicState.abortReason      = null
    atomicState.zoneQueue        = syringeQueue
    atomicState.zoneQueueIdx     = 0
    atomicState.lastRunTimestamp = ts() + " (syringe)"
    atomicState.lastSkipReason   = "Heat-stress syringe running"
    logI "Syringe queue: ${syringeQueue.size()} zones × ${mins}min"
    // Adaptive-window once-per-day latch: set only here, at actual commit, so a skipped
    // check (temp below threshold, TOU, etc.) leaves the window free to try again.
    atomicState.lastSyringeDate = new Date().format("yyyy-MM-dd", location.timeZone)
    // Open master valve / pump relay before syringe bursts (same as a normal cycle)
    if (settings.masterValve) {
        try {
            if (settings.masterValve.hasCommand("on")) settings.masterValve.on()
            else settings.masterValve.open()
            logI "Master valve opened (syringe)"
        } catch (Exception e) { logW "Master valve open (syringe): ${e.message}" }
    }
    updateAppLabel()
    runIn(1, "startNextZone")
}

// ── Failsafe notification ──────────────────────────────────────────────────────
// Called when failsafe fires during a running cycle — extend failsafeClose() notification
// Note: actual notification is in failsafeClose() via sendNotification

// ── Morning skip digest ────────────────────────────────────────────────────────
// A 3:37 AM push about a skipped cycle wakes phones to report a decision nobody can
// act on at that hour. When skipMorningDigest is on (default), main-cycle skip
// notices are queued here and delivered as ONE summary at failsafe time, after the
// night's watering window has fully closed. Safety alerts (abort, failsafe breach,
// valve anomaly, runaway, leak) are never deferred; only the routine "did not water
// because" notices are. Queue persists in atomicState so a hub reboot cannot drop it.
private void notifyMainSkip(String msg) {
    if (settings.skipMorningDigest != false) {
        List q = (atomicState.pendingSkipNotices ?: []) as List
        String stamp = new Date().format("h:mm a", location.timeZone)
        q << "${stamp}: ${msg}"
        while (q.size() > 10) q.remove(0)   // bound the queue
        atomicState.pendingSkipNotices = q
        logD "Skip notice queued for morning digest: ${msg}"
    } else {
        sendNotification("notifyOnSkip", msg)
    }
}

private void flushSkipNotices() {
    List q = (atomicState.pendingSkipNotices ?: []) as List
    if (q.isEmpty()) return
    String body = (q.size() == 1) ? "Overnight: ${q[0]}"
                                  : "Overnight summary:\n" + q.join("\n")
    sendNotification("notifyOnSkip", body)
    atomicState.pendingSkipNotices = []
}

// ── sendNotification helper ────────────────────────────────────────────────────
// Sends a push notification if: notifyDevice is configured AND the specific
// notification type is enabled. Safe to call anywhere — no-ops if not configured.
// defaultOn governs the UNSET (null) case: most alerts default on, but the noisy/
// niche ones (e.g. midday-skip) default OFF, so a null setting must be treated as
// "off" for them — otherwise they'd send until the user explicitly toggles. This
// mirrors each input's defaultValue so behavior matches the UI before it's touched.
private void sendNotification(String key, String message, boolean defaultOn = true) {
    try {
        if (!settings.notifyDevice) return
        def pref = settings[key]
        boolean enabled = (pref == null) ? defaultOn : (pref != false)
        if (!enabled) return
        // Prefix every notification so it clearly identifies as this irrigation app
        // and stands out from unrelated hub notifications (lights, laundry, locks, etc).
        // Skip the prefix only if the caller already led with the 💧 marker.
        String prefixed = message?.startsWith("💧") ? message : "💧 Sprinklers: ${message}"
        settings.notifyDevice.each { dev ->
            try { dev.deviceNotification(prefixed) }
            catch (Exception e) { logD "sendNotification to ${dev.displayName}: ${e.message}" }
        }
        logD "Notification sent [${key}]: ${prefixed}"
    } catch (Exception e) { logD "sendNotification: ${e.message}" }
}

// ── recordCycleHistory helper ─────────────────────────────────────────────────
// Maintains a rolling MAX_CYCLE_HISTORY-entry list of cycle outcomes in atomicState.
private void recordCycleHistory(String outcome, String note) {
    try {
        def hist = (atomicState.cycleHistory as List) ?: []
        def eto2 = atomicState.lastEto
        hist << [
            date   : new Date().format("yyyy-MM-dd", location.timeZone),
            outcome: outcome,
            eto    : eto2 != null ? String.format("%.3f", eto2 as double) : null,
            note   : note
        ]
        // Trim to MAX_CYCLE_HISTORY entries
        while (hist.size() > MAX_CYCLE_HISTORY) hist.remove(0)
        atomicState.cycleHistory = hist
    } catch (Exception e) { logD "recordCycleHistory: ${e.message}" }
}

// ── calcEffectivePrecipRate helper ────────────────────────────────────────────
// Returns the nozzle precipitation rate (in/hr) for a zone, used for water-usage
// gallons estimation. Distribution uniformity (DU%) is intentionally NOT applied
// here: the sprinkler emits the same total volume regardless of how evenly it lands,
// so total water consumed depends only on the base output rate and runtime. DU
// affects how long you must run to wet the dry spots (a runtime concern handled
// separately), not the gallons-per-minute the nozzle physically discharges.
private BigDecimal calcEffectivePrecipRate(int z) {
    String nozzle = settings["zoneNozzle_${z}"] ?: "Rotor"
    BigDecimal baseRate = (settings["zonePrecipOverride_${z}"] as BigDecimal) ?:
                          ((NOZZLE_PRECIP[nozzle] ?: 0.5G) as BigDecimal)
    return baseRate
}

// ── Sunrise-relative start scheduler ─────────────────────────────────────────
// Called daily at midnight to schedule tonight's cycle at sunrise-relative time.
void scheduleSunriseRelativeStart() {
    if (!settings.sunriseRelativeStart) return
    try {
        int offsetMins = (settings.sunriseOffsetMins ?: 0) as int
        def sr = location.sunrise
        if (sr) {
            long startMs = sr.time - (offsetMins * 60000L)
            def startDate = new Date(startMs)
            schedule(startDate, "irrigationCycle")
            logI "Sunrise-relative cycle scheduled: ${startDate.format('h:mm a', location.timeZone)} " +
                 "(${offsetMins}min before sunrise)"
        }
    } catch (Exception e) { logW "scheduleSunriseRelativeStart: ${e.message}" }
}

// ── Startup inspection mode toggle ───────────────────────────────────────────
private void startStartupInspection() {
    atomicState.startupInspectionActive = true
    logI "Spring startup inspection mode activated — manual zone runs will use 60s burst"
    sendNotification("notifyOnComplete", "🌱 Startup inspection mode activated", false)
}
private void endStartupInspection() {
    atomicState.startupInspectionActive = false
    logI "Startup inspection mode ended"
}

// ── Solar radiation sanity check ──────────────────────────────────────────────
// Called after solar noon capture. Compares measured vs theoretical.
// Returns true if reading is plausible.
private boolean solarSanityCheck(BigDecimal measuredWm2, int doy) {
    if (!settings.solarSanityEnabled) return true
    try {
        BigDecimal lat = location.latitude as BigDecimal
        BigDecimal Ra  = calcRa(degToRad(lat), doy)
        // Convert Ra from MJ/m²/day to W/m² equivalent for comparison
        // MJ/m²/day × 11.57 ≈ W/m² average; clear-sky peak ≈ 2× average
        BigDecimal theoreticalPeakWm2 = (Ra * 11.57G * 2G).setScale(0, BigDecimal.ROUND_HALF_UP)
        BigDecimal ratio = (theoreticalPeakWm2 > 0G) ?
            (measuredWm2 / theoreticalPeakWm2).setScale(3, BigDecimal.ROUND_HALF_UP) : 1G
        atomicState.solarSanityRatio = ratio
        if (ratio < 0.15G) {
            logW "Solar radiation sanity: measured ${measuredWm2} W/m² is only ${ratio}× of theoretical " +
                 "${theoreticalPeakWm2} W/m² — sensor may be dirty/blocked. Falling back to Hargreaves ET."
            return false
        }
        logD "Solar sanity OK: ${measuredWm2} W/m² = ${ratio}× theoretical ${theoreticalPeakWm2} W/m²"
        return true
    } catch (Exception e) { logD "solarSanityCheck: ${e.message}"; return true }
}

// ── Soil temperature ET correction factor ────────────────────────────────────
// Returns a multiplier (0.5–1.0) based on soil temperature.
// When soil < 55°F, roots absorb water poorly — scale ET down.
private BigDecimal soilTempEtFactor(int z) {
    if (!settings.soilTempEtCorrEnabled) return 1G
    try {
        BigDecimal soilTempF = atomicState["soilTempF_${z}"] as BigDecimal
        if (soilTempF == null) return 1G
        if (soilTempF >= 55G) return 1G
        if (soilTempF <= 35G) return 0.5G
        // Linear interpolation 35–55°F → 0.5–1.0
        return (0.5G + ((soilTempF - 35G) / 40G) * 0.5G).setScale(3, BigDecimal.ROUND_HALF_UP)
    } catch (Exception e) { return 1G }
}

// ── Pre-freeze winterization alert ───────────────────────────────────────────
// Called by checkAllGuards() or daily check. Warns when hard freeze is forecast
// within 48 hours and the system is not winterized.
private void checkPreFreezeAlert() {
    if (!settings.preFreezeAlertEnabled) return
    if (settings.winterizedMode) return
    if (isLocalOnlyMode() || !settings.noaaFallbackEnabled) return
    try {
        BigDecimal tmin = atomicState.noaaCacheTmin as BigDecimal
        if (tmin != null && tmin < 28G) {
            long lastFreeze = atomicState.lastFreezeAlert ?: 0L
            if ((now() - lastFreeze) > 43200000L) {   // max twice per day
                logW "Pre-freeze alert: forecast Tmin=${tmin}°F — system not winterized"
                sendNotification("preFreezeAlertEnabled",
                    "❄️ Hard freeze forecast (${tmin.toInteger()}°F). " +
                    "System is NOT winterized — consider shutting off irrigation water supply.")
                atomicState.lastFreezeAlert = now()
            }
        }
    } catch (Exception e) { logD "checkPreFreezeAlert: ${e.message}" }
}

// ── appButtonHandler additions for new buttons ─────────────────────────────────
// (injected via the existing appButtonHandler — see additions below)

// ═══════════════════════════════════════════════════════════════════════════════
// LOG AUTO-RESET
// ═══════════════════════════════════════════════════════════════════════════════

private void scheduleLogAutoReset() {
    unschedule("autoResetLog"); atomicState.remove("logAutoResetAt")
    int lv = activeLogLevel()
    if (lv >= LOG_DEBUG) {
        int mins = (settings.logAutoResetMins ?: "30") as int
        runIn(mins * 60, "autoResetLog")
        atomicState.logAutoResetAt = now() + (mins * 60 * 1000L)
        logI "Log ${logLevelName(lv)} auto-resets in ${mins} min"
    }
}

void autoResetLog() {
    logI "Auto-resetting log to Info"
    app.updateSetting("logLevel", [type:"enum", value:LOG_INFO.toString()])
    atomicState.remove("logAutoResetAt")
}

// ═══════════════════════════════════════════════════════════════════════════════
// BUTTON HANDLER
// ═══════════════════════════════════════════════════════════════════════════════

void appButtonHandler(String btn) {
    logD "Button: ${btn}"
    if (btn == "btnRunNow")      {
        // Manual run: bypass the schedule-based gates, keep the physical ones.
        atomicState.manualCycleRequest = true
        logI "Manual run requested: schedule gates (day, TOU, forecast, season) bypassed; soil moisture and safety guards still apply"
        runIn(2, "irrigationCycle"); return
    }
    if (btn == "btnCloseAll")    { closeAllValves(); atomicState.cycleRunning=false; atomicState.activeZone=null; updateAppLabel(); return }
    if (btn == "btnCalcEt")      { runIn(1, "captureSolarNoon"); runIn(10, "manualCalcEt"); return }
    if (btn == "btnResetLogNow") { app.updateSetting("logLevel",[type:"enum",value:LOG_INFO.toString()]); unschedule("autoResetLog"); atomicState.remove("logAutoResetAt"); logI "Log reset to Info"; return }
    if (btn == "btnTestNoaa")    { atomicState.noaaTestResult = testNoaaConnection(); return }
    if (btn == "btnFetchNoaa")   { runIn(2, "fetchNoaaDailyForecast"); logI "Manual NOAA forecast fetch triggered"; return }
    def mStart = btn =~ /^btnManualStart_(\d+)$/; if (mStart) { manualStartZone(mStart[0][1] as int); return }
    def mStop  = btn =~ /^btnManualStop_(\d+)$/;  if (mStop)  { manualStopZone(mStop[0][1] as int);   return }
    def rAdv   = btn =~ /^btnResetAdv_(\d+)$/
    if (rAdv) {
        int z = rAdv[0][1] as int
        ["zoneKcOverride_${z}","zoneRootOverride_${z}","zonePrecipOverride_${z}","zoneMadOverride_${z}"].each { app.removeSetting(it) }
        logI "Zone ${z} advanced overrides cleared"; return
    }
    if (btn == "btnResetWaterUsage") {
        if (settings.confirmResetWater) {
            resetWaterUsage()
            app.removeSetting("confirmResetWater")
            logI "Water-usage totals reset by user"
        } else {
            app.updateSetting("confirmResetWater",[type:"bool",value:true])
        }
        return
    }
    if (btn == "btnNewHouseReset") {
        if (settings.confirmNewHouse) {
            resetForNewHouse()
            app.removeSetting("confirmNewHouse")
            logI "Accumulated data reset for new house by user"
        } else {
            app.updateSetting("confirmNewHouse",[type:"bool",value:true])
        }
        return
    }
    // Seasonal lifecycle buttons
    if (btn == "btnStartupInspection") { startStartupInspection(); return }
    if (btn == "btnEndStartupInspection") { endStartupInspection(); return }
}


void manualCalcEt() { BigDecimal eto=calculateETo(); atomicState.lastEto=eto; logI "Manual ET: ${String.format('%.3f',eto as double)} in/day" }

// ── Data reset helpers ─────────────────────────────────────────────────────────
// Clears the water-usage counters only (monthly buckets + all-time total). Keeps
// everything else. Used by the "Reset water-usage totals" button and as part of
// the new-house reset.
void resetWaterUsage() {
    // monthlyGallons_* keys are dynamic (one per month). The rest of the app addresses
    // them by computing the month key rather than enumerating state keys (atomicState
    // key enumeration isn't relied on elsewhere), so we clear by walking back a wide
    // range of months — far more than any realistic history.
    Calendar c = Calendar.getInstance(location.timeZone)
    (0..72).each { mo ->
        Calendar cc = Calendar.getInstance(location.timeZone)
        cc.add(Calendar.MONTH, -mo)
        String mk = cc.getTime().format("yyyy-MM", location.timeZone)
        atomicState.remove("monthlyGallons_${mk}")
    }
    atomicState.remove("totalGallonsAllTime")
    int zc = safeZoneCount()
    (1..zc).each { z -> atomicState.remove("lastGallons_${z}") }
    logI "Water-usage totals cleared"
    updateAppLabel()
}

// Clears accumulated HISTORICAL and LOCATION-SPECIFIC state for a move to a new
// house, while preserving all zone/guard CONFIGURATION (settings). Location itself
// (lat/lon/elevation/timezone) lives on the hub, not in the app, so there is nothing
// to clear here for that — the user updates the hub's Location after moving and the
// app reads it live.
void resetForNewHouse() {
    resetWaterUsage()
    // Cycle + decision history
    ["cycleHistory","lastCycleDate","lastCycleDone","lastCycleWindowMin",
     "lastRunTimestamp","lastSkipReason","lastGuardResult","abortReason","abortCycle"].each { atomicState.remove(it) }
    // ET state + normals cache (normals are location-specific)
    ["lastEto","lastEtInputs","etNormalsSource","etNormalsGhcndId","etNormalsRefreshedAt"].each { atomicState.remove(it) }
    // NOAA forecast cache (tied to old coordinates/grid)
    ["noaaCacheDate","noaaCachePop","noaaCacheQpf","noaaCacheTmax","noaaCacheTmin",
     "noaaTestResult","noaaTestTimestamp","noaaPointsCache","noaaGridUrl","noaaForecastUrl"].each { atomicState.remove(it) }
    // Solar-noon capture (location-specific)
    ["solarNoonDate","solarNoonReading","solarNoonSanityOk"].each { atomicState.remove(it) }
    // Sensor liveness + pressure history + seeding progress (per zone)
    atomicState.remove("pressureHistory")
    atomicState.remove("stationLastSeen")
    atomicState.remove("bucketLastDate")
    atomicState.remove("ddLearnerVer")
    atomicState.remove("predictIntervalSeeded")
    atomicState.remove("pendingSkipNotices")
    atomicState.remove("forecastSkipPending")
    atomicState.remove("verifyBypassPredictive")
    int zc = safeZoneCount()
    (1..zc).each { z ->
        ["sensorLastSeen_","sensorLastValue_","lastRuntime_","zoneLastRun_",
         "consecutiveSensorSkips_","orphanSince_","ddLastVal_","ddLastTime_","ddRatePerDay_","ddRateBaseline_","ddSettleUntil_","ddSettleExt_","zoneLastWaterMs_","bucketIn_","bucketWasFresh_"].each { k -> atomicState.remove("${k}${z}") }
    }
    logI "🏠 New-house reset complete — history and location cache cleared; configuration kept. " +
         "Remember to update the hub's Location (lat/lon/elevation) for the new address."
    sendNotification("notifyOnComplete", "🏠 Reset for new house — update hub Location next", false)
    // Re-initialize so schedules/subscriptions re-seed cleanly against current devices
    runIn(2, "initializeFromReset")
    updateAppLabel()
}

// Small wrapper so the reset can re-run init on a fresh slate without re-entrancy.
void initializeFromReset() { updated() }

// ═══════════════════════════════════════════════════════════════════════════════
// SCHEDULED HANDLERS
// ═══════════════════════════════════════════════════════════════════════════════

void failsafeClose() {
    // Morning digest: the failsafe time marks the end of the watering window, so any
    // queued overnight skip notices are delivered now as one summary.
    flushSkipNotices()
    // Only flag as an alarm condition if a cycle was actually running when the
    // failsafe fired — otherwise this is routine end-of-day housekeeping and
    // should not light up the red badge in the installed-apps list
    boolean cycleWasRunning = atomicState.cycleRunning as boolean
    if (cycleWasRunning) {
        logW "Failsafe close — cycle was running, force-closing all valves"
        atomicState.failsafeTriggered = true
        sendNotification("notifyOnFailsafe", "🔴 Failsafe triggered — valves force-closed. Check your schedule.")
    } else {
        logI "Failsafe close — routine scheduled close, no cycle was active"
        atomicState.failsafeTriggered = false
    }
    closeAllValves()
    atomicState.cycleRunning = false
    atomicState.activeZone   = null
    atomicState.remove("abortCycle")
    updateAppLabel()
}
void midnightReset() {
    checkPauseAutoResume()
    atomicState.remove("abortCycle")
    atomicState.remove("abortReason")
    atomicState.failsafeTriggered = false
    atomicState.lastCycleDone     = false   // reset DST double-run guard for tomorrow
    logD "Midnight reset"
    // Schedule sunrise-relative start for today if configured
    if (settings.sunriseRelativeStart) runIn(30, "scheduleSunriseRelativeStart")
    // Re-evaluate grass-seed scheduling: phases advance daily and programs complete,
    // so a zone that was seeding may no longer be (or vice versa). Clear the slot
    // schedules and re-add them only if some zone is still in a germination phase.
    unschedule("seedCycleSlot0"); unschedule("seedCycleSlot1"); unschedule("seedCycleSlot2")
    if (anyZoneSeeding()) {
        def sp0 = (SEED_CYCLE_TIMES[0] as String).split(":")
        def sp1 = (SEED_CYCLE_TIMES[1] as String).split(":")
        def sp2 = (SEED_CYCLE_TIMES[2] as String).split(":")
        schedule("0 ${sp0[1] as int} ${sp0[0] as int} * * ?", "seedCycleSlot0")
        schedule("0 ${sp1[1] as int} ${sp1[0] as int} * * ?", "seedCycleSlot1")
        schedule("0 ${sp2[1] as int} ${sp2[0] as int} * * ?", "seedCycleSlot2")
        logD "🌱 Seed cycles re-scheduled (zones still in germination)"
    }
    updateAppLabel()
}

// Parses an NWS gridpoint validTime string "ISO8601-start/ISO8601-duration" into
// [startMs, endMs]. Duration examples: PT6H, PT1H, PT30M, P1DT6H, P1D. Returns null
// on any parse failure so a malformed entry can never break the forecast fetch.
// Uses Date.parse (sandbox-safe, same pattern as the NOAA period parsing elsewhere).
private List parseNoaaValidTime(String vt) {
    try {
        if (!vt || !vt.contains("/")) return null
        String startStr = vt.substring(0, vt.indexOf("/"))
        String durStr   = vt.substring(vt.indexOf("/") + 1)
        // NWS uses forms like 2026-06-01T18:00:00+00:00. Date.parse with XXX handles
        // the colon-offset; normalize a trailing 'Z' to +0000 just in case.
        String norm = startStr.replace("Z", "+00:00")
        long startMs = Date.parse("yyyy-MM-dd'T'HH:mm:ssXXX", norm).time
        // Parse ISO-8601 duration: P[nD]T[nH][nM]
        long durMs = 0L
        def dm = (durStr =~ /P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?)?/)
        if (dm.find()) {
            if (dm.group(1)) durMs += (dm.group(1) as long) * 86400000L
            if (dm.group(2)) durMs += (dm.group(2) as long) * 3600000L
            if (dm.group(3)) durMs += (dm.group(3) as long) * 60000L
        }
        if (durMs <= 0L) durMs = 3600000L  // default 1h if duration unparseable
        return [startMs, startMs + durMs]
    } catch (Exception e) { logD "parseNoaaValidTime('${vt}'): ${e.message}"; return null }
}

// ── Daily NOAA forecast cache ─────────────────────────────────────────────────
// Fetches PoP/QPF for tonight and Tmax/Tmin for today. Runs at 2 PM daily.
// Data is used at 4 AM by checkAllGuards() (PoP guard) and calcHargreaves()
// (accurate dT instead of hardcoded estimate).
// This is NOAA's unique contribution — a local station cannot predict the future.
//
// ── PLANNED NEXT-VERSION ENHANCEMENT: reliable QPF from gridpoints ────────────
// The named-period /forecast endpoint (used below for PoP) frequently OMITS
// quantitativePrecipitation entirely — NWS only attaches it to periods when a
// forecaster entered a specific amount. That is why the QPF display can show "—"
// even when PoP is 100%. This is a known NWS API behavior, not an app bug; PoP
// alone already drives the skip decision, so QPF is informational/secondary.
//
// When we next revise this app, populate QPF from the gridpoints data we ALREADY
// fetch above (forecastGridData → gp.quantitativePrecipitation), which reliably
// contains it as an hourly/6-hourly time series. Sketch:
//   def qpfSeries = gp.quantitativePrecipitation?.values   // [{validTime, value}, ...]
//   - value is in the unit given by gp.quantitativePrecipitation.uom (usually mm,
//     wmoUnit:mm → convert mmToIn); each entry covers a duration encoded in
//     validTime as ISO-8601 "<start>/<PnYnMnDTnHnMnS>" (e.g. ".../PT6H").
//   - Sum the entries whose validTime window overlaps tonight (roughly the
//     hours from the cycle start back ~12h, or simply the next 0000Z–1200Z block)
//     to get tonight's total expected liquid, then store as atomicState.noaaCacheQpf.
//   - Parsing the duration string is the only fiddly part; keep it in a small
//     helper (parseIso8601DurationHours) with a try/catch so a malformed entry
//     can never break the cycle. Leave the PoP path below unchanged.
void fetchNoaaDailyForecast() {
    if (isLocalOnlyMode() || !settings.noaaFallbackEnabled) { logD "fetchNoaaDailyForecast: skipped (local-only or NOAA disabled)"; return }
    logI "Fetching NOAA daily forecast (PoP, QPF, Tmax, Tmin)..."
    try {
        def points = noaaFetch("https://api.weather.gov/points/${noaaCoord()}")
        if (!points?.properties) { logW "NOAA daily: no points data — check coordinates ${noaaCoord()}"; return }

        // ── Tmax/Tmin + QPF from grid data ────────────────────────────────
        // forecastGridData reliably contains quantitativePrecipitation (the named-
        // period /forecast endpoint often omits it). We sum the overnight entries
        // to get tonight's expected liquid total.
        BigDecimal gridQpfIn = null
        String gridUrl = points.properties.forecastGridData as String
        if (gridUrl) {
            def grid = noaaFetch(gridUrl)
            def gp   = grid?.properties
            if (gp) {
                def tMaxC = gp.maxTemperature?.values?.getAt(0)?.value
                def tMinC = gp.minTemperature?.values?.getAt(0)?.value
                if (tMaxC != null) atomicState.noaaCacheTmax = cToF(tMaxC as BigDecimal)
                if (tMinC != null) atomicState.noaaCacheTmin = cToF(tMinC as BigDecimal)
                logD "NOAA Tmax=${atomicState.noaaCacheTmax}°F Tmin=${atomicState.noaaCacheTmin}°F"

                // QPF: sum entries whose window overlaps tonight (now → +14h, covering
                // the overnight cycle start). Unit is in uom (usually wmoUnit:mm).
                try {
                    def qpfObj = gp.quantitativePrecipitation
                    def qpfVals = qpfObj?.values
                    if (qpfVals) {
                        boolean isMm = ((qpfObj.uom ?: "") as String).toLowerCase().contains("mm")
                        long nowMs   = now()
                        long windowEndMs = nowMs + (14L * 3600000L)  // next 14 hours
                        BigDecimal sum = 0G
                        boolean any = false
                        qpfVals.each { entry ->
                            def vt = entry?.validTime as String
                            def v  = entry?.value
                            if (vt && v != null) {
                                def parsed = parseNoaaValidTime(vt)  // [startMs, endMs]
                                if (parsed && parsed[1] > nowMs && parsed[0] < windowEndMs) {
                                    sum += (v as BigDecimal); any = true
                                }
                            }
                        }
                        if (any) {
                            gridQpfIn = (isMm ? mmToIn(sum) : sum).setScale(2, BigDecimal.ROUND_HALF_UP)
                            logD "NOAA grid QPF (tonight window): ${gridQpfIn}\" (raw sum ${sum}${isMm ? 'mm' : 'in'})"
                        }
                    }
                } catch (Exception qe) { logD "Grid QPF parse: ${qe.message}" }
            }
        }

        // ── Tonight PoP/QPF from standard forecast ────────────────────────
        // NWS /forecast provides named periods ("Tonight", "Friday Night", etc.)
        // each with probabilityOfPrecipitation. We find the next overnight period.
        String forecastUrl = points.properties.forecast as String
        if (forecastUrl) {
            def forecast = noaaFetch(forecastUrl)
            def periods  = forecast?.properties?.periods
            if (periods) {
                // Find tonight's overnight period (first period with "night" in name)
                def tonight = periods.find { p ->
                    def name = (p.name as String)?.toLowerCase() ?: ""
                    return name.contains("night")
                } ?: periods.getAt(0)

                def pop = tonight?.probabilityOfPrecipitation?.value
                def qpf = tonight?.quantitativePrecipitation?.value

                atomicState.noaaCachePop = (pop != null) ? (pop as Integer) : null
                // Prefer the grid-derived QPF (reliable); fall back to the named-period
                // value when grid data was unavailable.
                if (gridQpfIn != null) {
                    atomicState.noaaCacheQpf = gridQpfIn
                } else if (qpf != null) {
                    String qpfUnit = tonight?.quantitativePrecipitation?.unitCode ?: ""
                    BigDecimal qpfBD = qpf as BigDecimal
                    atomicState.noaaCacheQpf = (qpfUnit.contains("mm") ? mmToIn(qpfBD) : qpfBD)
                                               .setScale(2, BigDecimal.ROUND_HALF_UP)
                } else {
                    atomicState.noaaCacheQpf = null
                }

                logI "NOAA Tonight (${tonight?.name}): PoP=${atomicState.noaaCachePop}% " +
                     "QPF=${atomicState.noaaCacheQpf}\" | ${tonight?.shortForecast ?: 'no summary'}"
            }
        }

        atomicState.noaaCacheDate   = new Date().format("yyyy-MM-dd", location.timeZone)
        atomicState.lastNoaaResult  = [
            timestamp: ts(),
            result: "Daily forecast: PoP=${atomicState.noaaCachePop}% " +
                    "QPF=${atomicState.noaaCacheQpf}\" " +
                    "Tmax=${atomicState.noaaCacheTmax}°F Tmin=${atomicState.noaaCacheTmin}°F"
        ]

    } catch (Exception e) {
        logW "fetchNoaaDailyForecast: ${e.message}"
    }
}

void weatherStationHandler(evt) {
    atomicState.stationLastSeen = now()
    logT "Station heartbeat: ${evt.name}=${evt.value}"
}

void pressureEventHandler(evt) {
    // Track rolling 3-hour pressure history for storm approach guard
    try {
        BigDecimal p = evt.value as BigDecimal
        if (p < 26G || p > 32G) { logD "pressureEventHandler: value ${p} out of range — skipping"; return }
        def history = (atomicState.pressureHistory ?: []) as List
        history << [t: now(), p: p]
        // Cap at 50 entries — at 1-per-minute that is ~50 min of history,
        // more than enough for the 30-min minimum window in calcPressureTrend().
        // remove(0) is O(1) vs the previous findAll pruning which deserialised
        // up to 240 items on every single barometer event.
        while (history.size() > 50) history.remove(0)
        atomicState.pressureHistory = history
        logT "Pressure: ${p} inHg, history ${history.size()} pts"
    } catch (e) { logD "pressureEventHandler: ${e.message}" }
}

// Returns the pressure change (inHg) over the last ~3 hours, or null if insufficient data
private BigDecimal calcPressureTrend() {
    def history = atomicState.pressureHistory
    if (!history || history.size() < 2) return null
    // List is maintained in append order (oldest→newest) by pressureEventHandler
    // so first() is always oldest and last() is always newest — no sort needed
    def oldest = history.first()
    def newest = history.last()
    long ageMs = (newest.t as Long) - (oldest.t as Long)
    if (ageMs < 1800000L) return null  // need at least 30 minutes of history
    // Return total change (negative = dropping)
    return ((newest.p as BigDecimal) - (oldest.p as BigDecimal)).setScale(3, BigDecimal.ROUND_HALF_UP)
}

// Master switch — when true, NO internet calls happen anywhere in the app.
// Defaults to true (local-only) when the setting has never been touched.
private boolean isLocalOnlyMode() {
    // settings.localOnlyMode is null on first install — default to true (safest)
    return settings.localOnlyMode != false
}

// Returns true if the station has reported within stationStaleMins (or NOAA not enabled)
private boolean isStationFresh() {
    if (isLocalOnlyMode())             return true  // no fallback possible — always use station
    if (!settings.noaaFallbackEnabled) return true  // no fallback configured
    int maxMins  = (settings.stationStaleMins ?: "30") as int
    def lastSeen = atomicState.stationLastSeen
    if (lastSeen == null) {
        // Log at debug — this fires from UI renders on first install before any station event
        logD "isStationFresh: station not seen since app start"
        return false
    }
    boolean fresh = (now() - (lastSeen as Long)) <= (maxMins * 60000L)
    if (!fresh) logW "isStationFresh: station stale — last seen ${((now()-(lastSeen as Long))/60000).toInteger()} min ago (max ${maxMins} min)"
    return fresh
}

void guardCheckHandler(evt) {
    if (!atomicState.cycleRunning || !settings.cancelOnGuardFail) return
    logD "Guard event mid-cycle: ${evt.name}=${evt.value}"
    def r = checkAllGuards()
    if (!r.passed) { logW "Mid-cycle guard fail: ${r.reason}"; atomicState.abortCycle=true; atomicState.abortReason=r.reason; runIn(1,"abortCycleNow") }
}

void abortCycleNow() {
    if (!atomicState.cycleRunning) return
    logW "Aborting: ${atomicState.abortReason}"
    unschedule("closeZoneAndAdvance"); unschedule("startNextZone");
    unschedule("soakAndContinue"); unschedule("resumeAfterSoak");
    closeAllValves()
    atomicState.cycleRunning     = false
    atomicState.activeZone       = null
    atomicState.lastSkipReason   = "Aborted: ${atomicState.abortReason}"
    atomicState.lastRunTimestamp = ts() + " (aborted)"
    // Close master valve
    if (settings.masterValve) {
        try { if (settings.masterValve.hasCommand("off")) settings.masterValve.off() else settings.masterValve.close() } catch (Exception ignored) { }
    }
    recordCycleHistory("aborted", atomicState.abortReason as String)
    sendNotification("notifyOnAbort", "⚠️ Cycle aborted: ${atomicState.abortReason}")
    updateAppLabel()
}

// ═══════════════════════════════════════════════════════════════════════════════
// IRRIGATION CYCLE — event-driven, non-blocking runIn architecture
// ═══════════════════════════════════════════════════════════════════════════════

// Returns true if the current local time is within the configured TOU peak-rate window.
// Handles windows that wrap past midnight (start >= end). Shared by the main cycle and the
// midday top-up so both honor the same time-of-use restriction consistently.
// Is the cached NOAA daily forecast usable for a decision being made right now?
// The forecast is fetched at 2 PM and describes "tonight", but the cycle it protects runs
// after midnight, so a same-day-only test silently disarmed the predictive guard for
// every pre-dawn cycle (field data 2026-07-17: an 82% forecast aborted an evening attempt
// and was then invisible to the 3:46 AM run). Valid if fetched today, or fetched
// yesterday and it is still before local noon, which covers the pre-dawn window without
// letting a genuinely old forecast drive an afternoon decision. Shared by the guard and
// the diagnostics display so the two can never disagree.
// Minimum hours between predict-ahead waterings for a zone: per-zone override if set,
// else the plant-type default. 0 disables the interval entirely.
// Minutes from now until the failsafe close, or -1 when no failsafe is set or the time
// cannot be parsed. Shared by the queue triage and the window warning so both agree.
private long availableMinutesBeforeFailsafe() {
    if (!settings.failsafeTime) return -1L
    try {
        def startCal    = Calendar.getInstance(location.timeZone)
        def failsafeCal = Calendar.getInstance(location.timeZone)
        failsafeCal.setTime(toDateTime(settings.failsafeTime))
        failsafeCal.set(Calendar.YEAR,         startCal.get(Calendar.YEAR))
        failsafeCal.set(Calendar.MONTH,        startCal.get(Calendar.MONTH))
        failsafeCal.set(Calendar.DAY_OF_MONTH, startCal.get(Calendar.DAY_OF_MONTH))
        return (failsafeCal.timeInMillis - startCal.timeInMillis) / 60000L
    } catch (Exception e) { logD "availableMinutesBeforeFailsafe: ${e.message}"; return -1L }
}

// Order the night's queue by need and, when it cannot finish before the failsafe, shed
// the discretionary zones instead of letting the failsafe truncate whichever zone
// happened to be last in numeric order. Field case 2026-07-24: a 366 min window against
// 336 min available queued all four zones anyway, so the final zone was guaranteed to be
// cut mid-sequence. Predict-ahead zones are ABOVE threshold by definition, so dropping
// one costs nothing tonight and it is re-evaluated tomorrow; a zone genuinely below
// threshold is never dropped. Whatever remains runs driest-first, so any residual
// overrun always lands on the least needy zone rather than an arbitrary one.
private List triageQueueForWindow(List queue) {
    if (!queue || queue.size() < 2) return queue
    // margin = threshold - soil: positive when genuinely dry, negative for predict-ahead,
    // so a descending sort puts the driest first and discretionary zones last.
    List ordered = queue.sort { a, b -> ((b.margin ?: 0) as int) <=> ((a.margin ?: 0) as int) }
    long availableMin = availableMinutesBeforeFailsafe()
    if (availableMin <= 0) return ordered
    int windowMin = calcTotalCycleWindowMin(ordered)
    while (windowMin > availableMin && ordered.size() > 1 && ordered[-1].predict) {
        def dropped = ordered[-1]
        ordered = ordered[0..(ordered.size() - 2)]
        windowMin = calcTotalCycleWindowMin(ordered)
        logW "Queue triage: deferring predict-ahead zone ${dropped.name} (${dropped.runtimeMin}min). " +
             "Projected window exceeded the ${availableMin}min before failsafe; that zone is still above " +
             "threshold, so it waits for tomorrow. Window now ${windowMin}min."
    }
    if (windowMin > availableMin) {
        logW "Queue triage: ${windowMin}min still exceeds ${availableMin}min with only genuinely-dry zones " +
             "left. Driest zones run first so the failsafe truncates the least needy. Consider starting " +
             "earlier, pushing the failsafe later, or reducing base runtimes."
    }
    return ordered
}

private int predictMinIntervalHrs(int z) {
    def ovr = settings["zonePredictMinHrs_${z}"]
    if (ovr != null && "${ovr}" != "") return Math.max(0, Math.min(168, ovr as int))
    String plant = settings["zonePlant_${z}"] ?: "Cool Season Turf"
    return (PREDICT_MIN_HRS[plant] ?: 36) as int
}

private boolean isNoaaCacheUsable() {
    def cacheDate = atomicState.noaaCacheDate
    if (!cacheDate) return false
    String cToday = new Date().format("yyyy-MM-dd", location.timeZone)
    if (cacheDate == cToday) return true
    String cYday = new Date(now() - 86400000L).format("yyyy-MM-dd", location.timeZone)
    return (cacheDate == cYday &&
            Calendar.getInstance(location.timeZone).get(Calendar.HOUR_OF_DAY) < 12)
}

private boolean isInTouPeak() {
    if (!settings.touGuardEnabled || !settings.touPeakStart || !settings.touPeakEnd) return false
    try {
        def nowCal = Calendar.getInstance(location.timeZone)
        def peakS  = Calendar.getInstance(location.timeZone)
        def peakE  = Calendar.getInstance(location.timeZone)
        peakS.setTime(toDateTime(settings.touPeakStart))
        peakE.setTime(toDateTime(settings.touPeakEnd))
        [peakS,peakE].each { c ->
            c.set(Calendar.YEAR,  nowCal.get(Calendar.YEAR))
            c.set(Calendar.MONTH, nowCal.get(Calendar.MONTH))
            c.set(Calendar.DAY_OF_MONTH, nowCal.get(Calendar.DAY_OF_MONTH))
        }
        long nowMs = nowCal.timeInMillis, sMs = peakS.timeInMillis, eMs = peakE.timeInMillis
        return (sMs >= eMs) ? (nowMs >= sMs || nowMs < eMs) : (nowMs >= sMs && nowMs < eMs)
    } catch (Exception e) { logW "isInTouPeak: ${e.message}"; return false }
}

// Auto-resume from a timed pause. The pauseUntil date input existed since the pause
// feature shipped but nothing ever READ it, so a pause with a resume date never resumed
// (field 2026-08-27: paused Aug 21 "until Aug 24", still paused six days later with three
// zones below threshold in 94F heat). Clears systemPaused once the local date reaches the
// resume date. Called from irrigationCycle (before the pause gate), midnightReset, and
// initialize, so it fires whichever comes first and cannot be missed by a reboot.
// Returns true if it just resumed.
private boolean checkPauseAutoResume() {
    if (!settings.systemPaused || !settings.pauseUntil) return false
    try {
        def nowCal = Calendar.getInstance(location.timeZone)
        def endCal = Calendar.getInstance(location.timeZone)
        endCal.setTime(toDateTime(settings.pauseUntil.toString()))
        // Compare whole local days: resume once today has reached the resume date.
        nowCal.set(Calendar.HOUR_OF_DAY,0); nowCal.set(Calendar.MINUTE,0)
        nowCal.set(Calendar.SECOND,0);      nowCal.set(Calendar.MILLISECOND,0)
        endCal.set(Calendar.HOUR_OF_DAY,0); endCal.set(Calendar.MINUTE,0)
        endCal.set(Calendar.SECOND,0);      endCal.set(Calendar.MILLISECOND,0)
        if (nowCal.timeInMillis < endCal.timeInMillis) return false
        app.updateSetting("systemPaused", [type:"bool",  value:false])
        app.updateSetting("pauseUntil",   [type:"date",  value:""])
        logI "Pause auto-resume: resume date reached, watering re-enabled"
        sendNotification("notifyOnSkip", "System resumed automatically (paused until date reached)")
        updateAppLabel()
        return true
    } catch (Exception e) { logW "checkPauseAutoResume: ${e.message}"; return false }
}

void irrigationCycle() {
    // Manual run flag. Hubitat schedules handlers by NAME, so a parameter cannot be passed
    // through runIn; the button sets this one-shot state key instead. A manual run drops
    // the SCHEDULE-based gates (already-ran-today, TOU peak, active-day, season, and the
    // rain FORECAST) because the user is deliberately overriding the schedule and can see
    // the sky. Every PHYSICAL gate stays: pause, winterize, rain falling now, wind, leaf
    // wetness, freeze, frost, storm, plus per-zone soil moisture, ET-scaled runtimes and
    // cycle-and-soak. A zone already above threshold is still skipped, because pressing
    // the button asks the app to water what needs water now, not to flood wet zones.
    boolean manualRun = (atomicState.manualCycleRequest == true)
    atomicState.remove("manualCycleRequest")
    logI "=== Cycle starting ===" + (manualRun ? " (manual run: schedule gates bypassed)" : "")
    if (atomicState.cycleRunning) { logW "Already running — skipping"; return }

    // ── Pre-cycle guards: pause, winterize, vacation, TOU ───────────────────
    checkPauseAutoResume()
    if (settings.systemPaused) {
        logI "Cycle skipped — system paused"
        atomicState.lastSkipReason   = "System paused"
        atomicState.lastRunTimestamp = ts() + " (skipped)"
        updateAppLabel(); return
    }
    if (settings.winterizedMode) {
        logI "Cycle skipped — system winterized"
        atomicState.lastSkipReason   = "System winterized"
        atomicState.lastRunTimestamp = ts() + " (skipped)"
        updateAppLabel(); return
    }
    // DST double-run guard: if cycle already ran today, skip
    String todayDate = new Date().format("yyyy-MM-dd", location.timeZone)
    if (!manualRun && atomicState.lastCycleDate == todayDate && atomicState.lastCycleDone) {
        logI "Cycle skipped — already ran today (${todayDate}), DST guard"
        return
    }
    // TOU guard: skip if currently in peak electricity hours
    if (!manualRun && isInTouPeak()) {
        logI "Cycle skipped — within TOU peak hours (${fmtTime(settings.touPeakStart)}–${fmtTime(settings.touPeakEnd)})"
        atomicState.lastSkipReason   = "TOU peak hours"
        atomicState.lastRunTimestamp = ts() + " (skipped)"
        notifyMainSkip("⚡ Cycle skipped — within time-of-use peak hours")
        updateAppLabel(); return
    }
    // Leaf wetness guard
    if (settings.leafWetGuardEnabled && settings.leafWetnessSensor) {
        try {
            String lwAttr = (settings.leafWetnessAttr ?: "leafWetness")
            BigDecimal lwVal = safeGetAttrBD(settings.leafWetnessSensor, lwAttr)
            BigDecimal lwThr = (settings.leafWetnessThreshold ?: 30G) as BigDecimal
            if (lwVal != null && lwVal > lwThr) {
                logI "Cycle skipped — leaf wetness ${lwVal} > threshold ${lwThr}"
                atomicState.lastSkipReason   = "Leaf wetness ${lwVal} > ${lwThr}"
                atomicState.lastRunTimestamp = ts() + " (skipped)"
                updateAppLabel(); return
            }
        } catch (Exception e) { logW "Leaf wetness guard: ${e.message}" }
    }
    // Wind guard — SUSTAINED wind is the cancel criterion. Gusts are transient and a
    // single gust should not cancel a whole night's watering, so by default gusts are
    // ignored here. An optional "severe gust" threshold (well above the sustained one)
    // can still skip if a genuine windstorm is blowing, but it's off unless configured.
    if (settings.windGuardEnabled && settings.guardWindMph && settings.weatherStation) {
        try {
            BigDecimal windThr = (settings.guardWindMph) as BigDecimal
            BigDecimal curWind = safeGetAttrBD(settings.weatherStation, resolvedAttr("attrWind","windSpeed"))
            boolean sustainedHigh = (curWind != null && curWind > windThr)

            // Optional severe-gust safety valve (off by default = pure sustained-only)
            boolean severeGust = false
            BigDecimal curGust = null
            if (settings.guardGustMph) {
                BigDecimal gustThr = (settings.guardGustMph) as BigDecimal
                curGust = safeGetAttrBD(settings.weatherStation, resolvedAttr("attrWindGust","windGust"))
                severeGust = (curGust != null && curGust > gustThr)
            }

            if (sustainedHigh || severeGust) {
                String detail = sustainedHigh ?
                    "${curWind}mph sustained > ${windThr}mph" :
                    "severe gust ${curGust}mph > ${settings.guardGustMph}mph"
                logI "Cycle skipped — wind ${detail}"
                atomicState.lastSkipReason   = "Wind: ${detail}"
                atomicState.lastRunTimestamp = ts() + " (skipped)"
                notifyMainSkip("💨 Cycle skipped — wind ${detail}")
                updateAppLabel(); return
            }
        } catch (Exception e) { logW "Wind guard: ${e.message}" }
    }

    if (!manualRun && !isTodayActiveDay()) {
        def day = new Date().format("EEEE", location.timeZone)
        logI "Not active today (${day})"
        atomicState.lastSkipReason   = "Not active day (${day})"
        atomicState.lastRunTimestamp = ts() + " (skipped)"
        updateAppLabel()
        return
    }

    // Compute ETo BEFORE the guard check so the displayed/stored value is always
    // current — even on nights we skip for rain/freeze/etc. ET is a cheap read-only
    // calculation; storing it on every cycle keeps the main-page "ETo" live instead
    // of going blank whenever recent cycles were skipped.
    def eto = calculateETo()
    // calculateETo() already stores lastEto (when positive). Here we only need the
    // local value for this cycle's runtime math; if it came back 0 (sensor offline),
    // fall back to the last good stored reading rather than watering on a zero.
    if ((eto as BigDecimal) <= 0G) {
        logW "ETo=0 calculated — sensor may be offline. Using last known ETo."
        eto = atomicState.lastEto ?: eto
    }

    // Cumulative water-balance ledger: update each fallback zone's bucket once per day
    // with (rain − ETc). ET loss accrues whether or not we end up watering, so this runs
    // before the guard checks — but it is date-guarded so a re-run on the same day (hub
    // restart, manual trigger) can't double-count the day's ET.
    String bToday = new Date().format("yyyy-MM-dd", location.timeZone)
    if (settings.bucketEnabled && atomicState.bucketLastDate != bToday) {
        updateBucketsDaily(eto as BigDecimal)
        atomicState.bucketLastDate = bToday
    }

    def gr = checkAllGuards(manualRun)
    atomicState.lastGuardResult = gr + [timestamp:ts()]
    if (!gr.passed) {
        logI "Guard failed: ${gr.reason}"
        atomicState.lastSkipReason   = gr.reason
        atomicState.lastRunTimestamp = ts() + " (skipped)"
        recordCycleHistory("skipped", gr.reason)
        notifyMainSkip("⏭️ Cycle skipped: ${gr.reason}")
        // Forecast-skip verification: a predictive skip trusts a forecast that has not
        // happened yet. Schedule a recheck before failsafe; if the promised rain never
        // arrives, the cycle runs late instead of the zones losing the whole night to a
        // busted thunderstorm forecast. Only the MAIN cycle schedules this (midday's
        // afternoon skip has tonight's cycle itself as the natural retry).
        if (settings.forecastVerifyEnabled != false && "${gr.reason}".startsWith("NOAA predictive")) {
            scheduleForecastVerify(gr.reason as String)
        }
        updateAppLabel()
        return
    }

    // Away mode ET scaling
    boolean isAway = (settings.awayPresence && settings.awayPresence.currentPresence == "not present") ||
                     (settings.awayMode && location.mode == settings.awayMode)
    if (isAway) {
        BigDecimal awayFactor = (settings.awayEtFactor ?: 0.6G) as BigDecimal
        eto = ((eto as BigDecimal) * awayFactor).setScale(4, BigDecimal.ROUND_HALF_UP)
        logI "Away mode active — ET scaled to ${awayFactor}×: ${String.format('%.3f',eto as double)} in/day"
    }
    def lat      = location.latitude as BigDecimal
    def mon      = Calendar.getInstance(location.timeZone).get(Calendar.MONTH) + 1
    def baseline = seasonalBaseline(lat, mon)
    logI "ETo: ${String.format('%.3f',eto as double)} | Baseline: ${String.format('%.3f',baseline as double)} in/day"

    int zc    = safeZoneCount()
    def queue = []
    (1..zc).each { z ->
        if ((settings["zoneEnabled_${z}"] == false)) { logI "Zone ${z}: disabled"; return }
        if (!settings["zoneValve_${z}"])             { logW "Zone ${z}: no valve";  return }
        def name = settings["zoneName_${z}"] ?: "Zone ${z}"

        // Manual credit — proportional reduction, not complete skip
        // If you manually watered this zone, subtract that time from tonight's runtime.
        // Only skip entirely if the manual run already covered the full planned amount.
        int creditMin = getManualCreditMinutes(z)

        // Soil evaluation with failure tolerance
        def sr = evaluateSoilForZone(z, eto as BigDecimal, baseline as BigDecimal)

        // Bucket reset on sensor recovery. The ledger is a fallback model used only while
        // a zone can't read its own soil. The moment a stale/failed sensor comes back
        // fresh, the sensor is the ground truth again, so the modeled deficit is obsolete
        // and we zero it (Option A). We detect the stale->fresh transition with a per-zone
        // "was fresh last cycle" flag so a zone that was never stale isn't re-zeroed daily
        // (which would defeat the background drift). Only acts when the bucket is enabled
        // and the flag actually flips, so a continuously-healthy zone is untouched.
        if (settings.bucketEnabled) {
            boolean wasFresh = (atomicState["bucketWasFresh_${z}"] == true)
            if (sr.fresh && !wasFresh) {
                if ((atomicState["bucketIn_${z}"] ?: 0G) != 0G) {
                    logI "Zone ${z} (${name}): sensor recovered, zeroing bucket (was ${atomicState["bucketIn_${z}"]} in); sensor is now ground truth"
                }
                atomicState["bucketIn_${z}"] = 0G
            }
            atomicState["bucketWasFresh_${z}"] = (sr.fresh == true)
        }

        // ── Water-balance: anomaly detection (notify-only) ───────────────────
        // If this zone is drying far faster than its own learned baseline, flag it —
        // likely a leak, broken emitter, or a sudden exposure change. Never changes the
        // watering decision; just warns.
        if (settings["zoneAnomalyDetect_${z}"] && sr.fresh) {
            def rate = atomicState["ddRatePerDay_${z}"]
            def base = atomicState["ddRateBaseline_${z}"]
            if (rate != null && base != null && (base as BigDecimal) > 0.5G) {
                BigDecimal ratio = ((rate as BigDecimal) / (base as BigDecimal)).setScale(2, BigDecimal.ROUND_HALF_UP)
                BigDecimal trip = (settings["anomalyRatio_${z}"] ?: "2.5") as BigDecimal
                if (ratio >= trip) {
                    logW "⚠️ Zone ${z} (${name}) drying ${ratio}× faster than normal (${rate}%/day vs ${base}%/day baseline)"
                    sendNotification("notifyOnAnomaly", "⚠️ ${name}: drying ${ratio}× faster than normal — possible leak or broken head")
                }
            }
        }

        // ── Water-balance: predict-ahead watering ────────────────────────────
        // If the zone is currently above threshold (so normal logic would skip) but the
        // learned dry-down rate says it will cross threshold before the next cycle, water
        // it tonight so it doesn't go dry mid-day tomorrow. Opt-in per zone; only acts on
        // a FRESH reading; never overrides a guard skip (we're already past guards here).
        boolean predictWater = false
        if (!sr.shouldWater && settings["zonePredictAhead_${z}"] && sr.fresh && sr.soilPct != null && sr.thresh != null) {
            // Minimum-interval guard. Predict-ahead is DISCRETIONARY early watering: the
            // zone is still above threshold, so nothing is dry yet. If it was watered
            // recently, both the sensor and deep-and-infrequent turf practice say wait.
            // Without this, an over-estimated dry-down rate waters a zone every night in
            // a self-feeding loop (field data 2026-07: all three turf zones firing
            // nightly, monthly water use 3.5x the prior month against roughly 1.3x the
            // ET demand). A zone that genuinely drops below threshold is still watered by
            // the normal path, so this can only prevent over-watering.
            int minHrs = predictMinIntervalHrs(z)
            def lastWaterMs = atomicState["zoneLastWaterMs_${z}"]
            BigDecimal sinceHrs = (lastWaterMs != null)
                ? (new BigDecimal(now() - (lastWaterMs as long)) / 3600000G) : null
            if (minHrs > 0 && sinceHrs != null && sinceHrs < (minHrs as BigDecimal)) {
                logI "Zone ${z} (${name}): predict-ahead held off, last watered ${sinceHrs.setScale(1, BigDecimal.ROUND_HALF_UP)}h ago (minimum ${minHrs}h)"
            } else {
                BigDecimal hrs = hoursUntilThreshold(z, sr.soilPct as int, sr.thresh as int)
                BigDecimal horizon = (settings["predictHorizonHrs_${z}"] ?: "24") as BigDecimal
                if (hrs != null && hrs <= horizon) {
                    predictWater = true
                    logI "Zone ${z} (${name}): predict-ahead — ${sr.soilPct}% now, ~${hrs}h to threshold (≤ ${horizon}h horizon) → watering early"
                }
            }
        }

        logI "Zone ${z} (${name}): ${sr.reason}${predictWater ? ' [predict-ahead]' : ''}"
        if (!sr.shouldWater && !predictWater) return

        int rtMin = calcZoneRuntime(z, eto, baseline)

        // Sensor-fallback water accounting (stale/failed/no sensor). Two mechanisms,
        // both gated on the non-fresh path so a healthy sensor (which already reflects
        // moisture directly) is never double-counted:
        //  • Bucket (preferred when enabled): the cumulative ET-minus-rain ledger sets
        //    the runtime from accumulated deficit — the general, multi-day form.
        //  • Rain credit (fallback when bucket off): subtract just today's rain.
        boolean fallbackWatered = false
        if (!sr.fresh) {
            if (settings.bucketEnabled) {
                int need = bucketDeficitMinutes(z)
                if (need <= RUNTIME_FLOOR) {
                    logI "Zone ${z} (${name}): sensor-fallback + bucket shows no deficit (${atomicState["bucketIn_${z}"]}in) — skipping"
                    return
                }
                // The ledger, not raw ET, dictates the fallback runtime.
                logI "Zone ${z} (${name}): sensor-fallback — bucket deficit drives runtime ${rtMin}min → ${need}min"
                rtMin = need
                fallbackWatered = true
            } else {
                int rainMin = rainCreditMinutes(z)
                if (rainMin > 0) {
                    int afterRain = rtMin - rainMin
                    if (afterRain <= RUNTIME_FLOOR) {
                        logI "Zone ${z} (${name}): sensor-fallback + rain credit ${rainMin}min ≥ planned ${rtMin}min — skipping (rain covered it)"
                        return
                    }
                    logI "Zone ${z} (${name}): sensor-fallback — crediting ${rainMin}min of measured rain, ${rtMin}min → ${afterRain}min"
                    rtMin = afterRain
                }
            }
        }

        if (creditMin > 0) {
            int adjusted = rtMin - creditMin
            if (adjusted <= RUNTIME_FLOOR) {
                // Manual run covered essentially the full planned runtime — skip
                logI "Zone ${z} (${name}): manual credit ${creditMin}min ≥ planned ${rtMin}min — skipping (fully watered)"
                return
            }
            // Partial credit — run for the remaining deficit
            logI "Zone ${z} (${name}): reducing ${rtMin}min by ${creditMin}min manual credit → running ${adjusted}min"
            rtMin = adjusted
        }

        atomicState["lastRuntime_${z}"] = rtMin
        // Bucket credit is applied at zone COMPLETION (closeZoneAndAdvance), not here.
        // Crediting at queue-commit charged the ledger for water an abort or failsafe
        // might never deliver, leaving the zone under-watered on later fallback days.
        // The queue entry is tagged; completion applies the delivered minutes.
        // Compute cycle-and-soak parameters for this zone and embed in queue entry
        Integer maxCycle = calcMaxCycleMin(z)
        boolean doSplit  = (maxCycle != null &&
                            settings["cycleSoakEnabled_${z}"] != false &&
                            maxCycle < rtMin)
        // Store soakMin in queue entry so mid-cycle settings changes
        // don't cause inconsistency within a zone's C&S sequence
        // Per-zone dynamic soak time — auto from soil type with optional override
        int soakMinQ = calcSoakMin(z)
        // predict and margin drive triageQueueForWindow: margin is (threshold - soil), so
        // it is positive for a genuinely dry zone and negative for a predict-ahead one.
        int qMargin = ((sr.thresh != null && sr.soilPct != null)
                       ? ((sr.thresh as int) - (sr.soilPct as int)) : 0)
        queue << [zone:z, name:name, runtimeMin:rtMin,
                  maxCycleMin:(doSplit ? maxCycle : rtMin),
                  fallback:fallbackWatered, predict:predictWater, margin:qMargin,
                  doSplit:doSplit, soakMin:soakMinQ]
    }

    if (queue.isEmpty()) {
        logI "No zones need water"
        atomicState.lastSkipReason   = "All zones satisfied or credited"
        atomicState.lastRunTimestamp = ts() + " (all satisfied)"
        updateAppLabel()
        return
    }

    // Order by need and shed discretionary zones if the night cannot fit before failsafe.
    queue = triageQueueForWindow(queue)

    atomicState.cycleRunning     = true
    atomicState.abortCycle       = false
    atomicState.abortReason      = null
    atomicState.failsafeTriggered = false   // clear any previous failsafe badge when a new cycle starts
    atomicState.zoneQueue        = queue
    atomicState.zoneQueueIdx     = 0
    atomicState.lastRunTimestamp = ts()
    atomicState.lastSkipReason   = "Cycle running"
    // Log queue summary including cycle-and-soak splits
    logI "Queue: ${queue.collect { e -> e.name+' '+e.runtimeMin+'min'+(e.doSplit?' (split '+e.maxCycleMin+'min cycles)':'') }.join(' → ')}"

    // Calculate total wall-clock window and warn if it might exceed the failsafe time
    int windowMin = calcTotalCycleWindowMin(queue)
    atomicState.lastCycleWindowMin = windowMin
    logI "Estimated total cycle window: ${windowMin} min (${String.format('%.1f', windowMin/60.0)} hrs)"
    long availableMin = availableMinutesBeforeFailsafe()
    if (availableMin > 0 && windowMin > availableMin) {
        logW "⚠️ Cycle window (${windowMin} min) may exceed available time before failsafe (${availableMin} min). " +
             "Consider starting earlier, pushing the failsafe later, or reducing base runtimes or soak time."
    }

    // Open master valve / pump relay before first zone
    if (settings.masterValve) {
        try {
            if (settings.masterValve.hasCommand("on")) settings.masterValve.on()
            else settings.masterValve.open()
            logI "Master valve opened"
        } catch (Exception e) { logW "Master valve open: ${e.message}" }
    }
    updateAppLabel()   // show 💧 Running immediately
    runIn(1, "startNextZone")
}

void startNextZone() {
    if (!atomicState.cycleRunning) { logD "startNextZone: not running"; return }
    if (atomicState.abortCycle)    { runIn(1,"abortCycleNow"); return }
    def queue = atomicState.zoneQueue ?: []
    int idx   = atomicState.zoneQueueIdx ?: 0
    if (idx >= queue.size()) {
        boolean wasSyringe = queue && queue[0]?.isSyringe
        boolean wasSeed    = queue && queue[0]?.isSeed
        boolean wasMidday  = queue && queue[0]?.isMidday
        logI "All zones done — safety close"
        closeAllValves()
        atomicState.cycleRunning     = false
        atomicState.activeZone       = null
        // Close master valve / pump relay after all zones
        if (settings.masterValve) {
            try {
                if (settings.masterValve.hasCommand("off")) settings.masterValve.off()
                else settings.masterValve.close()
                logI "Master valve closed"
            } catch (Exception e) { logW "Master valve close: ${e.message}" }
        }
        if (wasMidday) {
            // Midday top-up is a demand-based second check — real watering (water usage
            // is counted per-zone as normal), but NOT the main daily cycle, so don't set
            // the DST guard and don't claim the main "Cycle complete".
            atomicState.lastRunTimestamp = ts() + " (midday done)"
            atomicState.lastSkipReason   = "Midday top-up complete"
            logI "=== 🌡️ Midday top-up complete ==="
            sendNotification("notifyOnComplete", "🌡️ Midday top-up complete", false)
            updateAppLabel()
            return
        }
        if (wasSeed) {
            // Seeding cycles are an intraday germination program — NOT the main daily
            // irrigation. Don't set the DST guard (the normal cycle still runs), don't
            // record as a normal irrigation cycle, don't claim "Cycle complete".
            atomicState.lastRunTimestamp = ts() + " (seeding done)"
            atomicState.lastSkipReason   = "Seeding cycle complete"
            logI "=== 🌱 Seed cycle complete ==="
            sendNotification("notifyOnComplete", "🌱 Seeding cycle complete", false)
            updateAppLabel()
            return
        }
        if (wasSyringe) {
            // Syringe is cooling only — don't record as an irrigation cycle, don't set
            // the DST guard (the nightly cycle still needs to run), don't claim completion
            atomicState.lastRunTimestamp = ts() + " (syringe done)"
            atomicState.lastSkipReason   = "Heat-stress syringe complete"
            logI "=== Syringe complete ==="
            updateAppLabel()
            return
        }
        atomicState.lastRunTimestamp = ts() + " (complete)"
        atomicState.lastSkipReason   = "Completed normally"
        // DST guard: mark that cycle ran today
        atomicState.lastCycleDate = new Date().format("yyyy-MM-dd", location.timeZone)
        atomicState.lastCycleDone = true
        // Record cycle history entry
        recordCycleHistory("complete", null)
        sendNotification("notifyOnComplete", "✅ Cycle complete — all zones watered", false)
        logI "=== Cycle complete ==="
        updateAppLabel()   // show ✅ Complete
        return
    }
    def entry    = queue[idx]
    int z        = entry.zone as int
    int totalMin = entry.runtimeMin as int
    int maxCycle = (entry.maxCycleMin ?: totalMin) as int
    boolean doSplit = (entry.doSplit ?: false) as boolean
    boolean isSyr    = (entry.isSyringe ?: false) as boolean
    boolean isSeed   = (entry.isSeed ?: false) as boolean
    boolean isMidday = (entry.isMidday ?: false) as boolean
    boolean isFb     = (entry.fallback ?: false) as boolean

    if (!openValve(z, entry.name as String)) {
        logW "Zone ${z}: open failed — advancing"
        atomicState.zoneQueueIdx = idx + 1; runIn(2,"startNextZone"); return
    }
    atomicState.activeZone = z
    atomicState["zoneLastRun_${z}"] = ts()
    // Millis timestamp of the last real watering, used by the predict-ahead minimum
    // interval. Syringe is excluded: a few minutes of cooling mist is not irrigation.
    if (!isSyr) atomicState["zoneLastWaterMs_${z}"] = now()

    if (doSplit && maxCycle < totalMin) {
        // Cycle-and-soak: run first cycle, then hand off to soakAndContinue
        int thisCycle = Math.min(maxCycle, totalMin)
        int remaining = totalMin - thisCycle
        logI "Zone ${z} (${entry.name}): cycle-and-soak — ${thisCycle} min on, soak, ${remaining} min remaining"
        runIn(thisCycle * 60, "soakAndContinue",
              [data:[zone:z, idx:idx, name:entry.name, fallback:isFb, soakMin:(entry.soakMin ?: 30),
                     remainingMin:remaining, maxCycleMin:maxCycle, cycleNum:1]])
    } else {
        String tag = isSyr ? ' (syringe — cooling only)' : (isSeed ? ' (seeding)' : (isMidday ? ' (midday top-up)' : ' (no split needed)'))
        logI "Zone ${z} (${entry.name}): opened — ${totalMin} min${tag}"
        runIn(totalMin * 60, "closeZoneAndAdvance", [data:[zone:z, idx:idx, name:entry.name, isSyringe:isSyr, isSeed:isSeed, isMidday:isMidday, fallback:isFb]])
    }
}

void closeZoneAndAdvance(Map data) {
    if (!atomicState.cycleRunning) { logD "closeZoneAndAdvance: not running"; return }
    closeValve(data.zone as int, data.name as String)
    atomicState.activeZone = null
    logI "Zone ${data.zone} (${data.name}): closed"
    // Water usage tracking — estimate gallons for this zone run.
    // Skip syringe (cooling) runs: they are short evaporative bursts, not irrigation,
    // and counting them would inflate the water-usage totals.
    if (!data.isSyringe) {
        try {
            int z2 = data.zone as int
            int rtUsed = atomicState["lastRuntime_${z2}"] as int ?: 0
            // Bucket credit for a sensor-fallback zone: the zone has now actually
            // completed its run, so offset the ledger by the delivered minutes.
            // An aborted zone never reaches this point, so the deficit correctly
            // remains and the zone waters again on a later fallback day (the cap
            // bounds any resulting over-water; safer than over-crediting).
            if (data.fallback) bucketApplyWater(z2, rtUsed)
            BigDecimal precipRateIn = calcEffectivePrecipRate(z2)
            BigDecimal areaFt2 = (settings["zoneAreaFt2_${z2}"] ?: 1000G) as BigDecimal
            // gallons = (precipRate in/hr / 12) * area_ft2 * runtime_hr * 7.48 gal/ft3
            BigDecimal gal = (precipRateIn / 12G) * areaFt2 * (rtUsed / 60G) * 7.48G
            gal = gal.setScale(1, BigDecimal.ROUND_HALF_UP)
            atomicState["lastGallons_${z2}"] = gal
            // Accumulate monthly + all-time totals
            String monthKey2 = new Date().format("yyyy-MM", location.timeZone)
            atomicState["monthlyGallons_${monthKey2}"] =
                (((atomicState["monthlyGallons_${monthKey2}"] ?: 0G) as BigDecimal) + gal)
                    .setScale(1, BigDecimal.ROUND_HALF_UP)
            atomicState.totalGallonsAllTime =
                (((atomicState.totalGallonsAllTime ?: 0G) as BigDecimal) + gal)
                    .setScale(1, BigDecimal.ROUND_HALF_UP)
            logD "Zone ${z2} water usage: ${gal} gal (precip ${precipRateIn} in/hr, area ${areaFt2} ft², ${rtUsed}min)"
        } catch (Exception e) { logD "Water usage calc: ${e.message}" }
    }
    if (atomicState.abortCycle) { runIn(1,"abortCycleNow"); return }
    atomicState.zoneQueueIdx = (data.idx as int) + 1
    int zoneDelaySec = (settings.interZoneDelaySec?:"10") as int
    // runIn requires >= 1; for user-selected "None" (0s) use 1s minimum internally
    // but this is transparent — the valve opens essentially immediately
    runIn(Math.max(1, zoneDelaySec), "startNextZone")
}

// ── Cycle-and-soak handlers ──────────────────────────────────────────────────
// Called when a cycle burst finishes but the zone still has remaining runtime.
// Closes the valve, waits the soak period, then reopens for the next burst.
void soakAndContinue(Map data) {
    if (!atomicState.cycleRunning) { logD "soakAndContinue: not running"; return }
    if (atomicState.abortCycle)    { runIn(1,"abortCycleNow"); return }
    int z       = data.zone as int
    // Use soakMin from queue entry (set at cycle-start time) for consistency;
    // fall back to live setting only if not present (e.g. manually triggered)
    int soakMin = (data.soakMin ?: settings.cycleSoakMins ?: "30") as int
    closeValve(z, data.name as String)
    atomicState.activeZone = null
    logI "Zone ${z} (${data.name}): cycle ${data.cycleNum} done — soaking ${soakMin} min before next burst"
    runIn(soakMin * 60, "resumeAfterSoak", [data:data])
}

// Called after the soak period ends — reopens the valve for the next burst.
void resumeAfterSoak(Map data) {
    if (!atomicState.cycleRunning) { logD "resumeAfterSoak: not running"; return }
    if (atomicState.abortCycle)    { runIn(1,"abortCycleNow"); return }
    int z         = data.zone as int
    int remaining = data.remainingMin as int
    int maxCycle  = data.maxCycleMin as int
    int cycleNum  = (data.cycleNum as int) + 1

    if (!openValve(z, data.name as String)) {
        logW "Zone ${z}: reopen failed in cycle ${cycleNum} — skipping remaining ${remaining} min"
        atomicState.zoneQueueIdx = (data.idx as int) + 1
        int delayS = Math.max(1, (settings.interZoneDelaySec?:"10") as int)
        runIn(delayS, "startNextZone"); return
    }
    atomicState.activeZone = z

    int thisCycle = Math.min(maxCycle, remaining)
    int newRemain = remaining - thisCycle
    logI "Zone ${z} (${data.name}): cycle ${cycleNum} — ${thisCycle} min on, ${newRemain} min still remaining"

    if (newRemain > 0) {
        runIn(thisCycle * 60, "soakAndContinue",
              [data:[zone:z, idx:data.idx, name:data.name, fallback:(data.fallback ?: false), soakMin:data.soakMin,
                     remainingMin:newRemain, maxCycleMin:maxCycle, cycleNum:cycleNum]])
    } else {
        // Last burst — hand off to normal closeZoneAndAdvance
        runIn(thisCycle * 60, "closeZoneAndAdvance",
              [data:[zone:z, idx:data.idx, name:data.name, fallback:(data.fallback ?: false)]])
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MANUAL ZONE CONTROL
// ═══════════════════════════════════════════════════════════════════════════════

private void manualStartZone(int z) {
    if (atomicState.cycleRunning) { logW "Auto cycle running — manual blocked"; return }
    def name  = settings["zoneName_${z}"] ?: "Zone ${z}"
    int rtMin = (settings["zoneBaseRuntime_${z}"] ?: "45") as int
    // During spring startup inspection, use a short 60-second burst so you can walk
    // the yard and check each zone's heads quickly without a full runtime.
    boolean inspect = atomicState.startupInspectionActive as boolean
    // The stop timer is ONE job (runIn overwrites by handler name): starting a second
    // manual zone would orphan the first zone's timer and leave it open. Close it first.
    def prevMan = atomicState.manualRunZone
    if (prevMan != null && (prevMan as int) != z) manualStopZone(prevMan as int)
    if (openValve(z, name)) {
        atomicState.manualRunZone = z
        if (inspect) {
            runIn(60, "manualStopZoneHandler", [data:[zone:z]])
            logI "Inspection start: Zone ${z} (${name}) 60 sec burst"
        } else {
            // Use a unique method name per zone so unschedule() only cancels THIS zone
            runIn(rtMin * 60, "manualStopZoneHandler", [data:[zone:z]])
            logI "Manual start: Zone ${z} (${name}) ${rtMin} min"
        }
    }
}

void manualStopZoneHandler(Map data) { manualStopZone(data.zone as int) }

private void manualStopZone(int z) {
    def name = settings["zoneName_${z}"] ?: "Zone ${z}"
    closeValve(z, name)
    // Only cancel the timer if this zone is the currently tracked manual zone —
    // unschedule("manualStopZoneHandler") would cancel ALL zones' timers.
    // Since Hubitat runIn passes data through the handler, we just clear the
    // manualRunZone flag; the handler guard above checks data.zone so stale
    // timers for other zones are harmless (valve already closed).
    if (atomicState.manualRunZone == z) atomicState.remove("manualRunZone")
    logI "Manual stop: Zone ${z} (${name})"
}

// ═══════════════════════════════════════════════════════════════════════════════
// VALVE CONTROL — valve (open/close) and switch (on/off)
// ═══════════════════════════════════════════════════════════════════════════════

private boolean openValve(int z, String name="") {
    def dev = settings["zoneValve_${z}"]
    if (!dev) { logW "openValve(${z}): no device"; return false }
    try {
        if (dev.hasCommand("open"))    dev.open()
        else if (dev.hasCommand("on")) dev.on()
        else { logW "openValve(${z}): no open/on"; return false }
        atomicState["zoneOpenSince_${z}"] = now()   // for runaway detection
        // check_back: confirm the valve actually opened. Commands can be silently
        // dropped on Zigbee/Z-Wave/WiFi; a valve that never opened means a zone gets
        // no water while the app believes it's running. Verify after a short settle
        // delay (valves report state with lag). retryDone=false so the first failure
        // gets one retry; the verify handler escalates to an alert only if still wrong.
        if (settings.valveCheckBackEnabled != false) {
            atomicState.remove("valveOpenRetried_${z}")
            runIn(valveCheckDelaySec(), "verifyValveOpen", [data:[zone:z, name:name]])
        }
        logD "openValve(${z}): ${dev.displayName}"; return true
    } catch (e) { logW "openValve(${z}): ${e.message}"; return false }
}

private boolean closeValve(int z, String name="") {
    def dev = settings["zoneValve_${z}"]
    if (!dev) { logW "closeValve(${z}): no device"; return false }
    try {
        if (dev.hasCommand("close"))    dev.close()
        else if (dev.hasCommand("off")) dev.off()
        else { logW "closeValve(${z}): no close/off"; return false }
        atomicState.remove("zoneOpenSince_${z}")
        // Post-close verification: if valve reports state, check after 90s
        runIn(90, "verifyValveClosed", [data:[zone:z]])
        logD "closeValve(${z}): ${dev.displayName}"; return true
    } catch (e) { logW "closeValve(${z}): ${e.message}"; return false }
}

private void closeAllValves() {
    int zc = safeZoneCount(); (1..zc).each { closeValve(it) }; logI "closeAllValves: ${zc} zones"
}

// ═══════════════════════════════════════════════════════════════════════════════
// GUARD CHECKS
// ═══════════════════════════════════════════════════════════════════════════════

// manual=true means the user pressed Run Cycle Now. Schedule-based gates (season and the
// NOAA rain FORECAST) are dropped for a manual run because the user is deliberately
// overriding the schedule and can see the sky. Physical gates stay: rain actually falling,
// freeze, frost, soil temperature, and approaching storm all protect the plants and the
// system regardless of what time the button was pressed.
private Map checkAllGuards(boolean manual = false) {
    if (!manual && !isInSeason()) {
        return [passed:false, reason:"Outside season (${settings.seasonStartMonth?:'5'}/${settings.seasonStartDay?:'1'} – ${settings.seasonEndMonth?:'10'}/${settings.seasonEndDay?:'15'})"]
    }
    def wx = settings.weatherStation
    boolean canFallback = !isLocalOnlyMode() && settings.noaaFallbackEnabled
    if (!wx && !canFallback) return [passed:false, reason:"No station and NOAA fallback unavailable (local-only or disabled)"]

    // Temperature — local station first, converted to °F respecting hub temperature scale
    boolean stationFresh = isStationFresh()
    def tempNow = (wx && stationFresh) ? stationTempToF(safeGetAttrBD(wx, resolvedAttr("attrTemp","temperature"))) : null
    if (tempNow == null && canFallback) {
        logI "Guards: ${stationFresh ? 'local temp null' : 'station stale'} — NOAA fallback"
        tempNow = fetchNoaaCurrentTemp()
    }
    def tempMin = (settings.guardTempMin ?: "38") as BigDecimal
    if (tempNow == null) return [passed:false, reason:"Temperature unavailable"]
    if (tempNow <= tempMin) return [passed:false, reason:"Freeze: ${tempNow}°F ≤ ${tempMin}°F"]

    if (wx) {
        def rainRate = safeGetAttrBD(wx, resolvedAttr("attrRainRate","rainRate"))
        def maxRate  = (settings.guardRainRate ?: "0.09") as BigDecimal
        if (rainRate != null && rainRate >= maxRate) return [passed:false, reason:"Raining: ${rainRate} in/hr ≥ ${maxRate}"]

        def rainDay  = safeGetAttrBD(wx, resolvedAttr("attrRainDay","rainDaily"))
        // Dedicated rain gauge override — a separate tipping-bucket gauge (e.g. Ecowitt WH40)
        // is more reliable than the all-in-one station's rain reading. Prefer it when configured.
        if (settings.rainGaugeDevice) {
            def gaugeDay = safeGetAttrBD(settings.rainGaugeDevice, settings.rainGaugeAttr ?: "rainDaily")
            if (gaugeDay != null) {
                rainDay = gaugeDay
                logD "Rain guard using dedicated gauge '${settings.rainGaugeDevice.displayName}': ${rainDay}\""
            }
        }
        def maxDay   = (settings.guardRainDaily ?: "0.25") as BigDecimal
        if (rainDay != null && rainDay >= maxDay) return [passed:false, reason:"Rain today: ${rainDay}\" ≥ ${maxDay}\""]

        def rainEvt  = safeGetAttrBD(wx, resolvedAttr("attrRainEvt","rainEvent"))
        def maxEvt   = (settings.guardRainEvent ?: "0.50") as BigDecimal
        if (rainEvt != null && rainEvt >= maxEvt) return [passed:false, reason:"Rain event: ${rainEvt}\" ≥ ${maxEvt}\""]

        // ── Local frost guard: dew point spread AND near-freezing temperature ──
        // Frost requires BOTH a small temp−dewpoint spread (moisture condensing) AND
        // an actually cold air temperature. A small spread alone just means fog/dew —
        // on a warm humid night (e.g. 68°F, 1°F spread) there is no frost risk, so
        // spread alone must NOT trigger a skip. Require temp ≤ the frost ceiling too.
        if (settings.dpSpreadGuardEnabled) {
            def tempAttrVal = safeGetAttrBD(wx, resolvedAttr("attrTemp","temperature"))
            def dpAttrVal   = safeGetAttrBD(wx, resolvedAttr("attrDewPoint","dewPoint"))
            if (tempAttrVal != null && dpAttrVal != null) {
                BigDecimal tempF     = stationTempToF(tempAttrVal)
                BigDecimal spreadF   = (tempF - stationTempToF(dpAttrVal)).setScale(1, BigDecimal.ROUND_HALF_UP)
                BigDecimal minSpread = (settings.dpSpreadMinF ?: "4") as BigDecimal
                BigDecimal maxTemp   = (settings.dpSpreadMaxTempF ?: "38") as BigDecimal
                if (spreadF <= minSpread && tempF <= maxTemp) {
                    return [passed:false, reason:"Frost risk: ${tempF}°F with only ${spreadF}°F temp−dewpoint spread (≤ ${minSpread}°F spread and ≤ ${maxTemp}°F)"]
                }
            }
        }

        // ── Local storm guard: barometric pressure trend ──────────────────────
        if (settings.pressureTrendGuardEnabled && settings.pressureSensor &&
            settings.pressureAttr && settings.pressureAttr != "(not available)") {
            BigDecimal trend = calcPressureTrend()
            if (trend != null) {
                BigDecimal maxDrop = -((settings.pressureTrendThreshIn ?: "0.10") as BigDecimal)
                if (trend <= maxDrop) {
                    return [passed:false, reason:"Storm approaching: pressure dropped ${String.format('%.3f', trend as double)}\" in 3 hrs (threshold ${-maxDrop}\")"]
                }
            }
        }
    }

    // ── NOAA predictive guard: skip only when rain is BOTH likely and meaningful ──
    // This is NOAA's unique value: the local station can't predict future rainfall.
    if (!manual && !isLocalOnlyMode() && settings.noaaFallbackEnabled && settings.noaaPopGuardEnabled) {
        // One-shot bypass set by the forecast-verification re-run: that re-run exists
        // precisely because this forecast already busted, so don't re-skip on it.
        if (atomicState.verifyBypassPredictive) {
            atomicState.verifyBypassPredictive = false
            logI "NOAA guard: bypassed once for forecast-verification re-run"
        } else {
            def cacheDate = atomicState.noaaCacheDate
            // Validity-window logic now lives in isNoaaCacheUsable() so the diagnostics
            // display cannot contradict the guard's actual behavior.
            boolean cacheValid = isNoaaCacheUsable()
            if (cacheValid) {
                def pop = atomicState.noaaCachePop
                def qpf = atomicState.noaaCacheQpf
                int popMax = (settings.noaaPopGuardThreshold ?: "50") as int
                BigDecimal qpfMin = (settings.noaaQpfMinForSkip ?: "0.15") as BigDecimal
                boolean popHit = (pop != null && (pop as int) >= popMax)
                // Amount gate: a high-probability forecast of a trivial amount is the
                // classic thunderstorm miss (2026-07-17: 82% and 0.14 in forecast, 0.08 in
                // fell). Probability alone no longer skips; the promised AMOUNT must be
                // worth a watering. Unknown QPF falls back to probability alone, with the
                // verification recheck as the backstop either way.
                boolean qpfHit = (qpf == null) || ((qpf as BigDecimal) >= qpfMin)
                if (popHit && qpfHit) {
                    String qpfTxt = (qpf != null) ? "${qpf} in expected" : "amount unknown"
                    return [passed:false, reason:"NOAA predictive: ${pop}% chance of rain, ${qpfTxt} tonight (thresholds ${popMax}% and ${qpfMin} in)"]
                }
                // Standalone large-amount trigger (legacy, opt-in): very large expected
                // totals skip regardless of the probability threshold. Threshold is
                // decimal inches compared as BigDecimal. (Earlier code cast it to int —
                // "0.25" as int = 0 — which silently disabled this check.)
                BigDecimal qpfMax = (settings.noaaQpfGuardThreshold ?: "0") as BigDecimal
                if (qpfMax > 0G && qpf != null && (qpf as BigDecimal) >= qpfMax) {
                    return [passed:false, reason:"NOAA predictive: ${qpf} in rain expected tonight (threshold ${qpfMax} in)"]
                }
            } else if (cacheDate) {
                logD "NOAA guard: cache ${cacheDate} outside validity window, predictive guard inactive"
            }
        }
    }

    return [passed:true, reason:"All guards passed"]
}

// ── Forecast-skip verification ─────────────────────────────────────────────────
// A predictive skip acts on rain that has not happened. This pair closes the loop:
// when the main cycle skips on the NOAA guard, a recheck is scheduled before the
// failsafe; if the measured rain since midnight is below the same amount that
// justified skipping, the forecast busted and the cycle runs in the remaining
// window (with the predictive guard bypassed once so the same stale forecast
// cannot re-skip it). Everything else re-evaluates live: current rain, wind, TOU,
// and fresh soil readings, so partial rain still trims or skips zones naturally.
private void scheduleForecastVerify(String reason) {
    try {
        if (!settings.failsafeTime) {
            logW "Forecast verify: no failsafe time configured, cannot schedule recheck"
            return
        }
        int leadMin = Math.max(30, Math.min(240, ((settings.forecastVerifyLeadMin ?: 120) as int)))
        def fs = Calendar.getInstance(location.timeZone)
        fs.setTime(toDateTime(settings.failsafeTime))
        def nowCal = Calendar.getInstance(location.timeZone)
        fs.set(Calendar.YEAR,  nowCal.get(Calendar.YEAR))
        fs.set(Calendar.MONTH, nowCal.get(Calendar.MONTH))
        fs.set(Calendar.DAY_OF_MONTH, nowCal.get(Calendar.DAY_OF_MONTH))
        long checkMs = fs.getTimeInMillis() - (leadMin * 60000L)
        if (checkMs <= now() + 60000L) {
            logW "Forecast verify: recheck time already past, skipping verification today"
            return
        }
        atomicState.forecastSkipPending = [date: new Date().format("yyyy-MM-dd", location.timeZone),
                                           reason: reason, qpf: atomicState.noaaCacheQpf, skippedAt: ts()]
        runOnce(new Date(checkMs), "forecastSkipVerify")
        logI "Forecast-skip verification scheduled for ${new Date(checkMs).format('h:mm a', location.timeZone)} (${leadMin} min before failsafe)"
    } catch (Exception e) { logW "scheduleForecastVerify: ${e.message}" }
}

void forecastSkipVerify() {
    def pend = atomicState.forecastSkipPending
    String vToday = new Date().format("yyyy-MM-dd", location.timeZone)
    if (pend == null || pend.date != vToday) { atomicState.remove("forecastSkipPending"); return }
    atomicState.remove("forecastSkipPending")
    if (settings.systemPaused || settings.winterizedMode) return
    if (atomicState.cycleRunning) {
        // A seed or manual cycle can be running at the recheck moment. Dropping the
        // verification here would silently lose a busted-forecast watering day, so
        // re-arm the pending state and retry in 15 minutes, unless failsafe is now
        // too close for any useful late run anyway.
        try {
            def fsC = Calendar.getInstance(location.timeZone)
            fsC.setTime(toDateTime(settings.failsafeTime))
            def ncC = Calendar.getInstance(location.timeZone)
            fsC.set(Calendar.YEAR, ncC.get(Calendar.YEAR))
            fsC.set(Calendar.MONTH, ncC.get(Calendar.MONTH))
            fsC.set(Calendar.DAY_OF_MONTH, ncC.get(Calendar.DAY_OF_MONTH))
            if (now() + 1200000L < fsC.getTimeInMillis()) {
                atomicState.forecastSkipPending = pend
                runIn(900, "forecastSkipVerify")
                logI "Forecast verify: another cycle is running, retrying in 15 min"
                return
            }
        } catch (Exception e) { logD "Forecast verify retry calc: ${e.message}" }
        logW "Forecast verify: cycle running and failsafe too close, verification abandoned for today"
        return
    }
    BigDecimal fell   = bucketRainToday()   // station rainDaily since midnight, gauge-preferred
    BigDecimal needed = (settings.noaaQpfMinForSkip ?: "0.15") as BigDecimal
    if (fell >= needed) {
        logI "Forecast verified: ${fell} in fell since midnight (needed ${needed} in), skip stands"
        notifyMainSkip("🌧️ Forecast verified: ${fell} in of rain arrived, the skipped cycle stays skipped")
        return
    }
    logW "Forecast BUSTED: only ${fell} in fell since midnight (needed ${needed} in), running the skipped cycle now"
    sendNotification("notifyOnSkip", "🌦️ Forecast busted: only ${fell} in of rain arrived after the overnight skip. Running the cycle now; watering ends at failsafe.")
    atomicState.verifyBypassPredictive = true
    irrigationCycle()
    // One-shot: if the run exited before consuming it (wind, pause, freeze...), it must
    // not bypass the NEXT night's forecast skip.
    atomicState.verifyBypassPredictive = false
}

private boolean isInSeason() {
    def cal   = Calendar.getInstance(location.timeZone)
    int today = (cal.get(Calendar.MONTH)+1)*100 + cal.get(Calendar.DAY_OF_MONTH)
    int start = ((settings.seasonStartMonth?:"5") as int)*100 + ((settings.seasonStartDay?:"1") as int)
    int end   = ((settings.seasonEndMonth?:"10") as int)*100  + ((settings.seasonEndDay?:"15") as int)
    // Handle seasons that cross year-end (e.g. Nov–Mar):
    // when end < start the valid range wraps December→January
    if (end < start) return today >= start || today <= end
    return today >= start && today <= end
}

private boolean isTodayActiveDay() {
    def days = settings.activeDays; if (!days || days.isEmpty()) return true
    def day  = Calendar.getInstance(location.timeZone).getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH)
    return days.contains(day)
}

// ═══════════════════════════════════════════════════════════════════════════════
// ADAPTIVE RUNTIME
// ═══════════════════════════════════════════════════════════════════════════════

private int calcZoneRuntime(int z, BigDecimal eto, BigDecimal baseline) {
    int base = (settings["zoneBaseRuntime_${z}"] ?: "45") as int
    if (settings.etUseAdaptive == false) return base
    if (baseline <= 0G) return base
    BigDecimal kc     = zoneKc(z)
    BigDecimal etcIn  = (eto * kc).setScale(4, BigDecimal.ROUND_HALF_UP)
    BigDecimal scale  = (etcIn / baseline).setScale(3, BigDecimal.ROUND_HALF_UP)
    // Soil temperature ET correction — cold soil reduces actual ET below what air
    // temperature alone suggests (roots slow water uptake). Factor is 1.0 above 55°F,
    // scaling down to 0.5 at 35°F. Returns 1.0 when disabled or soil temp unavailable.
    BigDecimal stFactor = soilTempEtFactor(z)
    BigDecimal scaledBD  = (base as BigDecimal) * scale * stFactor
    // Dry-down duration correction (water-balance branch, opt-in per zone). The ET model
    // above is generic; the soil sensor knows this zone's ground truth. If the zone is
    // measured to be drying faster than its OWN established baseline, the ET schedule is
    // under-calling it, so nudge runtime up; if slower, nudge down. Self-referential
    // (recent rate vs this zone's baseline, same %/day units) so it needs no soil-%→inch
    // calibration. Bounded to ±15% and requires a settled baseline so it can't run away
    // or act on noise. A gentle multiplier on the ET runtime — never a replacement.
    BigDecimal ddNote = null
    if (settings["zoneDryDownCorrect_${z}"]) {
        def recent = atomicState["ddRatePerDay_${z}"]
        def base2  = atomicState["ddRateBaseline_${z}"]
        // Require a CURRENT sensor (same staleness rule as every other moisture feature)
        // AND a settled baseline before correcting. A stale/dead sensor means the learned
        // rate is outdated, so we fall back to pure ET rather than nudging off stale data.
        boolean sensorOk = !isSensorStale(z)
        if (sensorOk && recent != null && base2 != null && (base2 as BigDecimal) >= 1.0G) {
            BigDecimal ratio = ((recent as BigDecimal) / (base2 as BigDecimal))
            // Clamp the correction multiplier to [0.85, 1.15] = ±15%
            double cf = Math.max(0.85d, Math.min(1.15d, ratio.toDouble()))
            BigDecimal corr = new BigDecimal(cf).setScale(3, BigDecimal.ROUND_HALF_UP)
            scaledBD = scaledBD * corr
            ddNote = corr
        }
    }
    // Distribution uniformity correction: lower DU means dry spots need more water,
    // so total runtime increases to ensure the least-covered area gets enough.
    // Standard practice divides scheduled runtime by DU (as a fraction). Clamped to
    // a sensible 50–100% range to avoid extreme multipliers from a mis-entered value.
    def duPctRaw = settings["zoneDuPct_${z}"]
    BigDecimal duNote = null
    if (duPctRaw != null) {
        BigDecimal du = (duPctRaw as BigDecimal) / 100G
        du = new BigDecimal(Math.max(0.5d, Math.min(1.0d, du.toDouble())))
        if (du > 0G) { scaledBD = scaledBD / du; duNote = du }
    }
    int scaled        = scaledBD.setScale(0, BigDecimal.ROUND_HALF_UP) as int
    int minMin        = (settings.runtimeMin ?: "20") as int
    int maxMin        = (settings.runtimeMax ?: "65") as int
    int result        = Math.max(RUNTIME_FLOOR, Math.max(minMin, Math.min(maxMin, Math.min(RUNTIME_CEIL, scaled))))
    String stNote = (stFactor < 1G) ? " soilTempFactor=${stFactor}" : ""
    String duStr  = (duNote != null) ? " DU=${duNote}" : ""
    String ddStr  = (ddNote != null) ? " dryDownCorr=${ddNote}" : ""
    logD "Zone ${z}: base=${base} ETo=${eto} Kc=${kc} ETc=${etcIn} scale=${scale}${stNote}${ddStr}${duStr} → ${result}min"
    return result
}

// ── Midday moisture top-up math ────────────────────────────────────────────────
// Computes the minutes needed to refill ONE FULL MAD band for a zone — i.e. to bring
// the soil from the "time to water" threshold back up to field capacity. This is the
// physically-correct dynamic CAP on a midday top-up: you can't usefully apply more
// than this in a single watering (beyond field capacity, water drains past the roots).
// Refill depth (in) = root × AWC × MAD; minutes = (depth / nozzle precip in/hr) × 60.
private int madBandRefillMinutes(int z) {
    BigDecimal root   = zoneRoot(z)
    BigDecimal awc    = SOIL_AWC[settings["zoneSoil_${z}"] ?: "Loam"] ?: 0.17G
    BigDecimal mad    = zoneMad(z)
    BigDecimal precip = zonePrecip(z)
    if (precip <= 0G) return 0
    BigDecimal refillIn = (root * awc * mad).setScale(3, BigDecimal.ROUND_HALF_UP)
    int mins = ((refillIn / precip) * 60G).setScale(0, BigDecimal.ROUND_HALF_UP) as int
    return Math.max(0, mins)
}

// The dynamic top-up runtime for a midday check on a dry zone. Scales the work by HOW
// FAR the measured soil reading has fallen below threshold, as a fraction of the MAD
// band, then converts that partial deficit to minutes. Capped at the full MAD-band
// refill (the physical ceiling) so a misreading sensor can never trigger more than one
// proper irrigation's worth of water. All inputs are existing per-zone values.
private int middayTopUpMinutes(int z, Integer soilPct, Integer thresh) {
    if (soilPct == null || thresh == null) return 0
    if (soilPct >= thresh) return 0
    int capMin = madBandRefillMinutes(z)
    if (capMin <= 0) return 0
    // How deep into the dry zone are we? The MAD band, expressed in soil-% points, is
    // approximated by the configured base threshold (the % at which the band is empty).
    // Fraction missing = (thresh - soilPct) / thresh, bounded to (0,1].
    BigDecimal frac = ((thresh - soilPct) as BigDecimal) / (thresh as BigDecimal)
    frac = new BigDecimal(Math.max(0.0d, Math.min(1.0d, frac.toDouble())))
    int mins = (capMin * frac).setScale(0, BigDecimal.ROUND_HALF_UP) as int
    // Floor at a sensible minimum so a tiny top-up isn't a pointless 0-1 min blip,
    // but never exceed the MAD-band cap.
    int floored = Math.max(mins, (capMin >= 6) ? 5 : capMin)
    return Math.min(floored, capMin)
}


// ═══════════════════════════════════════════════════════════════════════════════
// WATER-BALANCE DRY-DOWN TRACKING (branch feature)
// ═══════════════════════════════════════════════════════════════════════════════
// Learns each zone's natural drying slope (%/day) from the soil sensor stream, using
// ONLY the falling segments (watering/rain produce sharp UP jumps that are not drying
// and must be excluded). The smoothed rate drives two opt-in behaviors: predict-ahead
// watering (water just before a zone will cross threshold) and anomaly detection (flag
// a dry-down far faster than the zone's own baseline — likely a leak/broken emitter).
// State per zone: ddLastVal_, ddLastTime_, ddRatePerDay_ (EMA), ddRateBaseline_ (slow).

@Field static final BigDecimal DD_EMA_ALPHA      = 0.30G
@Field static final BigDecimal DD_BASELINE_ALPHA = 0.05G
@Field static final long       DD_MIN_INTERVAL_MS = 1800000L   // 30 min minimum sample spacing
@Field static final long       DD_MAX_INTERVAL_MS = 86400000L  // 24 hr: window expiry, learn whatever accumulated
@Field static final BigDecimal DD_RISE_RESET     = 3G          // >+3pts vs last = watering/rain
@Field static final BigDecimal DD_MIN_DROP_PTS   = 2G          // deadband: sensors flicker +/-1pt, require a real move
@Field static final BigDecimal DD_RATE_CAP       = 30G         // %/day sanity ceiling on any single learned sample
@Field static final BigDecimal DD_DRAINAGE_SUSPECT = 12G       // %/day: a post-settle sample this fast is probably still drainage
@Field static final int        DD_SETTLE_MAX_EXT  = 4          // max quarter-settle extensions (clay loam: up to +24h, 48h total)
// Post-watering settle (drainage-to-field-capacity) time by soil texture, in hours.
// Sandy soils shed gravitational water within hours; clays take a day or more. The
// dry-down learner suspends learning for this long after a rise-reset so it measures
// the ET-driven slope, not the drainage cliff. Per-zone override available in the UI.
@Field static final Map SOIL_SETTLE_HRS = [
    "Sand":8, "Loamy Sand":10, "Sandy Loam":12,
    "Loam":18, "Clay Loam":24, "Silty Clay":30, "Clay":36
]
@Field static final int DD_SETTLE_DEFAULT_HRS = 24  // fallback when soil type unknown

// Update the dry-down rate for a zone from a new reading. Only falling segments feed
// the rate; a rise (watering/rain) re-seeds the anchor without polluting the slope.
private void updateDryDownRate(int z, BigDecimal val) {
    try {
        def lastV = atomicState["ddLastVal_${z}"]
        def lastT = atomicState["ddLastTime_${z}"]
        long nowMs = now()
        if (lastV == null || lastT == null) {
            atomicState["ddLastVal_${z}"] = val; atomicState["ddLastTime_${z}"] = nowMs; return
        }
        BigDecimal prev = lastV as BigDecimal
        long dtMs = nowMs - (lastT as long)
        if (val > prev + DD_RISE_RESET) {   // watering/rain: reset anchor, no rate
            // Start the post-watering settle blackout. The first ~day after irrigation
            // (or rain) the sensor falls steeply as gravitational water drains back to
            // field capacity. That cliff is drainage, not plant use; learning it taught
            // phantom rates of 17-24%/day on zones whose true ET slope is 2-6%/day and
            // made predict-ahead fire nightly (field data 2026-07-09). No learning until
            // the soil has settled.
            atomicState["ddLastVal_${z}"] = val; atomicState["ddLastTime_${z}"] = nowMs
            atomicState["ddSettleUntil_${z}"] = nowMs + zoneSettleMs(z)
            atomicState.remove("ddSettleExt_${z}")
            return
        }
        // During the settle window: keep the anchor fresh (every DD_MIN_INTERVAL so we
        // don't hammer atomicState on 2-minute sensor events) but learn nothing. When
        // settle expires, the next window starts from a post-drainage anchor and measures
        // the true ET-driven slope only.
        def settleUntil = atomicState["ddSettleUntil_${z}"]
        if (settleUntil != null && nowMs < (settleUntil as long)) {
            if (dtMs >= DD_MIN_INTERVAL_MS) {
                atomicState["ddLastVal_${z}"] = val; atomicState["ddLastTime_${z}"] = nowMs
            }
            return
        }
        if (dtMs < DD_MIN_INTERVAL_MS) return   // too soon to be meaningful
        BigDecimal dropPts = prev - val
        if (dropPts < 0G) dropPts = 0G
        // Quantization deadband (learner v2). These sensors flicker +/-1 point between
        // adjacent readings. The v1 learner re-anchored on every 30-minute sample, so a
        // 1-point flicker read as 1pt/30min (about 48%/day) and, with rises floored at
        // zero, the EMA converged to a large phantom rate (observed 22%/day on a zone
        // whose charts show about 6%/day). Rule now: a sample must show a REAL move
        // (>= DD_MIN_DROP_PTS) before we learn and re-anchor. Anything smaller returns
        // WITHOUT re-anchoring, so the measurement window keeps extending until genuine
        // drying accumulates. If the window reaches DD_MAX_INTERVAL_MS first (flat wet
        // spell, or a sensor outage), we learn whatever accumulated over that long span,
        // where a 1-point uncertainty is small (1pt/24h = 1%/day), and re-anchor. A
        // watering hidden inside a long gap is caught by the rise-reset above.
        boolean windowExpired = (dtMs >= DD_MAX_INTERVAL_MS)
        if (dropPts < DD_MIN_DROP_PTS && !windowExpired) return
        BigDecimal days = new BigDecimal(dtMs) / 86400000G
        if (days <= 0G) return
        BigDecimal ratePerDay = (dropPts / days).setScale(3, BigDecimal.ROUND_HALF_UP)
        // Sanity ceiling: post-watering drainage or an odd segment can still produce an
        // extreme instantaneous slope; cap the sample so one artifact cannot swamp the EMA.
        if (ratePerDay > DD_RATE_CAP) ratePerDay = DD_RATE_CAP
        // Adaptive settle extension. A fixed blackout assumes drainage finishes inside it,
        // but a zone can still be draining when it expires. Field data 2026-07-23: Front
        // Yard spikes to 82% and needs well over a day to reach its floor near 46%, so the
        // 24h clay-loam settle expired mid-cliff and the learner sampled 24.6%/day, which
        // then drove predict-ahead every night. If the first post-settle sample is still
        // drainage-speed, extend the blackout by a quarter-settle and discard the sample
        // rather than learning it. Bounded by DD_SETTLE_MAX_EXT so a genuinely fast-drying
        // zone still learns eventually instead of blacking out forever.
        if (ratePerDay >= DD_DRAINAGE_SUSPECT) {
            int ext = (atomicState["ddSettleExt_${z}"] ?: 0) as int
            if (ext < DD_SETTLE_MAX_EXT) {
                atomicState["ddSettleExt_${z}"]   = ext + 1
                atomicState["ddSettleUntil_${z}"] = nowMs + (zoneSettleMs(z) / 4L)
                atomicState["ddLastVal_${z}"]     = val
                atomicState["ddLastTime_${z}"]    = nowMs
                logD "Zone ${z}: post-settle sample ${ratePerDay}%/day still drainage-speed, extending settle (${ext + 1}/${DD_SETTLE_MAX_EXT})"
                return
            }
            logD "Zone ${z}: settle extension limit reached, accepting ${ratePerDay}%/day"
        }
        atomicState.remove("ddSettleExt_${z}")
        def curRate = atomicState["ddRatePerDay_${z}"]
        BigDecimal newRate = (curRate == null) ? ratePerDay :
            (DD_EMA_ALPHA * ratePerDay + (1G - DD_EMA_ALPHA) * (curRate as BigDecimal)).setScale(3, BigDecimal.ROUND_HALF_UP)
        atomicState["ddRatePerDay_${z}"] = newRate
        def curBase = atomicState["ddRateBaseline_${z}"]
        BigDecimal newBase = (curBase == null) ? newRate :
            (DD_BASELINE_ALPHA * ratePerDay + (1G - DD_BASELINE_ALPHA) * (curBase as BigDecimal)).setScale(3, BigDecimal.ROUND_HALF_UP)
        atomicState["ddRateBaseline_${z}"] = newBase
        atomicState["ddLastVal_${z}"] = val; atomicState["ddLastTime_${z}"] = nowMs
        logT "Zone ${z} dry-down: ${ratePerDay}%/day (EMA ${newRate}, baseline ${newBase})"
    } catch (Exception e) { logD "updateDryDownRate zone ${z}: ${e.message}" }
}

// Hours until a zone is predicted to cross its (dynamic) threshold. null if not
// predictable (no rate / no reading / already below / rate ~0). Positive = hours left.
private BigDecimal hoursUntilThreshold(int z, Integer soilPct, Integer thresh) {
    if (soilPct == null || thresh == null) return null
    def rate = atomicState["ddRatePerDay_${z}"]
    if (rate == null) return null
    BigDecimal r = rate as BigDecimal
    if (r <= 0.05G) return null
    if (soilPct <= thresh) return 0G
    BigDecimal pointsToGo = (soilPct - thresh) as BigDecimal
    return ((pointsToGo / r) * 24G).setScale(1, BigDecimal.ROUND_HALF_UP)
}

// Rain-credit minutes for a zone running in SENSOR-FALLBACK mode (stale/failed/no
// sensor). When a zone can read its soil, rain is already credited physically through
// the higher moisture reading — crediting again would double-count, so this is used
// ONLY on the fallback path. Converts today's measured rainfall to equivalent runtime
// minutes via the zone's nozzle precip rate: minutes = rain_in / precip_in_per_hr × 60.
// Clean unit match (both inches) — no soil-%→inch calibration needed. Caller decides
// whether the zone is actually in fallback; this just computes the credit.
private int rainCreditMinutes(int z) {
    if (!settings.rainCreditEnabled) return 0
    def wx = settings.weatherStation
    if (!wx) return 0
    // Prefer a dedicated rain gauge if configured (same precedence as the rain guard)
    BigDecimal rainDay = safeGetAttrBD(wx, resolvedAttr("attrRainDay","rainDaily"))
    if (settings.rainGaugeDevice) {
        def gaugeDay = safeGetAttrBD(settings.rainGaugeDevice, settings.rainGaugeAttr ?: "rainDaily")
        if (gaugeDay != null) rainDay = gaugeDay
    }
    if (rainDay == null || rainDay <= 0G) return 0
    BigDecimal precip = zonePrecip(z)
    if (precip <= 0G) return 0
    int mins = ((rainDay / precip) * 60G).setScale(0, BigDecimal.ROUND_HALF_UP) as int
    return Math.max(0, mins)
}

// ── Cumulative water-balance "bucket" (sensorless / sensor-fallback zones) ──────
// A running per-zone moisture ledger in inches, updated once per day with
// (rain − ETc). Negative = accumulated deficit (needs water); it is the fully-general
// form of rain-credit: instead of only crediting today's rain, it tracks net deficit
// across multiple days, so a zone that can't read its own soil still schedules
// sensibly. Used ONLY on the sensor-fallback path (!sr.fresh) — a healthy sensor is a
// better integrator (it measures runoff/drainage the bucket can only model), so the
// bucket stays dormant while the sensor is live and can't double-count.
//
// The deficit is capped at one MAD band (root × AWC × MAD) — the same physical
// "one refill" ceiling used elsewhere — so a bad ET/rain streak or a long outage
// can't accumulate a runaway number that dumps a huge runtime on sensor recovery.
// Surplus is capped at 0: soil can't hold negative deficit; extra rain runs off or
// drains, exactly what a sensor would show.

// Physical size of one MAD band for a zone, in inches (the deficit cap magnitude).
private BigDecimal zoneMadBandIn(int z) {
    String soil = settings["zoneSoil_${z}"] ?: "Loam"
    BigDecimal awc = (SOIL_AWC[soil] ?: 0.17G)
    return (zoneRoot(z) * awc * zoneMad(z)).setScale(3, BigDecimal.ROUND_HALF_UP)
}

// Today's measured rainfall (inches), rain-gauge preferred, same precedence as guards.
private BigDecimal bucketRainToday() {
    def wx = settings.weatherStation
    BigDecimal rainDay = wx ? safeGetAttrBD(wx, resolvedAttr("attrRainDay","rainDaily")) : null
    if (settings.rainGaugeDevice) {
        def g = safeGetAttrBD(settings.rainGaugeDevice, settings.rainGaugeAttr ?: "rainDaily")
        if (g != null) rainDay = g
    }
    return (rainDay == null || rainDay < 0G) ? 0G : rainDay
}

// Daily ledger update for every fallback-eligible zone: bucket += (rain − ETc).
// Called once per day from the main cycle after ET is computed. Only maintains the
// ledger for zones without a live sensor reading; a sensored-healthy zone's bucket
// is left untouched (and effectively ignored) so recovery doesn't act on stale math.
private void updateBucketsDaily(BigDecimal eto) {
    if (!settings.bucketEnabled) return
    if (eto == null || eto < 0G) return
    int zc = safeZoneCount()
    (1..zc).each { z ->
        if (settings["zoneEnabled_${z}"] == false || !settings["zoneValve_${z}"]) return
        BigDecimal etc  = (eto * zoneKc(z)).setScale(4, BigDecimal.ROUND_HALF_UP)
        BigDecimal rain = bucketRainToday()
        BigDecimal cur  = (atomicState["bucketIn_${z}"] ?: 0G) as BigDecimal
        BigDecimal next = cur + (rain - etc)
        // Cap surplus at 0 and deficit at one MAD band (stored as a negative number).
        BigDecimal cap = zoneMadBandIn(z)
        if (next > 0G)      next = 0G
        else if (next < -cap) next = -cap
        atomicState["bucketIn_${z}"] = next.setScale(3, BigDecimal.ROUND_HALF_UP)
        logD "Bucket zone ${z}: ${cur} + (rain ${rain} − ETc ${etc}) → ${atomicState["bucketIn_${z}"]} in (cap ${cap})"
    }
}

// Convert a fallback zone's accumulated deficit to runtime minutes. Positive minutes
// mean "the ledger says this zone needs this much water." Returns 0 if no deficit.
private int bucketDeficitMinutes(int z) {
    if (!settings.bucketEnabled) return 0
    BigDecimal bucket = (atomicState["bucketIn_${z}"] ?: 0G) as BigDecimal
    if (bucket >= 0G) return 0                 // no deficit (surplus or dry-even)
    BigDecimal deficit = bucket.abs()
    BigDecimal precip = zonePrecip(z)
    if (precip <= 0G) return 0
    int mins = ((deficit / precip) * 60G).setScale(0, BigDecimal.ROUND_HALF_UP) as int
    return Math.max(0, mins)
}

// Offset the ledger when a fallback zone actually waters: bucket += applied inches
// (a partial run only partially refills; leftover deficit carries forward). Capped
// at 0 (can't overfill the model past field capacity).
private void bucketApplyWater(int z, int minutesRun) {
    if (!settings.bucketEnabled) return
    if (minutesRun <= 0) return
    BigDecimal applied = (zonePrecip(z) * (minutesRun as BigDecimal) / 60G)
    BigDecimal cur = (atomicState["bucketIn_${z}"] ?: 0G) as BigDecimal
    BigDecimal next = cur + applied
    if (next > 0G) next = 0G
    atomicState["bucketIn_${z}"] = next.setScale(3, BigDecimal.ROUND_HALF_UP)
    logD "Bucket zone ${z}: applied ${minutesRun}min (${applied}in) → ${atomicState["bucketIn_${z}"]} in"
}

private BigDecimal zoneKc(int z)     { (settings["zoneKcOverride_${z}"] as BigDecimal)     ?: (PLANT_KC[settings["zonePlant_${z}"]   ?: "Cool Season Turf"] ?: 0.80G) }
private BigDecimal zoneRoot(int z)   { (settings["zoneRootOverride_${z}"] as BigDecimal)   ?: (PLANT_ROOT_IN[settings["zonePlant_${z}"] ?: "Cool Season Turf"] ?: 6.0G) }
private BigDecimal zoneMad(int z)    { (settings["zoneMadOverride_${z}"] as BigDecimal)    ?: (PLANT_MAD[settings["zonePlant_${z}"]   ?: "Cool Season Turf"] ?: 0.40G) }
private BigDecimal zonePrecip(int z) { (settings["zonePrecipOverride_${z}"] as BigDecimal) ?: (NOZZLE_PRECIP[settings["zoneNozzle_${z}"] ?: "Rotor"]         ?: 0.50G) }

// Effective post-watering settle time for a zone, in ms: per-zone override if set,
// else the soil-texture map, else the 24h default. Clamped 0..72h (0 disables the
// blackout for that zone).
private long zoneSettleMs(int z) {
    def ovr = settings["zoneSettleHrs_${z}"]
    Integer hrs = (ovr != null && "${ovr}" != "") ? (ovr as int) : null
    if (hrs == null) {
        String soil = settings["zoneSoil_${z}"] ?: "Loam"
        hrs = (SOIL_SETTLE_HRS[soil] ?: DD_SETTLE_DEFAULT_HRS) as int
    }
    hrs = Math.max(0, Math.min(72, hrs))
    return hrs * 3600000L
}

// ═══════════════════════════════════════════════════════════════════════════════
// ET CALCULATION
// ═══════════════════════════════════════════════════════════════════════════════

private BigDecimal calculateETo() {
    def wx  = settings.weatherStation
    def inp = [source:"Local station"]

    // Check if local station data is fresh enough to use
    boolean stationFresh = isStationFresh()

    if (wx && stationFresh) {
        // stationTempToF() converts from hub's configured scale to °F for ET equations.
        // Hubitat station drivers report in location.temperatureScale (F or C).
        inp.tempF     = stationTempToF(safeGetAttrBD(wx, resolvedAttr("attrTemp","temperature")))
        inp.humidity  = safeGetAttrBD(wx, resolvedAttr("attrHumidity","humidity"))
        inp.windMph   = safeGetAttrBD(wx, resolvedAttr("attrWind","windSpeed"))
        inp.dewPointF = stationTempToF(safeGetAttrBD(wx, resolvedAttr("attrDewPoint","dewPoint")))
        // Cache local temp so we can sanity-check NOAA if local station later goes stale
        if (inp.tempF != null) atomicState.lastStationTempF = inp.tempF
        def today     = new Date().format("yyyy-MM-dd", location.timeZone)
        def yesterday = new Date(now() - 86400000L).format("yyyy-MM-dd", location.timeZone)
        if (atomicState.solarNoonDate in [today, yesterday] && atomicState.solarNoonReading != null) {
            // Solar radiation sanity check: if enabled and the last solar-noon reading was
            // implausibly low vs the theoretical clear-sky maximum (sensor blocked/drifted),
            // skip the solar value so calculateETo falls back to Hargreaves rather than
            // trusting bad data and silently miscalculating ET.
            if (settings.solarSanityEnabled && atomicState.solarNoonSanityOk == false) {
                logW "ET: solar reading failed sanity check — using Hargreaves (temperature-only) instead"
                inp.source = (inp.source ?: "Local station") + " (solar suspect → Hargreaves)"
            } else {
                inp.solarWm2 = atomicState.solarNoonReading as BigDecimal
            }
        }
    } else if (wx && !stationFresh) {
        logW "ET: local station data stale (>${settings.stationStaleMins ?: 30} min since last event)"
        inp.source = "station stale"
    }
    if (inp.tempF == null && !isLocalOnlyMode() && settings.noaaFallbackEnabled) {
        logI "ET: local temp missing — NOAA fallback"
        def noaa = fetchNoaaWeatherForEt()
        if (noaa) {
            // Sanity check: if NOAA temperature differs wildly from last known local reading,
            // log a warning. Local station measures your specific microclimate; NOAA is a
            // regional grid forecast. Large differences suggest microclimate effects or
            // a NOAA data issue — flag but still use NOAA since local is unavailable.
            def lastKnownTemp = atomicState.lastStationTempF
            if (lastKnownTemp != null && noaa.tempF != null) {
                BigDecimal diff = ((noaa.tempF as BigDecimal) - (lastKnownTemp as BigDecimal)).abs()
                if (diff > 15G) {
                    logW "⚠️ NOAA/station disagreement: NOAA=${noaa.tempF}°F vs last local=${lastKnownTemp}°F (${diff}°F gap). " +
                         "Using NOAA — local station unavailable. Large gap may reflect microclimate or NOAA grid mismatch."
                    atomicState.noaaDisagreement = "${diff}°F gap at ${ts()}"
                }
            }
            inp.putAll(noaa); inp.source = "NOAA fallback"
        }
    }
    if (inp.tempF == null) { logW "calculateETo: temp unavailable"; return 0G }

    def lat      = location.latitude as BigDecimal
    def mon      = Calendar.getInstance(location.timeZone).get(Calendar.MONTH) + 1
    inp.baseline = seasonalBaseline(lat, mon)
    logI "ET [${inp.source}]: ${inp.tempF}°F RH=${inp.humidity}% solar=${inp.solarWm2} W/m² wind=${inp.windMph} mph"

    BigDecimal eto = (inp.solarWm2 != null && (inp.solarWm2 as BigDecimal) > 0G) ?
        calcPenmanMonteith(inp.tempF, inp.humidity, inp.solarWm2, inp.windMph, inp.dewPointF) :
        calcHargreaves(inp.tempF, inp.tMaxF as BigDecimal, inp.tMinF as BigDecimal)

    atomicState.lastEtInputs = [source:"${inp.source}", tempF:"${inp.tempF}", humidity:"${inp.humidity}",
        solarWm2:"${inp.solarWm2}", windMph:"${inp.windMph}", dewPointF:"${inp.dewPointF}",
        baseline:"${inp.baseline}", etoIn:"${String.format('%.4f', eto as double)}", timestamp:ts()]
    // Keep the main-page "ETo" display fresh from ANY caller (cycle, diagnostics view,
    // manual recalc, NOAA fetch). Only store positive values — a zero is treated as a
    // sensor-offline artifact and must not clobber the last good reading.
    if ((eto as BigDecimal) > 0G) atomicState.lastEto = eto
    return eto
}

// ── Penman-Monteith FAO-56 Eq. 6 ─────────────────────────────────────────────
private BigDecimal calcPenmanMonteith(BigDecimal tempF, BigDecimal humidity,
        BigDecimal solarWm2, BigDecimal windMph, BigDecimal dewPointF) {
    try {
        BigDecimal Tmean = fToC(tempF)
        BigDecimal elev  = ftToM(settings.locationElevationFt ?: 100.0)
        BigDecimal u2    = mphToMs(windMph ?: 0G)
        // solarWm2 is the INSTANTANEOUS solar-noon peak. Convert it to a daily-total
        // Rs (MJ/m²/day) before computing net radiation — using the raw peak as if it
        // were a daily average massively overstated Rn and therefore ETo (e.g. a cool
        // 45°F day reading 0.54 in/day). FAO-56 net shortwave Rns = 0.77 × Rs.
        BigDecimal latRad = degToRad(location.latitude as BigDecimal)
        int doy = Calendar.getInstance(location.timeZone).get(Calendar.DAY_OF_YEAR)
        BigDecimal Rs    = noonPeakToDailyRs(solarWm2, latRad, doy)
        BigDecimal Rn    = (Rs * 0.77G).setScale(4, BigDecimal.ROUND_HALF_UP)
        // FAO-56 Eq. 7: P = 101.3 * ((293 - 0.0065z) / 293)^5.26
        // Use Math.pow (double) — Groovy's ** with non-integer exponent on BigDecimal
        // is not safe in the Hubitat sandbox
        BigDecimal pressureBase = ((293G - 0.0065G * elev) / 293G)
        BigDecimal P     = (101.3G * Math.pow(pressureBase.doubleValue(), 5.26)).toBigDecimal().setScale(3, BigDecimal.ROUND_HALF_UP)
        BigDecimal gamma = (0.000665G * P).setScale(6, BigDecimal.ROUND_HALF_UP)
        BigDecimal es    = satVapPres(Tmean)
        BigDecimal ea    = actualVapPres(es, humidity, dewPointF)
        BigDecimal vpd   = (es - ea).max(0G).setScale(4, BigDecimal.ROUND_HALF_UP)
        // FAO-56 Eq. 13: Δ = 4098 * es / (Tmean + 237.3)²
        // Use explicit multiply for squaring — avoids ** on BigDecimal entirely
        BigDecimal tPlus = Tmean + 237.3G
        BigDecimal delta = (4098G * es / (tPlus * tPlus)).setScale(6, BigDecimal.ROUND_HALF_UP)
        BigDecimal num   = (0.408G * delta * Rn + gamma * (900G / (Tmean + 273G)) * u2 * vpd).setScale(6, BigDecimal.ROUND_HALF_UP)
        BigDecimal den   = (delta + gamma * (1G + 0.34G * u2)).setScale(6, BigDecimal.ROUND_HALF_UP)
        if (den <= 0G) { logW "PM: den≤0"; return 0G }
        BigDecimal et0in = mmToIn((num / den).setScale(4, BigDecimal.ROUND_HALF_UP).max(0G))
        logI "PM ET₀: ${et0in} in/day (Tmean=${Tmean}°C Rs=${Rs}MJ Rn=${Rn} u2=${u2} VPD=${vpd})"
        return et0in
    } catch (Exception e) { logW "calcPM: ${e.message}"; return 0G }
}

// ── Hargreaves fallback — uses NOAA Tmax/Tmin if cached, else estimates dT ──
private BigDecimal calcHargreaves(BigDecimal tempF, BigDecimal tMaxF=null, BigDecimal tMinF=null) {
    if (tempF == null) return 0G   // explicit null check — 0°F is a valid temperature (falsy in Groovy)
    try {
        BigDecimal Tmean = fToC(tempF)
        BigDecimal dT

        // Prefer passed-in values (from NOAA fetchNoaaWeatherForEt),
        // then fall back to cached daily values, then estimate
        def useTMax = tMaxF ?: atomicState.noaaCacheTmax as BigDecimal
        def useTMin = tMinF ?: atomicState.noaaCacheTmin as BigDecimal
        if (useTMax && useTMin && useTMax > useTMin) {
            dT = (fToC(useTMax) - fToC(useTMin)).abs()
            logD "Hargreaves dT from ${tMaxF ? 'passed-in' : 'NOAA cache'}: ${dT}°C"
        } else {
            // No Tmax/Tmin available — use a climatological estimate.
            // 15°C is a typical semi-arid summer average diurnal range (NOAA normals).
            dT = 15G
            logD "Hargreaves dT: estimated 15°C (no Tmax/Tmin — enable NOAA for better accuracy)"
        }

        BigDecimal lat   = (location.latitude ?: 0.0) as BigDecimal
        int doy          = Calendar.getInstance(location.timeZone).get(Calendar.DAY_OF_YEAR)
        BigDecimal Ra    = calcRa(degToRad(lat), doy)
        if (Ra <= 0G || dT <= 0G) return 0G
        BigDecimal et0in = mmToIn(new BigDecimal(0.0023d*(Tmean+17.8G).toDouble()*Math.sqrt(dT.toDouble())*Ra.toDouble()).setScale(3,BigDecimal.ROUND_HALF_UP).max(0G))
        logI "Hargreaves ET₀: ${et0in} in/day (dT=${dT}°C)"
        return et0in
    } catch (Exception e) { logW "calcHargreaves: ${e.message}"; return 0G }
}

// ── Ra — FAO-56 Eq. 21 ───────────────────────────────────────────────────────
private BigDecimal calcRa(BigDecimal latRad, int j) {
    try {
        double phi=latRad.toDouble(),Gsc=0.0820d
        double dr=1+0.033*Math.cos(2*Math.PI*j/365),delta=0.409*Math.sin(2*Math.PI*j/365-1.39)
        double ws=Math.acos(Math.max(-1d,Math.min(1d,-Math.tan(phi)*Math.tan(delta))))
        double Ra=(24*60/Math.PI)*Gsc*dr*(ws*Math.sin(phi)*Math.sin(delta)+Math.cos(phi)*Math.cos(delta)*Math.sin(ws))
        return (Ra<0?0d:Ra).toBigDecimal().setScale(3,BigDecimal.ROUND_HALF_UP)
    } catch (Exception e) { logW "calcRa: ${e.message}"; return 0G }
}

// Daylight hours for the given latitude and day-of-year (FAO-56 Eq. 34): N = 24/π × ωs
private BigDecimal calcDaylightHours(BigDecimal latRad, int j) {
    try {
        double phi = latRad.toDouble()
        double delta = 0.409*Math.sin(2*Math.PI*j/365-1.39)
        double ws = Math.acos(Math.max(-1d, Math.min(1d, -Math.tan(phi)*Math.tan(delta))))
        return ((24.0/Math.PI) * ws).toBigDecimal().setScale(3, BigDecimal.ROUND_HALF_UP)
    } catch (Exception e) { logW "calcDaylightHours: ${e.message}"; return 12G }
}

// Convert an INSTANTANEOUS solar-noon irradiance reading (W/m², the daily peak) into
// an estimated DAILY-TOTAL solar radiation Rs in MJ/m²/day, which is what FAO-56 needs.
// Over a daylight period the irradiance follows roughly a half-sine; its average is the
// peak × 2/π. Daily total energy = peakWm2 × (2/π) × daylightSeconds, then J→MJ (÷1e6).
// Capped at the clear-sky maximum (0.75 × Ra) so a spuriously high noon spike can't
// produce an impossible daily total.
private BigDecimal noonPeakToDailyRs(BigDecimal peakWm2, BigDecimal latRad, int doy) {
    try {
        if (peakWm2 == null || peakWm2 <= 0G) return 0G
        BigDecimal daylightHrs = calcDaylightHours(latRad, doy)
        double avgWm2     = peakWm2.toDouble() * (2.0/Math.PI)
        double daylightSec = daylightHrs.toDouble() * 3600.0
        double rsMJ       = avgWm2 * daylightSec / 1_000_000.0
        // Clear-sky ceiling: Rso = 0.75 × Ra
        BigDecimal Ra  = calcRa(latRad, doy)
        double rsoMax  = Ra.toDouble() * 0.75
        if (rsoMax > 0 && rsMJ > rsoMax) rsMJ = rsoMax
        return rsMJ.toBigDecimal().setScale(4, BigDecimal.ROUND_HALF_UP)
    } catch (Exception e) { logW "noonPeakToDailyRs: ${e.message}"; return 0G }
}

private BigDecimal satVapPres(BigDecimal tc) {
    (0.6108G*Math.exp((17.27*tc.doubleValue())/(tc.doubleValue()+237.3))).toBigDecimal().setScale(4,BigDecimal.ROUND_HALF_UP)
}
private BigDecimal actualVapPres(BigDecimal es, BigDecimal rh, BigDecimal dpF) {
    if (dpF != null) return satVapPres(fToC(dpF))
    if (rh  != null) return (es*rh/100G).setScale(4,BigDecimal.ROUND_HALF_UP)
    logW "actualVapPres: no RH or dew point — assuming 50%"; return (es*0.50G).setScale(4,BigDecimal.ROUND_HALF_UP)
}

// ═══════════════════════════════════════════════════════════════════════════════
// NOAA NWS — opt-in only, never called unless noaaFallbackEnabled = true
// ═══════════════════════════════════════════════════════════════════════════════

// Shared NOAA HTTP helper. Uses textParser+JsonSlurper so we don't depend on
// Hubitat auto-parsing application/geo+json content-type. Returns parsed Map
// on success, null on any error.
private Map noaaFetch(String url) {
    Map result = null
    try {
        httpGet([
            uri    : url,
            headers: ["User-Agent": "${APP_NAME}/${APP_VERSION}", "Accept": "application/geo+json"],
            timeout: 10,            // seconds — NWS is normally fast; cap to prevent thread starvation
            textParser: true        // get raw text; we parse explicitly below
        ]) { r ->
            if (r.status == 200) {
                result = new groovy.json.JsonSlurper().parseText(r.data.text) as Map
            } else {
                logW "noaaFetch: HTTP ${r.status} for ${url}"
            }
        }
    } catch (Exception e) {
        logW "noaaFetch: ${e.message}"
    }
    return result
}

// Formatted coordinate string for NOAA URLs — 4 decimal places max
private String noaaCoord() {
    String lat = String.format("%.4f", (location.latitude  as double))
    String lon = String.format("%.4f", (location.longitude as double))
    return "${lat},${lon}"
}

private String testNoaaConnection() {
    if (isLocalOnlyMode())             return "⚠️ Blocked — LOCAL-ONLY mode is on"
    if (!settings.noaaFallbackEnabled) return "⚠️ NOAA fallback is disabled"
    def data = noaaFetch("https://api.weather.gov/points/${noaaCoord()}")
    String result = (data != null) ?
        "✅ NOAA connected — ${data?.properties?.relativeLocation?.properties?.city ?: noaaCoord()}" :
        "❌ NOAA fetch failed (check hub internet access)"
    String timestamp = ts()
    atomicState.noaaTestResult    = result
    atomicState.noaaTestTimestamp = timestamp
    atomicState.lastNoaaResult    = [timestamp: timestamp, result: result]
    return result
}

private def fetchNoaaCurrentTemp() {
    if (isLocalOnlyMode())             { logD "NOAA: blocked by LOCAL-ONLY mode"; return null }
    if (!settings.noaaFallbackEnabled) return null
    try {
        def points = noaaFetch("https://api.weather.gov/points/${noaaCoord()}")
        String hourlyUrl = points?.properties?.forecastHourly
        if (!hourlyUrl) { logW "NOAA: no forecastHourly URL in points response"; return null }

        def forecast = noaaFetch(hourlyUrl)
        def periods  = forecast?.properties?.periods
        if (!periods) { logW "NOAA: no periods in hourly forecast"; return null }

        def now  = new Date()
        def cur  = periods.find { pd ->
            try {
                def s = Date.parse("yyyy-MM-dd'T'HH:mm:ssXXX", pd.startTime as String)
                def e = Date.parse("yyyy-MM-dd'T'HH:mm:ssXXX", pd.endTime   as String)
                return now >= s && now <= e
            } catch (ex) { return false }
        } ?: periods[0]

        if (cur?.temperature == null) { logW "NOAA: current period has no temperature"; return null }
        BigDecimal tempF = (cur.temperatureUnit == "C") ?
            cToF(cur.temperature as BigDecimal) : cur.temperature as BigDecimal
        logI "NOAA current temp: ${tempF}°F"
        atomicState.lastNoaaResult = [timestamp: ts(), result: "Current temp: ${tempF}°F"]
        return tempF

    } catch (Exception e) { logW "fetchNoaaCurrentTemp: ${e.message}"; return null }
}

private Map fetchNoaaWeatherForEt() {
    if (isLocalOnlyMode())             { logD "NOAA: blocked by LOCAL-ONLY mode"; return null }
    if (!settings.noaaFallbackEnabled) return null
    try {
        def points  = noaaFetch("https://api.weather.gov/points/${noaaCoord()}")
        String gridUrl = points?.properties?.forecastGridData
        if (!gridUrl) { logW "NOAA: no forecastGridData URL in points response"; return null }

        def grid = noaaFetch(gridUrl)
        def p    = grid?.properties
        if (!p) { logW "NOAA: no properties in grid data response"; return null }

        Map res = [source: "NOAA NWS"]
        def tMaxC = p.maxTemperature?.values?.getAt(0)?.value
        def tMinC = p.minTemperature?.values?.getAt(0)?.value
        if (tMaxC != null) res.tMaxF = cToF(tMaxC as BigDecimal)
        if (tMinC != null) res.tMinF = cToF(tMinC as BigDecimal)
        if (res.tMaxF && res.tMinF) {
            res.tempF = (((res.tMaxF as BigDecimal) + (res.tMinF as BigDecimal)) / 2G).setScale(1, BigDecimal.ROUND_HALF_UP)
        }
        def rh   = p.relativeHumidity?.values?.getAt(0)?.value
        if (rh   != null) res.humidity = rh as BigDecimal
        def wind = p.windSpeed?.values?.getAt(0)?.value  // NOAA reports in km/h
        if (wind != null) res.windMph  = ((wind as BigDecimal) * 0.621371G).setScale(1, BigDecimal.ROUND_HALF_UP)

        atomicState.lastNoaaResult = [timestamp: ts(),
            result: "ET data: temp=${res.tempF}°F rh=${res.humidity}% wind=${res.windMph}mph"]
        logI "NOAA ET data: ${atomicState.lastNoaaResult.result}"
        return res.size() > 1 ? res : null

    } catch (Exception e) { logW "fetchNoaaWeatherForEt: ${e.message}"; return null }
}

// ── Unit conversions ────────────────────────────────────────────────────────
// Convert a temperature reading FROM the station TO Fahrenheit for ET equations.
// Must check location.temperatureScale — if hub is set to Celsius, the station
// already reports Celsius and we must NOT call fToC() again.
// Reference: https://docs2.hubitat.com/en/developer/location-object (temperatureScale)
private BigDecimal stationTempToF(BigDecimal t) {
    if (t == null) return null
    return (location.temperatureScale == "C") ? cToF(t) : t
}

private BigDecimal fToC(BigDecimal f)    { if (f == null) return null; ((f-32G)*5G/9G).setScale(3,BigDecimal.ROUND_HALF_UP) }
private BigDecimal cToF(BigDecimal c)    { if (c == null) return null; (c*9G/5G+32G).setScale(1,BigDecimal.ROUND_HALF_UP) }
private BigDecimal ftToM(def ft)         { ((ft as BigDecimal)/3.28084G).setScale(3,BigDecimal.ROUND_HALF_UP) }
private BigDecimal mphToMs(BigDecimal m) { if (!m) return 0G; (m*0.44704G).setScale(3,BigDecimal.ROUND_HALF_UP) }
private BigDecimal mmToIn(BigDecimal mm) { if (!mm) return 0G; (mm/25.4G).setScale(4,BigDecimal.ROUND_HALF_UP) }
private BigDecimal degToRad(BigDecimal d){ if (!d) return 0G; (d*(Math.PI/180.0)).toBigDecimal().setScale(6,BigDecimal.ROUND_HALF_UP) }

// ═══════════════════════════════════════════════════════════════════════════════
// UTILITIES
// ═══════════════════════════════════════════════════════════════════════════════

private int safeZoneCount()     { Math.max(0, Math.min((settings.zoneCount?:0) as int, MAX_ZONES)) }
private int safeParamZone(def r) {
    // Math.max/min replaces .clamp() which is not available in Hubitat's Groovy sandbox
    int z = (r ?: 1) as int
    return Math.max(1, Math.min(MAX_ZONES, z))
}
private int activeLogLevel() {
    // settings.logLevel is null on first install (user hasn't visited logging page yet).
    // Cast to boxed Integer (returns null for null) not primitive int (throws on null).
    return (settings.logLevel as Integer) ?: LOG_INFO
}

private List<String> getDeviceAttrs(device) {
    try { device.supportedAttributes.collect { it.name } ?: [] }
    catch (e) { logD "getDeviceAttrs: ${e.message}"; [] }
}

private String resolvedAttr(String key, String fallback) {
    def v = settings[key]; (v && v != "(not available)") ? v : fallback
}

private def safeGetAttr(device, String attr) {
    try { device.currentValue(attr) } catch (e) { logD "getAttr(${attr}): ${e.message}"; null }
}

private BigDecimal safeGetAttrBD(device, String attr) {
    def v = safeGetAttr(device, attr); if (v == null) return null
    try { v as BigDecimal } catch (e) { null }
}

private String fmtTime(String t) { try { toDateTime(t).format("h:mm a", location.timeZone) } catch (e) { t?:"?" } }
private String ts()              { new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone) }

// ═══════════════════════════════════════════════════════════════════════════════
// "NORMAL" CONTEXT HELPERS — shown next to calculated values in diagnostics
// so users can immediately tell if a reading is reasonable
// ═══════════════════════════════════════════════════════════════════════════════

private String etoContext(def etoIn, def baselineIn) {
    // Show ETo relative to the seasonal baseline so users know if today is normal
    try {
        BigDecimal eto      = etoIn as BigDecimal
        BigDecimal baseline = baselineIn as BigDecimal
        if (baseline <= 0G) return ""
        BigDecimal ratio = (eto / baseline).setScale(1, BigDecimal.ROUND_HALF_UP)
        String desc; String color
        if      (ratio < 0.3G) { desc = "very low — overcast or cold";       color = "gray"   }
        else if (ratio < 0.7G) { desc = "below average — cool/cloudy day";   color = "blue"   }
        else if (ratio < 1.4G) { desc = "near average — normal day";         color = "green"  }
        else if (ratio < 2.2G) { desc = "above average — hot or very sunny"; color = "orange" }
        else if (ratio < 3.5G) { desc = "well above average — hot dry day";  color = "orange" }
        else                   { desc = "⚠️ SUSPICIOUS — likely bad sensor data (check solar attribute)"; color = "red" }
        return " <font color='${color}'><small>${ratio}× average — ${desc}</small></font>"
    } catch (e) { return "" }
}

private String solarContext(def wm2Val) {
    // Show what the solar radiation level means in plain English
    try {
        BigDecimal w = wm2Val as BigDecimal
        if (w <= 0G)    return " <small style='color:gray'>(night / no sun)</small>"
        if (w < 50G)    return " <small style='color:gray'>(twilight or heavy shade)</small>"
        if (w < 200G)   return " <small style='color:gray'>(heavily overcast — minimal ET effect)</small>"
        if (w < 450G)   return " <small style='color:#888'>(cloudy)</small>"
        if (w < 700G)   return " <small style='color:#666'>(partly cloudy)</small>"
        if (w < 900G)   return " <small style='color:green'>(mostly clear)</small>"
        if (w <= 1200G) return " <small style='color:green'>(clear sky — near maximum)</small>"
        return " <font color='red'><small>(⚠️ IMPOSSIBLE — max at Earth's surface is ~1,200 W/m². Check Solar Radiation attribute mapping.)</small></font>"
    } catch (e) { return "" }
}

private String tempContext(def tempF) {
    try {
        BigDecimal t = tempF as BigDecimal
        String desc; String color
        if      (t < 28G)  { desc = "hard freeze — irrigation suspended";  color = "blue"   }
        else if (t < 38G)  { desc = "freezing — freeze guard will trigger"; color = "blue"   }
        else if (t < 50G)  { desc = "cold";                                color = "gray"   }
        else if (t < 65G)  { desc = "cool";                                color = "gray"   }
        else if (t < 85G)  { desc = "warm — typical irrigation season";    color = "green"  }
        else if (t < 95G)  { desc = "hot — higher water demand";           color = "orange" }
        else               { desc = "very hot";                            color = "red"    }
        return " <font color='${color}'><small>(${desc})</small></font>"
    } catch (e) { return "" }
}

private String humidityContext(def rh) {
    try {
        BigDecimal h = rh as BigDecimal
        String desc; String color
        if      (h < 20G)  { desc = "very dry — increases ET significantly"; color = "orange" }
        else if (h < 40G)  { desc = "dry";                                   color = "gray"   }
        else if (h < 70G)  { desc = "normal";                                color = "green"  }
        else if (h < 85G)  { desc = "humid";                                 color = "gray"   }
        else               { desc = "very humid — reduces ET";               color = "blue"   }
        return " <font color='${color}'><small>(${desc})</small></font>"
    } catch (e) { return "" }
}

private String windContext(def mph) {
    try {
        BigDecimal w = mph as BigDecimal
        String desc
        if      (w < 3G)   desc = "calm — minimal ET effect"
        else if (w < 10G)  desc = "light breeze — minor ET effect"
        else if (w < 20G)  desc = "moderate — noticeable ET increase"
        else if (w < 30G)  desc = "fresh — significant ET increase"
        else               desc = "strong wind — major ET increase"
        return " <small style='color:gray'>(${desc})</small>"
    } catch (e) { return "" }
}

private String popContext(def pop) {
    try {
        int p = pop as int
        String desc; String color
        if      (p < 20)  { desc = "unlikely";               color = "gray"   }
        else if (p < 40)  { desc = "slight chance";          color = "gray"   }
        else if (p < 60)  { desc = "possible";               color = "orange" }
        else if (p < 80)  { desc = "likely — consider skip"; color = "orange" }
        else              { desc = "very likely — skip recommended"; color = "red" }
        return " <font color='${color}'><small>(${desc})</small></font>"
    } catch (e) { return "" }
}

private String pressureTrendContext(BigDecimal changeIn3hr) {
    if (changeIn3hr == null) return ""
    String desc; String color
    if      (changeIn3hr > 0.05G)  { desc = "rising — improving weather";      color = "green"  }
    else if (changeIn3hr > -0.05G) { desc = "steady — no storm indicated";      color = "green"  }
    else if (changeIn3hr > -0.10G) { desc = "slight fall — watch for changes";  color = "gray"   }
    else if (changeIn3hr > -0.20G) { desc = "falling — storm approaching";      color = "orange" }
    else                           { desc = "rapid fall — significant storm";   color = "red"    }
    return " <font color='${color}'><small>(${desc}; normal: −0.05 to +0.05\")</small></font>"
}

private String soilContext(def pct, int threshold) {
    try {
        int p = pct as int
        if (p <= threshold)     return " <font color='orange'><small>(dry — below watering threshold of ${threshold}%)</small></font>"
        if (p <= threshold + 10) return " <font color='gray'><small>(borderline — close to threshold)</small></font>"
        if (p < 70)             return " <font color='green'><small>(adequately moist)</small></font>"
        return " <font color='blue'><small>(very wet — well above threshold)</small></font>"
    } catch (e) { return "" }
}

// ═══════════════════════════════════════════════════════════════════════════════
// LOGGING — 6 levels, level-gated
// ═══════════════════════════════════════════════════════════════════════════════

private void logT(String m) { if (activeLogLevel()>=LOG_TRACE) log.trace "[${APP_NAME}] ${m}" }
private void logD(String m) { if (activeLogLevel()>=LOG_DEBUG) log.debug "[${APP_NAME}] ${m}" }
private void logI(String m) { if (activeLogLevel()>=LOG_INFO)  log.info  "[${APP_NAME}] ${m}" }
private void logW(String m) { if (activeLogLevel()>=LOG_WARN)  log.warn  "[${APP_NAME}] ${m}" }
private void logE(String m) { if (activeLogLevel()>=LOG_ERROR) log.error "[${APP_NAME}] ${m}" }