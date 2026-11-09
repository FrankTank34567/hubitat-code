// Home Smoke and CO Response
// Version: 1.6.0
// Author: AI-assisted (Claude/Anthropic)
//
// v1.6.0 Changes:
//   - CRITICAL FIX: Lights are now returned to their pre-alarm state when the
//     alarm clears. Previously only night lights were restored. Color lights were
//     left in RGB/HS mode at the emergency color, and every light the alarm turned
//     on stayed on. The circadian apps could not reliably recover them: a light in
//     color mode can still report its old color temperature, which the circadian
//     app reads as "already correct" and skips, and after sunset the circadian
//     app has no 5-minute refresh at all.
//     Every light this app touches (inside color, CT-only, outside, night lights)
//     is snapshotted before the first command: on/off, level, color mode, color
//     temperature, hue and saturation. On clear the app waits the restore delay,
//     restores lights in small paced batches (Hue groups first, then individual
//     bulbs, so a bulb's own state wins over its group's), and only then turns the
//     disable switches back on. The circadian apps therefore resume last and
//     correct any light that is on to the current circadian target.
//   - New: "After the alarm clears" setting. Restore (default) as above, or keep
//     lights on: lights stay on but color lights are switched back to white
//     (color temperature mode) so the circadian apps can take them over.
//   - New: Per-tier brightness settings for inside color lights, CT-only lights,
//     night lights and outside lights. Defaults:
//       Smoke and CO: 100% everywhere. NFPA 101 sets egress lighting minimums
//       (1 fc average, 0.1 fc minimum at the floor), not maximums, and smoke
//       attenuates light, so the most useful setting is full output.
//       Early warning: 70% color and CT lights, 50% night lights. No code or
//       standard covers a pre-alarm air quality warning. These are judgment
//       defaults: visible enough to notice, less startling than an evacuation
//       signal. Adjust to taste.
//   - FIX: A detector "clear" event no longer ends a PM2.5 early warning while a
//     PM2.5 sensor is still above the threshold, and a smoke/CO alarm no longer
//     clears while PM2.5 remains high. One clear path (tryClear) now requires: no
//     detector still active, no PM2.5 sensor above threshold, and no test running.
//   - FIX: A PM2.5 drop now clears a smoke/CO tier once detectors are also clear.
//     Previously only a WARNING tier could be cleared by PM2.5, so a smoke alarm
//     that cleared while PM2.5 was still high could stay active indefinitely.
//   - FIX: PM2.5 now clears at a lower level than it triggers (default 80% of the
//     threshold). Previously it cleared the moment a reading dipped below the
//     trigger (log, Nov 8: triggered at 123, cleared at 89 two minutes later), so
//     smoke hovering near the threshold could flash the whole house on and off.
//   - FIX: Detector "clear" events are ignored while a test is running, so a
//     routine poll cannot end a test early.
//   - FIX: A real detector alarm during a test cancels the 60-second test timer,
//     so the test auto-clear can no longer dismiss a real alarm.
//   - FIX: Escalating tiers (e.g. warning to smoke) cancels the earlier flash loop
//     before starting the new one.
//   - FIX: Clicking Done during an alarm or a pending restore no longer strands the
//     app: a pending restore is restarted and an active alarm's announcement
//     repeat is rescheduled. Previously updated() unscheduled everything, which
//     could leave the disable switches off and the circadian apps paused.
//   - FIX: Flash dim level is capped at half the tier's brightness so the flash
//     stays visible when a lower brightness is configured.
//   - State maps (flashState, activeRooms, lightSnapshot) are now written back as
//     whole maps instead of mutated in place.
//   - Removed the separate night-light snapshot (folded into the light snapshot).
//   - "How This App Works" rewritten to match the new behavior and the research
//     note that lights do not reliably wake sleepers (sound does).
//   - Text: replaced em dashes and non-ASCII arrows.
//
// v1.5.8 Changes:
//   - pm25Device input now allows multiple sensors (multiple: true).
//   - pm25Handler trigger path: any sensor exceeding the threshold activates
//     the warning, using the triggering sensor's displayName as the room.
//   - pm25Handler clear path: reads current values from ALL selected sensors
//     before clearing.
//   - Initialized log reports the count of PM2.5 sensors.
//
// v1.5.7 Changes:
//   - manualTestActive subscription as a second detection path.
//   - Night light snapshot cleared after restore, not before.
//   - state.testActive reset inside clearAllAlarms().
//   - coHandler "tested" triggers TIER_SMOKE, not TIER_CO.
//   - pm25Handler numeric cast wrapped in try/catch.
//   - buildMessage() warns on empty template.
//
// v1.5.6: activeRooms keyed by deviceId.
// v1.5.5: activateTier() ignores re-triggering the active tier.
// v1.5.4: Night light capture/restore with configurable delay.
// v1.5.3: Variable shadowing fixes; flash count 3 -> 2.
// v1.5.2: Announcements fire after flash completes.
// v1.5.1: Announcements moved into delayed light handlers.
// v1.5.0: Full audit; clearAllAlarms() cancels in-flight handlers.
// v1.4.x: Flash pattern and test timer fixes.
// v1.3.0: Flash rewritten as setColor once then setLevel loop.
// v1.2.0: Device lookup and sequencing fixes.
// v1.1.0: Disable switch logic inverted; any-brand detectors.
// v1.0.0: Initial release.
//
// BEFORE INSTALLING:
//   1. Create a virtual switch (e.g. "Smoke Alarm Active") and add it to each
//      circadian app's disable list. Configure circadian apps to disable when
//      the switch is OFF.
//   2. Add the same switch to each Room Lighting instance's disable conditions,
//      with re-enable set to "switch turns on".
//   3. Ensure AirPlay Integration is configured for HomePod announcements.
//   4. Ensure the Hubitat mobile app is installed on phones to notify.
//
// SCOPE NOTE: This app supplements your smoke and CO alarms; it does not replace
// them. NFPA 72 requires a 520 Hz low-frequency signal in sleeping areas because
// it wakes people far more reliably than other sounds or light. Speaker
// announcements and lights are additional cues.
 
import groovy.transform.Field
 
@Field static final Integer LOG_REVERT_SECONDS     = 1800
@Field static final Integer LOG_VERBOSE_THRESHOLD  = 4
@Field static final Integer PM25_DEFAULT_THRESHOLD = 55
@Field static final Integer RESTORE_BATCH_SIZE     = 4     // lights restored per batch
@Field static final Integer RESTORE_BATCH_MS       = 1000  // pause between batches (Hue Bridge pacing)
 
@Field static final String TIER_NONE    = "none"
@Field static final String TIER_WARNING = "warning"
@Field static final String TIER_SMOKE   = "smoke"
@Field static final String TIER_CO      = "co"
 
// Hue values: 0-100 scale (not 0-360). Red = 0, Amber = about 8, White = saturation 0.
// Level is applied per tier from the brightness settings.
@Field static final Map COLOR_RED   = [hue: 0, saturation: 100]
@Field static final Map COLOR_AMBER = [hue: 8, saturation: 100]
@Field static final Map COLOR_WHITE = [hue: 0, saturation: 0]
 
