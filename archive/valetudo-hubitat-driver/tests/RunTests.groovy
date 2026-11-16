// Usage: groovy -cp harness:harness/stub RunTests.groovy <driver.groovy> <fixturesDir> [replayOut.txt]
// Robot identifier is always the fake TESTBOT. No network access: all MQTT/HTTP is simulated.
import org.codehaus.groovy.control.CompilerConfiguration
import groovy.json.JsonOutput

def driverPath = args[0], fx = args[1], replayOut = args.length > 2 ? args[2] : null
def driverSrc = new File(driverPath).text
boolean isNew = driverSrc.contains('DRIVER_VERSION = "3.12.0"')

def cfg = new CompilerConfiguration(); cfg.scriptBaseClass = "HubitatSim"
def loader = new GroovyClassLoader(this.class.classLoader, cfg)
def driverClass = loader.parseClass(driverSrc, "Driver.groovy")

def fresh = { Map extra = [:] ->
    def d = driverClass.getDeclaredConstructor().newInstance()
    d.binding = new Binding([mqttBroker: "tcp://broker:1883", topicPrefix: "valetudo", robotIdentifier: "TESTBOT",
        robotIp: "192.0.2.10", restFallback: true, autoReconnect: true, reconnectDelay: 30,
        batteryStrandedGraceMin: 10, alertConfirmSec: 20, logEnable: true, txtEnable: true] + extra)
    d.current.mqttStatus = "connected"; d.simState.lastMqttStatus = "connected"
    // Steady state of a long-running device with no active alerts.
    d.current.putAll([activeAlerts: "none", pushAlerts: "none", alertSeverity: "none", alertCount: 0, robotError: "none"])
    d.simState.putAll([lastActiveAlerts: "", lastPushAlerts: "", lastAlertSeverity: "none", lastRobotError: ""])
    d
}

int pass = 0, fail = 0
def check = { String name, expected, actual ->
    boolean ok = (expected == actual)
    if (ok) pass++ else fail++
    println "${ok ? 'PASS' : 'FAIL'}  ${name}\n        expected: ${expected}\n        actual:   ${actual}"
}
// Push an alert through the confirmation window the way a real persisting fault would.
def settle = { d -> d.runScheduled("flushAlerts"); d.advance(21); d.runScheduled("flushAlerts") }

// ── 1. Replay of recorded live retained topics (both robots) ─────────────────
["walter", "quay"].each { r ->
    def d = fresh()
    new File("${fx}/${r}_topics.tsv").eachLine { l -> def (t, p) = l.split("\t", 2); d.mqttIn(t, p ?: "") }
    d.mqttIn("MapData/map-data", "x" * 12000)          // big blob: must be skipped entirely
    d.mqttIn("hass/TESTBOT_vacuum/state", '{"state":"docked"}')
    settle(d)
    if (replayOut) new File("${replayOut}.${r}").text = d.events.findAll { it[0] != "lastActivity" && it[0] != "driverVersion" }
        .collect { "${it[0]}=${it[1]}" }.join("\n")
    println "\n=== replay ${r}: ${d.events.size()} events, ${d.dropped.size()} dropped, warnings: ${d.warnings()}"
    check("[${r}] robotStatus", "docked", d.current.robotStatus)
    check("[${r}] battery", 99, d.current.battery)
    check("[${r}] dock_dustbag (enum)", "ok", d.current.dock_dustbag)
    check("[${r}] activeAlerts (healthy 'No error' filtered)", "none", d.current.activeAlerts)
    check("[${r}] alertCount", 0, d.current.alertCount)
    check("[${r}] consumables has the 5 real keys only", ["brush-main","brush-side_right","cleaning-sensor","cleaning-wheel","filter-main"],
          new groovy.json.JsonSlurper().parseText(d.current.consumables).keySet().sort())
    check("[${r}] no warnings/errors logged", [], d.warnings())
    if (isNew) {
        check("[${r}] carpetSensorMode (new)", "lift", d.current.carpetSensorMode)
        check("[${r}] robotErrorDetail healthy (new)", "none", d.current.robotErrorDetail)
    }
}

