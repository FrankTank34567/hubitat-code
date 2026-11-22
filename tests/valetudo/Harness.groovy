// Offline test harness for drivers/Valetudo Robot Vacuum.groovy: mocks the Hubitat runtime and
// serves REST GETs from fixtures captured from the real robots.
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

class H {
    static int pass = 0, fail = 0
    static void check(String name, boolean ok, String detail = "") {
        if (ok) { pass++; println "PASS  ${name}" } else { fail++; println "FAIL  ${name}  ${detail}" }
    }
}

def newDriver(String fixtureName, Map prefs = [:]) {
    def fx = new JsonSlurper().parse(new File("fixtures/${fixtureName}.json"))
    def events = [], puts = [], pubs = [], logs = [], gets = []
    def attrs = [mqttStatus: "connected"]
    def st = [:]
    def clock = [t: 1_800_000_000_000L], runIns = [], unscheduled = []
    def settings = [robotIp: "10.0.0.9", logEnable: false, txtEnable: true, topicPrefix: "valetudo",
                    robotIdentifier: "Quay2", restFallback: true] + prefs
    def b = new Binding()
    settings.each { k, v -> b.setVariable(k, v) }
    b.setVariable("settings", settings)
    b.setVariable("state", st)
    b.setVariable("device", [displayName: "T", id: 1, currentValue: { String n -> attrs[n] },
                             updateSetting: { a, c -> }])
    b.setVariable("log", [info: { logs << "I " + it }, warn: { logs << "W " + it },
                          debug: { logs << "D " + it }, error: { logs << "E " + it }])
    b.setVariable("interfaces", [mqtt: [publish: { t, p, q, r -> pubs << [t, p] }, isConnected: { true },
                                        parseMessage: { d -> new JsonSlurper().parseText(d) }, subscribe: { t, q -> }, connect: { a, b2, c, d -> }, disconnect: {}]])
    b.setVariable("sendEvent", { Map m -> events << m; attrs[m.name] = m.value })
    b.setVariable("runIn", { Object[] a -> runIns << a })
    b.setVariable("runEvery5Minutes", { a -> })
    b.setVariable("unschedule", { a -> unscheduled << a })
    b.setVariable("now", { clock.t })
    b.setVariable("location", [timeZone: TimeZone.getTimeZone("America/Denver")])

    b.setVariable("httpGet", { Map p, Closure c ->
        String path = (p.uri as String).replaceFirst(/^http:\/\/[^\/]+/, "")
        gets << path
        def data = fx[path]
        if (data == null || (data instanceof Map && data.__error)) throw new RuntimeException("404 ${path}")
        c([data: data, status: 200])
    })
    b.setVariable("httpPut", { Map p, Closure c -> puts << [p.uri, p.body]; c([status: 200]) })
    def d = new ValetudoDriver(b)
    return [d: d, events: events, puts: puts, pubs: pubs, logs: logs, gets: gets, attrs: attrs, st: st, settings: settings, b: b, clock: clock, runIns: runIns, unscheduled: unscheduled]
}

def lastEvent(ctx, String n) { ctx.events.findAll { it.name == n }.collect { it.value }.with { it ? it[-1] : null } }

// ── V50 ─────────────────────────────────────────────────────────────────────
def v = newDriver("v50")
// Before discovery: old behaviour, everything passes.
v.d.setFanSpeed("turbo")
H.check("pre-discovery: turbo fan preset still sent (3.12.0 behaviour)", v.pubs.any { it[1] == "turbo" })
v.pubs.clear()

v.d.discoverRobot()
H.check("discover: robotModel", lastEvent(v, "robotModel") == "MOVA V50", "${lastEvent(v,'robotModel')}")
H.check("discover: unsupportedCommands none (V50 has all 23 capabilities)", lastEvent(v, "unsupportedCommands") == "none", "${lastEvent(v,'unsupportedCommands')}")
def so = new JsonSlurper().parseText(lastEvent(v, "supportedOptions"))
H.check("discover: fan options", so.fanSpeed == ["low","medium","high","max"], "${so.fanSpeed}")
H.check("discover: water options", so.waterLevel == ["min","low","medium","high","max"], "${so.waterLevel}")
H.check("discover: routes/intervals/carpet present", so.cleanRoute && so.autoEmptyInterval && so.carpetSensorMode, "${so}")
H.check("discover: dustbin attachment not supported", lastEvent(v, "attachment_dustbin") == "not supported")
H.check("discover: watertank attachment not supported", lastEvent(v, "attachment_watertank") == "not supported")
H.check("discover: mop attachment untouched", lastEvent(v, "attachment_mop") == null)