// Default brightness per tier and light group. See v1.6.0 notes for the basis.
@Field static final Map DEFAULT_LEVELS = [
    warning: [color: 70,  ct: 70,  night: 50,  outside: 100],
    smoke:   [color: 100, ct: 100, night: 100, outside: 100],
    co:      [color: 100, ct: 100, night: 100, outside: 100]
]
 
definition(
    name:           "Home Smoke and CO Response",
    namespace:      "homeEmergency",
    author:         "AI-assisted (Claude/Anthropic)",
    description:    "Three-tier emergency response to any Hubitat smoke/CO alarm and PM2.5 air quality spike.",
    category:       "Safety",
    iconUrl:        "",
    iconX2Url:      "",
    singleThreaded: true
)
 
preferences {
    page(name: "mainPage")
    page(name: "warningPage")
    page(name: "smokePage")
    page(name: "coPage")
    page(name: "testPage")
}
 
// ---------------------------------------------------------------------------
// UI Pages
// ---------------------------------------------------------------------------
 
def mainPage() {
    dynamicPage(name: "mainPage", title: "Home Smoke and CO Response", install: true, uninstall: true) {
 
        section("<h2>Sensors to Monitor</h2>") {
            input name: "protectDevices",
                  type: "capability.smokeDetector",
                  title: "Smoke detector devices (any brand)",
                  multiple: true, required: false
 
            input name: "coDevices",
                  type: "capability.carbonMonoxideDetector",
                  title: "Carbon monoxide detector devices (any brand)",
                  description: "Can be the same devices as above if your detector reports both",
                  multiple: true, required: false
 
            input name: "pm25Device",
                  type: "capability.airQuality",
                  title: "Air quality sensor(s) for PM2.5 early warning (optional, any brand)",
                  multiple: true, required: false
 
            input name: "pm25Threshold",
                  type: "number",
                  title: "PM2.5 early warning threshold (µg/m³, default 55)",
                  defaultValue: PM25_DEFAULT_THRESHOLD,
                  range: "20..500",
                  required: false
 
            input name: "pm25ClearPercent",
                  type: "number",
                  title: "Clear the warning when every sensor is below this % of the threshold (default 80)",
                  description: "A gap between the on and off points stops the warning flapping when readings hover near the threshold.",
                  defaultValue: 80,
                  range: "50..100",
                  required: false
        }
 
        section("<h2>Circadian / Room Lighting Disable Switch</h2>") {
            paragraph "Select the virtual switch(es) that disable your circadian apps and Room Lighting instances. " +
                      "These switches turn OFF when an alarm fires (disabling circadian apps) and back ON after the " +
                      "lights have been restored. Configure your circadian apps to disable when this switch is OFF."
            input name: "disableSwitches",
                  type: "capability.switch",
                  title: "Disable switch(es)",
                  multiple: true, required: false
        }
 
        section("<h2>After the Alarm Clears</h2>") {
            input name: "afterClear",
                  type: "enum",
                  title: "What should the lights do when the alarm clears?",
                  options: ["restore": "Return every light to how it was before the alarm (recommended)",
                            "keepOn":  "Leave lights on, switch color lights back to white for the circadian apps"],
                  defaultValue: "restore",
                  required: true
            input name: "nightLightRestoreDelay",
                  type: "number",
                  title: "Wait before restoring (seconds, default 5)",
                  description: "Gives the Hue Bridge time to finish the alarm commands before restore commands arrive.",
                  defaultValue: 5,
                  range: "1..30",
                  required: false
        }
 
        section("<h2>Flash Settings</h2>") {
            paragraph "Color lights flash 2 times before holding the emergency color. " +
                      "Color is set once with setColor(), then setLevel() alternates between the tier's brightness " +
                      "and the dim level. Outside lights always stay solid. " +
                      "For best results, select Hue Groups rather than individual bulbs " +
                      "to avoid sequential \"popcorn\" delays across the Hue Bridge."
            input name: "flashIntervalSeconds",
                  type: "enum",
                  title: "Time between flash steps",
                  options: ["1": "1 second", "2": "2 seconds", "3": "3 seconds", "4": "4 seconds", "5": "5 seconds"],
                  defaultValue: "2",
                  required: true
            input name: "flashDimLevel",
                  type: "number",
                  title: "Dim level during flash (0-30%, capped at half the tier's brightness)",
                  defaultValue: 10,
                  range: "0..30",
                  required: true
        }
 
        section("<h2>Response Settings</h2>") {
            href name: "toWarningPage", page: "warningPage",
                 title: "Tier 1 - Early Warning (PM2.5)",
                 description: "Amber lights, gentle announcement"
            href name: "toSmokePage", page: "smokePage",
                 title: "Tier 2 - Smoke Alarm",
                 description: "Red lights, evacuate announcement"
            href name: "toCOPage", page: "coPage",
                 title: "Tier 3 - CO Alarm",
                 description: "White lights, leave immediately announcement"
        }
 
        section("<h2>Test</h2>") {
            href name: "toTestPage", page: "testPage",
                 title: "Test Response",
                 description: "Trigger a test of each alarm tier"
        }
 
        section("<h2>How This App Works</h2>", hideable: true, hidden: true) {
            paragraph "<b>Alarm sequence:</b><br>" +
                "1. A detector or PM2.5 sensor fires. The disable switches turn off so the circadian apps stop.<br>" +
                "2. Two seconds later the app records the current state of every light it is about to change " +
                "(on/off, brightness, color mode, color temperature, color).<br>" +
                "3. Outside lights go solid; inside color lights flash 2 times then hold the emergency color; " +
                "CT-only lights and night lights turn on. Brightness comes from each tier's settings.<br>" +
                "4. After the flash: speaker announcement and push notifications, repeated on your schedule.<br><br>" +
                "<b>Clearing:</b> the alarm clears only when every detector reports clear, every PM2.5 sensor is " +
                "below the clear level (80% of the threshold by default), and no test is running. The app then waits the restore delay, returns " +
                "lights to their pre-alarm state a few at a time (or leaves them on in white, per your setting), " +
                "and turns the disable switches back on last. The circadian apps then bring any light that is on " +
                "to the current circadian target.<br><br>" +
                "<b>Brightness defaults:</b> Smoke and CO use 100%. Egress lighting codes (NFPA 101) set minimums, " +
                "not maximums, and smoke absorbs light. The early warning uses 70% (50% for night lights) because " +
                "no standard covers a pre-alarm air quality warning; it is meant to be noticed, not to trigger an " +
                "evacuation.<br><br>" +
                "<b>Lights do not wake people reliably.</b> Research on sleeping occupants found most slept through " +
                "strobe lights, while a 520 Hz alarm sound woke over 90%. Lights here are for finding the way out; " +
                "your smoke and CO alarms do the waking.<br><br>" +
                "<b>Priority:</b> CO (Tier 3) > Smoke (Tier 2) > PM2.5 Warning (Tier 1). A lower-priority event " +
                "never downgrades a higher one.<br><br>" +
                "<b>Tests:</b> run for 60 seconds then clear and restore. Detector clear events and PM2.5 readings " +
                "are ignored during a test. A real alarm during a test cancels the test timer."
        }
 
        section("<h2>Logging</h2>") {
            input name: "logLevel",
                  type: "enum",
                  title: "Log level (Debug and Trace revert to Info after 30 minutes)",
                  options: ["0": "None", "1": "Error", "2": "Warn", "3": "Info", "4": "Debug", "5": "Trace"],
                  defaultValue: "3", required: true
        }
    }
}
 
