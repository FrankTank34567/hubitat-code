/**
 * Frame Bridge (Hubitat app)
 *
 * Publishes every non-blocked attribute of the selected devices to the e-paper
 * frame service (frame/ in this project). Which devices appear on the panel
 * is picked here; which of their attributes show, and how, is picked on the
 * frame's own settings page (widget assignment). Credential-like attributes
 * are blocked in code and cannot be sent even if a device reports them.
 *
 * No secret or signature is used. The frame accepts posts only from the
 * source addresses on its own settings page (http://<frame>:8080/admin).
 * The app's settings page shows this hub's address so you can copy it there.
 * Keep the frame on the LAN, behind the router firewall.
 *
 * Each device also carries "capabilities": its Hubitat capability NAMES only
 * (for example "GarageDoorControl", "ContactSensor"), from
 * DeviceWrapper.getCapabilities() / Capability.name. The frame uses them to
 * pick pictures (a garage door opener gets a garage door icon). They are type
 * names, never values, so nothing sensitive is added.
 *
 * Snapshots are sent as ONE post on purpose: the frame treats every snapshot
 * as the complete device list and drops devices missing from it, so splitting
 * a snapshot into parts would remove devices. The frame rejects bodies over
 * 65536 bytes (HTTP 413); see MAX_BODY_BYTES below.
 *
 * Logging never includes attribute values, the frame URL, or response bodies.
 *
 * Notification device (0.6.0): the "Create notification device" button adds
 * a child device of type "Frame Notifier" (namespace epaperframe; paste
 * hubitat/FrameNotifierDriver.groovy into Drivers Code first). It has the
 * Notification capability, so the Notifications app and Rule Machine can
 * send to it, and it finds the frame through frameBaseUrl() below (the
 * scheme, host and port of the Frame ingest URL, nothing else). "Remove
 * notification device" deletes it; uninstalling the app deletes it too.
 *
 * Version history:
 *   0.6.0  "Create notification device" / "Remove notification device"
 *          buttons (child device "Frame Notifier"), frameBaseUrl() for the
 *          child; publishing, the blocked-attribute list and logging are
 *          unchanged.
 *   0.5.1  device labels fall back to the device name (displayName); labels
 *          and units are clipped to the frame's limits so one long label can
 *          not fail a whole snapshot; the frame URL is trimmed and URLs with
 *          user:password@ are refused; any 2xx is success; error messages
 *          are logged without host addresses; snapshot size guard.
 *   0.5.0  adds capabilities.
 *   0.4.0  first version installed on the hub.
 *
 * Installation: Apps Code > New App > paste this file > Save.
 * Then Apps > Add User App > Frame Bridge. For notifications also paste
 * hubitat/FrameNotifierDriver.groovy into Drivers Code. A Hubitat Package
 * Manager manifest is in hubitat/hpm/ (see its README for the hosting step).
 */

