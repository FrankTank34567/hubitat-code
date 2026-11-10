/*
 * ============================================================
 * Curtain Scheduler — Room (Child App)
 *
 * One instance per room. Controls one or more curtain/blind
 * devices on a daily schedule, with two optional layers that
 * both work with ANY device and ANY attribute you select:
 *   - Presence (morning behavior + daytime naps)
 *   - Event trigger (e.g. TV switch turns on -> close)
 *
 * MORNING BEHAVIOR (choose one per room):
 *   Normal             - Open at the open trigger.
 *   Open early on rise - Before the open trigger, if the room
 *                        becomes empty after a floor time AND
 *                        after sunrise, open early. At the
 *                        trigger it opens regardless.
 *   Hold until empty   - Ignore the open trigger; stay closed
 *                        until the room has been empty for a
 *                        set time, then open. IMPORTANT: this
 *                        mode has NO timed failsafe open. If
 *                        the sensor dies the curtains stay
 *                        closed until opened by hand. That is
 *                        deliberate: a bedroom should fail
 *                        private, not fling open.
 *
 * DAYTIME NAP (independent): during the open window, close when
 *   occupied for a while, reopen when empty for a while.
 *
 * EVENT TRIGGER (independent): when a chosen device/attribute
 *   reaches a chosen value, close the curtains. Optionally
 *   reopen when it leaves that value. Only acts inside the open
 *   window; after the scheduled close it stays closed.
 *
 * CONFLICTS: presence and event trigger are independent and the
 *   last command wins. No arbitration is attempted.
 *
 * SAFETY:
 *   - The scheduled CLOSE is the hard boundary and always fires.
 *   - A stale presence device (no update within the configured
 *     window) is ignored, and the room relies on the schedule.
 *   - A generation counter guards every timer so re-saving the
 *     app never leaves old callbacks running.
 *
 * Author:      Frank Tank (GitHub: FrankTank34567)
 * Version:     0.6.1  (2026-08-30)
 * License:     Apache 2.0
 *
 * Developed with Claude (Anthropic) — AI-assisted design and
 * implementation. Vibe-coded: the human owns the requirements
 * and device decisions; Claude wrote the Groovy against those.
 * Hubitat API specifics were verified against the developer
 * documentation while building.
 *
 * CHANGELOG:
 *   0.6.1 - 2026-08-30 - The scheduled open now stays closed when the
 *           event trigger is currently active (TV already on at open
 *           time). Previously it opened into an active TV session because
 *           the event logic only reacted to changes.
 *   0.6.0 - 2026-08-15 - Added optional verify-and-retry. Previously a
 *           command sent to an offline curtain was silently lost: the
 *           device accepts it without throwing, so the try/catch caught
 *           nothing and the log claimed success. Rooms can now confirm the
 *           curtains actually arrived and re-send to any that did not,
 *           warning if they never get there. Off by default.
 *   0.5.3 - 2026-08-09 - FIX: a hold-until-empty room configured (or a hub
 *           restarted) after its open trigger never armed, so it could stay
 *           closed all day with no timed failsafe to rescue it. Hold mode
 *           now arms on startup when already inside the open window.
 *   0.5.2 - 2026-08-08 - FIX: curtains could be left open past the close
 *           time if the app was saved (or the hub restarted) after that
 *           time had already passed, because nothing re-checked state on
 *           startup. Rooms now close on init when outside the open window.
 *           Startup never opens, only closes.
 *           FIX: the nightly reschedule job stacked a duplicate copy of
 *           itself every day instead of replacing it.
 *           HARDENED: the event trigger's optional reopen now re-checks
 *           the open window immediately before opening.
 *   0.5.1 - 2026-08-08 - FIX: stale-sensor detection produced false
 *           positives on presence/contact devices, which only report
 *           when they change. A healthy sensor looked "stale" simply
 *           because nobody had got in or out of bed for a couple of
 *           hours, and the room then ignored real occupancy. The check
 *           is now opt-in and defaults to OFF (0 minutes).
 *   0.5.0 - 2026-08-08 - Added configurable event trigger (any
 *           device/attribute/value; close, with optional reopen)
 *           acting only inside the open window. Added "Evaluate
 *           now" button and a live status readout. Much more
 *           logging at every decision point. Hold-until-empty
 *           now logs loudly when a stale sensor leaves curtains
 *           closed. Cleans up stale settings from versions 0.2
 *           and 0.3.
 *   0.4.0 - 2026-08-08 - Three-way morning behavior + nap.
 *   0.3.0 - 2026-08-08 - Combinable toggles.
 *   0.2.0 - 2026-08-08 - Scheduling and presence.
 *   0.1.0 - 2026-08-08 - Skeleton.
 * ============================================================
 */