// Brightness inputs shared by the three tier pages.
private void brightnessSection(String prefix, String tier, boolean hasOutside) {
    Map d = DEFAULT_LEVELS[tier]
    section("<h2>Brightness</h2>") {
        if (tier == TIER_WARNING) {
            paragraph "No code or standard covers a pre-alarm air quality warning. Defaults are set to be noticed " +
                      "without feeling like an evacuation signal."
        }
        else {
            paragraph "Defaults are 100%. Egress lighting codes set minimums, not maximums, and smoke absorbs light."
        }
        input name: "${prefix}ColorLevel", type: "number", title: "Inside color lights (default ${d.color}%)",
              defaultValue: d.color, range: "1..100", required: false
        input name: "${prefix}CTLevel", type: "number", title: "Inside CT-only lights (default ${d.ct}%)",
              defaultValue: d.ct, range: "1..100", required: false
        input name: "${prefix}NightLevel", type: "number", title: "Night lights (default ${d.night}%)",
              defaultValue: d.night, range: "1..100", required: false
        if (hasOutside) {
            input name: "${prefix}OutsideLevel", type: "number", title: "Outside lights (default ${d.outside}%)",
                  defaultValue: d.outside, range: "1..100", required: false
        }
    }
}
 
def warningPage() {
    dynamicPage(name: "warningPage", title: "Tier 1 - Early Warning", install: false, uninstall: false) {
 
        section("<h2>Lights</h2>") {
            input name: "warnColorLights",
                  type: "capability.colorControl",
                  title: "Inside color lights (will flash amber then hold)",
                  multiple: true, required: false
            input name: "warnCTLights",
                  type: "capability.colorTemperature",
                  title: "Inside CT-only lights (will turn on)",
                  description: "Select white-only bulbs here. Color-capable bulbs go in the color lights list above.",
                  multiple: true, required: false
            input name: "warnNightLights",
                  type: "capability.switchLevel",
                  title: "Night lights / hall lights (will turn on)",
                  multiple: true, required: false
        }
 
        brightnessSection("warn", TIER_WARNING, false)
 
        section("<h2>Announcements</h2>") {
            input name: "warnSpeakers",
                  type: "capability.speechSynthesis",
                  title: "Speakers for announcement",
                  multiple: true, required: false
            input name: "warnVolume",
                  type: "number",
                  title: "Announcement volume (0-100)",
                  defaultValue: 80, range: "0..100", required: false
            input name: "warnMessage",
                  type: "text",
                  title: "Announcement message",
                  defaultValue: "Attention. Elevated smoke particles detected. Please check for a smoke source.",
                  required: false
            input name: "warnRepeatMinutes",
                  type: "enum",
                  title: "Repeat announcement every...",
                  options: ["0": "Do not repeat", "1": "1 minute", "2": "2 minutes", "5": "5 minutes"],
                  defaultValue: "2", required: false
        }
 
        section("<h2>Notifications</h2>") {
            input name: "warnNotifyDevices",
                  type: "capability.notification",
                  title: "Send push notification to these devices",
                  multiple: true, required: false
            input name: "warnNotifyMessage",
                  type: "text",
                  title: "Push notification message",
                  defaultValue: "Early Warning: Elevated smoke particles detected. Check your home for a smoke source.",
                  required: false
        }
    }
}
 
def smokePage() {
    dynamicPage(name: "smokePage", title: "Tier 2 - Smoke Alarm", install: false, uninstall: false) {
 
        section("<h2>Lights</h2>") {
            input name: "smokeColorLights",
                  type: "capability.colorControl",
                  title: "Inside color lights (will flash red then hold)",
                  multiple: true, required: false
            input name: "smokeCTLights",
                  type: "capability.colorTemperature",
                  title: "Inside CT-only lights (will turn on)",
                  description: "Select white-only bulbs here.",
                  multiple: true, required: false
            input name: "smokeNightLights",
                  type: "capability.switchLevel",
                  title: "Night lights / hall lights (will turn on)",
                  multiple: true, required: false
            input name: "smokeOutsideLights",
                  type: "capability.colorControl",
                  title: "Outside color lights (solid red, no flash)",
                  multiple: true, required: false
        }
 
        brightnessSection("smoke", TIER_SMOKE, true)
 
        section("<h2>Announcements</h2>") {
            input name: "smokeSpeakers",
                  type: "capability.speechSynthesis",
                  title: "Speakers for announcement",
                  multiple: true, required: false
            input name: "smokeVolume",
                  type: "number",
                  title: "Announcement volume (0-100)",
                  defaultValue: 100, range: "0..100", required: false
            input name: "smokeMessage",
                  type: "text",
                  title: "Announcement message (%room% = room name)",
                  defaultValue: "Smoke alarm. Smoke detected in %room%. Evacuate immediately. Stay low and crawl to the nearest exit.",
                  required: false
            input name: "smokeRepeatMinutes",
                  type: "enum",
                  title: "Repeat announcement every...",
                  options: ["0": "Do not repeat", "1": "1 minute", "2": "2 minutes", "5": "5 minutes"],
                  defaultValue: "1", required: false
        }
 
        section("<h2>Notifications</h2>") {
            input name: "smokeNotifyDevices",
                  type: "capability.notification",
                  title: "Send push notification to these devices",
                  multiple: true, required: false
            input name: "smokeNotifyMessage",
                  type: "text",
                  title: "Push notification message (%room% = room name)",
                  defaultValue: "SMOKE ALARM: Smoke detected in %room%. Evacuate immediately.",
                  required: false
        }
    }
}
 
