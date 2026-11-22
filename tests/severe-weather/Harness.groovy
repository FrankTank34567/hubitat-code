// Offline tests for apps/Advanced Severe Weather Detector.groovy (fork 1.0.0 freeze changes).
// Mocks the Hubitat app runtime; drives doEvaluateMatrix() with a fake clock and sensors.
import groovy.json.JsonSlurper
import groovy.transform.Field

class H {
    static int pass = 0, fail = 0
    static void check(String name, boolean ok, String detail = "") {
        if (ok) { pass++; println "PASS  ${name}" } else { fail++; println "FAIL  ${name}  ${detail}" }
    }
}

class Dev {
    String id; String displayName; Map attrs = [:]
    def currentValue(String n) { attrs[n] }
    boolean hasCommand(String c) { false }
    String getTypeName() { "Fake" }
}

// Times in the captured forecast are 2026-11-21 21:00 MST onward.
@Field long T0 = Date.parse("yyyy-MM-dd HH:mm Z", "2026-11-21 21:30 -0700").time

def newApp(Map prefs = [:], Map st0 = [:]) {
    def clock = [t: T0]
    def pushes = [], tts = [], logs = [], gets = [], runIns = []
    def temp = new Dev(id: "1", displayName: "Temp", attrs: [temperature: 40.0])
    def hum = new Dev(id: "2", displayName: "Hum", attrs: [humidity: 60.0])
    def press = new Dev(id: "3", displayName: "Press", attrs: [pressure: 30.00])
    def rain = new Dev(id: "4", displayName: "Rain", attrs: [rainRate: 0.0])
    def notif = new Dev(id: "9", displayName: "Phone")
    notif.metaClass.deviceNotification = { String m -> pushes << [t: clock.t, msg: m] }
    def settings = [
        sensorTemp: temp, sensorHum: hum, sensorPress: press, sensorRain: rain,
        notifyDevices: [notif], txtEnable: false,
        enableInternet: true, enableNOAA: true, noaaTriggersHardware: true, enableAutoCal: true,
        seasonalFreezeAuto: true, enableFreezeForecast: true,
        freezeAlarmNotify: true, freezeWarnNotify: true, freezeAlarmTTS: false, freezeWarnTTS: false,
        globalAllClearNotify: true, globalAllClearTTS: false, enableDefcon: true, defconThresh: 25,
        debounceMins: 15, realertCooldownMins: 120,
        enableTornado: false, enableThunderstorm: false, enableFlood: false, enableSevereHeat: false,
        enableTropical: false, enableFireWeather: false, enableThermalSmoothing: false,
    ] + prefs
    def st = [threatMatrix: [Freeze: [threat: 0, prob: 0, conf: 0, state: "Clear", howWhy: "", mathEx: "", lastTrig: 0, history: []]],
              lastHeartbeat: T0, tempHistory: [], pressureHistory: [], spreadHistory: [], windHistory: [],
              windDirHistory: [], lightningHistory: [], probHistory: [], noaaAlertsMap: [:]] + st0
    def b = new Binding()
    // Every input the app may read as a bare name.
    ["sensorTemp","sensorHum","sensorPress","sensorWind","sensorWindDir","sensorLightning","sensorRain",
     "sensorRainDaily","sensorRainWeekly","sensorSoil","sensorLux","sensorLeak","sensorLeak2","sensorLeak3",
     "notifyDevices","audioDevices","soundDevices","sirenDevices","txtEnable","debugEnable","audioVolume",
     "enablePolling","pollInterval","enableNOAA","enableDefcon","enableRadar","irrigationSwitch"].each { b.setVariable(it, settings[it]) }
    b.setVariable("settings", settings)
    b.setVariable("state", st)
    b.setVariable("app", [id: 1, label: "ASWD"])
    b.setVariable("location", [timeZone: TimeZone.getTimeZone("America/Denver"), latitude: 47.5, longitude: -111.3,
                               temperatureScale: "F", mode: "Home"])
    b.setVariable("log", [info: { logs << it }, warn: { logs << it }, debug: { }, error: { logs << "E " + it }])
    b.setVariable("now", { clock.t })
    b.setVariable("runIn", { Object[] a -> runIns << a })
    b.setVariable("asynchttpGet", { String h, Map p -> gets << [h, p.uri] })
    b.setVariable("getChildDevice", { String id -> null })
    b.setVariable("toDateTime", { String s -> Date.from(java.time.OffsetDateTime.parse(s).toInstant()) })
    def app = new SevereWeatherApp(b)
    def ctx = [app: app, st: st, settings: settings, clock: clock, pushes: pushes, logs: logs, gets: gets, temp: temp, rain: rain]
    // Seed a little temperature history so trends exist.
    return ctx
}

