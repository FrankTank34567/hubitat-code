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
    String id; String displayName; Map attrs = [:]; Date lastActivity = null
    Date getLastActivity() { lastActivity }
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
    def ctx = [app: app, b: b, st: st, settings: settings, clock: clock, pushes: pushes, logs: logs, gets: gets, temp: temp, rain: rain, hum: hum, press: press]
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
def nws(String event, String desc, Map extra = [:]) {
    def j = groovy.json.JsonOutput.toJson([features: [[properties: [id: "urn:" + event + (extra.idSuffix ?: ""), event: event, headline: event, description: desc, status: extra.status ?: "Actual",
        messageType: extra.messageType ?: "Alert", references: extra.references ?: [], ends: extra.ends, expires: extra.expires]]]])
    return resp(j)
}
// A stored, already-parsed NWS alert (as noaaResponseHandler saves it).
def nwsA(String event, Map extra = [:]) {
    def info = newApp().app.nwsClassify(event, extra.text ?: "")
    return [id: "urn:" + event, event: event, headline: event, refs: [], ends: null, grp: info.grp, tier: info.tier, haz: info.haz, ice: info.ice] + extra
}

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
def g = newApp([:], [freezeDayLog: [true] * 14])
g.app.forecastHandler(resp(fcJson), null)
g.app.noaaResponseHandler(nws("Ice Storm Warning", "Ice accumulations of up to half an inch."), null)
step(g, 25.0)
H.check("NWS ice storm: ICE ALARM, one NWS push (no duplicate local ICE push)", frState(g) == "ICE ALARM" && g.pushes.size() == 1 && g.pushes[0].msg.contains("NWS: Ice Storm Warning"), "${frState(g)} ${g.pushes}")
def g2 = newApp([:], [freezeDayLog: [true] * 14, nwsAlerts: [nwsA("Freeze Warning")], lastNwsGoodPoll: T0])
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
def nwsMarker = 0
def n1 = newApp([:], [freezeDayLog: [true] * 14])
n1.app.noaaResponseHandler(nws("Winter Storm Warning", "Heavy snow expected. Total snow accumulations of 8 to 14 inches."), null)
H.check("NWS Winter Storm Warning (snow only) no longer maps to Freeze", n1.st.noaaAlertsMap.Freeze == "Clear" && !n1.st.noaaAlertsMap.FreezeIce, "${n1.st.noaaAlertsMap}")
H.check("NWS snow-only storm: no Freeze push", freezePushes(n1).isEmpty(), "${n1.pushes}")
def n2 = newApp([:], [freezeDayLog: [true] * 14])
n2.app.noaaResponseHandler(nws("Winter Storm Warning", "Mixed precipitation. Ice accumulations of a quarter inch from freezing rain."), null)
H.check("NWS Winter Storm Warning with freezing rain -> Freeze ALARM + ice", n2.st.noaaAlertsMap.Freeze == "ALARM" && n2.st.noaaAlertsMap.FreezeIce == true, "${n2.st.noaaAlertsMap}")
H.check("NWS ice storm text: ICE ALARM state, pushed once from the NWS Ice Storm row", frState(n2) == "ICE ALARM" && n2.pushes.size() == 1 && n2.pushes[0].msg.contains("NWS: Winter Storm Warning"), "${frState(n2)} ${n2.pushes}")
def n3 = newApp([:], [freezeDayLog: [true] * 14])
def two = groovy.json.JsonOutput.toJson([features: [[properties: [event: "Freeze Warning", headline: "", description: ""]], [properties: [event: "Frost Advisory", headline: "", description: ""]]]])
n3.app.noaaResponseHandler(resp(two), null)
H.check("NWS: later Frost Advisory does not downgrade Freeze Warning", n3.st.noaaAlertsMap.Freeze == "ALARM", "${n3.st.noaaAlertsMap}")
H.check("NWS Freeze Warning: ALARM, one NWS push (growing-season product, not muted by default)", frState(n3) == "ALARM" && n3.pushes.size() == 1 && n3.pushes[0].msg.contains("NWS: Freeze Warning"), "${frState(n3)} ${n3.pushes}")
def n4 = newApp([n_nws_freeze_warn_muteExpected: true], [freezeDayLog: [true] * 14])
n4.app.noaaResponseHandler(resp(two), null)
H.check("NWS Freeze Warning muted when its mute-while-expected option is on", n4.pushes.isEmpty() && n4.st.notifyLog.any { it.contains("freeze expected") }, "${n4.pushes} ${n4.st.notifyLog.take(3)}")