definition(
    name:        "Curtain Scheduler Room",
    namespace:   "franktank",
    author:      "Frank Tank",
    description: "A single room controlled by Curtain Scheduler.",
    category:    "Convenience",
    parent:      "franktank:Curtain Scheduler",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/FrankTank34567/hubitat-curtain-scheduler/main/CurtainSchedulerRoom.groovy",
    singleThreaded: true
)

preferences {
    page(name: "roomPage")
}

// Settings left behind by versions 0.2 and 0.3, removed on save.
@groovy.transform.Field
static final List<String> OBSOLETE_SETTINGS = [
    'curtain', 'presence', 'presenceMode', 'morningHold'
]

// ============================================================
// Lifecycle
// ============================================================

def installed() {
    logInfo "Room installed: ${app.label}"
    initialize()
}

def updated() {
    logInfo "Room updated: ${app.label}"
    unsubscribe()
    unschedule()
    cleanupObsoleteSettings()
    initialize()
}

def initialize() {
    state.generation = ((state.generation ?: 0) as int) + 1
    int gen = state.generation

    scheduleOpen(gen)
    scheduleClose(gen)

    if (presenceConfigured()) {
        subscribe(settings.presenceDevice, settings.presenceAttribute, presenceEventHandler)
        logInfo "Watching occupancy: ${settings.presenceDevice} '${settings.presenceAttribute}' " +
                "== '${settings.presenceOccupiedValue}' means occupied (currently ${isOccupied() ? 'OCCUPIED' : 'empty'})"
    }

    if (eventConfigured()) {
        subscribe(settings.eventDevice, settings.eventAttribute, eventTriggerHandler)
        logInfo "Watching event trigger: ${settings.eventDevice} '${settings.eventAttribute}' " +
                "== '${settings.eventTriggerValue}' closes curtains" +
                (settings.eventReopen ? "; leaving that value reopens them" : "; no automatic reopen")
    }

    logInfo "Room ready: ${app.label} | morning=${settings.morningBehavior} nap=${settings.daytimeNap} " +
            "event=${eventConfigured()} | opens ${fmt(computeOpenTime())} closes ${fmt(computeCloseTime())}"
    logDebug "generation=${gen}"

    // Catch-up: if we are starting outside the open window the curtains must
    // be closed. Saving the app in the evening (or a hub reboot) previously
    // left them wherever they happened to be until the next day's close.
    // Only ever closes; it will not open anything on startup.
    if (settings.curtains && !inOpenWindow()) {
        logInfo "Startup check: outside the open window, ensuring curtains are closed"
        closeCurtains("startup catch-up")
        return
    }

    // Hold-mode catch-up: if we are already past the open trigger (because the
    // room was configured, or the hub restarted, later in the day) then the
    // scheduled openHandler will not run again until tomorrow. Without this,
    // a hold-mode room would never arm and — having no timed failsafe by
    // design — could sit closed all day with nothing able to open it.
    if (settings.morningBehavior == "hold" && presenceConfigured() && !parentClosedMode()) {
        logInfo "Startup check: inside the open window in hold mode, arming the vacancy wait now"
        armSleepInOpen()
    }
}

def uninstalled() {
    logInfo "Room uninstalled: ${app.label}"
}

// Remove settings from earlier versions so the app page and the
// settings dump stay honest about what is actually in use.
private void cleanupObsoleteSettings() {
    OBSOLETE_SETTINGS.each { key ->
        if (settings.containsKey(key)) {
            try {
                app.removeSetting(key)
                logDebug "Removed obsolete setting '${key}' from a previous version"
            } catch (e) {
                logDebug "Could not remove obsolete setting '${key}': ${e.message}"
            }
        }
    }
}

// ============================================================
// Configuration page
// ============================================================

