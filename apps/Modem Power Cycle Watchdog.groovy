/**
 * Modem Power Cycle Watchdog
 * ============================================================
 * Watches your Internet connection from the Hubitat hub. If the
 * Internet stops working but your local network is healthy, it
 * restarts your modem by briefly cutting power to the smart plug
 * the modem is connected to, then verifies the fix worked.
 *
 * HARDWARE-AGNOSTIC. Works with:
 *   - Any modem or gateway (cable, DSL, fiber ONT, fixed
 *     wireless, Starlink router) fed by a smart plug
 *   - Any Hubitat switch or outlet device (Z-Wave, Zigbee,
 *     Matter, virtual) as the plug
 *   - Any router. The modem-address check is optional and only
 *     used when the hardware provides one.
 *
 * DECISION LOGIC (evaluated in order, stops at first abort):
 *   1. Can the hub reach the Internet?        yes -> do nothing
 *   2. Can the hub reach the router?          no  -> abort
 *      (router problem; restarting the modem will not help)
 *   3. Can the hub reach the modem, if a
 *      modem address is configured?           no  -> abort
 *      (modem has no power or has failed; restarting via the
 *      plug cannot fix that and might leave it off)
 *   4. Restart the modem via the plug, wait, verify.
 *
 * SAFETY GUARDS:
 *   - A problem must persist through a confirmation re-test
 *     before anything happens (ignores brief blips)
 *   - Hard cap on restarts per rolling 24 hours, plus a
 *     cooldown between restarts (prevents restart loops during
 *     provider-wide outages, which look identical to a local
 *     fault from inside the network)
 *   - Aborts if the plug is already off (assumes a human did
 *     that deliberately)
 *   - Power restore is verified, retried, and if the plug still
 *     will not confirm ON, retried persistently every minute
 *     with a single alert
 *   - Power restore is honored even if the app is disabled,
 *     reconfigured, updated, or the hub reboots mid-restart
 *   - Duplicate alerts are suppressed (one per cause per hour)
 *
 * RESILIENCE DESIGN (hub reboots and code updates):
 *   - The state machine is stored as STABLE KEYS ("cycling",
 *     "plugfault", ...) that MUST NEVER be renamed between
 *     versions. Display text is derived at render time, so
 *     wording can change freely without breaking stored state.
 *   - migrateState() maps any state value written by an older
 *     version onto the current keys, so updating the code
 *     mid-restart cannot orphan the plug in the OFF position.
 *   - A version stamp (state.codeVersion) is checked at the top
 *     of every entry point. After a code update, the first
 *     event or scheduled tick rebuilds subscriptions and
 *     schedules automatically; the user does not have to open
 *     the app and press Done.
 *   - Every timer handler validates the current state before
 *     acting, so a stale scheduled job left over from an older
 *     version (or from before a reboot) cannot fire into a
 *     state it no longer belongs to.
 *   - hubStartHandler (location "systemStart" event) restores
 *     any in-flight restart after a hub reboot instead of
 *     relying on pending jobs surviving it.
 *   - COMPATIBILITY RULE FOR FUTURE VERSIONS: the scheduled
 *     handler method names (scheduledCheck, confirmCheck,
 *     restorePower, verifyPowerOn, persistentRestore,
 *     recoveryCheck, logsOff, hubStartHandler) are a public
 *     API to the hub's job scheduler. Do not rename them.
 *
 * APP-LIST STATUS BADGE:
 *   The app's name in the Hubitat installed-apps list carries a
 *   live status badge (same pattern as the irrigation and HRV
 *   apps in this collection): one badge at a time, highest
 *   severity wins, HTML color always paired with a symbol and
 *   text (WCAG 1.4.1), rewritten by updateAppLabel() from every
 *   state change, user-disableable.
 *
 * HUMAN FACTORS BASIS FOR THE INTERFACE
 * The configuration UI applies established, citable usability
 * principles rather than ad-hoc preference:
 *   - Visibility of system status (Nielsen #1; ISO 9241-110
 *     self-descriptiveness): persistent plain-language status
 *     banner, readiness checklist, and the app-list badge.
 *   - Match to the real world / plain language (Nielsen #2):
 *     no protocol jargon in the primary UI.
 *   - Recognition rather than recall (Nielsen #6): recommended
 *     values, units, and reasons sit next to each field.
 *   - Error prevention (Nielsen #5; ISO 9241-110 error
 *     tolerance): built-in setup test with plain-language
 *     interpretation; two-step arm-and-confirm on the manual
 *     restart.
 *   - User control and freedom (Nielsen #3): enable switch,
 *     cancel path, budget reset, badge on/off.
 *   - Flexibility via progressive disclosure (Nielsen #7):
 *     three-step main page; Advanced holds the rest.
 *   - Help users recognize, diagnose, recover (Nielsen #9):
 *     every alert states what happened, why, what to do next.
 *   - Status never by color alone (WCAG 2.2 SC 1.4.1).
 * References: Nielsen, J. (1994), CHI '94; ISO 9241-110:2020;
 * W3C WCAG 2.2.
 *
 * Author:      Frank Tank (GitHub: FrankTank34567)
 * Version:     2.2.1  (2026-08-28)
 * License:     Apache 2.0
 *
 * Developed with Claude (Anthropic). Original hardware context:
 * ARRIS SB8200 on Spectrum, ASUS ZenWiFi router, Innr SP 224
 * Zigbee plug, Hubitat C-x. Generalized in 2.0.0.
 *
 * INSTALLATION:
 *   Hubitat -> Apps Code -> New App -> paste -> Save
 *   Apps -> Add User App -> Modem Power Cycle Watchdog
 *
 * OPERATIONAL NOTES:
 *   - hubitat.helper.NetworkUtils.ping() is a BLOCKING platform
 *     call. Steady-state cost is one ping burst per interval;
 *     router/modem pings run only after a failure.
 *   - The setup test blocks the UI for up to ~30 seconds while
 *     it runs. The page warns about this.
 *
 * CHANGELOG:
 *   2.2.1 - 2026-08-28
 *     LABEL ARCHITECTURE FIX (from live testing):
 *     - ROOT CAUSE: under 2.1.0 the label outgrew the
 *       platform's storage limit and was truncated, embedding
 *       ellipsis fragments and a tag cut mid-way ("<span
 *       style='color:#2..."). 2.2.0's cleaner required intact
 *       tags and worked end-anchored, so it stalled at the
 *       first fragment and could not heal the damage.
 *     - FIX: state.baseLabel is now the source of truth for
 *       the app's name. The label is compose-only: base plus
 *       exactly one badge, never parsed back except to detect
 *       a user rename. The base is capped at 60 characters so
 *       a platform truncation can never occur again.
 *     - FIX: cleanBase() rebuilt: removes badge text globally
 *       (not end-anchored), strips truncated tag fragments,
 *       escaped fragments, orphaned entities, ellipsis debris,
 *       and stranded symbol runs, then collapses whitespace.
 *       Verified against the exact corrupted label observed.
 *     - Log prefixes now use the clean base name instead of
 *       the decorated label.
 *   2.2.0 - 2026-08-28
 *     APP-LIST BADGE FIXES (from live testing on a C-8 Pro):
 *     - FIX: the badge appended to itself on every refresh
 *       ("... (Watching) (Watching) (Watching)"). Base-name
 *       recovery stripped HTML tags but not the badge text
 *       inside them. Replaced with cleanBase(), which strips
 *       tags, escaped tags, stray entities, and then loops
 *       stripping trailing badge text until stable - this also
 *       self-heals labels already corrupted by 2.1.0.
 *     - FIX: badges rendered without color. Saving settings
 *       passes the label back through the label input, which
 *       strips span tags. Badges now pair named-color spans
 *       with inherently colored emoji symbols, so the state
 *       color survives even when the HTML is sanitized.
 *     ANTI-NUISANCE NOTIFICATION PASS:
 *     - FIX (spam hole): the repeat-alert dedupe map was
 *       cleared every time the Internet recovered, so a
 *       flapping connection after the daily budget was spent
 *       could re-send "standing down" on every flap. The
 *       dedupe window now persists across recoveries; only
 *       its time-to-live expires it.
 *     - NEW: master alert switch (one toggle silences every
 *       notification without disabling the watchdog).
 *     - NEW: user-configurable minimum time between repeat
 *       alerts for the same ongoing cause (default 60 min).
 *     - NEW: optional quiet hours. During quiet hours only the
 *       critical plug-fault alert is delivered; everything
 *       else is logged and suppressed.
 *     - NEW: "What you will receive" worst-case summary on the
 *       Alerts page, so expectations are set before enabling.
 *   2.1.0 - 2026-08-28
 *     - NEW: live status badge in the installed-apps list
 *       (updateAppLabel), matching the irrigation/HRV app
 *       pattern: severity-ordered, one badge, symbol + text +
 *       color, optional via "Show status in the app name".
 *     - FIX (cross-version safety): 2.0.0 stored the state
 *       machine as display phrases and changed them from 1.0.0,
 *       so updating the code mid-restart could leave the plug
 *       OFF unrecognized. State now uses stable keys; display
 *       text is derived; migrateState() maps all legacy values.
 *     - NEW: code-update self-heal. state.codeVersion is
 *       checked at every entry point; after an update the next
 *       tick rebuilds schedules and subscriptions without the
 *       user opening the app.
 *     - NEW: state guards in every timer handler so stale
 *       scheduled jobs from old versions or reboots cannot act
 *       in the wrong state; SUSPECT confirmation re-armed after
 *       reinitialization instead of being silently lost.
 *     - Label writes are skipped when unchanged to limit
 *       database churn.
 *   2.0.0 - 2026-08-28
 *     - Safety fixes from self-review of 1.0.0 (stranded-plug
 *       paths on disable/uninstall/reboot; alert spam), modem
 *       address made optional, router auto-suggest, full
 *       plain-language human-factors interface rework.
 *   1.0.0 - 2026-08-28 - Initial release
 * ============================================================
 */

