/*
* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
*	Day Lights
*
*	Author:
*		OldChicagoPete
*
*	Documentation:  https://community.hubitat.com/t/release-day-lights-an-interation-of-circadian-daylight/130157
*
* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
*
*	Forked from:
*  		Hubitat Circadian Daylight 0.81
*		https://raw.githubusercontent.com/adamkempenich/hubitat/master/Apps/CircadianDaylight.groovy
*	Which was forked from:
*  		SmartThings Circadian Daylight v. 2.6
*		https://github.com/KristopherKubicki/smartapp-circadian-daylight/
*
*
*
*   Custom fade-in/fade-out time:
*       Jeff Byrom (@talz13)
*   Color temperature converter:
*       http://www.tannerhelland.com/4435/convert-temperature-rgb-algorithm-code/
*   RGB to Hue/Saturation/Value:
*       http://www.rapidtables.com/convert/color/rgb-to-hsv.htm
*
* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
*
*  Changelog:
*	0.90 (November 2, 2023)
*		- Soft Release
*	0.91 (December 20, 2023)
*		- General Release
*	0.92 (February 2, 2024)
*		- Clarification on device selection headings
*		- When using RGB mode devices with Dynamic Brightness, the calculated level will be used for the Value parameter
*		- RGB mode devices will not be polled to determine if Dynamic Brightness has been overridden
*		- Fix for HSV return values
*	0.93 (February 3, 2024)
*		- New option in Color Temperature Options to force a device into CT mode by setting saturation to zero
*	0.94 (February 3, 2024)
*		- New option to reset the color temperature when a device is dimmed manually
*		- Fix for Brightness Options page
*		- Minor formatting updates
*	0.95 (March 24, 2024)
*       - Fix for sunrise/sunset offset
*		- New option to select switches to turn on while Day Lights is active
*		- Added a log entry when manual dimming turns off Dynamic Brightness
*		- Don't disable Dynamic Brightness if device reports current level as null
*	0.96 (October 4, 2024)
*       - No log entries from scheduled updates while processing is disabled
*		- New option to select a button to re-enable Dynamic Brightness and/or refresh the lights
*		- New option to select hub variables to save the calculated color temperature and brightness values
*	0.96.1 (May 26, 2026)
*		- Fix: state.processing flag not reset when eventHandler returns early due to active disable switch.
*		  This caused all subsequent eventUpdate calls to be silently skipped until next initialize().
*	0.96.2 (May 26, 2026)
*		- Fix: scheduleNextWakeup() crashes with IndexOutOfBoundsException when hub has no sunriseTime
*		  event history (e.g. after a database reset or on a newly configured hub). Now falls back
*		  gracefully to getSunriseAndSunset() instead of crashing.
*		- Fix: colorTemp and brightness variables in getNewValues() now explicitly declared with def
*		  to prevent potential NullPointerException on unexpected code paths.
*	0.97 (June 1, 2026)
*		- New: Color Temperature Transition Speed setting. Controls how quickly color temperature
*		  moves from warm to cold across the day. "Stays warm longer" keeps lights warm for most
*		  of the day and only reaches peak coolness near midday. "Even transition" is a straight
*		  ramp. This replaces the previous fixed linear curve.
*		- Brightness profile descriptions rewritten to accurately describe actual behaviour including
*		  which profiles respect or intentionally ignore the Low/High Brightness override settings.
*		- Fix: Duplicate minBrightnessOverride input removed from Disable Options page. The setting
*		  is correctly located in Brightness Options only.
*		- Fix: Added null check for numberOfButtons attribute in DisableOptions page to prevent
*		  UI rendering crash when button device has not yet reported that attribute.
*		- Fix: hvCT and hvLevel null initialization in initialize() now uses proper state variable
*		  pattern instead of incorrectly writing to settings references.
*		- Improvement: Input range constraints added to CT override values to prevent nonsense entries.
*		- Improvement: Removed redundant double blank line at end of MainPage definition.
*	0.98 (June 2, 2026)
*		- Fix: Lights turning back on immediately after being switched off. The Hue bridge can emit a
*		  transient switch.on event while a bulb is mid-transition (common when a button/dial rule
*		  toggles a bulb off, or when a grouped bulb re-reports state). The app acted on that event and
*		  re-asserted the light. eventDeviceOn now waits a short settle period (750ms) and re-checks the
*		  device's actual switch state before processing; if it settled to "off" the event is ignored.
*	0.98.1 (June 20, 2026)
*		- Fix: "Command 'setSaturation' is not supported" error when Force CT mode is enabled and a
*		  white-only / tunable-white bulb is in the device list. setSaturation is now only attempted
*		  on devices that report supporting the command (device.hasCommand check added).
*	0.99 (June 23, 2026)
*		- New: Seasonal color temperature adjustment. When enabled (within Color Temperature Options),
*		  warm and cold endpoints interpolate smoothly across the year between configurable winter and
*		  summer values, reaching warmest at the winter solstice and coolest at the summer solstice.
*		  Uses a cosine curve so change eases near the solstices. Includes a Southern Hemisphere toggle.
*		  The seasonal factor is computed once per day at sunrise wakeup and cached in state.
*		  When seasonal mode is off, the original single warm/cold override inputs are shown and used.
*		- New: Live preview of today's computed seasonal warm/cold values on the options page.
*		- New: "Refresh Lights Now" button on the main page forces an immediate recalculation and
*		  re-push to all currently-on lights. Useful after changing settings or if a bulb missed an update.
*	0.99.1 (August 28, 2026)
*		- Change: A device turning on now updates only that device instead of re-pushing values to every
*		  selected device. Previously each switch.on event ran a full sweep of all devices, and because
*		  Hue Groups and their member bulbs each emit their own switch.on, one physical "on" could fire
*		  several full sweeps. On Aug 28 2026 this amplification contributed roughly 100+ Hue Bridge
*		  commands in a five minute window during a bridge API lockup. The 5-minute cron (eventUpdate)
*		  still refreshes all devices, so drift correction is unchanged.
*		- Change: App is now declared singleThreaded. Overlapping executions could previously run
*		  concurrent full sweeps because state.processing is not visible across parallel executions
*		  (Hubitat loads state at execution start and saves at end). Serializing executions makes the
*		  guard reliable and prevents parallel command bursts to the Hue Bridge.
*		- Change: The disabling-switch check now also guards the single-device path, so a light turned
*		  on while processing is disabled (e.g. during a smoke/CO alarm response) is left untouched.
*		- Refactor: Target calculation (computeTargets) and per-device command logic (applyToDevice)
*		  extracted from eventHandler and shared by the full-sweep and single-device paths so the two
*		  paths cannot drift apart. No behavior change to the full sweep itself.
*	0.99.2 (November 8, 2026)
*		- New: Status section on the main page. Shows whether Day Lights is running, paused (and which
*		  disable switch is blocking it), or in the overnight hold where the 5-minute refresh is off.
*		  Also shows Dynamic Brightness state, the last full update, and how many lit lights disagree
*		  with the current targets.
*		- New: Status Details page. Per-light table comparing each light's reported power, color mode,
*		  color temperature and level against the last calculated targets, with the time and content
*		  of the last command Day Lights sent it. Flags lights stuck in a color (RGB/HS/XY) mode whose
*		  stale CT reading matches the target, which the normal update path skips by design.
*		- New: Recent activity log (last 40 entries, consecutive repeats collapsed) recording disable
*		  switch changes, pauses, resumes, lights turning on, sweeps and errors.
*		- New: Force Resync button. Sends color temperature (and level, when Dynamic Brightness is
*		  active) to every light that is on, even if its readings already match. Refuses while paused.
*		- Fix: An exception on one device (Hue Bridge error, unsupported command) no longer aborts the
*		  sweep. Previously every device after the failing one was skipped for that pass.
*		- Fix: state.processing is now cleared in a finally block, so an exception mid-sweep cannot
*		  leave it set and silently block the 5-minute refresh.
*		- Fix: When a disable switch returns to its allowed state, the manual-dimming check is skipped
*		  for that pass. Levels left by the overriding automation (e.g. the smoke response app) were
*		  otherwise treated as manual dimming and paused Dynamic Brightness until sunrise when
*		  "Disable Dynamic Brightness ... manually changed" is enabled.
*		- Refactor: Disable-switch evaluation split into blockingSwitch() (pure, safe during page
*		  rendering) and processingDisabled() (logs). applyToDevice now reports what it sent.
*		- Text: Replaced em dashes in UI labels and comments.
*		- Fix: Every time setting (brightening/dimming period times, sunrise/sunset overrides, and the
*		  Dynamic Brightness re-enable time) was parsed with the date it was originally picked on,
*		  because Hubitat stores time inputs as full timestamps. From the day after a time was set,
*		  the period overrides compared against a past date: brightening ended immediately and the
*		  afternoon dropped straight to Low Brightness after midday, sunrise/sunset overrides pointed
*		  at an old day, and the re-enable time never scheduled. All eight uses now go through
*		  todayAt(), which wraps Hubitat's timeToday() with the hub time zone.
*		- Fix: The re-enable button handler now compares the button event's own value instead of
*		  re-reading the device's "pushed" attribute, which can lag the event.
*		- Fix: The Force Resync button on Status Details has its own input name instead of sharing
*		  the main page's.
*
* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
*/