// ── 2. Alert classification (injected TESTBOT messages) ──────────────────────
println "\n=== alert classification"
def sevCase = { String text, String expSev, String expPush ->
    def d = fresh(); d.current.robotStatus = "cleaning"
    d.mqttIn("StatusStateAttribute/error_description", text); settle(d)
    check("'${text}' -> alertSeverity", expSev, d.current.alertSeverity)
    check("'${text}' -> pushAlerts", expPush, d.current.pushAlerts)
}
sevCase("Cannot reach target",        isNew ? "supply" : "fault", "Cannot reach target")
sevCase("Gyroscope sensor error",     isNew ? "fault"  : "info",  isNew ? "Gyroscope sensor error" : "none")
sevCase("Fan speed abnormal",         isNew ? "fault"  : "info",  isNew ? "Fan speed abnormal" : "none")
sevCase("ToF Sensor offline",         isNew ? "fault"  : "info",  isNew ? "ToF Sensor offline" : "none")
sevCase("Water pump fault",           isNew ? "fault"  : "supply","Water pump fault")
sevCase("Camera dirty",               "info",   "none")
sevCase("Robot stuck or trapped",     "fault",  "Robot stuck or trapped")
sevCase("Mop Dock Clean Water Tank empty", "supply", "Mop Dock Clean Water Tank empty")
sevCase("Failed to attach mop pads",  "mop",    "Failed to attach mop pads")

// Transient blip must never publish (3.11.0 confirmation window, unchanged)
def b = fresh(); b.current.robotStatus = "cleaning"
b.mqttIn("StatusStateAttribute/error_description", "Wheel lost floor contact"); b.runScheduled("flushAlerts")
b.advance(5); b.mqttIn("StatusStateAttribute/error_description", "No error"); b.runScheduled("flushAlerts"); b.advance(20); b.runScheduled("flushAlerts")
check("5 s wheel blip never published (activeAlerts)", "none", b.current.activeAlerts)
check("5 s wheel blip: no push events at all", [], b.events.findAll { it[0] == "pushAlerts" && it[1] != "none" })

// Battery grace (3.8.0, unchanged): docked = info; idle past grace = fault
def bat = fresh(); bat.current.robotStatus = "docked"
bat.mqttIn("StatusStateAttribute/error_description", "Low battery"); settle(bat)
check("Low battery while docked -> info, no push", ["info", "none"], [bat.current.alertSeverity, bat.current.pushAlerts])
bat.mqttIn("StatusStateAttribute/status", "idle"); bat.runScheduled("flushAlerts"); bat.advance(601); bat.runScheduled("flushAlerts")
check("Low battery idle > 10 min -> fault, pushed", ["fault", "Low battery"], [bat.current.alertSeverity, bat.current.pushAlerts])

// ValetudoEvents raise and clear (unchanged)
def ev = fresh()
ev.mqttIn("ValetudoEvents/valetudo_events", JsonOutput.toJson(["e1": [__class: "DustBinFullValetudoEvent", id: "e1", processed: false]])); settle(ev)
check("DustBinFull event -> activeAlerts", "Dust bin full", ev.current.activeAlerts)
ev.mqttIn("ValetudoEvents/valetudo_events", "{}"); ev.runScheduled("flushAlerts")
check("Empty event set clears immediately", "none", ev.current.activeAlerts)

