/**
 * Valetudo Robot Vacuum — Hubitat Device Driver
 *
 * Complete integration of any Valetudo-flashed robot vacuum with Hubitat Elevation.
 * Uses MQTT for real-time state + commands, with optional REST API for discovery,
 * on-demand refresh, and command fallback when the MQTT broker is unavailable.
 *
 * All automation logic (presence, security, lighting, scheduling) is built in
 * Rule Machine using the attributes and commands this driver exposes. No app needed.
 *
 * Tested against: Dreame X40 Master + X40 Ultra / Valetudo 2026.10.0 (firmware 1782)
 * Verified against: MOVA V50 Ultra (mova.vacuum.r2582k) / Valetudo 2026.10.0 (firmware 4676):
 *   every REST body checked against the V50's own Swagger, live REST reads, MQTT publishing.
 * Compatible with: Any Valetudo-supported robot (Dreame, Roborock, Viomi, Midea).
 *   Since 3.13.0 the driver discovers what each robot supports (see v3.13.0 notes) and
 *   blocks unsupported commands/values locally instead of sending them.
 *
 * AUTHORITATIVE SOURCES (payload formats verified against these):
 *   MQTT API:  https://valetudo.cloud/pages/integrations/mqtt/
 *   REST API:  http://<ROBOT_IP>/swagger/   (live, per-robot OpenAPI docs)
 *   Hubitat:   https://docs2.hubitat.com/en/developer/interfaces/mqtt-interface
 *
 * ── IMPORTANT PAYLOAD NOTE ────────────────────────────────────────────────────
 *   Over MQTT, Valetudo uses BARE ENUM STRINGS for simple commands, NOT JSON:
 *     BasicControlCapability/operation/set  → START | STOP | PAUSE | HOME
 *     LocateCapability/locate/set           → PERFORM
 *     AutoEmptyDockManualTriggerCapability  → PERFORM
 *     ConsumableMonitoringCapability/.../reset/set → PERFORM
 *     *ControlCapability/enabled/set        → ON | OFF
 *     FanSpeedControlCapability/preset/set  → off|min|low|medium|high|turbo|max
 *   Only complex commands use JSON (identical to the REST body):
 *     MapSegmentationCapability/clean/set   → {"segment_ids":[...],"iterations":N,"customOrder":bool}
 *     ZoneCleaningCapability/start/set      → {"zones":[{"points":{...}}],"iterations":N}
 *     GoToLocationCapability/go/set         → {"coordinates":{"x":N,"y":N}}
 *   The REST API uses JSON {"action":"..."} for everything. This driver handles both.
 *
 * ── ATTRIBUTE REFERENCE (for Rule Machine triggers/conditions) ────────────────
 *   switch                "on" cleaning | "off" docked/idle/paused
 *   battery               0-100 (%)
 *   batteryStatus         none | charging | discharging | charged
 *                         (since 3.13.0 also "charged" when docked at the robot's
 *                         Charging Limit quirk, e.g. MOVA V50 stops at 90% and reports none)
 *   robotStatus           idle|cleaning|returning|docked|paused|error|manual_control|moving
 *   robotStatusFlag       none|segment|zone|spot|target|resumable|mapping
 *                         (since Valetudo 2026.08.0 "resumable" can also appear while
 *                         returning, paused or idle, not only docked)
 *   robotError            highest-severity active fault text ("none" when clear)
 *   robotErrorDetail      Valetudo's structured error: "level/kind/subsystem: message"
 *                         ("none" when clear). Display only — does NOT feed the alert engine.
 *   activeAlerts / pushAlerts / alertCount / alertSeverity — see v3.7.0 notes below
 *   robotAvailability     online|offline — the robot's own MQTT presence ($state)
 *   robotModel            e.g. "MOVA V50" / "Dreame X40 Ultra" (via discoverRobot)
 *   supportedOptions      JSON {fanSpeed:[...], waterLevel:[...], ...} this robot accepts
 *   unsupportedCommands   comma list of driver commands this robot cannot do ("none" = all)
 *   dockStatus            idle|pause|cleaning|emptying|drying|error (model dependent)
 *   operationMode         mop|vacuum|vacuum_and_mop|vacuum_then_mop (if supported)
 *   fanSpeed              current fan preset
 *   waterLevel            current water preset
 *   attachment_mop        attached|detached
 *   attachment_dustbin    attached|detached ("not supported" if the robot does not report it)
 *   attachment_watertank  attached|detached ("not supported" if the robot does not report it)
 *   keyLock               ON|OFF
 *   carpetMode            ON|OFF
 *   carpetSensorMode      off|avoid|lift|detach (model dependent; via MQTT or refreshSettings)
 *   obstacleAvoidance     ON|OFF
 *   petObstacleAvoidance  ON|OFF
 *   speakerVolume         0-100
 *   mapSegments            JSON {id:name,...} — your room directory
 *   activeSegments         JSON [id,...] — last commanded segments (not live position)
 *   wifiSSID / wifiSignal / wifiFrequency
 *   currentArea (cm2) / currentTime (s)
 *   totalArea (cm2) / totalTime (s) / totalCount (#)
 *   dock_dustbag / dock_freshwater / dock_wastewater / dock_detergent
 *                         ok|missing|empty|full|unknown (enum, NOT a percentage)
 *   consumables           JSON {name: value, ...} — remaining life. Keys look like
 *                         brush-main, brush-side_right, filter-main, cleaning-sensor;
 *                         values are seconds (or percent on some robots)
 *   quirks                JSON {title: value, ...} — current quirk values (via refreshQuirks).
 *                         The titles here are exactly what setQuirkByTitle expects.
 *   dnd                   on|off — Do Not Disturb enabled (via setDoNotDisturb / refreshSettings)
 *   dndWindow             "HH:MM-HH:MM" — DND window ("none" when off)
 *   cleanRoute            current clean route/intensity (via setCleanRoute / refreshSettings)
 *   autoEmptyInterval     current auto-empty dock interval (via setAutoEmptyInterval / refreshSettings)
 *   mopAutoDrying         on|off — auto-dry mops after cleanup (via setMopAutoDrying / refreshSettings)
 *   mqttStatus            connected|disconnected|not configured|error
 *   restStatus            ok|error|breaker_open|not configured
 *   lastActivity          ISO-8601 UTC timestamp
 *
 * ── COMMANDS ──────────────────────────────────────────────────────────────────
 *   Switch:        on (start) / off (dock)
 *   Basic:         start / stop / pause / dock / locate
 *   Cleaning:      cleanSegment(ids, iterations, customOrder)
 *                  cleanZones(zonesJson, iterations)
 *                  goToLocation(x, y)
 *   Presets:       setFanSpeed(preset) / setWaterLevel(preset) / setOperationMode(mode)
 *   Dock:          triggerAutoEmpty   (empty dustbin into dock bag)
 *   Toggles:       setKeyLock(on/off) / setCarpetMode(on/off)
 *                  setObstacleAvoidance(on/off) / setPetObstacleAvoidance(on/off)
 *                  setCarpetSensorMode(off/avoid/lift/detach)
 *   Sound:         setSpeakerVolume(0-100)
 *   Consumables:   resetConsumable(name)   name = a key from the consumables attribute
 *   Events:        dismissEvent(id)   acknowledge a Valetudo event (e.g. a stuck error)
 *   Quirks:        setQuirkByTitle(title, value)  <-- preferred, no UUIDs, any robot
 *                  setQuirk(quirkId, value)      <-- advanced escape hatch
 *                  setFanSpeedTurbo(on/off) / refreshQuirks (all REST)
 *   Mop dock:      mopDockDry(start/stop) / mopDockClean(start/stop) / setMopAutoDrying(on/off)
 *   Scheduling:    setDoNotDisturb(on/off, startHHMM, endHHMM)
 *   Dock/Route:    setAutoEmptyInterval(interval) / setCleanRoute(route)
 *   Maintenance:   refresh (REST state pull) / refreshSegments (REST) / refreshSettings (REST) / reconnect (MQTT)
 *                  discoverRobot (REST: what this robot supports)
 *
 * Author:  Hubitat Community contribution
 * Version: 3.13.0
 * Date:    2026-11-14
 *
 * Copyright 2025 Contributors. Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 *
 * THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED. Use at your own risk. This driver communicates with your robot over
 * UNENCRYPTED MQTT and (optionally) UNAUTHENTICATED HTTP on your local network,
 * which mirrors how Valetudo itself operates. Do NOT expose your MQTT broker or
 * robot's REST API to the public internet.
 *
 * v3.13.0 — ADAPTIVE: THE DRIVER LEARNS WHAT EACH ROBOT SUPPORTS
 *
 *   PROBLEM: the driver was written on two X40s that expose identical capabilities.
 *     On a MOVA V50 several X40 assumptions misfire:
 *     - setFanSpeedTurbo: the V50 has no Fan Speed Turbo quirk. The driver fell back
 *       to the X40's hardcoded quirk UUID, the robot rejected it, and every attempt
 *       counted toward the REST circuit breaker (3 failures pause ALL REST for 15 min).
 *     - Presets differ: V50 fan speeds are low|medium|high|max (no off/min/turbo),
 *       water has no "off". Picking an X40-only value failed the same way.
 *     - Battery: the V50's Charging Limit quirk stops charging at 90%; Valetudo then
 *       reports flag "none", so batteryStatus never read "charged" and rules waiting
 *       for it never fired.
 *
 *   FIX — discovery, then local validation:
 *     - New discoverRobot() (REST, runs on every (re)connect, also a command) reads
 *       /api/v2/robot (model, attachments, dock parts), /api/v2/robot/capabilities and
 *       the presets/properties endpoints for fan speed, water, operation mode, carpet
 *       sensor mode, clean route and auto-empty interval. Cached in state.
 *     - Every command checks the cache first. A command whose capability the robot
 *       lacks, or a value it does not accept, is logged with the valid list and NOT
 *       sent: no REST call, no breaker hit, no MQTT publish. Values are sent in the
 *       robot's exact casing.
 *     - Nothing is hidden: every command and preference stays available on every
 *       robot. Before discovery has run, or with the new "Block commands this robot
 *       does not support" preference OFF, the driver behaves exactly like 3.12.0.
 *     - setFanSpeedTurbo uses the hardcoded X40 UUID only when quirks have NEVER been
 *       discovered. Once they have and the robot has no such quirk, it logs and stops.
 *     - batteryStatus reads "charged" when the robot is docked, Valetudo reports flag
 *       "none", and the level has reached the robot's own Charging Limit quirk. Robots
 *       without that quirk are unaffected.
 *     - refreshSettings / refreshQuirks skip capabilities the robot does not have
 *       (no more 404 probes on every connect).
 *     - Attachments the robot does not report (V50: dustbin, watertank) read
 *       "not supported" instead of staying blank.
 *     - Alert rule: "drain"/"drainage" text (V50 dock drainage fault) is classified
 *       supply, ahead of the "abnormal" catastrophic-fault rule.
 *
 *   NEW: command discoverRobot; attributes robotModel, supportedOptions,
 *     unsupportedCommands; preference enforceSupported (default ON).
 *   NOT CHANGED: MQTT topics/payloads, REST bodies, alert engine, confirmation window,
 *     battery grace logic, breaker, quirk discovery, RM parameter hints.
 *
 * Changes:
 *   3.6.0 - ROBOT-INDEPENDENT QUIRKS. The driver no longer depends on any hardcoded
 *           robot-specific quirk data. refreshQuirks() now discovers this robot's own
 *           title -> UUID and title -> options maps and caches them in state, and it
 *           runs automatically on (re)connect alongside refresh/refreshSegments/
 *           refreshSettings. New command setQuirkByTitle(title, value) drives ANY quirk
 *           on ANY Valetudo robot with no UUID knowledge, validating the value against
 *           the robot's own reported options and rejecting typos with the valid list
 *           logged (it also corrects casing, which matters for values like Wet/Dry).
 *           setQuirk(uuid, value) remains as an escape hatch. setFanSpeedTurbo() now
 *           resolves its UUID in priority order: discovery cache -> optional per-device
 *           preference -> shared fallback constant, so it works on any robot exposing
 *           that quirk even if the UUID differs.
 *           Verified by comparing full quirk dumps from a Dreame X40 Master and an X40
 *           Ultra: identical 16 quirks, identical UUIDs, identical titles (only the
 *           user-configured VALUES differed). Both robots also expose an identical
 *           capability set, so every other command here is already model-agnostic.
 *           Code quality: renamed every command parameter named 'state' to 'onOff'
 *           (setKeyLock, setCarpetMode, setObstacleAvoidance, setPetObstacleAvoidance,
 *           setToggle, setFanSpeedTurbo, setMopAutoDrying, setDoNotDisturb). Those
 *           parameters shadowed Hubitat's built-in 'state' object — harmless before,
 *           but an active hazard now that quirk resolution reads state.quirkIds.
 *   3.4.0 - Added REST-only capability commands (all bodies verified against the robot's
 *           Swagger except MopDockClean, see note): mopDockDry(start/stop),
 *           mopDockClean(start/stop, body unconfirmed — isolated in mopDockCleanBody),
 *           setDoNotDisturb(state,start,end), setCleanRoute(route),
 *           setAutoEmptyInterval(interval), setMopAutoDrying(on/off), and refreshSettings()
 *           to read them back. New attributes: dnd, dndWindow, cleanRoute,
 *           autoEmptyInterval, mopAutoDrying. Stateful setters update their attribute
 *           optimistically; refreshSettings reconciles from the robot and its per-endpoint
 *           GET treats a 404 (unsupported capability) as a skip, not a breaker failure.
 *           Rule Machine teaching clues: every command's parameter description now names
 *           the RM parameter type AND the attribute it reflects, and the parameterless
 *           basic commands carry inline comments pointing to the attributes they change,
 *           applied consistently across new and pre-existing commands.
 *   3.3.1 - Verified against the robot's own Swagger export (X40 Master):
 *           * QUIRK PUT FORMAT CONFIRMED: {"id","value"} is correct (no action field).
 *             The v3.3.0 curl verification step is no longer needed.
 *           * FIXED preset REST fallback bug: setFanSpeed/setWaterLevel/setOperationMode
 *             were doing PUT .../preset/<value> with an empty body, but the API wants
 *             PUT .../preset with body {"name":"<value>"}. The old form 400'd whenever
 *             MQTT was down. MQTT (primary) path was always correct; only the fallback
 *             was broken. Now sends the documented body.
 *           * Removed encPath() + the URLEncoder import: it only existed to sanitize the
 *             preset value in the URL path, and the value no longer goes in the path.
 *           KNOWN REMAINING ISSUE (not yet fixed): resetConsumable's REST fallback sends
 *           PUT ConsumableMonitoringCapability {"action":"reset","type":name}, but Swagger
 *           shows the type/sub_type are PATH segments: PUT .../ConsumableMonitoringCapability/
 *           <type>[/<sub_type>] with body {"action":"reset"}. MQTT path is fine. Left as-is
 *           pending a decision on how to split the stored consumable key into type/subtype.
 *   3.3.0 - Added Quirks support (vendor-specific tunables). Quirks are REST-ONLY in
 *           Valetudo (no MQTT handle exists), so these obey restFallback + the circuit
 *           breaker like any other REST command. New: setQuirk(quirkId, value) generic
 *           engine, setFanSpeedTurbo(on/off) convenience wrapper for the X40's turbo
 *           quirk (UUID discovered at runtime as of 3.6.0), and refreshQuirks() which
 *           reads current values into the new 'quirks' attribute. Fan Speed Turbo is a
 *           one-shot: the robot self-clears it after the next cleanup — set it right
 *           before dispatching a clean; a read-back may already show off.
 *           IMPORTANT: the quirk PUT body shape is {"id","value"} — CONFIRMED correct
 *           against the robot's Swagger in 3.3.1. Isolated to quirkPutBody() should a
 *           future firmware ever change it.
 *   3.2.0 - Fixed a real bug: the "Use REST as command fallback when MQTT is down"
 *           preference (restFallback) was defined in the UI but never read anywhere
 *           in code — every command always fell back to REST regardless of the
 *           toggle. restPut() now honors it; unchecking it in device preferences
 *           fully silences REST command attempts while MQTT is down (e.g. robot
 *           powered off for repair) with zero code changes needed.
 *           Added a REST failure circuit breaker (shared by restPut, refresh and
 *           refreshSegments via the restBlocked() gate): after REST_FAIL_THRESHOLD
 *           consecutive failures, further attempts are skipped for
 *           REST_BREAKER_COOLDOWN_SEC (fails fast at debug level — no socket, no
 *           timeout wait, no per-attempt error log) instead of retrying and logging
 *           every call. Resets automatically on the next success; per-device
 *           (state-scoped), not global. New restStatus value "breaker_open"
 *           distinguishes "actively down, not retrying" from a transient "error".
 *           State bookkeeping (restFailCount / restBreakerUntil) is written from the
 *           command's own method frame, NOT from inside the http response closure,
 *           so Hubitat persists it reliably. Deliberate state pulls (refresh /
 *           refreshSegments) honor the breaker but ignore restFallback so a manual
 *           Refresh still attempts.
 *           Security: preset/mode values interpolated into REST paths (setFanSpeed,
 *           setWaterLevel, setOperationMode) are now URL-encoded via encPath() to
 *           prevent path traversal / malformed URLs from unexpected Rule Machine input.
 *           KNOWN LIMITATION: state.restFailCount is a read-modify-write with no
 *           driver-level mutex available on Hubitat, so simultaneous failures may lose
 *           an increment (breaker trips at ~4 instead of exactly 3). Benign; documented.
 *   3.1.0 - Rule Machine robustness: multi-arg commands (cleanSegment, cleanZones,
 *           goToLocation, setSpeakerVolume) now accept String/Number/Boolean forms via
 *           coercion helpers, since RM only offers string/number/decimal parameter types
 *           (no boolean). customOrder now accepts true/false/1/0/yes/on in any form.
 *           Rewrote every command parameter description to state the exact RM type to pick.
 *           Restored a logInfo helper that a prior edit had dropped.
 *   3.0.3 - Release-readiness pass: removed temporary credential diagnostic logging;
 *           removed dead/no-op REST fallback branch in publishRaw; softened TLS hint
 *           (untested with self-signed certs); added consumables attribute size guard;
 *           skip high-frequency raw map-data topics to cut event-DB churn; sanitize
 *           robot IP host in restBase(); added copyright/disclaimer.
 *   3.0.2 - Corrected misleading broker-address hint: the Hubitat built-in broker
 *           authenticates on the hub LAN IP, not 127.0.0.1. Removed loopback default.
 *   3.0.1 - Fixed REST PUT incorrectly logging success (2xx) as an error; response
 *           body is now read as text so non-JSON 200 replies no longer throw.
 *   3.0.0 - Full capability coverage. CORRECTED BasicControl/Locate payloads to bare
 *           enum strings (was incorrectly JSON in v2). Added ZoneCleaning, GoToLocation,
 *           OperationMode, KeyLock, CarpetMode, ObstacleAvoidance, PetObstacleAvoidance,
 *           SpeakerVolume, AutoEmptyDock trigger, consumable reset, DockComponent state.
 *           Added optional REST API: refresh(), refreshSegments(), and automatic command
 *           fallback to REST when MQTT is disconnected. Added robot IP preference.
 *
 * v3.7.0 — ALERT ENGINE + MQTT CONNECTION FIXES
 *
 *   PROBLEM FIXED (event storm):
 *     robotError was written by two independent code paths — the
 *     StatusStateAttribute/error_description topic handler AND a loop inside
 *     handleValetudoEvents — both with isStateChange:true and no change guard.
 *     When the robot had two concurrent faults, every ~30s Valetudo push produced
 *     3-5 robotError events with alternating values, all within ~10ms. Any Rule
 *     Machine rule triggering on "robotError *changed*" fired on each one, which
 *     measured out at roughly 600 notifications per hour on a live hub.
 *
 *     A change guard alone does not fix this. Inside a sub-100ms burst,
 *     device.currentValue() still returns the pre-burst value because sendEvent
 *     has not committed yet, so the guard passes every time. And with two genuinely
 *     concurrent faults, a single-string attribute has no correct value to hold —
 *     it can only alternate.
 *
 *   FIX — treat active faults as a SET, not a string:
 *     - Each source writes into its own slice of state.alertSlices rather than
 *       calling sendEvent directly.
 *     - Any write schedules flushAlerts() on a debounce (ALERT_DEBOUNCE_SEC).
 *       Repeated runIn() with the same handler name replaces the pending schedule,
 *       so an entire burst coalesces into ONE flush.
 *     - flushAlerts() merges all slices, sorts deterministically, renders the
 *       strings, and compares against state (NOT currentValue, which lags) —
 *       emitting at most one event per attribute per flush.
 *     Result: 600 events/hour becomes ~2 per fault (raised, then cleared).
 *
 *   NEW ATTRIBUTES:
 *     activeAlerts    "Low battery; Mop Dock Clean Water Tank empty" ("" = all clear)
 *                     Single combined summary. Use this for notifications, not robotError.
 *     alertCount      number of distinct active faults (0 = all clear)
 *     alertSeverity   highest active severity: fault | supply | mop | info | none
 *     pushAlerts      the subset of activeAlerts whose severity is push-worthy
 *                     (fault/supply/mop). Excludes battery and consumables.
 *
 *   RESTORED: the valetudoEvent attribute, dropped between v3.2.0 and v3.6.0.
 *
 *   MQTT CONNECTION FIXES:
 *     - mqttConnect() now checks interfaces.mqtt.isConnected() and a state re-entry
 *       flag before connecting. Previously nothing prevented overlapping connects.
 *     - mqttSubscribeAll() subscribes at most once per session. Hubitat does NOT
 *       de-duplicate MQTT subscriptions; calling subscribe() twice on one session
 *       delivers every inbound message twice, multiplying the event storm above.
 *     - mqttStatus is now written only on actual change. A live hub logged
 *       "connected" nine times in eight seconds under the old code.
 *
 *   robotError is RETAINED for backward compatibility with existing rules, but is
 *   now single-writer and change-guarded. Prefer activeAlerts for new rules.
 *
 * v3.12.0 — VALETUDO 2026.10.0 COMPATIBILITY
 *
 *   Verified against Valetudo 2026.10.0 on a Dreame X40 Master and X40 Ultra:
 *   every topic and REST endpoint this driver used still exists with the same
 *   payloads (live capture + both robots' Swagger). No command, attribute or
 *   preference was renamed or removed.
 *
 *   ALERT CLASSIFICATION (the part that changes notifications):
 *     - Valetudo 2026.10.0 (commit 359a212) maps Dreame code 93 to "Cannot reach
 *       target". Before, it arrived as "Unknown error 93", matched nothing, and
 *       stayed silent. It matched the fault pattern ("cannot reach"), so it would
 *       have pushed AND set alertSeverity=fault, blocking scheduled runs. It is now
 *       classified "supply": it still pushes, but does not read as a fault.
 *       Valetudo itself rates it TRANSIENT/WARNING.
 *     - Pre-existing gap fixed: hardware failures Valetudo rates CATASTROPHIC
 *       ("Gyroscope sensor error", "Fan speed abnormal", "Camera fault",
 *       "ToF Sensor offline", "RTC fault", "3.3V rail abnormal", ...) matched no
 *       pattern and fell to "info", so they never pushed. They are now "fault".
 *       Side effect: "Water pump fault" moves from supply to fault.
 *
 *   FIXES:
 *     - resetConsumable REST fallback (known issue since 3.3.1) now uses the
 *       documented PUT .../ConsumableMonitoringCapability/<type>/<sub_type> with
 *       {"action":"reset"}. The key is split on its first "-" (brush-main ->
 *       brush/main). Names are validated against [A-Za-z0-9_-] for BOTH the MQTT
 *       topic and the REST path; anything else is rejected with a warning. That
 *       allowlist makes URL-encoding a no-op, so no encoder import is needed.
 *     - refresh() (REST) now maps DockComponentStateAttribute (dock_*) and
 *       DockStatusStateAttribute (dockStatus). Before, a REST-only refresh never
 *       updated them.
 *     - robotAvailability is now known at connect. Valetudo publishes $state as
 *       retained, but on this broker it was not replayed to new subscribers
 *       (observed live on both robots; cause unverified). If no $state arrives
 *       within AVAILABILITY_PROBE_SEC of subscribing, ONE quiet REST read of
 *       /api/v2/mqtt/status sets it. A failed probe changes nothing and logs at
 *       debug only, so a powered-off robot cannot raise a false "offline".
 *     - setDoNotDisturb(off) wrote dndWindow="" — the empty-string bug fixed for
 *       alerts in 3.9.1 (Hubitat drops it, the attribute stays stale). Now "none".
 *     - mopDockClean body {"action":"start"|"stop"} is now confirmed by Swagger.
 *     - Corrected value hints: batteryStatus uses "charged" (not "full"),
 *       robotStatusFlag uses "resumable" (not "resuming"), dock_* are enums (not
 *       percentages), clean routes are normal|quick|intensive|deep, auto-empty
 *       intervals are off|infrequent|normal|frequent, consumable keys look like
 *       brush-main.
 *
 *   NEW (additive):
 *     - carpetSensorMode attribute + setCarpetSensorMode(mode). MQTT, REST fallback,
 *       also read by refreshSettings.
 *     - robotErrorDetail attribute from StatusStateAttribute/error (Valetudo's
 *       structured severity/subsystem). Display only — the alert engine is untouched.
 *     - dismissEvent(id): acknowledge a Valetudo event (documented interact topic;
 *       REST fallback PUT /api/v2/events/<id>/interact). The id is validated.
 *     - targetedSubscriptions preference (default OFF = unchanged behaviour). When
 *       ON, the driver subscribes to the specific topics it handles instead of "#".
 *       Since 2026.10.0 Valetudo always publishes raw map data (commit 5772c9c,
 *       ~12 KB + ~36 KB per map update) and Home Assistant state topics under the
 *       robot's base topic (commit 31bbc50). With "#" the hub receives all of it
 *       only to discard it. If a targeted subscribe fails part-way, the session is
 *       dropped and rebuilt, so a retry can never double-subscribe.
 *
 *   NOT CHANGED: alert debounce, confirmation window, battery grace logic, REST
 *     circuit breaker, offline handling, quirk discovery, RM parameter hints.
 *
 * v3.11.0 — TRANSIENT FAULT CONFIRMATION WINDOW
 *
 *   PROBLEM FIXED (momentary faults treated as real):
 *     Dreame robots emit short-lived faults during normal driving. Observed live:
 *     "Wheel lost floor contact" for a few seconds while crossing a door
 *     threshold. It matched the fault pattern ("wheel"), so it pushed a phone
 *     notification, and because alertSeverity could still read "fault" at the next
 *     guard evaluation, scheduled runs were skipped after cleaning one room.
 *   FIX: a NEW alert must be continuously present for alertConfirmSec (preference,
 *     default 20s, 0 disables) before it is published to any attribute. First-seen
 *     timestamps are dropped the moment the text disappears, so repeated blips
 *     never accumulate confirmation time. Clears are never delayed. Pending alerts
 *     schedule a re-flush at maturity; if gone by then, nothing is published.
 *   NOTE: valetudoEvent (informational, event-class only) still fires immediately;
 *     it is not consumed by run guards. All guard-facing attributes (activeAlerts,
 *     pushAlerts, alertCount, alertSeverity, robotError) respect the window.
 *
 * v3.10.2 — AUDIT: ROBOT AVAILABILITY SPLIT FROM BROKER LINK
 *
 *   Found while auditing the v3.10.1 LWT removal. handleHomieState() mapped the
 *   robot's Homie $state onto mqttStatus — the attribute that reports the DRIVER's
 *   broker connection. Different failure domains: a powered-off robot would mark
 *   mqttStatus "disconnected" while the Hubitat session was healthy, which (a)
 *   misreported the link, and (b) flipped mqttUp() false so commands futilely
 *   routed to REST — against a robot that is off — instead of simply queueing the
 *   truth: robot offline. With the driver's own LWT gone, $state is now
 *   authentically the robot's, so it gets its own attribute:
 *     robotAvailability: online | offline   (change-guarded)
 *   RULE MACHINE: to be told when a robot goes offline, trigger on
 *   robotAvailability changed. mqttStatus now means ONLY the driver<->broker link.
 *
 * v3.10.1 — REMOVED SELF-INFLICTED LAST WILL
 *
 *   PROBLEM FIXED (false "robot offline" storms):
 *     The driver connected with a Last Will of retained "lost" on
 *     "<prefix>/<identifier>/$state". That topic is the ROBOT's Homie device state,
 *     owned by Valetudo. So every time the Hubitat MQTT socket dropped, the broker
 *     wrote "lost" into the robot's own state topic; the driver then read its own
 *     will back, logged "Homie $state -> lost", and flagged itself disconnected.
 *     Observed live: ~60 such warnings in one night on a robot that was docked and
 *     healthy the whole time. Because the will was RETAINED it also persisted and
 *     was replayed to other subscribers (e.g. Home Assistant), and it masked real
 *     robot-offline events by making "lost" ambiguous.
 *   FIX: connect with no LWT at all. A subscriber must not publish the device's
 *     state topic; Valetudo already sets the correct will on it.
 *
 * v3.10.0 — CONSUMABLES METADATA FILTER + MQTT SESSION RECONCILIATION
 *
 *   PROBLEM FIXED (consumables polluted with Homie metadata):
 *     parse() only skips topics whose FIRST segment starts with '$', so nested
 *     Homie descriptors fell through into handleConsumable() and were stored as if
 *     they were consumable life values. Observed live on one robot: $name, $type,
 *     $properties, brush-main/$settable, cleaning-wheel/reset/$format and others
 *     stored alongside the 5 real readings — 11 junk keys inflating the attribute
 *     toward the ~1024-char event cap and obscuring the correct key to pass to
 *     resetConsumable(). The second robot was clean, because the retained metadata
 *     is only replayed on some subscribe sequences.
 *   FIX: reject any sub-topic segment beginning with '$', and reset sub-topics
 *     (commands, not readings). Existing polluted maps self-heal: stored metadata
 *     keys are stripped on the next consumable update.
 *
 *   PROBLEM FIXED (stuck MQTT flags / double-subscribe risk):
 *     mqttConnect()'s already-connected early return did not clear mqttConnecting.
 *     A robot was found at mqttConnecting=true / mqttSubscribed=false while
 *     mqttStatus read connected and data was still arriving. That is not cosmetic:
 *     mqttSubscribed=false means "next connect must re-subscribe", so the next
 *     reconnect would subscribe a SECOND time on a session that already had a
 *     subscription — the exact double-delivery behind the v3.7.0 event storm.
 *   FIX: clear the flag on that path and subscribe if the session has none. Added
 *     mqttHealthCheck(), scheduled every 5 minutes from initialize(), which
 *     reconciles the session against its flags: connected-but-unsubscribed ->
 *     subscribe once; not connected -> reconnect; stale connecting flag -> clear.
 *     uninstalled() now unschedules it.
 *
 * v3.9.1 — EMPTY-STRING ATTRIBUTES NEVER CLEARED
 *
 *   PROBLEM FIXED (permanently stale alerts):
 *     Hubitat silently discards sendEvent() when the value is an empty string. The
 *     state variable updates but the ATTRIBUTE keeps its previous value forever.
 *     Observed live: state.lastActiveAlerts / lastPushAlerts / lastRobotError were
 *     all empty while the attributes stayed frozen on "No error" and "Mop Dock Clean
 *     Water Tank empty" — a tank that the robot's own web UI reported as OK. Meanwhile
 *     alertCount (0) and alertSeverity ("none") updated correctly, because a number
 *     and a non-empty string both commit normally. "All clear" was unrepresentable.
 *   FIX: publish ALERT_CLEAR ("none") instead of "" for activeAlerts, pushAlerts,
 *     robotError and dndWindow, via alertOrClear().
 *   REPAIR: initialize() drops the cached state.last* values and forces a flush, so
 *     attributes already stuck on stale text are corrected on the next load. Without
 *     this the change-guard would see state as already-correct and skip the fix.
 *
 *   RULE MACHINE IMPACT: compare these attributes against "none", NOT "". Rules
 *     testing `is not ""` were always true and must be updated.
 *
 * v3.9.0 — HEALTHY-SENTINEL FILTER + VERSION REPORTING
 *
 *   PROBLEM FIXED (phantom permanent alert):
 *     Valetudo publishes StatusStateAttribute/error_description as a NON-EMPTY
 *     string when the robot is fine — on Dreame that is literally "No error".
 *     The status slice treated any non-empty payload as a fault, so a healthy
 *     robot sat permanently at activeAlerts="No error", alertCount=1,
 *     alertSeverity=info, robotError="No error".
 *     Consequences: any Rule Machine guard written as `alertCount > 0` or
 *     `robotError is not ""` was ALWAYS true and would block every automation,
 *     and "all clear" could never be represented.
 *   FIX: NON_ALERT_TEXTS + isNonAlertText() filter both alert sources (the
 *     error_description topic and ErrorStateValetudoEvent messages).
 *
 *   VERSION REPORTING: driverVersion was written only in installed()/updated().
 *     Pasting new code and clicking "Save Driver" calls neither, so devices kept
 *     reporting the version they were created with (observed: 3.0.0 on a hub
 *     running 3.8.0 code). It is now also written in initialize().
 *
 * v3.8.0 — CONTEXT-AWARE BATTERY SEVERITY
 *
 *   "Low battery" is normal behaviour, not a fault, when the robot is doing
 *   something about it. A robot that notices a low battery and drives back to the
 *   dock is working correctly and should never wake anyone. The same message while
 *   the robot is stranded mid-floor IS worth knowing about.
 *
 *   Severity is therefore no longer a pure function of the message text. Battery
 *   alerts are escalated info -> fault only when BOTH hold:
 *     1. robotStatus is not in RECOVERING_STATUSES (i.e. the robot is in error,
 *        paused, idle, or unknown — not cleaning, returning, docking or docked)
 *     2. that condition has persisted for batteryStrandedGraceMin minutes
 *
 *   The grace period exists because status legitimately passes through transient
 *   values during a return-to-dock. Escalating instantly would push on every
 *   momentary pause.
 *
 *   Because context can change AFTER an alert is raised, handleRobotStatus() now
 *   re-triggers the alert flush whenever alerts are active. A robot that gets
 *   stuck on its way home escalates from info to fault without any new MQTT alert
 *   message being published, and de-escalates just as cleanly if it recovers.
 *
 *   This is deliberately battery-only. Other severities remain pure text matches.
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String DRIVER_VERSION = "3.13.0"

// REST circuit breaker tuning. See restPut() / restFailed() / restSucceeded().
@Field static final int REST_FAIL_THRESHOLD      = 3     // consecutive failures before the breaker opens
@Field static final int REST_BREAKER_COOLDOWN_SEC = 900  // 15 min — how long the breaker stays open

// Alert engine. See flushAlerts().
// Debounce window that coalesces a burst of MQTT messages into a single flush.
// Must be long enough to span one Valetudo push (observed < 100ms) but short
// enough that a real fault is reported promptly. 3s is a wide, safe margin.
@Field static final int ALERT_DEBOUNCE_SEC = 3

// Confirmation window for NEW alerts (seconds). A fault must remain continuously
// present for this long before it is published to attributes / Rule Machine.
// Rationale: Dreame robots throw momentary faults during normal driving — observed
// live: "Wheel lost floor contact" for a few seconds while crossing a door
// threshold, which pushed a phone notification and (because it can linger into a
// guard check) blocked scheduled runs. A real fault persists; a threshold bump
// appears and clears within seconds. Clears are NEVER delayed — only raises.
// Overridable per device via the alertConfirmSec preference.
@Field static final int ALERT_CONFIRM_SEC_DEFAULT = 20

// Severity classification. First matching pattern wins, so order matters:
// most specific and most urgent first. Patterns are matched case-insensitively
// against the fault text Valetudo reports.
//   fault  — robot is stuck or broken and cannot continue without you
//   supply — dock or tank needs refilling/emptying; robot may still be running
//   mop    — mop pad attach/detach problems; cleaning quality is affected
//   info   — battery, consumable life, and anything unrecognised. Never pushed.
// NOTE: an unrecognised fault falls through to "info" and will NOT push. If your
// robot reports a fault string not covered here, add it rather than relying on
// the fallback. Check the activeAlerts attribute to see the exact text.
// Valetudo publishes StatusStateAttribute/error_description as a NON-EMPTY sentinel
// string when the robot is healthy — on Dreame firmware this is literally "No error".
// Treating any non-empty payload as a fault made a perfectly healthy robot report
// alertCount=1 / activeAlerts="No error" permanently, which in turn made every
// Rule Machine guard written against alertCount or robotError fire constantly.
// Matched case-insensitively after trimming; extend if other firmware differs.
// Hubitat silently DISCARDS sendEvent() when value is an empty string: the state
// variable updates but the attribute keeps its previous value forever. Observed on a
// live hub — lastActiveAlerts/lastPushAlerts/lastRobotError all went empty while the
// attributes stayed frozen on stale text ("No error", "Mop Dock Clean Water Tank
// empty") long after the conditions cleared, making "all clear" unrepresentable.
// We therefore publish this sentinel instead of "". Rule Machine should compare
// against "none" (which is also easier to enter in RM than an empty string).
@Field static final String ALERT_CLEAR = "none"

// Renders an alert string for publication, substituting the clear sentinel when empty.
private String alertOrClear(String s) { return (s == null || s == "") ? ALERT_CLEAR : s }

@Field static final List NON_ALERT_TEXTS = [
    "no error", "none", "ok", "n/a", "-", "no_error", "noerror"
]

// True when text is absent or one of the healthy sentinels above.
private boolean isNonAlertText(String s) {
    if (!s) return true
    return NON_ALERT_TEXTS.contains(s.trim().toLowerCase())
}

@Field static final List ALERT_RULES = [
    // v3.12.0: must precede the fault rule, which also matches "cannot reach".
    // Valetudo 2026.10.0 maps Dreame code 93 to this text (it was a silent "Unknown
    // error 93" before). Valetudo rates it TRANSIENT/WARNING: still worth a push, but
    // it must not read as a fault that blocks scheduled runs.
    [sev: "supply", pat: "(cannot reach target)"],
    // v3.13.0: dock drainage faults (MOVA V50: "Abnormal dock drainage, check if the
    // drain pipe is clogged") are a dock/plumbing supply problem, not a robot failure.
    // Placed before both fault rules because the text can contain "abnormal"/"blocked".
    [sev: "supply", pat: "(drain)"],
    [sev: "fault",  pat: "(jam|stuck|trapped|tangl|blocked|cliff|drop|lost mop pad|cannot reach|failed to (return|reach)|wheel|bumper|collision|overheat|motor|internal error|no route|no path)"],
    // v3.12.0: hardware failures Valetudo rates CATASTROPHIC ("Gyroscope sensor error",
    // "Fan speed abnormal", "Camera fault", "ToF Sensor offline", "3.3V rail abnormal")
    // matched nothing above and fell through to "info", so they never pushed. Placed
    // after the fault rule and before supply on purpose. "Camera dirty" / "Sensor dirty"
    // are maintenance, not failures, and still fall through to info.
    [sev: "fault",  pat: "(sensor error|abnormal|\\bfault\\b|sensor offline)"],
    [sev: "supply", pat: "(tank|dustbag|dust bag|dust bin|dustbin|bin full|detergent|water|waste|empty|full)"],
    [sev: "mop",    pat: "(mop)"],
    [sev: "info",   pat: "(batter|charg|consumable|filter|brush life)"]
]

// Which severities are allowed to generate a push. Everything else is logged and
// exposed in activeAlerts for dashboards, but stays out of pushAlerts.
@Field static final List PUSH_SEVERITIES = ["fault", "supply", "mop"]

// Ranking used to compute alertSeverity (highest wins).
@Field static final List SEVERITY_RANK = ["none", "info", "mop", "supply", "fault"]

// Battery alerts get context-aware severity — see contextSeverityOf().
@Field static final String BATTERY_PAT = "(batter|low power|needs charg)"

// robotStatus values that mean the robot is handling things itself. A low battery
// in any of these states is expected behaviour and must never generate a push.
// "cleaning" is included deliberately: Valetudo warns before returning, and the
// robot will route itself home when it decides to.
@Field static final List RECOVERING_STATUSES =
    ["cleaning", "returning", "docking", "docked", "moving", "charging"]

// Seconds to wait after subscribing for a retained $state before asking the robot
// directly (see availabilityProbe()). Retained messages normally arrive within 1-2s.
@Field static final int AVAILABILITY_PROBE_SEC = 15

// Allowlists for caller-supplied values that end up in an MQTT topic or a REST path.
// Rule Machine can pass anything; a "/", "+", "#" or ".." must never reach a topic or URL.
@Field static final String CONSUMABLE_KEY_PAT = /^[A-Za-z0-9_-]{1,64}$/
@Field static final String EVENT_ID_PAT       = /^[A-Za-z0-9_-]{1,64}$/
@Field static final List   CARPET_SENSOR_MODES = ["off", "avoid", "lift", "detach"]

// Valetudo dock component type -> driver attribute. Shared by the REST refresh; the
// MQTT parser keeps its explicit cases.
@Field static final Map DOCK_COMPONENT_ATTRS = [
    "dustbag":          "dock_dustbag",
    "water_tank_clean": "dock_freshwater",
    "water_tank_dirty": "dock_wastewater",
    "detergent":        "dock_detergent"
]

// ── v3.13.0 robot discovery ────────────────────────────────────────────────────
// Driver command -> the Valetudo capability it needs. discoverRobot() reads the
// robot's capability list; supports() blocks a command whose capability is absent.
// Commands not listed here (refresh, reconnect, dismissEvent, ...) are never blocked.
@Field static final Map FEATURE_CAPS = [
    "start":                   "BasicControlCapability",
    "stop":                    "BasicControlCapability",
    "pause":                   "BasicControlCapability",
    "dock":                    "BasicControlCapability",
    "locate":                  "LocateCapability",
    "cleanSegment":            "MapSegmentationCapability",
    "cleanZones":              "ZoneCleaningCapability",
    "goToLocation":            "GoToLocationCapability",
    "setFanSpeed":             "FanSpeedControlCapability",
    "setWaterLevel":           "WaterUsageControlCapability",
    "setOperationMode":        "OperationModeControlCapability",
    "triggerAutoEmpty":        "AutoEmptyDockManualTriggerCapability",
    "setKeyLock":              "KeyLockCapability",
    "setCarpetMode":           "CarpetModeControlCapability",
    "setObstacleAvoidance":    "ObstacleAvoidanceControlCapability",
    "setPetObstacleAvoidance": "PetObstacleAvoidanceControlCapability",
    "setCarpetSensorMode":     "CarpetSensorModeControlCapability",
    "setSpeakerVolume":        "SpeakerVolumeControlCapability",
    "resetConsumable":         "ConsumableMonitoringCapability",
    "setQuirk":                "QuirksCapability",
    "setQuirkByTitle":         "QuirksCapability",
    "setFanSpeedTurbo":        "QuirksCapability",
    "refreshQuirks":           "QuirksCapability",
    "mopDockDry":              "MopDockDryManualTriggerCapability",
    "mopDockClean":            "MopDockCleanManualTriggerCapability",
    "setDoNotDisturb":         "DoNotDisturbCapability",
    "setCleanRoute":           "CleanRouteControlCapability",
    "setAutoEmptyInterval":    "AutoEmptyDockAutoEmptyIntervalControlCapability",
    "setMopAutoDrying":        "MopDockMopAutoDryingControlCapability"
]

// Option lists discoverRobot() reads, keyed by the name used in supportedOptions.
// key = the JSON field holding the list (null = the endpoint returns a bare list).
@Field static final Map OPTION_SOURCES = [
    "fanSpeed":          [cap: "FanSpeedControlCapability",      path: "FanSpeedControlCapability/presets",      key: null],
    "waterLevel":        [cap: "WaterUsageControlCapability",    path: "WaterUsageControlCapability/presets",    key: null],
    "operationMode":     [cap: "OperationModeControlCapability", path: "OperationModeControlCapability/presets", key: null],
    "carpetSensorMode":  [cap: "CarpetSensorModeControlCapability", path: "CarpetSensorModeControlCapability/properties", key: "supportedModes"],
    "cleanRoute":        [cap: "CleanRouteControlCapability",    path: "CleanRouteControlCapability/properties", key: "supportedRoutes"],
    "autoEmptyInterval": [cap: "AutoEmptyDockAutoEmptyIntervalControlCapability", path: "AutoEmptyDockAutoEmptyIntervalControlCapability/properties", key: "supportedIntervals"]
]

// Attachments the driver exposes. Ones the robot does not report read NOT_SUPPORTED.
@Field static final Map ATTACHMENT_ATTRS = [
    "mop":       "attachment_mop",
    "dustbin":   "attachment_dustbin",
    "watertank": "attachment_watertank"
]
@Field static final String NOT_SUPPORTED = "not supported"

// Quirk title holding the robot's charge ceiling (MOVA V50: 100%/95%/90%/85%/80%).
@Field static final String QUIRK_TITLE_CHARGE_LIMIT = "Charging Limit"

// Topic filters used when the targetedSubscriptions preference is ON. Exactly the
// topics parse() handles (plus their siblings via "+"), and nothing else — so the
// always-on raw map blobs and Home Assistant state topics never reach the hub.
// "+" matches one level only, so nested Homie descriptors like ".../status/$format"
// are excluded; the few one-level "$name"-style siblings it does match are ignored
// by parse() exactly as they are with "#".
@Field static final List TARGETED_TOPICS = [
    "\$state",
    "StatusStateAttribute/+",
    "BatteryStateAttribute/+",
    "DockStatusStateAttribute/+",
    "DockComponentStateAttribute/+",
    "AttachmentStateAttribute/+",
    "FanSpeedControlCapability/preset",
    "WaterUsageControlCapability/preset",
    "OperationModeControlCapability/preset",
    "KeyLockCapability/enabled",
    "CarpetModeControlCapability/enabled",
    "CarpetSensorModeControlCapability/mode",
    "ObstacleAvoidanceControlCapability/enabled",
    "PetObstacleAvoidanceControlCapability/enabled",
    "SpeakerVolumeControlCapability/value",
    "MapData/segments",
    "MapSegmentationCapability/clean",
    "WifiConfigurationCapability/+",
    "CurrentStatisticsCapability/+",
    "TotalStatisticsCapability/+",
    "ConsumableMonitoringCapability/+",
    "ValetudoEvents/valetudo_events"
]

metadata {
    definition(
        name:      "Valetudo Robot Vacuum",
        namespace: "valetudo-hubitat",   // change to a unique namespace before publishing (e.g. yourGithubUser.valetudo)
        author:    "Hubitat Community",
        importUrl: ""                    // set to the raw GitHub URL of this file to enable in-UI import/updates
    ) {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "Battery"
        capability "Refresh"
        capability "Initialize"

        // Basic control
        // RM usage: these take NO parameters — in Rule Machine add them as a "Custom Action"
        // and just select the command. Their effect shows up in these attributes you can
        // TRIGGER on: switch (on/off), robotStatus (cleaning/returning/docked/...), and for
        // locate there is no attribute (it just plays a sound).
        command "start"     // → switch=on / robotStatus=cleaning
        command "stop"      // → robotStatus=idle (or paused on robots without stop)
        command "pause"     // → robotStatus=paused
        command "dock"      // → switch=off / robotStatus=returning then docked
        command "locate"    // → plays a sound; no attribute change

        // Cleaning
        // NOTE on Rule Machine: when adding these as a "Custom Action", RM asks for a
        // "parameter type" with ONLY three choices: string, number, decimal. There is NO
        // boolean type. Pick the type named in each description and type the value as text.
        command "cleanSegment", [
            [name: "segmentIds*",  type: "STRING", description: "RM type: string. Comma-separated segment IDs from the mapSegments attribute, e.g. 16 or 16,17,18"],
            [name: "iterations",   type: "NUMBER", description: "RM type: number. Cleaning passes (default 1)"],
            [name: "customOrder",  type: "STRING", description: "RM type: string. Type the word true or false (NOT a number). true = clean in listed order (Dreame); false = robot decides. Progress shows in robotStatus/switch; commanded rooms in activeSegments"]
        ]
        command "cleanZones", [
            [name: "zonesJson*", type: "STRING", description: "RM type: string. The JSON 'zones' array copied from Valetudo's zone long-press payload. Progress shows in the robotStatus/switch attributes"],
            [name: "iterations", type: "NUMBER", description: "RM type: number. Cleaning passes (default 1)"]
        ]
        command "goToLocation", [
            [name: "x*", type: "NUMBER", description: "RM type: number. X coordinate in map units (from Valetudo GoTo long-press payload). Progress shows in the robotStatus attribute"],
            [name: "y*", type: "NUMBER", description: "RM type: number. Y coordinate in map units"]
        ]

        // Presets — single string argument each.
        // In Rule Machine custom actions, choose parameter type: string, and type one of
        // the listed values exactly. (The ENUM here only drives the device-page dropdown.)
        // The dropdown lists every value any supported robot uses; the supportedOptions
        // attribute shows which ones THIS robot accepts (others are blocked and logged).
        command "setFanSpeed", [[name: "preset*", type: "ENUM",
            description: "RM type: string. One of: off,min,low,medium,high,turbo,max (model dependent; see supportedOptions). Reflected in the fanSpeed attribute",
            constraints: ["off","min","low","medium","high","turbo","max"]]]
        command "setWaterLevel", [[name: "preset*", type: "ENUM",
            description: "RM type: string. One of: off,min,low,medium,high,max (model dependent; see supportedOptions). Reflected in the waterLevel attribute",
            constraints: ["off","min","low","medium","high","max"]]]
        command "setOperationMode", [[name: "mode*", type: "ENUM",
            description: "RM type: string. One of: vacuum,mop,vacuum_and_mop,vacuum_then_mop (model dependent; see supportedOptions). Reflected in the operationMode attribute",
            constraints: ["vacuum","mop","vacuum_and_mop","vacuum_then_mop"]]]

        // Dock
        command "triggerAutoEmpty"   // no parameter; momentary. No attribute (empties dustbin into dock)

        // Toggles — single string argument each (type the word on or off).
        command "setKeyLock",              [[name: "state*", type: "ENUM", description: "RM type: string. on or off. Reflected in the keyLock attribute", constraints: ["on","off"]]]
        command "setCarpetMode",           [[name: "state*", type: "ENUM", description: "RM type: string. on or off. Reflected in the carpetMode attribute", constraints: ["on","off"]]]
        command "setObstacleAvoidance",    [[name: "state*", type: "ENUM", description: "RM type: string. on or off. Reflected in the obstacleAvoidance attribute", constraints: ["on","off"]]]
        command "setPetObstacleAvoidance", [[name: "state*", type: "ENUM", description: "RM type: string. on or off. Reflected in the petObstacleAvoidance attribute", constraints: ["on","off"]]]
        // What the robot does when it detects carpet (model dependent; X40: off, avoid, lift, detach).
        command "setCarpetSensorMode",     [[name: "mode*", type: "ENUM", description: "RM type: string. One of: off, avoid, lift, detach (model dependent). Reflected in the carpetSensorMode attribute", constraints: ["off","avoid","lift","detach"]]]

        // Sound
        command "setSpeakerVolume", [[name: "volume*", type: "NUMBER", description: "RM type: number. 0-100 (values outside are clamped). Reflected in the speakerVolume attribute"]]

        // Consumables
        command "resetConsumable", [[name: "name*", type: "STRING",
            description: "RM type: string. Consumable key exactly as shown in the consumables attribute (e.g. brush-main, brush-side_right, filter-main, cleaning-sensor). Reflected in the consumables attribute"]]

        // Events
        // Acknowledge (dismiss) a Valetudo event, e.g. an error that stays listed after
        // you fixed the cause. Event ids appear in the robot's web UI and in the
        // ValetudoEvents MQTT topic. Clearing it removes it from activeAlerts.
        command "dismissEvent", [[name: "eventId*", type: "STRING",
            description: "RM type: string. The Valetudo event id (letters, digits, - and _ only), e.g. mop_attachment_reminder or a UUID. Reflected in activeAlerts once Valetudo drops it"]]

        // Quirks (vendor-specific tunables — REST only, no MQTT handle exists in Valetudo)
        // Generic engine: set ANY quirk by its UUID. Get UUIDs + valid values from
        //   GET http://<robotIp>/api/v2/robot/capabilities/QuirksCapability
        // PREFERRED, robot-independent: set a quirk by its Valetudo title — no UUIDs.
        // Run refreshQuirks once first so the driver learns this robot's quirk titles;
        // the discovered titles then appear in the 'quirks' attribute.
        command "setQuirkByTitle", [
            [name: "title*", type: "STRING", description: "RM type: string. The quirk's exact title as shown in Valetudo/the quirks attribute, e.g. Fan Speed Turbo, Carpet Mode Sensitivity, Mop Cleaning Frequency, Charging Limit"],
            [name: "value*", type: "STRING", description: "RM type: string. One of that quirk's option strings, e.g. on/off, low/medium/high, every_15_m2. Invalid values are rejected with the valid list logged. Reflected in the quirks attribute after refreshQuirks"]
        ]
        // Advanced/escape hatch: set a quirk by raw UUID (works without discovery).
        command "setQuirk", [
            [name: "quirkId*", type: "STRING", description: "RM type: string. The quirk's UUID from the QuirksCapability GET response. Reflected in the quirks attribute after refreshQuirks"],
            [name: "value*",   type: "STRING", description: "RM type: string. One of that quirk's exact option strings (case-sensitive, e.g. on/off, low/medium/high)"]
        ]
        // Convenience wrapper for the X40's Fan Speed Turbo quirk (a one-shot: the robot
        // clears it itself after the next cleanup — set it right before dispatching a clean).
        // Robots without the quirk (e.g. MOVA V50) log and skip it once quirks are discovered.
        command "setFanSpeedTurbo", [[name: "state*", type: "ENUM",
            description: "RM type: string. on or off. NOTE: self-clears after one cleanup. Only on robots with a Fan Speed Turbo quirk (X40; not MOVA V50). Reflected in the quirks attribute after refreshQuirks",
            constraints: ["on","off"]]]
        // Read current quirk values into the 'quirks' attribute (JSON {title: value, ...})
        command "refreshQuirks"

        // ── Extra capabilities (REST only — verified against the robot's Swagger) ─────
        // These have no MQTT handle, so they always use REST and therefore honor the
        // "Use REST as command fallback" preference + the circuit breaker like any REST
        // command. Each stateful setter updates its read-back attribute optimistically
        // (to the value you asked for); run refreshSettings to reconcile from the robot.

        // Mop dock: dry the mop pads. Momentary trigger, start or stop. No attribute.
        command "mopDockDry", [[name: "action*", type: "ENUM",
            description: "RM type: string. start or stop. Starts/stops drying the mop pads in the dock",
            constraints: ["start","stop"]]]
        // Mop dock: wash the mop pads. Momentary trigger. Body confirmed against Swagger in 3.12.0.
        command "mopDockClean", [[name: "action*", type: "ENUM",
            description: "RM type: string. start or stop. Starts/stops washing the mop pads in the dock",
            constraints: ["start","stop"]]]
        // Do Not Disturb window.
        command "setDoNotDisturb", [
            [name: "state*",     type: "ENUM",   description: "RM type: string. on or off. Reflected in the dnd attribute", constraints: ["on","off"]],
            [name: "startTime",  type: "STRING", description: "RM type: string. 24-hour HH:MM, e.g. 22:00. Ignored when state is off. Reflected in the dndWindow attribute"],
            [name: "endTime",    type: "STRING", description: "RM type: string. 24-hour HH:MM, e.g. 08:00. Ignored when state is off"]
        ]
        // Clean route / intensity. Valid values vary by robot — see the cleanRoute attribute
        // or GET .../CleanRouteControlCapability/properties (supportedRoutes) for yours.
        command "setCleanRoute", [[name: "route*", type: "STRING",
            description: "RM type: string. One of your robot's supported routes (X40 and V50: normal, quick, intensive, deep; see supportedOptions). Reflected in the cleanRoute attribute"]]
        // Auto-empty dock interval. Valid values vary — see the autoEmptyInterval attribute
        // or GET .../AutoEmptyDockAutoEmptyIntervalControlCapability/properties.
        command "setAutoEmptyInterval", [[name: "interval*", type: "STRING",
            description: "RM type: string. One of your robot's supported intervals (X40 and V50: off, infrequent, normal, frequent; see supportedOptions). Reflected in the autoEmptyInterval attribute"]]
        // Mop dock: automatically dry mops after each cleanup.
        command "setMopAutoDrying", [[name: "state*", type: "ENUM",
            description: "RM type: string. on or off. Reflected in the mopAutoDrying attribute",
            constraints: ["on","off"]]]
        // Read the above settings from the robot into their attributes (best effort).
        command "refreshSettings"

        // Maintenance
        command "refreshSegments"
        command "reconnect"
        // v3.13.0: learn what this robot supports (runs automatically on connect).
        // Results: robotModel, supportedOptions, unsupportedCommands attributes.
        command "discoverRobot"

        // Attributes
        attribute "switch",               "string"
        attribute "battery",              "number"
        attribute "batteryStatus",        "string"
        attribute "robotStatus",          "string"
        attribute "robotStatusFlag",      "string"
        // robotError is retained for backward compatibility with existing rules.
        // It now holds only the single highest-severity active fault, and is
        // written at most once per flush. Prefer activeAlerts for new rules.
        attribute "robotError",           "string"
        attribute "robotErrorDetail",     "string"   // Valetudo structured error "level/kind/subsystem: message" ("none" = clear). Display only
        attribute "activeAlerts",         "string"   // all active faults, "; " joined ("none" = clear)
        attribute "pushAlerts",           "string"   // push-worthy subset of activeAlerts
        attribute "alertCount",           "number"   // count of distinct active faults
        attribute "alertSeverity",        "string"   // fault | supply | mop | info | none
        attribute "valetudoEvent",        "string"   // last Valetudo event class seen
        attribute "dockStatus",           "string"
        attribute "operationMode",        "string"
        attribute "fanSpeed",             "string"
        attribute "waterLevel",           "string"
        attribute "attachment_mop",       "string"
        attribute "attachment_dustbin",   "string"
        attribute "attachment_watertank", "string"
        attribute "keyLock",              "string"
        attribute "carpetMode",           "string"
        attribute "carpetSensorMode",     "string"   // off|avoid|lift|detach
        attribute "obstacleAvoidance",    "string"
        attribute "petObstacleAvoidance", "string"
        attribute "speakerVolume",        "number"
        attribute "mapSegments",          "string"
        attribute "activeSegments",       "string"
        attribute "wifiSSID",             "string"
        attribute "wifiSignal",           "number"
        attribute "wifiFrequency",        "string"
        attribute "currentArea",          "number"
        attribute "currentTime",          "number"
        attribute "totalArea",            "number"
        attribute "totalTime",            "number"
        attribute "totalCount",           "number"
        attribute "dock_dustbag",         "string"
        attribute "dock_freshwater",      "string"
        attribute "dock_wastewater",      "string"
        attribute "dock_detergent",       "string"
        attribute "consumables",          "string"
        attribute "mqttStatus",           "string"
        attribute "robotAvailability",    "string"   // online|offline — the ROBOT's Homie $state, distinct from the driver's broker link
        attribute "restStatus",           "string"
        attribute "quirks",               "string"
        attribute "dnd",                  "string"
        attribute "dndWindow",            "string"
        attribute "cleanRoute",           "string"
        attribute "autoEmptyInterval",    "string"
        attribute "mopAutoDrying",        "string"
        attribute "robotModel",           "string"   // v3.13.0: "<manufacturer> <model>" from discoverRobot
        attribute "supportedOptions",     "string"   // v3.13.0: JSON of the values this robot accepts
        attribute "unsupportedCommands",  "string"   // v3.13.0: driver commands this robot cannot do ("none" = all supported)
        attribute "lastActivity",         "string"
        attribute "driverVersion",        "string"
    }

    preferences {
        input name: "mqttBroker", type: "text",
              title: "<b>MQTT Broker Address</b>",
              description: "Use the hub's LAN IP, NOT 127.0.0.1 (the built-in broker authenticates on the LAN interface). e.g. tcp://192.168.1.10:1883. TLS (ssl://IP:8883) is supported by Hubitat but UNTESTED here with self-signed broker certs.",
              required: true, defaultValue: ""
        input name: "mqttUsername", type: "text",
              title: "<b>MQTT Username</b>", description: "Blank if none", required: false
        input name: "mqttPassword", type: "password",
              title: "<b>MQTT Password</b>", description: "Blank if none", required: false
        input name: "topicPrefix", type: "text",
              title: "<b>MQTT Topic Prefix</b>",
              description: "Match Valetudo MQTT settings (default: valetudo)",
              required: true, defaultValue: "valetudo"
        input name: "robotIdentifier", type: "text",
              title: "<b>Robot Identifier</b>",
              description: "Match Valetudo MQTT settings Identifier (case-sensitive)",
              required: true

        input name: "robotIp", type: "text",
              title: "<b>Robot IP Address (optional, enables REST)</b>",
              description: "e.g. 192.168.1.50 — use a DHCP reservation/static IP. Enables refresh(), command fallback, and robot discovery (what this model supports).",
              required: false
        input name: "restFallback", type: "bool",
              title: "<b>Use REST as command fallback when MQTT is down</b>",
              description: "Requires Robot IP set. Uncheck this during planned robot downtime (e.g. powered off for repair) to stop REST command attempts and their error logging entirely.",
              defaultValue: true
        input name: "enforceSupported", type: "bool",
              title: "<b>Block commands this robot does not support</b>",
              description: "Uses what discoverRobot learned (needs Robot IP). On: unsupported commands and values are logged with the valid list and not sent, so they never count toward the REST breaker. Off: everything is sent as-is, exactly like 3.12.0. All commands and settings stay available either way.",
              defaultValue: true
        input name: "turboQuirkTitle", type: "text",
              title: "<b>Fan Speed Turbo quirk title (advanced)</b>",
              description: "Only for robots with a Fan Speed Turbo quirk (X40). Not present on MOVA V50: leave blank. Only needed if a firmware renames this quirk; setFanSpeedTurbo looks it up by this title after refreshQuirks has run. Default: Fan Speed Turbo",
              required: false
        input name: "turboQuirkId", type: "text",
              title: "<b>Fan Speed Turbo quirk UUID (advanced fallback)</b>",
              description: "Only for robots with a Fan Speed Turbo quirk (X40). Rarely needed: used only if the quirk cannot be found by title. If set, it is always sent, even on a robot whose discovered quirks do not include it. Leave blank in almost all cases.",
              required: false

        input name: "autoReconnect", type: "bool",
              title: "<b>Auto-reconnect MQTT on disconnect</b>", defaultValue: true
        input name: "reconnectDelay", type: "number",
              title: "<b>Reconnect delay (seconds)</b>", defaultValue: 30, range: "5..300"
        input name: "targetedSubscriptions", type: "bool",
              title: "<b>Targeted MQTT subscriptions (reduce hub traffic)</b>",
              description: "Subscribe only to the topics this driver uses instead of everything under the robot. Keeps Valetudo's raw map data (~50 KB per map update while cleaning) and Home Assistant topics off the hub. Off = previous behaviour. Saving reconnects MQTT.",
              defaultValue: false

        input name: "batteryStrandedGraceMin", type: "number",
              title: "<b>Low-battery grace period (minutes)</b>",
              description: "How long the robot must sit in a non-recovering state (error, paused, idle) with a low battery before that counts as a real fault. Below this, low battery is treated as informational and never pushed.",
              defaultValue: 10, range: "1..120"

        input name: "alertConfirmSec", type: "number",
              title: "<b>Alert confirmation window (seconds)</b>",
              description: "A new fault must persist this long before it is reported. Filters momentary errors like wheel-contact blips at door thresholds. 0 = report immediately.",
              defaultValue: 20, range: "0..300"

        input name: "logEnable", type: "bool",
              title: "<b>Enable debug logging</b>", description: "Auto-off after 30 min", defaultValue: true
        input name: "txtEnable", type: "bool",
              title: "<b>Enable info logging</b>", defaultValue: true
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// LIFECYCLE
// ═══════════════════════════════════════════════════════════════════════════════

def installed() {
    log.info "${device.displayName}: Installed driver v${DRIVER_VERSION}"
    sendEvent(name: "driverVersion", value: DRIVER_VERSION, isStateChange: false)
    sendEvent(name: "switch",          value: "off",            isStateChange: false)
    sendEvent(name: "robotStatus",     value: "unknown",        isStateChange: false)
    sendEvent(name: "robotStatusFlag", value: "none",           isStateChange: false)
    sendEvent(name: "robotError",      value: ALERT_CLEAR,               isStateChange: false)
    sendEvent(name: "activeAlerts",    value: ALERT_CLEAR,               isStateChange: false)
    sendEvent(name: "pushAlerts",      value: ALERT_CLEAR,               isStateChange: false)
    sendEvent(name: "alertCount",      value: 0,                isStateChange: false)
    sendEvent(name: "alertSeverity",   value: "none",           isStateChange: false)
    sendEvent(name: "mqttStatus",      value: "not configured", isStateChange: false)
    sendEvent(name: "restStatus",      value: "not configured", isStateChange: false)
    initialize()
}

def updated() {
    log.info "${device.displayName}: Preferences updated"
    sendEvent(name: "driverVersion", value: DRIVER_VERSION, isStateChange: false)
    if (logEnable) runIn(1800, "disableDebugLogging")
    if (robotIp?.trim()) sendEvent(name: "restStatus", value: "ok", isStateChange: false)
    else sendEvent(name: "restStatus", value: "not configured", isStateChange: false)
    initialize()
}

def initialize() {
    logDebug "initialize()"
    // Report the running code version here as well as in installed()/updated().
    // Pasting new driver code and clicking "Save Driver" does NOT invoke updated(),
    // so a version written only there goes stale and misreports what is running.
    // initialize() is also called by the Initialize capability on hub restart.
    sendEvent(name: "driverVersion", value: DRIVER_VERSION, isStateChange: true)

    // Repair pass. flushAlerts() compares against state.last*, so if an attribute is
    // stuck on a stale value while its state var is already correct, the guard would
    // skip the corrective write and the attribute would never recover. Clearing the
    // cached values here forces the next flush to republish all four unconditionally.
    state.remove("lastActiveAlerts")
    state.remove("alertFirstSeen")   // confirmation-window timestamps: rebuilt from live topics
    state.remove("lastPushAlerts")
    state.remove("lastAlertSeverity")
    state.remove("lastRobotError")
    runIn(ALERT_DEBOUNCE_SEC, "flushAlerts")

    unschedule("mqttConnect")
    state.mqttSubscribed = false
    state.mqttConnecting = false
    // Reconcile the MQTT session against its flags every 5 minutes. runEvery5Minutes
    // replaces any existing schedule for the same handler, so repeated initialize()
    // calls cannot stack duplicate health checks.
    runEvery5Minutes("mqttHealthCheck")
    try { interfaces.mqtt.disconnect() } catch (e) { }

    if (!settings.mqttBroker?.trim() || !settings.robotIdentifier?.trim()) {
        log.warn "${device.displayName}: Broker or identifier not configured — skipping MQTT connect"
        setMqttStatus("not configured")
        return
    }
    mqttConnect()
}

def uninstalled() {
    log.info "${device.displayName}: Uninstalled"
    // Stop the reconciliation loop before tearing the session down, otherwise it
    // would keep firing (and keep trying to reconnect) after the device is gone.
    unschedule("mqttHealthCheck")
    unschedule("mqttConnect")
    unschedule("availabilityProbe")
    try { interfaces.mqtt.disconnect() } catch (e) { }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MQTT CONNECTION
// ═══════════════════════════════════════════════════════════════════════════════

// Periodic reconciliation of the MQTT session against its flags.
//
// The connect path is event-driven (connect -> runIn -> subscribe) and every step
// can be lost: a runIn can be replaced by a later schedule with the same handler
// name, a hub reboot can land mid-sequence, and mqttClientStatus can report
// "connected" without the subscribe having completed. When that happens the driver
// looks healthy (mqttStatus connected, data still arriving from a broker-retained
// session) while mqttSubscribed is false — and the NEXT reconnect then subscribes a
// second time on the same session, double-delivering every message.
//
// This runs every 5 minutes and fixes the three reachable bad states:
//   connected + not subscribed  -> subscribe once
//   not connected               -> reconnect
//   connecting flag stale       -> clear it so connects are not blocked
def mqttHealthCheck() {
    boolean connected = false
    try { connected = interfaces.mqtt.isConnected() } catch (e) { connected = false }

    // A connect attempt older than 60s has either finished or failed; the flag is stale.
    if (state.mqttConnecting && (now() - (state.mqttConnectingAt ?: 0L)) > 60000L) {
        logDebug "Clearing stale mqttConnecting flag"
        state.mqttConnecting = false
    }

    if (connected) {
        if (!state.mqttSubscribed) {
            log.warn "${device.displayName}: health check — connected but not subscribed; subscribing"
            mqttSubscribeAll()
        }
        return
    }

    // Not connected. Ensure status reflects that, then reconnect if configured.
    setMqttStatus("disconnected")
    if (settings.mqttBroker?.trim() && settings.robotIdentifier?.trim()) {
        logDebug "Health check — not connected; reconnecting"
        state.mqttSubscribed = false
        mqttConnect()
    }
}

def mqttConnect() {
    def clientId = "hubitat-valetudo-${device.id}"

    // Guard against overlapping connects. Without this, a reconnect racing an
    // initialize() could open a second session and — worse — a second wildcard
    // subscription, causing every inbound message to be delivered more than once.
    try {
        if (interfaces.mqtt.isConnected()) {
            logDebug "mqttConnect() called while already connected — ignoring"
            // Previously this returned WITHOUT clearing mqttConnecting, so the flag
            // could stay true indefinitely. Observed live: a robot sat at
            // mqttConnecting=true / mqttSubscribed=false while mqttStatus said
            // connected. That combination is dangerous, not just untidy — the next
            // reconnect sees "not subscribed" and subscribes again on a session that
            // may already hold a subscription, which is exactly the double-delivery
            // that produced the v3.7.0 event storm.
            state.mqttConnecting = false
            // Reconcile: connected but never subscribed means the post-connect
            // subscribe was lost (e.g. its runIn was replaced by another schedule).
            if (!state.mqttSubscribed) {
                log.warn "${device.displayName}: connected but not subscribed — subscribing now"
                mqttSubscribeAll()
            }
            return
        }
    } catch (e) { /* isConnected() can throw before first connect; treat as not connected */ }

    if (state.mqttConnecting && (now() - (state.mqttConnectingAt ?: 0L)) < 30000L) {
        logDebug "Connect already in progress — ignoring duplicate call"
        return
    }
    state.mqttConnecting   = true
    state.mqttConnectingAt = now()
    state.mqttSubscribed   = false

    try {
        logDebug "Connecting to ${settings.mqttBroker} as ${clientId}"
        // NO Last Will. Earlier versions set an LWT of retained "lost" on
        // "<prefix>/<identifier>/$state" — but that topic is the ROBOT's Homie device
        // state, owned and published by Valetudo. Using it as the DRIVER's will meant
        // that whenever the Hubitat connection dropped, the broker wrote a retained
        // "lost" into the robot's own state topic. Three consequences, all observed:
        //   1. The driver read its own will back and logged "Homie $state → lost",
        //      then set mqttStatus=disconnected — a self-inflicted false offline.
        //   2. Because the will was RETAINED, the bogus value persisted and was
        //      replayed to every subscriber on connect, including Home Assistant.
        //   3. It masked genuine robot-offline events, since "lost" could now mean
        //      either the robot went away or Hubitat's own socket blipped.
        // A subscriber has no business publishing the device's state topic. Valetudo
        // sets its own LWT on that topic already, which is the correct owner.
        interfaces.mqtt.connect(
            settings.mqttBroker, clientId,
            settings.mqttUsername ?: null, settings.mqttPassword ?: null
        )
        runIn(2, "mqttSubscribeAll")
    } catch (e) {
        state.mqttConnecting = false
        log.error "${device.displayName}: MQTT connect failed — ${e.message}"
        setMqttStatus("error")
        scheduleReconnect()
    }
}

