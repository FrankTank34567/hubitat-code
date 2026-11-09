/*
 * ============================================================
 * Curtain Scheduler — Parent App
 *
 * Schedules curtains and blinds per room on sunrise/sunset or
 * fixed times, with an optional presence layer and an optional
 * event trigger (any device/attribute), plus a house-wide
 * "keep closed" mode for vacations. Each room is a child.
 *
 * DESIGN PRINCIPLES:
 *   - Schedule is the hard boundary. Scheduled closes fire on
 *     the clock/sunset regardless of presence or mode, so a
 *     stuck sensor can never hold curtains open past close.
 *   - Presence is real-time truth; mode is stated intention
 *     that can be wrong. Where they conflict, favor the safe
 *     physical outcome (fail toward closed/private).
 *   - Rooms fail closed. If a sensor dies, curtains stay shut
 *     rather than opening at the wrong moment.
 *
 * Author:      Frank Tank (GitHub: FrankTank34567)
 * Version:     0.2.1  (2026-08-10)
 * License:     Apache 2.0
 *
 * Developed with Claude (Anthropic) — AI-assisted design and
 * implementation. Vibe-coded: the human owns the requirements
 * and device decisions; Claude wrote the Groovy against those.
 *
 * CHANGELOG:
 *   0.2.1 - 2026-08-10 - FIX: every hub mode change was announced to the
 *           rooms as a keep-closed transition, even when it did not cross
 *           the keep-closed boundary (for example Night -> Home when only
 *           Away is a keep-closed mode). Rooms then evaluated a reopen for
 *           no reason, which during the day could undo a manual close.
 *           Rooms are now notified only on a real transition.
 *   0.2.0 - 2026-08-08 - Added house-wide "Evaluate all rooms
 *           now" button, room status summary on the main page,
 *           and safer child notification with per-child error
 *           isolation.
 *   0.1.0 - 2026-08-08 - Parent skeleton.
 * ============================================================
 */

definition(
    name:            "Curtain Scheduler",
    namespace:       "franktank",
    author:          "Frank Tank",
    description:     "Schedules curtains/blinds per room on sunrise/sunset or fixed times, " +
                     "with optional presence and event triggers, and a house-wide keep-closed mode.",
    category:        "Convenience",
    iconUrl:         "",
    iconX2Url:       "",
    importUrl:       "https://raw.githubusercontent.com/FrankTank34567/hubitat-curtain-scheduler/main/CurtainSchedulerParent.groovy",
    singleInstance:  true,
    singleThreaded:  true
)

preferences {
    page(name: "mainPage")
}

// ============================================================
// Lifecycle
// ============================================================

def installed() {
    logInfo "Installed"
    initialize()
}

def updated() {
    logInfo "Updated"
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    subscribe(location, "mode", modeChangeHandler)
    // Remember the current keep-closed state so mode changes that do not
    // actually cross the keep-closed boundary are ignored.
    state.lastClosedMode = isClosedMode()
    logDebug "Initialized. closedModes=${settings.closedModes} currentMode=${location.mode} keepClosed=${isClosedMode()}"
}

def uninstalled() {
    logInfo "Uninstalled — removing child apps"
    getChildApps().each { deleteChildApp(it.id) }
}

// ============================================================
// Main configuration page
// ============================================================

def mainPage() {
    dynamicPage(name: "mainPage", title: "Curtain Scheduler", install: true, uninstall: true) {

        section("Rooms") {
            paragraph "Each room is its own configuration: which curtains, when they open and " +
                      "close, and optionally how presence or an event (like a TV switching on) " +
                      "changes that. Add one room per set of curtains you want controlled together."
            app(
                name:      "childRooms",
                appName:   "Curtain Scheduler Room",
                namespace: "franktank",
                title:     "Add a room",
                multiple:  true
            )
        }

        section("Vacation / Keep-Closed Mode") {
            paragraph "Pick the hub mode(s) that mean nobody is home. While the hub is in one of " +
                      "them, no room will OPEN its curtains. Scheduled closes still happen, so if " +
                      "you leave during the day the curtains close that night and stay closed. " +
                      "When you leave the mode during a room's open window, that room reopens " +
                      "unless it is currently occupied."
            input name: "closedModes", type: "mode",
                  title: "Modes that keep all curtains closed",
                  multiple: true, required: false
            paragraph statusLine()
        }

        section("Diagnostics") {
            paragraph "Use this to make every room re-check its state right now, without waiting " +
                      "for a scheduled time or a sensor event. Useful for testing."
            input name: "btnEvaluateAll", type: "button", title: "Evaluate all rooms now"
        }

        section("Logging") {
            paragraph "Debug is verbose and good while setting up. Info is a sensible default for " +
                      "day-to-day use. This level applies to the parent and every room."
            input name: "logLevel", type: "enum", title: "Log level",
                  options: ["0": "Off", "1": "Error", "2": "Warn", "3": "Info", "4": "Debug"],
                  defaultValue: "3", required: true
        }
    }
}

private String statusLine() {
    int rooms = getChildApps()?.size() ?: 0
    return "Current hub mode: ${location.mode}. " +
           "Keep-closed is ${isClosedMode() ? 'ACTIVE — rooms will not open' : 'not active'}. " +
           "${rooms} room${rooms == 1 ? '' : 's'} configured."
}

// ============================================================
// Buttons
// ============================================================

def appButtonHandler(String btn) {
    if (btn == "btnEvaluateAll") {
        logInfo "Manual evaluate-all requested"
        getChildApps().each { child ->
            try {
                child.evaluateNow()
            } catch (e) {
                logWarn "Child ${child.label} evaluateNow failed: ${e.message}"
            }
        }
    }
}

// ============================================================
// Mode handling — notify children on closed-mode transitions
// ============================================================

def modeChangeHandler(evt) {
    boolean nowClosed = isClosedMode()
    boolean wasClosed = (state.lastClosedMode == true)

    // Only tell the rooms anything when the keep-closed state actually
    // changes. Previously every mode change (Night -> Home, Home -> Evening,
    // and so on) was announced as "keep-closed off", which made rooms
    // consider reopening for no reason and could undo a manual close.
    if (nowClosed == wasClosed) {
        logDebug "Hub mode changed to '${evt.value}'. Keep-closed still ${nowClosed}; " +
                 "no room action needed"
        return
    }

    state.lastClosedMode = nowClosed
    logInfo "Hub mode changed to '${evt.value}'. Keep-closed is now ${nowClosed}"
    getChildApps().each { child ->
        try {
            child.onClosedModeChange(nowClosed)
        } catch (e) {
            logWarn "Child ${child.label} onClosedModeChange failed: ${e.message}"
        }
    }
}

// True if the hub's current mode is one of the configured keep-closed modes.
boolean isClosedMode() {
    List modes = (settings.closedModes ?: []) as List
    return modes.contains(location.mode)
}

// ============================================================
// Logging helpers (level-gated), shared by parent and children
// ============================================================

private int currentLogLevel() { (settings.logLevel ?: "3") as int }

void logError(String msg) { if (currentLogLevel() >= 1) log.error "CurtainScheduler: ${msg}" }
void logWarn (String msg) { if (currentLogLevel() >= 2) log.warn  "CurtainScheduler: ${msg}" }
void logInfo (String msg) { if (currentLogLevel() >= 3) log.info  "CurtainScheduler: ${msg}" }
void logDebug(String msg) { if (currentLogLevel() >= 4) log.debug "CurtainScheduler: ${msg}" }