def roomPage() {
    dynamicPage(name: "roomPage", title: "Room Setup", install: true, uninstall: true) {

        section("Status") {
            paragraph statusText()
            input name: "btnEvaluate", type: "button", title: "Evaluate now"
            paragraph "Evaluate now makes this room re-check everything immediately and act if " +
                      "something is out of place. It does not wait for a scheduled time or a " +
                      "sensor event, so it is the quickest way to test a change."
        }

        section("Room name") {
            paragraph "A label for your own use, like 'Living Room' or \"Natalie's Room\". " +
                      "It does not need to match a Hubitat room."
            label title: "Room name", required: true
        }

        section("Curtains / blinds") {
            paragraph "Pick one or more devices. They are all opened and closed together. " +
                      "One device is fine."
            input name: "curtains", type: "capability.windowShade",
                  title: "Curtains or blinds to control",
                  required: true, multiple: true
        }

        section("When to OPEN in the morning") {
            paragraph "'Sunrise' follows the seasons automatically. 'Later of sunrise or a time' " +
                      "waits for whichever comes later, so the room never opens before a set hour " +
                      "even in midsummer."
            input name: "openMode", type: "enum", title: "Open trigger",
                  options: [
                      "sunrise":      "At sunrise",
                      "time":         "At a fixed time",
                      "laterSunTime": "At the later of sunrise or a time"
                  ],
                  defaultValue: "sunrise", required: true, submitOnChange: true
            if (settings.openMode in ["time", "laterSunTime"]) {
                input name: "openTime", type: "time",
                      title: settings.openMode == "laterSunTime"
                                 ? "Open time (opens at whichever is later: this or sunrise)"
                                 : "Open time",
                      required: true
            }
            if (settings.openMode in ["sunrise", "laterSunTime"]) {
                input name: "openOffset", type: "number",
                      title: "Sunrise offset in minutes (optional; negative means before sunrise)",
                      required: false, defaultValue: 0
            }
        }

        section("When to CLOSE at night") {
            paragraph "This is the hard boundary. It always fires, even if a sensor has failed, " +
                      "so the room is never left exposed overnight. 'Earlier of sunset or a time' " +
                      "closes at whichever comes first."
            input name: "closeMode", type: "enum", title: "Close trigger",
                  options: [
                      "sunset":         "At sunset",
                      "time":           "At a fixed time",
                      "earlierSunTime": "At the earlier of sunset or a time"
                  ],
                  defaultValue: "sunset", required: true, submitOnChange: true
            if (settings.closeMode in ["time", "earlierSunTime"]) {
                input name: "closeTime", type: "time",
                      title: settings.closeMode == "earlierSunTime"
                                 ? "Close time (closes at whichever is earlier: this or sunset)"
                                 : "Close time",
                      required: true
            }
            if (settings.closeMode in ["sunset", "earlierSunTime"]) {
                input name: "closeOffset", type: "number",
                      title: "Sunset offset in minutes (optional; negative means before sunset)",
                      required: false, defaultValue: 0
            }
        }

        section("If a curtain does not respond") {
            paragraph "Curtains sometimes accept a command and never move, usually because " +
                      "their hub or bridge has dropped offline. Nothing reports an error when " +
                      "this happens, so the only way to catch it is to check afterwards.\n\n" +
                      "Set retries above 0 to have this room confirm the curtains arrived and " +
                      "try again if they did not. It only re-sends to the ones that fell short."
            input name: "maxRetries", type: "number",
                  title: "Retry attempts if a curtain does not arrive (0 = do not check)",
                  required: true, defaultValue: 0
            if ((settings.maxRetries ?: 0) > 0) {
                input name: "verifyDelay", type: "number",
                      title: "Seconds to wait before checking (allow time for full travel)",
                      required: true, defaultValue: 45
                paragraph "Make this longer than the curtains take to travel end to end, or a " +
                          "still-moving curtain will be counted as failed and told to move again. " +
                          "If a curtain always reports 'partially open' even when fully closed, " +
                          "it will retry every time and then log a warning; leave retries off for " +
                          "that room until the device is recalibrated."
            }
        }

        section("Morning behavior") {
            paragraph "How this room behaves around the open trigger:\n\n" +
                      "• Normal — open at the trigger and nothing else.\n\n" +
                      "• Open early when someone gets up — before the trigger, if the room becomes " +
                      "empty after the floor time below AND after sunrise, open early. At the " +
                      "trigger it opens no matter what. Good for a child's room: dark until they " +
                      "are up and the sun is up.\n\n" +
                      "• Hold closed until empty — ignore the trigger and stay closed until the " +
                      "room has been empty for a set time, then open. Good for adults who sleep " +
                      "in. Note this mode has no timed failsafe: if the sensor stops reporting, " +
                      "the curtains stay closed until you open them yourself."
            input name: "morningBehavior", type: "enum", title: "Morning behavior",
                  options: [
                      "normal": "Normal (open at the trigger)",
                      "getup":  "Open early when someone gets up",
                      "hold":   "Hold closed until empty (sleep-in)"
                  ],
                  defaultValue: "normal", required: true, submitOnChange: true

            if (settings.morningBehavior == "getup") {
                input name: "getupFloor", type: "time",
                      title: "Earliest time an early open is allowed",
                      required: true
                paragraph "Getting up before this time does nothing. An early open also waits for " +
                          "sunrise, so a pre-dawn trip out of bed will not open the curtains."
            }

            if (settings.morningBehavior == "hold") {
                input name: "sleepinVacantMin", type: "number",
                      title: "Open once the room has been empty for this many minutes",
                      required: true, defaultValue: 20
                paragraph "A brief trip to the bathroom will not open the curtains as long as this " +
                          "is longer than the trip."
            }
        }

        section("Daytime naps (optional)") {
            paragraph "During the open window, close the curtains when the room has been occupied " +
                      "for a while and reopen when it has been empty for a while. Combines with " +
                      "any morning behavior."
            input name: "daytimeNap", type: "bool",
                  title: "Close for daytime naps",
                  defaultValue: false, submitOnChange: true
            if (settings.daytimeNap) {
                input name: "napCloseMin", type: "number",
                      title: "Close after occupied for this many minutes",
                      required: true, defaultValue: 5
                input name: "napReopenMin", type: "number",
                      title: "Reopen after empty for this many minutes",
                      required: true, defaultValue: 10
            }
        }

        section("Event trigger (optional)") {
            paragraph "Close the curtains when something happens, such as a TV turning on. Pick " +
                      "any device, the attribute to watch, and the value that should close the " +
                      "curtains. This only acts during the open window: if it is already past the " +
                      "close time the curtains simply stay closed."
            input name: "eventEnabled", type: "bool",
                  title: "Use an event trigger",
                  defaultValue: false, submitOnChange: true

            if (settings.eventEnabled) {
                input name: "eventDevice", type: "capability.*",
                      title: "Trigger device (any device)",
                      required: true, multiple: false, submitOnChange: true

                if (settings.eventDevice) {
                    List attrNames = settings.eventDevice.getSupportedAttributes()*.name.unique().sort()
                    input name: "eventAttribute", type: "enum",
                          title: "Attribute to watch",
                          options: attrNames, required: true, submitOnChange: true

                    if (settings.eventAttribute) {
                        String cur = safeValue(settings.eventDevice, settings.eventAttribute)
                        input name: "eventTriggerValue", type: "text",
                              title: "Value that CLOSES the curtains (device reads '${cur}' right now)",
                              required: true
                    }
                }

                input name: "eventReopen", type: "bool",
                      title: "Also reopen when the value goes away",
                      defaultValue: false
                paragraph "Leave reopen off if you would rather the curtains stay closed for the " +
                          "evening once the TV has been on, and reopen on tomorrow's schedule."
            }
        }

        if (needsPresence()) {
            section("Occupancy source") {
                paragraph "The behaviors above need to know whether the room is occupied. Pick any " +
                          "device, then the attribute to watch, then type the value that means " +
                          "occupied. For a presence sensor that is usually attribute 'presence' " +
                          "with value 'present'. For a switch it would be 'switch' and 'on'."
                input name: "presenceDevice", type: "capability.*",
                      title: "Occupancy device (any device)",
                      required: true, multiple: false, submitOnChange: true

                if (settings.presenceDevice) {
                    List attrNames = settings.presenceDevice.getSupportedAttributes()*.name.unique().sort()
                    input name: "presenceAttribute", type: "enum",
                          title: "Attribute that indicates occupancy",
                          options: attrNames, required: true, submitOnChange: true

                    if (settings.presenceAttribute) {
                        String cur = safeValue(settings.presenceDevice, settings.presenceAttribute)
                        input name: "presenceOccupiedValue", type: "text",
                              title: "Value that means OCCUPIED (device reads '${cur}' right now)",
                              required: true
                    }
                }

                input name: "staleMinutes", type: "number",
                      title: "Ignore this device if it goes silent for this many minutes (0 = never ignore)",
                      required: true, defaultValue: 0
                paragraph "Leave this at 0 for most devices. Presence and contact devices only " +
                          "report when they CHANGE, so a perfectly healthy sensor can look silent " +
                          "for hours simply because nobody got in or out of bed. Only set a value " +
                          "here if you picked a device that reports continuously (a pressure or " +
                          "signal reading, for example) and you want the room to fall back to the " +
                          "plain schedule when it stops."
            }
        }
    }
}