v.d.setFanSpeed("turbo")
H.check("V50: fan 'turbo' blocked, nothing published", v.pubs.isEmpty() && v.logs.any { it.startsWith("W") && it.contains("Supported: low, medium, high, max") })
v.d.setFanSpeed("MAX")
H.check("V50: fan 'MAX' sent in robot casing 'max'", v.pubs.any { it[0].endsWith("FanSpeedControlCapability/preset/set") && it[1] == "max" })
v.d.setWaterLevel("off")
H.check("V50: water 'off' blocked", !v.pubs.any { it[0].contains("WaterUsage") })
v.d.setCleanRoute("Deep")
H.check("V50: clean route 'Deep' -> PUT route deep", v.puts.any { it[0].endsWith("CleanRouteControlCapability") && it[1].contains('"deep"') })
v.d.setCleanRoute("turbo")
H.check("V50: bad clean route blocked", v.puts.count { it[0].endsWith("CleanRouteControlCapability") } == 1)

v.d.refresh()
H.check("refresh: battery 90", lastEvent(v, "battery") == 90, "${lastEvent(v,'battery')}")
H.check("refresh: before quirks, batteryStatus is raw 'none'", lastEvent(v, "batteryStatus") == "none", "${lastEvent(v,'batteryStatus')}")
v.d.refreshQuirks()
H.check("quirks: Charging Limit cached", v.st.quirkValues?."Charging Limit" == "90%")
H.check("battery: docked at 90% limit -> charged", lastEvent(v, "batteryStatus") == "charged", "${lastEvent(v,'batteryStatus')}")

int putsBefore = v.puts.size()
v.d.setFanSpeedTurbo("on")
H.check("V50: setFanSpeedTurbo blocked, no PUT, no breaker hit",
        v.puts.size() == putsBefore && (v.st.restFailCount ?: 0) == 0 && v.logs.any { it.contains("has no 'Fan Speed Turbo' quirk") })

v.d.setQuirkByTitle("charging limit", "100%")
H.check("V50: setQuirkByTitle Charging Limit 100% -> PUT with V50 UUID",
        v.puts.any { it[1]?.contains("afa79ff9-2078-4d39-b960-c61c2baa42b1") && it[1].contains("100%") })

// MQTT path: level below limit -> raw flag; back at limit -> charged
v.d.parse(JsonOutput.toJson([topic: "valetudo/Quay2/BatteryStateAttribute/level", payload: "85"]))
H.check("mqtt: docked, 90 -> 85% with flag none -> discharging (3.14.0 drop)", lastEvent(v, "batteryStatus") == "discharging", "${lastEvent(v,'batteryStatus')}")
v.d.parse(JsonOutput.toJson([topic: "valetudo/Quay2/BatteryStateAttribute/level", payload: "90"]))
H.check("mqtt: 90% docked -> charged", lastEvent(v, "batteryStatus") == "charged")
v.d.parse(JsonOutput.toJson([topic: "valetudo/Quay2/StatusStateAttribute/status", payload: "cleaning"]))
H.check("mqtt: leaves dock -> discharging (3.14.0, was none)", lastEvent(v, "batteryStatus") == "discharging", "${lastEvent(v,'batteryStatus')}")

// v3.14.0 derived trend, robot keeps reporting flag "none"
def lvl = { String n -> v.d.parse(JsonOutput.toJson([topic: "valetudo/Quay2/BatteryStateAttribute/level", payload: n])) }
def sts = { String n -> v.d.parse(JsonOutput.toJson([topic: "valetudo/Quay2/StatusStateAttribute/status", payload: n])) }
lvl("70"); v.clock.t += 60_000; lvl("62")
H.check("trend: cleaning, level falling -> discharging", lastEvent(v, "batteryStatus") == "discharging")
sts("returning"); sts("docked")
H.check("trend: just docked, no rise yet -> none", lastEvent(v, "batteryStatus") == "none", "${lastEvent(v,'batteryStatus')}")
v.clock.t += 60_000; lvl("63")
H.check("trend: docked and rising -> charging", lastEvent(v, "batteryStatus") == "charging", "${lastEvent(v,'batteryStatus')}")
H.check("trend: a re-check is scheduled", v.runIns.any { it[1] == "batteryTrendCheck" })
v.clock.t += 60_000; lvl("64")
H.check("trend: still rising -> charging", lastEvent(v, "batteryStatus") == "charging")
v.clock.t += 301_000; v.d.batteryTrendCheck()
H.check("trend: no rise for 5 min, below limit -> none", lastEvent(v, "batteryStatus") == "none", "${lastEvent(v,'batteryStatus')}")
v.clock.t += 60_000; lvl("63")
H.check("trend: docked but dropping -> discharging", lastEvent(v, "batteryStatus") == "discharging", "${lastEvent(v,'batteryStatus')}")
v.clock.t += 60_000; lvl("64"); v.clock.t += 60_000; lvl("90")
H.check("trend: docked at limit -> charged beats charging", lastEvent(v, "batteryStatus") == "charged")
sts("cleaning"); v.clock.t += 60_000; lvl("91")
H.check("trend: rising off the dock is never charging", lastEvent(v, "batteryStatus") == "discharging", "${lastEvent(v,'batteryStatus')}")
def evBefore = v.events.count { it.name == "batteryStatus" }
v.clock.t += 60_000; lvl("88"); lvl("87")
H.check("trend: no duplicate events for an unchanged status", v.events.count { it.name == "batteryStatus" } == evBefore)
v.d.parse(JsonOutput.toJson([topic: "valetudo/Quay2/BatteryStateAttribute/status", payload: "discharging"]))
H.check("mqtt: real flag passes through", lastEvent(v, "batteryStatus") == "discharging")