if (isNew) {
    // ── 3. New attributes ───────────────────────────────────────────────────
    println "\n=== new attributes"
    def e = fresh()
    e.mqttIn("StatusStateAttribute/error", '{"severity":{"kind":"transient","level":"warning"},"subsystem":"navigation","message":"Cannot reach target"}')
    check("robotErrorDetail from structured error", "warning/transient/navigation: Cannot reach target", e.current.robotErrorDetail)
    int n = e.events.size(); e.mqttIn("StatusStateAttribute/error", '{"severity":{"kind":"transient","level":"warning"},"subsystem":"navigation","message":"Cannot reach target"}')
    check("robotErrorDetail republished identical -> no new event", 0, e.events.findAll { it[0] == "robotErrorDetail" }.size() - 1)
    e.mqttIn("StatusStateAttribute/error", '{"severity":{"kind":"none","level":"none"},"subsystem":"none","message":""}')
    check("robotErrorDetail healthy -> none", "none", e.current.robotErrorDetail)
    check("robotErrorDetail never touches alert slices", null, e.simState.alertSlices)
    e.mqttIn("StatusStateAttribute/error", "not json")
    check("robotErrorDetail bad JSON -> unchanged, no warning", ["none", []], [e.current.robotErrorDetail, e.warnings()])

    // ── 4. Availability ─────────────────────────────────────────────────────
    println "\n=== availability"
    def a = fresh(); 
    a.getRoutes["http://192.0.2.10/api/v2/mqtt/status"] = [state: "ready"]
    a.mqttSubscribeAll()
    check("probe scheduled after subscribe", 15, a.scheduled.availabilityProbe)
    a.runScheduled("availabilityProbe")
    check("no retained \$state + probe says ready -> online", "online", a.current.robotAvailability)
    def a2 = fresh(); a2.mqttSubscribeAll(); a2.mqttIn("\$state", "lost")
    a2.getRoutes["http://192.0.2.10/api/v2/mqtt/status"] = [state: "ready"]; a2.runScheduled("availabilityProbe")
    check("live \$state wins over probe", "offline", a2.current.robotAvailability)
    def a3 = fresh(); a3.mqttSubscribeAll(); a3.clearLogs(); a3.runScheduled("availabilityProbe")
    check("failed probe -> attribute unchanged", null, a3.current.robotAvailability)
    check("failed probe -> no warnings, breaker untouched", [[], null], [a3.warnings(), a3.simState.restFailCount])

    // ── 5. Subscriptions ────────────────────────────────────────────────────
    println "\n=== subscriptions"
    def s1 = fresh(); s1.mqttSubscribeAll()
    check("default: single wildcard (unchanged)", ["valetudo/TESTBOT/#"], s1.subscribed)
    def s2 = fresh([targetedSubscriptions: true]); s2.mqttSubscribeAll()
    check("targeted: 22 filters, no '#', no MapData blobs, no hass", [22, false, false, false],
          [s2.subscribed.size(), s2.subscribed.any { it.endsWith("#") }, s2.subscribed.any { it.contains("map-data") }, s2.subscribed.any { it.contains("hass") }])
    check("targeted: includes \$state", true, s2.subscribed.contains("valetudo/TESTBOT/\$state"))
    def s3 = fresh([targetedSubscriptions: true]); s3.failSubscribeOn(5); s3.mqttSubscribeAll()
    check("targeted failure part-way -> session dropped, flag false, reconnect scheduled",
          [1, false, 30], [s3.disconnects, s3.simState.mqttSubscribed, s3.scheduled.mqttConnect])
    def s4 = fresh([targetedSubscriptions: true]); s4.mqttSubscribeAll(); s4.mqttSubscribeAll()
    check("targeted: second call on same session does not re-subscribe", 22, s4.subscribed.size())

    // ── 6. Commands ─────────────────────────────────────────────────────────
    println "\n=== commands (MQTT up)"
    def c = fresh()
    c.resetConsumable("brush-main");      check("resetConsumable MQTT topic", ["valetudo/TESTBOT/ConsumableMonitoringCapability/brush-main/reset/set", "PERFORM"], c.published[-1][0..1])
    int before = c.published.size()
    ["../x", "a/b", "#", "+", "x y", ""].each { c.resetConsumable(it) }
    check("resetConsumable rejects ../x a/b # + 'x y' '' (nothing published)", before, c.published.size())
    c.setCarpetSensorMode("LIFT");        check("setCarpetSensorMode MQTT (lower-cased)", ["valetudo/TESTBOT/CarpetSensorModeControlCapability/mode/set", "lift"], c.published[-1][0..1])
    before = c.published.size(); c.setCarpetSensorMode("bogus")
    check("setCarpetSensorMode rejects bogus", before, c.published.size())
    c.dismissEvent("mop_attachment_reminder")
    check("dismissEvent MQTT payload", ["valetudo/TESTBOT/ValetudoEvents/valetudo_events/interact/set", '{"id":"mop_attachment_reminder","interaction":"ok"}'], c.published[-1][0..1])
    before = c.published.size(); ["x/../y", "a#", '"}{'].each { c.dismissEvent(it) }
    check("dismissEvent rejects x/../y a# quote-injection", before, c.published.size())
    c.setDoNotDisturb("off")
    check("setDoNotDisturb off -> dndWindow 'none' (was dropped '')", ["none", []], [c.current.dndWindow, c.dropped.findAll { it[0] == "dndWindow" }])

    println "\n=== commands (MQTT down -> REST fallback)"
    def rr = fresh(); rr.current.mqttStatus = "disconnected"
    rr.resetConsumable("brush-side_right")
    check("resetConsumable REST path + body", ["http://192.0.2.10/api/v2/robot/capabilities/ConsumableMonitoringCapability/brush/side_right", '{"action":"reset"}'], rr.puts[-1])
    rr.resetConsumable("filter")
    check("resetConsumable REST path, key without sub_type", "http://192.0.2.10/api/v2/robot/capabilities/ConsumableMonitoringCapability/filter", rr.puts[-1][0])
    rr.setCarpetSensorMode("avoid")
    check("setCarpetSensorMode REST", ["http://192.0.2.10/api/v2/robot/capabilities/CarpetSensorModeControlCapability", '{"mode":"avoid"}'], rr.puts[-1])
    rr.dismissEvent("3fab7eb3-7bee-4455-a3b8-c4357dab59b6")
    check("dismissEvent REST", ["http://192.0.2.10/api/v2/events/3fab7eb3-7bee-4455-a3b8-c4357dab59b6/interact", '{"interaction":"ok"}'], rr.puts[-1])
    rr.start()
    check("start REST body (unchanged)", ["http://192.0.2.10/api/v2/robot/capabilities/BasicControlCapability", '{"action":"start"}'], rr.puts[-1])
    def off = fresh([restFallback: false]); off.current.mqttStatus = "disconnected"; off.dismissEvent("abc"); off.resetConsumable("brush-main")
    check("restFallback=false: dismissEvent/resetConsumable send nothing", 0, off.puts.size())

    println "\n=== REST breaker (unchanged)"
    def br = fresh(); br.current.mqttStatus = "disconnected"; br.failPutsWith(500)
    3.times { br.start() }; int putsAtTrip = br.puts.size(); br.start(); br.dismissEvent("abc")
    check("breaker opens after 3 failures; later PUTs (incl. dismissEvent) skipped", [3, 3, "breaker_open"], [putsAtTrip, br.puts.size(), br.current.restStatus])
}

