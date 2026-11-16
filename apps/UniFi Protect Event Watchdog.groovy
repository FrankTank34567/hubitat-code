/**
 *  UniFi Protect Event Watchdog  --  v3.0.0
 *
 *  Detects a silently dead UniFi Protect event websocket by watching for
 *  staleness in motion events, then attempts rate-limited remediation by
 *  calling initialize() on the Protect NVR/server device.
 *
 *  DESIGN
 *   - Staleness of motion events is the primary signal, NOT socket status.
 *     A disconnected socket is easy to see; a socket reporting "connected"
 *     that has stopped delivering events is the failure mode that bites.
 *   - Socket status is an optional secondary trigger with a grace period,
 *     so we never race the driver's own reconnect logic.
 *   - Every remediation path is rate limited and has a hard give-up.
 *   - Limitation: staleness cannot tell "stream dead" from "nothing moved".
 *     A genuinely quiet house (vacation, snow) will trigger the alarm and
 *     use up the remediation attempts. That is safe (rate limited, capped),
 *     just noisy. Select every camera to make this rare.
 *
 *  CHANGES IN v3.0.0 (audit 2026-11-15)
 *   - singleThreaded: true. atomicState alone does not make read-modify-write
 *     of lists atomic; handlers could still interleave. The hub now runs one
 *     handler at a time for this app.
 *   - NVR selector is capability.initialize. Hubitat inputs accept a single
 *     capability; the v2 "capability.initialize,capability.refresh" string
 *     was not a supported form.
 *   - Socket trigger: the attribute name and its healthy value(s) are now
 *     inputs. Drivers differ (webSocketStatus, commStatus, realtime, ...).
 *     Any value that is not healthy counts as down, and the app warns if the
 *     selected device does not have the attribute. Default grace raised to
 *     15 minutes (tomw's driver backs off up to 10 minutes on its own).
 *   - Smart-detection attribute name is an input (drivers differ:
 *     smartDetections, smartDetectType, ...).
 *   - Notifications suppressed by quiet hours are no longer lost: "stale"
 *     and "gaveup" are held and sent when quiet hours end, if still relevant.
 *   - Hub reboot restarts the staleness clock, so a long hub outage is not
 *     mistaken for a dead stream.
 *   - Recovery clears the alarm and lockout but keeps the 24h attempt
 *     history, so a flapping stream cannot exceed the daily cap.
 *   - Scheduled preventative initialize skips if a remediation attempt ran
 *     within the minimum-gap window.
 *
 *  PORTABILITY
 *   Nothing here is site-specific. Every device, threshold, and window is an
 *   input. To deploy at another house, install and select that hub's devices.
 *   Keep the namespace identical across sites so code updates stay consistent.
 *
 *  Install: Apps Code > New App > paste > Save.
 *           Then Apps > Add User App > UniFi Protect Event Watchdog.
 *  Upgrading from v2: paste over the existing code and Save, then open the
 *  app and press Done once. Re-select the NVR device if the selector is empty.
 */

import groovy.transform.Field

@Field static final String  APP_VERSION = "3.0.0"
@Field static final Integer HEARTBEAT_THROTTLE_MS = 30000
@Field static final Long    DAY_MS = 86400000L

definition(
    name: "UniFi Protect Event Watchdog",
    namespace: "watchdog",
    author: "local",
    description: "Detects and recovers from a silently dead UniFi Protect event stream.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleThreaded: true
)

preferences {
    page(name: "mainPage")
}

// ---------------------------------------------------------------- UI