import groovy.time.TimeCategory
import groovy.transform.Field

// 0.99.2: Status/diagnostics limits
@Field static final Integer STATUS_LOG_MAX = 40   // entries kept in state.statusLog
@Field static final Integer CT_TOLERANCE_K = 100  // allowed gap between reported and target CT (Hue mired rounding)

definition(
    name: "Day Lights",
    namespace: "daylightsapp",
    author: "OldChicagoPete",
    importURL: "",
    description: "Adjust device color temperature, color, and/or brightness during daylight hours",
    category: "Lighting",
    singleThreaded: true,
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page name: "MainPage", install: true, uninstall: true
    page name: "SunriseSunsetOptions"
    page name: "ColorTemperatureOptions"
    page name: "BrightnessOptions"
    page name: "ModeOptions"
    page name: "DisableOptions"
    page name: "HubVariables"
    page name: "StatusPage"
}

def MainPage(){
    dynamicPage(name: "MainPage") {

        section("<h2>Day Lights</h2>"){
            paragraph "This application adjusts the color temperature, color, and/or the brightness of your lights based on the time of day. By default it runs from sunrise to sunset, adjusting the devices selected below. Many additional options are also available."
            label title: "Enter a name for this instance of the application (optional)"
        }

        section("<h2>Status</h2>") {
            paragraph statusSummaryHtml()
            href(name: "toStatusPage",
                 title: "<b>Status Details</b>",
                 page: "StatusPage",
                 description: "Per-light status and recent activity"
            )
            input name: "refreshNowButton", type: "button", title: "Refresh Lights Now", width: 4
            input name: "forceResyncButton", type: "button", title: "Force Resync", width: 4
            if (state.lastManualRefresh) {
                paragraph "<small>Last manual refresh: ${esc(state.lastManualRefresh)}</small>"
            }
        }

        section("<h2>Select Devices</h2>") {
            paragraph "Individual devices should only be selected for one group"
            input "colorTemperatureDevices", "capability.colorTemperature", title: "<b>Color temperature devices (CT mode)</b>", multiple:true
            input "colorDevices", "capability.colorControl", title: "<b>Color changing devices (RGB mode)</b>", multiple:true
            input "dimmableDevices", "capability.switchLevel", title: "<b>Dimmable devices (requires Dynamic Brightness to be enabled)</b>", multiple:true
            input "otherSwitches", "capability.switch", title: "<b>Other switches to turn on while Day Lights is active</b>", multiple: true
        }

        section("<h2>Dynamic Brightness</h2>") {
            input "dynamicBrightness","bool", title: "Adjust brightness based on the time of day"
            input "resetCT", "bool", title: "Reset the color temperature after manual dimming? (for bulbs (e.g. LIFX) that change color temperature when dimmed)"
        }

        section("<h2>Advanced Options</h2>") {
            href(name: "toSunriseSunsetOptions",
                 title: "<b>Sunrise/Sunset Options</b>",
                 page: "SunriseSunsetOptions",
                 description: "Set Advanced Sunrise/Sunset Options"
            )
            href(name: "toColorTemperatureOptions",
                 title: "<b>Color Temperature Options</b>",
                 page: "ColorTemperatureOptions",
                 description: "Set Advanced Color Temperature Options"
            )
            href(name: "toBrightnessOptions",
                 title: "<b>Dynamic Brightness Options</b>",
                 page: "BrightnessOptions",
                 description: "Set Brightness Options"
            )
            href(name: "toModeOptions",
                 title: "<b>Mode Options</b>",
                 page: "ModeOptions",
                 description: "Set Mode Options"
            )
            href(name: "toDisableOptions",
                 title: "<b>Disable Options</b>",
                 page: "DisableOptions",
                 description: "Set Disable Options"
            )
            href(name: "toHubVariables",
                 title: "<b>Hub Variables</b>",
                 page: "HubVariables",
                 description: "Select Hub Variables"
            )
        }

        section("<h2>Logging</h2>") {
            input(name:"logDescriptionText",
                  type:"bool",
                  title: "Enable descriptionText logging",
                  description: "Logs information regarding application activity (Default: On)",
                  defaultValue: true,
                  required: true,
                  displayDuringSetup: true
            )
            input(name:"logEnhancedDescriptionText",
                  type:"bool",
                  title: "Enable enhanced descriptionText logging",
                  description: "Logs additional information regarding application activity (Default: Off)",
                  defaultValue: false,
                  required: true,
                  displayDuringSetup: true
            )
            input(name:"logDebug",
                  type:"bool",
                  title: "Enable debug logging",
                  description: "Logs detailed data regarding application values (Default: Off)",
                  defaultValue: false,
                  required: true,
                  displayDuringSetup: true
            )
        }
    }
}

def SunriseSunsetOptions() {
    dynamicPage(name: "SunriseSunsetOptions") {

        section("<h2>Sunrise/Sunset Options</h2>") {
            input "useSunOverrides", "bool", title: "<b>Use Sunrise/Sunset Overrides?</b>"
            input "sunriseOverride", "time", title: "Sunrise Override"
            input "sunsetOverride", "time", title: "Sunset Override"
            paragraph "<br>"
            input "useSunOffsets", "bool", title: "<b>Use Sunset/Sunrise Offsets (+/-)?</b>"
            input "sunriseOffset", "number", title: "Sunrise Offset (+/-)", range: "-240:240"
            input "sunsetOffset", "number", title: "Sunset Offset (+/-)", range: "-240:240"
        }
    }
}

def ColorTemperatureOptions() {
    dynamicPage(name: "ColorTemperatureOptions") {

        section("<h2>Color Temperature Options</h2>"){
            paragraph "The color temperature on your devices will be adjusted from warm at sunrise to cold at midday then back to warm at sunset.<br><br>"
            input "useCTOverrides", "bool", title: "<b>Use Color Temperature Overrides?</b>"

            // Seasonal adjustment toggle. When enabled, the single warm/cold inputs are hidden
            // and replaced with winter/summer endpoint inputs that interpolate across the year.
            input name: "useSeasonalCT", type: "bool",
                  title: "<b>Adjust color temperature seasonally?</b>",
                  submitOnChange: true

            if (settings.useSeasonalCT) {
                paragraph "Color temperature shifts smoothly across the year, reaching its warmest at the winter solstice and coolest at the summer solstice."
                input "warmCTWinter", "number", title: "Warm White, Winter (default is 2400)", range: "1000:6500"
                input "warmCTSummer", "number", title: "Warm White, Summer (default is 2700)", range: "1000:6500"
                input "coldCTWinter", "number", title: "Cold White, Winter (default is 5000)", range: "1000:6500"
                input "coldCTSummer", "number", title: "Cold White, Summer (default is 6500)", range: "1000:6500"
                input name: "southernHemisphere", type: "bool",
                      title: "Southern Hemisphere? (reverses the seasonal timing)"
                // Live display of today's computed values so the user can see the current
                // result without checking logs. Recomputes the factor on page render.
                def previewWarmWinter = (settings.warmCTWinter ?: 2400) as int
                def previewWarmSummer = (settings.warmCTSummer ?: 2700) as int
                def previewColdWinter = (settings.coldCTWinter ?: 5000) as int
                def previewColdSummer = (settings.coldCTSummer ?: 6500) as int
                def sf = computeSeasonFactor()
                def previewWarm = Math.round(previewWarmWinter + sf * (previewWarmSummer - previewWarmWinter))
                def previewCold = Math.round(previewColdWinter + sf * (previewColdSummer - previewColdWinter))
                def pct = Math.round(sf * 100)
                paragraph "<b>Today's values:</b> Warm ${previewWarm}K, Cold ${previewCold}K &nbsp;(${pct}% toward summer)"
            }
            else {
                // Range constrained to valid Kelvin values supported by ctToRGB() and typical bulbs
                input "warmCTOverride", "number", title: "Warm White Temperature (default is 2700)", range: "1000:6500"
                input "coldCTOverride", "number", title: "Cold White Temperature (default is 6500)", range: "1000:6500"
            }
            paragraph "<br>"
            input "forceCT", "bool", title: "<b>Force CT mode with Saturation=0? (for bulbs (e.g. LIFX) that do not automatically switch to CT mode when a color temperature is assigned)</b>"
            paragraph "<br>"
            // CT curve shape setting: controls how quickly temperature moves from warm to cold
            input name: "ctCurveShape", type: "enum",
                  title: "<b>Color Temperature Transition Speed</b>",
                  description: "Controls how quickly lights shift from warm to cool across the day. Default is Even Transition.",
                  required: true,
                  defaultValue: "1.0",
                  options: [
                      "1.0": "Even Transition: lights move steadily from warm to cool all day",
                      "1.5": "Mostly Warm: stays warmer through the morning, transitions faster near midday",
                      "2.0": "Stays Warm Longer: warm for most of the day, only reaches peak cool briefly at midday",
                      "3.0": "Very Warm: stays near warmest setting most of the day, sharp transition around midday only"
                  ]
        }
    }
}