def mqttSubscribeAll() {
    def base = baseTopic()

    // Hubitat does NOT de-duplicate MQTT subscriptions. Subscribing twice on the
    // same session delivers every message twice. Subscribe at most once per session;
    // the flag is reset in mqttConnect() and on disconnect.
    if (state.mqttSubscribed) {
        logDebug "Already subscribed on this session — skipping"
        return
    }

    boolean targeted = (settings.targetedSubscriptions == true)
    try {
        if (targeted) {
            // One filter per handled topic. If any subscribe throws part-way, the
            // session already holds the earlier ones; a retry on the SAME session would
            // subscribe them twice (Hubitat does not de-duplicate — the v3.7.0 storm).
            // So a failure here drops the session (cleanSession defaults to true) and
            // the reconnect starts from zero subscriptions. See the catch below.
            TARGETED_TOPICS.each { t -> interfaces.mqtt.subscribe("${base}/${t}", 1) }
            logInfo "Subscribed to ${TARGETED_TOPICS.size()} targeted topics under ${base}/"
        } else {
            // Single wildcard subscription — simpler and future-proof.
            // Valetudo only publishes topics for capabilities the robot actually has.
            interfaces.mqtt.subscribe("${base}/#", 1)
            logInfo "Subscribed to ${base}/#"
        }
        state.mqttSubscribed = true
        state.mqttConnecting = false
        setMqttStatus("connected")
        // Robot availability: a retained $state should arrive within a second or two.
        // If it has not by AVAILABILITY_PROBE_SEC, availabilityProbe() asks the robot.
        state.homieStateSeen = false
        if (robotIp?.trim()) runIn(AVAILABILITY_PROBE_SEC, "availabilityProbe")
        // Pull a fresh REST snapshot on (re)connect if REST is configured.
        // v3.13.0: discoverRobot runs first so the later pulls can skip what this
        // robot does not have.
        if (robotIp?.trim()) {
            runIn(3, "discoverRobot"); runIn(6, "refresh"); runIn(8, "refreshSegments")
            runIn(10, "refreshQuirks"); runIn(12, "refreshSettings")
        }
    } catch (e) {
        state.mqttConnecting = false
        log.error "${device.displayName}: Subscribe failed — ${e.message}"
        if (targeted) {
            // Drop the half-subscribed session so the retry cannot double-subscribe.
            state.mqttSubscribed = false
            try { interfaces.mqtt.disconnect() } catch (ignored) { }
        }
        setMqttStatus("error")
        scheduleReconnect()
    }
}