// ── 13. Notification Center ─────────────────────────────────────────────────
// Legacy settings carry over: Rob's hub has freezeWarnNotify=false.
def q1 = newApp([freezeWarnNotify: false], [freezeDayLog: warmLog.clone(), lastRoutineFreezeAlertTs: T0 - 5L * 86400000])
q1.app.forecastHandler(resp(mild), null)
def evW = q1.app.notifyEvent("freezeWarn")
H.check("NC: legacy freezeWarnNotify=false carries into freezeWarn push", q1.app.nBool(evW, "push") == false)
H.check("NC: new setting overrides legacy", { q1.settings.n_freezeWarn_push = true; def r = q1.app.nBool(evW, "push"); q1.settings.remove("n_freezeWarn_push"); r }())
H.check("NC: 48 notification types registered (20 station/system + 28 NWS)", q1.app.notifyEvents().size() == 48, "${q1.app.notifyEvents().size()}")
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
// Source filter: an NWS-driven Thunderstorm ALARM, hazard row default = local sensors only -> skipped
def q6 = newApp([enableThunderstorm: true], [nwsAlerts: [nwsA("Severe Thunderstorm Warning")], lastNwsGoodPoll: T0])
q6.st.threatMatrix.Thunderstorm = [threat: 0, prob: 0, conf: 0, state: "Clear", howWhy: "", mathEx: "", lastTrig: 0, history: []]
step(q6, 60.0)
H.check("NC source filter: NWS-driven Thunderstorm ALARM not pushed by the local row (default)", q6.st.threatMatrix.Thunderstorm.state == "ALARM" && !q6.pushes.any { it.msg.contains("Thunderstorm") }, "${q6.st.threatMatrix.Thunderstorm.state} ${q6.pushes}")
def q7 = newApp([enableThunderstorm: true, n_thunderstormAlarm_source: "any"], [nwsAlerts: [nwsA("Severe Thunderstorm Warning")], lastNwsGoodPoll: T0])
q7.st.threatMatrix.Thunderstorm = [threat: 0, prob: 0, conf: 0, state: "Clear", howWhy: "", mathEx: "", lastTrig: 0, history: []]
step(q7, 60.0)
H.check("NC: source 'any' -> NWS-driven Thunderstorm ALARM pushed, details say NWS", q7.pushes.any { it.msg.contains("Thunderstorm ALARM") && it.msg.contains("NWS") }, "${q7.pushes}")
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
// Escalation bypass: Thunderstorm WARNING pushed, then ALARM inside a 60 min ALARM cooldown still alerts
def r1 = newApp([enableThunderstorm: true, n_thunderstormAlarm_cooldown: 60, n_thunderstormAlarm_source: "any", n_thunderstormWarn_source: "any"], [nwsAlerts: [nwsA("Severe Thunderstorm Watch")], lastNwsGoodPoll: T0])
r1.st.threatMatrix.Thunderstorm = [threat: 0, prob: 0, conf: 0, state: "Clear", howWhy: "", mathEx: "", lastTrig: 0, history: []]
r1.st.notifyLast = [thunderstormAlarm: T0]   // an ALARM went out recently
step(r1, 60.0)
r1.st.nwsAlerts = [nwsA("Severe Thunderstorm Warning")]; r1.st.lastNwsGoodPoll = r1.clock.t
step(r1, 60.0)
H.check("NC escalation: WARNING told -> ALARM skips cooldown", r1.pushes.any { it.msg.contains("Thunderstorm ALARM") }, "${r1.pushes}")
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
H.check("NC page builds; 48 push toggles present", inputs.count { it.toString().endsWith("_push") } == 48, "${inputs.count { it.toString().endsWith('_push') }}")

