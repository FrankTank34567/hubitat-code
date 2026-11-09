/**
 * Frame Notifier (Hubitat driver)
 *
 * A notification device that shows Hubitat notifications on the e-paper
 * frame (frame/ in this project). Pick it in the Notifications app or in a
 * Rule Machine "send notification" action like a phone. Emoji are kept.
 *
 * Install: Drivers Code > New Driver > paste this file > Save. Then open the
 * Frame Bridge app (0.6.0 or newer) and click "Create notification device":
 * the device it creates finds the frame through the app (the address of the
 * app's "Frame ingest URL"). A device added by hand instead needs the
 * "Frame address" preference, for example http://192.168.1.20:8080
 *
 * Commands:
 *   deviceNotification(text)        capability Notification (what apps call)
 *   notify(text, priority, ttlMinutes)  priority low / normal / urgent; ttl 1-1440 minutes
 *   clear()                         removes this device's notifications from the frame
 *
 * What is sent: POST <frame>/notify with JSON {"text", "priority", "ttl_s",
 * "source"} (and POST <frame>/notify/clear for clear()). The JSON is built
 * here as pure ASCII: every character outside printable ASCII is written as a
 * JSON "backslash-u" escape of its UTF-16 code unit (four hex digits), so an
 * emoji outside the Basic Multilingual Plane becomes its surrogate pair
 * (U+1F6AA "door" is sent as the escapes for D83D and DEAA) and the frame's
 * JSON parser joins it back into the same character. Because the body is plain ASCII, it arrives intact whatever
 * character set the hub's HTTP client would otherwise pick for a String body.
 * Text is cut to 1000 UTF-16 units (the frame's limit), never in the middle
 * of a surrogate pair.
 *
 * The frame accepts these posts only from the addresses in its "Allowed
 * sources" (the same list as for Frame Bridge). Non-2xx answers are logged
 * by status only; a connection problem or a 5xx is retried once after
 * 10 seconds. Logging never includes the notification text or the frame
 * address.
 *
 * Version history:
 *   0.1.0  first version (with Frame Bridge 0.6.0).
 */

import groovy.transform.Field

@Field static final String VERSION = "0.1.0"
@Field static final int MAX_TEXT_UNITS = 1000
@Field static final List PRIORITIES = ["low", "normal", "urgent"]

metadata {
    definition(name: "Frame Notifier", namespace: "epaperframe", author: "Owner") {
        capability "Notification"
        command "notify", [[name: "text*", type: "STRING", description: "Notification text (emoji are fine)"],
                           [name: "priority", type: "ENUM", constraints: ["normal", "urgent", "low"]],
                           [name: "ttlMinutes", type: "NUMBER", description: "Minutes to show it (1-1440)"]]
        command "clear"
        attribute "lastStatus", "string"
    }
    preferences {
        input "defaultPriority", "enum", title: "Priority for notifications from apps",
              options: ["normal", "urgent", "low"], defaultValue: "normal"
        input "defaultTtl", "number", title: "Show each notification for (minutes, 1-1440; empty: the frame's default)",
              required: false
        input "frameAddress", "text", title: "Frame address, only if this device was NOT created by Frame Bridge " +
              "(for example http://192.168.1.20:8080)", required: false
        input "logEnable", "bool", title: "Debug logging (never logs the text or the address)", defaultValue: false
    }
}

def installed() {
    sendEvent(name: "lastStatus", value: "ready")
}

def updated() {
    if (logEnable) log.debug "Frame Notifier ${VERSION} settings saved"
}

// capability Notification
def deviceNotification(text) {
    send(text as String, (defaultPriority ?: "normal") as String, defaultTtl)
}

def notify(text, priority = null, ttlMinutes = null) {
    send(text as String, (priority ?: defaultPriority ?: "normal") as String, ttlMinutes != null ? ttlMinutes : defaultTtl)
}

def clear() {
    String base = frameBase()
    if (!base) {
        problem("no frame address (create this device from Frame Bridge, or set Frame address)")
        return
    }
    post(base, "/notify/clear", "{\"all\":true,\"source\":${jsonString(sourceName())}}", 1)
}