// Sets robotAvailability when no retained $state arrived after subscribing.
//
// Valetudo publishes $state retained (with a retained "lost" Last Will), so a new
// subscriber normally learns the robot's presence immediately. On the Hubitat
// built-in broker that replay was observed NOT to happen (both robots, 2026-10-31;
// cause unverified), leaving robotAvailability unset until the robot next changed
// state. GET /api/v2/mqtt/status reports the same Homie state Valetudo publishes.
//
// Deliberately quiet: it bypasses restFailed() so it can never open the breaker or
// log warnings, and a FAILED probe changes nothing — a network blip must not raise
// a false "offline" (which could fire a Rule Machine notification). Only an answer
// from the robot itself sets the attribute.
def availabilityProbe() {
    if (state.homieStateSeen) return
    if (restBlocked("GET mqtt/status", false)) return
    def host = restHost()
    if (!host) return
    def url = "http://${host}/api/v2/mqtt/status"
    String robotState = null
    try {
        httpGet([uri: url, timeout: 10]) { resp -> robotState = resp?.data?.state as String }
    } catch (e) {
        logDebug "availabilityProbe: no answer (${e.message}); robotAvailability left unchanged"
        return
    }
    if (state.homieStateSeen) return   // a live $state won the race; it is authoritative
    if (robotState) {
        logDebug "availabilityProbe: robot MQTT state = ${robotState}"
        handleHomieState(robotState)
    }
}