def mainPage() {
    dynamicPage(name: "mainPage", title: "UniFi Protect Event Watchdog v${APP_VERSION}", install: true, uninstall: true) {

        section("<b>What to watch</b>") {
            input name: "cameras", type: "capability.motionSensor", title: "Protect camera devices",
                multiple: true, required: true,
                description: "Select ALL your Protect cameras. More cameras means fewer false alarms."
            input name: "nvrDevice", type: "capability.initialize", title: "Protect NVR / controller device",
                multiple: false, required: false,
                description: "Required for auto-remediation. Leave blank for notify-only mode."
            input name: "alsoWatchSmartDetections", type: "bool",
                title: "Also treat smart-detection events as heartbeats",
                defaultValue: false, submitOnChange: true,
                description: "Usually redundant, since smart detections accompany motion. " +
                             "Enable only if you have a camera reporting detections without motion."
            if (alsoWatchSmartDetections) {
                input name: "smartAttribute", type: "text",
                    title: "Smart-detection attribute name on the camera devices",
                    defaultValue: "smartDetections", required: true,
                    description: "Check a camera's Current States. tomw's driver uses smartDetectType."
            }
        }

        section("<b>Staleness detection</b>") {
            input name: "staleHours", type: "decimal",
                title: "Consider the event stream dead after this many hours with no event from ANY selected camera",
                defaultValue: 8, required: true
            input name: "useActiveHours", type: "bool", title: "Only raise the alarm during an active-hours window",
                defaultValue: true, submitOnChange: true
            if (useActiveHours) {
                input name: "activeStart", type: "time", title: "Active window starts", required: true
                input name: "activeEnd", type: "time", title: "Active window ends", required: true
                paragraph "<small>Overnight quiet is normal and would otherwise trigger false alarms. " +
                          "The staleness clock still runs overnight; this only controls when the alarm " +
                          "may fire. Windows crossing midnight are handled correctly.</small>"
            }
            input name: "checkMinutes", type: "enum", title: "How often to evaluate",
                options: ["5": "Every 5 minutes", "10": "Every 10 minutes", "15": "Every 15 minutes", "30": "Every 30 minutes"],
                defaultValue: "15", required: true
        }

        section("<b>Secondary trigger: socket status</b>") {
            input name: "watchSocket", type: "bool",
                title: "Also remediate if the NVR device reports its socket is down",
                defaultValue: false, submitOnChange: true
            if (watchSocket) {
                input name: "socketAttribute", type: "text",
                    title: "Socket status attribute on the NVR device",
                    defaultValue: "webSocketStatus", required: true,
                    description: "Check the NVR device's Current States. Examples: webSocketStatus, " +
                                 "commStatus (tomw), realtime (dailous)."
                input name: "socketHealthyValues", type: "text",
                    title: "Value(s) that mean healthy, comma separated",
                    defaultValue: "connected", required: true,
                    description: "Anything else counts as down. tomw's commStatus uses: good"
                input name: "socketGraceMinutes", type: "number",
                    title: "...but only if still down after this many minutes",
                    defaultValue: 15, required: true
                paragraph "<small>The grace period lets the driver's own reconnect logic go first. " +
                          "Some drivers back off up to 10 minutes between reconnects, so keep this above " +
                          "that. Off by default; staleness detection alone is usually sufficient.</small>"
            }
        }

        section("<b>Remediation and rate limiting</b>") {
            input name: "autoRemediate", type: "bool",
                title: "Attempt automatic recovery (call initialize on the NVR device)",
                defaultValue: true, submitOnChange: true
            if (autoRemediate) {
                input name: "minMinutesBetweenAttempts", type: "number",
                    title: "Minimum minutes between remediation attempts (hard floor)",
                    defaultValue: 15, required: true
                input name: "maxAttemptsPerDay", type: "number",
                    title: "Maximum attempts per rolling 24 hours, then lock out",
                    defaultValue: 4, required: true
                paragraph "<small>After the cap the app stops touching the NVR and notifies you. " +
                          "It resumes when a real event arrives or you reset it below. The 24h attempt " +
                          "history survives a recovery, so a flapping stream cannot exceed the cap.</small>"
            }
            input name: "scheduledReinit", type: "enum",
                title: "Also run a preventative initialize on a schedule",
                options: ["none": "Never", "daily": "Once daily", "12h": "Every 12 hours"],
                defaultValue: "none", required: true, submitOnChange: true
            if (scheduledReinit && scheduledReinit != "none") {
                input name: "reinitHour", type: "number", title: "Hour of day to run it (0-23)",
                    defaultValue: 3, required: true
            }
        }

        section("<b>Notifications</b>") {
            input name: "notifyDevices", type: "capability.notification", title: "Send notifications to",
                multiple: true, required: false
            input name: "notifyOn", type: "enum", title: "Notify me about",
                options: [
                    "stale":     "Event stream appears dead",
                    "attempt":   "A remediation attempt was made",
                    "recovered": "Event stream recovered",
                    "gaveup":    "Gave up, manual intervention needed",
                    "scheduled": "Scheduled preventative initialize ran"
                ],
                multiple: true, defaultValue: ["stale", "gaveup", "recovered"], required: false
            input name: "quietHours", type: "bool", title: "Hold notifications during quiet hours",
                defaultValue: false, submitOnChange: true
            if (quietHours) {
                input name: "quietStart", type: "time", title: "Quiet hours start", required: true
                input name: "quietEnd", type: "time", title: "Quiet hours end", required: true
                input name: "quietOverrideGaveUp", type: "bool",
                    title: "Still notify during quiet hours for 'gave up'", defaultValue: true
                paragraph "<small>Held 'dead' and 'gave up' alerts are sent when quiet hours end, " +
                          "if the problem is still current. Other types are dropped.</small>"
            }
            input name: "maxNotificationsPerDay", type: "number",
                title: "Maximum notifications per rolling 24 hours (0 = unlimited)",
                defaultValue: 10, required: true
        }

        section("<b>Status and manual controls</b>") {
            paragraph statusSummary()
            input name: "btnTestNotify", type: "button", title: "Send test notification"
            input name: "btnRemediateNow", type: "button", title: "Remediate now (ignores rate limits)"
            input name: "btnResetLockout", type: "button", title: "Reset lockout and counters"
        }

        section("<b>Logging</b>") {
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
            input name: "txtEnable", type: "bool", title: "Enable descriptive info logging", defaultValue: true
            input name: "logMinutes", type: "number",
                title: "Auto-disable debug logging after N minutes (0 = never)", defaultValue: 60
        }

        section() {
            label title: "Name this app instance", required: false
        }
    }
}