def coPage() {
    dynamicPage(name: "coPage", title: "Tier 3 - CO Alarm", install: false, uninstall: false) {
 
        section("<h2>Lights</h2>") {
            input name: "coColorLights",
                  type: "capability.colorControl",
                  title: "Inside color lights (will flash white then hold)",
                  multiple: true, required: false
            input name: "coCTLights",
                  type: "capability.colorTemperature",
                  title: "Inside CT-only lights (will turn on)",
                  description: "Select white-only bulbs here.",
                  multiple: true, required: false
            input name: "coNightLights",
                  type: "capability.switchLevel",
                  title: "Night lights / hall lights (will turn on)",
                  multiple: true, required: false
            input name: "coOutsideLights",
                  type: "capability.colorControl",
                  title: "Outside color lights (solid red, no flash)",
                  multiple: true, required: false
        }
 
        brightnessSection("co", TIER_CO, true)
 
        section("<h2>Announcements</h2>") {
            input name: "coSpeakers",
                  type: "capability.speechSynthesis",
                  title: "Speakers for announcement",
                  multiple: true, required: false
            input name: "coVolume",
                  type: "number",
                  title: "Announcement volume (0-100)",
                  defaultValue: 100, range: "0..100", required: false
            input name: "coMessage",
                  type: "text",
                  title: "Announcement message (%room% = room name)",
                  defaultValue: "Carbon monoxide alarm. Carbon monoxide detected in %room%. Leave the house immediately. Do not stop for anything. Go outside now.",
                  required: false
            input name: "coRepeatMinutes",
                  type: "enum",
                  title: "Repeat announcement every...",
                  options: ["0": "Do not repeat", "1": "1 minute", "2": "2 minutes", "5": "5 minutes"],
                  defaultValue: "1", required: false
        }
 
        section("<h2>Notifications</h2>") {
            input name: "coNotifyDevices",
                  type: "capability.notification",
                  title: "Send push notification to these devices",
                  multiple: true, required: false
            input name: "coNotifyMessage",
                  type: "text",
                  title: "Push notification message (%room% = room name)",
                  defaultValue: "CO ALARM: Carbon monoxide detected in %room%. Leave the house immediately.",
                  required: false
        }
    }
}
 
def testPage() {
    dynamicPage(name: "testPage", title: "Test Response", install: false, uninstall: false) {
        section {
            paragraph "Use these buttons to test each alarm tier. The response runs for 60 seconds, then clears and restores the lights. Press Clear Test at any time to stop early."
            input name: "testWarning", type: "button", title: "Test Tier 1 - Early Warning"
            input name: "testSmoke",   type: "button", title: "Test Tier 2 - Smoke Alarm"
            input name: "testCO",      type: "button", title: "Test Tier 3 - CO Alarm"
            input name: "testClear",   type: "button", title: "Clear Test (restore lights)"
        }
        if (state.testActive) {
            section {
                paragraph "Test in progress: ${state.activeTier?.toUpperCase()}. It clears automatically after 60 seconds, or press Clear Test above."
            }
        }
        else if (state.restorePending) {
            section {
                paragraph "Restoring lights. The disable switches turn back on when the restore finishes."
            }
        }
    }
}
 
// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------
 
def installed() {
    logInfo "installed()"
    state.activeTier     = TIER_NONE
    state.activeRooms    = [:]
    state.testActive     = false
    state.flashState     = [:]
    state.lightSnapshot  = null
    state.restorePending = false
    scheduleLogRevertIfNeeded()
    initialize()
}
 
def updated() {
    logInfo "updated()"
    unschedule()   // cancel ALL scheduled methods; anything still needed is rescheduled in initialize()
    scheduleLogRevertIfNeeded()
    initialize()
}
 
def uninstalled() {
    logInfo "uninstalled()"
    // No restore after uninstall (scheduled handlers would never run). Just hand control back.
    clearAllAlarms(false)
}
 
private void initialize() {
    unsubscribe()
 
    if (protectDevices) {
        subscribe(protectDevices, "smoke", "smokeHandler")
        protectDevices.each { dev ->
            if (dev.hasAttribute("smokeStateDetail")) {
                subscribe(dev, "smokeStateDetail", "smokeDetailHandler")
                logDebug "Subscribed to smokeStateDetail for ${dev.displayName}"
            }
            // manualTestActive is a second trigger path for the physical test button,
            // which only reports for about 10 seconds and can be missed by a poll.
            if (dev.hasAttribute("manualTestActive")) {
                subscribe(dev, "manualTestActive", "manualTestHandler")
                logDebug "Subscribed to manualTestActive for ${dev.displayName}"
            }
        }
    }
 
    if (coDevices) {
        subscribe(coDevices, "carbonMonoxide", "coHandler")
    }
 
    if (pm25Device) {
        subscribe(pm25Device, "pm25", "pm25Handler")
    }
 
    // Upgrade from v1.5.x: the separate night light snapshot is replaced by lightSnapshot.
    state.remove("nightLightSnapshot")
 
    // updated() cancelled every schedule. Restart anything that was in flight.
    if (state.restorePending) {
        logInfo "Settings saved during a restore - restarting the restore"
        runIn(2, "restoreLightsStart")
    }
    else if (state.activeTier && state.activeTier != TIER_NONE) {
        logInfo "Settings saved during an active ${state.activeTier} alarm - rescheduling announcement repeats"
        scheduleRepeat("repeatAnnouncement", repeatSettingFor(state.activeTier),
                       [tier: state.activeTier, room: (state.activeRooms?.values()?.join(", ") ?: "unknown")])
    }
 
    def manualTestCount = protectDevices?.count { it.hasAttribute("manualTestActive") } ?: 0
    logInfo "Initialized. Monitoring: ${protectDevices?.size() ?: 0} smoke detectors (${manualTestCount} with manualTestActive), ${coDevices?.size() ?: 0} CO detectors, ${pm25Device?.size() ?: 0} PM2.5 sensor(s)"
}
 
// ---------------------------------------------------------------------------
// Button handler (test page)
// ---------------------------------------------------------------------------
 
def appButtonHandler(btn) {
    switch (btn) {
        case "testWarning":
            logInfo "Test: triggering Early Warning"
            state.testActive = true
            activateTier(TIER_WARNING, "Test Location")
            runIn(60, "clearTestAlarm")
            break
        case "testSmoke":
            logInfo "Test: triggering Smoke Alarm"
            state.testActive = true
            activateTier(TIER_SMOKE, "Test Room")
            runIn(60, "clearTestAlarm")
            break
        case "testCO":
            logInfo "Test: triggering CO Alarm"
            state.testActive = true
            activateTier(TIER_CO, "Test Room")
            runIn(60, "clearTestAlarm")
            break
        case "testClear":
            logInfo "Test: manual clear"
            clearTestAlarm()
            break
    }
}
 
def clearTestAlarm() {
    if (state.activeRooms) {
        // A real detector is still active (cancelRealAlarmTest normally prevents this).
        logWarn "Test clear skipped: a real detector is still reporting an alarm"
        state.testActive = false
        return
    }
    logInfo "Test cleared"
    clearAllAlarms()   // clearAllAlarms() resets state.testActive
}
 
// ---------------------------------------------------------------------------
// Event handlers
// ---------------------------------------------------------------------------
 
def smokeHandler(evt) {
    logDebug "smokeHandler: ${evt.displayName} = ${evt.value}"
    if (evt.value == "detected" || evt.value == "tested") {
        addActiveRoom(evt.deviceId.toString(), evt.displayName)
        if (state.activeTier != TIER_CO) {
            activateTier(TIER_SMOKE, evt.displayName)
        }
    }
    else if (evt.value == "clear") {
        removeActiveRoom(evt.deviceId.toString())
        tryClear("smoke clear from ${evt.displayName}")
    }
}
 