// mqttStatus was previously written unconditionally from two code paths, which on a
// live hub produced nine "connected" events in eight seconds. Change-guarded now.
private void setMqttStatus(String value) {
    if (state.lastMqttStatus == value) return
    state.lastMqttStatus = value
    sendEvent(name: "mqttStatus", value: value, isStateChange: true)
}

def mqttClientStatus(String message) {
    logDebug "mqttClientStatus: ${message}"
    if (message.startsWith("Error") || message.contains("Connection lost") || message.contains("disconnect")) {
        log.warn "${device.displayName}: MQTT disconnected — ${message}"
        state.mqttSubscribed = false   // next connect must re-subscribe
        state.mqttConnecting = false
        setMqttStatus("disconnected")
        scheduleReconnect()
    } else if (message.contains("connected")) {
        setMqttStatus("connected")
    }
}

def scheduleReconnect() {
    if (settings.autoReconnect) {
        def delay = (settings.reconnectDelay ?: 30).toInteger()
        logInfo "Reconnect in ${delay}s"
        runIn(delay, "mqttConnect")
    }
}

def reconnect() {
    logInfo "Manual reconnect"
    unschedule("mqttConnect")
    mqttConnect()
}

// ═══════════════════════════════════════════════════════════════════════════════
// INCOMING MQTT MESSAGE PARSER
// ═══════════════════════════════════════════════════════════════════════════════