definition(
    name: "Frame Bridge",
    namespace: "epaperframe",
    author: "Owner",
    description: "Publish selected devices' attributes to the e-paper frame service",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String VERSION = "0.6.0"
@Field static final String NOTIFIER_NAMESPACE = "epaperframe"
@Field static final String NOTIFIER_TYPE = "Frame Notifier"

// The frame accepts at most 32 capability names of 1-40 letters/digits each.
@Field static final int MAX_CAPABILITIES = 32
// Frame limits (frame/ingest.py): label up to 128 characters, unit up to 16.
@Field static final int MAX_LABEL = 128
@Field static final int MAX_UNIT = 16
// Frame body limit is 65536 bytes (frame/config.py max_body_bytes); keep a margin.
@Field static final int MAX_BODY_BYTES = 60000

// Never sent, regardless of what the device reports. Mirrors BLOCKED_ATTRIBUTES
// in frame/config.py. Keep both lists in sync.
@Field static final Set BLOCKED_ATTRIBUTES = [
    "lockCodes", "codeChanged", "codeLength", "lastCodeName", "maxCodes",
    "passkey", "password", "token", "secret", "accessToken"
] as Set

preferences {
    page(name: "mainPage", title: "Frame Bridge", install: true, uninstall: true) {
        section("Devices") {
            input "frameDevices", "capability.*", title: "Devices to publish",
                  multiple: true, required: true
            paragraph "Every attribute each selected device reports is sent, except the " +
                      "credential-like ones blocked above. Pick which attributes actually " +
                      "show, and how, on the frame's own settings page."
        }
        section("Frame endpoint") {
            input "frameUrl", "text",
                  title: "Frame ingest URL, for example http://192.168.1.50:8080/ingest",
                  required: true
            String adminLink = frameAdminUrl()
            if (adminLink) {
                href name: "frameAdminLink", title: "Open frame settings page",
                     description: "Opens ${adminLink} in a new tab: allowed sources, panel selection, and preview.",
                     url: adminLink, style: "external"
            } else {
                paragraph "Enter the frame URL above to get a link to the frame's settings page."
            }
            paragraph "This hub's address (add it to the frame's allowed sources): " +
                      "${hubAddress() ?: '(hub address not available; check Hubitat network settings)'}"
            input "debugLogging", "bool", title: "Debug logging", defaultValue: false
        }
        section("Notifications on the frame") {
            def notifier = notifierDevice()
            if (notifier) {
                paragraph "Notification device: ${notifier.displayName}. Choose it as a notification " +
                          "device in the Notifications app or in Rule Machine, like a phone. Its messages " +
                          "show on the frame, emoji included."
                input "removeNotifier", "button", title: "Remove notification device"
            } else {
                paragraph "Show Hubitat notifications on the frame: first paste " +
                          "hubitat/FrameNotifierDriver.groovy into Drivers Code (New Driver, Save), " +
                          "then click the button. The device finds the frame through this app."
                input "createNotifier", "button", title: "Create notification device"
            }
            if (state.notifierMessage) paragraph "${state.notifierMessage}"
        }
    }
}

// ---- Notification device ---------------------------------------------------------

def appButtonHandler(String btn) {
    if (btn == "createNotifier") {
        createNotifier()
    } else if (btn == "removeNotifier") {
        removeNotifier()
    }
}

private def notifierDevice() {
    try {
        List children = getChildDevices() ?: []
        return children ? children[0] : null   // the only child this app makes
    } catch (ignored) {
        return null
    }
}

private void createNotifier() {
    if (notifierDevice()) {
        state.notifierMessage = "The notification device already exists."
        return
    }
    if (app.getInstallationState() != "COMPLETE") {
        state.notifierMessage = "Click Done once to install this app, then open it again and click " +
                                "Create notification device."
        return
    }
    try {
        addChildDevice(NOTIFIER_NAMESPACE, NOTIFIER_TYPE, "frame-notifier-${app.id}",
                       [name: NOTIFIER_TYPE, label: NOTIFIER_TYPE, isComponent: false])
        state.notifierMessage = "Created “${NOTIFIER_TYPE}”. Choose it as a notification device in the " +
                                "Notifications app or Rule Machine, and use its Notify command to send a test."
        log.info "Frame Bridge ${VERSION}: notification device created"
    } catch (e) {
        // Most often the driver is not installed yet (UnknownDeviceTypeException).
        state.notifierMessage = "Could not create the device. Install the driver first: Drivers Code > " +
                                "New Driver, paste hubitat/FrameNotifierDriver.groovy from the frame project, " +
                                "click Save, then come back here and click Create notification device again."
        log.warn "Frame Bridge ${VERSION}: notification device not created (${e.class.simpleName})"
    }
}

private void removeNotifier() {
    try {
        getChildDevices()?.each { deleteChildDevice(it.deviceNetworkId) }
        state.notifierMessage = "Notification device removed. Notifications and rules that used it need another device."
        log.info "Frame Bridge ${VERSION}: notification device removed"
    } catch (e) {
        state.notifierMessage = "Could not remove the device: it may still be used by an app. Remove it from " +
                                "those apps first."
        log.warn "Frame Bridge ${VERSION}: notification device not removed (${e.class.simpleName})"
    }
}

// Called by the child Frame Notifier device: the frame's base address
// (scheme://host:port of the ingest URL), or null when the URL is not valid.
// Never logged.
String frameBaseUrl() {
    if (validateUrlOnly()) return null
    return ingestUrl().replaceFirst('^(https?://[^/?#]+).*$', '$1')
}

def uninstalled() {
    try {
        getChildDevices()?.each { deleteChildDevice(it.deviceNetworkId) }
    } catch (ignored) { }
}

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    String problem = validateSettings()
    if (problem) {
        log.error "Frame Bridge ${VERSION} not started: ${problem}"
        return
    }
    // One handler for every event from every selected device, any attribute.
    subscribe(frameDevices, "deviceEventHandler")
    runEvery15Minutes("publishSnapshot")
    // Send the current selection now, so the panel updates when you save this page.
    publishSnapshot()
    log.info "Frame Bridge ${VERSION} started: ${frameDevices.size()} devices"
}

private String ingestUrl() {
    return (frameUrl ?: "").trim()
}

// Never echoes the URL itself: it is only described.
private String validateSettings() {
    if (!frameDevices) return "no devices selected"
    return validateUrlOnly()
}

private String validateUrlOnly() {
    String u = ingestUrl()
    if (!u.matches('^https?://[^/?#\\s]+(/\\S*)?$')) return "frame URL must start with http:// or https:// and contain no spaces"
    String authority = u.replaceFirst('^https?://([^/?#]+).*$', '$1')
    if (authority.contains("@")) return "frame URL must not contain a user name or password"
    return null
}