private String statusSummary() {
    List parts = []
    Long last = asLong(atomicState.lastEventEpoch)
    if (last) {
        Long mins = ((now() - last) / 60000L) as Long
        parts << "Last event from any watched camera: ${formatAge(mins)} ago " +
                 "(${new Date(last).format('yyyy-MM-dd HH:mm:ss', location.timeZone)})"
    } else {
        parts << "Last event: none recorded yet. The clock starts at the first event after install."
    }
    parts << "Current state: ${atomicState.alarmActive ? '<b>ALARM - stream appears dead</b>' : 'OK'}"
    parts << "Lockout: ${atomicState.lockedOut ? '<b>YES - manual intervention needed</b>' : 'no'}"
    parts << "Remediation attempts in last 24h: ${prunedAttempts().size()}"
    Long la = asLong(atomicState.lastAttemptEpoch)
    if (la) parts << "Last attempt: ${new Date(la).format('yyyy-MM-dd HH:mm:ss', location.timeZone)}"
    parts << "Notifications sent in last 24h: ${prunedNotifications().size()}"
    if (atomicState.pendingNotice) parts << "Held by quiet hours: ${atomicState.pendingNotice.type}"
    return parts.join("<br>")
}

private String formatAge(Long mins) {
    if (mins == null) return "unknown"
    if (mins < 60) return "${mins} min"
    Long h = (mins / 60) as Long
    Long m = (mins % 60) as Long
    if (h < 48) return "${h}h ${m}m"
    return "${(h / 24) as Long}d ${(h % 24) as Long}h"
}

// ---------------------------------------------------------------- lifecycle

void installed() {
    logInfo "installed v${APP_VERSION}"
    atomicState.lastEventEpoch = now()
    atomicState.attempts = []
    atomicState.notifications = []
    atomicState.alarmActive = false
    atomicState.lockedOut = false
    initialize()
}

void updated() {
    logInfo "updated v${APP_VERSION}"
    unsubscribe()
    unschedule()
    initialize()
}

void uninstalled() {
    unsubscribe()
    unschedule()
}