// Human-readable summary shown at the top of the room page.
private String statusText() {
    if (!settings.curtains) { return "Pick your curtains below to get started." }
    if ((settings.openMode in ["time", "laterSunTime"] && !settings.openTime) ||
        (settings.closeMode in ["time", "earlierSunTime"] && !settings.closeTime)) {
        // The mode is saved (submitOnChange) before its time is entered; timeToday(null) would throw.
        return "Enter the open and close times below to finish setting up this room."
    }
    List lines = []
    lines << "Opens ${fmt(computeOpenTime())}, closes ${fmt(computeCloseTime())}."
    lines << (inOpenWindow() ? "Right now this room is inside its open window."
                             : "Right now this room is outside its open window (night).")
    if (presenceConfigured()) {
        lines << ("Occupancy reads '" + safeValue(settings.presenceDevice, settings.presenceAttribute) +
                  "', which means " + (isOccupied() ? "OCCUPIED" : "empty") +
                  (presenceStale() ? " — but the device is STALE and is being ignored." : "."))
    }
    if (eventConfigured()) {
        lines << ("Event trigger reads '" + safeValue(settings.eventDevice, settings.eventAttribute) +
                  "', which is " + (isEventActive() ? "ACTIVE (should be closed)" : "not active") + ".")
    }
    if (parentClosedMode()) {
        lines << "Keep-closed mode is ACTIVE, so this room will not open."
    }
    return lines.join("\n")
}