// Advance the clock, set the temperature, run one evaluation.
def step(ctx, double tempF, long advanceMs = 60000L) {
    ctx.clock.t += advanceMs
    ctx.temp.attrs.temperature = tempF
    ctx.st.lastHeartbeat = ctx.clock.t
    ctx.app.updateHistory("tempHistory", tempF, 3600000)
    ctx.app.evaluateMatrix()
    if (ctx.st.lastEngineError) println "   engine error: ${ctx.st.lastEngineError}"
}
def frState(ctx) { ctx.st.threatMatrix.Freeze.state }
def freezePushes(ctx) { ctx.pushes.findAll { it.msg.contains("Freeze") || it.msg.contains("ICE") } }

def fcJson = new File("fixtures/nws_forecast_greatfalls.json").text
def resp(String json) { def e = new Expando(); e.hasError = { false }; e.getStatus = { 200 }; e.getJson = { new JsonSlurper().parseText(json) }; e.getData = { json }; e }
def errResp(int code) { def e = new Expando(); e.hasError = { true }; e.getStatus = { code }; e }
def warmLog = [false] * 14

// ── 1. Forecast parsing ─────────────────────────────────────────────────────
def a = newApp([:], [freezeDayLog: warmLog.clone()])
a.app.forecastHandler(resp(fcJson), null)
def fc = a.st.freezeForecast
H.check("forecast: 14 periods parsed", fc?.periods?.size() == 14, "${fc?.periods?.size()}")
H.check("forecast: Tonight low 3F, night period", fc.periods[0].name == "Tonight" && fc.periods[0].temp == 3.0 && !fc.periods[0].day, "${fc.periods[0]}")
H.check("forecast: start/end are epoch ms", fc.periods[0].start instanceof Long && fc.periods[0].end > fc.periods[0].start)
H.check("forecast: degC quantity converted", a.app.forecastTempF([unitCode: "wmoUnit:degC", value: 0], null) == 32.0)
a.app.forecastHandler(errResp(503), null)
H.check("forecast: 503 keeps last good forecast", a.st.freezeForecast?.periods?.size() == 14)
a.app.forecastPointsHandler(resp('{"properties":{"forecast":"https://evil.example/x"}}'), null)
H.check("forecast: off-site forecast URL rejected", a.st.forecastUrl == null)
a.app.forecastPointsHandler(resp('{"properties":{"forecast":"https://api.weather.gov/gridpoints/TFX/98,144/forecast"}}'), null)
H.check("forecast: api.weather.gov gridpoint URL accepted", a.st.forecastUrl?.endsWith("/forecast"))