def BrightnessOptions() {
    dynamicPage(name: "BrightnessOptions") {

        section("<h2>Dynamic Brightness Options</h2>") {
            paragraph "When Dynamic Brightness is enabled the brightness on your devices will be adjusted from low at sunrise to high at midday then back to low at sunset.<br><br>"
            if (dynamicBrightness) {
                input name: "brightnessProfile", type: "enum",
                      title: "<b>Brightness Profile</b> (default is Gradual)",
                      required: true,
                      defaultValue: "Gradual",
                      options: [
                          "Gradual":      "Gradual: starts dimming up from your Low Brightness setting at sunrise, reaches your High Brightness setting at midday, then dims back down to Low by sunset. Respects both your Low and High Brightness settings.",
                          "Accelerated":  "Accelerated: ramps up faster than Gradual by using a wider internal range, so your High Brightness setting is reached noticeably earlier in the day. The clamp at your High Brightness setting is still respected; it just gets there sooner. Low Brightness override is respected; High Brightness override controls the ceiling.",
                          "Delayed":      "Delayed: stays at minimum brightness (1%) until a fast ramp begins, timed so it reaches full brightness (100%) exactly at midday, then mirrors that fast ramp back down at sunset. Useful for bedrooms where you don't want brightness climbing early in the morning. Ignores your Low and High Brightness overrides."
                      ]
                paragraph "<br>"
            }
            input "useBrightnessOverrides", "bool", title: "<b>Use Brightness Overrides?</b>"
            input "minBrightnessOverride","number", title: "Low Brightness: minimum level during dim periods (default is 1)", range: "1:100"
            input "maxBrightnessOverride","number", title: "High Brightness: maximum level at midday (default is 100)", range: "1:100"
            paragraph "<br>"
            input "usePeriodOverrides", "bool", title: "<b>Use Brightness Period Overrides?</b>"
            paragraph "Times outside of the sunrise/midday and midday/sunset periods will be ignored."
            input "brightenTimeStart", "time", title: "Start Brightening At"
            input "brightenTimeEnd", "time", title: "End Brightening At"
            input "dimTimeStart", "time", title: "Start Dimming At"
            input "dimTimeEnd", "time", title: "End Dimming At"
        }
    }
}

def ModeOptions() {
    dynamicPage(name: "ModeOptions") {

        section("<h2>Mode Options</h2>") {
            input "useModeOverrides", "bool", title: "<b>Use Mode Overrides?</b>"
        }

        section("<b>Override 1</b>") {
            input "mode1Override", "mode", title: "Mode"
            input "mode1OverrideColorTemperature","number", title: "Color temperature"
            input "mode1OverrideValue","number", title: "Brightness"
        }

        section("<b>Override 2</b>") {
            input "mode2Override", "mode", title: "Mode"
            input "mode2OverrideColorTemperature","number", title: "Color temperature"
            input "mode2OverrideValue","number", title: "Brightness"
        }

        section("<b>Override 3</b>") {
            input "mode3Override", "mode", title: "Mode"
            input "mode3OverrideColorTemperature","number", title: "Color temperature"
            input "mode3OverrideValue","number", title: "Brightness"
        }

        section("<b>Override 4</b>") {
            input "mode4Override", "mode", title: "Mode"
            input "mode4OverrideColorTemperature","number", title: "Color temperature"
            input "mode4OverrideValue","number", title: "Brightness"
        }

        section("<b>Override 5</b>") {
            input "mode5Override", "mode", title: "Mode"
            input "mode5OverrideColorTemperature","number", title: "Color temperature"
            input "mode5OverrideValue","number", title: "Brightness"
        }

        section("<b>Override 6</b>") {
            input "mode6Override", "mode", title: "Mode"
            input "mode6OverrideColorTemperature","number", title: "Color temperature"
            input "mode6OverrideValue","number", title: "Brightness"
        }

        section("<b>Override 7</b>") {
            input "mode7Override", "mode", title: "Mode"
            input "mode7OverrideColorTemperature","number", title: "Color temperature"
            input "mode7OverrideValue","number", title: "Brightness"
        }

        section("<b>Override 8</b>") {
            input "mode8Override", "mode", title: "Mode"
            input "mode8OverrideColorTemperature","number", title: "Color temperature"
            input "mode8OverrideValue","number", title: "Brightness"
        }

        section("<b>Override 9</b>") {
            input "mode9Override", "mode", title: "Mode"
            input "mode9OverrideColorTemperature","number", title: "Color temperature"
            input "mode9OverrideValue","number", title: "Brightness"
        }

        section("<b>Override 10</b>") {
            input "mode10Override", "mode", title: "Mode"
            input "mode10OverrideColorTemperature","number", title: "Color temperature"
            input "mode10OverrideValue","number", title: "Brightness"
        }
    }
}

def DisableOptions(){
    dynamicPage(name: "DisableOptions") {

        section("<h2>Disable Day Lights</h2>") {
            input "disablingSwitches","capability.switch", title: "Select switches that will disable Day Lights", multiple:true
            input "disableWhenSwitchOff","bool", title: "Disable when off (normally disables when switch is on)"
        }

        section("<h2>Disable Dynamic Brightness</h2>") {
            input "disableWhenDimmed", "bool", title: "<b>Disable Dynamic Brightness for the day when the brightness on a selected device is manually changed?</b>"
            paragraph "Dynamic Brightness will be re-enabled automatically at the next sunrise"
            input "enableButton", "capability.pushableButton", title: "Button to re-enable Dynamic Brightness and/or refresh light values", width: 6, submitOnChange: true
            if (enableButton) {
                // Guard against button device not yet reporting numberOfButtons attribute
                def numButtons = enableButton.currentValue("numberOfButtons")
                if (numButtons != null && numButtons > 0) {
                    def buttonrange = 1..numButtons
                    input "enableButtonNumber","enum", title: "Button Number", width: 6, required: true, options:buttonrange
                } else {
                    paragraph "Button number options will appear once the device reports how many buttons it has."
                }
            }
            // minBrightnessOverride is defined in BrightnessOptions; removed duplicate here
            input "reenableDimmingTime", "time", title: "Add an additional time to re-enable Dynamic Brightness"
            paragraph "If currently disabled, Dynamic Brightness will be re-enabled by clicking the Done button"
        }
    }
}

def HubVariables(){
    dynamicPage(name: "HubVariables") {

        section("<h2>Hub Variables</h2>") {
            paragraph "Optionally specify hub variables to save the current values calculated by Day Lights<br><br>"
            def intglobalvars = getGlobalVarsByType("integer").keySet()
            input "hvCT", "enum", title: "Select variable to save the current calculated color temperature", options:intglobalvars
            input "hvLevel","enum", title: "Select variable to save the current calculated brightness level", options:intglobalvars
        }
    }
}

// 0.99.2: Read-only diagnostics page. Rendering it never sends device commands.
def StatusPage() {
    dynamicPage(name: "StatusPage") {

        section("<h2>Status</h2>") {
            paragraph statusSummaryHtml()
            input name: "refreshStatusButton", type: "button", title: "Reload Status", width: 3
            input name: "forceResyncButton2", type: "button", title: "Force Resync", width: 3
        }

        section("<h2>Lights</h2>") {
            def t = state.lastTargets
            paragraph "Each light's reported state compared with the last targets Day Lights calculated" +
                      (t?.t ? " (${esc(t.t)}: ${t.ct}K, ${t.bright}%)" : "") +
                      ". Lights that need attention are listed first."
            paragraph deviceTableHtml()
        }

        section("<h2>Recent Activity</h2>") {
            paragraph statusLogHtml()
            input name: "clearStatusLogButton", type: "button", title: "Clear Activity", width: 3
        }

        section("<h2>Reading This Page</h2>", hideable: true, hidden: true) {
            paragraph "<b>Paused</b>: a disable switch is in its blocking state. Day Lights sends nothing until every disable switch is back in its allowed state, then it updates every light that is on."
            paragraph "<b>Overnight hold</b>: outside the sunrise to sunset window the 5-minute refresh is off. Lights are only adjusted when one turns on, the mode changes, a disable switch changes, or you press a button here. Anything that changes a light after that is not corrected until one of those happens."
            paragraph "<b>In a color mode</b>: the light was last set by hue/saturation or color (for example by an alarm or scene). Its CT reading can still show the old value. When that old value matches the target, the normal update skips the light because it looks correct. Force Resync sends the values regardless."
            paragraph "<b>Last sent by Day Lights</b>: the last command this app sent to the light and when. If a light is wrong and this time is before the problem started, Day Lights never tried to correct it."
            paragraph "<b>Force Resync</b>: sends color temperature, and level when Dynamic Brightness is active, to every light that is on. It is a manual tool and is refused while paused, so it cannot override an active alarm."
        }
    }
}

def installed() {
    unsubscribe()
    unschedule()
    removeAllInUseGlobalVar()
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()
    removeAllInUseGlobalVar()
    initialize()
}

def uninstalled() {
    unsubscribe()
    unschedule()
    removeAllInUseGlobalVar()
}

private logDescriptionText(debugText) {

    if (settings.logDescriptionText) {
        log.info "${app.name} (${app.getLabel()}): ${debugText}"
    }
}