def smokeDetailHandler(evt) {
    logDebug "smokeDetailHandler: ${evt.displayName} = ${evt.value}"
    if (evt.value == "warn" || evt.value == "emergency") {
        addActiveRoom(evt.deviceId.toString(), evt.displayName)
        if (state.activeTier != TIER_CO) {
            activateTier(TIER_SMOKE, evt.displayName)
        }
    }
    else if (evt.value == "ok") {
        removeActiveRoom(evt.deviceId.toString())
        tryClear("smoke detail ok from ${evt.displayName}")
    }
}
 
def coHandler(evt) {
    logDebug "coHandler: ${evt.displayName} = ${evt.value}"
    if (evt.value == "detected") {
        addActiveRoom(evt.deviceId.toString(), evt.displayName)
        activateTier(TIER_CO, evt.displayName)
    }
    else if (evt.value == "tested") {
        // A CO hardware test is not CO present: use the smoke tier, not a full CO evacuation.
        addActiveRoom(evt.deviceId.toString(), evt.displayName)
        if (state.activeTier != TIER_CO) {
            activateTier(TIER_SMOKE, evt.displayName)
        }
    }
    else if (evt.value == "clear") {
        removeActiveRoom(evt.deviceId.toString())
        tryClear("CO clear from ${evt.displayName}")
    }
}
 
// manualTestActive = true when the Nest Protect test button is pressed. "false" is
// intentionally not handled: the smoke/smokeStateDetail clear path ends the alarm so
// the full flash and announcement sequence completes first.
def manualTestHandler(evt) {
    logDebug "manualTestHandler: ${evt.displayName} = ${evt.value}"
    if (evt.value == "true") {
        logInfo "Manual test detected on ${evt.displayName} via manualTestActive"
        addActiveRoom(evt.deviceId.toString(), evt.displayName)
        if (state.activeTier != TIER_CO) {
            activateTier(TIER_SMOKE, evt.displayName)
        }
    }
}
 
def pm25Handler(evt) {
    logDebug "pm25Handler: ${evt.displayName} = ${evt.value} µg/m³"
    Integer value
    try {
        value = (evt.value as BigDecimal).toInteger()
    }
    catch (Exception e) {
        logWarn "pm25Handler: non-numeric value '${evt.value}' from ${evt.displayName} - ignoring"
        return
    }
 
    if (value >= pm25ThresholdValue()) {
        // Any sensor over the threshold starts the warning if nothing is active yet
        if (state.activeTier == TIER_NONE) {
            logInfo "PM2.5 threshold exceeded: ${value} µg/m³ on ${evt.displayName} (threshold: ${pm25ThresholdValue()})"
            activateTier(TIER_WARNING, evt.displayName)
        }
    }
    else if (value < pm25ClearValue() && state.activeTier != TIER_NONE) {
        tryClear("PM2.5 on ${evt.displayName} fell to ${value} µg/m³")
    }
}
 
// ---------------------------------------------------------------------------
// Clear decision
// ---------------------------------------------------------------------------
 
private Integer pm25ThresholdValue() {
    return (pm25Threshold ?: PM25_DEFAULT_THRESHOLD) as Integer
}
 
// Level every sensor must fall below before the warning clears (hysteresis).
private Integer pm25ClearValue() {
    Integer pct = Math.max(50, Math.min(100, ((pm25ClearPercent != null) ? pm25ClearPercent : 80) as Integer))
    return (int) Math.floor(pm25ThresholdValue() * pct / 100.0)
}
 
// True when any selected PM2.5 sensor still reads at or above the clear level.
// An unreadable sensor is treated as not above, so a dead sensor cannot hold an alarm forever.
private boolean pm25AnyAbove() {
    Integer threshold = pm25ClearValue()
    return pm25Device?.any { sensor ->
        try {
            def reading = sensor.currentValue("pm25")
            (reading != null) && ((reading as BigDecimal).toInteger() >= threshold)
        }
        catch (Exception e) {
            logWarn "Could not read PM2.5 from ${sensor.displayName}: ${e.message}"
            false
        }
    } ?: false
}
 
// The single path that ends a sensor-driven alarm. Requires: no test running,
// no detector still active, and no PM2.5 sensor still above the threshold.
private void tryClear(String reason) {
    if (state.activeTier == TIER_NONE) {
        return
    }
    if (state.testActive) {
        logDebug "tryClear (${reason}): test running - ignoring sensor clear"
        return
    }
    if (state.activeRooms) {
        logDebug "tryClear (${reason}): detectors still active: ${state.activeRooms}"
        return
    }
    if (pm25AnyAbove()) {
        logInfo "tryClear (${reason}): holding, a PM2.5 sensor is still at or above the clear level (${pm25ClearValue()} µg/m³)"
        return
    }
    logInfo "All sensors clear (${reason}) - ending alarm"
    clearAllAlarms()
}
 
// ---------------------------------------------------------------------------
// Tier activation
// ---------------------------------------------------------------------------
 
private void activateTier(String tier, String roomName) {
    // Prevent downgrade to lower priority
    if (tier == TIER_WARNING && state.activeTier in [TIER_SMOKE, TIER_CO]) {
        logDebug "activateTier: ignoring ${tier} - higher priority alarm active"
        return
    }
    if (tier == TIER_SMOKE && state.activeTier == TIER_CO) {
        logDebug "activateTier: ignoring ${tier} - CO alarm active"
        return
    }
    // Several events from one alarm (e.g. smoke "tested" plus smokeStateDetail "warn")
    // must not start duplicate light and announcement sequences.
    if (tier == state.activeTier) {
        logDebug "activateTier: ignoring ${tier} - already active"
        return
    }
 
    // A new alarm during a restore: stop restoring. The snapshot still holds the true
    // pre-alarm state, so nothing is lost.
    if (state.restorePending) {
        logInfo "New alarm during restore - restore cancelled"
        unschedule("restoreLightsStart")
        unschedule("restoreStep")
        unschedule("finishRestore")
        state.restorePending = false
        state.restoreQueue   = null
    }
 
    // Escalation: stop the previous tier's flash loop before starting a new one.
    unschedule("flashStep")
    unschedule("announceAfterFlash")
    state.flashState = [:]
 
    logInfo "Activating ${tier.toUpperCase()} response for: ${roomName}"
    state.activeTier  = tier
    state.pendingRoom = roomName
 
    // Disable circadian apps first: they react to light-on events and would overwrite
    // the emergency colors. Light commands wait 2 seconds so they process the switch.
    enableDisableSwitches(false)
    switch (tier) {
        case TIER_WARNING: runIn(2, "activateWarningLights"); break
        case TIER_SMOKE:   runIn(2, "activateSmokeLights");   break
        case TIER_CO:      runIn(2, "activateCOLights");      break
    }
}
 
def activateWarningLights() {
    captureLightState([warnColorLights, warnCTLights, warnNightLights])
    flashAndHold(warnColorLights, COLOR_AMBER + [level: tierLevel(TIER_WARNING, "color")])
    turnOnLights(warnCTLights, tierLevel(TIER_WARNING, "ct"))
    turnOnLights(warnNightLights, tierLevel(TIER_WARNING, "night"))
}
 