private String fmt(Date d) {
    return d ? d.format("h:mm a", location.timeZone) : "unknown"
}

private String safeValue(dev, String attr) {
    try {
        return dev?.currentValue(attr)?.toString() ?: "unknown"
    } catch (e) {
        return "unknown"
    }
}

private boolean needsPresence() {
    return settings.morningBehavior in ["getup", "hold"] || settings.daytimeNap
}

// ============================================================
// Buttons
// ============================================================

def appButtonHandler(String btn) {
    if (btn == "btnEvaluate") {
        logInfo "Manual evaluate requested for ${app.label}"
        evaluateNow()
    }
}

// Re-check everything and correct the curtains if needed.
// Called by the button and by the parent's evaluate-all.
void evaluateNow() {
    logInfo "=== Evaluate: ${app.label} ==="
    logInfo "  Now inside open window: ${inOpenWindow()} (opens ${fmt(computeOpenTime())}, closes ${fmt(computeCloseTime())})"
    if (presenceConfigured()) {
        logInfo "  Occupancy: ${isOccupied() ? 'OCCUPIED' : 'empty'}${presenceStale() ? ' (STALE — ignored)' : ''}"
    }
    if (eventConfigured()) {
        logInfo "  Event trigger active: ${isEventActive()}"
    }
    if (parentClosedMode()) {
        logInfo "  Keep-closed mode ACTIVE — will not open"
    }

    if (!inOpenWindow()) {
        logInfo "  Decision: outside open window, curtains should be CLOSED"
        closeCurtains("evaluate: outside open window")
        return
    }
    if (parentClosedMode()) {
        logInfo "  Decision: keep-closed mode, leaving curtains alone"
        return
    }
    if (eventConfigured() && isEventActive()) {
        logInfo "  Decision: event trigger active, curtains should be CLOSED"
        closeCurtains("evaluate: event trigger active")
        return
    }
    if (settings.daytimeNap && presenceConfigured() && !presenceStale() && isOccupied()) {
        logInfo "  Decision: room occupied during the day, curtains should be CLOSED"
        closeCurtains("evaluate: occupied")
        return
    }
    if (settings.morningBehavior == "hold" && state.sleepInArmed) {
        logInfo "  Decision: morning hold still armed and waiting for the room to be empty, leaving closed"
        return
    }
    logInfo "  Decision: nothing blocking, curtains should be OPEN"
    openCurtains("evaluate: clear to open")
}

// ============================================================
// Schedule computation
// ============================================================

private Date computeOpenTime() {
    int offset = (settings.openOffset ?: 0) as int
    Date sunrise = getSunriseAndSunset(sunriseOffset: offset).sunrise
    switch (settings.openMode) {
        case "time":
            return toTodayTime(settings.openTime)
        case "laterSunTime":
            Date t = toTodayTime(settings.openTime)
            return (sunrise.after(t)) ? sunrise : t
        default:
            return sunrise
    }
}

private Date computeCloseTime() {
    int offset = (settings.closeOffset ?: 0) as int
    Date sunset = getSunriseAndSunset(sunsetOffset: offset).sunset
    switch (settings.closeMode) {
        case "time":
            return toTodayTime(settings.closeTime)
        case "earlierSunTime":
            Date t = toTodayTime(settings.closeTime)
            return (sunset.before(t)) ? sunset : t
        default:
            return sunset
    }
}

private Date toTodayTime(String timeStr) {
    return timeToday(timeStr, location.timeZone)
}

private Date todaySunrise() {
    return getSunriseAndSunset().sunrise
}

def scheduleOpen(int gen) {
    Date t = computeOpenTime()
    schedule(t, "openHandler", [data: [gen: gen], overwrite: false])
    logDebug "Open scheduled for ${t}"
    // overwrite:true so the nightly recompute replaces its own job rather
    // than stacking a new one every day.
    schedule(timeToday("00:05", location.timeZone), "dailyReschedule",
             [data: [gen: gen], overwrite: true])
}

def scheduleClose(int gen) {
    Date t = computeCloseTime()
    schedule(t, "closeHandler", [data: [gen: gen], overwrite: false])
    logDebug "Close scheduled for ${t}"
}