["mainPage", "configPage", "dnaConfigPage", "speakerMappingPage"].each { pg ->
    def err = null
    try { r4.b.setVariable("href", { Object[] ha -> }); r4.app."${pg}"() } catch (Throwable ex) { err = ex }
    H.check("page ${pg} renders", err == null, "${err}")
}
// ── 14. 2.0 foundation ──────────────────────────────────────────────────────
// Pressure math against Rob's console (11/21): station 26.62 inHg, KGFA 30.12.
def x = newApp()
def alt = x.app.altimeterFromStation(26.62d, 1033.0d)
H.check("altimeter: 26.62 inHg station at 1033 m -> 30.12 inHg", Math.abs(alt - 30.12) < 0.01, "${alt}")
def hImp = x.app.impliedElevationM(30.12d, 26.62d)
H.check("altimeter: implied elevation ~1033 m (3390 ft)", Math.abs(hImp - 1033.0) < 3.0, "${hImp}")
H.check("altimeter: station<->altimeter round trip", Math.abs(x.app.stationFromAltimeter(alt, 1033.0d) - 26.62) < 0.0005)
// At 0 m the NWS formula still subtracts its 0.3 hPa (0.009 inHg) constant.
H.check("altimeter: at 0 m equals station pressure less 0.3 hPa", Math.abs(x.app.altimeterFromStation(29.92d, 0.0d) - (29.92 - 0.3 / 33.8639)) < 0.0005)

def p1 = newApp([stationElevationFt: 3390.0])
p1.press.attrs = [pressure: 27.58, pressureAbs: 26.62]
step(p1, 40.0)
H.check("pressure: station mode with entered elevation", p1.st.pressureMode == "station" && Math.abs((p1.st.currentAltimeter as Double) - 30.12) < 0.02, "${p1.st.pressureMode} ${p1.st.currentAltimeter}")
p1.app.recordPressure()
H.check("pressure: tendency series is raw station pressure", p1.st.pressSeriesKind == "station" && Math.abs((p1.st.pressureHistory[-1].value as Double) - 26.62) < 0.001, "${p1.st.pressureHistory}")

// Auto-cal learns elevation; no offset is applied and the history does not step.
def p2 = newApp()
p2.press.attrs = [pressure: 27.58, pressureAbs: 26.62]
p2.st.calStationId = "KGFA"
p2.app.recordPressure()
def obs = { double inHg -> resp(groovy.json.JsonOutput.toJson([properties: [barometricPressure: [value: inHg * 3386.39]]])) }
[30.12, 30.13, 30.11].each { v -> p2.clock.t += 3L * 3600000; p2.app.calObsHandler(obs(v), null) }
H.check("auto-cal: elevation learned from 3 pulls (~3390 ft), no offset", Math.abs((p2.st.elevLearnedM as Double) - 1033.0) < 5 && p2.st.autoPressOffset == null, "${p2.st.elevLearnedM} ${p2.st.autoPressOffset}")
p2.clock.t += 60000; p2.app.recordPressure()
H.check("auto-cal: pressure history still raw (no injected step)", p2.st.pressureHistory.every { Math.abs((it.value as Double) - 26.62) < 0.001 }, "${p2.st.pressureHistory}")
step(p2, 40.0)
H.check("auto-cal: sea-level pressure computed from learned elevation", p2.st.pressureMode == "station" && Math.abs((p2.st.currentAltimeter as Double) - 30.12) < 0.02, "${p2.st.currentAltimeter}")
// Relative-only sensor keeps the offset method, but history stays offset-free.
def p3 = newApp()
p3.press.attrs = [pressure: 26.51]
p3.st.calStationId = "KGFA"
p3.app.recordPressure()
p3.app.calObsHandler(obs(29.92), null)
p3.clock.t += 60000; p3.app.recordPressure()
H.check("relative sensor: offset 3.41 learned, history offset-free", p3.st.autoPressOffset == 3.41 && p3.st.pressureHistory.every { Math.abs((it.value as Double) - 26.51) < 0.001 }, "${p3.st.autoPressOffset} ${p3.st.pressureHistory}")
step(p3, 40.0)
H.check("relative sensor: sea-level = reading + offset", p3.st.pressureMode == "offset" && Math.abs((p3.st.currentAltimeter as Double) - 29.92) < 0.001, "${p3.st.currentAltimeter}")
H.check("relative sensor uncalibrated: flagged, not used as sea level", { def p4 = newApp(); p4.press.attrs = [pressure: 26.51]; step(p4, 40.0); p4.st.pressureMode == "uncalibrated" && p4.st.mslpMiscalibrated == true }())

