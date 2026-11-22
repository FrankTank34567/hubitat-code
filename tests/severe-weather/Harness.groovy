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
    b.setVariable("timeOfDayIsBetween", { Date a1, Date b1, Date c1, TimeZone tz ->
        def mins = { Date d -> def cal = Calendar.getInstance(tz); cal.setTime(d); cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE) }
        def s1 = mins(a1), e1 = mins(b1), n1 = mins(new Date(clock.t))
        s1 <= e1 ? (n1 >= s1 && n1 < e1) : (n1 >= s1 || n1 < e1) })
    b.setVariable("toDateTime", { String s -> Date.from(java.time.OffsetDateTime.parse(s).toInstant()) })
    def app = new SevereWeatherApp(b)
    def ctx = [app: app, b: b, st: st, settings: settings, clock: clock, pushes: pushes, logs: logs, gets: gets, temp: temp, rain: rain]
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
def f = newApp([:], [freezeDayLog: [true] * 14, tafAdvisory: [fzra: true, ts: false, station: "KGTF", time: T0]])
f.app.forecastHandler(resp(fcJson), null)
f.settings.sensorRain.attrs.rainRate = 0.10
f.st.threatMatrix.Freeze.lastTrig = T0 - 3600000
f.settings.sensorHum.attrs.humidity = 70.0
step(f, 33.0)
H.check("ice: ICE ALARM despite expected freeze", frState(f) == "ICE ALARM", frState(f))
H.check("ice: ICE push sent", f.pushes.any { it.msg.contains("ICE STORM") }, "${f.pushes}")

// Ice ends but it stays below freezing: ICE ALARM -> ALARM step-down is not a new push.
f.settings.sensorRain.attrs.rainRate = 0.0
step(f, 30.0, 70L * 60000)   // past the 60 min ice hold
H.check("ice step-down to dry freeze: ALARM, no Freeze push (ice-end all-clear is fine)", frState(f) == "ALARM" && freezePushes(f).size() == 1, "${frState(f)} ${f.pushes}")

// ── 7. NWS Ice Storm Warning routed as ICE, Winter Storm Warning muted ──────
def g = newApp([:], [freezeDayLog: [true] * 14, noaaAlertsMap: [Freeze: "ALARM", FreezeIce: true]])
g.app.forecastHandler(resp(fcJson), null)
step(g, 25.0)
H.check("NWS ice storm: ICE ALARM, pushed", frState(g) == "ICE ALARM" && g.pushes.any { it.msg.contains("ICE STORM") }, "${frState(g)} ${g.pushes}")
def g2 = newApp([:], [freezeDayLog: [true] * 14, noaaAlertsMap: [Freeze: "ALARM"]])
g2.app.forecastHandler(resp(fcJson), null)
step(g2, 25.0)
H.check("NWS Freeze-level ALARM (not ice) while expected: ALARM, muted", frState(g2) == "ALARM" && freezePushes(g2).isEmpty(), "${g2.pushes}")

// ── 8. Auto-mute off -> old behaviour (alerts), but still once per 12 h ─────
def h = newApp([seasonalFreezeAuto: false], [freezeDayLog: [true] * 14])
step(h, 34.0); step(h, 30.0, 16L * 60000); step(h, 36.0, 16L * 60000); step(h, 30.0, 16L * 60000)
H.check("mute off: alerts, re-alert window limits to 1", freezePushes(h).count { it.msg.contains("ALARM") } == 1, "${freezePushes(h)}")
def h2 = newApp([seasonalFreezeAuto: false, n_freezeAlarm_cooldown: 0, n_freezeAlarm_maxPerDay: 0], [freezeDayLog: [true] * 14])
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