private logEnhancedDescriptionText(debugText) {

    if (settings.logEnhancedDescriptionText) {
        log.info "${app.name} (${app.getLabel()}): ${debugText}"
    }
}

private logDebug(debugText) {

    if (settings.logDebug) {
        log.debug "${app.name} (${app.getLabel()}): ${debugText}"
    }
}

private def initialize() {
    state.scheduleActive = false
    for (device in otherSwitches) {
        device.off()
    }
    state.processing = false
    state.disabledFromDimmer = false
    state.lastAssignedBrightness = 0

    logDebug("initialize() with settings: ${settings}")

    subscribe(location, "sunriseTime", scheduleNextWakeup)

    if (colorTemperatureDevices) {
        subscribe(colorTemperatureDevices, "switch.on", eventDeviceOn)
        if (resetCT) {
            subscribe(colorTemperatureDevices, "level", eventLevel)
        }
    }
    if (colorDevices) {
        subscribe(colorDevices, "switch.on", eventDeviceOn)
    }
    if (dimmableDevices) {
        subscribe(dimmableDevices, "switch.on", eventDeviceOn)
    }
    if (settings.useModeOverrides) {
        subscribe(location, "mode", eventMode)
    }
    if (disablingSwitches) {
        subscribe(disablingSwitches, "switch", eventSwitch)
    }
    if (enableButton) {
        subscribe(enableButton, "pushed", eventEnable)
    }

    // Use state variables to track hub variable names rather than writing to settings references
    state.hvCT    = (settings.hvCT    != null && settings.hvCT    != "") ? settings.hvCT    : ""
    state.hvLevel = (settings.hvLevel != null && settings.hvLevel != "") ? settings.hvLevel : ""

    if (state.hvCT    != "") { addInUseGlobalVar(state.hvCT) }
    if (state.hvLevel != "") { addInUseGlobalVar(state.hvLevel) }

    // 0.99.2: Drop last-command records for devices no longer selected
    Set currentIds = allDeviceRows().collect { it[0].id.toString() } as Set
    state.lastCommanded = ((state.lastCommanded ?: [:]) as Map).findAll { k, v -> k in currentIds }
    addStatusEvent("Settings saved")

    scheduleNextWakeup()
    updateSeasonFactor()
    state.bypassManualOverrideCheck = true
    eventHandler("Initialize")
}

def scheduleNextWakeup(evt) {
    def sunriseTime
    def events

    if (settings.useSunOverrides && settings.sunriseOverride != null && settings.sunriseOverride != "") {
        sunriseTime = todayAt(settings.sunriseOverride)
        if (sunriseTime < new Date()) {
            sunriseTime = sunriseTime + 1
        }
        logDebug("Sunrise Override is ${sunriseTime}")
    }
    else {
        if (evt != null) {
            sunriseTime = toDateTime(evt.value)
        }
        else {
            events = getLocationEventsSince("sunriseTime", new Date() - 2, [max: 1])
            if (events && events.size() > 0) {
                sunriseTime = toDateTime(events[0].value)
            }
            else {
                // No sunrise event history (e.g. after hub database reset). Fall back to calculated sunrise.
                logDebug("No sunriseTime event history found, falling back to getSunriseAndSunset()")
                sunriseTime = getSunriseAndSunset().sunrise
            }
        }
        logDebug("Next system sunrise time is ${sunriseTime}")
    }
    if (settings.useSunOffsets && settings.sunriseOffset != null && settings.sunriseOffset != "") {
        int offset = settings.sunriseOffset
        use(TimeCategory) {
            sunriseTime = sunriseTime + offset.minutes
        }
        logDebug("Sunrise offset to ${sunriseTime}")
    }

    schedule(sunriseTime, eventWakeup)
}

def eventUpdate(evt) {

    if (!state.processing) {
        eventHandler("Scheduled update")
    }
    else {
        addStatusEvent("Scheduled update skipped: processing flag still set")
    }
}

def eventDeviceOn(evt) {

    // Debounce: Hue bridge can report a transient switch.on while a bulb is mid-transition
    // (e.g. when a button rule turns a bulb off, or a grouped bulb re-reports state). Acting
    // on that transient event causes the app to re-assert (turn back on) a light the user just
    // turned off. We wait a short settle period, then re-check the device's actual switch state
    // before processing. If it settled to "off", we skip it entirely.
    //
    // evt.device is the specific device that reported the event. We schedule a one-shot check
    // keyed to that device's id so simultaneous events on different devices don't clobber each other.
    if (evt?.device != null) {
        runInMillis(750, "processDeviceOnSettled", [overwrite: false, data: [deviceId: evt.device.id]])
    }
    else {
        // No device context (defensive fallback): process immediately as before
        state.bypassManualOverrideCheck = true
        eventHandler("Device On")
    }
}

def processDeviceOnSettled(data) {

    // Re-check the device's current switch state after the settle delay. If it is no longer "on",
    // the original event was a transient bounce and we do nothing. This prevents re-asserting a
    // light the user intentionally turned off.
    def deviceId = data?.deviceId
    def device = null
    def deviceType = null
    for (d in colorTemperatureDevices) { if (d.id == deviceId) { device = d; deviceType = "ct"; break } }
    if (device == null) { for (d in colorDevices)    { if (d.id == deviceId) { device = d; deviceType = "color";  break } } }
    if (device == null) { for (d in dimmableDevices) { if (d.id == deviceId) { device = d; deviceType = "dimmer"; break } } }

    if (device == null) {
        // Defensive: event from a device no longer in any selection list. Preserve the
        // pre-0.99.1 behavior (full update) since there is no per-device context.
        logDebug("Device id ${deviceId} not found in selections, falling back to full update")
        state.bypassManualOverrideCheck = true
        eventHandler("Device On")
        return
    }
    if (device.currentValue("switch") != "on") {
        logDescriptionText("Ignoring transient on event from ${device} (settled to off)")
        addStatusEvent("Ignored brief on from ${device.displayName} (settled off)")
        return
    }

    // 0.99.1: Update only the device that turned on. Previously this ran a full sweep of
    // every selected device, so each switch.on event (and a Hue Group plus its member
    // bulbs each emit one) re-pushed commands to the entire house. The 5-minute cron
    // (eventUpdate) still refreshes all devices.
    if (processingDisabled("Turned on: ${device.displayName}")) {
        return
    }
    logDescriptionText("Processing(Device On: ${device.displayName})")
    String result
    try {
        result = applyToDevice(device, deviceType, computeTargets())
    }
    catch (Exception e) {
        result = "error: ${e.message}"
        log.error "${app.label}: failed to update ${device.displayName}: ${e.message}"
    }
    recordCommand(device, result)
    addStatusEvent("Turned on: ${device.displayName} (${result})")
}

// Handles button-type inputs on the app pages. Currently the "Refresh Lights Now" button on
// the main page, which forces an immediate recalculation and re-push to all selected lights.
def appButtonHandler(buttonName) {

    switch (buttonName) {
        case "refreshNowButton":
            logDescriptionText("Manual refresh requested")
            updateSeasonFactor()
            state.bypassManualOverrideCheck = true
            state.lastManualRefresh = nowStr()
            eventHandler("Manual refresh")
            break
        case "forceResyncButton":
        case "forceResyncButton2":
            // 0.99.2: Same as a manual refresh, but commands are sent even when a light's
            // readings already match the targets. Still refused while a disable switch blocks.
            logDescriptionText("Force resync requested")
            updateSeasonFactor()
            state.bypassManualOverrideCheck = true
            state.lastManualRefresh = nowStr()
            eventHandler("Force resync", true)
            break
        case "clearStatusLogButton":
            state.statusLog = []
            break
        default:
            // refreshStatusButton needs no action: any button press re-renders the page.
            break
    }
}

def eventMode(evt) {

    eventHandler("Mode changed to ${location.mode}")
}

def eventSwitch(evt) {

    // 0.99.2: Record the transition and, when the switch returns to its allowed state,
    // skip the manual-dimming check for this pass. The lights were just under another
    // automation's control (e.g. the smoke response app), so their levels say nothing
    // about a person dimming them.
    boolean resuming = (evt.value == allowedSwitchValue())
    addStatusEvent("${evt.displayName} turned ${evt.value}" + (resuming ? "" : " (blocks Day Lights)"))
    if (resuming) {
        state.bypassManualOverrideCheck = true
    }
    eventHandler(resuming ? "Resume after ${evt.displayName}" : "Pause by ${evt.displayName}")
}

def eventEnable(evt) {

    if (evt.value?.toString() == settings.enableButtonNumber?.toString()) {
        state.disabledFromDimmer = false
        unschedule(disableDimmerOverride)
        state.bypassManualOverrideCheck = true
        eventHandler("Re-enable Dynamic Brightness")
    }
}

def eventLevel(evt) {

    eventHandler("Reset Color Temperature")
}

def eventWakeup(evt) {

    if (!state.scheduleActive) {
        state.disabledFromDimmer = false
        state.bypassManualOverrideCheck = true
        // Recompute the cached seasonal factor once per day at wakeup
        updateSeasonFactor()
        eventHandler("Sunrise wakeup")
    }
}