import groovy.transform.Field

definition(
    name:           "Modem Power Cycle Watchdog",
    namespace:      "franktank",
    author:         "Frank Tank",
    description:    "Restarts your modem via its smart plug when the Internet goes down and the fault is genuinely the modem's to fix. Guarded against restart loops.",
    category:       "Convenience",
    iconUrl:        "",
    iconX2Url:      "",
    importUrl:      "https://raw.githubusercontent.com/FrankTank34567/hubitat-modem-watchdog/main/ModemPowerCycleWatchdog.groovy",
    singleInstance: false
)

// ============================================================
// CONSTANTS
// ============================================================

@Field static final String  APP_VERSION           = "2.2.1"

@Field static final Integer PING_COUNT            = 3       // per target; platform max is 5
@Field static final Integer DEFAULT_POLL_MINUTES  = 5
@Field static final Integer DEFAULT_CONFIRM_SECS  = 120
@Field static final Integer DEFAULT_OFF_SECS      = 60
@Field static final Integer DEFAULT_BOOT_SECS     = 300
@Field static final Integer DEFAULT_MAX_PER_DAY   = 2
@Field static final Integer DEFAULT_COOLDOWN_MIN  = 30
@Field static final Integer ON_VERIFY_SECS        = 15
@Field static final Integer MAX_ON_RETRIES        = 3
@Field static final Integer DEFAULT_REPEAT_MINS   = 60      // min gap for repeat alerts, same cause
@Field static final Long    ARM_WINDOW_MS         = 60000L

// ---- Watchdog state keys. STABLE API - never rename across versions.
// Display wording lives in machineText(), which may change freely.
@Field static final String  ST_NORMAL     = "normal"
@Field static final String  ST_SUSPECT    = "suspect"
@Field static final String  ST_CYCLING    = "cycling"
@Field static final String  ST_RECOVERING = "recovering"
@Field static final String  ST_FAILED     = "failed"
@Field static final String  ST_PLUGFAULT  = "plugfault"

// ---- App-list badges: one at a time, severity-ordered, symbol + text + color.
// Colored emoji are used alongside the span color on purpose: the settings
// page's label input strips HTML on save, and the emoji keeps carrying the
// state color when the span does not survive.
@Field static final String  LABEL_OFF       = " <span style='color:gray'>\u23F8\uFE0F (Off)</span>"
@Field static final String  LABEL_OK        = " <span style='color:green'>\u2705 (Watching)</span>"
@Field static final String  LABEL_SUSPECT   = " <span style='color:orange'>\u26A0\uFE0F (Checking a problem)</span>"
@Field static final String  LABEL_CYCLING   = " <span style='color:orange'>\uD83D\uDD0C (Restarting modem)</span>"
@Field static final String  LABEL_RECOVER   = " <span style='color:orange'>\u23F3 (Modem starting up)</span>"
@Field static final String  LABEL_PLUGFAULT = " <span style='color:red'>\uD83C\uDD98 (PLUG FAULT - check plug)</span>"
@Field static final String  LABEL_FAILED    = " <span style='color:red'>\uD83D\uDED1 (Standing down)</span>"

// Badge status words, used to strip old badge text off the label no matter
// how the platform mangled the surrounding HTML. Order does not matter.
@Field static final String  BADGE_WORDS_RX  =
    /(Watching|Checking a problem|Restarting modem|Modem starting up|PLUG FAULT - check plug|Standing down|Off)/

// Hard cap on the stored base name. Base + longest badge stays far below the
// platform's label storage limit, so truncation (the root cause of the 2.1.x
// label corruption) can never recur.
@Field static final Integer MAX_BASE_LEN    = 60

// ============================================================
// PREFERENCES / PAGES
// ============================================================

preferences {
    page(name: "mainPage")
    page(name: "testPage")
    page(name: "advancedPage")
    page(name: "notifyPage")
    page(name: "historyPage")
    page(name: "helpPage")
}

// ---- tiny HTML helpers: symbol + text always accompany color (WCAG 1.4.1)
private String h(String s)    { "<div style='font-weight:bold;font-size:16px'>${s}</div>" }
private String good(String s) { "<span style='color:#276749'><b>&#10003; ${s}</b></span>" }
private String warnT(String s){ "<span style='color:#c05621'><b>&#9888; ${s}</b></span>" }
private String bad(String s)  { "<span style='color:#9b2c2c'><b>&#10007; ${s}</b></span>" }
private String dim(String s)  { "<span style='color:#666666'>${s}</span>" }

def mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {

        section(h("Modem Power Cycle Watchdog")) {
            paragraph statusBanner()
            paragraph dim("Version ${APP_VERSION} &nbsp;|&nbsp; " +
                          "This app restarts your modem by turning its smart plug off and on " +
                          "when the Internet stops working and the modem is the likely fix.")
        }

        section(h("Step 1 - Choose the modem's plug")) {
            paragraph "Pick the smart plug or outlet your modem is plugged into. " +
                      "This is the device that will be turned off and back on."
            input "modemPlug", "capability.switch",
                  title: "Modem's smart plug",
                  description: "Tap to select", required: true, multiple: false, submitOnChange: true
            if (modemPlug) {
                String s = modemPlug.currentValue("switch") ?: "unknown"
                paragraph "The plug currently reports: <b>${s.toUpperCase()}</b>"
                paragraph warnT("Before relying on this app, unplug that smart plug from the wall for " +
                        "ten seconds and plug it back in. If your modem does not power up on its own, " +
                        "this plug is not safe to use for a modem - a power outage could leave your " +
                        "modem off permanently.")
            }
        }

        section(h("Step 2 - Test your setup")) {
            href "testPage", title: "Run the setup test",
                 description: state.testSummaryShort ?: "Not run yet - do this before turning the app on",
                 state: state.testAllPassed ? "complete" : null
        }

        section(h("Step 3 - Choose where alerts go")) {
            href "notifyPage", title: "Alerts",
                 description: notifySummary(),
                 state: notifyDevices ? "complete" : null
        }

        section(h("Readiness checklist")) {
            paragraph readinessChecklist()
        }

        section(h("Turn it on")) {
            input "enableApp", "bool", title: "Watchdog enabled", defaultValue: false, submitOnChange: true
            if (!enableApp) paragraph dim("The watchdog is OFF. Nothing will be monitored or restarted.")
        }

        section(h("More")) {
            href "advancedPage", title: "Advanced settings",
                 description: "Addresses, timing, and restart limits. Sensible defaults are already set."
            href "historyPage", title: "History and manual restart",
                 description: "What the watchdog has done, and a button to restart the modem yourself"
            href "helpPage", title: "How it decides, and what the alerts mean",
                 description: "Plain-language explanation"
        }

        section("") {
            input "showStatusInLabel", "bool",
                  title: "Show live status in the app's name in the apps list",
                  defaultValue: true, submitOnChange: true
            input "logEnable",   "bool", title: "Debug logging (turns itself off in 30 min)", defaultValue: false
            input "logDescText", "bool", title: "Normal activity logging", defaultValue: true
            label title: "Rename this app instance (optional)", required: false
            paragraph dim("If you rename the app, the status badge is re-applied to the new name " +
                          "automatically. Names are kept under ${MAX_BASE_LEN} characters. If the " +
                          "name ever looks wrong, type the name you want here and press Done - " +
                          "that always wins.")
        }
    }
}

def testPage() {
    dynamicPage(name: "testPage", title: "Setup Test", install: false, uninstall: false) {

        section(h("What this does")) {
            paragraph "The hub will try to reach the Internet, your router, and (if configured) your " +
                      "modem, and tell you in plain language what it found. Run it once during setup and " +
                      "again any time you change your network."
            paragraph warnT("The test takes up to 30 seconds and the page will look frozen while it runs. " +
                            "That is normal. The results appear below when it finishes.")
            input "doTestPing", "button", title: "Run the test now"
        }

        if (state.testReport) {
            section(h("Results from ${fmtTime(state.testRanAt as Long)}")) {
                paragraph state.testReport
            }
        }

        section(h("Addresses being tested")) {
            paragraph dim("These come from Advanced settings. Defaults work for most homes; " +
                          "the router address below was suggested from your hub's own address and " +
                          "should be verified.")
            paragraph "Internet check addresses: <b>${internetTargets ?: '1.1.1.1, 9.9.9.9, 8.8.8.8'}</b><br>" +
                      "Router address: <b>${effectiveGatewayIp() ?: 'not set'}</b><br>" +
                      "Modem address: <b>${modemIp ?: 'not set (this check will be skipped)'}</b>"
        }
    }
}

def advancedPage() {
    dynamicPage(name: "advancedPage", title: "Advanced Settings", install: false, uninstall: false) {

        section(h("Internet check addresses")) {
            paragraph "The hub decides the Internet is working if <b>any</b> of these addresses answers. " +
                      "They are large public services chosen because they are almost never all down at once. " +
                      "Plain numeric addresses are used on purpose, so that a name-lookup (DNS) failure is " +
                      "not mistaken for a dead connection. Comma-separated."
            input "internetTargets", "string",
                  title: "Addresses (recommended: leave as is)",
                  defaultValue: "1.1.1.1,9.9.9.9,8.8.8.8", required: true
        }

        section(h("Your router's address")) {
            paragraph "Used to make sure your router is alive before blaming the modem. If the router is " +
                      "down, restarting the modem will not help, and the watchdog stands down instead."
            String guess = suggestGatewayIp()
            if (guess) {
                paragraph dim("Suggested from your hub's own address: <b>${guess}</b>. This is a guess. " +
                              "It is right for most home networks, but verify it: it is the same address " +
                              "you type into a browser to open your router's settings page.")
            }
            input "gatewayIp", "string",
                  title: "Router address",
                  defaultValue: guess, required: false
            paragraph dim("If left blank, this safety check is skipped. Not recommended.")
        }

        section(h("Your modem's address (optional)")) {
            paragraph "Many modems answer at a fixed address on your network even when the Internet is " +
                      "down. If yours does, the watchdog uses it to confirm the modem has power before " +
                      "restarting it. Examples: most cable modems (ARRIS, Motorola, Netgear CM series) " +
                      "answer at <b>192.168.100.1</b>. Many fiber boxes, DSL modems, and all-in-one " +
                      "gateways do not have a separate address - if you are not sure, leave this blank. " +
                      "The app still works; it just has one less safety check."
            input "modemIp", "string", title: "Modem address (leave blank to skip this check)", required: false
        }

        section(h("Timing")) {
            input "pollMinutes", "enum", title: "How often to check the Internet",
                  options: ["1": "Every minute", "5": "Every 5 minutes (recommended)",
                            "10": "Every 10 minutes", "15": "Every 15 minutes"],
                  defaultValue: "${DEFAULT_POLL_MINUTES}", required: true

            input "confirmSecs", "number",
                  title: "How long a problem must last before acting (seconds)",
                  defaultValue: DEFAULT_CONFIRM_SECS, range: "30..900", required: true
            paragraph dim("Recommended: 120. Prevents a brief blip from causing a restart.")

            input "offSecs", "number",
                  title: "How long to keep the modem unplugged (seconds)",
                  defaultValue: DEFAULT_OFF_SECS, range: "15..300", required: true
            paragraph dim("Recommended: 60. Long enough for a genuine reset on any hardware.")

            input "bootSecs", "number",
                  title: "How long your modem needs to start up (seconds)",
                  defaultValue: DEFAULT_BOOT_SECS, range: "60..1800", required: true
            paragraph warnT("Measure this once instead of guessing: unplug your modem, plug it back in, " +
                    "and time how long until the Internet works again. Enter that number plus about " +
                    "half again as margin. Cable modems commonly need 2 to 5 minutes. If this number " +
                    "is too small, the watchdog will wrongly report that a successful restart failed.")
        }

        section(h("Restart limits")) {
            input "maxPerDay", "number",
                  title: "Maximum automatic restarts per 24 hours",
                  defaultValue: DEFAULT_MAX_PER_DAY, range: "1..6", required: true
            paragraph dim("Recommended: 2. When your provider has an area-wide outage, no amount of " +
                          "modem restarts will fix it, and from inside your home that outage looks " +
                          "identical to a modem problem. This cap is what stops the watchdog from " +
                          "fighting an outage it cannot fix.")
            input "cooldownMin", "number",
                  title: "Minimum minutes between restarts",
                  defaultValue: DEFAULT_COOLDOWN_MIN, range: "10..240", required: true
            paragraph dim("Recommended: 30.")
        }
    }
}