// ── 11. Ice coverage across the app ─────────────────────────────────────────
// Freezing rain at 28F (below the old 31F bound) with TAF FZRA, freeze expected.
def i1 = newApp([:], [freezeDayLog: [true] * 14, tafAdvisory: [fzra: true, ts: false, station: "KGTF", time: T0]])
i1.app.forecastHandler(resp(fcJson), null)
i1.settings.sensorRain.attrs.rainRate = 0.05
step(i1, 28.0)
H.check("ice 28F + TAF FZRA: ICE ALARM pushed although freeze expected", frState(i1) == "ICE ALARM" && i1.pushes.any { it.msg.contains("ICE STORM") }, "${frState(i1)} ${i1.pushes}")
// Hold through a 30 min break, leave ICE after > 60 min dry, then restart inside 6 h: no 2nd push.
i1.settings.sensorRain.attrs.rainRate = 0.0
step(i1, 28.0, 30L * 60000)
H.check("ice: holds ICE ALARM through a 30 min precip break", frState(i1) == "ICE ALARM", frState(i1))
step(i1, 28.0, 45L * 60000)
H.check("ice: after > 60 min dry, drops to routine freeze ALARM", frState(i1) == "ALARM", frState(i1))
i1.settings.sensorRain.attrs.rainRate = 0.05
step(i1, 28.0, 30L * 60000)
H.check("ice: restart inside 6 h -> ICE ALARM, no 2nd ICE push", frState(i1) == "ICE ALARM" && i1.pushes.count { it.msg.contains("ICE STORM") } == 1, "${frState(i1)} ${i1.pushes}")
// Uncorroborated (no TAF, forecast says snow/fog): ICE WARNING, not ALARM.
def i2 = newApp([:], [freezeDayLog: [true] * 14])
i2.app.forecastHandler(resp(fcJson), null)
i2.settings.sensorRain.attrs.rainRate = 0.05
step(i2, 33.0)
H.check("ice uncorroborated: ICE WARNING tier", frState(i2) == "ICE WARNING", frState(i2))
H.check("ice uncorroborated: still not muted (warn push honoured)", i2.pushes.any { it.msg.contains("Possible freezing rain") }, "${i2.pushes}")
// NWS forecast text corroborates.
def iceFc = fcJson.replaceFirst('"shortForecast": "Patchy Fog"', '"shortForecast": "Freezing Rain Likely"')
def i3 = newApp([:], [freezeDayLog: [true] * 14])
i3.app.forecastHandler(resp(iceFc), null)
i3.settings.sensorRain.attrs.rainRate = 0.05
step(i3, 30.0)
H.check("ice: NWS forecast 'Freezing Rain' corroborates -> ICE ALARM", frState(i3) == "ICE ALARM", "${frState(i3)} ${i3.st.threatMatrix.Freeze.mathEx}")
// Local-only mode: no corroboration possible, original ALARM behaviour kept.
def i4 = newApp([enableInternet: false], [freezeDayLog: [true] * 14])
i4.settings.sensorRain.attrs.rainRate = 0.05
step(i4, 30.0)
H.check("ice local-only mode: ICE ALARM without corroboration", frState(i4) == "ICE ALARM", frState(i4))