// Pure calculation: returns a 0.0-1.0 factor representing where today falls in the seasonal cycle.
// 0.0 = winter solstice (warmest light), 1.0 = summer solstice (coolest light).
// Uses a cosine curve so the change eases near the solstices and is fastest near
// the equinoxes, matching how daylight length actually varies through the year.
// This function does not write state, so it is safe to call during page rendering.
def computeSeasonFactor() {

    def cal = java.util.Calendar.getInstance()
    int dayOfYear = cal.get(java.util.Calendar.DAY_OF_YEAR)
    int daysInYear = cal.getActualMaximum(java.util.Calendar.DAY_OF_YEAR)  // 365 or 366

    // Winter solstice is ~Dec 21, which is day ~355. Days since winter solstice:
    int winterSolsticeDay = 355
    int daysSinceSolstice = dayOfYear - winterSolsticeDay
    if (daysSinceSolstice < 0) {
        daysSinceSolstice += daysInYear
    }

    // Cosine interpolation: 0 at winter solstice, peaks at 1.0 a half-year later (summer solstice)
    double phase = (daysSinceSolstice / (double) daysInYear) * 2.0 * Math.PI
    double factor = (1.0 - Math.cos(phase)) / 2.0

    // Southern hemisphere experiences opposite seasons
    if (settings.southernHemisphere) {
        factor = 1.0 - factor
    }

    return factor
}

// Refreshes the cached season factor in state. Called once daily at wakeup and at initialize.
def updateSeasonFactor() {

    double factor = computeSeasonFactor()
    state.seasonFactor = factor
    logDebug("Season factor updated: ${factor}")
}

def disableDimmerOverride(evt) {

    state.disabledFromDimmer = false
    unschedule(disableDimmerOverride)
    state.bypassManualOverrideCheck = true
    eventHandler("Dynamic brightness re-enabled at scheduled time")
}

def eventHandler(evt, boolean force = false) {

    state.processing = true
    try {
        if (processingDisabled("${evt}")) {
            return
        }

        if (state.disabledFromDimmer) {
            logDescriptionText("Processing(${evt}) (Dynamic Brightness disabled)")
        }
        else {
            logDescriptionText("Processing(${evt})")
        }

        if (state.bypassManualOverrideCheck) {
            state.bypassManualOverrideCheck = false
        }
        else if (settings.disableWhenDimmed && !state.disabledFromDimmer) {
            for (device in colorTemperatureDevices) {
                if (device.currentValue("switch") == "on" && device.currentValue("level") != null && device.currentValue("level") != state.lastAssignedBrightness) {
                    state.disabledFromDimmer = true
                    state.dimmerPausedBy = "${device.displayName} at ${device.currentValue('level')}%, expected ${state.lastAssignedBrightness}%".toString()
                    logDescriptionText("Dynamic Brightness disabled by ${device} level=${device.currentValue("level")} but was last assigned=${state.lastAssignedBrightness}")
                }
            }
            for (device in dimmableDevices) {
                if (device.currentValue("switch") == "on" && device.currentValue("level") != null && device.currentValue("level") != state.lastAssignedBrightness) {
                    state.disabledFromDimmer = true
                    state.dimmerPausedBy = "${device.displayName} at ${device.currentValue('level')}%, expected ${state.lastAssignedBrightness}%".toString()
                    logDescriptionText("Dynamic Brightness disabled by ${device} level=${device.currentValue("level")} but was last assigned=${state.lastAssignedBrightness}")
                }
            }
            if (state.disabledFromDimmer) {
                def disabletext = "Dynamic Brightness disabled until reset or next sunrise"
                if (settings.reenableDimmingTime != null && settings.reenableDimmingTime != "") {
                    def scheduleTime = todayAt(settings.reenableDimmingTime)
                    if (scheduleTime > new Date()) {
                        schedule(scheduleTime, disableDimmerOverride)
                        disabletext = "Dynamic Brightness disabled until ${scheduleTime}"
                    }
                }
                logDescriptionText(disabletext)
                addStatusEvent("Dynamic brightness paused by manual dimming: ${state.dimmerPausedBy}")
            }
        }

        def t = computeTargets()

        // 0.99.2: Each device is isolated in its own try/catch. Previously an exception on one
        // device (Hue Bridge error, unsupported command) aborted the loop and every device after
        // it was skipped for the whole pass.
        int total = 0, lit = 0, sent = 0, errors = 0
        for (row in allDeviceRows()) {
            def device = row[0]
            String deviceType = row[1]
            total++
            String result
            try {
                result = applyToDevice(device, deviceType, t, force)
            }
            catch (Exception e) {
                errors++
                result = "error: ${e.message}".toString()
                log.error "${app.label}: failed to update ${device.displayName}: ${e.message}"
                addStatusEvent("Error updating ${device.displayName}: ${e.message}")
            }
            if (result != "off") { lit++ }
            if (result != "off" && result != "in sync" && !result.startsWith("error")) { sent++ }
            recordCommand(device, result)
        }

        state.lastRun = [t: nowStr(), reason: "${evt}".toString(), ct: t.ct, bright: t.bright,
                         total: total, lit: lit, sent: sent, errors: errors, force: force]

        // Routine 5-minute updates are logged with a fixed message so consecutive ones collapse
        // into a single counted entry instead of pushing the interesting events out of the log.
        if ("${evt}" == "Scheduled update" && errors == 0) {
            addStatusEvent("Scheduled update")
        }
        else {
            addStatusEvent("${evt}: target ${t.ct}K/${t.bright}%, ${lit} of ${total} on, ${sent} updated" +
                           (errors ? ", ${errors} errors" : ""))
        }
    }
    finally {
        // 0.99.2: Always clear the flag, even if something above throws. A stuck flag makes
        // eventUpdate skip every scheduled refresh until the next initialize().
        state.processing = false
    }
}

// 0.99.2: Returns the first disabling switch currently in its blocking state, or null.
// Pure: no logging or state writes, so it is safe to call while rendering a page.
private blockingSwitch() {

    for (disableSwitch in disablingSwitches) {
        if (switchBlocks(disableSwitch)) {
            return disableSwitch
        }
    }
    return null
}

private boolean switchBlocks(disableSwitch) {

    def v = disableSwitch.currentValue("switch")
    return (v == "on" && !settings.disableWhenSwitchOff) || (v == "off" && settings.disableWhenSwitchOff)
}

// The switch state that lets Day Lights run.
private String allowedSwitchValue() {

    return settings.disableWhenSwitchOff ? "on" : "off"
}

// 0.99.1: Returns true (and logs) when any disabling switch currently blocks processing.
// Shared by the full-sweep path (eventHandler) and the single-device path
// (processDeviceOnSettled) so both respect the same disable conditions.
// 0.99.2: Also records the pause in the status log, tagged with what triggered the check.
private boolean processingDisabled(String reason = null) {

    def blocker = blockingSwitch()
    if (blocker) {
        logDescriptionText("Processing disabled by ${blocker}")
        addStatusEvent((reason ? "${reason}: " : "") + "paused, ${blocker.displayName} is ${blocker.currentValue('switch')}")
        return true
    }
    return false
}

// 0.99.1: Computes the current target values once and performs the shared side effects
// (state.lastAssignedBrightness, hub variable updates, value logging). Extracted from
// eventHandler so the full-sweep and single-device paths cannot drift apart.
// 0.99.2: Also stores the targets in state.lastTargets for the status pages.
private Map computeTargets() {

    def nv = getNewValues()
    def ct = nv.colorTemp
    def bright = nv.brightness
    def rgb = ctToRGB(ct)
    def hex = rgbToHex(rgb).toUpperCase()
    def hsv = rgbToHSV(rgb)
    if (settings.dynamicBrightness && !state.disabledFromDimmer) {
        hsv.v = bright
    }
    def color = [hex: hex, hue: hsv.h, saturation: hsv.s, level: hsv.v]
    state.lastAssignedBrightness = bright
    state.lastTargets = [ct: ct, bright: bright, hex: hex, t: nowStr()]
    if (state.hvCT != "") {
        setGlobalVar(state.hvCT, ct)
    }
    if (state.hvLevel != "") {
        setGlobalVar(state.hvLevel, bright)
    }

    logEnhancedDescriptionText("CT=${ct}K, Level=${bright}%, Color=${hex}, Hue=${hsv.h}, Saturation=${hsv.s}, Value=${hsv.v}")
    logDebug("Color Temperature: ${ct}")
    logDebug("Brightness: ${bright}")
    logDebug("Color: ${color}")

    return [ct: ct, bright: bright, hex: hex, color: color]
}