def dailyReschedule(data) {
    if (staleGen(data)) return
    int gen = state.generation
    unschedule("openHandler")
    unschedule("closeHandler")
    scheduleOpen(gen)
    scheduleClose(gen)
    state.getupDone = false
    state.sleepInArmed = false
    logInfo "New day: opens ${fmt(computeOpenTime())}, closes ${fmt(computeCloseTime())}"
}

// ============================================================
// Scheduled movement handlers
// ============================================================

def openHandler(data) {
    if (staleGen(data)) return
    logInfo "Open trigger reached for ${app.label}"
    if (parentClosedMode()) {
        logInfo "  Suppressed: keep-closed mode is active"
        return
    }
    if (eventConfigured() && isEventActive()) {
        // The TV (or whatever the trigger watches) is already on at open
        // time. Opening now would defeat the point of the trigger, so stay
        // closed. If reopen-on-clear is enabled, the curtains open when the
        // trigger clears; otherwise they stay closed until tomorrow.
        logInfo "  Suppressed: event trigger is currently active, staying closed"
        return
    }
    if (settings.morningBehavior == "hold" && presenceConfigured()) {
        armSleepInOpen()
        return
    }
    openCurtains("scheduled open")
    state.getupDone = true
}

def closeHandler(data) {
    if (staleGen(data)) return
    logInfo "Close trigger reached for ${app.label} — closing (hard boundary)"
    closeCurtains("scheduled close")
    state.sleepInArmed = false
}

// ============================================================
// Event trigger
// ============================================================

def eventTriggerHandler(evt) {
    if (!eventConfigured()) return
    boolean active = isEventActive()
    logInfo "Event trigger ${settings.eventDevice} '${settings.eventAttribute}' = '${evt.value}' -> active=${active}"

    if (!inOpenWindow()) {
        logInfo "  Ignored: outside the open window, curtains already closed for the night"
        return
    }
    if (parentClosedMode()) {
        logInfo "  Ignored: keep-closed mode is active"
        return
    }
    if (active) {
        closeCurtains("event trigger active")
    } else if (settings.eventReopen && inOpenWindow()) {
        openCurtains("event trigger cleared")
    } else if (settings.eventReopen) {
        logInfo "  Value cleared but it is past the close time, leaving curtains closed"
    } else {
        logInfo "  Value cleared but reopen is switched off, leaving curtains closed"
    }
}

private boolean eventConfigured() {
    return settings.eventEnabled &&
           settings.eventDevice != null &&
           settings.eventAttribute &&
           settings.eventTriggerValue
}

private boolean isEventActive() {
    if (!eventConfigured()) return false
    def v = settings.eventDevice.currentValue(settings.eventAttribute)?.toString()
    return v == settings.eventTriggerValue?.toString()
}

// ============================================================
// Presence handling
// ============================================================

def presenceEventHandler(evt) {
    if (!presenceConfigured()) return
    if (presenceStale()) {
        logWarn "${app.label}: occupancy device has not reported in over ${settings.staleMinutes} " +
                "minutes, so this event is ignored and the room is following the schedule only"
        return
    }
    boolean occupied = isOccupied()
    logInfo "Occupancy for ${app.label}: '${evt.value}' -> ${occupied ? 'OCCUPIED' : 'empty'}"

    if (settings.morningBehavior == "getup") { handleGetup(occupied) }
    if (settings.morningBehavior == "hold")  { handleSleepIn(occupied) }
    if (settings.daytimeNap)                 { handleNap(occupied) }
}

// ---- Early-getup open ----------------------------------------

private void handleGetup(boolean occupied) {
    if (occupied) return
    if (state.getupDone) {
        logDebug "  Early open: already opened once today"
        return
    }
    if (parentClosedMode()) {
        logInfo "  Early open: suppressed by keep-closed mode"
        return
    }
    Date nowD = new Date()
    Date floorT = settings.getupFloor ? toTodayTime(settings.getupFloor) : toTodayTime("06:45")
    Date openT = computeOpenTime()
    Date sun = todaySunrise()

    if (!nowD.after(floorT)) {
        logInfo "  Early open: too early, floor time is ${fmt(floorT)}"
        return
    }
    if (!nowD.after(sun)) {
        logInfo "  Early open: waiting for sunrise at ${fmt(sun)}"
        return
    }
    if (!nowD.before(openT)) {
        logDebug "  Early open: past the normal open trigger, nothing to do"
        return
    }
    openCurtains("early open, someone got up")
    state.getupDone = true
}

// ---- Daytime nap ---------------------------------------------