// ── 12. NWS product mapping (real parser) ───────────────────────────────────
def nws(String event, String desc) {
    def j = groovy.json.JsonOutput.toJson([features: [[properties: [event: event, headline: event, description: desc]]]])
    return resp(j)
}
def n1 = newApp([:], [freezeDayLog: [true] * 14])
n1.app.noaaResponseHandler(nws("Winter Storm Warning", "Heavy snow expected. Total snow accumulations of 8 to 14 inches."), null)
H.check("NWS Winter Storm Warning (snow only) no longer maps to Freeze", n1.st.noaaAlertsMap.Freeze == "Clear" && !n1.st.noaaAlertsMap.FreezeIce, "${n1.st.noaaAlertsMap}")
H.check("NWS snow-only storm: no Freeze push", freezePushes(n1).isEmpty(), "${n1.pushes}")
def n2 = newApp([:], [freezeDayLog: [true] * 14])
n2.app.noaaResponseHandler(nws("Winter Storm Warning", "Mixed precipitation. Ice accumulations of a quarter inch from freezing rain."), null)
H.check("NWS Winter Storm Warning with freezing rain -> Freeze ALARM + ice", n2.st.noaaAlertsMap.Freeze == "ALARM" && n2.st.noaaAlertsMap.FreezeIce == true, "${n2.st.noaaAlertsMap}")
H.check("NWS ice storm text: ICE ALARM state and ICE push", frState(n2) == "ICE ALARM" && n2.pushes.any { it.msg.contains("ICE STORM") }, "${frState(n2)} ${n2.pushes}")
def n3 = newApp([:], [freezeDayLog: [true] * 14])
def two = groovy.json.JsonOutput.toJson([features: [[properties: [event: "Freeze Warning", headline: "", description: ""]], [properties: [event: "Frost Advisory", headline: "", description: ""]]]])
n3.app.noaaResponseHandler(resp(two), null)
H.check("NWS: later Frost Advisory does not downgrade Freeze Warning", n3.st.noaaAlertsMap.Freeze == "ALARM", "${n3.st.noaaAlertsMap}")
H.check("NWS Freeze Warning while freeze expected: muted", frState(n3) == "ALARM" && freezePushes(n3).isEmpty(), "${frState(n3)} ${n3.pushes}")