// 0.99.1: Applies the computed targets to a single device. deviceType is "ct", "color",
// or "dimmer" and selects the command set. Devices that are not on are left untouched.
// 0.99.2: Returns a short description of what was sent ("off", "in sync", or the commands).
// When force is true, commands are sent even if the device's readings already match.
private String applyToDevice(device, String deviceType, Map t, boolean force = false) {

    if (device.currentValue("switch") != "on") {
        return "off"
    }
    boolean levelManaged = settings.dynamicBrightness && !state.disabledFromDimmer
    List sent = []

    if (deviceType == "ct") {
        // Only attempt setSaturation on devices that actually support the command.
        // White-only / tunable-white bulbs (e.g. some Hue bulbs) expose colorTemperature
        // but not colorControl, so calling setSaturation throws IllegalArgumentException.
        if (forceCT && device.hasCommand("setSaturation") && device.currentValue("colorMode") != "CT") {
            logEnhancedDescriptionText("Attempting to force CT mode")
            device.setSaturation(0)
            sent << "saturation 0"
        }
        if (force || device.currentValue("colorTemperature") != t.ct) {
            device.setColorTemperature(t.ct)
            sent << "CT ${t.ct}K"
        }
        if (levelManaged && (force || device.currentValue("level") != t.bright)) {
            device.setLevel(t.bright)
            sent << "level ${t.bright}%"
        }
    }
    else if (deviceType == "color") {
        if (force || device.currentValue("color") != t.hex || (levelManaged && device.currentValue("level") != t.bright)) {
            device.setColor(t.color)
            sent << "color ${t.hex}"
        }
    }
    else if (deviceType == "dimmer") {
        if (levelManaged && (force || device.currentValue("level") != t.bright)) {
            device.setLevel(t.bright)
            sent << "level ${t.bright}%"
        }
    }
    return sent ? sent.join(", ") : "in sync"
}

// ---------------------------------------------------------------------------
// 0.99.2: Status helpers
// Everything below only reads device attributes and app state, except
// addStatusEvent() and recordCommand() which write app state. None of them send
// device commands, so the page-rendering helpers are safe to call from a page.
// ---------------------------------------------------------------------------

// Every selected device paired with the command set used for it.
private List allDeviceRows() {

    List rows = []
    colorTemperatureDevices?.each { rows << [it, "ct"] }
    colorDevices?.each { rows << [it, "color"] }
    dimmableDevices?.each { rows << [it, "dimmer"] }
    return rows
}

// 0.99.2: Hubitat stores a time input as a full timestamp that keeps the date it was
// picked (e.g. 2026-06-20T21:45:00.000-0600). Parsing it directly yields that old date,
// so every comparison against today was wrong. timeToday() moves it to today in the
// hub's time zone, which also handles the daylight saving offset change.
private Date todayAt(String timeSetting) {

    return timeToday(timeSetting, location.timeZone)
}

private String nowStr() {

    def tz = location.timeZone ?: TimeZone.getDefault()
    return new Date().format("MM-dd HH:mm:ss", tz)
}

// HTML-escape text from device labels and error messages before putting it in a page.
private String esc(text) {

    if (text == null) {
        return ""
    }
    return text.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")
}

// Adds an entry to the activity log, newest first. A message identical to the newest
// entry increments that entry's count and time instead of adding a new line.
private void addStatusEvent(String msg) {

    List entries = (state.statusLog ?: []) as List
    if (entries && entries[0]?.m == msg) {
        entries[0].n = ((entries[0].n ?: 1) as Integer) + 1
        entries[0].t = nowStr()
    }
    else {
        entries.add(0, [t: nowStr(), m: msg, n: 1])
    }
    if (entries.size() > STATUS_LOG_MAX) {
        entries = entries.take(STATUS_LOG_MAX)
    }
    state.statusLog = entries
}

// Remembers the last command sent to a device (or the error), keyed by device id.
private void recordCommand(device, String result) {

    if (result == null || result == "off" || result == "in sync") {
        return
    }
    Map sentMap = (state.lastCommanded ?: [:]) as Map
    sentMap[device.id.toString()] = "${nowStr()}: ${result}".toString()
    state.lastCommanded = sentMap
}

// Compares one device's reported state with the last calculated targets.
// Returns [power, mode, now, target, verdict, sev] where sev is "warn", "ok" or "info".
private Map deviceStatus(device, String deviceType, Map t) {

    def power = device.currentValue("switch")
    def mode  = device.currentValue("colorMode")
    def ct    = device.currentValue("colorTemperature")
    def lvl   = device.currentValue("level")
    boolean levelManaged = settings.dynamicBrightness && !state.disabledFromDimmer

    List nowParts = []
    if (deviceType == "color" && device.currentValue("color") != null) { nowParts << device.currentValue("color").toString() }
    if (deviceType == "ct" && ct != null) { nowParts << "${ct}K" }
    if (lvl != null) { nowParts << "${lvl}%" }

    String targetTxt = ""
    if (t) {
        if (deviceType == "ct")     { targetTxt = "${t.ct}K" + (levelManaged ? ", ${t.bright}%" : "") }
        if (deviceType == "color")  { targetTxt = "${t.hex}" + (levelManaged ? ", ${t.bright}%" : "") }
        if (deviceType == "dimmer") { targetTxt = levelManaged ? "${t.bright}%" : "not managed" }
    }
    Map r = [power: power ?: "unknown", mode: mode ?: "", now: nowParts.join(", "), target: targetTxt]

    if (power != "on") {
        r.verdict = "Off. Adjusted when it turns on."
        r.sev = "info"
        return r
    }
    if (!t) {
        r.verdict = "No targets yet. Press Refresh Lights Now."
        r.sev = "info"
        return r
    }

    List problems = []
    if (deviceType == "ct") {
        boolean ctMatches = (ct != null && Math.abs((ct as Integer) - (t.ct as Integer)) <= CT_TOLERANCE_K)
        if (mode != null && mode != "CT") {
            problems << "In ${mode} mode, not following color temperature." +
                        (ctMatches ? " Its CT reading matches the target, so normal updates skip it." : "")
        }
        else if (ct != null && !ctMatches) {
            problems << "CT ${ct}K, target ${t.ct}K."
        }
    }
    else if (deviceType == "color") {
        def hex = device.currentValue("color")
        if (hex != null && hex.toString().toUpperCase() != t.hex) {
            problems << "Color ${hex}, target ${t.hex}."
        }
    }
    if (levelManaged && lvl != null && (lvl as Integer) != (t.bright as Integer)) {
        problems << "Level ${lvl}%, target ${t.bright}%."
    }

    if (problems) {
        r.verdict = problems.join(" ")
        r.sev = "warn"
    }
    else {
        r.verdict = "In sync"
        r.sev = "ok"
    }
    return r
}

// Short summary shown on the main page and at the top of the Status Details page.
private String statusSummaryHtml() {

    List lines = []

    def blocker = blockingSwitch()
    if (blocker) {
        lines << "<b style='color:#b91c1c'>Paused</b>: ${esc(blocker.displayName)} is ${esc(blocker.currentValue('switch'))}. " +
                 "Day Lights runs only while every disable switch is ${allowedSwitchValue()}."
    }
    else if (state.scheduleActive) {
        lines << "<b style='color:#15803d'>Running</b>: inside the daylight window, lights refresh every 5 minutes."
    }
    else {
        lines << "<b style='color:#b45309'>Overnight hold</b>: outside the daylight window, so the 5-minute refresh is off until sunrise. " +
                 "Lights are only adjusted when one turns on, the mode changes, a disable switch changes, or you press a button below."
    }

    if (disablingSwitches) {
        String sw = disablingSwitches.collect { s ->
            "${esc(s.displayName)}: ${esc(s.currentValue('switch'))}" + (switchBlocks(s) ? " <b style='color:#b91c1c'>(blocking)</b>" : "")
        }.join(" &nbsp;|&nbsp; ")
        lines << "Disable switches (allowed state: ${allowedSwitchValue()}): ${sw}"
    }

    if (!settings.dynamicBrightness) {
        lines << "Dynamic brightness: off (color temperature only)."
    }
    else if (state.disabledFromDimmer) {
        lines << "<b style='color:#b45309'>Dynamic brightness paused</b> by manual dimming" +
                 (state.dimmerPausedBy ? " (${esc(state.dimmerPausedBy)})" : "") +
                 ". Resumes at sunrise, the re-enable time, or the re-enable button."
    }
    else {
        lines << "Dynamic brightness: active."
    }

    Map lr = state.lastRun
    if (lr) {
        lines << "Last full update: ${esc(lr.t)} (${esc(lr.reason)}). Target ${lr.ct}K, ${lr.bright}%. " +
                 "${lr.lit} of ${lr.total} lights on, ${lr.sent} updated" +
                 ((lr.errors ?: 0) > 0 ? ", <b style='color:#b91c1c'>${lr.errors} errors</b>" : "") + "."
    }
    else {
        lines << "No full update recorded yet."
    }

    int lit = 0, attention = 0
    allDeviceRows().each { row ->
        Map s = deviceStatus(row[0], row[1], state.lastTargets)
        if (s.power == "on") { lit++ }
        if (s.sev == "warn") { attention++ }
    }
    if (attention > 0) {
        lines << "<b style='color:#b45309'>${attention} of ${lit} lit lights differ from the targets.</b> See Status Details."
    }
    else if (lit > 0) {
        lines << "All ${lit} lit lights match the targets."
    }

    if (state.processing) {
        lines << "<b style='color:#b91c1c'>Processing flag is set.</b> If this is still shown after Reload Status, scheduled updates are being skipped. Click Done on the main page to reset it."
    }

    return lines.join("<br>")
}