def notifyPage() {
    dynamicPage(name: "notifyPage", title: "Alerts", install: false, uninstall: false) {

        section(h("Where should alerts go?")) {
            input "notifyDevices", "capability.notification",
                  title: "Alert devices",
                  description: "Tap to select", required: false, multiple: true
            paragraph warnT("Phone push alerts travel over the Internet, so alerts sent while your " +
                    "Internet is down arrive late, after service is restored. They are still worth " +
                    "having as a record. For real-time awareness during an outage, also pick a local " +
                    "device such as a speaker or chime if you have one.")
        }

        section(h("Master switch")) {
            input "notifyEnable", "bool",
                  title: "Send alerts at all",
                  defaultValue: true, submitOnChange: true
            if (notifyEnable == false) paragraph warnT("All alerts are muted. The watchdog still " +
                    "works and still logs everything; it just will not message you.")
        }

        section(h("Which alerts?")) {
            input "notifyOnCycle",   "bool", title: "When a restart begins",                          defaultValue: true
            input "notifyOnSuccess", "bool", title: "When a restart fixes the Internet",              defaultValue: true
            input "notifyOnFail",    "bool", title: "When a restart does not fix it",                 defaultValue: true
            input "notifyOnAbort",   "bool", title: "When the watchdog decides NOT to act, and why",  defaultValue: true
        }

        section(h("Nuisance controls")) {
            input "repeatMins", "number",
                  title: "Minimum minutes between repeat alerts for the same ongoing problem",
                  defaultValue: DEFAULT_REPEAT_MINS, range: "15..1440", required: true
            paragraph dim("Recommended: 60. An ongoing problem alerts once, then stays quiet for " +
                          "this long even if the connection flaps up and down.")
            input "quietStart", "time", title: "Quiet hours start (optional)", required: false, submitOnChange: true
            input "quietEnd",   "time", title: "Quiet hours end (optional)",   required: false, submitOnChange: true
            paragraph dim("During quiet hours, only one alert can get through: the critical " +
                          "\"plug will not confirm ON\" message, because that one can mean your modem " +
                          "is sitting without power. Everything else is logged and suppressed.")
        }

        section(h("What you will receive - worst cases, with defaults")) {
            paragraph """<b>A single outage the restart fixes:</b> 2 messages (restarting, fixed).<br>
<b>A single outage the restart cannot fix</b> (e.g. a provider outage): about 5 messages across the
whole day - two restart attempts with their results, then one standing-down notice - then silence.<br>
<b>A connection that flaps up and down for hours:</b> the restart budget bounds the restart messages,
and repeat alerts for the same cause are held to the minimum-minutes setting above.<br>
<b>Router down / modem dark / plug turned off by a person:</b> one message per cause per repeat
window, however long it lasts.<br>
<b>The only repeating alert:</b> none repeat on their own. Nothing is sent on a timer or a schedule."""
        }
    }
}

def historyPage() {
    dynamicPage(name: "historyPage", title: "History and Manual Restart", install: false, uninstall: false) {

        section(h("Current status")) {
            paragraph statusBanner()
            paragraph "Last Internet check: <b>${fmtTime(state.lastCheck as Long)}</b><br>" +
                      "Result: ${state.lastResult ?: dim('no data yet')}"
        }

        section(h("Restart history")) {
            paragraph "Automatic restarts used in the last 24 hours: " +
                      "<b>${countRecentCycles()} of ${safeInt(maxPerDay, DEFAULT_MAX_PER_DAY)}</b><br>" +
                      "Most recent restart: <b>${fmtTime(state.lastCycleAt as Long)}</b><br>" +
                      "Outcome: ${state.lastCycleOutcome ?: dim('none yet')}"
            List hist = (state.cycleHistory ?: []) as List
            if (hist) {
                paragraph "Recent restarts:<br>" + hist.collect { "&bull; ${fmtTime(it as Long)}" }.join("<br>")
            }
            input "doResetBudget", "button", title: "Reset the restart allowance now"
            paragraph dim("Use this after you have fixed an underlying problem yourself and want the " +
                          "watchdog re-armed before the 24-hour window clears on its own.")
        }

        section(h("Restart the modem manually")) {
            if (armedNow()) {
                paragraph warnT("Are you sure? Your Internet will go down for several minutes while the " +
                        "modem restarts. Press CONFIRM within 60 seconds to proceed, or Cancel.")
                input "doManualCycleConfirm", "button", title: "CONFIRM - restart the modem now"
                input "doManualCycleCancel",  "button", title: "Cancel"
            } else {
                paragraph "Restarts the modem immediately, skipping the daily limit. " +
                          "You will be asked to confirm."
                input "doManualCycleArm", "button", title: "Restart the modem..."
            }
        }
    }
}

def helpPage() {
    dynamicPage(name: "helpPage", title: "How It Decides", install: false, uninstall: false) {

        section(h("The decision, in plain language")) {
            paragraph """Every few minutes the hub quietly checks whether the Internet is reachable. When
it is not, the watchdog does <b>not</b> act immediately. It waits, checks again, and then asks two
questions before doing anything:<br><br>
<b>"Is the router alive?"</b> If your router is not answering, the router is the problem, and
restarting the modem would just add a second outage on top of the first. The watchdog stands down
and alerts you instead.<br><br>
<b>"Does the modem have power?"</b> (only if you provided a modem address) If the modem is not
answering, it has no power or has failed, and turning its plug off and on cannot help. The watchdog
stands down and alerts you.<br><br>
Only when the Internet is down, the router is fine, and the modem is powered does it act: plug off,
a pause, plug on, then a wait while the modem starts up, then a final check to see whether the fix
worked."""
        }

        section(h("What the watchdog cannot know")) {
            paragraph warnT("""From inside your home, a problem at your Internet provider looks exactly
like a problem with your modem. No app can tell them apart. That is why there is a daily restart
limit: during a provider outage the watchdog will try once or twice, tell you it did not help, and
then stand down rather than restarting your modem all day.""")
        }

        section(h("What the badges in the apps list mean")) {
            paragraph """<b>\u2705 (Watching)</b> - all is well; the Internet answered the last check.<br>
<b>\u26A0\uFE0F (Checking a problem)</b> - a failed check is being re-verified before any action.<br>
<b>\uD83D\uDD0C (Restarting modem)</b> - the plug is intentionally off right now.<br>
<b>\u23F3 (Modem starting up)</b> - power is back; waiting before judging success.<br>
<b>\uD83C\uDD98 (PLUG FAULT - check plug)</b> - the plug did not confirm the power-ON command;
retrying every minute. Physically check it.<br>
<b>\uD83D\uDED1 (Standing down)</b> - restarts did not fix it or the daily limit was reached.<br>
<b>\u23F8\uFE0F (Off)</b> - the watchdog is disabled."""
        }

        section(h("What the alerts mean")) {
            paragraph """<b>"Restarting the modem"</b> - the watchdog found the exact problem it exists to
fix and is acting on it.<br>
<b>"Restart fixed it"</b> - done; nothing needed from you.<br>
<b>"Restart did not fix it"</b> - the problem is likely at your provider. Check their outage page or
app when you can.<br>
<b>"Router not responding"</b> - restart or check your router; the modem was left alone on purpose.<br>
<b>"Modem not responding"</b> - check the modem's power and cables; the plug was left alone on purpose.<br>
<b>"Plug will not confirm ON"</b> - the smart plug did not acknowledge the power-on command. The
watchdog keeps retrying every minute, but go physically check the plug as soon as you can.<br>
<b>"Standing down"</b> - the daily limit is used up. The watchdog will re-arm as the 24-hour window
passes, or you can reset it on the History page."""
        }
    }
}

