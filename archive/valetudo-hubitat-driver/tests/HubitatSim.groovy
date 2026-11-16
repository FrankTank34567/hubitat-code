// Minimal Hubitat driver runtime for offline testing. Not a full emulation: it models
// exactly the platform behaviour this driver depends on.
//   - sendEvent() with "" is DROPPED (Hubitat discards empty-string events; see 3.9.1)
//   - runIn() with the same handler name REPLACES the pending schedule
//   - now() is a controllable clock
//   - interfaces.mqtt records subscribe/publish/disconnect; can be told to fail
//   - httpGet/httpPut are answered from a fake route table; unknown URIs throw
import groovy.json.JsonSlurper

abstract class HubitatSim extends Script {
    Map simState = [:]
    long clock = 1_800_000_000_000L
    List events = []                 // every committed event [name, value]
    List dropped = []                // events Hubitat would discard
    Map current = [:]                // attribute -> last committed value
    Map scheduled = [:]              // handler -> delaySec
    List logs = []                   // [level, msg]
    List published = []              // [topic, payload, qos, retained]
    List subscribed = []
    int disconnects = 0
    boolean mqttConnected = true
    Integer failSubscribeAt = null   // throw on the Nth subscribe call (1-based)
    Map getRoutes = [:]              // uri -> data (or Closure throwing)
    List puts = []                   // [uri, body]
    Integer putStatus = 200

    def getState() { simState }
    def getSettings() { binding.variables }
    long now() { clock }

    def getDevice() {
        def sim = this
        [displayName: "TESTBOT", id: 999,
         currentValue: { String n -> sim.current[n] },
         updateSetting: { String n, Map v -> sim.binding.variables[n] = v.value }]
    }

    def getLog() {
        def sim = this
        [debug: { m -> sim.logs << ["debug", m.toString()] },
         info:  { m -> sim.logs << ["info",  m.toString()] },
         warn:  { m -> sim.logs << ["warn",  m.toString()] },
         error: { m -> sim.logs << ["error", m.toString()] }]
    }

    def getInterfaces() {
        def sim = this
        [mqtt: [
            connect:      { Object[] a -> sim.mqttConnected = true },
            disconnect:   { -> sim.disconnects++; sim.mqttConnected = false },
            isConnected:  { -> sim.mqttConnected },
            subscribe:    { String t, int q = 1 ->
                              if (sim.subscribeFailAt() != null && sim.subscribed.size() + 1 == sim.subscribeFailAt())
                                  throw new RuntimeException("simulated subscribe failure")
                              sim.subscribed << t },
            publish:      { String t, String p, int q = 1, boolean r = false -> sim.published << [t, p, q, r] },
            parseMessage: { String d -> new JsonSlurper().parseText(d) }
        ]]
    }

    void sendEvent(Map m) {
        if (m.value == null || m.value.toString() == "") { dropped << [m.name, m.value]; return }
        events << [m.name, m.value]
        current[m.name] = m.value
    }

    void runIn(Number sec, String handler, Map opts = null) { scheduled[handler] = sec }
    void runEvery5Minutes(String handler) { scheduled[handler] = "every5" }
    void unschedule(String handler = null) { if (handler) scheduled.remove(handler) else scheduled.clear() }

    void httpGet(Map params, Closure c) {
        def r = getRoutes[params.uri]
        if (r == null) throw new java.net.ConnectException("no route to ${params.uri}")
        if (r instanceof Closure) r = r()
        c.call([status: 200, data: r])
    }

    void httpPut(Map params, Closure c) {
        puts << [params.uri, params.body]
        if (this.@putStatus >= 300) throw new groovyx.net.http.HttpResponseException(putStatus, "HTTP ${putStatus}")
        c.call([status: this.@putStatus])
    }

    // Hubitat metadata DSL: no-ops (run() is never called by the tests).
    def metadata(Closure c) {}

    // ── test helpers ──────────────────────────────────────────────────────────
    void mqttIn(String sub, String payload) {
        parse(groovy.json.JsonOutput.toJson([topic: "valetudo/TESTBOT/${sub}", payload: payload]))
    }
    void advance(long sec) { clock += sec * 1000L }
    void runScheduled(String handler) { scheduled.remove(handler); "${handler}"() }
    List warnings() { logs.findAll { it[0] in ["warn", "error"] }.collect { it[1] } }
    void clearLogs() { logs.clear() }
    // Script property writes go to the binding, not fields; use these setters in tests.
    void failPutsWith(int s) { putStatus = s }
    void failSubscribeOn(int n) { failSubscribeAt = n }
    Integer subscribeFailAt() { this.@failSubscribeAt }
}