// v3.15.0 robot offline logging (Walter reconnect loop, 2026-11-21)
def homie = { String p -> v.d.parse(JsonOutput.toJson([topic: "valetudo/Quay2/\$state", payload: p])) }
v.logs.clear(); v.runIns.clear()
homie("ready")
homie("lost")
H.check("offline: availability turns offline at once", lastEvent(v, "robotAvailability") == "offline", "${lastEvent(v,'robotAvailability')}")
H.check("offline: no warning on the first lost report", !v.logs.any { it.startsWith("W ") })
H.check("offline: first check scheduled after 2 min", v.runIns.any { it[0] == 120 && it[1] == "robotOfflineCheck" })
int availEvents = v.events.count { it.name == "robotAvailability" }
(1..50).each { v.clock.t += 3_000; homie(it % 2 ? "init" : "lost") }   // reconnect loop: init, lost, init, lost ...
H.check("offline: reconnect loop logs no warnings", !v.logs.any { it.startsWith("W ") })
H.check("offline: init does not flip availability (no event spam)", v.events.count { it.name == "robotAvailability" } == availEvents)
v.d.robotOfflineCheck()
def warns = v.logs.findAll { it.startsWith("W ") }
H.check("offline: one warning after the grace period, with count", warns.size() == 1 && warns[0].contains("26 \"lost\" reports") && warns[0].contains("since"), "${warns}")
H.check("offline: reminder scheduled every 30 min", v.runIns.any { it[0] == 1800 && it[1] == "robotOfflineCheck" })
v.clock.t += 1_800_000; v.d.robotOfflineCheck()
H.check("offline: reminder says still offline", v.logs.findAll { it.startsWith("W ") }.size() == 2 && v.logs.findAll { it.startsWith("W ") }[1].contains("still offline"))
homie("ready")
H.check("offline: ready brings it back online with one info line", lastEvent(v, "robotAvailability") == "online" && v.logs.any { it.startsWith("I ") && it.contains("back online on MQTT after") })
H.check("offline: recovery cancels the reminder", v.unscheduled.contains("robotOfflineCheck"))
v.logs.clear(); homie("lost"); v.clock.t += 30_000; homie("ready")
H.check("offline: a 30 s blip logs nothing above debug", !v.logs.any { it.startsWith("W ") || it.startsWith("I ") }, "${v.logs}")

// Alert classification
H.check("alert: drainage -> supply", v.d.severityOf("Abnormal dock drainage. Check if the drain pipe is clogged.") == "supply")
H.check("alert: gyroscope still fault", v.d.severityOf("Gyroscope sensor error") == "fault")
H.check("alert: cannot reach target still supply", v.d.severityOf("Cannot reach target") == "supply")
H.check("alert: wheel still fault", v.d.severityOf("Wheel lost floor contact") == "fault")

// enforceSupported OFF -> 3.12.0 behaviour
def v2 = newDriver("v50", [enforceSupported: false])
v2.d.discoverRobot(); v2.d.refreshQuirks()
v2.d.setFanSpeed("turbo")
H.check("enforce OFF: turbo preset sent", v2.pubs.any { it[1] == "turbo" })
v2.d.setFanSpeedTurbo("on")
H.check("enforce OFF: turbo quirk falls back to X40 UUID (old behaviour)", v2.puts.any { it[1]?.contains("017c40b7") })

// refreshSettings skips capabilities discovery says are missing
def v3 = newDriver("v50")
v3.d.discoverRobot()
v3.st.robotCaps = v3.st.robotCaps.findAll { it != "DoNotDisturbCapability" }
v3.gets.clear(); v3.d.refreshSettings()
H.check("refreshSettings: skips missing capability", !v3.gets.any { it.contains("DoNotDisturb") } && v3.gets.any { it.contains("CleanRoute") })
v3.d.setDoNotDisturb("on", "22:00", "08:00")
H.check("command for missing capability blocked", !v3.puts.any { it[0].contains("DoNotDisturb") } && v3.logs.any { it.contains("setDoNotDisturb: not supported") })

// ── X40 Ultra ──────────────────────────────────────────────────────────────
def x = newDriver("x40u")
x.d.discoverRobot(); x.d.refreshQuirks()
H.check("X40U: robotModel", lastEvent(x, "robotModel") == "Dreame X40 Ultra", "${lastEvent(x,'robotModel')}")
x.d.setFanSpeedTurbo("on")
H.check("X40U: setFanSpeedTurbo sends its own discovered UUID", x.puts.any { it[0].endsWith("QuirksCapability") && it[1].contains('"on"') })
x.d.refresh()
H.check("X40U: no Charging Limit quirk -> batteryStatus is raw flag", lastEvent(x, "batteryStatus") != null && x.st.quirkValues?."Charging Limit" == null)

println "\n${H.pass} passed, ${H.fail} failed"
System.exit(H.fail ? 1 : 0)