def activateSmokeLights() {
    captureLightState([smokeColorLights, smokeCTLights, smokeNightLights, smokeOutsideLights])
    setOutsideLights(smokeOutsideLights, COLOR_RED + [level: tierLevel(TIER_SMOKE, "outside")])
    flashAndHold(smokeColorLights, COLOR_RED + [level: tierLevel(TIER_SMOKE, "color")])
    turnOnLights(smokeCTLights, tierLevel(TIER_SMOKE, "ct"))
    turnOnLights(smokeNightLights, tierLevel(TIER_SMOKE, "night"))
}
 
def activateCOLights() {
    captureLightState([coColorLights, coCTLights, coNightLights, coOutsideLights])
    setOutsideLights(coOutsideLights, COLOR_RED + [level: tierLevel(TIER_CO, "outside")])
    flashAndHold(coColorLights, COLOR_WHITE + [level: tierLevel(TIER_CO, "color")])
    turnOnLights(coCTLights, tierLevel(TIER_CO, "ct"))
    turnOnLights(coNightLights, tierLevel(TIER_CO, "night"))
}
 
// Brightness for a tier and light group ("color", "ct", "night", "outside"), from
// settings or the defaults, clamped to 1-100.
private Integer tierLevel(String tier, String kind) {
    String prefix = [(TIER_WARNING): "warn", (TIER_SMOKE): "smoke", (TIER_CO): "co"][tier]
    String suffix = [color: "ColorLevel", ct: "CTLevel", night: "NightLevel", outside: "OutsideLevel"][kind]
    def configured = settings["${prefix}${suffix}".toString()]
    Integer level = (configured != null) ? (configured as Integer) : (DEFAULT_LEVELS[tier][kind] as Integer)
    return Math.max(1, Math.min(100, level))
}
 
// ---------------------------------------------------------------------------
// Repeat announcement scheduler
// ---------------------------------------------------------------------------
 
def repeatAnnouncement(data) {
    if (state.activeTier == TIER_NONE) return
 
    def tier     = data?.tier ?: state.activeTier
    def roomName = data?.room ?: (state.activeRooms?.values()?.join(", ") ?: "unknown")
 
    logDebug "repeatAnnouncement: tier=${tier} room=${roomName}"
 
    switch (tier) {
        case TIER_WARNING:
            announceOnSpeakers(warnSpeakers, warnMessage ?: "Attention. Elevated smoke particles detected.", warnVolume)
            break
        case TIER_SMOKE:
            def smokeRepeatMsg = buildMessage(smokeMessage ?: "Smoke alarm. Smoke detected in %room%. Evacuate immediately.", roomName)
            announceOnSpeakers(smokeSpeakers, smokeRepeatMsg, smokeVolume)
            break
        case TIER_CO:
            def coRepeatMsg = buildMessage(coMessage ?: "Carbon monoxide alarm. Leave the house immediately.", roomName)
            announceOnSpeakers(coSpeakers, coRepeatMsg, coVolume)
            break
    }
 
    scheduleRepeat("repeatAnnouncement", repeatSettingFor(tier), [tier: tier, room: roomName])
}
 
private repeatSettingFor(String tier) {
    switch (tier) {
        case TIER_WARNING: return warnRepeatMinutes
        case TIER_SMOKE:   return smokeRepeatMinutes
        case TIER_CO:      return coRepeatMinutes
        default:           return "0"
    }
}
 
// ---------------------------------------------------------------------------
// Clear / restore
//
// Order matters. The circadian apps must resume LAST, after the lights are back
// in color temperature mode, so their first sweep corrects lights that are on to
// the current circadian target instead of being overwritten by the restore.
//   clearAllAlarms -> (restore delay) -> restoreLightsStart -> restoreStep batches
//   -> finishRestore: disable switches back on
// ---------------------------------------------------------------------------
 
private void clearAllAlarms(boolean restore = true) {
    // Cancel in-flight handlers so no alarm command fires after the clear
    unschedule("repeatAnnouncement")
    unschedule("activateWarningLights")
    unschedule("activateSmokeLights")
    unschedule("activateCOLights")
    unschedule("flashStep")
    unschedule("clearTestAlarm")
    unschedule("announceAfterFlash")
    unschedule("restoreLightsStart")
    unschedule("restoreStep")
    unschedule("finishRestore")
 
    state.activeTier  = TIER_NONE
    state.activeRooms = [:]
    state.flashState  = [:]
    state.testActive  = false
 
    if (restore && state.lightSnapshot) {
        Integer delaySecs = (nightLightRestoreDelay ?: 5) as Integer
        state.restorePending = true
        logInfo "Alarm cleared. Restoring ${state.lightSnapshot.size()} light(s) in ${delaySecs}s, then re-enabling circadian apps."
        runIn(delaySecs, "restoreLightsStart")
    }
    else {
        finishRestore()
    }
}
 
def restoreLightsStart() {
    Map snapshot = (state.lightSnapshot ?: [:]) as Map
    if (!snapshot) {
        finishRestore()
        return
    }
    // Groups first, then individual lights, so a bulb's own state wins over its group's.
    List groups = snapshot.findAll { k, v -> v.isGroup }.keySet().toList()
    List others = snapshot.findAll { k, v -> !v.isGroup }.keySet().toList()
    state.restoreQueue = groups + others
    restoreStep()
}
 
def restoreStep() {
    List queue = (state.restoreQueue ?: []) as List
    if (!queue) {
        // Short pause so the bridge applies the last batch before circadian apps sweep
        runIn(2, "finishRestore")
        return
    }
    List batch = queue.take(RESTORE_BATCH_SIZE)
    state.restoreQueue = queue.drop(RESTORE_BATCH_SIZE)
 
    Map snapshot = (state.lightSnapshot ?: [:]) as Map
    Map devices  = managedDevicesByDni()
    boolean keepOn = (afterClear == "keepOn")
    batch.each { dni ->
        def device = devices[dni]
        Map saved  = snapshot[dni] as Map
        if (!device || !saved) {
            logWarn "restore: device not found for ${saved?.label ?: dni}"
            return
        }
        try {
            if (keepOn) {
                returnToWhite(device, saved)
            }
            else {
                restoreDevice(device, saved)
            }
        }
        catch (Exception e) {
            logWarn "restore error for ${device.displayName}: ${e.message}"
        }
    }
    runInMillis(RESTORE_BATCH_MS, "restoreStep")
}
 
// Puts one light back exactly as it was before the alarm.
private void restoreDevice(device, Map saved) {
    if (saved.switch != "on") {
        device.off()
        logDebug "restore: ${device.displayName} -> off"
        return
    }
    Integer level = (saved.level != null) ? (saved.level as Integer) : null
    boolean wasColor = saved.colorMode && saved.colorMode != "CT"
    if (wasColor && device.hasCommand("setColor") && saved.hue != null && saved.saturation != null) {
        // Light was showing a color before the alarm (scene etc.): put that color back
        Map c = [hue: saved.hue as Integer, saturation: saved.saturation as Integer]
        if (level) c.level = level
        device.setColor(c)
        logDebug "restore: ${device.displayName} -> color ${c}"
        return
    }
    if (saved.ct && device.hasCommand("setColorTemperature")) {
        // Also takes a color bulb out of RGB/HS mode
        device.setColorTemperature(saved.ct as Integer)
    }
    if (level && device.hasCommand("setLevel")) {
        device.setLevel(level)
    }
    else {
        device.on()
    }
    logDebug "restore: ${device.displayName} -> on, ${saved.ct ?: '-'}K, ${level ?: '-'}%"
}
 