def parse(String description) {
    def msg = interfaces.mqtt.parseMessage(description)
    if (!msg) return
    def topic   = msg.topic
    def payload = msg.payload?.trim() ?: ""

    def base = baseTopic()
    if (!topic.startsWith(base + "/")) return
    def sub = topic.substring(base.length() + 1)   // strip "prefix/identifier/"

    // Skip the large, high-frequency raw map payloads entirely — we don't use them,
    // and logging/timestamping them on every publish wastes CPU and event-DB writes.
    if (sub == "MapData/map-data" || sub == "MapData/map-data-hass") return

    logDebug "IN  ${topic} = ${payload}"

    // Timestamp only meaningful inbound traffic (isStateChange:false = no RM trigger).
    sendEvent(name: "lastActivity",
        value: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")),
        isStateChange: false)

    // Ignore Homie metadata topics ($state handled below, others skipped)
    if (sub == "\$state") { state.homieStateSeen = true; handleHomieState(payload); return }
    if (sub.startsWith("\$")) return
    // Ignore command response echoes (".../set" never published back, but be safe)
    if (sub.endsWith("/set")) return

    switch (true) {
        case sub == "StatusStateAttribute/status":
            handleRobotStatus(payload); break
        case sub == "StatusStateAttribute/flag":
            sendEvent(name: "robotStatusFlag", value: payload ?: "none", isStateChange: true); break
        case sub == "StatusStateAttribute/error_description":
            // Feeds the "status" slice only. Does NOT write robotError directly —
            // see the ALERT ENGINE section for why that caused an event storm.
            // isNonAlertText() filters the healthy sentinel ("No error" on Dreame),
            // which is a NON-EMPTY payload and would otherwise register as a fault.
            setAlertSlice("status", isNonAlertText(payload) ? [:] : [(payload): payload])
            break
        case sub == "StatusStateAttribute/error":
            // Structured twin of error_description. Display only: it deliberately does
            // NOT feed an alert slice, so the alert engine's behaviour is unchanged.
            handleRobotErrorDetail(payload); break
        case sub == "BatteryStateAttribute/level":
            sendIntEvent("battery", payload, "%")
            // v3.13.0: remember the level for the Charging Limit check (currentValue lags).
            try { state.lastBatteryLevel = Double.valueOf(payload).intValue() } catch (e) { }
            publishBatteryStatus()
            break
        case sub == "BatteryStateAttribute/status":
            setBatteryFlag(payload); break
        case sub == "DockStatusStateAttribute/status":
            sendEvent(name: "dockStatus", value: payload ?: "unknown", isStateChange: true); break
        case sub == "FanSpeedControlCapability/preset":
            sendEvent(name: "fanSpeed", value: payload, isStateChange: true); break
        case sub == "WaterUsageControlCapability/preset":
            sendEvent(name: "waterLevel", value: payload, isStateChange: true); break
        case sub == "OperationModeControlCapability/preset":
            sendEvent(name: "operationMode", value: payload, isStateChange: true); break
        case sub == "AttachmentStateAttribute/mop":
            sendAttach("attachment_mop", payload); break
        case sub == "AttachmentStateAttribute/dustbin":
            sendAttach("attachment_dustbin", payload); break
        case sub == "AttachmentStateAttribute/watertank":
            sendAttach("attachment_watertank", payload); break
        case sub == "KeyLockCapability/enabled":
            sendEvent(name: "keyLock", value: payload, isStateChange: true); break
        case sub == "CarpetModeControlCapability/enabled":
            sendEvent(name: "carpetMode", value: payload, isStateChange: true); break
        case sub == "CarpetSensorModeControlCapability/mode":
            if (payload) sendEvent(name: "carpetSensorMode", value: payload, isStateChange: true); break
        case sub == "ObstacleAvoidanceControlCapability/enabled":
            sendEvent(name: "obstacleAvoidance", value: payload, isStateChange: true); break
        case sub == "PetObstacleAvoidanceControlCapability/enabled":
            sendEvent(name: "petObstacleAvoidance", value: payload, isStateChange: true); break
        case sub == "SpeakerVolumeControlCapability/value":
            sendIntEvent("speakerVolume", payload, null); break
        case sub == "MapData/segments":
            if (payload) { sendEvent(name: "mapSegments", value: payload, isStateChange: true); logInfo "Segments → ${payload}" }; break
        case sub == "MapSegmentationCapability/clean":
            handleActiveSegments(payload); break
        case sub == "WifiConfigurationCapability/ssid":
            sendEvent(name: "wifiSSID", value: payload, isStateChange: true); break
        case sub == "WifiConfigurationCapability/signal":
            sendIntEvent("wifiSignal", payload, "dBm"); break
        case sub == "WifiConfigurationCapability/frequency":
            sendEvent(name: "wifiFrequency", value: payload, isStateChange: true); break
        case sub == "CurrentStatisticsCapability/area":
            sendIntEvent("currentArea", payload, "cm2"); break
        case sub == "CurrentStatisticsCapability/time":
            sendIntEvent("currentTime", payload, "s"); break
        case sub == "TotalStatisticsCapability/area":
            sendIntEvent("totalArea", payload, "cm2"); break
        case sub == "TotalStatisticsCapability/time":
            sendIntEvent("totalTime", payload, "s"); break
        case sub == "TotalStatisticsCapability/count":
            sendIntEvent("totalCount", payload, null); break
        case sub == "DockComponentStateAttribute/dustbag":
            sendEvent(name: "dock_dustbag", value: payload, isStateChange: true); break
        case sub == "DockComponentStateAttribute/water_tank_clean":
            sendEvent(name: "dock_freshwater", value: payload, isStateChange: true); break
        case sub == "DockComponentStateAttribute/water_tank_dirty":
            sendEvent(name: "dock_wastewater", value: payload, isStateChange: true); break
        case sub == "DockComponentStateAttribute/detergent":
            sendEvent(name: "dock_detergent", value: payload, isStateChange: true); break
        case sub == "ValetudoEvents/valetudo_events":
            handleValetudoEvents(payload); break
        case sub.startsWith("ConsumableMonitoringCapability/") && !sub.endsWith("/reset"):
            handleConsumable(sub, payload); break
        default:
            logDebug "Unhandled sub-topic: ${sub} = ${payload}"; break
    }
}

// ── State handler helpers ──────────────────────────────────────────────────────

// NOTE: this reports the ROBOT's Homie availability, which is not the same thing as
// the hub's connection to the broker — but both are surfaced through mqttStatus for
// backward compatibility with existing rules. Routed through setMqttStatus() so a
// retained $state republished on every reconnect no longer generates an event.
// Homie $state is the ROBOT's availability, published by Valetudo (including via its
// own LWT when the robot drops off the broker). Since v3.10.1 the driver no longer
// publishes to this topic, so its value is authentically the robot's.
//
// AUDIT FIX: this handler previously mapped robot-offline onto mqttStatus —
// the attribute that reports the DRIVER's broker link. Those are different failure
// domains: the robot can power off while Hubitat's broker session is perfectly
// healthy, and marking mqttStatus "disconnected" in that case both lied about the
// driver link and (worse) made mqttUp() route commands to REST while MQTT was fine.
// The robot's availability now gets its own attribute.
private void handleHomieState(String payload) {
    if (payload in ["ready","init"]) {
        setRobotAvailability("online")
    } else if (payload in ["lost","disconnected","sleeping"]) {
        setRobotAvailability("offline")
        log.warn "${device.displayName}: Robot reports Homie \$state → ${payload} (robot offline; Hubitat broker link unaffected)"
    }
}

// Change-guarded like setMqttStatus, for the same reason: retained $state is
// republished on every reconnect and must not spam events.
private void setRobotAvailability(String value) {
    if (state.lastRobotAvailability == value) return
    state.lastRobotAvailability = value
    sendEvent(name: "robotAvailability", value: value, isStateChange: true)
}

// StatusStateAttribute/error carries Valetudo's own classification of the current
// error, e.g. {"severity":{"kind":"transient","level":"warning"},"subsystem":
// "navigation","message":"Cannot reach target"}. Healthy = kind "none", empty message.
// Rendered to one non-empty string (Hubitat drops "", see 3.9.1) and change-guarded
// in state, because the topic is retained and republished on every reconnect.
private void handleRobotErrorDetail(String payload) {
    String detail = ALERT_CLEAR
    try {
        def e = payload ? new JsonSlurper().parseText(payload) : null
        if (e instanceof Map) {
            String kind  = (e.severity?.kind  ?: "none") as String
            String level = (e.severity?.level ?: "none") as String
            String sys   = (e.subsystem ?: "none") as String
            String msg   = (e.message ?: "").toString().trim()
            if (!(kind == "none" && level == "none" && !msg)) {
                detail = "${level}/${kind}/${sys}" + (msg ? ": ${msg}" : "")
            }
        }
    } catch (ex) {
        logDebug "Cannot parse StatusStateAttribute/error: ${payload}"
        return
    }
    if (detail.length() > 250) detail = detail.substring(0, 250)
    if (state.lastRobotErrorDetail == detail) return
    state.lastRobotErrorDetail = detail
    sendEvent(name: "robotErrorDetail", value: detail, isStateChange: true)
}

private void handleRobotStatus(String payload) {
    sendEvent(name: "robotStatus", value: payload ?: "unknown", isStateChange: true)
    def sw = (payload in ["cleaning","manual_control","moving"]) ? "on" : "off"
    sendEvent(name: "switch", value: sw, isStateChange: true)
    logInfo "Status → ${payload} (switch: ${sw})"
    // v3.13.0: the Charging Limit check depends on being docked; currentValue lags.
    state.lastRobotStatus = payload ?: "unknown"
    publishBatteryStatus()

    // Battery severity depends on robotStatus, so a status change can escalate or
    // de-escalate an already-raised alert with no new alert message arriving.
    // Debounced and gated on there being something to re-evaluate, so this is cheap.
    if (state.alertSlices?.any { k, v -> v }) runIn(ALERT_DEBOUNCE_SEC, "flushAlerts")
}

// ── v3.13.0 battery status with Charging Limit awareness ───────────────────────
// Valetudo reports the battery flag (none|charging|discharging|charged). A robot with
// a Charging Limit quirk (MOVA V50) stops at the limit and reports "none", so
// "charged" never appears. When docked at or above the limit, report "charged".
private void setBatteryFlag(String flag) {
    state.rawBatteryFlag = flag ?: "none"
    publishBatteryStatus()
}

private void publishBatteryStatus() {
    String flag = (state.rawBatteryFlag ?: null) as String
    if (flag == null) return   // nothing reported yet
    String out = flag
    Integer limit = chargeLimitPct()
    Integer level = (state.lastBatteryLevel != null) ? (state.lastBatteryLevel as Integer) : null
    if (flag == "none" && limit != null && level != null && level >= limit &&
            state.lastRobotStatus == "docked") {
        out = "charged"
    }
    if (state.lastBatteryStatus == out) return
    state.lastBatteryStatus = out
    sendEvent(name: "batteryStatus", value: out, isStateChange: true)
}

// The robot's Charging Limit in percent, from the refreshQuirks cache; null if none.
private Integer chargeLimitPct() {
    def vals = state.quirkValues
    if (!(vals instanceof Map)) return null
    def hit = vals.find { k, v -> (k as String)?.trim()?.equalsIgnoreCase(QUIRK_TITLE_CHARGE_LIMIT) }
    if (!hit) return null
    def m = ((hit.value ?: "") as String) =~ /(\d{2,3})/
    return m.find() ? (m.group(1) as Integer) : null
}

private void handleActiveSegments(String payload) {
    if (!payload) return
    try {
        def p = new JsonSlurper().parseText(payload)
        def ids = p?.segment_ids
        sendEvent(name: "activeSegments", value: ids ? JsonOutput.toJson(ids) : "[]", isStateChange: true)
    } catch (e) { sendEvent(name: "activeSegments", value: payload, isStateChange: true) }
}

private void handleConsumable(String sub, String payload) {
    def name = sub.replace("ConsumableMonitoringCapability/", "")

    // Reject Homie metadata rather than storing it as a consumable reading.
    // Valetudo publishes retained Homie descriptors alongside the real values —
    // $name, $type, $properties at the capability level, and per-property
    // descriptors like "brush-main/$settable", "cleaning-wheel/reset/$format".
    // parse() only skips topics whose FIRST segment starts with '$', so these
    // nested ones fell through and were stored as if they were consumable life
    // values. Observed live: one robot's consumables attribute held 11 metadata
    // keys alongside 5 real ones, inflating it toward the ~1024-char event cap
    // and making the correct key for resetConsumable() hard to identify.
    if (name.tokenize("/").any { it.startsWith("\$") }) {
        logDebug "Ignoring Homie metadata topic: ${sub}"
        return
    }
    // Reset sub-topics are commands, not readings.
    if (name.endsWith("/reset") || name.contains("/reset/")) {
        logDebug "Ignoring consumable reset topic: ${sub}"
        return
    }

    def map = [:]
    try {
        def existing = device.currentValue("consumables")
        if (existing) map = new JsonSlurper().parseText(existing)
    } catch (e) { map = [:] }
    // Drop any metadata keys stored by earlier driver versions, so an already
    // polluted attribute self-heals on the next consumable update.
    map = map.findAll { k, v ->
        !(k.toString().tokenize("/").any { it.startsWith("\$") }) &&
        !k.toString().contains("/reset")
    }
    map[name] = payload
    def json = JsonOutput.toJson(map)
    // Hubitat attribute values are capped (~1024 chars) in the events DB. If a robot
    // reports an unusually large set of consumables, keep only this single update
    // rather than silently truncating a merged blob the user can't parse.
    if (json.length() > 1000) {
        json = JsonOutput.toJson([(name): payload])
        log.warn "${device.displayName}: consumables map exceeded size limit; storing latest key only"
    }
    sendEvent(name: "consumables", value: json, isStateChange: true)
    logInfo "Consumable ${name} → ${payload}"
}

// Valetudo republishes the FULL current event set on this retained topic, and
// republishes it on every reconnect. It is therefore authoritative: whatever is
// absent from this payload is no longer active. We rebuild the "events" slice
// from scratch each time rather than accumulating, which is what makes clearing
// work correctly. Events already marked processed are treated as resolved.
private void handleValetudoEvents(String payload) {
    if (!payload) return
    def events
    try {
        events = new JsonSlurper().parseText(payload)
    } catch (e) {
        logDebug "Cannot parse ValetudoEvents: ${payload}"
        return
    }
    if (!(events instanceof Map)) return

    Map slice   = [:]
    String last = null

    events.each { id, evt ->
        if (!evt) return
        def cls = evt.__class ?: "UnknownEvent"
        def msg = (evt.message ?: "").toString().trim()
        if (evt.processed) return

        last = cls
        // Key by event id so two faults with identical text stay distinct, and so
        // an id vanishing from the payload cleanly removes it from the slice.
        switch (cls) {
            case "ErrorStateValetudoEvent":
                // Same sentinel guard as the status slice: an ErrorStateValetudoEvent
                // can carry healthy text (e.g. "No error") and must not become a fault.
                if (!isNonAlertText(msg)) slice[id.toString()] = msg
                break
            case "DustBinFullValetudoEvent":
                slice[id.toString()] = "Dust bin full"
                break
            case "MopAttachmentReminderValetudoEvent":
                slice[id.toString()] = "Mop attachment reminder"
                break
            case "ConsumableDepletedValetudoEvent":
                slice[id.toString()] = "Consumable depleted${msg ? ': ' + msg : ''}"
                break
            default:
                // Informational events (e.g. ValetudoUpdatedValetudoEvent) are surfaced
                // via the valetudoEvent attribute but are not treated as active faults.
                break
        }
    }

    // Change-guarded so a republished identical event set does not re-fire this.
    if (last && state.lastValetudoEvent != last) {
        state.lastValetudoEvent = last
        sendEvent(name: "valetudoEvent", value: last, isStateChange: true)
    }

    setAlertSlice("events", slice)
}