void initialize() {
    // Defensive defaults: updated() can run before installed() in some edit flows.
    if (atomicState.lastEventEpoch == null) atomicState.lastEventEpoch = now()
    if (atomicState.attempts == null) atomicState.attempts = []
    if (atomicState.notifications == null) atomicState.notifications = []
    if (atomicState.alarmActive == null) atomicState.alarmActive = false
    if (atomicState.lockedOut == null) atomicState.lockedOut = false

    if (!cameras) {
        log.warn "Watchdog: no cameras selected; watchdog is inert"
        return
    }

    subscribe(cameras, "motion.active", "eventHandler")
    if (alsoWatchSmartDetections) {
        String smartAttr = smartAttribute ?: "smartDetections"
        if (!cameras.any { it.hasAttribute(smartAttr) }) {
            log.warn "Watchdog: no selected camera has attribute '${smartAttr}'; smart-detection heartbeats will never arrive"
        }
        subscribe(cameras, smartAttr, "eventHandler")
    }

    if (watchSocket && nvrDevice) {
        String attr = socketAttribute ?: "webSocketStatus"
        if (nvrDevice.hasAttribute(attr)) {
            subscribe(nvrDevice, attr, "socketHandler")
        } else {
            log.warn "Watchdog: ${nvrDevice.displayName} has no attribute '${attr}'; socket trigger disabled. " +
                     "Check the device's Current States for the right attribute name."
        }
    }

    // A hub reboot interrupts every event source; restart the clock instead of
    // treating the outage as a dead stream.
    subscribe(location, "systemStart", "systemStartHandler")

    switch ((checkMinutes ?: "15") as Integer) {
        case 5:  runEvery5Minutes("checkStaleness");  break
        case 10: runEvery10Minutes("checkStaleness"); break
        case 30: runEvery30Minutes("checkStaleness"); break
        default: runEvery15Minutes("checkStaleness"); break
    }

    Integer h = ((reinitHour ?: 3) as Integer)
    if (h < 0 || h > 23) h = 3
    if (scheduledReinit == "daily") {
        schedule("0 0 ${h} * * ?", "scheduledReinitHandler")
    } else if (scheduledReinit == "12h") {
        schedule("0 0 ${h},${(h + 12) % 24} * * ?", "scheduledReinitHandler")
    }

    if (logEnable && ((logMinutes ?: 0) as Integer) > 0) {
        runIn(((logMinutes as Integer) * 60), "disableDebugLogging")
    }

    logInfo "initialized; watching ${cameras.size()} camera(s), " +
            "stale threshold ${staleHours}h, autoRemediate=${autoRemediate}"
}