// ── 6b. Exact actions from the owner's Rule Machine rules (both driver versions) ───
// Night Run, Weekday Cleaning Upstairs, Sunday 3 AM, Go home on arrival.
println "\n=== Rule Machine actions in use (must be identical in 3.11.0 and 3.12.0)"
def rm = fresh()
rm.setFanSpeed("low"); rm.setWaterLevel("max"); rm.setOperationMode("vacuum_and_mop"); rm.setSpeakerVolume(0); rm.start()
rm.setFanSpeed("max"); rm.setOperationMode("vacuum_then_mop"); rm.on()
rm.setSpeakerVolume(0); rm.dock(); rm.setSpeakerVolume(80)
check("RM MQTT actions publish exactly", [
    ["FanSpeedControlCapability/preset/set", "low"], ["WaterUsageControlCapability/preset/set", "max"],
    ["OperationModeControlCapability/preset/set", "vacuum_and_mop"], ["SpeakerVolumeControlCapability/value/set", "0"],
    ["BasicControlCapability/operation/set", "START"], ["FanSpeedControlCapability/preset/set", "max"],
    ["OperationModeControlCapability/preset/set", "vacuum_then_mop"], ["BasicControlCapability/operation/set", "START"],
    ["SpeakerVolumeControlCapability/value/set", "0"], ["BasicControlCapability/operation/set", "HOME"],
    ["SpeakerVolumeControlCapability/value/set", "80"]],
    rm.published.collect { [it[0] - "valetudo/TESTBOT/", it[1]] })
rm.setFanSpeedTurbo("on")
check("RM setFanSpeedTurbo('on') -> quirk PUT (fallback UUID before discovery)",
    ["http://192.0.2.10/api/v2/robot/capabilities/QuirksCapability", '{"id":"017c40b7-dc24-48e8-a6e0-19d66528fa6d","value":"on"}'], rm.puts[-1])
// Notification rules: trigger = pushAlerts *changed*, condition pushAlerts != none.
def nt = fresh(); nt.current.robotStatus = "cleaning"
nt.mqttIn("StatusStateAttribute/error_description", "Main brush jammed"); settle(nt)
nt.mqttIn("StatusStateAttribute/error_description", "Main brush jammed"); nt.runScheduled("flushAlerts")   // republished retained
nt.mqttIn("StatusStateAttribute/error_description", "No error"); nt.runScheduled("flushAlerts")
check("Notification rule sees exactly: raise once, clear once (pushAlerts events)", ["Main brush jammed", "none"],
    nt.events.findAll { it[0] == "pushAlerts" }.collect { it[1] })
check("Guard sees alertSeverity fault while jammed, then none", ["fault", "none"],
    nt.events.findAll { it[0] == "alertSeverity" }.collect { it[1] })

// ── 7. REST refresh with live attribute payloads ─────────────────────────────
println "\n=== REST refresh (live payloads)"
["walter", "quay"].each { r ->
    def d = fresh()
    d.getRoutes["http://192.0.2.10/api/v2/robot/state/attributes"] = new groovy.json.JsonSlurper().parse(new File("${fx}/${r}_attrs.json"))
    d.refresh()
    def liveStatus = new groovy.json.JsonSlurper().parse(new File("${fx}/${r}_attrs.json")).find { it.__class == "StatusStateAttribute" }.value
    check("[${r}] refresh robotStatus (live fixture value)", liveStatus, d.current.robotStatus)
    check("[${r}] refresh dock_freshwater", isNew ? "ok" : null, d.current.dock_freshwater)
    check("[${r}] refresh dockStatus", isNew ? (r == "walter" ? "idle" : "drying") : null, d.current.dockStatus)
    check("[${r}] refresh restStatus", "ok", d.current.restStatus)
}

println "\n${pass} passed, ${fail} failed  (driver ${isNew ? '3.12.0' : '3.11.0'})"
System.exit(fail ? 1 : 0)