// ═══════════════════════════════════════════════════════════════════════════════
// ALERT ENGINE
// ═══════════════════════════════════════════════════════════════════════════════
//
// Multiple MQTT topics report faults independently and can disagree with each
// other transiently. Each source owns a named "slice" of state.alertSlices; the
// merged union of all slices is the robot's true fault set.
//
// Writing an attribute per inbound message is what produced the v3.6.0 event
// storm, so setAlertSlice() only stages data and (re)schedules a single flush.
// Calling runIn() again with the same handler name CANCELS the pending one, so a
// burst of N messages produces exactly one flushAlerts() call.

private void setAlertSlice(String source, Map slice) {
    if (state.alertSlices == null) state.alertSlices = [:]
    def existing = state.alertSlices[source] ?: [:]
    // Skip the reschedule if this source's contribution is byte-identical. Saves
    // needless flushes when Valetudo republishes an unchanged retained payload.
    if (existing == slice) return
    state.alertSlices[source] = slice
    runIn(ALERT_DEBOUNCE_SEC, "flushAlerts")
}

// Public only because Hubitat's runIn() requires a callable method.
def flushAlerts() {
    Map slices = (state.alertSlices ?: [:]) as Map

    // Union of every slice's messages, de-duplicated by text. Two sources
    // reporting the same fault with the same wording collapse to one entry.
    Set<String> msgs = new TreeSet<String>()   // TreeSet = deterministic sort order
    slices.each { src, slice ->
        (slice ?: [:]).each { k, v -> if (v) msgs << v.toString() }
    }

    // ── Confirmation window ────────────────────────────────────────────────────
    // Publish a NEW alert only after it has been continuously present for
    // alertConfirmSec. Track first-seen per message text; drop the timestamp the
    // moment the text disappears, so a transient never accrues confirmation time
    // across separate occurrences. Clears are never delayed: a message that
    // vanishes is simply absent from this flush.
    long nowMs      = now()
    int  confirmSec = (settings.alertConfirmSec != null ? settings.alertConfirmSec : ALERT_CONFIRM_SEC_DEFAULT).toInteger()
    Map firstSeen   = (state.alertFirstSeen ?: [:]) as Map

    // Prune texts no longer present, then stamp new arrivals.
    firstSeen = firstSeen.findAll { k, v -> msgs.contains(k.toString()) }
    msgs.each { m -> if (firstSeen[m] == null) firstSeen[m] = nowMs }
    state.alertFirstSeen = firstSeen

    List all      = []
    boolean pendingConfirm = false
    msgs.each { m ->
        long ageMs = nowMs - ((firstSeen[m] ?: nowMs) as Long)
        if (confirmSec <= 0 || ageMs >= confirmSec * 1000L) {
            all << m
        } else {
            pendingConfirm = true
            logDebug "Alert awaiting confirmation (${(ageMs/1000L)}s/${confirmSec}s): ${m}"
        }
    }
    // Re-check once the youngest pending alert would mature. If it cleared by
    // then, the flush finds it absent and nothing is ever published.
    if (pendingConfirm) runIn(Math.max(2, confirmSec), "flushAlerts")

    // Housekeeping: if no battery alert is active any more, forget when the
    // stranded timer started so a future low battery gets a fresh grace period.
    if (!all.any { it.toLowerCase() =~ BATTERY_PAT }) state.remove("batteryStrandedSince")

    // Classify ONCE per message. contextSeverityOf() can have side effects
    // (starting the stranded timer), so it must not be called repeatedly per message.
    Map sevBy = [:]
    all.each { sevBy[it] = contextSeverityOf(it) }

    List pushable = all.findAll { PUSH_SEVERITIES.contains(sevBy[it]) }

    String allStr  = all.join("; ")
    String pushStr = pushable.join("; ")
    String worst   = all ? all.collect { sevBy[it] }
                              .max { SEVERITY_RANK.indexOf(it) } : "none"
    // Highest-severity single fault, for the legacy robotError attribute.
    String primary = all ? (all.find { sevBy[it] == worst } ?: all[0]) : ""

    // Compare against state, NOT device.currentValue(). currentValue() reflects the
    // last COMMITTED event; inside a burst it lags, which is why the earlier
    // change-guard attempt failed to suppress anything.
    if (state.lastActiveAlerts != allStr) {
        state.lastActiveAlerts = allStr
        sendEvent(name: "activeAlerts", value: alertOrClear(allStr), isStateChange: true)
        sendEvent(name: "alertCount",   value: all.size(), isStateChange: true)
        if (allStr) log.warn  "${device.displayName}: Active alerts — ${allStr}"
        else        logInfo   "All alerts cleared"
    }
    if (state.lastPushAlerts != pushStr) {
        state.lastPushAlerts = pushStr
        sendEvent(name: "pushAlerts", value: alertOrClear(pushStr), isStateChange: true)
    }
    if (state.lastAlertSeverity != worst) {
        state.lastAlertSeverity = worst
        sendEvent(name: "alertSeverity", value: worst, isStateChange: true)
    }
    // Legacy attribute — single writer now, and change-guarded.
    if (state.lastRobotError != primary) {
        state.lastRobotError = primary
        sendEvent(name: "robotError", value: alertOrClear(primary), isStateChange: true)
    }
}

// Applies robot context on top of the plain text classification. Currently only
// battery alerts are context-sensitive; everything else passes straight through.
private String contextSeverityOf(String msg) {
    String base = severityOf(msg)
    if (!msg || !(msg.toLowerCase() =~ BATTERY_PAT)) return base

    String status = device.currentValue("robotStatus") ?: "unknown"
    if (RECOVERING_STATUSES.contains(status)) {
        // Robot is dealing with it. Clear any running timer so a later strand
        // gets the full grace period rather than inheriting a stale start time.
        if (state.batteryStrandedSince) {
            logDebug "Battery alert de-escalated — robot is ${status}"
            state.remove("batteryStrandedSince")
        }
        return "info"
    }

    long graceMs = ((settings.batteryStrandedGraceMin ?: 10) as Long) * 60000L
    Long since   = state.batteryStrandedSince as Long

    if (!since) {
        state.batteryStrandedSince = now()
        // Re-check once the grace period is up. Nothing else would wake us: a
        // stranded robot publishes no new MQTT messages, so without this the
        // escalation would never happen.
        runIn((graceMs / 1000L) as Integer, "flushAlerts")
        logDebug "Battery alert: robot is ${status}, starting ${settings.batteryStrandedGraceMin ?: 10}min stranded timer"
        return "info"
    }

    if ((now() - since) >= graceMs) {
        log.warn "${device.displayName}: Low battery with robot ${status} for over " +
                 "${settings.batteryStrandedGraceMin ?: 10} min — treating as a fault"
        return "fault"
    }
    return "info"
}

private String severityOf(String msg) {
    if (!msg) return "none"
    String lower = msg.toLowerCase()
    for (rule in ALERT_RULES) {
        if (lower =~ rule.pat) return rule.sev
    }
    // Unrecognised text is deliberately NOT pushed. Silence is preferable to
    // waking someone for something we cannot classify. It still shows in activeAlerts.
    logDebug "Unclassified alert text (defaulting to info, will not push): ${msg}"
    return "info"
}

private void sendAttach(String name, String payload) {
    sendEvent(name: name, value: (payload == "true" || payload == "1") ? "attached" : "detached", isStateChange: true)
}

private void sendIntEvent(String name, String payload, String unit) {
    try {
        def v = payload.contains(".") ? payload.toDouble() : payload.toInteger()
        if (unit) sendEvent(name: name, value: v, unit: unit, isStateChange: true)
        else sendEvent(name: name, value: v, isStateChange: true)
    } catch (e) { logDebug "Cannot parse ${name}: ${payload}" }
}

// ═══════════════════════════════════════════════════════════════════════════════
// v3.13.0 ROBOT DISCOVERY + ADAPTIVE GUARDS
//
// discoverRobot() learns, per device, what the connected robot supports and caches
// it in state. The guards below read that cache:
//   - no cache yet (never discovered, or no Robot IP)  -> allow, like 3.12.0
//   - enforceSupported preference OFF                    -> allow, like 3.12.0
//   - capability or value missing                        -> log the valid list, block
// Blocking happens BEFORE any MQTT publish or REST call, so a blocked command never
// touches the robot and never counts toward the REST circuit breaker.
// ═══════════════════════════════════════════════════════════════════════════════

def discoverRobot() {
    if (restBlocked("GET robot discovery", false)) {
        if (!robotIp?.trim()) logDebug "discoverRobot(): no robot IP configured, skipping"
        return
    }
    def base = restBase()
    boolean ok = false
    String failReason = null
    Map info = null
    List caps = null
    Map opts = [:]
    try {
        httpGet([uri: base, timeout: 10]) { resp -> if (resp?.data instanceof Map) info = resp.data as Map }
        httpGet([uri: "${base}/capabilities", timeout: 10]) { resp ->
            if (resp?.data instanceof List) caps = resp.data.collect { it as String }
        }
        ok = (caps != null)
        if (!ok) failReason = "discoverRobot: capability list missing"
    } catch (e) {
        failReason = "discoverRobot failed — ${e.message}"
    }
    if (!ok) { restFailed(failReason ?: "discoverRobot error", base); return }

    // Option lists: one GET per capability the robot actually has. A failure here
    // only means "unknown list" for that option (no blocking), never a breaker hit.
    OPTION_SOURCES.each { String optName, Map src ->
        if (!caps.contains(src.cap)) return
        try {
            httpGet([uri: "${base}/capabilities/${src.path}", timeout: 10]) { resp ->
                def d = resp?.data
                def list = (src.key == null) ? d : (d instanceof Map ? d[src.key] : null)
                if (list instanceof List) opts[optName] = list.collect { it as String }
            }
        } catch (e) {
            logDebug "discoverRobot: could not read ${src.path} (${e.message})"
        }
    }

    state.robotCaps    = caps
    state.robotOptions = opts
    restSucceeded(200)

    String model = info ? [info.manufacturer, info.modelName].findAll { it }.join(" ") : ""
    if (model) sendEvent(name: "robotModel", value: model, isStateChange: true)

    def json = JsonOutput.toJson(opts)
    if (json.length() > 1000) json = JsonOutput.toJson(opts.keySet().toList())
    sendEvent(name: "supportedOptions", value: json ?: "{}", isStateChange: true)

    List missing = FEATURE_CAPS.findAll { k, v -> !caps.contains(v) }.collect { it.key as String }.sort()
    String missingStr = missing ? missing.join(", ") : ALERT_CLEAR
    if (missingStr.length() > 1000) missingStr = missingStr.substring(0, 1000)
    sendEvent(name: "unsupportedCommands", value: missingStr, isStateChange: true)

    // Attachments the robot does not report would otherwise stay blank forever.
    def att = info?.modelDetails?.supportedAttachments
    if (att instanceof List) {
        List supported = att.collect { it as String }
        ATTACHMENT_ATTRS.each { String type, String attr ->
            if (!supported.contains(type)) sendEvent(name: attr, value: NOT_SUPPORTED, isStateChange: false)
        }
    }

    logInfo "Discovered ${model ?: 'robot'}: ${caps.size()} capabilities; options ${json}; unsupported: ${missingStr}"
}

// True when discovery says this robot has the capability, or when nothing is known.
// Never logs; used by the background pulls (refreshSettings/refreshQuirks).
private boolean robotHas(String cap) {
    def caps = state.robotCaps
    if (!(caps instanceof List) || caps.isEmpty()) return true
    return caps.contains(cap)
}

// Guard for user commands. Logs and returns false when the robot lacks the
// capability behind `cmd` and enforcement is on.
private boolean supports(String cmd) {
    if (settings.enforceSupported == false) return true
    def cap = FEATURE_CAPS[cmd]
    if (!cap || robotHas(cap as String)) return true
    log.warn "${device.displayName}: ${cmd}: not supported on this robot " +
             "(${device.currentValue('robotModel') ?: 'model unknown'} has no ${cap}) — not sent"
    return false
}

// Guard for values. Returns the value in the robot's exact casing, the input
// unchanged when nothing is known or enforcement is off, or null (logged) when the
// robot does not accept it.
private String supportedValue(String optName, String cmd, String value) {
    def v = value?.toString()?.trim()
    if (settings.enforceSupported == false || v == null) return v
    def list = state.robotOptions instanceof Map ? state.robotOptions[optName] : null
    if (!(list instanceof List) || list.isEmpty()) return v
    def hit = list.find { (it as String).equalsIgnoreCase(v) }
    if (hit) return hit as String
    log.warn "${device.displayName}: ${cmd}: '${v}' is not supported on this robot. Supported: ${list.join(', ')} — not sent"
    return null
}

// ═══════════════════════════════════════════════════════════════════════════════
// COMMANDS — Switch + Basic
// ═══════════════════════════════════════════════════════════════════════════════

def on()  { start() }
def off() { dock()  }

def start()  { sendBasic("START") }
def stop()   { sendBasic("STOP")  }
def pause()  { sendBasic("PAUSE") }
def dock()   { sendBasic("HOME")  }

private void sendBasic(String op) {
    if (!supports("start")) return
    // MQTT wants bare enum (START/STOP/PAUSE/HOME); REST wants {"action":"start"} lowercase
    if (mqttUp()) {
        pub("BasicControlCapability/operation/set", op)
    } else {
        restPut("BasicControlCapability", [action: op.toLowerCase()])
    }
    logInfo "Command → ${op}"
}

def locate() {
    if (!supports("locate")) return
    if (mqttUp()) pub("LocateCapability/locate/set", "PERFORM")
    else restPut("LocateCapability", [action: "locate"])
    logInfo "Command → LOCATE"
}

// ═══════════════════════════════════════════════════════════════════════════════
// COMMANDS — Cleaning
// ═══════════════════════════════════════════════════════════════════════════════

def cleanSegment(segmentIds, iterations = 1, customOrder = false) {
    if (!supports("cleanSegment")) return
    def idStr = (segmentIds ?: "").toString().trim()
    if (!idStr) { log.warn "${device.displayName}: cleanSegment: no segment IDs"; return }
    def ids   = idStr.tokenize(",").collect { it.trim() }.findAll { it }
    def body  = [segment_ids: ids, iterations: toIntOr(iterations, 1), customOrder: toBool(customOrder)]
    if (mqttUp()) pub("MapSegmentationCapability/clean/set", JsonOutput.toJson(body))
    else restPut("MapSegmentationCapability", body + [action: "start_segment_action"])
    logInfo "cleanSegment ${ids} x${body.iterations} order=${body.customOrder}"
}

def cleanZones(zonesJson, iterations = 1) {
    if (!supports("cleanZones")) return
    def zStr = (zonesJson ?: "").toString().trim()
    if (!zStr) { log.warn "${device.displayName}: cleanZones: no zones"; return }
    def zones
    try { zones = new JsonSlurper().parseText(zStr) }
    catch (e) { log.error "${device.displayName}: cleanZones: invalid JSON — ${e.message}"; return }
    def body = [zones: zones, iterations: toIntOr(iterations, 1)]
    if (mqttUp()) pub("ZoneCleaningCapability/start/set", JsonOutput.toJson(body))
    else restPut("ZoneCleaningCapability", body + [action: "clean"])
    logInfo "cleanZones x${body.iterations}"
}

def goToLocation(x, y) {
    if (!supports("goToLocation")) return
    def xi = toIntOrNull(x)
    def yi = toIntOrNull(y)
    if (xi == null || yi == null) { log.warn "${device.displayName}: goToLocation: need numeric x and y"; return }
    def body = [coordinates: [x: xi, y: yi]]
    if (mqttUp()) pub("GoToLocationCapability/go/set", JsonOutput.toJson(body))
    else restPut("GoToLocationCapability", body + [action: "goto"])
    logInfo "goToLocation (${xi}, ${yi})"
}

// ═══════════════════════════════════════════════════════════════════════════════
// COMMANDS — Presets
// ═══════════════════════════════════════════════════════════════════════════════

def setFanSpeed(String preset) {
    if (!supports("setFanSpeed")) return
    def p = supportedValue("fanSpeed", "setFanSpeed", preset)
    if (p == null) return
    if (mqttUp()) pub("FanSpeedControlCapability/preset/set", p)
    else restPut("FanSpeedControlCapability/preset", [name: p])
    logInfo "Fan → ${p}"
}

def setWaterLevel(String preset) {
    if (!supports("setWaterLevel")) return
    def p = supportedValue("waterLevel", "setWaterLevel", preset)
    if (p == null) return
    if (mqttUp()) pub("WaterUsageControlCapability/preset/set", p)
    else restPut("WaterUsageControlCapability/preset", [name: p])
    logInfo "Water → ${p}"
}

def setOperationMode(String mode) {
    if (!supports("setOperationMode")) return
    def m = supportedValue("operationMode", "setOperationMode", mode)
    if (m == null) return
    if (mqttUp()) pub("OperationModeControlCapability/preset/set", m)
    else restPut("OperationModeControlCapability/preset", [name: m])
    logInfo "Mode → ${m}"
}

// ═══════════════════════════════════════════════════════════════════════════════
// COMMANDS — Dock / Toggles / Sound / Consumables
// ═══════════════════════════════════════════════════════════════════════════════

def triggerAutoEmpty() {
    if (!supports("triggerAutoEmpty")) return
    if (mqttUp()) pub("AutoEmptyDockManualTriggerCapability/trigger/set", "PERFORM")
    else restPut("AutoEmptyDockManualTriggerCapability", [action: "trigger"])
    logInfo "Trigger auto-empty dock"
}

def setKeyLock(String onOff)              { if (supports("setKeyLock"))              setToggle("KeyLockCapability", onOff) }
def setCarpetMode(String onOff)           { if (supports("setCarpetMode"))           setToggle("CarpetModeControlCapability", onOff) }
def setObstacleAvoidance(String onOff)    { if (supports("setObstacleAvoidance"))    setToggle("ObstacleAvoidanceControlCapability", onOff) }
def setPetObstacleAvoidance(String onOff) { if (supports("setPetObstacleAvoidance")) setToggle("PetObstacleAvoidanceControlCapability", onOff) }

private void setToggle(String cap, String onOff) {
    def on = (onOff?.toLowerCase() == "on")
    if (mqttUp()) pub("${cap}/enabled/set", on ? "ON" : "OFF")
    else restPut(cap, [action: on ? "enable" : "disable"])
    logInfo "${cap} → ${on ? 'ON' : 'OFF'}"
}