// ---- UI composition helpers ------------------------------------------------

/** Friendly wording for a state key. Wording may change; keys may not. */
private String machineText(String key) {
    switch (key) {
        case ST_NORMAL:     return "watching"
        case ST_SUSPECT:    return "confirming a problem"
        case ST_CYCLING:    return "restarting the modem"
        case ST_RECOVERING: return "waiting for the modem to start up"
        case ST_FAILED:     return "standing down - needs a human"
        case ST_PLUGFAULT:  return "plug will not confirm ON"
        default:            return key ?: "unknown"
    }
}

private String statusBanner() {
    if (!enableApp) return dim("<b>&#9724; OFF</b> - the watchdog is not monitoring anything.")
    String m = state.machine ?: ST_NORMAL
    switch (m) {
        case ST_NORMAL:     return good("Watching. Internet was reachable at the last check " +
                                        "(${fmtTime(state.lastCheck as Long)}).")
        case ST_SUSPECT:    return warnT("Possible problem detected. Confirming before acting.")
        case ST_CYCLING:    return warnT("Restarting the modem right now. The plug is intentionally off.")
        case ST_RECOVERING: return warnT("Modem power restored. Waiting for it to start up before judging.")
        case ST_PLUGFAULT:  return bad("The plug has not confirmed the power-ON command. Retrying every " +
                                       "minute. Physically check the plug.")
        case ST_FAILED:     return bad("Standing down: automatic restarts did not fix the Internet or the " +
                                       "daily limit was reached. A human needs to look at this.")
        default:            return dim("Status: ${machineText(m)}")
    }
}

private String readinessChecklist() {
    List<String> lines = []
    lines << (modemPlug ? good("Modem plug selected") : bad("No modem plug selected yet (Step 1)"))
    if (modemPlug) {
        String s = modemPlug.currentValue("switch")
        lines << (s == "on" ? good("Plug is currently ON") :
                              bad("Plug reports '${s ?: 'unknown'}' - the modem may be off right now"))
    }
    lines << (state.testAllPassed ? good("Setup test passed (${fmtTime(state.testRanAt as Long)})") :
              (state.testRanAt ? warnT("Setup test had warnings - open Step 2 to review") :
                                 bad("Setup test not run yet (Step 2)")))
    lines << (notifyDevices ? good("Alert device chosen") :
                              warnT("No alert device - the watchdog will act silently (Step 3)"))
    lines << (enableApp ? good("Watchdog is ON") : warnT("Watchdog is OFF - turn it on below when ready"))
    return lines.join("<br>")
}

private String notifySummary() {
    if (!notifyDevices) return "None chosen - recommended so you know what happened and why"
    return notifyDevices.collect { it.displayName }.join(", ")
}

// ============================================================
// APP LABEL - dynamic status badge in the installed-apps list
// ============================================================
// Same pattern as the irrigation and HRV apps: one badge at a
// time, highest severity wins, rewritten from every state
// change via setMachine(). Base name is recovered by stripping
// HTML from the current label, so user renames are respected.

/**
 * Recover a clean app name from arbitrarily mangled label text: composed
 * labels, platform-sanitized copies (tags stripped or escaped on settings
 * save), 2.1.x badge accumulation, and platform TRUNCATION debris (ellipsis
 * fragments, tags cut mid-way). Everything badge- or markup-shaped is
 * removed GLOBALLY, not just from the end, so garbage anywhere in the
 * string cannot shield garbage before it.
 */