// ── 13. Notification Center ─────────────────────────────────────────────────
// Legacy settings carry over: Rob's hub has freezeWarnNotify=false.
def q1 = newApp([freezeWarnNotify: false], [freezeDayLog: warmLog.clone(), lastRoutineFreezeAlertTs: T0 - 5L * 86400000])
q1.app.forecastHandler(resp(mild), null)
def evW = q1.app.notifyEvent("freezeWarn")
H.check("NC: legacy freezeWarnNotify=false carries into freezeWarn push", q1.app.nBool(evW, "push") == false)
H.check("NC: new setting overrides legacy", { q1.settings.n_freezeWarn_push = true; def r = q1.app.nBool(evW, "push"); q1.settings.remove("n_freezeWarn_push"); r }())
H.check("NC: 24 notification types registered", q1.app.notifyEvents().size() == 24, "${q1.app.notifyEvents().size()}")
// Per-type off: freezeAlarm push off -> nothing sent, logged as OFF
def q2 = newApp([n_freezeAlarm_push: false], [freezeDayLog: warmLog.clone(), lastRoutineFreezeAlertTs: T0 - 5L * 86400000])
q2.app.forecastHandler(resp(mild), null)
step(q2, 34.0); step(q2, 30.0, 16L * 60000)
H.check("NC: freezeAlarm switched off -> no push, logged", freezePushes(q2).isEmpty() && q2.st.notifyLog.any { it.contains("OFF Freeze ALARM") }, "${q2.pushes} ${q2.st.notifyLog}")
// Hold-off: ALARM held 30 min, temp recovers within it -> dropped; stays -> sent
def q3 = newApp([n_freezeAlarm_holdoff: 30, freezeHysteresis: 0.0, debounceMins: 1], [freezeDayLog: warmLog.clone(), lastRoutineFreezeAlertTs: T0 - 5L * 86400000])
q3.app.forecastHandler(resp(mild), null)
step(q3, 34.0); step(q3, 31.5, 5L * 60000)
H.check("NC hold-off: not sent immediately", freezePushes(q3).isEmpty() && q3.st.pendingNotify?.freezeAlarm, "${q3.pushes} ${q3.st.pendingNotify}")
step(q3, 33.0, 10L * 60000); step(q3, 33.0, 25L * 60000)
H.check("NC hold-off: ALARM blip dropped, never sent as ALARM", !freezePushes(q3).any { it.msg.contains("ALARM") } && q3.st.notifyLog.any { it.contains("DROPPED") }, "${q3.pushes} ${q3.st.notifyLog.take(4)}")
def q4 = newApp([n_freezeAlarm_holdoff: 30], [freezeDayLog: warmLog.clone(), lastRoutineFreezeAlertTs: T0 - 5L * 86400000])
q4.app.forecastHandler(resp(mild), null)
step(q4, 34.0); step(q4, 30.0, 5L * 60000); step(q4, 29.0, 20L * 60000); step(q4, 29.0, 11L * 60000)
H.check("NC hold-off: still frozen after 30 min -> sent once", freezePushes(q4).size() == 1, "${q4.pushes}")
// Trajectory fix: falling 6F/hr from 40F -> WARNING (not ALARM) before 32F
def t1 = newApp([n_freezeWarn_push: true], [freezeDayLog: warmLog.clone(), lastRoutineFreezeAlertTs: T0 - 5L * 86400000])
t1.app.forecastHandler(resp(mild), null)
(0..6).each { i -> step(t1, 40.0 - i * 1.0, 10L * 60000) }
H.check("trajectory fixed: 34F falling 6F/hr -> WARNING (was stuck at 30%)", frState(t1) == "WARNING", "${frState(t1)} ${t1.st.threatMatrix.Freeze.mathEx}")
H.check("trajectory: predicted freeze never ALARM above 32F", t1.st.threatMatrix.Freeze.prob < 80)
// Min probability gate (local): freezeWarn at 65% blocked by minProb 80
def q5 = newApp([n_freezeWarn_minProb: 80, n_freezeWarn_push: true], [freezeDayLog: warmLog.clone(), lastRoutineFreezeAlertTs: T0 - 5L * 86400000])
q5.app.forecastHandler(resp(mild), null)
(0..6).each { i -> step(q5, 40.0 - i * 1.0, 10L * 60000) }
H.check("NC minProb: trajectory WARNING at 75% blocked by minProb 80", !q5.pushes.any { it.msg.contains("WARNING") } && q5.st.notifyLog.any { it.contains("< minimum 80") }, "${q5.pushes} ${q5.st.notifyLog.take(3)}")
// Source filter: Tornado ALARM from NWS with source=local -> skipped
def q6 = newApp([enableTornado: true, n_tornadoAlarm_source: "local", sensorWind: null], [noaaAlertsMap: [Tornado: "ALARM"]])
q6.st.threatMatrix.Tornado = [threat: 0, prob: 0, conf: 0, state: "Clear", howWhy: "", mathEx: "", lastTrig: 0, history: []]
step(q6, 60.0)
H.check("NC source filter: NWS-driven Tornado ALARM skipped when Local only", q6.st.threatMatrix.Tornado.state == "ALARM" && !q6.pushes.any { it.msg.contains("Tornado") }, "${q6.st.threatMatrix.Tornado.state} ${q6.pushes}")
def q7 = newApp([enableTornado: true], [noaaAlertsMap: [Tornado: "ALARM"]])
q7.st.threatMatrix.Tornado = [threat: 0, prob: 0, conf: 0, state: "Clear", howWhy: "", mathEx: "", lastTrig: 0, history: []]
step(q7, 60.0)
H.check("NC: NWS Tornado ALARM pushed by default, details say NWS", q7.pushes.any { it.msg.contains("Tornado ALARM") && it.msg.contains("NWS") }, "${q7.pushes}")
// Quiet hours 21:00-07:00 block a freeze ALARM at ~21:46
def q8 = newApp([n_freezeAlarm_quietStart: "2026-11-21T21:00:00.000-0700", n_freezeAlarm_quietEnd: "2026-11-22T07:00:00.000-0700"], [freezeDayLog: warmLog.clone(), lastRoutineFreezeAlertTs: T0 - 5L * 86400000])
q8.b.setVariable("toDateTime", { String s -> s.length() > 25 ? Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", s) : Date.from(java.time.OffsetDateTime.parse(s).toInstant()) })
q8.app.forecastHandler(resp(mild), null)
step(q8, 34.0); step(q8, 30.0, 16L * 60000)
H.check("NC quiet hours: freeze ALARM skipped", freezePushes(q8).isEmpty() && q8.st.notifyLog.any { it.contains("quiet hours") }, "${q8.pushes} ${q8.st.notifyLog.take(3)}")
// Daily cap: storms with cooldown 0 and cap 2
def q9 = newApp([n_freezeAlarm_cooldown: 0, n_freezeAlarm_maxPerDay: 2, freezeHysteresis: 0.0, debounceMins: 1], [freezeDayLog: warmLog.clone(), lastRoutineFreezeAlertTs: T0 - 5L * 86400000])
q9.app.forecastHandler(resp(mild), null)
step(q9, 34.0, 1L)
(1..4).each { step(q9, 31.0, 2L * 60000); step(q9, 33.5, 2L * 60000) }
H.check("NC daily cap 2: only 2 freeze ALARM pushes from 4 entries", freezePushes(q9).count { it.msg.contains("ALARM") } == 2, "${freezePushes(q9)}")
// Escalation bypass: Tornado WARNING pushed, then ALARM inside a 60 min ALARM cooldown still alerts
def r1 = newApp([enableTornado: true, n_tornadoAlarm_cooldown: 60], [noaaAlertsMap: [Tornado: "WARNING"]])
r1.st.threatMatrix.Tornado = [threat: 0, prob: 0, conf: 0, state: "Clear", howWhy: "", mathEx: "", lastTrig: 0, history: []]
r1.st.notifyLast = [tornadoAlarm: T0]   // an ALARM went out recently
step(r1, 60.0)
r1.st.noaaAlertsMap = [Tornado: "ALARM"]
step(r1, 60.0)
H.check("NC escalation: WARNING told -> ALARM skips cooldown", r1.pushes.any { it.msg.contains("Tornado ALARM") }, "${r1.pushes}")
// Clear 'only if alerted': freeze cleared after a muted episode -> no clear push
def r2 = newApp([n_freezeClear_push: true, n_freezeClear_muteExpected: false], [freezeDayLog: [true] * 14])
r2.app.forecastHandler(resp(fcJson), null)
step(r2, 30.0); step(r2, 36.0, 30L * 60000)
H.check("NC clear: muted episode -> no 'cleared' push", !r2.pushes.any { it.msg.contains("cleared") }, "${r2.pushes}")
// Test button
def r3 = newApp()
r3.app.appButtonHandler("testNotifyBtn")
H.check("NC test button sends a push", r3.pushes.any { it.msg.contains("Test from") })
// Page renders without error (all inputs resolvable)
def r4 = newApp()
def inputs = []
r4.b.setVariable("dynamicPage", { Map pm, Closure pc -> pc() })
r4.b.setVariable("section", { Object[] sa -> def sc = sa[-1]; if (sc instanceof Closure) sc() })
r4.b.setVariable("paragraph", { Object[] pa -> })
r4.b.setVariable("input", { Object[] ia -> inputs << ia.find { it instanceof CharSequence }.toString() })
r4.app.notificationPage()
H.check("NC page builds; 24 push toggles present", inputs.count { it.toString().endsWith("_push") } == 24, "${inputs.count { it.toString().endsWith('_push') }}")

["mainPage", "configPage", "dnaConfigPage", "speakerMappingPage"].each { pg ->
    def err = null
    try { r4.b.setVariable("href", { Object[] ha -> }); r4.app."${pg}"() } catch (Throwable ex) { err = ex }
    H.check("page ${pg} renders", err == null, "${err}")
}
H.check("no engine errors in any run", ![a, b, b2, c, d, t1, q2, q3, q4, q5, q6, q7, q8, q9, r1, r2, i1, i2, i3, i4, n1, n2, n3, d2, e, f, g, g2, h, h2, k, k2, m].any { it.st.lastEngineError })
println "\n${H.pass} passed, ${H.fail} failed"
System.exit(H.fail ? 1 : 0)