private void send(String text, String priority, ttlMinutes) {
    if (text == null || !text.trim()) {
        problem("empty notification not sent")
        return
    }
    String base = frameBase()
    if (!base) {
        problem("no frame address (create this device from Frame Bridge, or set Frame address)")
        return
    }
    if (!PRIORITIES.contains(priority)) priority = "normal"
    String body = "{\"text\":${jsonString(clip(text))},\"priority\":\"${priority}\",\"source\":${jsonString(sourceName())}"
    Integer ttl = ttlSeconds(ttlMinutes)
    if (ttl != null) body += ",\"ttl_s\":${ttl}"
    body += "}"
    post(base, "/notify", body, 1)
}

private Integer ttlSeconds(value) {
    if (value == null || value == "") return null
    try {
        BigDecimal minutes = new BigDecimal(value.toString())
        int m = Math.max(1, Math.min(1440, minutes.intValue()))
        return m * 60
    } catch (ignored) {
        return null
    }
}

private String sourceName() {
    String name = "Hubitat " + (device?.displayName ?: "Frame Notifier")
    return name.length() > 64 ? name.substring(0, 64) : name
}

// At most MAX_TEXT_UNITS UTF-16 units, never ending in half a surrogate pair.
private String clip(String text) {
    if (text.length() <= MAX_TEXT_UNITS) return text
    String cut = text.substring(0, MAX_TEXT_UNITS)
    if (Character.isHighSurrogate(cut.charAt(cut.length() - 1))) cut = cut.substring(0, cut.length() - 1)
    return cut
}

// A JSON string literal using only printable ASCII: other characters become
// backslash-u escapes of their UTF-16 code units (astral emoji become surrogate pairs).
private String jsonString(String s) {
    StringBuilder out = new StringBuilder("\"")
    for (int i = 0; i < s.length(); i++) {
        int code = (int) s.charAt(i)
        if (code == 34) {            // double quote
            out.append("\\\"")
        } else if (code == 92) {     // backslash
            out.append("\\\\")
        } else if (code < 0x20 || code > 0x7E) {
            out.append("\\u").append(Integer.toHexString(code).padLeft(4, "0"))
        } else {
            out.append(s.charAt(i))
        }
    }
    return out.append("\"").toString()
}

// The frame's base address: from the parent Frame Bridge app when this device
// is its child, else the "Frame address" preference. http(s) only, no
// user:password@, no path. Never logged.
private String frameBase() {
    String base = null
    try {
        if (parent != null) base = parent.frameBaseUrl() as String
    } catch (ignored) {
        base = null
    }
    if (!base) base = (frameAddress ?: "").trim()
    if (!base) return null
    if (!base.matches('^https?://[^/?#\\s@]+(/.*)?$')) return null
    return base.replaceFirst('^(https?://[^/?#]+).*$', '$1')
}

private void post(String base, String path, String body, int attempt) {
    Map params = [
        uri               : base + path,
        body              : body,
        requestContentType: "application/json",
        contentType       : "application/json",
        timeout           : 15
    ]
    asynchttpPost("handlePost", params, [path: path, body: body, attempt: attempt])
}

def handlePost(resp, data) {
    int status = 0
    try {
        status = resp.getStatus() as int
    } catch (ignored) { }
    String what = data?.path == "/notify/clear" ? "clear" : "notification"
    if (status >= 200 && status < 300) {
        sendEvent(name: "lastStatus", value: "${what} delivered")
        if (logEnable) log.debug "Frame Notifier ${what} delivered"
        return
    }
    boolean retry = (status == 0 || status >= 500 || status == 408) && (data?.attempt ?: 1) < 2
    if (status == 403) {
        problem("the frame refused the ${what} (HTTP 403): add this hub's address to the frame's Allowed sources")
    } else if (status == 413 || status == 400) {
        problem("the frame refused the ${what} (HTTP ${status})")
    } else {
        problem("${what} not delivered (HTTP ${status == 0 ? 'no answer' : status})" + (retry ? ", retrying once" : ""))
    }
    if (retry) {
        runIn(10, "retryPost", [data: [path: data.path, body: data.body], overwrite: false])
    }
}

def retryPost(Map data) {
    String base = frameBase()
    if (!base || !data?.path || !data?.body) return
    post(base, data.path as String, data.body as String, 2)
}

private void problem(String message) {
    log.warn "Frame Notifier: ${message}"
    sendEvent(name: "lastStatus", value: message.length() > 200 ? message.substring(0, 200) : message)
}