// "Keep lights on" mode: leave the light on, but take color bulbs out of color mode
// so the circadian apps can manage them again.
private void returnToWhite(device, Map saved) {
    if (device.hasCommand("setColorTemperature") && device.currentValue("colorMode") && device.currentValue("colorMode") != "CT") {
        device.setColorTemperature((saved.ct ?: 2700) as Integer)
        logDebug "keep on: ${device.displayName} -> white ${saved.ct ?: 2700}K"
    }
}
 
def finishRestore() {
    unschedule("restoreStep")
    state.restorePending = false
    state.restoreQueue   = null
    state.lightSnapshot  = null
    enableDisableSwitches(true)
    logInfo "Response finished. Disable switches ON - circadian apps resume now."
}
 
// ---------------------------------------------------------------------------
// Light state capture
//
// Called from each tier's light handler before any light command. Adds only lights
// not already recorded, so escalating from Tier 1 to Tier 2 keeps the true
// pre-alarm state of lights the warning already changed, while still recording
// lights only the higher tier touches (e.g. outside lights).
// ---------------------------------------------------------------------------
 
private void captureLightState(List lists) {
    Map snapshot = (state.lightSnapshot ?: [:]) as Map
    int added = 0
    lists.each { list ->
        list?.each { light ->
            String dni = light.deviceNetworkId
            if (snapshot.containsKey(dni)) return
            try {
                snapshot[dni] = [
                    switch    : light.currentValue("switch") ?: "off",
                    level     : light.currentValue("level"),
                    colorMode : light.currentValue("colorMode"),
                    ct        : light.currentValue("colorTemperature"),
                    hue       : light.currentValue("hue"),
                    saturation: light.currentValue("saturation"),
                    label     : light.displayName,
                    isGroup   : (light.typeName ?: "").toLowerCase().contains("group")
                ]
                added++
            }
            catch (Exception e) {
                logWarn "captureLightState error for ${light.displayName}: ${e.message}"
            }
        }
    }
    state.lightSnapshot = snapshot
    logInfo "Light state captured for ${added} new device(s), ${snapshot.size()} total"
}
 
// Every device this app can command, keyed by DNI.
private Map managedDevicesByDni() {
    Map byDni = [:]
    [warnColorLights, warnCTLights, warnNightLights,
     smokeColorLights, smokeCTLights, smokeNightLights, smokeOutsideLights,
     coColorLights, coCTLights, coNightLights, coOutsideLights].each { list ->
        list?.each { d -> if (d && !byDni.containsKey(d.deviceNetworkId)) byDni[d.deviceNetworkId] = d }
    }
    return byDni
}
 
// ---------------------------------------------------------------------------
// Flash logic
//
// Inside color lights:
//   T+0    setColor(emergency color at the tier's brightness)
//   T+N    setLevel(dim)    [step 0]
//   T+2N   setLevel(full)   [step 1]
//   T+3N   setLevel(dim)    [step 2]
//   T+4N   setLevel(full)   [step 3]
//   T+5N   hold at full, then announcements + notifications
// N = flashIntervalSeconds (default 2). Outside lights are set solid separately.
// ---------------------------------------------------------------------------
 
private void flashAndHold(lights, Map color) {
    if (!lights) return
 
    String flashKey = "flash_${now()}".toString()
    Map all = (state.flashState ?: [:]) as Map
    all[flashKey] = [
        color     : color,
        dnis      : lights.collect { it.deviceNetworkId },
        flashStep : 0,
        totalSteps: 4    // 2 flashes x 2 steps (dim + bright)
    ]
    state.flashState = all
 
    lights.each { light ->
        try {
            light.on()
            light.setColor(color)
            logDebug "setColor: ${light.displayName} -> hue:${color.hue} sat:${color.saturation} level:${color.level}"
        }
        catch (Exception e) {
            logWarn "setColor error for ${light.displayName}: ${e.message}"
        }
    }
 
    Integer intervalSecs = (flashIntervalSeconds ?: "2").toInteger()
    runIn(intervalSecs, "flashStep", [data: [flashKey: flashKey]])
}
 
def flashStep(data) {
    String flashKey = data?.flashKey
    if (!flashKey) return
 
    Map all = (state.flashState ?: [:]) as Map
    Map fs  = all[flashKey] as Map
    if (!fs || state.activeTier == TIER_NONE) {
        logDebug "flashStep: alarm cleared or flash replaced - stopping"
        return
    }
 
    Integer step         = fs.flashStep as Integer
    Integer total        = fs.totalSteps as Integer
    List    dnis         = fs.dnis as List
    Integer intervalSecs = (flashIntervalSeconds ?: "2").toInteger()
    Integer fullLevel    = (fs.color?.level ?: 100) as Integer
    // Keep the flash visible when a low tier brightness is configured
    Integer dimLevel     = Math.min(((flashDimLevel != null) ? flashDimLevel : 10) as Integer, (int) (fullLevel / 2))
 
    if (step >= total) {
        setLevelOnDnis(dnis, fullLevel)
        all.remove(flashKey)
        state.flashState = all
        logDebug "flashStep: complete - holding at ${fullLevel}%"
        runIn(1, "announceAfterFlash")
        return
    }
 
    Integer targetLevel = (step % 2 == 0) ? dimLevel : fullLevel
    setLevelOnDnis(dnis, targetLevel)
    logDebug "flashStep: step ${step}/${total} -> ${targetLevel}%"
 
    fs.flashStep = step + 1
    all[flashKey] = fs
    state.flashState = all
 
    runIn(intervalSecs, "flashStep", [data: [flashKey: flashKey]])
}
 
// ---------------------------------------------------------------------------
// Post-flash announcement handler
// Fires after lights reach steady state so TTS is clear.
// ---------------------------------------------------------------------------
 
def announceAfterFlash() {
    if (state.activeTier == TIER_NONE) return   // alarm cleared during flash
 
    def tier     = state.activeTier
    def roomName = state.pendingRoom ?: (state.activeRooms?.values()?.join(", ") ?: "unknown")
 
    logDebug "announceAfterFlash: tier=${tier} room=${roomName}"
 
    switch (tier) {
        case TIER_WARNING:
            announceOnSpeakers(warnSpeakers,
                warnMessage ?: "Attention. Elevated smoke particles detected. Please check for a smoke source.",
                warnVolume)
            sendNotifications(warnNotifyDevices,
                warnNotifyMessage ?: "Early Warning: Elevated smoke particles detected. Check your home for a smoke source.")
            break
        case TIER_SMOKE:
            def smokeMsg = buildMessage(smokeMessage ?: "Smoke alarm. Smoke detected in %room%. Evacuate immediately. Stay low and crawl to the nearest exit.", roomName)
            announceOnSpeakers(smokeSpeakers, smokeMsg, smokeVolume)
            sendNotifications(smokeNotifyDevices,
                buildMessage(smokeNotifyMessage ?: "SMOKE ALARM: Smoke detected in %room%. Evacuate immediately.", roomName))
            break
        case TIER_CO:
            def coMsg = buildMessage(coMessage ?: "Carbon monoxide alarm. Carbon monoxide detected in %room%. Leave the house immediately. Do not stop for anything. Go outside now.", roomName)
            announceOnSpeakers(coSpeakers, coMsg, coVolume)
            sendNotifications(coNotifyDevices,
                buildMessage(coNotifyMessage ?: "CO ALARM: Carbon monoxide detected in %room%. Leave the house immediately.", roomName))
            break
    }
    scheduleRepeat("repeatAnnouncement", repeatSettingFor(tier), [tier: tier, room: roomName])
}
 