private void handleNap(boolean occupied) {
    if (!inOpenWindow()) {
        logDebug "  Nap: outside the open window, ignoring"
        return
    }
    if (parentClosedMode()) {
        logInfo "  Nap: suppressed by keep-closed mode"
        return
    }
    int gen = state.generation
    if (occupied) {
        unschedule("napReopenFire")
        int mins = (settings.napCloseMin ?: 5) as int
        runIn(mins * 60, "napCloseFire", [data: [gen: gen]])
        logInfo "  Nap: occupied, will close in ${mins} min if still occupied"
    } else {
        unschedule("napCloseFire")
        int mins = (settings.napReopenMin ?: 10) as int
        runIn(mins * 60, "napReopenFire", [data: [gen: gen]])
        logInfo "  Nap: empty, will reopen in ${mins} min if still empty"
    }
}

def napCloseFire(data) {
    if (staleGen(data)) return
    if (!inOpenWindow() || parentClosedMode()) {
        logInfo "Nap close timer fired but conditions changed, doing nothing"
        return
    }
    if (isOccupied()) {
        closeCurtains("nap close")
    } else {
        logInfo "Nap close timer fired but the room is empty now, doing nothing"
    }
}

def napReopenFire(data) {
    if (staleGen(data)) return
    if (!inOpenWindow() || parentClosedMode()) {
        logInfo "Nap reopen timer fired but conditions changed, doing nothing"
        return
    }
    if (eventConfigured() && isEventActive()) {
        logInfo "Nap reopen timer fired but the event trigger is active, leaving curtains closed"
        return
    }
    if (!isOccupied()) {
        openCurtains("nap reopen")
    } else {
        logInfo "Nap reopen timer fired but the room is occupied again, doing nothing"
    }
}

// ---- Hold until empty (sleep-in) ------------------------------

private void armSleepInOpen() {
    state.sleepInArmed = true
    if (presenceStale()) {
        logWarn "${app.label}: morning hold reached the open trigger but the occupancy device is " +
                "STALE. This room has no timed failsafe by design, so the curtains will stay " +
                "closed until the sensor reports again or you open them by hand."
        return
    }
    logInfo "  Morning hold: staying closed until the room has been empty for " +
            "${settings.sleepinVacantMin ?: 20} minutes"
    if (!isOccupied()) {
        int gen = state.generation
        int mins = (settings.sleepinVacantMin ?: 20) as int
        runIn(mins * 60, "sleepInOpenFire", [data: [gen: gen]])
        logInfo "  Morning hold: already empty, will open in ${mins} min if it stays empty"
    }
}

private void handleSleepIn(boolean occupied) {
    if (!state.sleepInArmed) {
        logDebug "  Morning hold: not armed yet (open trigger has not been reached)"
        return
    }
    if (parentClosedMode()) return
    int gen = state.generation
    if (occupied) {
        unschedule("sleepInOpenFire")
        logInfo "  Morning hold: someone is back in bed, cancelling the pending open"
    } else {
        unschedule("sleepInOpenFire")
        int mins = (settings.sleepinVacantMin ?: 20) as int
        runIn(mins * 60, "sleepInOpenFire", [data: [gen: gen]])
        logInfo "  Morning hold: room empty, will open in ${mins} min if it stays empty"
    }
}

def sleepInOpenFire(data) {
    if (staleGen(data)) return
    if (parentClosedMode()) {
        logInfo "Morning hold timer fired but keep-closed mode is active, doing nothing"
        return
    }
    if (eventConfigured() && isEventActive()) {
        logInfo "Morning hold timer fired but the event trigger is active, leaving curtains closed"
        return
    }
    if (!isOccupied() && state.sleepInArmed) {
        openCurtains("morning hold satisfied, room has been empty")
        state.sleepInArmed = false
    } else {
        logInfo "Morning hold timer fired but the room is occupied again, staying closed"
    }
}

// ============================================================
// Parent hook — keep-closed (vacation) mode transitions
// ============================================================

void onClosedModeChange(boolean nowClosed) {
    if (nowClosed) {
        unschedule("napReopenFire")
        unschedule("sleepInOpenFire")
        logInfo "${app.label}: keep-closed mode on, cancelled any pending opens"
        return
    }
    logInfo "${app.label}: keep-closed mode off"
    if (!inOpenWindow()) {
        logInfo "  Outside the open window, leaving curtains closed until tomorrow"
        return
    }
    if (presenceConfigured() && !presenceStale() && isOccupied()) {
        logInfo "  Room is occupied, leaving curtains closed"
        return
    }
    if (eventConfigured() && isEventActive()) {
        logInfo "  Event trigger is active, leaving curtains closed"
        return
    }
    openCurtains("returned home during the day")
}

// ============================================================
// Curtain actions
// ============================================================

private void openCurtains(String reason) { moveCurtains('open', reason) }