// Carpet sensor mode. MQTT: CarpetSensorModeControlCapability/mode/set with a bare enum
// (mqtt docs). REST: PUT CarpetSensorModeControlCapability {"mode":"<mode>"} (Swagger).
// Validated against the allowlist because the value reaches a payload either way.
def setCarpetSensorMode(String mode) {
    if (!supports("setCarpetSensorMode")) return
    def m = mode?.trim()?.toLowerCase()
    if (!(m in CARPET_SENSOR_MODES)) {
        log.warn "${device.displayName}: setCarpetSensorMode: use one of ${CARPET_SENSOR_MODES.join(', ')} (got '${mode}')"
        return
    }
    m = supportedValue("carpetSensorMode", "setCarpetSensorMode", m)
    if (m == null) return
    if (mqttUp()) pub("CarpetSensorModeControlCapability/mode/set", m)
    else restPut("CarpetSensorModeControlCapability", [mode: m])
    logInfo "Carpet sensor mode → ${m}"
}

// Acknowledge a Valetudo event. MQTT: ValetudoEvents/valetudo_events/interact/set with
// {"id":"<id>","interaction":"ok"} (mqtt docs). REST: PUT /api/v2/events/<id>/interact
// {"interaction":"ok"} (Swagger). "ok" is the documented interaction for dismissible
// events such as ErrorStateValetudoEvent. The id reaches a URL path, so it is
// allowlisted first. Once Valetudo drops the event, the retained valetudo_events topic
// is republished without it and the alert engine clears it as usual.
def dismissEvent(String eventId) {
    def id = eventId?.trim()
    if (!id || !(id ==~ EVENT_ID_PAT)) {
        log.warn "${device.displayName}: dismissEvent: '${eventId}' is not a valid event id (letters, digits, - and _ only)"
        return
    }
    if (mqttUp()) {
        pub("ValetudoEvents/valetudo_events/interact/set", JsonOutput.toJson([id: id, interaction: "ok"]))
    } else {
        def host = restHost()
        if (host) restPutUrl("http://${host}/api/v2/events/${id}/interact", [interaction: "ok"], "PUT events/interact")
        else if (settings.restFallback != false) log.warn "${device.displayName}: REST command needs Robot IP set — command not sent"
    }
    logInfo "Dismiss event → ${id}"
}

def setSpeakerVolume(volume) {
    if (!supports("setSpeakerVolume")) return
    def v = Math.max(0, Math.min(100, toIntOr(volume, 0)))
    if (mqttUp()) pub("SpeakerVolumeControlCapability/value/set", v.toString())
    else restPut("SpeakerVolumeControlCapability", [action: "set_volume", value: v])
    logInfo "Speaker volume → ${v}"
}

def resetConsumable(String name) {
    if (!supports("resetConsumable")) return
    def key = name?.trim()
    if (!key) { log.warn "${device.displayName}: resetConsumable: no name"; return }
    // The key goes into an MQTT topic or a REST path, so it must be a plain token.
    // Real keys look like brush-main, brush-side_right, filter-main, cleaning-sensor.
    if (!(key ==~ CONSUMABLE_KEY_PAT)) {
        log.warn "${device.displayName}: resetConsumable: '${key}' is not a valid consumable key (use one from the consumables attribute)"
        return
    }
    if (mqttUp()) {
        pub("ConsumableMonitoringCapability/${key}/reset/set", "PERFORM")
    } else {
        // Swagger: PUT .../ConsumableMonitoringCapability/<type>[/<sub_type>] {"action":"reset"}.
        // MQTT key = "<type>-<sub_type>" (sub_type may itself contain "_" but never "-"),
        // so split on the FIRST "-". Both parts passed the allowlist above, which makes
        // URL-encoding them a no-op.
        int dash = key.indexOf("-")
        String path = (dash > 0 && dash < key.length() - 1) ?
            "ConsumableMonitoringCapability/${key.substring(0, dash)}/${key.substring(dash + 1)}" :
            "ConsumableMonitoringCapability/${key}"
        restPut(path, [action: "reset"])
    }
    logInfo "Reset consumable → ${key}"
}

// ═══════════════════════════════════════════════════════════════════════════════
// MQTT PUBLISH
// ═══════════════════════════════════════════════════════════════════════════════

private boolean mqttUp() {
    return device.currentValue("mqttStatus") == "connected"
}