private String cleanBase(String raw) {
    String s = (raw ?: app.name ?: "Modem Power Cycle Watchdog")
    s = s.replaceAll(/<[^>]*>/, " ")                       // complete tags
         .replaceAll(/<[^>]*$/, " ")                       // tag cut off at end
         .replaceAll(/<[^>]*?(?=\u2026|\.{3})/, " ")       // tag cut off at an ellipsis
         .replaceAll(/&lt;.*?(?:&gt;|$)/, " ")             // escaped tags, whole or cut
         .replaceAll(/&#\d+;?/, " ")                       // entities, whole or cut
         .replaceAll(/\s*[^\s\w()]{0,4}\s*\(/ + BADGE_WORDS_RX + /\)/, " ")  // ALL badge texts
         .replaceAll(/\(Watchin?g?\u2026?|\(Watchin?g?\.{3}/, " ")  // truncated badge stumps
         .replaceAll(/[\u2026]+|\.{3,}/, " ")              // ellipsis debris
         .replaceAll(/(?<=\s|^)[^\s\w()]{1,4}(?=\s|$)/, " ") // stranded symbol runs
         .replaceAll(/\s+/, " ")
         .trim()
    if (s.length() > MAX_BASE_LEN) s = s.substring(0, MAX_BASE_LEN).trim()
    return s ?: "Modem Power Cycle Watchdog"
}

/** The app's clean display name. state.baseLabel is the source of truth. */
private String appName() {
    return (state.baseLabel ?: cleanBase(app.label))
}

/**
 * Adopt a rename made through the label input, and heal legacy corruption.
 * Called from initializeCore(), i.e. after every settings save and every
 * code-update self-heal - the only moments the label input can have changed.
 */
private void adoptBaseLabel() {
    String candidate = cleanBase(app.label)
    if (!state.baseLabel) {
        state.baseLabel = candidate
        logInfo "App name recorded as '${candidate}'"
    } else if (candidate != state.baseLabel) {
        logInfo "App name changed to '${candidate}'"
        state.baseLabel = candidate
    }
}

private void updateAppLabel() {
    String base = appName()

    if (showStatusInLabel == false) {
        if (app.label != base) app.updateLabel(base)
        return
    }

    String badge
    if (!enableApp) {
        badge = LABEL_OFF
    } else {
        switch (state.machine ?: ST_NORMAL) {
            case ST_PLUGFAULT:  badge = LABEL_PLUGFAULT; break
            case ST_FAILED:     badge = LABEL_FAILED;    break
            case ST_CYCLING:    badge = LABEL_CYCLING;   break
            case ST_RECOVERING: badge = LABEL_RECOVER;   break
            case ST_SUSPECT:    badge = LABEL_SUSPECT;   break
            default:            badge = LABEL_OK;        break
        }
    }

    String newLabel = "${base}${badge}"
    if (app.label != newLabel) app.updateLabel(newLabel)   // skip writes when unchanged
}

/** Central state setter: every transition also refreshes the app-list badge. */
private void setMachine(String key) {
    state.machine = key
    updateAppLabel()
}

// ============================================================
// VERSION MIGRATION AND SELF-HEAL
// ============================================================

/**
 * Map any state.machine value written by an older version onto the
 * current stable keys. 1.0.0 used these same keys; 2.0.0 stored
 * display phrases. Unknown values fall back to "normal" (safe:
 * monitoring resumes; nothing is left half-restarted, because
 * cycling/plugfault phrases are explicitly mapped).
 */
private void migrateState() {
    String m = state.machine
    if (m == null) { state.machine = ST_NORMAL; return }
    Map<String, String> legacy = [
        // 2.0.0 display phrases -> stable keys
        "watching"                             : ST_NORMAL,
        "confirming a problem"                 : ST_SUSPECT,
        "restarting the modem"                 : ST_CYCLING,
        "waiting for the modem to start up"    : ST_RECOVERING,
        "standing down - needs a human"        : ST_FAILED,
        "plug will not confirm ON"             : ST_PLUGFAULT
    ]
    if (legacy.containsKey(m)) {
        logWarn "Migrating legacy state '${m}' -> '${legacy[m]}'"
        state.machine = legacy[m]
    } else if (!(m in [ST_NORMAL, ST_SUSPECT, ST_CYCLING, ST_RECOVERING, ST_FAILED, ST_PLUGFAULT])) {
        logWarn "Unknown legacy state '${m}'; resetting to '${ST_NORMAL}'"
        state.machine = ST_NORMAL
    }
}

/**
 * Called at the top of every entry point. After a code update, the
 * first event or tick lands here, and the app rebuilds its own
 * subscriptions and schedules; the user never has to open the app.
 */
private void ensureCurrentVersion() {
    if ((state.codeVersion ?: "none") == APP_VERSION) return
    logWarn "Code updated (${state.codeVersion ?: 'pre-2.1.0'} -> ${APP_VERSION}); re-initializing"
    unsubscribe()
    unschedule()
    migrateState()
    initializeCore()
}

// ============================================================
// LIFECYCLE
// ============================================================

def installed() {
    logInfo "Installed, version ${APP_VERSION}"
    state.machine      = ST_NORMAL
    state.cycleHistory = []
    initialize()
}

def updated() {
    logInfo "Settings updated"
    unsubscribe()
    unschedule()   // wipes pending jobs; initializeCore() re-establishes in-flight state first
    migrateState()
    initializeCore()
}

def initialize() {
    migrateState()
    initializeCore()
}

def uninstalled() {
    // SAFETY: never leave the modem dark because the app was removed mid-restart.
    if (modemPlug && (state.machine in [ST_CYCLING, ST_PLUGFAULT])) {
        log.warn "${app.label}: uninstalled mid-restart - forcing modem plug ON"
        try { modemPlug.on() } catch (Exception e) { log.error "${app.label}: restore on uninstall failed: ${e.message}" }
    }
    logInfo "Uninstalled"
}

private void initializeCore() {
    // Stamp the version FIRST so ensureCurrentVersion() is a no-op if any
    // method called below (e.g. restorePower) re-enters it. Stamping last
    // would recurse: ensure -> initializeCore -> restorePower -> ensure -> ...
    state.codeVersion  = APP_VERSION
    adoptBaseLabel()   // heal any corrupted label and pick up user renames
    state.machine      = state.machine ?: ST_NORMAL
    state.cycleHistory = state.cycleHistory ?: []

    if (logEnable) runIn(1800, "logsOff")

    // SAFETY FIRST, before any enabled/disabled logic. Deliberately NOT
    // returning early: monitoring must still be rescheduled below, or a
    // mid-restart save/update would silently stop the watchdog forever.
    if (state.machine == ST_CYCLING || state.machine == ST_PLUGFAULT) {
        logWarn "Initialization found a restart in flight. Restoring modem power immediately."
        restorePower()
    } else if (state.machine == ST_RECOVERING) {
        // The recovery timer was wiped by unschedule(); re-arm it.
        runIn(safeInt(bootSecs, DEFAULT_BOOT_SECS), "recoveryCheck")
    } else if (state.machine == ST_SUSPECT) {
        // The confirmation timer was wiped; re-arm it rather than losing the check.
        runIn(safeInt(confirmSecs, DEFAULT_CONFIRM_SECS), "confirmCheck")
    }

    // Hub-reboot resilience: re-establish in-flight state on startup ourselves
    // rather than relying on pending jobs surviving the reboot.
    subscribe(location, "systemStart", "hubStartHandler")

    updateAppLabel()

    if (!enableApp) {
        logInfo "Watchdog disabled; no monitoring scheduled"
        return
    }

    Integer mins = safeInt(pollMinutes, DEFAULT_POLL_MINUTES)
    switch (mins) {
        case 1:  runEvery1Minute("scheduledCheck");   break
        case 10: runEvery10Minutes("scheduledCheck"); break
        case 15: runEvery15Minutes("scheduledCheck"); break
        default: runEvery5Minutes("scheduledCheck");  break
    }
    logInfo "Monitoring every ${mins} minute(s)"
}

def hubStartHandler(evt) {
    ensureCurrentVersion()
    String m = state.machine ?: ST_NORMAL
    logWarn "Hub restarted. Watchdog state was: ${machineText(m)}"
    if (m == ST_CYCLING || m == ST_PLUGFAULT) {
        // The plug may have been off across the entire reboot. Fix that first.
        restorePower()
    } else if (m == ST_RECOVERING) {
        runIn(30, "recoveryCheck")
    }
    updateAppLabel()
}

def logsOff() {
    log.warn "${app.label}: debug logging disabled"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ============================================================
// BUTTON HANDLERS
// ============================================================

def appButtonHandler(String btn) {
    ensureCurrentVersion()
    switch (btn) {
        case "doTestPing":
            runSetupTest()
            break
        case "doManualCycleArm":
            state.manualArmAt = now()
            break
        case "doManualCycleCancel":
            state.manualArmAt = null
            break
        case "doManualCycleConfirm":
            if (armedNow()) {
                state.manualArmAt = null
                logWarn "Manual restart confirmed by user"
                startCycle(true)
            }
            break
        case "doResetBudget":
            state.cycleHistory = []
            if (state.machine == ST_FAILED) setMachine(ST_NORMAL)
            logInfo "Restart allowance reset by user"
            break
        default:
            logDebug "Unhandled button: ${btn}"
    }
}

private boolean armedNow() {
    Long t = state.manualArmAt as Long
    return t && (now() - t) < ARM_WINDOW_MS
}

// ============================================================
// SETUP TEST (guided, plain-language interpretation)
// ============================================================

private void runSetupTest() {
    boolean inetOk  = false
    List<String> inetDetail = []
    getInternetTargetList().each { String ip ->
        boolean ok = pingOk(ip)
        if (ok) inetOk = true
        inetDetail << "${ip}: ${ok ? 'answered' : 'no answer'}"
    }

    String gw = effectiveGatewayIp()
    Boolean gwOk = gw ? pingOk(gw) : null
    Boolean mdOk = modemIp ? pingOk(modemIp) : null

    List<String> lines = []

    lines << (inetOk ?
        good("Internet: reachable. (${inetDetail.join(', ')})") :
        bad("Internet: NOT reachable. If your Internet is working right now, your network is " +
            "blocking ping tests and this app cannot function as designed. If your Internet is " +
            "genuinely down, re-run this test when it is back."))

    if (gw == null) {
        lines << warnT("Router: no address set, so this safety check will be skipped. See Advanced settings.")
    } else {
        lines << (gwOk ?
            good("Router (${gw}): answered.") :
            bad("Router (${gw}): no answer. Either the address is wrong - check it in Advanced " +
                "settings - or your router ignores ping tests. With no working router check, the " +
                "watchdog cannot tell a router problem from a modem problem."))
    }

    if (!modemIp) {
        lines << warnT("Modem: no address set, so that check will be skipped. This is fine - many " +
                       "modems have no separate address. The app still works with one less safeguard.")
    } else {
        lines << (mdOk ?
            good("Modem (${modemIp}): answered.") :
            bad("Modem (${modemIp}): no answer. Either the address is wrong for your hardware, or " +
                "your network cannot reach it from the hub. Fix the address or clear the field in " +
                "Advanced settings - a modem check that always fails would stop the watchdog from " +
                "ever acting."))
    }

    // Passing requires: internet reachable, router check either passing or intentionally blank,
    // and modem check passing if configured.
    state.testAllPassed    = (inetOk && (gw == null || gwOk) && (!modemIp || mdOk))
    state.testRanAt        = now()
    state.testReport       = lines.join("<br><br>")
    state.testSummaryShort = state.testAllPassed ? "Passed at ${fmtTime(now())}" :
                                                   "Ran with warnings at ${fmtTime(now())} - open to review"
    logInfo "Setup test complete. Passed: ${state.testAllPassed}"
}

// ============================================================
// CHECK LOOP
// ============================================================

def scheduledCheck() {
    ensureCurrentVersion()
    if (!enableApp) return
    if ((state.machine ?: ST_NORMAL) in [ST_CYCLING, ST_RECOVERING, ST_PLUGFAULT]) {
        logDebug "Check skipped; state is '${machineText(state.machine)}'"
        return
    }

    state.lastCheck = now()

    if (internetIsUp()) {
        if (state.machine != ST_NORMAL) logInfo "Internet is reachable again"
        setMachine(ST_NORMAL)
        state.lastResult = good("Internet reachable")
        // NOTE: the alert dedupe map is deliberately NOT cleared here. A
        // flapping connection would otherwise re-arm every alert on each
        // recovery and re-send it on each drop. The per-cause TTL in
        // notifyOnce() is the only thing that expires suppression.
        return
    }

    state.lastResult = bad("Internet unreachable")
    logWarn "Internet unreachable"

    if (state.machine == ST_NORMAL) {
        setMachine(ST_SUSPECT)
        Integer delay = safeInt(confirmSecs, DEFAULT_CONFIRM_SECS)
        logInfo "Confirming the problem in ${delay}s before acting"
        runIn(delay, "confirmCheck")
    }
    // In ST_SUSPECT a confirm is already pending; in ST_FAILED we stay stood down
    // until the Internet returns on its own or the budget window clears.
}

def confirmCheck() {
    ensureCurrentVersion()
    if (!enableApp) { setMachine(ST_NORMAL); return }
    // Guard: only act if the confirmation is still relevant. A stale job from
    // before a reboot or code update must not fire into the wrong state.
    if (state.machine != ST_SUSPECT) {
        logDebug "confirmCheck ignored; state is '${machineText(state.machine)}'"
        return
    }

    if (internetIsUp()) {
        logInfo "Internet recovered on its own during the confirmation delay. No action taken."
        setMachine(ST_NORMAL)
        state.lastResult = good("Recovered without intervention")
        return
    }

    String gw = effectiveGatewayIp()
    if (gw && !pingOk(gw)) {
        setMachine(ST_NORMAL)
        state.lastResult = bad("Router unreachable; stood down")
        notifyOnce("routerDown",
            "Internet is down and your router (${gw}) is not responding. This is a router problem, " +
            "not a modem problem, so the modem was left alone. Restart or check your router.")
        return
    }

    if (modemIp && !pingOk(modemIp)) {
        setMachine(ST_NORMAL)
        state.lastResult = bad("Modem unreachable; stood down")
        notifyOnce("modemDark",
            "Internet is down and your modem (${modemIp}) is not responding, which usually means it " +
            "has no power or has failed. Restarting its plug cannot fix that, so nothing was done. " +
            "Check the modem's power and cables.")
        return
    }

    startCycle(false)
}

// ============================================================
// POWER CYCLE
// ============================================================

private void startCycle(boolean manual) {
    if (!modemPlug) {
        logError "No modem plug configured; cannot restart"
        return
    }

    if (modemPlug.currentValue("switch") == "off") {
        setMachine(ST_NORMAL)
        state.lastResult = bad("Plug already off; stood down")
        notifyOnce("plugAlreadyOff",
            "The watchdog wanted to restart the modem, but its plug is already off. Assuming a " +
            "person did that on purpose, nothing was changed.")
        return
    }

    if (!manual) {
        Integer used = countRecentCycles()
        Integer cap  = safeInt(maxPerDay, DEFAULT_MAX_PER_DAY)
        if (used >= cap) {
            setMachine(ST_FAILED)
            state.lastResult = bad("Daily restart limit reached")
            notifyOnce("budgetSpent",
                "Internet is still down, but the modem has already been restarted ${used} time(s) in " +
                "24 hours without fixing it. The problem is likely at your Internet provider. The " +
                "watchdog is standing down; check your provider's outage page when you can.")
            return
        }
        Long last    = state.lastCycleAt as Long
        Integer cool = safeInt(cooldownMin, DEFAULT_COOLDOWN_MIN)
        if (last && (now() - last) < (cool * 60000L)) {
            logWarn "Within the cooldown window; not restarting yet"
            setMachine(ST_NORMAL)
            state.lastResult = bad("Cooldown active")
            return
        }
    }

    Integer off = safeInt(offSecs, DEFAULT_OFF_SECS)

    setMachine(ST_CYCLING)
    state.lastCycleAt      = now()
    state.onRetries        = 0
    state.plugFaultAlerted = false
    List hist = (state.cycleHistory ?: []) as List
    hist << now()
    state.cycleHistory = hist

    if (notifyOnCycle != false) notify(
        "Restarting the modem: power off for ${off} seconds, then " +
        "${safeInt(bootSecs, DEFAULT_BOOT_SECS)} seconds for it to start up. " +
        "The Internet will be down during this time.")
    logWarn "Restart started (manual: ${manual})"

    modemPlug.off()
    // The restore is its own scheduled job, independent of everything else.
    runIn(off, "restorePower")
}

def restorePower() {
    ensureCurrentVersion()
    // Intentionally unguarded: commanding the plug ON is always safe, and this
    // is the fail-safe path for every stranded-plug scenario.
    if (!modemPlug) { logError "No modem plug configured; cannot restore power"; return }
    logWarn "Restoring modem power"
    modemPlug.on()
    setMachine(ST_RECOVERING)
    runIn(ON_VERIFY_SECS, "verifyPowerOn")
}

def verifyPowerOn() {
    ensureCurrentVersion()
    if (!modemPlug) return
    // Guard against stale jobs: only meaningful while recovering.
    if (state.machine != ST_RECOVERING) {
        logDebug "verifyPowerOn ignored; state is '${machineText(state.machine)}'"
        return
    }

    if (modemPlug.currentValue("switch") == "on") {
        Integer boot = safeInt(bootSecs, DEFAULT_BOOT_SECS)
        logInfo "Plug confirmed ON; waiting ${boot}s for the modem to start up"
        runIn(boot, "recoveryCheck")
        return
    }

    Integer tries = (state.onRetries ?: 0) + 1
    state.onRetries = tries

    if (tries <= MAX_ON_RETRIES) {
        logWarn "Plug has not confirmed ON; retry ${tries} of ${MAX_ON_RETRIES}"
        modemPlug.on()
        runIn(ON_VERIFY_SECS, "verifyPowerOn")
        return
    }

    // Give-up path: ONE alert, then quiet persistent retries until it sticks.
    setMachine(ST_PLUGFAULT)
    if (!state.plugFaultAlerted) {
        state.plugFaultAlerted = true
        notify("URGENT: the modem's smart plug did not confirm the power-ON command after " +
               "${MAX_ON_RETRIES} attempts. The modem may still be without power. The watchdog will " +
               "keep retrying every minute, but physically check the plug as soon as you can.", true)
    }
    logError "Plug will not confirm ON; entering persistent retry"
    runEvery1Minute("persistentRestore")
}

def persistentRestore() {
    ensureCurrentVersion()
    if (!modemPlug) return
    // Guard against stale jobs: only meaningful during a plug fault.
    if (state.machine != ST_PLUGFAULT) {
        unschedule("persistentRestore")
        logDebug "persistentRestore ignored and unscheduled; state is '${machineText(state.machine)}'"
        return
    }
    if (modemPlug.currentValue("switch") == "on") {
        logWarn "Plug finally confirmed ON; resuming normal recovery"
        unschedule("persistentRestore")
        setMachine(ST_RECOVERING)
        if (state.plugFaultAlerted) notify("Update: the modem's plug has now confirmed ON. " +
            "Waiting for the modem to start up.")
        runIn(safeInt(bootSecs, DEFAULT_BOOT_SECS), "recoveryCheck")
        return
    }
    logWarn "Persistent retry: commanding plug ON again"
    modemPlug.on()
}

def recoveryCheck() {
    ensureCurrentVersion()
    // Guard against stale jobs: only judge a restart while one is being judged.
    if (state.machine != ST_RECOVERING) {
        logDebug "recoveryCheck ignored; state is '${machineText(state.machine)}'"
        return
    }

    if (internetIsUp()) {
        setMachine(ST_NORMAL)
        state.lastResult       = good("Recovered after restart")
        state.lastCycleOutcome = good("Fixed it, ${fmtTime(now())}")
        if (notifyOnSuccess != false) notify("The modem restart fixed the Internet. All good.")
        logInfo "Restart succeeded"
        return
    }

    Integer used = countRecentCycles()
    Integer cap  = safeInt(maxPerDay, DEFAULT_MAX_PER_DAY)
    boolean spent = used >= cap
    setMachine(spent ? ST_FAILED : ST_NORMAL)
    state.lastResult       = bad("Still down after restart")
    state.lastCycleOutcome = bad("Did not fix it, ${fmtTime(now())}")
    if (notifyOnFail != false) notify(
        "The modem restart did not bring the Internet back (${used} of ${cap} restarts used in 24 " +
        "hours). " + (spent ?
            "The watchdog is standing down; the problem is likely at your provider." :
            "It will try once more after the cooldown if the problem persists."))
    logWarn "Restart did not restore the Internet (used ${used}/${cap})"
}

// ============================================================
// HELPERS
// ============================================================

private List<String> getInternetTargetList() {
    String raw = (internetTargets ?: "1.1.1.1,9.9.9.9,8.8.8.8").toString()
    return raw.split(",").collect { it.trim() }.findAll { it }
}

private String effectiveGatewayIp() {
    String v = (gatewayIp ?: "")?.trim()
    return v ? v : null
}

/** Best-effort guess of the router address from the hub's own IP. Clearly labeled as a guess in the UI. */
private String suggestGatewayIp() {
    String ip = null
    try { ip = location.hubs[0]?.getDataValue("localIP") } catch (Exception ignored) { }
    if (!ip) { try { ip = location.hub?.localIP } catch (Exception ignored) { } }
    if (!ip) return null
    List parts = ip.tokenize(".")
    if (parts.size() != 4) return null
    parts[3] = "1"
    return parts.join(".")
}

private boolean internetIsUp() {
    for (String ip : getInternetTargetList()) {
        if (pingOk(ip)) { logDebug "Internet target ${ip} answered"; return true }
    }
    return false
}

/** Blocking ICMP ping wrapped so a bad address or platform hiccup can never crash the app. */
private boolean pingOk(String ip) {
    if (!ip) return false
    try {
        def pd = hubitat.helper.NetworkUtils.ping(ip.trim(), PING_COUNT)
        if (pd == null) { logDebug "Ping ${ip}: null result"; return false }
        Integer received = (pd.packetsReceived ?: 0) as Integer
        logDebug "Ping ${ip}: ${received}/${pd.packetsTransmitted} received"
        return received > 0
    } catch (Exception e) {
        logWarn "Ping ${ip} failed: ${e.message}"
        return false
    }
}

private Integer countRecentCycles() {
    Long cutoff = now() - 86400000L
    List hist = ((state.cycleHistory ?: []) as List).findAll { (it as Long) > cutoff }
    state.cycleHistory = hist
    return hist.size()
}

/** Alert once per cause per user-set repeat window, so an ongoing outage does not spam. */
private void notifyOnce(String key, String msg) {
    Map notices = (state.abortNotices ?: [:]) as Map
    Long prev = notices[key] as Long
    Long windowMs = safeInt(repeatMins, DEFAULT_REPEAT_MINS) * 60000L
    if (prev && (now() - prev) < windowMs) {
        logInfo "(suppressed repeat alert '${key}') ${msg}"
        return
    }
    notices[key] = now()
    state.abortNotices = notices
    logWarn msg
    if (notifyOnAbort != false) notify(msg)
}

private void notify(String msg, boolean critical = false) {
    if (!notifyDevices) return
    if (notifyEnable == false) {
        logInfo "(alerts muted by master switch) ${msg}"
        return
    }
    if (!critical && inQuietHours()) {
        logInfo "(suppressed by quiet hours) ${msg}"
        return
    }
    String text = "[Modem Watchdog] ${msg}"
    notifyDevices.each { dev ->
        try { dev.deviceNotification(text) }
        catch (Exception e) { logWarn "Alert to ${dev.displayName} failed: ${e.message}" }
    }
}

/** True when quiet hours are configured and the current time falls inside them. */
private boolean inQuietHours() {
    if (!quietStart || !quietEnd) return false
    try {
        return timeOfDayIsBetween(toDateTime(quietStart), toDateTime(quietEnd),
                                  new Date(), location.timeZone)
    } catch (Exception e) {
        logWarn "Quiet-hours check failed (${e.message}); treating as not quiet"
        return false
    }
}

private Integer safeInt(def val, Integer fallback) {
    if (val == null) return fallback
    try { return (val as Integer) } catch (Exception ignored) { return fallback }
}

private String fmtTime(Long ts) {
    if (!ts) return "never"
    return new Date(ts).format("MMM d, h:mm a", location.timeZone)
}

private void logDebug(String msg) { if (logEnable)            log.debug "${appName()}: ${msg}" }
private void logInfo (String msg) { if (logDescText != false) log.info  "${appName()}: ${msg}" }
private void logWarn (String msg) { log.warn  "${appName()}: ${msg}" }
private void logError(String msg) { log.error "${appName()}: ${msg}" }