// Builds the settings page URL from the ingest URL the operator entered,
// for example http://192.168.1.20:8080/ingest -> http://192.168.1.20:8080/
// (the frame serves its settings page at / and /admin).
private String frameAdminUrl() {
    String u = ingestUrl()
    if (!u.matches('^https?://[^/@\\s]+.*$')) return null
    return u.replaceFirst('^(https?://[^/]+).*$', '$1/')
}

private String hubAddress() {
    try {
        return location?.hubs?.find { it.localIP }?.localIP
    } catch (ignored) {
        return null
    }
}

def deviceEventHandler(evt) {
    if (!evt?.name || BLOCKED_ATTRIBUTES.contains(evt.name)) return
    if (validateSettings()) return
    String unit = evt.unit as String
    Map payload = [
        type     : "event",
        ts       : nowSeconds(),
        device   : [id: evt.deviceId as String, label: deviceLabel(evt.device),
                    capabilities: capabilityNames(evt.device)],
        attribute: evt.name,
        value    : evt.value,
        unit     : (unit && unit.length() <= MAX_UNIT) ? unit : null
    ]
    send(payload, JsonOutput.toJson(payload))
}

def publishSnapshot() {
    if (validateSettings()) return
    List devices = (frameDevices ?: []).collect { dev ->
        Map attrs = [:]
        dev.currentStates.each { state ->
            if (state?.name && !BLOCKED_ATTRIBUTES.contains(state.name)) {
                attrs[state.name] = state.value
            }
        }
        [id: dev.id as String, label: deviceLabel(dev), attributes: attrs,
         capabilities: capabilityNames(dev)]
    }
    Map payload = [type: "snapshot", ts: nowSeconds(), devices: devices]
    String body = JsonOutput.toJson(payload)
    int size = byteLength(body)
    if (size > MAX_BODY_BYTES) {
        // Capabilities are optional: the frame keeps the last known list when
        // they are missing. Drop them before giving up.
        devices.each { it.remove("capabilities") }
        body = JsonOutput.toJson(payload)
        int trimmed = byteLength(body)
        if (trimmed > MAX_BODY_BYTES) {
            log.warn "Frame Bridge snapshot not sent: ${trimmed} bytes for ${devices.size()} devices " +
                     "exceeds the frame limit; select fewer devices"
            return
        }
        log.warn "Frame Bridge snapshot is ${size} bytes; sent without capabilities (${trimmed} bytes)"
    } else if (debugLogging) {
        log.debug "Frame Bridge snapshot: ${devices.size()} devices, ${size} bytes"
    }
    send(payload, body)
}

// The device's label, or its name when no label is set, clipped to the frame limit.
private String deviceLabel(dev) {
    String label = (dev?.displayName ?: "") as String
    return label.length() > MAX_LABEL ? label.substring(0, MAX_LABEL) : label
}

// Capability names only (e.g. "GarageDoorControl"), never attribute values.
// Anything unexpected yields an empty list rather than failing the post.
private List capabilityNames(dev) {
    try {
        List names = (dev?.capabilities ?: []).collect { it?.name as String }
        return names.findAll { it && it.matches('^[A-Za-z0-9]{1,40}$') }.unique().take(MAX_CAPABILITIES)
    } catch (ignored) {
        return []
    }
}

private int byteLength(String s) {
    return s.getBytes("UTF-8").length
}

private void send(Map payload, String body) {
    Map params = [
        uri               : ingestUrl(),
        body              : body,
        requestContentType: "application/json",
        contentType       : "application/json",
        timeout           : 10
    ]
    asynchttpPost("handleResponse", params, [type: payload.type])
}

def handleResponse(resp, data) {
    int status = 0
    try {
        status = resp.status as int
    } catch (ignored) { }
    if (resp.hasError()) {
        log.warn "Frame Bridge ${data?.type} post failed (HTTP ${status}): ${safeError(resp)}"
        return
    }
    if (status < 200 || status > 299) {
        log.warn "Frame Bridge ${data?.type} post returned HTTP ${status}"
    } else if (debugLogging) {
        log.debug "Frame Bridge ${data?.type} post accepted"
    }
}

// The error reason without URLs or host addresses, and never the response body.
private String safeError(resp) {
    String msg
    try {
        msg = resp.getErrorMessage() as String
    } catch (ignored) {
        msg = null
    }
    if (!msg) return "no error message"
    msg = msg.replaceAll('https?://\\S+', '<frame>')
             .replaceAll('\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b', '<frame>')
    return msg.length() > 200 ? msg.substring(0, 200) : msg
}

private String nowSeconds() {
    return ((now() / 1000L) as Long).toString()
}