private void closeCurtains(String reason) { moveCurtains('closed', reason) }

// Sends the command, then (optionally) checks afterwards that the curtains
// actually got there and retries the ones that did not.
//
// This matters because an offline hub or bridge usually ACCEPTS a command
// without throwing an error. Nothing is caught, the log happily says
// "OPENING curtains", and the curtains never move. Verification is the only
// way to notice.
private void moveCurtains(String target, String reason) {
    String verb = (target == 'open') ? 'OPENING' : 'CLOSING'
    logInfo "${app.label}: ${verb} curtains (${reason})"
    sendCurtainCommand(settings.curtains, target)

    if (retriesEnabled()) {
        int gen = state.generation
        runIn(verifyDelaySec(), "verifyCurtains",
              [data: [gen: gen, target: target, attempt: 1, reason: reason]])
    }
}

private void sendCurtainCommand(devices, String target) {
    devices?.each { c ->
        try {
            if (target == 'open') { c.open() } else { c.close() }
        } catch (e) {
            logWarn "${target}() failed on ${c}: ${e.message}"
        }
    }
}

// A curtain counts as arrived if windowShade matches the target. Anything
// else (partially open, unknown, still opening/closing, or no report at
// all) counts as not arrived and is retried.
private boolean curtainArrived(c, String target) {
    String v = null
    try { v = c.currentValue('windowShade')?.toString() } catch (e) { return false }
    return (v == target)
}

def verifyCurtains(data) {
    if (staleGen(data)) return
    String target = data?.target as String
    int attempt = (data?.attempt ?: 1) as int
    String reason = (data?.reason ?: 'unknown') as String

    List stragglers = (settings.curtains ?: []).findAll { !curtainArrived(it, target) }

    if (!stragglers) {
        logDebug "Verify: all curtains reached '${target}' (${reason})"
        return
    }

    String names = stragglers.collect { "${it} (${safeValue(it, 'windowShade')})" }.join(', ')

    if (attempt >= maxRetries()) {
        logWarn "${app.label}: gave up after ${attempt} attempts. These did not reach " +
                "'${target}': ${names}. The device or its hub may be offline."
        return
    }

    logWarn "${app.label}: did not reach '${target}', retrying (attempt ${attempt + 1} of " +
            "${maxRetries()}): ${names}"
    sendCurtainCommand(stragglers, target)
    runIn(verifyDelaySec(), "verifyCurtains",
          [data: [gen: state.generation, target: target, attempt: attempt + 1, reason: reason]])
}

private boolean retriesEnabled() { return (maxRetries() > 0) }
private int maxRetries()     { return ((settings.maxRetries ?: 0) as int) }
private int verifyDelaySec() { return Math.max(5, ((settings.verifyDelay ?: 45) as int)) }

// ============================================================
// Helpers
// ============================================================

private boolean presenceConfigured() {
    return needsPresence() &&
           settings.presenceDevice != null &&
           settings.presenceAttribute &&
           settings.presenceOccupiedValue
}

private boolean isOccupied() {
    if (!presenceConfigured()) return false
    def v = settings.presenceDevice.currentValue(settings.presenceAttribute)?.toString()
    return v == settings.presenceOccupiedValue?.toString()
}

// Staleness is OPT-IN. Presence and contact devices only report on change,
// so lastActivity being old does not mean the device is broken. A value of
// 0 (the default) disables the check entirely.
private boolean presenceStale() {
    if (!presenceConfigured()) return false
    Integer mins = (settings.staleMinutes ?: 0) as Integer
    if (mins <= 0) { return false }
    Date last = settings.presenceDevice.getLastActivity()
    if (last == null) { return false }
    long ageMin = (now() - last.time) / 60000L
    return ageMin > mins
}

private boolean inOpenWindow() {
    Date nowD = new Date()
    return nowD.after(computeOpenTime()) && nowD.before(computeCloseTime())
}

private boolean parentClosedMode() {
    try { return parent?.isClosedMode() } catch (e) { return false }
}

private boolean staleGen(data) {
    Integer g = data?.gen as Integer
    if (g != null && g != (state.generation as Integer)) {
        logDebug "Ignoring a timer left over from an earlier save (gen ${g}, now ${state.generation})"
        return true
    }
    return false
}

// ============================================================
// Logging — delegate to parent so the whole app shares one level
// ============================================================

void logError(String msg) { parent?.logError("[${app.label}] ${msg}") }
void logWarn (String msg) { parent?.logWarn ("[${app.label}] ${msg}") }
void logInfo (String msg) { parent?.logInfo ("[${app.label}] ${msg}") }
void logDebug(String msg) { parent?.logDebug("[${app.label}] ${msg}") }