// Missing temperature used to read as 0F (hard freeze ALARM). Now it pauses.
def s1 = newApp([:], [freezeDayLog: warmLog.clone()])
s1.temp.attrs = [:]
s1.clock.t += 60000; s1.app.evaluateMatrix()
H.check("missing temperature: no freeze ALARM, hazard paused", frState(s1) == "Clear" && s1.st.threatMatrix.Freeze.howWhy.contains("Paused"), "${frState(s1)} ${s1.st.threatMatrix.Freeze.howWhy}")
H.check("missing temperature: no pushes", s1.pushes.isEmpty(), "${s1.pushes}")
// Stale temperature during a freeze ALARM: state is held, then the user is told once.
def s2 = newApp([:], [freezeDayLog: warmLog.clone(), lastRoutineFreezeAlertTs: T0 - 5L * 86400000])
s2.app.forecastHandler(resp(mild), null)
step(s2, 34.0); step(s2, 30.0, 16L * 60000)
def alarmPushes = s2.pushes.size()
s2.temp.lastActivity = new Date(s2.clock.t)
s2.clock.t += 45L * 60000; s2.app.evaluateMatrix()
H.check("stale sensor: freeze ALARM held, not cleared", frState(s2) == "ALARM" && s2.st.sensorHealth.temp.ok == false, "${frState(s2)} ${s2.st.sensorHealth}")
s2.clock.t += 11L * 60000; s2.app.evaluateMatrix()
s2.clock.t += 5L * 60000; s2.app.evaluateMatrix()
H.check("stale sensor: one 'sensor problem' push", s2.pushes.size() == alarmPushes + 1 && s2.pushes[-1].msg.contains("Temperature: stale"), "${s2.pushes}")
s2.temp.lastActivity = new Date(s2.clock.t)
s2.clock.t += 60000; s2.app.evaluateMatrix()
H.check("stale sensor: recovery logged, back push off by default", s2.st.sensorDown.isEmpty() && s2.pushes.size() == alarmPushes + 1 && s2.st.notifyLog.any { it.contains("OFF Sensor back") }, "${s2.st.sensorDown} ${s2.st.notifyLog.take(2)}")
// Humidity out of range (0%) -> ice check still works off temperature alone.
def s3 = newApp([enableInternet: false], [freezeDayLog: [true] * 14])
s3.hum.attrs = [humidity: 0]
s3.rain.attrs.rainRate = 0.05
step(s3, 30.0)
H.check("humidity invalid: wet bulb falls back to air temp, ICE ALARM still detected", frState(s3) == "ICE ALARM" && s3.st.sensorHealth.hum.ok == false, "${frState(s3)} ${s3.st.sensorHealth.hum}")

// NWS: expiry, staleness, dedupe, updates, max severity, product names.
def w1 = newApp()
w1.app.noaaResponseHandler(nws("Tornado Warning", "A tornado was observed.", [ends: "2026-11-21T22:15:00-07:00"]), null)
H.check("NWS Tornado Warning: pushed with end time, TTS row on", w1.pushes.size() == 1 && w1.pushes[0].msg.contains("NWS: Tornado Warning (until Sat 10:15 PM)"), "${w1.pushes}")
w1.clock.t += 5L * 60000
w1.app.noaaResponseHandler(nws("Tornado Warning", "A tornado was observed.", [ends: "2026-11-21T22:15:00-07:00"]), null)
H.check("NWS: same product polled again -> not re-sent", w1.pushes.size() == 1, "${w1.pushes}")
w1.app.noaaResponseHandler(nws("Tornado Warning", "Extended.", [idSuffix: "-2", messageType: "Update", references: [[identifier: "urn:Tornado Warning"]], ends: "2026-11-21T22:45:00-07:00"]), null)
H.check("NWS: update/extension of a product already sent -> logged, not pushed", w1.pushes.size() == 1 && w1.st.notifyLog.any { it.contains("NWS update") }, "${w1.pushes}")
w1.clock.t = Date.parse("yyyy-MM-dd HH:mm Z", "2026-11-21 22:50 -0700").time
H.check("NWS: expired product drops out without a new poll", w1.app.nwsActiveAlerts().isEmpty())
def w2 = newApp([:], [nwsAlerts: [nwsA("Severe Thunderstorm Warning")], lastNwsGoodPoll: T0])
H.check("NWS: active while polls are fresh", w2.app.nwsHazardStatus("Thunderstorm") == "ALARM")
w2.clock.t += 31L * 60000
H.check("NWS: not trusted after 30 min without a good poll", w2.app.nwsHazardStatus("Thunderstorm") == "Clear")
def w3 = newApp()
def mix = groovy.json.JsonOutput.toJson([features: [[properties: [id: "a", event: "Severe Thunderstorm Warning", status: "Actual"]], [properties: [id: "b", event: "Severe Thunderstorm Watch", status: "Actual"]],
    [properties: [id: "c", event: "Flood Advisory", status: "Actual"]], [properties: [id: "d", event: "Flash Flood Warning", status: "Test"]]]])