// Per-light table for the Status Details page, lights that need attention first.
private String deviceTableHtml() {

    List rows = allDeviceRows()
    if (!rows) {
        return "No lights selected."
    }
    Map t = state.lastTargets
    Map sentMap = (state.lastCommanded ?: [:]) as Map
    Map rank = [warn: 0, ok: 1, info: 2]

    List data = rows.collect { row -> [device: row[0], s: deviceStatus(row[0], row[1], t)] }
    data.sort { a, b -> rank[a.s.sev] <=> rank[b.s.sev] ?: a.device.displayName <=> b.device.displayName }

    StringBuilder sb = new StringBuilder()
    sb << "<table style='width:100%;border-collapse:collapse;font-size:13px'>"
    sb << "<tr style='text-align:left;border-bottom:2px solid #ccc'>"
    sb << "<th>Light</th><th>Power</th><th>Mode</th><th>Now</th><th>Target</th><th>Last sent by Day Lights</th><th>Status</th></tr>"
    data.each { d ->
        Map s = d.s
        String color = (s.sev == "warn") ? "#b45309" : ((s.sev == "ok") ? "#15803d" : "#6b7280")
        sb << "<tr style='border-bottom:1px solid #e5e7eb;vertical-align:top'>"
        sb << "<td>${esc(d.device.displayName)}</td>"
        sb << "<td>${esc(s.power)}</td>"
        sb << "<td>${esc(s.mode)}</td>"
        sb << "<td>${esc(s.now)}</td>"
        sb << "<td>${esc(s.target)}</td>"
        sb << "<td>${esc(sentMap[d.device.id.toString()] ?: 'never')}</td>"
        sb << "<td style='color:${color}'>${esc(s.verdict)}</td>"
        sb << "</tr>"
    }
    sb << "</table>"
    return sb.toString()
}

private String statusLogHtml() {

    List entries = (state.statusLog ?: []) as List
    if (!entries) {
        return "No activity recorded yet."
    }
    return entries.collect { e ->
        "<code>${esc(e.t)}</code> ${esc(e.m)}" + (((e.n ?: 1) as Integer) > 1 ? " <small>(x${e.n})</small>" : "")
    }.join("<br>")
}

def getNewValues() {
    def sunriseTime = getSunriseTime()
    def sunsetTime = getSunsetTime()
    def midTime = sunriseTime.time + ((sunsetTime.time - sunriseTime.time) / 2)
    def currentTime = now()

    def workTime
    def brightenStart = sunriseTime.time
    def brightenEnd = midTime
    def dimStart = midTime
    def dimEnd = sunsetTime.time
    if (settings.usePeriodOverrides) {
        if (settings.brightenTimeStart != null && settings.brightenTimeStart != "") {
            workTime = todayAt(settings.brightenTimeStart)
            if (workTime > sunriseTime) {
                brightenStart = workTime.time
            }
        }
        if (settings.brightenTimeEnd != null && settings.brightenTimeEnd != "") {
            workTime = todayAt(settings.brightenTimeEnd)
            if (workTime.time < midTime) {
                brightenEnd = workTime.time
            }
        }
        if (settings.dimTimeStart != null && settings.dimTimeStart != "") {
            workTime = todayAt(settings.dimTimeStart)
            if (workTime.time > midTime) {
                dimStart = workTime.time
            }
        }
        if (settings.dimTimeEnd != null && settings.dimTimeEnd != "") {
            workTime = todayAt(settings.dimTimeEnd)
            if (workTime < sunsetTime) {
                dimEnd = workTime.time
            }
        }
    }

    logDebug("sunriseTime: ${sunriseTime}")
    logDebug("sunsetTime: ${sunsetTime}")
    logDebug("midTime: ${midTime}")
    logDebug("currentTime: ${currentTime}")
    logDebug("brightenStart: ${brightenStart}")
    logDebug("brightenEnd: ${brightenEnd}")
    logDebug("dimStart: ${dimStart}")
    logDebug("dimEnd: ${dimEnd}")

    def int warmCT = 2700
    def int coldCT = 6500
    if (settings.useCTOverrides) {
        if (settings.useSeasonalCT) {
            // Seasonal mode: interpolate between winter and summer endpoints using the
            // cached season factor (0.0 = winter solstice/warmest, 1.0 = summer solstice/coolest).
            double sf = (state.seasonFactor != null) ? state.seasonFactor : 0.5
            int warmWinter = (settings.warmCTWinter != null && settings.warmCTWinter != "") ? settings.warmCTWinter : 2400
            int warmSummer = (settings.warmCTSummer != null && settings.warmCTSummer != "") ? settings.warmCTSummer : 2700
            int coldWinter = (settings.coldCTWinter != null && settings.coldCTWinter != "") ? settings.coldCTWinter : 5000
            int coldSummer = (settings.coldCTSummer != null && settings.coldCTSummer != "") ? settings.coldCTSummer : 6500
            warmCT = Math.round(warmWinter + sf * (warmSummer - warmWinter))
            coldCT = Math.round(coldWinter + sf * (coldSummer - coldWinter))
            logDebug("Seasonal CT: factor=${sf}, warmCT=${warmCT}, coldCT=${coldCT}")
        }
        else {
            if (settings.warmCTOverride != null && settings.warmCTOverride != "") {
                warmCT = settings.warmCTOverride
            }
            if (settings.coldCTOverride != null && settings.coldCTOverride != "") {
                coldCT = settings.coldCTOverride
            }
        }
    }
    def int rangeCT = coldCT - warmCT

    // CT curve shape: controls how quickly temperature moves warm->cold->warm.
    // Values above 1.0 cause the curve to spend more time near warm and only
    // reach peak cold briefly around midday. Stored as a string enum in settings.
    double ctCurve = 1.0
    if (settings.ctCurveShape != null && settings.ctCurveShape != "") {
        try {
            ctCurve = Double.parseDouble(settings.ctCurveShape)
        } catch (Exception e) {
            logDebug("ctCurveShape parse error, using default 1.0: ${e.message}")
            ctCurve = 1.0
        }
    }

    def int minBrightness = 1
    def int maxBrightness = 100
    if (settings.useBrightnessOverrides) {
        if (settings.minBrightnessOverride != null && settings.minBrightnessOverride > 1) {
            minBrightness = settings.minBrightnessOverride
        }
        if (settings.maxBrightnessOverride != null && settings.maxBrightnessOverride < 100) {
            maxBrightness = settings.maxBrightnessOverride
        }
    }

    def int baseBrightness
    def int rangeBrightness
    switch (settings.brightnessProfile) {
        case "Gradual":
            baseBrightness = minBrightness
            rangeBrightness = maxBrightness - minBrightness
            break
        case "Accelerated":
            // Uses a fixed wide range (0-99) so the ramp overshoots maxBrightness before
            // brightenEnd. The clamp catches it early, making it hit max sooner than Gradual.
            // This is the intentional mechanism. Do not change rangeBrightness to match Gradual.
            baseBrightness = minBrightness
            rangeBrightness = 99
            break
        case "Delayed":
            // Stays at 1% until a fast ramp reaches full brightness exactly at midday.
            // Intentionally ignores min/max overrides to preserve the timed-ramp behaviour.
            baseBrightness = 1
            rangeBrightness = 99
            break
        default:
            baseBrightness = minBrightness
            rangeBrightness = maxBrightness - minBrightness
            break
    }

    // Explicitly declare colorTemp and brightness to prevent NullPointerException on unexpected paths
    def colorTemp = warmCT
    def brightness = minBrightness

    if (currentTime >= sunriseTime.time && currentTime <= sunsetTime.time) {
        if (currentTime < midTime) {
            // Apply curve shape to CT: progress^ctCurve bends the ramp so values > 1.0
            // keep the temperature warmer for longer before rising toward cold.
            def ctProgress = (currentTime - sunriseTime.time) / (midTime - sunriseTime.time)
            colorTemp = warmCT + (ctProgress ** ctCurve) * rangeCT

            if (currentTime < brightenStart) {
                brightness = minBrightness
            }
            else if (currentTime < brightenEnd) {
                brightness = baseBrightness + ((currentTime - brightenStart) / (brightenEnd - brightenStart) * rangeBrightness)
                if (brightness < minBrightness) { brightness = minBrightness }
                if (brightness > maxBrightness) { brightness = maxBrightness }
            }
            else {
                brightness = maxBrightness
            }
        }
        else {
            // Mirror the curve on the afternoon side
            def ctProgress = (sunsetTime.time - currentTime) / (sunsetTime.time - midTime)
            colorTemp = warmCT + (ctProgress ** ctCurve) * rangeCT

            if (currentTime < dimStart) {
                brightness = maxBrightness
            }
            else if (currentTime < dimEnd) {
                brightness = baseBrightness + ((dimEnd - currentTime) / (dimEnd - dimStart) * rangeBrightness)
                if (brightness < minBrightness) { brightness = minBrightness }
                if (brightness > maxBrightness) { brightness = maxBrightness }
            }
            else {
                brightness = minBrightness
            }
        }
        if (!state.scheduleActive) {
            schedule("0 */5 * * * ?", eventUpdate)
            state.scheduleActive = true
            for (device in otherSwitches) {
                device.on()
            }
        }
    }
    else {
        if (state.scheduleActive) {
            unschedule(eventUpdate)
            state.scheduleActive = false
            for (device in otherSwitches) {
                device.off()
            }
        }
        colorTemp = warmCT
        brightness = minBrightness
    }

    if (settings.useModeOverrides) {
        switch (location.mode) {
            case mode1Override:
                if (settings.mode1OverrideValue != null && settings.mode1OverrideValue > 0 && settings.mode1OverrideValue <= 100) {
                    brightness = settings.mode1OverrideValue
                }
                if (settings.mode1OverrideColorTemperature != null && settings.mode1OverrideColorTemperature != "") {
                    colorTemp = settings.mode1OverrideColorTemperature
                }
                logDebug("Mode1 Override for ${location.mode} mode")
                break
            case mode2Override:
                if (settings.mode2OverrideValue != null && settings.mode2OverrideValue > 0 && settings.mode2OverrideValue <= 100) {
                    brightness = settings.mode2OverrideValue
                }
                if (settings.mode2OverrideColorTemperature != null && settings.mode2OverrideColorTemperature != "") {
                    colorTemp = settings.mode2OverrideColorTemperature
                }
                logDebug("Mode2 Override for ${location.mode} mode")
                break
            case mode3Override:
                if (settings.mode3OverrideValue != null && settings.mode3OverrideValue > 0 && settings.mode3OverrideValue <= 100) {
                    brightness = settings.mode3OverrideValue
                }
                if (settings.mode3OverrideColorTemperature != null && settings.mode3OverrideColorTemperature != "") {
                    colorTemp = settings.mode3OverrideColorTemperature
                }
                logDebug("Mode3 Override for ${location.mode} mode")
                break
            case mode4Override:
                if (settings.mode4OverrideValue != null && settings.mode4OverrideValue > 0 && settings.mode4OverrideValue <= 100) {
                    brightness = settings.mode4OverrideValue
                }
                if (settings.mode4OverrideColorTemperature != null && settings.mode4OverrideColorTemperature != "") {
                    colorTemp = settings.mode4OverrideColorTemperature
                }
                logDebug("Mode4 Override for ${location.mode} mode")
                break
            case mode5Override:
                if (settings.mode5OverrideValue != null && settings.mode5OverrideValue > 0 && settings.mode5OverrideValue <= 100) {
                    brightness = settings.mode5OverrideValue
                }
                if (settings.mode5OverrideColorTemperature != null && settings.mode5OverrideColorTemperature != "") {
                    colorTemp = settings.mode5OverrideColorTemperature
                }
                logDebug("Mode5 Override for ${location.mode} mode")
                break
            case mode6Override:
                if (settings.mode6OverrideValue != null && settings.mode6OverrideValue > 0 && settings.mode6OverrideValue <= 100) {
                    brightness = settings.mode6OverrideValue
                }
                if (settings.mode6OverrideColorTemperature != null && settings.mode6OverrideColorTemperature != "") {
                    colorTemp = settings.mode6OverrideColorTemperature
                }
                logDebug("Mode6 Override for ${location.mode} mode")
                break
            case mode7Override:
                if (settings.mode7OverrideValue != null && settings.mode7OverrideValue > 0 && settings.mode7OverrideValue <= 100) {
                    brightness = settings.mode7OverrideValue
                }
                if (settings.mode7OverrideColorTemperature != null && settings.mode7OverrideColorTemperature != "") {
                    colorTemp = settings.mode7OverrideColorTemperature
                }
                logDebug("Mode7 Override for ${location.mode} mode")
                break
            case mode8Override:
                if (settings.mode8OverrideValue != null && settings.mode8OverrideValue > 0 && settings.mode8OverrideValue <= 100) {
                    brightness = settings.mode8OverrideValue
                }
                if (settings.mode8OverrideColorTemperature != null && settings.mode8OverrideColorTemperature != "") {
                    colorTemp = settings.mode8OverrideColorTemperature
                }
                logDebug("Mode8 Override for ${location.mode} mode")
                break
            case mode9Override:
                if (settings.mode9OverrideValue != null && settings.mode9OverrideValue > 0 && settings.mode9OverrideValue <= 100) {
                    brightness = settings.mode9OverrideValue
                }
                if (settings.mode9OverrideColorTemperature != null && settings.mode9OverrideColorTemperature != "") {
                    colorTemp = settings.mode9OverrideColorTemperature
                }
                logDebug("Mode9 Override for ${location.mode} mode")
                break
            case mode10Override:
                if (settings.mode10OverrideValue != null && settings.mode10OverrideValue > 0 && settings.mode10OverrideValue <= 100) {
                    brightness = settings.mode10OverrideValue
                }
                if (settings.mode10OverrideColorTemperature != null && settings.mode10OverrideColorTemperature != "") {
                    colorTemp = settings.mode10OverrideColorTemperature
                }
                logDebug("Mode10 Override for ${location.mode} mode")
                break
            default:
                logDebug("No override for ${location.mode} mode")
                break
        }
    }
    else {
        logDebug("Mode overrides are not active")
    }

    return [colorTemp: Math.round(colorTemp), brightness: Math.round(brightness)]
}