// ── 2. Replay of the 11/20 flap, no freeze expected (forecast mild, warm log) ─
def mild = fcJson.replaceAll(/"temperature": -?\d+/, '"temperature": 45')
def b = newApp([:], [freezeDayLog: warmLog.clone(), lastRoutineFreezeAlertTs: T0 - 5L * 86400000])
b.app.forecastHandler(resp(mild), null)
step(b, 36.0); step(b, 35.0)
[31.9, 32.6, 33.1, 32.2, 31.8, 32.9, 33.4, 32.4, 31.9, 33.0].each { t -> step(b, t, 16L * 60000) }
H.check("flap: not expected (mild forecast, warm log)", b.st.freezeExpected == false, "${b.st.freezeExpectedReason}")
H.check("flap: first-of-season freeze ALARM pushed exactly once", freezePushes(b).count { it.msg.contains("ALARM") } == 1, "${freezePushes(b)}")
H.check("flap: no WARNING push after the ALARM", !freezePushes(b).any { it.msg.contains("WARNING") }, "${freezePushes(b)}")
H.check("flap: ALARM held through 32-33.4F", frState(b) == "ALARM", frState(b))
H.check("flap: DEFCON not engaged by a dry freeze", b.st.watchdogActive != true)
step(b, 34.5, 16L * 60000); step(b, 36.0, 16L * 60000)
H.check("hysteresis: clears once above 34F", frState(b) == "Clear", frState(b))
H.check("global: no 'Weather All-Clear' push for a dry freeze", !b.pushes.any { it.msg.contains("All-Clear") }, "${b.pushes}")
H.check("global: forensic event not created by freeze", b.st.lastEventReport == null)
// Next evening, 20 h later. The run crossed midnight, so the log now holds a
// freezing day: no longer first-of-season, forecast is mild -> a surprise freeze alerts.
step(b, 31.0, 20L * 3600000)
H.check("surprise 2nd night (mild forecast, 1/7 local) alerts again", freezePushes(b).count { it.msg.contains("ALARM") } == 2, "${freezePushes(b)}")
// 48 h guard: first freeze alerted 20 h ago but the log has not rolled yet.
def b2 = newApp([:], [freezeDayLog: warmLog.clone(), lastRoutineFreezeAlertTs: T0 - 20L * 3600000])
b2.app.forecastHandler(resp(fcJson), null)
step(b2, 34.0); step(b2, 30.0, 16L * 60000)
H.check("first-of-season: 48 h guard mutes the forecast freeze next night", freezePushes(b2).isEmpty() && b2.st.freezeExpected == true, "${freezePushes(b2)} ${b2.st.freezeExpectedReason}")

// ── 3. Forecast says freeze tonight -> muted (log already has a freeze) ─────
def c = newApp([:], [freezeDayLog: [false]*11 + [true, true, true]])
c.app.forecastHandler(resp(fcJson), null)
step(c, 34.0); step(c, 30.0, 16L * 60000)
H.check("forecast-expected: state is ALARM", frState(c) == "ALARM", frState(c))
H.check("forecast-expected: expected=true via forecast", c.st.freezeExpected == true && c.st.freezeExpectedReason.contains("forecast"), "${c.st.freezeExpectedReason}")
H.check("forecast-expected: no freeze push", freezePushes(c).isEmpty(), "${freezePushes(c)}")

// ── 4. Local only: 4 of last 7 froze, internet off -> muted ─────────────────
def d = newApp([enableInternet: false], [freezeDayLog: [false]*7 + [false, true, false, true, true, false, true]])
step(d, 34.0); step(d, 30.0, 16L * 60000)
H.check("local-expected: 4/7 -> expected, no push", d.st.freezeExpected == true && freezePushes(d).isEmpty(), "${d.st.freezeExpectedReason} ${freezePushes(d)}")
def d2 = newApp([enableInternet: false], [freezeDayLog: [false]*11 + [true, true, true]])
step(d2, 34.0); step(d2, 30.0, 16L * 60000)
H.check("local: 3/7 with no forecast -> not expected, pushes", d2.st.freezeExpected == false && freezePushes(d2).size() == 1, "${freezePushes(d2)}")

// ── 5. Stale forecast ignored ───────────────────────────────────────────────
def e = newApp([enableInternet: true], [freezeDayLog: [false]*11 + [true, true, true]])
e.app.forecastHandler(resp(fcJson), null)
e.st.freezeForecast.time = T0 - 13L * 3600000
step(e, 30.0)
H.check("stale forecast (13 h) ignored", e.st.freezeExpected == false, "${e.st.freezeExpectedReason}")

// ── 6. Ice storm while freeze expected -> ICE ALARM pushed ──────────────────
def f = newApp([:], [freezeDayLog: [true] * 14])
f.app.forecastHandler(resp(fcJson), null)
f.settings.sensorRain.attrs.rainRate = 0.10
f.st.threatMatrix.Freeze.lastTrig = T0 - 3600000
f.settings.sensorHum.attrs.humidity = 70.0
step(f, 33.0)
H.check("ice: ICE ALARM despite expected freeze", frState(f) == "ICE ALARM", frState(f))
H.check("ice: ICE push sent", f.pushes.any { it.msg.contains("ICE STORM") }, "${f.pushes}")