private void pub(String subTopic, String payload) {
    def topic = "${baseTopic()}/${subTopic}"
    logDebug "OUT ${topic} = ${payload}"
    try {
        interfaces.mqtt.publish(topic, payload, 1, false)
    } catch (e) {
        log.error "${device.displayName}: Publish failed ${topic} — ${e.message}"
        setMqttStatus("error")
        scheduleReconnect()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// REST API (optional — requires robotIp)
// ═══════════════════════════════════════════════════════════════════════════════

private String restBase() {
    def host = restHost()
    return host ? "http://${host}/api/v2/robot" : null
}

// Just host[:port] from the robotIp preference. Shared by restBase() and the few
// endpoints that live outside /api/v2/robot (mqtt/status, events).
private String restHost() {
    def ip = robotIp?.trim()
    if (!ip) return null
    // Defensive: strip any accidental scheme, path, or trailing slash the user may have
    // pasted, leaving just host[:port]. Prevents malformed REST URLs.
    if (ip.startsWith("http://"))  ip = ip.substring(7)
    if (ip.startsWith("https://")) ip = ip.substring(8)
    int slash = ip.indexOf("/")
    if (slash >= 0) ip = ip.substring(0, slash)
    ip = ip.trim()
    return ip ?: null
}

// Manual / on-(re)connect refresh of all state attributes via REST
def refresh() {
    if (restBlocked("GET state/attributes", false)) {
        if (!robotIp?.trim()) logDebug "refresh(): no robot IP configured, skipping REST pull"
        return
    }
    def url = "${restBase()}/state/attributes"
    logDebug "REST GET ${url}"

    boolean ok = false
    String failReason = null
    try {
        httpGet([uri: url, timeout: 10]) { resp ->
            ok = true
            if (resp?.data instanceof List) {
                resp.data.each { attr -> applyRestAttribute(attr) }
                logInfo "Refreshed state from REST (${resp.data.size()} attributes)"
            }
        }
    } catch (e) {
        failReason = "refresh failed — ${e.message}"
    }
    if (ok) restSucceeded(200)
    else    restFailed(failReason ?: "refresh error", url)
}

// Maps a single REST StateAttribute object onto driver attributes.
// REST attribute shape: {"__class":"StatusStateAttribute","type":"status","value":"docked","flag":"none"}
private void applyRestAttribute(Map attr) {
    switch (attr.__class) {
        case "StatusStateAttribute":
            if (attr.value) handleRobotStatus(attr.value as String)
            if (attr.flag)  sendEvent(name: "robotStatusFlag", value: attr.flag as String, isStateChange: true)
            break
        case "BatteryStateAttribute":
            if (attr.level != null) {
                sendEvent(name: "battery", value: (attr.level as Integer), unit: "%", isStateChange: true)
                state.lastBatteryLevel = attr.level as Integer
            }
            if (attr.flag) setBatteryFlag(attr.flag as String)
            else publishBatteryStatus()
            break
        case "PresetSelectionStateAttribute":
            // type can be fan_speed / water_grade / operation_mode
            if (attr.type == "fan_speed")      sendEvent(name: "fanSpeed",  value: attr.value as String, isStateChange: true)
            else if (attr.type == "water_grade") sendEvent(name: "waterLevel", value: attr.value as String, isStateChange: true)
            else if (attr.type == "operation_mode") sendEvent(name: "operationMode", value: attr.value as String, isStateChange: true)
            break
        case "AttachmentStateAttribute":
            if (attr.type == "mop")       sendEvent(name: "attachment_mop",       value: attr.attached ? "attached" : "detached", isStateChange: true)
            else if (attr.type == "dustbin")   sendEvent(name: "attachment_dustbin",   value: attr.attached ? "attached" : "detached", isStateChange: true)
            else if (attr.type == "watertank") sendEvent(name: "attachment_watertank", value: attr.attached ? "attached" : "detached", isStateChange: true)
            break
        case "DockStatusStateAttribute":
            // REST shape: {"__class":"DockStatusStateAttribute","value":"idle"} (live, 2026.10.0)
            if (attr.value) sendEvent(name: "dockStatus", value: attr.value as String, isStateChange: true)
            break
        case "DockComponentStateAttribute":
            // REST shape: {"__class":"DockComponentStateAttribute","type":"dustbag","value":"ok"}
            // Same names and values as the MQTT DockComponentStateAttribute/<type> topics.
            def dockAttr = DOCK_COMPONENT_ATTRS[attr.type as String]
            if (dockAttr && attr.value) sendEvent(name: dockAttr, value: attr.value as String, isStateChange: true)
            break
        default:
            logDebug "REST attribute not mapped: ${attr.__class}"
            break
    }
}

// Refresh the segment (room) directory via REST
def refreshSegments() {
    if (restBlocked("GET MapSegmentationCapability", false)) {
        if (!robotIp?.trim()) logDebug "refreshSegments(): no robot IP"
        return
    }
    if (!robotHas("MapSegmentationCapability")) { logDebug "refreshSegments(): robot has no MapSegmentationCapability"; return }
    def url = "${restBase()}/capabilities/MapSegmentationCapability"
    logDebug "REST GET ${url}"

    boolean ok = false
    String failReason = null
    try {
        httpGet([uri: url, timeout: 10]) { resp ->
            ok = true
            // Response is a list of {id, name}
            if (resp?.data instanceof List) {
                def map = [:]
                resp.data.each { seg -> if (seg.id != null) map[seg.id as String] = (seg.name ?: seg.id) as String }
                sendEvent(name: "mapSegments", value: JsonOutput.toJson(map), isStateChange: true)
                logInfo "Refreshed ${map.size()} segments from REST"
            }
        }
    } catch (e) {
        failReason = "refreshSegments failed — ${e.message}"
    }
    if (ok) restSucceeded(200)
    else    restFailed(failReason ?: "refreshSegments error", url)
}

// ═══════════════════════════════════════════════════════════════════════════════
// QUIRKS (vendor-specific tunables — REST ONLY)
//
// Valetudo does NOT expose QuirksCapability over MQTT (confirmed against Valetudo's
// MQTT capability list), so these always go over HTTP to the robot IP and, per design,
// obey restFallback + the circuit breaker exactly like any other REST command.
//
// Endpoint (verified against Valetudo source docs):
//   GET  /api/v2/robot/capabilities/QuirksCapability
//        → array of {id, title, description, options[], value}
//   PUT  /api/v2/robot/capabilities/QuirksCapability
//        → sets one quirk by id to one of its option values
//
// PUT BODY FORMAT — confirmed against the robot's Swagger export:
//   {"id": "<uuid>", "value": "<value>"}
// Isolated to quirkPutBody() below so it's a one-line change if a future firmware
// ever alters the schema.
// ═══════════════════════════════════════════════════════════════════════════════

// ── Robot-independent quirk resolution ────────────────────────────────────────
// The driver hardcodes NO robot-specific quirk data. UUIDs are discovered at runtime
// by refreshQuirks() and cached in state.quirkIds (title -> uuid), so the same driver
// serves any Valetudo robot whose quirk set differs.
//
// The constant below is ONLY a last-resort fallback for setFanSpeedTurbo() when
// discovery has not run yet (e.g. the very first command after install, before the
// first refreshQuirks). Verified identical on Dreame X40 Master and X40 Ultra; Valetudo
// defines Dreame quirk UUIDs once in a shared factory, so robots that expose the same
// quirk share its UUID. If a robot ever differs, discovery wins and this is never used.
// v3.13.0: also never used once discovery HAS run and the robot has no such quirk
// (MOVA V50), because sending it there is rejected and counts toward the breaker.
@Field static final String QUIRK_FAN_SPEED_TURBO_FALLBACK = "017c40b7-dc24-48e8-a6e0-19d66528fa6d"

// Title of the turbo quirk as Valetudo reports it. Overridable per device in case a
// firmware renames it.
@Field static final String QUIRK_TITLE_FAN_SPEED_TURBO = "Fan Speed Turbo"

// Look up a quirk UUID by its Valetudo title from the discovery cache. Case- and
// whitespace-insensitive. Returns null when unknown (cache empty or title absent).
private String quirkIdForTitle(String title) {
    def t = title?.trim()
    if (!t) return null
    def ids = state.quirkIds
    if (!(ids instanceof Map) || ids.isEmpty()) return null
    def exact = ids[t]
    if (exact) return exact as String
    def hit = ids.find { k, v -> (k as String)?.trim()?.equalsIgnoreCase(t) }
    return hit ? (hit.value as String) : null
}

// Valid option strings for a quirk title, from the discovery cache (empty when unknown).
private List quirkOptionsForTitle(String title) {
    def t = title?.trim()
    if (!t) return []
    def opts = state.quirkOptions
    if (!(opts instanceof Map) || opts.isEmpty()) return []
    def exact = opts[t]
    if (exact) return exact as List
    def hit = opts.find { k, v -> (k as String)?.trim()?.equalsIgnoreCase(t) }
    return hit ? (hit.value as List) : []
}

// Resolves the turbo UUID for THIS robot, most-authoritative first:
//   1. discovery cache (the robot's own reported UUID)
//   2. the optional per-device preference
//   3. the shared fallback constant — ONLY while quirks have never been discovered
// Returns null when quirks were discovered, the robot has no turbo quirk, and no
// UUID preference is set (v3.13.0; the caller logs and stops).
private String turboQuirkId() {
    def discovered = quirkIdForTitle(settings.turboQuirkTitle?.trim() ?: QUIRK_TITLE_FAN_SPEED_TURBO)
    if (discovered) return discovered
    def pref = settings.turboQuirkId?.trim()
    if (pref) return pref
    boolean quirksKnown = (state.quirkIds instanceof Map) && !state.quirkIds.isEmpty()
    if (quirksKnown && settings.enforceSupported != false) return null
    logDebug "turboQuirkId(): using fallback UUID (run refreshQuirks to discover this robot's own)"
    return QUIRK_FAN_SPEED_TURBO_FALLBACK
}

// Builds the PUT body for a quirk write. Confirmed shape: {id, value}. CHANGE HERE
// (and only here) if a future firmware's Swagger ever shows a different schema.
private Map quirkPutBody(String quirkId, String value) {
    return [id: quirkId, value: value]
}

// Generic engine: set any quirk by UUID to one of its valid option strings.
// Routed through restPut() so it honors restFallback + breaker and reuses all the
// 2xx/failure handling. Value is sent in the JSON body (not the URL), so no path
// encoding is needed here.
def setQuirk(String quirkId, String value) {
    if (!supports("setQuirk")) return
    def id = quirkId?.trim()
    def v  = value?.toString()?.trim()
    if (!id) { log.warn "${device.displayName}: setQuirk: missing quirk UUID"; return }
    if (v == null || v == "") { log.warn "${device.displayName}: setQuirk: missing value"; return }
    // "QuirksCapability" is the fixed capability path segment (no user input in the URL).
    restPut("QuirksCapability", quirkPutBody(id, v))
    logInfo "Quirk ${id} → ${v}"
}

// Set a quirk by its human-readable Valetudo title (no UUID needed). This is the
// robot-independent way to drive quirks: the UUID is resolved from the discovery cache
// populated by refreshQuirks(). Validates the value against the robot's own reported
// options when they're known, so a typo fails loudly here instead of silently 400ing.
def setQuirkByTitle(String title, String value) {
    if (!supports("setQuirkByTitle")) return
    def t = title?.trim()
    def v = value?.toString()?.trim()
    if (!t) { log.warn "${device.displayName}: setQuirkByTitle: missing quirk title"; return }
    if (!v) { log.warn "${device.displayName}: setQuirkByTitle: missing value"; return }

    def id = quirkIdForTitle(t)
    if (!id) {
        def known = (state.quirkIds instanceof Map) ? state.quirkIds.keySet().toList() : []
        if (known.isEmpty()) {
            log.warn "${device.displayName}: setQuirkByTitle: no quirks discovered yet — run the refreshQuirks command once, then retry"
        } else {
            log.warn "${device.displayName}: setQuirkByTitle: no quirk titled '${t}' on this robot. Known titles: ${known.join(' | ')}"
        }
        return
    }

    // Validate against the robot's own option list when we have it.
    def opts = quirkOptionsForTitle(t)
    if (opts && !opts.any { (it as String).equalsIgnoreCase(v) }) {
        log.warn "${device.displayName}: setQuirkByTitle: '${v}' is not a valid value for '${t}'. Valid: ${opts.join(', ')}"
        return
    }
    // Send the robot's exact casing (values like "Wet"/"Dry" are case-sensitive).
    def exact = opts.find { (it as String).equalsIgnoreCase(v) }
    setQuirk(id, (exact ?: v) as String)
    logInfo "Quirk '${t}' → ${exact ?: v}"
}

// Convenience wrapper: Fan Speed Turbo. The quirk's options are exactly on|off.
// One-shot behavior: the robot self-clears it after the next cleanup, so set it right
// before dispatching a clean; a read-back may already show "off". No reset needed.
def setFanSpeedTurbo(String onOff) {
    if (!supports("setFanSpeedTurbo")) return
    def s = onOff?.trim()?.toLowerCase()
    if (!(s in ["on","off"])) {
        log.warn "${device.displayName}: setFanSpeedTurbo: use on or off (got '${onOff}')"
        return
    }
    def id = turboQuirkId()
    if (!id) {
        log.warn "${device.displayName}: setFanSpeedTurbo: this robot " +
                 "(${device.currentValue('robotModel') ?: 'model unknown'}) has no " +
                 "'${settings.turboQuirkTitle?.trim() ?: QUIRK_TITLE_FAN_SPEED_TURBO}' quirk — not sent. " +
                 "Use setFanSpeed max, or set the turbo UUID preference to force it."
        return
    }
    setQuirk(id, s)
    logInfo "Fan Speed Turbo → ${s}"
}

// Read all quirk values into the 'quirks' attribute as JSON {title: value, ...}.
// Deliberate state pull: honors the breaker but not restFallback (a manual tap should
// still try). Mirrors refresh()/refreshSegments(): outcome captured in locals, state
// bookkeeping runs in this method frame for reliable persistence.
// Read all quirks from the robot. This is the DISCOVERY step that makes the driver
// robot-independent: besides updating the 'quirks' attribute (title -> current value),
// it caches this robot's own title -> UUID and title -> options maps in state, so
// setQuirkByTitle() and setFanSpeedTurbo() can resolve UUIDs at runtime instead of
// relying on any hardcoded ID. Runs automatically on (re)connect.
//
// Deliberate state pull: honors the breaker but not restFallback (a manual tap should
// still try). Mirrors refresh()/refreshSegments(): outcome captured in locals, state
// bookkeeping runs in this method frame for reliable persistence.
def refreshQuirks() {
    if (restBlocked("GET QuirksCapability", false)) {
        if (!robotIp?.trim()) logDebug "refreshQuirks(): no robot IP"
        return
    }
    if (!robotHas("QuirksCapability")) { logDebug "refreshQuirks(): robot has no QuirksCapability"; return }
    def url = "${restBase()}/capabilities/QuirksCapability"
    logDebug "REST GET ${url}"

    boolean ok = false
    String failReason = null
    def idMap  = [:]   // title -> uuid
    def optMap = [:]   // title -> [options]
    def valMap = [:]   // title -> current value
    try {
        httpGet([uri: url, timeout: 10]) { resp ->
            ok = true
            // Valetudo returns an array of {id, title, description, options[], value}.
            // Some firmwares may return a single object; tolerate both.
            def rows = (resp?.data instanceof List) ? resp.data
                     : (resp?.data != null ? [resp.data] : [])
            rows.each { q ->
                def t = q?.title as String
                if (t) {
                    valMap[t] = (q.value ?: "") as String
                    if (q.id != null)      idMap[t]  = q.id as String
                    if (q.options != null) optMap[t] = q.options.collect { it as String }
                }
            }
        }
    } catch (e) {
        failReason = "refreshQuirks failed — ${e.message}"
    }

    if (!ok) { restFailed(failReason ?: "refreshQuirks error", url); return }

    // Cache discovery maps in state (not attributes): they can exceed the ~1024-char
    // attribute cap, and Rule Machine never needs to read them directly.
    state.quirkIds     = idMap
    state.quirkOptions = optMap
    state.quirkValues  = valMap   // v3.13.0: read by chargeLimitPct()

    def json = JsonOutput.toJson(valMap)
    if (json.length() > 1000) {
        log.warn "${device.displayName}: quirks map exceeded attribute size limit; storing titles only (full data is in state)"
        json = JsonOutput.toJson(valMap.keySet().toList())
    }
    sendEvent(name: "quirks", value: json, isStateChange: true)
    restSucceeded(200)
    logInfo "Refreshed ${valMap.size()} quirks from REST"
    // Print the usable vocabulary so the user can copy exact titles/values into Rule Machine.
    optMap.each { t, opts -> logDebug "Quirk '${t}' options: ${opts.join(', ')}" }
    // The Charging Limit may have changed, so re-evaluate batteryStatus.
    publishBatteryStatus()
}

// ═══════════════════════════════════════════════════════════════════════════════
// EXTRA CAPABILITIES (REST ONLY — bodies verified against the robot's Swagger)
//
// All route through restPut(), so they honor the restFallback preference + circuit
// breaker exactly like every other REST command. Stateful setters update their
// read-back attribute optimistically to the requested value; refreshSettings()
// reconciles from the robot.
// ═══════════════════════════════════════════════════════════════════════════════

// Mop dock: dry mop pads. Swagger: PUT MopDockDryManualTriggerCapability {"action":"start"|"stop"}
def mopDockDry(String action) {
    if (!supports("mopDockDry")) return
    def a = action?.trim()?.toLowerCase()
    if (!(a in ["start","stop"])) { log.warn "${device.displayName}: mopDockDry: use start or stop (got '${action}')"; return }
    restPut("MopDockDryManualTriggerCapability", [action: a])
    logInfo "Mop dock dry → ${a}"
}

// Mop dock: wash mop pads. Swagger: PUT MopDockCleanManualTriggerCapability {"action":"start"|"stop"}
// (confirmed on both robots' Valetudo 2026.10.0 Swagger in 3.12.0; the body was inferred
// by analogy with the Dry trigger before that). Still isolated in mopDockCleanBody().
def mopDockClean(String action) {
    if (!supports("mopDockClean")) return
    def a = action?.trim()?.toLowerCase()
    if (!(a in ["start","stop"])) { log.warn "${device.displayName}: mopDockClean: use start or stop (got '${action}')"; return }
    restPut("MopDockCleanManualTriggerCapability", mopDockCleanBody(a))
    logInfo "Mop dock clean → ${a}"
}
// CHANGE HERE (and only here) if Swagger shows a different body for the clean trigger.
private Map mopDockCleanBody(String action) { return [action: action] }

// Do Not Disturb. Swagger: PUT/GET DoNotDisturbCapability
//   {"enabled":bool, "start":{"hour":H,"minute":M}, "end":{"hour":H,"minute":M}}
def setDoNotDisturb(String onOff, String startTime = null, String endTime = null) {
    if (!supports("setDoNotDisturb")) return
    def on = (onOff?.trim()?.toLowerCase() == "on")
    def body = [enabled: on]
    def sWin = ""
    if (on) {
        def s = parseHHMM(startTime)
        def e = parseHHMM(endTime)
        if (s == null || e == null) {
            log.warn "${device.displayName}: setDoNotDisturb: on requires startTime and endTime as HH:MM"
            return
        }
        body.start = [hour: s[0], minute: s[1]]
        body.end   = [hour: e[0], minute: e[1]]
        sWin = String.format("%02d:%02d-%02d:%02d", s[0], s[1], e[0], e[1])
    }
    restPut("DoNotDisturbCapability", body)
    // Optimistic update
    sendEvent(name: "dnd", value: on ? "on" : "off", isStateChange: true)
    // ALERT_CLEAR, not "": Hubitat drops empty-string events (see 3.9.1), which left
    // dndWindow stuck on the old window after turning DND off.
    sendEvent(name: "dndWindow", value: on ? sWin : ALERT_CLEAR, isStateChange: true)
    logInfo "Do Not Disturb → ${on ? "on ${sWin}" : "off"}"
}

// Clean route. Swagger: PUT CleanRouteControlCapability {"route":"<value>"}
def setCleanRoute(String route) {
    if (!supports("setCleanRoute")) return
    def r = route?.trim()
    if (!r) { log.warn "${device.displayName}: setCleanRoute: no route"; return }
    r = supportedValue("cleanRoute", "setCleanRoute", r)
    if (r == null) return
    restPut("CleanRouteControlCapability", [route: r])
    sendEvent(name: "cleanRoute", value: r, isStateChange: true)   // optimistic
    logInfo "Clean route → ${r}"
}

// Auto-empty interval. Swagger: PUT AutoEmptyDockAutoEmptyIntervalControlCapability {"interval":"<value>"}
def setAutoEmptyInterval(String interval) {
    if (!supports("setAutoEmptyInterval")) return
    def iv = interval?.trim()
    if (!iv) { log.warn "${device.displayName}: setAutoEmptyInterval: no interval"; return }
    iv = supportedValue("autoEmptyInterval", "setAutoEmptyInterval", iv)
    if (iv == null) return
    restPut("AutoEmptyDockAutoEmptyIntervalControlCapability", [interval: iv])
    sendEvent(name: "autoEmptyInterval", value: iv, isStateChange: true)   // optimistic
    logInfo "Auto-empty interval → ${iv}"
}

// Mop auto-drying toggle. Swagger: PUT MopDockMopAutoDryingControlCapability {"action":"enable"|"disable"}
def setMopAutoDrying(String onOff) {
    if (!supports("setMopAutoDrying")) return
    def on = (onOff?.trim()?.toLowerCase() == "on")
    restPut("MopDockMopAutoDryingControlCapability", [action: on ? "enable" : "disable"])
    sendEvent(name: "mopAutoDrying", value: on ? "on" : "off", isStateChange: true)   // optimistic
    logInfo "Mop auto-drying → ${on ? "on" : "off"}"
}

// Best-effort read of the above settings into their attributes. Each capability GET is
// independent: a 404 (capability not supported on this robot) is skipped quietly and does
// NOT trip the circuit breaker. Any single success proves the robot is reachable and
// resets the breaker. Gated by restBlocked so it stays quiet while the robot is powered off.
// v3.13.0: capabilities discovery says the robot lacks are not requested at all.
def refreshSettings() {
    if (restBlocked("GET settings", false)) {
        if (!robotIp?.trim()) logDebug "refreshSettings(): no robot IP"
        return
    }
    boolean anyOk = false

    // Do Not Disturb
    anyOk = settingsGet("DoNotDisturbCapability") { d ->
        def on = (d?.enabled == true)
        sendEvent(name: "dnd", value: on ? "on" : "off", isStateChange: true)
        if (on && d?.start != null && d?.end != null) {
            sendEvent(name: "dndWindow",
                value: String.format("%02d:%02d-%02d:%02d",
                    (d.start.hour ?: 0) as int, (d.start.minute ?: 0) as int,
                    (d.end.hour ?: 0) as int,   (d.end.minute ?: 0) as int),
                isStateChange: true)
        } else {
            sendEvent(name: "dndWindow", value: ALERT_CLEAR, isStateChange: true)
        }
    } || anyOk

    // Clean route
    anyOk = settingsGet("CleanRouteControlCapability") { d ->
        if (d?.route != null) sendEvent(name: "cleanRoute", value: d.route as String, isStateChange: true)
    } || anyOk

    // Auto-empty interval
    anyOk = settingsGet("AutoEmptyDockAutoEmptyIntervalControlCapability") { d ->
        if (d?.interval != null) sendEvent(name: "autoEmptyInterval", value: d.interval as String, isStateChange: true)
    } || anyOk

    // Mop auto-drying
    anyOk = settingsGet("MopDockMopAutoDryingControlCapability") { d ->
        if (d?.enabled != null) sendEvent(name: "mopAutoDrying", value: (d.enabled ? "on" : "off"), isStateChange: true)
    } || anyOk

    // Carpet sensor mode (Swagger: GET returns {"mode":"lift"})
    anyOk = settingsGet("CarpetSensorModeControlCapability") { d ->
        if (d?.mode) sendEvent(name: "carpetSensorMode", value: d.mode as String, isStateChange: true)
    } || anyOk

    if (anyOk) {
        restSucceeded(200)
        logInfo "Refreshed settings from REST"
    } else {
        logDebug "refreshSettings(): no capabilities returned data (may be unsupported or robot unreachable)"
    }
}

// Helper: GET one capability endpoint and hand its parsed body to the closure.
// Returns true on a 2xx, false on any error (including 404 = capability unsupported).
// Never calls restFailed(), so an unsupported capability can't open the breaker.
private boolean settingsGet(String cap, Closure handler) {
    if (!robotHas(cap)) { logDebug "settingsGet ${cap}: not on this robot, skipped"; return false }
    def url = "${restBase()}/capabilities/${cap}"
    try {
        boolean ok = false
        httpGet([uri: url, timeout: 10]) { resp ->
            ok = true
            handler(resp?.data)
        }
        return ok
    } catch (e) {
        logDebug "settingsGet ${cap}: ${e.message}"
        return false
    }
}

// Parse "HH:MM" (24h) into [hour, minute]; returns null on bad input.
private List parseHHMM(String s) {
    if (!s?.trim()) return null
    def m = (s.trim() =~ /^(\d{1,2}):(\d{2})$/)
    if (!m.matches()) return null
    int h = m.group(1) as int
    int mi = m.group(2) as int
    if (h < 0 || h > 23 || mi < 0 || mi > 59) return null
    return [h, mi]
}

// ── REST guard + failure accounting (shared by refresh, refreshSegments, restPut) ──
//
// A single gate for ALL outbound REST traffic. Returns true when a REST call must be
// SKIPPED. isCommand distinguishes a command fallback (setFanSpeed, dock, etc.) from a
// deliberate state pull (refresh/refreshSegments):
//   - Command fallbacks honor the restFallback preference (the manual off-switch for
//     planned downtime) AND the breaker.
//   - State pulls ignore restFallback (a manual Refresh tap should still try) but still
//     honor the breaker so repeated auto-refresh failures go quiet.
// Reasons to skip, checked cheaply and in order:
//   1. (commands only) restFallback preference unchecked.
//   2. No robot IP configured — REST is impossible; caller decides whether to warn.
//   3. Circuit breaker open — REST has failed REST_FAIL_THRESHOLD times in a row, so
//      we skip (fail fast, no socket, no timeout, no per-call error log) until the
//      cooldown elapses. Reset by restSucceeded() on the next 2xx.
private boolean restBlocked(String what, boolean isCommand) {
    if (isCommand && settings.restFallback == false) {
        logDebug "REST skipped — fallback disabled in preferences (${what})"
        return true
    }
    if (!robotIp?.trim()) return true   // caller decides whether to warn
    long breakerUntil = (state.restBreakerUntil ?: 0L) as long
    if (breakerUntil > now()) {
        logDebug "REST skipped — breaker open for ${((breakerUntil - now()) / 1000).intValue()}s more (${what})"
        return true
    }
    return false
}

// Resets the failure streak and closes the breaker on any confirmed 2xx response.
// Called from the caller's own method frame (NOT from inside an http closure), so the
// state.* writes persist reliably the way Hubitat expects.
private void restSucceeded(int code, boolean bodyParseSkipped = false) {
    state.restFailCount = 0
    state.restBreakerUntil = 0L
    sendEvent(name: "restStatus", value: "ok", isStateChange: false)
    logDebug "REST ok (${code}${bodyParseSkipped ? ', body parse skipped' : ''})"
}

// Tracks consecutive failures and opens the breaker once the threshold is hit.
// Below the threshold: one log.warn per failure (as before). At/above threshold: a
// single log.warn announcing the pause, then debug-only silence until the breaker
// closes. Also called from the caller's own frame for reliable state persistence.
//
// NOTE on concurrency: state.restFailCount is a read-modify-write. Hubitat provides no
// driver-level mutex, and async http callbacks / scheduled jobs can interleave, so if
// two commands fail at the same instant one increment may be lost. The only effect is
// the breaker tripping after ~4 real failures instead of exactly 3. This is acceptable
// for a home vacuum driver and is documented rather than papered over.
private void restFailed(String reason, String url) {
    int fails = ((state.restFailCount ?: 0) as int) + 1
    state.restFailCount = fails
    if (fails < REST_FAIL_THRESHOLD) {
        log.warn "${device.displayName}: REST ${reason} ${url} (failure ${fails}/${REST_FAIL_THRESHOLD})"
        sendEvent(name: "restStatus", value: "error", isStateChange: true)
    } else {
        state.restBreakerUntil = now() + (REST_BREAKER_COOLDOWN_SEC * 1000L)
        log.warn "${device.displayName}: REST ${reason} ${url} — ${fails} consecutive failures, " +
                  "pausing further REST attempts for ${(REST_BREAKER_COOLDOWN_SEC / 60).intValue()} min"
        sendEvent(name: "restStatus", value: "breaker_open", isStateChange: true)
    }
}

// Generic REST PUT to a capability endpoint. body=null sends empty PUT (for /preset/<x> style).
//
// Valetudo returns 200/2xx on success, often with a plain-text or empty body and a
// non-JSON content type. Hubitat's httpPut throws an HttpResponseException for any
// status it considers non-2xx OR when it cannot parse the body as the requested type.
// We therefore (a) tell Hubitat to treat the response body as plain text so it never
// tries to JSON-parse it, and (b) inspect the status code in BOTH the success closure
// and the exception, treating any 2xx as success regardless of body.
//
// The 2xx/failure OUTCOME is captured into locals inside the closure/catch, then the
// success/fail state bookkeeping runs AFTER the http call returns, in this method's own
// frame — Hubitat persists state.* reliably from the method body but not always from
// inside the async http response closure.
private void restPut(String path, Map body) {
    if (restBlocked("PUT ${path}", true)) {
        if (!robotIp?.trim() && settings.restFallback != false)
            log.warn "${device.displayName}: REST command needs Robot IP set — command not sent"
        return
    }
    restPutSend("${restBase()}/capabilities/${path}", body)
}

// Same as restPut() for the few endpoints outside /api/v2/robot/capabilities
// (e.g. /api/v2/events/<id>/interact). Same gate, same breaker, same accounting.
private void restPutUrl(String url, Map body, String what) {
    if (restBlocked(what, true)) return
    restPutSend(url, body)
}

// The shared PUT body of restPut()/restPutUrl(). Unchanged from the pre-3.12.0 restPut().
private void restPutSend(String url, Map body) {
    logDebug "REST PUT ${url} ${body ? JsonOutput.toJson(body) : '(empty)'}"

    boolean ok = false
    int code = 0
    String failReason = null
    try {
        def params = [
            uri:         url,
            timeout:     10,
            contentType: "text/plain",              // how to parse the RESPONSE (avoid JSON parse)
            requestContentType: "application/json"  // content-type of what WE send
        ]
        if (body != null) params.body = JsonOutput.toJson(body)
        httpPut(params) { resp ->
            code = resp?.status ?: 0
            ok = (code >= 200 && code < 300)
            if (!ok) failReason = "PUT non-2xx (${code})"
        }
    } catch (groovyx.net.http.HttpResponseException he) {
        // Hubitat throws here even on some 2xx responses when body parsing differs.
        code = he?.statusCode ?: he?.response?.status ?: 0
        if (code >= 200 && code < 300) ok = true
        else failReason = "PUT failed (${code}) — ${he.message}"
    } catch (e) {
        failReason = "PUT error — ${e.message}"
    }

    if (ok) restSucceeded(code, true)
    else    restFailed(failReason ?: "PUT error", url)
}

// ═══════════════════════════════════════════════════════════════════════════════
// UTILITY
// ═══════════════════════════════════════════════════════════════════════════════

private String baseTopic() { "${settings.topicPrefix}/${settings.robotIdentifier}" }

// ── Type coercion helpers ──────────────────────────────────────────────────────
// Rule Machine custom-action parameters arrive as String, BigDecimal, Integer, or
// Boolean depending on the "parameter type" the user picked (RM offers only
// string/number/decimal — there is NO boolean type). These helpers accept any of
// those forms so commands never throw a MissingMethodException or "Invalid number
// value" regardless of how RM hands the value over.

private int toIntOr(val, int fallback) {
    if (val == null) return fallback
    try { return (val instanceof Number) ? val.intValue() : Double.valueOf(val.toString().trim()).intValue() }
    catch (e) { return fallback }
}

private Integer toIntOrNull(val) {
    if (val == null) return null
    try {
        if (val instanceof Number) return val.intValue()
        return Double.valueOf(val.toString().trim()).intValue()   // tolerate "3000.0"
    } catch (e) { return null }
}

private boolean toBool(val) {
    if (val == null) return false
    if (val instanceof Boolean) return val
    if (val instanceof Number)  return val != 0
    def s = val.toString().trim().toLowerCase()
    return (s == "true" || s == "1" || s == "yes" || s == "on")
}

private void logInfo(String m)  { if (txtEnable) log.info  "${device.displayName}: ${m}" }
private void logDebug(String m) { if (logEnable) log.debug "${device.displayName}: ${m}" }
def disableDebugLogging() {
    log.info "${device.displayName}: Debug logging auto-disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}