// ---------------------------------------------------------------------------
// Light command helpers
// ---------------------------------------------------------------------------
 
// Sets level on inside color lights by DNI (outside lights are never in a flash list).
private void setLevelOnDnis(List dnis, int level) {
    Map devices = managedDevicesByDni()
    dnis.each { dni ->
        def device = devices[dni]
        if (device) {
            try {
                device.setLevel(level)
                logDebug "setLevel: ${device.displayName} -> ${level}%"
            }
            catch (Exception e) {
                logWarn "setLevel error for ${device.displayName}: ${e.message}"
            }
        }
        else {
            logWarn "setLevelOnDnis: device not found for DNI ${dni}"
        }
    }
}
 
// CT-only lights and night lights: turn on at the tier's brightness.
private void turnOnLights(lights, Integer level) {
    if (!lights) return
    lights.each { light ->
        try {
            light.on()
            if (light.hasCommand("setLevel")) light.setLevel(level)
        }
        catch (Exception e) {
            logWarn "turnOnLights error for ${light.displayName}: ${e.message}"
        }
    }
}
 
private void setOutsideLights(lights, Map color) {
    if (!lights) return
    lights.each { light ->
        try {
            light.on()
            light.setColor(color)
            logDebug "setOutsideLights: ${light.displayName} -> solid hue:${color.hue} level:${color.level}"
        }
        catch (Exception e) {
            logWarn "setOutsideLights error for ${light.displayName}: ${e.message}"
        }
    }
}
 
// ---------------------------------------------------------------------------
// Disable switch helpers
// ---------------------------------------------------------------------------
 
private void enableDisableSwitches(boolean on) {
    if (!disableSwitches) return
    disableSwitches.each { sw ->
        try {
            on ? sw.on() : sw.off()
        }
        catch (Exception e) {
            logWarn "enableDisableSwitches error for ${sw.displayName}: ${e.message}"
        }
    }
    logDebug "Disable switches turned ${on ? 'ON (normal/restored)' : 'OFF (alarm active)'}"
}
 
// ---------------------------------------------------------------------------
// Speaker helpers
// ---------------------------------------------------------------------------
 
private void announceOnSpeakers(speakers, String message, volume) {
    if (!speakers || !message) return
    def vol = (volume ?: 80) as Integer
    speakers.each { speaker ->
        try {
            if (speaker.hasCapability("AudioVolume")) speaker.setVolume(vol)
            speaker.speak(message)
            logDebug "Announced on ${speaker.displayName}: ${message}"
        }
        catch (Exception e) {
            logWarn "Speaker error for ${speaker.displayName}: ${e.message}"
        }
    }
}
 
// ---------------------------------------------------------------------------
// Notification helpers
// ---------------------------------------------------------------------------
 
private void sendNotifications(devices, String message) {
    if (!devices || !message) return
    devices.each { device ->
        try {
            device.deviceNotification(message)
            logDebug "Notification sent to ${device.displayName}"
        }
        catch (Exception e) {
            logWarn "Notification error for ${device.displayName}: ${e.message}"
        }
    }
}
 
// ---------------------------------------------------------------------------
// Utility helpers
// ---------------------------------------------------------------------------
 
// activeRooms: deviceId -> room name. Keyed by deviceId so a renamed device still clears.
// Only real detector events call this, so it also ends any running test: a real alarm
// must never be dismissed by the 60-second test timer.
private void addActiveRoom(String deviceId, String roomName) {
    if (state.testActive) {
        logWarn "Real detector alarm from ${roomName} during a test - test timer cancelled"
        state.testActive = false
        unschedule("clearTestAlarm")
    }
    Map rooms = (state.activeRooms ?: [:]) as Map
    rooms[deviceId] = roomName
    state.activeRooms = rooms
    logDebug "activeRooms after add: ${rooms}"
}
 
private void removeActiveRoom(String deviceId) {
    Map rooms = (state.activeRooms ?: [:]) as Map
    rooms.remove(deviceId)
    state.activeRooms = rooms
    logDebug "activeRooms after remove: ${rooms}"
}
 
private String buildMessage(String template, String roomName) {
    if (!template) {
        logWarn "buildMessage: null or empty template - announcement will be silent"
        return ""
    }
    return template.replace("%room%", roomName ?: "unknown")
}
 
private void scheduleRepeat(String method, repeatSetting, Map data) {
    def minutes = (repeatSetting ?: "0").toString().toInteger()
    if (minutes > 0) {
        runIn(minutes * 60, method, [data: data])
    }
}
 
// ---------------------------------------------------------------------------
// Log level auto-revert
// ---------------------------------------------------------------------------
 
private void scheduleLogRevertIfNeeded() {
    def level = (logLevel ?: "3").toInteger()
    if (level >= LOG_VERBOSE_THRESHOLD) {
        state.logLevelExpiry = now() + (LOG_REVERT_SECONDS * 1000)
        runIn(LOG_REVERT_SECONDS, "revertLogLevel")
        logInfo "Verbose logging active - will revert to Info in 30 minutes"
    }
    else {
        state.logLevelExpiry = null
    }
}
 
def revertLogLevel() {
    log.info "Home Smoke and CO Response: 30-minute verbose log window expired - reverting to Info"
    app.updateSetting("logLevel", [type: "enum", value: "3"])
}
 
private int logLevelSetting() {
    def level = (logLevel ?: "3").toInteger()
    if (level >= LOG_VERBOSE_THRESHOLD) {
        def expiry = state.logLevelExpiry as Long
        if (expiry && now() > expiry) return 3
    }
    return level
}
 
private void logError(String msg) { if (logLevelSetting() >= 1) log.error "Home Smoke and CO Response: ${msg}" }
private void logWarn (String msg) { if (logLevelSetting() >= 2) log.warn  "Home Smoke and CO Response: ${msg}" }
private void logInfo (String msg) { if (logLevelSetting() >= 3) log.info  "Home Smoke and CO Response: ${msg}" }
private void logDebug(String msg) { if (logLevelSetting() >= 4) log.debug "Home Smoke and CO Response: ${msg}" }
private void logTrace(String msg) { if (logLevelSetting() >= 5) log.trace "Home Smoke and CO Response: ${msg}" }
 