void disableDebugLogging() {
    log.info "Watchdog: auto-disabling debug logging"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

private void logDebug(String msg) { if (logEnable) log.debug "Watchdog: ${msg}" }
private void logInfo(String msg)  { if (txtEnable != false) log.info "Watchdog: ${msg}" }

// ---------------------------------------------------------------- signals

void eventHandler(evt) {
    Long nowMs = now()
    Boolean wasAlarming = (atomicState.alarmActive == true)

    // Throttle heartbeat writes. Minute-level resolution is all the staleness
    // check needs, and cameras can fire hundreds of times a day.
    Long last = asLong(atomicState.lastEventEpoch)
    if (wasAlarming || last == null || (nowMs - last) > HEARTBEAT_THROTTLE_MS) {
        atomicState.lastEventEpoch = nowMs
    }

    if (wasAlarming) {
        atomicState.alarmActive = false
        atomicState.lockedOut = false
        atomicState.pendingNotice = null
        // Attempt history is kept on purpose: the rolling 24h cap must hold
        // across recoveries, or a flapping stream could hammer the NVR.
        logInfo "recovered on event from ${evt.displayName}"
        notifyUser("recovered",
            "UniFi Protect event stream RECOVERED. First event: ${evt.displayName} (${evt.name}).")
    } else {
        logDebug "heartbeat from ${evt.displayName} (${evt.name}=${evt.value})"
    }
}

void socketHandler(evt) {
    if (!watchSocket) return
    logDebug "${evt.name} = ${evt.value}"
    if (isSocketHealthy(evt.value as String)) {
        unschedule("confirmSocketDown")
    } else {
        Integer graceSec = ((socketGraceMinutes ?: 15) as Integer) * 60
        runIn(graceSec, "confirmSocketDown", [overwrite: true])
    }
}

void confirmSocketDown() {
    String attr = socketAttribute ?: "webSocketStatus"
    String current = nvrDevice?.currentValue(attr) as String
    if (!isSocketHealthy(current)) {
        logInfo "${attr} still '${current}' after ${socketGraceMinutes}min grace; raising alarm"
        raiseAlarm("${attr} has been '${current}' for ${socketGraceMinutes} minutes.")
    } else {
        logDebug "socket recovered on its own during grace period"
    }
}

private Boolean isSocketHealthy(String value) {
    if (value == null) return false
    List healthy = ((socketHealthyValues ?: "connected") as String).split(",")*.trim().findAll { it }
    return healthy.any { it.equalsIgnoreCase(value.trim()) }
}

void systemStartHandler(evt) {
    atomicState.lastEventEpoch = now()
    unschedule("confirmSocketDown")
    logInfo "hub restarted; staleness clock reset"
}

// ---------------------------------------------------------------- core check

void checkStaleness() {
    if (!cameras) return

    flushPendingNotice()

    Long thresholdMs = (((staleHours ?: 8) as BigDecimal) * 3600000L) as Long
    Long last = asLong(atomicState.lastEventEpoch) ?: now()
    Long age = now() - last

    logDebug "check: age=${(age / 60000L) as Long}min threshold=${(thresholdMs / 60000L) as Long}min " +
             "alarm=${atomicState.alarmActive} lockedOut=${atomicState.lockedOut}"

    if (age < thresholdMs) return

    if (useActiveHours && !inWindow(activeStart, activeEnd)) {
        logDebug "stale, but outside active-hours window; holding"
        return
    }

    raiseAlarm("No event from any watched camera in ${formatAge((age / 60000L) as Long)}.")
}

private void raiseAlarm(String reason) {
    if (atomicState.alarmActive != true) {
        atomicState.alarmActive = true
        log.warn "Watchdog: ALARM - ${reason}"
        notifyUser("stale", "UniFi Protect event stream appears DEAD. ${reason}")
    }

    if (!autoRemediate)        { logDebug "auto-remediation disabled; notify only"; return }
    if (!nvrDevice)            { logDebug "no NVR device selected; cannot remediate"; return }
    if (atomicState.lockedOut) { logDebug "locked out; not attempting"; return }

    attemptRemediation(false)
}

// ---------------------------------------------------------------- remediation

private void attemptRemediation(Boolean manual) {
    if (!nvrDevice) {
        log.warn "Watchdog: no NVR device selected"
        return
    }

    if (!manual) {
        // Hard floor between attempts. Primary anti-hammering guard.
        Long floorMs = ((minMinutesBetweenAttempts ?: 15) as Integer) * 60000L
        Long lastAttempt = asLong(atomicState.lastAttemptEpoch)
        if (lastAttempt && (now() - lastAttempt) < floorMs) {
            logDebug "rate limited: last attempt ${((now() - lastAttempt) / 60000L) as Long}min ago"
            return
        }

        // Rolling 24h cap, then give up entirely.
        List attempts = prunedAttempts()
        Integer cap = (maxAttemptsPerDay ?: 4) as Integer
        if (attempts.size() >= cap) {
            atomicState.lockedOut = true
            log.warn "Watchdog: attempt cap (${cap}/24h) reached; locking out"
            notifyUser("gaveup",
                "UniFi Protect watchdog has GIVEN UP after ${attempts.size()} recovery attempts in 24h. " +
                "No camera event since. Either the event stream is dead or nothing has moved. " +
                "Manual check needed.")
            return
        }
    }

    if (!nvrDevice.hasCommand("initialize")) {
        log.warn "Watchdog: ${nvrDevice.displayName} has no initialize() command; cannot remediate"
        atomicState.lockedOut = true
        notifyUser("gaveup",
            "UniFi Protect watchdog cannot remediate: ${nvrDevice.displayName} has no initialize command. " +
            "Detection still works; recovery does not.")
        return
    }

    // Reassign rather than mutate: atomicState does not persist in-place edits.
    List attempts = prunedAttempts()
    attempts << now()
    atomicState.attempts = attempts
    atomicState.lastAttemptEpoch = now()

    logInfo "calling initialize() on ${nvrDevice.displayName} " +
            "(attempt ${attempts.size()} of ${maxAttemptsPerDay ?: 4} in 24h, manual=${manual})"

    try {
        nvrDevice.initialize()
    } catch (e) {
        log.error "Watchdog: initialize() threw: ${e.message}"
        notifyUser("gaveup", "UniFi Protect watchdog: initialize() failed with an error: ${e.message}")
        return
    }

    notifyUser("attempt",
        "UniFi Protect watchdog attempted recovery on ${nvrDevice.displayName}. " +
        "Attempt ${attempts.size()} of ${maxAttemptsPerDay ?: 4} in 24h.")

    // No verify handler. The next scheduled checkStaleness re-evaluates and is
    // already governed by the floor and the cap, so a separate retry path would
    // only add a second way to hammer the NVR.
}

void scheduledReinitHandler() {
    if (!nvrDevice?.hasCommand("initialize")) return

    // Do not stack a preventative initialize on top of a recent remediation.
    Long floorMs = ((minMinutesBetweenAttempts ?: 15) as Integer) * 60000L
    Long lastAttempt = asLong(atomicState.lastAttemptEpoch)
    if (lastAttempt && (now() - lastAttempt) < floorMs) {
        logInfo "skipping scheduled initialize: remediation ran ${((now() - lastAttempt) / 60000L) as Long}min ago"
        return
    }

    logInfo "scheduled preventative initialize on ${nvrDevice.displayName}"
    try {
        nvrDevice.initialize()
        notifyUser("scheduled", "UniFi Protect watchdog ran its scheduled preventative initialize.")
    } catch (e) {
        log.error "Watchdog: scheduled initialize() threw: ${e.message}"
    }
}

// ---------------------------------------------------------------- notifications

private void notifyUser(String type, String message) {
    if (!notifyDevices) return
    if (!notifyOn || !notifyOn.contains(type)) {
        logDebug "notification type '${type}' not selected; suppressed"
        return
    }

    Boolean isGaveUp = (type == "gaveup")

    if (quietHours && inWindow(quietStart, quietEnd) && !(isGaveUp && quietOverrideGaveUp)) {
        if (type in ["stale", "gaveup"]) {
            // Hold it; flushPendingNotice() sends it after quiet hours if still current.
            atomicState.pendingNotice = [type: type, message: message]
            logInfo "holding '${type}' notification until quiet hours end"
        } else {
            logInfo "suppressed '${type}' notification during quiet hours"
        }
        return
    }

    List sent = prunedNotifications()
    Integer cap = (maxNotificationsPerDay ?: 10) as Integer
    if (cap > 0 && sent.size() >= cap && !isGaveUp) {
        log.warn "Watchdog: notification cap (${cap}/24h) reached; suppressing '${type}'"
        return
    }

    sent << now()
    atomicState.notifications = sent

    logInfo "notify [${type}]: ${message}"
    notifyDevices.each { d ->
        try { d.deviceNotification(message) }
        catch (e) { log.warn "Watchdog: failed to notify ${d.displayName}: ${e.message}" }
    }
}

private void flushPendingNotice() {
    Map pending = atomicState.pendingNotice as Map
    if (!pending) return
    if (quietHours && inWindow(quietStart, quietEnd)) return

    atomicState.pendingNotice = null
    if (atomicState.alarmActive != true) {
        logDebug "dropping held '${pending.type}' notification; alarm already cleared"
        return
    }
    notifyUser(pending.type as String, "${pending.message} (held during quiet hours)")
}

// ---------------------------------------------------------------- helpers

/**
 * Time window test that handles windows crossing midnight (22:00 to 07:00).
 * Hubitat's timeOfDayIsBetween compares full date-times and is reported not
 * to wrap past midnight, so the crossing case is handled here.
 * A window with identical start and end is treated as always open.
 */
private Boolean inWindow(startPref, endPref) {
    if (!startPref || !endPref) return true
    Date rightNow = new Date()
    Date start = toDateTime(startPref)
    Date end   = toDateTime(endPref)

    if (start.before(end)) {
        return timeOfDayIsBetween(start, end, rightNow, location.timeZone)
    }
    // Crosses midnight: inside if after start OR before end.
    return rightNow.after(start) || rightNow.before(end)
}

/** atomicState round-trips through JSON; numbers can return as Integer or BigInteger. */
private Long asLong(v) {
    if (v == null) return null
    try { return (v as Long) } catch (e) { return null }
}

private List prunedAttempts() {
    Long cutoff = now() - DAY_MS
    return ((atomicState.attempts ?: []) as List).findAll { asLong(it) != null && asLong(it) > cutoff }
}

private List prunedNotifications() {
    Long cutoff = now() - DAY_MS
    return ((atomicState.notifications ?: []) as List).findAll { asLong(it) != null && asLong(it) > cutoff }
}

// ---------------------------------------------------------------- buttons

void appButtonHandler(String btn) {
    switch (btn) {
        case "btnTestNotify":
            if (!notifyDevices) { log.warn "Watchdog: no notification devices selected"; return }
            notifyDevices.each {
                try { it.deviceNotification("UniFi Protect Watchdog test notification.") }
                catch (e) { log.warn "Watchdog: test notify failed for ${it.displayName}: ${e.message}" }
            }
            logInfo "test notification sent (bypasses type filter, quiet hours, and daily cap)"
            break

        case "btnRemediateNow":
            logInfo "manual remediation requested"
            attemptRemediation(true)
            break

        case "btnResetLockout":
            atomicState.lockedOut = false
            atomicState.alarmActive = false
            atomicState.attempts = []
            atomicState.notifications = []
            atomicState.pendingNotice = null
            atomicState.lastEventEpoch = now()
            unschedule("confirmSocketDown")
            logInfo "lockout and counters reset; staleness clock restarted"
            break
    }
}