// Ice ends but it stays below freezing: ICE ALARM -> ALARM step-down is not a new push.
f.settings.sensorRain.attrs.rainRate = 0.0
step(f, 30.0, 20L * 60000)
H.check("ice step-down to dry freeze: ALARM, no Freeze push (ice-end all-clear is fine)", frState(f) == "ALARM" && freezePushes(f).size() == 1, "${frState(f)} ${f.pushes}")

// ── 7. NWS Ice Storm Warning routed as ICE, Winter Storm Warning muted ──────
def g = newApp([:], [freezeDayLog: [true] * 14, noaaAlertsMap: [Freeze: "ALARM", FreezeIce: true]])
g.app.forecastHandler(resp(fcJson), null)
step(g, 25.0)
H.check("NWS ice storm: ICE ALARM, pushed", frState(g) == "ICE ALARM" && g.pushes.any { it.msg.contains("ICE STORM") }, "${frState(g)} ${g.pushes}")
def g2 = newApp([:], [freezeDayLog: [true] * 14, noaaAlertsMap: [Freeze: "ALARM"]])
g2.app.forecastHandler(resp(fcJson), null)
step(g2, 25.0)
H.check("NWS winter storm (not ice) while expected: ALARM, muted", frState(g2) == "ALARM" && freezePushes(g2).isEmpty(), "${g2.pushes}")

// ── 8. Auto-mute off -> old behaviour (alerts), but still once per 12 h ─────
def h = newApp([seasonalFreezeAuto: false], [freezeDayLog: [true] * 14])
step(h, 34.0); step(h, 30.0, 16L * 60000); step(h, 36.0, 16L * 60000); step(h, 30.0, 16L * 60000)
H.check("mute off: alerts, re-alert window limits to 1", freezePushes(h).count { it.msg.contains("ALARM") } == 1, "${freezePushes(h)}")
def h2 = newApp([seasonalFreezeAuto: false, freezeRealertHours: 0], [freezeDayLog: [true] * 14])
step(h2, 34.0); step(h2, 30.0, 16L * 60000); step(h2, 36.0, 16L * 60000); step(h2, 30.0, 16L * 60000)
H.check("window 0: every ALARM entry alerts (2)", freezePushes(h2).count { it.msg.contains("ALARM") } == 2, "${freezePushes(h2)}")

// ── 9. First-of-season switch off -> forecast freeze muted even with warm log
def k = newApp([freezeAlertFirstOfSeason: false], [freezeDayLog: warmLog.clone()])
k.app.forecastHandler(resp(fcJson), null)
step(k, 34.0); step(k, 30.0, 16L * 60000)
H.check("first-of-season off: forecast freeze muted", freezePushes(k).isEmpty(), "${freezePushes(k)}")
def k2 = newApp([:], [freezeDayLog: warmLog.clone()])
k2.app.forecastHandler(resp(fcJson), null)
step(k2, 34.0); step(k2, 30.0, 16L * 60000)
H.check("first-of-season on: forecast freeze still alerts once", freezePushes(k2).size() == 1, "${freezePushes(k2)}")

// ── 10. Midnight rollover logs the freeze day and flips to local-expected ───
def m = newApp([enableInternet: false], [freezeDayLog: [false]*10 + [true, true, true], freezeLogDate: "2026-11-20", todayMinTemp: 20.0])
step(m, 25.0)   // app reads the real date (new Date()), so seed yesterday
H.check("rollover: log gained a freezing day", m.st.freezeDayLog.size() == 14 && m.st.freezeDayLog[-1] == true, "${m.st.freezeDayLog}")
H.check("rollover: 4/7 now -> expected", m.st.freezeExpected == true, "${m.st.freezeExpectedReason}")

H.check("no engine errors in any run", ![a, b, b2, c, d, d2, e, f, g, g2, h, h2, k, k2, m].any { it.st.lastEngineError })
println "\n${H.pass} passed, ${H.fail} failed"
System.exit(H.fail ? 1 : 0)