private def getSunriseTime() {
    def sunRiseSet
    def sunriseTime

    if (settings.useSunOverrides && settings.sunriseOverride != null && settings.sunriseOverride != "") {
        sunriseTime = todayAt(settings.sunriseOverride)
        logDebug("Sunrise Override is ${sunriseTime}")
    }
    else {
        sunRiseSet = getSunriseAndSunset()
        sunriseTime = sunRiseSet.sunrise
        logDebug("System Sunrise time is ${sunriseTime}")
    }
    if (settings.useSunOffsets && settings.sunriseOffset != null && settings.sunriseOffset != "") {
        int offset = settings.sunriseOffset
        use(TimeCategory) {
            sunriseTime = sunriseTime + offset.minutes
        }
        logDebug("Sunrise offset to ${sunriseTime}")
    }

    return sunriseTime
}

private def getSunsetTime(){
    def sunRiseSet
    def sunsetTime

    if (settings.useSunOverrides && settings.sunsetOverride != null && settings.sunsetOverride != "") {
        sunsetTime = todayAt(settings.sunsetOverride)
        logDebug("Sunset Override is ${sunsetTime}")
    }
    else {
        sunRiseSet = getSunriseAndSunset()
        sunsetTime = sunRiseSet.sunset
        logDebug("System Sunset time is ${sunsetTime}")
    }
    if (settings.useSunOffsets && settings.sunsetOffset != null && settings.sunsetOffset != "") {
        int offset = settings.sunsetOffset
        use(TimeCategory) {
            sunsetTime = sunsetTime + offset.minutes
        }
        logDebug("Sunset offset to ${sunsetTime}")
    }

    return sunsetTime
}

def ctToRGB(ct) {

    if(ct < 1000) { ct = 1000 }
    if(ct > 40000) { ct = 40000 }

    ct = ct / 100

    //red
    def r
    if(ct <= 66) { r = 255 }
    else { r = 329.698727446 * ((ct - 60) ** -0.1332047592) }
    if(r < 0) { r = 0 }
    if(r > 255) { r = 255 }

    //green
    def g
    if (ct <= 66) { g = 99.4708025861 * Math.log(ct) - 161.1195681661 }
    else { g = 288.1221695283 * ((ct - 60) ** -0.0755148492) }
    if(g < 0) { g = 0 }
    if(g > 255) { g = 255 }

    //blue
    def b
    if(ct >= 66) { b = 255 }
    else if(ct <= 19) { b = 0 }
    else { b = 138.5177312231 * Math.log(ct - 10) - 305.0447927307 }
    if(b < 0) { b = 0 }
    if(b > 255) { b = 255 }

    def rgb = [:]
    rgb = [r: r as Integer, g: g as Integer, b: b as Integer]
    rgb
}

def rgbToHex(rgb) {
    return "#" + Integer.toHexString(rgb.r).padLeft(2,'0') + Integer.toHexString(rgb.g).padLeft(2,'0') + Integer.toHexString(rgb.b).padLeft(2,'0')
}

def rgbToHSV(rgb) {
    def h, s, v

    def r = rgb.r / 255
    def g = rgb.g / 255
    def b = rgb.b / 255

    def max = [r, g, b].max()
    def min = [r, g, b].min()

    def delta = max - min

    //hue
    if(delta == 0) { h = 0}
    else if(max == r) {
        double dub = (g - b) / delta
        h = 60 * (dub % 6)
    }
    else if(max == g) { h = 60 * (((b - r) / delta) + 2) }
    else if(max == b) { h = 60 * (((r - g) / delta) + 4) }

    //saturation
    if(max == 0) { s = 0 }
    else { s = (delta / max) * 100 }

    //value
    v = max * 100

    logDebug("r=${rgb.r}, g=${rgb.g}, b=${rgb.b}, max=${max}, min=${min}, delta=${delta}, h=${Math.round(h)}, s=${Math.round(s)}, v=${Math.round(v)}")
    return [h: Math.round(h / 3.6), s: Math.round(s), v: Math.round(v)]
}