w3.app.noaaResponseHandler(resp(mix), null)
H.check("NWS: Watch after Warning does not downgrade (any hazard)", w3.app.nwsHazardStatus("Thunderstorm") == "ALARM" && w3.app.nwsHazardStatus("Flood") == "WARNING", "${w3.st.nwsAlerts}")
H.check("NWS: Test messages ignored", !w3.st.nwsAlerts.any { it.id == "d" })
def names = ["Extreme Cold Warning": ["cold", "ALARM"], "Cold Weather Advisory": ["cold", "WARNING"], "Excessive Heat Warning": ["heat", "ALARM"], "Extreme Heat Warning": ["heat", "ALARM"],
             "High Wind Warning": ["wind", "ALARM"], "Blizzard Warning": ["winter", "ALARM"], "Snow Squall Warning": ["winter", "ALARM"], "Storm Surge Warning": ["tropical", "ALARM"],
             "Hurricane Watch": ["tropical", "WARNING"], "Some New Product Warning": ["other", "ALARM"]]
H.check("NWS product table: current names map to the right group and tier", names.every { nk, nv -> def c1 = w3.app.nwsClassify(nk, ""); c1.grp == nv[0] && c1.tier == nv[1] }, "${names.collect { nk, nv -> nk + '=' + w3.app.nwsClassify(nk, '') }}")
def w4 = newApp()
w4.app.noaaResponseHandler(nws("Wind Advisory", "Gusts to 50 mph."), null)
w4.app.noaaResponseHandler(nws("Extreme Cold Warning", "Wind chills as low as 45 below zero."), null)
H.check("NWS defaults: Wind Advisory silent, Extreme Cold Warning pushed", w4.pushes.size() == 1 && w4.pushes[0].msg.contains("Extreme Cold Warning"), "${w4.pushes}")
H.check("NWS products never set the local 'event' flag (no stray all-clear)", w4.st.globalEventAlerted != true)
def w5 = newApp([n_nws_wind_watch_push: true, n_nws_wind_watch_quietStart: "2026-11-21T21:00:00.000-0700", n_nws_wind_watch_quietEnd: "2026-11-22T07:00:00.000-0700"])
w5.b.setVariable("toDateTime", { String s5 -> s5.length() > 25 ? Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", s5) : Date.from(java.time.OffsetDateTime.parse(s5).toInstant()) })
w5.app.noaaResponseHandler(nws("Wind Advisory", "Gusts to 50 mph."), null)
H.check("NWS rows use the normal tuning (quiet hours)", w5.pushes.isEmpty() && w5.st.notifyLog.any { it.contains("quiet hours") }, "${w5.pushes}")
// Local detection says when NWS agrees.
def w6 = newApp([:], [freezeDayLog: warmLog.clone(), lastRoutineFreezeAlertTs: T0 - 5L * 86400000, nwsAlerts: [nwsA("Freeze Warning")], lastNwsGoodPoll: T0])
w6.app.forecastHandler(resp(mild), null)
step(w6, 34.0); step(w6, 30.0, 16L * 60000); w6.st.lastNwsGoodPoll = w6.clock.t
H.check("corroboration: local freeze push says 'NWS agrees: Freeze Warning'", { def sent = w6.pushes.find { it.msg.contains("Freeze ALARM") }; sent == null || sent.msg.contains("NWS agrees") }(), "${w6.pushes}")

// Lightning: count-based detector, baseline + no double counting.
def l1 = newApp()
def ldev = new Dev(id: "7", displayName: "WH57", attrs: [lightningCount: 3, lightningDistance: 8.7])
l1.settings.sensorLightning = ldev; l1.b.setVariable("sensorLightning", ldev)
def lev = { String n, v -> [name: n, value: v.toString(), deviceId: 7] }
l1.app.lightningHandler(lev("lightningCount", 3))
H.check("lightning: first count after start is a baseline (0 strikes)", (l1.st.lightningHistory ?: []).isEmpty(), "${l1.st.lightningHistory}")
ldev.attrs.lightningCount = 5
l1.app.lightningHandler(lev("lightningCount", 5))
l1.app.lightningHandler(lev("lightningDistance", 6.2))
H.check("lightning: +2 count = 2 strikes; distance update adds none", l1.st.lightningHistory.size() == 2, "${l1.st.lightningHistory}")

// Location-agnostic: a move resets site learning; outside coverage, NWS stands down.
def m1 = newApp([:], [siteLat: 47.5, siteLon: -111.3, calStationId: "KGFA", elevSamples: [1033.0, 1031.0, 1034.0], elevLearnedM: 1033.0, freezeDayLog: [true] * 14])
m1.b.setVariable("location", [timeZone: TimeZone.getTimeZone("America/Chicago"), latitude: 29.76, longitude: -95.37, temperatureScale: "F", mode: "Home"])
m1.app.checkSiteMoved()
H.check("move 2000+ km: station, elevation and freeze log re-learned", m1.st.calStationId == null && m1.st.elevLearnedM == null && m1.st.freezeDayLog == null && m1.st.siteLat == 29.76, "${m1.st.subMap(['calStationId', 'elevLearnedM', 'siteLat'])}")
def m2 = newApp([:], [siteLat: 47.5, siteLon: -111.3, calStationId: "KGFA"])
m2.b.setVariable("location", [timeZone: TimeZone.getTimeZone("America/Denver"), latitude: 47.52, longitude: -111.31, temperatureScale: "F", mode: "Home"])
m2.app.checkSiteMoved()
H.check("move < 10 km (GPS jitter, same town): nothing reset", m2.st.calStationId == "KGFA")
def m3 = newApp()
m3.app.forecastPointsHandler(errResp(404), null)
def before = m3.gets.size()
m3.app.pollNOAA(); m3.app.pollForecast()
H.check("outside NWS coverage (404): NWS feeds stand down", m3.st.nwsCoverage == false && m3.gets.size() == before, "${m3.gets}")
// Metric hub: -1.0 C is 30.2 F -> freeze.
def mt = newApp([:], [freezeDayLog: warmLog.clone(), lastRoutineFreezeAlertTs: T0 - 5L * 86400000])
mt.b.setVariable("location", [timeZone: TimeZone.getTimeZone("Europe/Berlin"), latitude: 47.5, longitude: -111.3, temperatureScale: "C", mode: "Home"])
mt.temp.attrs.temperature = -1.0
mt.clock.t += 60000; mt.app.evaluateMatrix()
H.check("°C hub: -1.0 °C read as 30.2 °F -> freeze ALARM", frState(mt) == "ALARM", "${frState(mt)} ${mt.st.threatMatrix.Freeze.howWhy}")
// Upgrade from 1.x.
def u1 = newApp([:], [threatMatrix: [Tornado: [state: "ALARM"], Tropical: [state: "Clear"], Freeze: [state: "Clear"]], pressureHistory: [[time: T0, value: 30.0]], lastLightningCount: 3])
u1.app.migrateTo2()
H.check("upgrade: Tornado/Tropical removed, pressure history restarted, lightning baseline reset", !u1.st.threatMatrix.containsKey("Tornado") && !u1.st.threatMatrix.containsKey("Tropical") && u1.st.pressureHistory.isEmpty() && u1.st.lastLightningCount == null && u1.st.schema == 2)
H.check("2.0 hazard list", x.app.hazardIds() == ["Thunderstorm", "Flood", "Freeze", "SevereHeat", "FireWeather"])

// ── 15. Fixes from the 2.0 review ───────────────────────────────────────────
// Falling pressure: median filter must not reorder its window.
def f1 = newApp()
f1.press.attrs = [pressureAbs: 26.80]
(0..12).each { i -> f1.press.attrs.pressureAbs = 26.80 - i * 0.02; f1.clock.t += 5L * 60000; f1.app.recordPressure() }
def fv = f1.st.pressureHistory.collect { it.value as Double }
H.check("pressure: a steady fall is recorded (median filter keeps order)", fv[-1] < 26.60 && f1.app.getTrendData(f1.st.pressureHistory, 0.25).rate < -0.2, "${fv}")
// Same upload, two attributes: one sample.
def f2 = newApp()
f2.press.attrs = [pressure: 27.58, pressureAbs: 26.62]
f2.app.recordPressure(); f2.clock.t += 2000; f2.app.recordPressure()
H.check("pressure: duplicate attribute in one upload stored once", f2.st.pressRaw3.size() == 1, "${f2.st.pressRaw3}")
// Watch upgraded to warning is news, even though it references the watch.
def u2 = newApp()
u2.app.noaaResponseHandler(nws("Winter Storm Watch", "Heavy snow possible."), null)
u2.app.noaaResponseHandler(nws("Winter Storm Warning", "Heavy snow.", [messageType: "Update", references: [[identifier: "urn:Winter Storm Watch"]]]), null)
H.check("NWS: watch upgraded to warning is pushed (not swallowed as an update)", u2.pushes.size() == 1 && u2.pushes[0].msg.contains("Winter Storm Warning"), "${u2.pushes} ${u2.st.notifyLog.take(3)}")
// Onset: Freeze Warning starting 1 am does not switch Freeze at 21:30.
def o1 = newApp([:], [freezeDayLog: warmLog.clone(), nwsAlerts: [nwsA("Freeze Warning", [onset: T0 + 3L * 3600000])], lastNwsGoodPoll: T0])
step(o1, 45.0)
H.check("NWS onset: product not yet in effect does not drive the hazard", frState(o1) == "Clear" && o1.app.nwsHazardStatus("Freeze") == "Clear", frState(o1))
o1.clock.t += 3L * 3600000; o1.st.lastNwsGoodPoll = o1.clock.t; step(o1, 45.0)
H.check("NWS onset: in effect after onset", frState(o1) == "ALARM", frState(o1))
// NWS-driven state is released even while the hazard is paused.
def o2 = newApp([:], [freezeDayLog: warmLog.clone(), nwsAlerts: [nwsA("Freeze Warning")], lastNwsGoodPoll: T0])
step(o2, 45.0)
o2.temp.attrs = [:]
o2.st.nwsAlerts = []; o2.st.lastNwsGoodPoll = o2.clock.t + 30L * 60000
(1..3).each { o2.clock.t += 10L * 60000; o2.app.evaluateMatrix() }
H.check("paused hazard: NWS-driven ALARM released when the product ends", frState(o2) == "Clear", "${frState(o2)} ${o2.st.threatMatrix.Freeze.howWhy}")
// Event stats with a missing temperature do not crash the cycle.
def o3 = newApp([enableThunderstorm: true], [nwsAlerts: [nwsA("Severe Thunderstorm Warning")], lastNwsGoodPoll: T0])
o3.st.threatMatrix.Thunderstorm = [threat: 0, prob: 0, conf: 0, state: "Clear", howWhy: "", mathEx: "", lastTrig: 0, history: []]
step(o3, 60.0)
o3.temp.attrs = [:]
o3.clock.t += 60000; o3.app.evaluateMatrix()
o3.st.nwsAlerts = []; o3.st.lastNwsGoodPoll = o3.clock.t + 3600000
(1..3).each { o3.clock.t += 10L * 60000; o3.app.evaluateMatrix() }
H.check("event end with missing temperature: no engine error", o3.st.lastEngineError == null, "${o3.st.lastEngineError}")
// mm rain on a metric hub.
def o4 = newApp([enableFlood: true, rainUnit: "mm"])
def rd1 = new Dev(id: "5", displayName: "RainDaily", attrs: [rainDaily: 20.0])
o4.settings.sensorRainDaily = rd1; o4.b.setVariable("sensorRainDaily", rd1)
o4.st.threatMatrix.Flood = [threat: 0, prob: 0, conf: 0, state: "Clear", howWhy: "", mathEx: "", lastTrig: 0, history: []]
step(o4, 60.0)
H.check("rain in mm: 20 mm daily is 0.79 in, no flood WARNING", Math.abs((o4.st.currentDayRain as Double) - 0.787) < 0.01 && o4.st.threatMatrix.Flood.state == "Clear", "${o4.st.currentDayRain} ${o4.st.threatMatrix.Flood.state}")
// Thunderstorm paused (not cleared) when the anemometer drops.
def o5 = newApp([enableThunderstorm: true])
def wdev = new Dev(id: "6", displayName: "Wind", attrs: [windSpeed: 10.0, windGust: 62.0])
o5.settings.sensorWind = wdev; o5.b.setVariable("sensorWind", wdev)
o5.st.threatMatrix.Thunderstorm = [threat: 0, prob: 0, conf: 0, state: "ALARM", howWhy: "", mathEx: "", lastTrig: T0, history: []]
wdev.attrs = [:]
(1..3).each { o5.clock.t += 10L * 60000; o5.app.evaluateMatrix() }
H.check("wind sensor lost mid-storm: Thunderstorm ALARM held", o5.st.threatMatrix.Thunderstorm.state == "ALARM" && o5.st.threatMatrix.Thunderstorm.howWhy.contains("Paused"), "${o5.st.threatMatrix.Thunderstorm}")
// Two sensors offline an hour apart: both announced.
def o6 = newApp()
o6.temp.lastActivity = new Date(o6.clock.t - 40L * 60000)
(1..3).each { o6.clock.t += 6L * 60000; o6.app.evaluateMatrix() }
o6.hum.lastActivity = new Date(o6.clock.t - 40L * 60000)
(1..3).each { o6.clock.t += 6L * 60000; o6.app.evaluateMatrix() }
H.check("sensor offline: each sensor announced once", o6.pushes.count { it.msg.contains("Temperature: stale") } == 1 && o6.pushes.count { it.msg.contains("Humidity: stale") } == 1, "${o6.pushes}")
// Possible ice + plain Freeze Warning stays ICE WARNING (no promotion).
def o7 = newApp([:], [freezeDayLog: [true] * 14, nwsAlerts: [nwsA("Freeze Warning")], lastNwsGoodPoll: T0])
o7.rain.attrs.rainRate = 0.05
step(o7, 30.0)
H.check("ice: plain Freeze Warning does not confirm local possible ice", frState(o7) == "ICE WARNING", frState(o7))
// Upgrade drops saved 'any' source settings.
def removed = []
def u3 = newApp([n_freezeAlarm_source: "any", n_iceAlarm_source: "nws"])
u3.b.setVariable("app", [id: 1, label: "ASWD", removeSetting: { String rk -> removed << rk; u3.settings.remove(rk) }])
u3.app.migrateTo2()
H.check("upgrade: saved 'any' source removed, other choices kept", removed == ["n_freezeAlarm_source"] && u3.settings.n_iceAlarm_source == "nws", "${removed}")
H.check("NWS: Avalanche Warning is a civil-emergency product (pushed by default)", x.app.nwsClassify("Avalanche Warning", "").grp == "civil")

H.check("no engine errors in any run", ![a, b, b2, c, d, t1, q2, q3, q4, q5, q6, q7, q8, q9, r1, r2, i1, i2, i3, i4, n1, n2, n3, n4, d2, e, f, g, g2, h, h2, k, k2, m, p1, p2, p3, s1, s2, s3, w1, w3, w4, w6, mt, f1, u2, o1, o2, o4, o5, o6, o7].any { it.st.lastEngineError })
println "\n${H.pass} passed, ${H.fail} failed"
System.exit(H.fail ? 1 : 0)
