/**
 * Advanced Severe Weather Detector
 *
 * Fork maintained in https://github.com/FrankTank34567/hubitat-code
 * Derived from the "Advanced Severe Weather Detector" app originally written by
 * Shane Allen (namespace ShaneAllen). This copy is maintained separately and is
 * not updated from the original.
 *
 * Version 2.0.0 (2026-11-22), local-first foundation:
 *  - Removed the local Tornado and Tropical engines (a single backyard station
 *    cannot detect either; their scores fired on ordinary wind/pressure).
 *    Tornado, hurricane and tropical storm now come from NWS products.
 *  - NWS: exact product-name table covering current products (High Wind,
 *    Blizzard, Snow Squall, Extreme Cold / Cold Weather Advisory, Extreme
 *    Heat, Storm Surge, civil emergencies...), the strongest product wins,
 *    every product keeps its own expiry, and nothing NWS is trusted once the
 *    last good poll is 30+ min old. Each product group has its own
 *    notification rows (warnings / watches & advisories), one message per new
 *    product; updates are logged unless enabled. Local hazard rows now default
 *    to local-sensor triggers, and say when NWS agrees.
 *  - Pressure: uses station (absolute) pressure when the sensor has it and
 *    computes sea-level pressure with the NWS altimeter formula and the site
 *    elevation (entered, learned from airport comparisons, or the NWS grid).
 *    Tendencies are measured on the raw series, so calibration can no longer
 *    inject a fake pressure step. Air density uses station pressure.
 *  - Per-sensor quality control: each reading is checked for staleness and
 *    plausible range; a hazard whose inputs are missing pauses and keeps its
 *    state (a missing temperature used to read as 0°F). New Sensor offline /
 *    back notifications. Removed the EMI guard, Dying Breath and Eye of the
 *    Storm lock (superseded or without basis).
 *  - Lightning detectors with a strike count are no longer double counted
 *    from distance updates; the first count after start is a baseline.
 *  - Location-agnostic: a hub move of 10+ km re-discovers the NWS office,
 *    grid, airport and elevation; outside NWS coverage the app runs locally.
 *    °C hubs and metric pressure/wind/rain sensors are converted.
 *
 * Version 1.0.0 (2026-11-21), first fork release:
 *  - Expected-freeze auto-mute now uses local data (N of the last 7 nights froze)
 *    AND the NWS point forecast (tonight's forecast low), instead of only
 *    "10 of the last 14 days" which took ~10 days to kick in each autumn.
 *  - Freeze ALARM hysteresis: clears only once the temperature rises a set
 *    margin above 32F, so a reading hovering at 32-33F no longer flaps
 *    ALARM/WARNING every 15 minutes.
 *  - Routine (non-ice) freeze pushes are limited to one per tier per
 *    re-alert window (default 12 h).
 *  - Routine freeze no longer drives DEFCON overdrive polling, the global
 *    all-clear push, forensic event reports or the Dying Breath latch.
 *  - Ice: no more 31F lower bound (freezing rain at 28F used to be scored as a
 *    routine hard freeze). ICE ALARM needs corroboration from the TAF, an NWS
 *    ice product or NWS forecast text; uncorroborated = ICE WARNING tier.
 *    ICE states hold through precip breaks under 60 min, and repeat ICE
 *    ALARMs inside the ice re-alert window (default 6 h) are logged, not
 *    pushed. Ice is never muted by the expected-freeze logic.
 *  - NWS: Winter Storm Warning / Winter Weather Advisory no longer map to
 *    Freeze (they sent "Freeze ALARM" for snowstorms) unless their text calls
 *    for freezing rain/drizzle or ice accumulation, then they route as ICE.
 *    Ice Storm products route as ICE. A WARNING-level product no longer
 *    downgrades an ALARM-level one in the same poll.
 *  - Child device globalState ignores routine (non-ice) freeze.
 *  - Notification Center: every push/TTS/siren/sound is its own notification
 *    type (WARNING / ALARM / cleared per hazard, Freeze split into routine
 *    freeze and ice, plus the global all-clear), each with on/off per output,
 *    text, details, devices, cooldown, escalation bypass, hold-off, daily
 *    cap, minimum probability, local/NWS source filter, modes and quiet
 *    hours. Existing per-hazard settings are used as starting values. A
 *    decision log shows what was sent or skipped and why.
 */

definition(
    name: "Advanced Severe Weather Detector",
    namespace: "franktank",
    author: "FrankTank34567",
    importUrl: "https://raw.githubusercontent.com/FrankTank34567/hubitat-code/main/apps/Advanced%20Severe%20Weather%20Detector.groovy",
    description: "Local-first severe weather monitor: your own station's readings judged against published criteria, with official NWS products as corroboration and as their own optional notifications.",
    category: "Safety & Security",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    singleThreaded: true  // Many event handlers + async HTTP callbacks all mutate state.
                          // Without this, Hubitat runs them concurrently and lost-update
                          // races can corrupt threatMatrix/history arrays during event storms.
)

preferences {
    page(name: "mainPage")
    page(name: "configPage")
    page(name: "speakerMappingPage")
    page(name: "dnaConfigPage")
    page(name: "notificationPage")
}

def renderChartHTML() {
    def buildJsArray = { hist ->
        if (!hist) return "[]"
        return "[" + hist.collect { "{ x: ${it.time}, y: ${it.value} }" }.join(",") + "]"
    }

    def buildLightningBars = { hist ->
        if (!hist || hist.size() == 0) return "[]"
        def counts = [:]
        hist.each { entry ->
            def hourMillis = entry.time - (entry.time % 3600000) 
            counts[hourMillis] = (counts[hourMillis] ?: 0) + 1
        }
        return "[" + counts.collect { "{ x: ${it.key}, y: ${it.value} }" }.join(",") + "]"
    }

    def tempJs = buildJsArray(state.tempHistory)
    def pressJs = buildJsArray(state.pressureHistory)
    def spreadJs = buildJsArray(state.spreadHistory)
    def probJs = buildJsArray(state.probHistory)
    
    def windJs = buildJsArray(state.windHistory)
    def lightJs = buildLightningBars(state.lightningHistory)
    
    def windUnit = isMetric() ? "km/h" : "mph"

    return """
    <h4 style="margin:0 0 10px 0; border-bottom:1px solid #ccc; padding-bottom:5px; color:#333;">24-Hour Thermodynamic Timeline</h4>
    <div id="chartWrapper1" style="position: relative; height: 350px; width: 100%; background: #fdfdfd; border: 1px solid #eee; border-radius: 4px; margin-bottom: 20px;">
        <div id="chartLoadingText1" style="position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); font-family: sans-serif; color: #555; font-weight: bold; font-size: 14px;">
            Loading chart data...
        </div>
        <canvas id="weatherChart" style="opacity: 0; transition: opacity 0.4s ease-in-out;"></canvas>
    </div>

    <h4 style="margin:0 0 10px 0; border-bottom:1px solid #ccc; padding-bottom:5px; color:#333;">Wind & Lightning Activity</h4>
    <div id="chartWrapper2" style="position: relative; height: 250px; width: 100%; background: #fdfdfd; border: 1px solid #eee; border-radius: 4px;">
        <div id="chartLoadingText2" style="position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); font-family: sans-serif; color: #555; font-weight: bold; font-size: 14px;">
            Loading activity data...
        </div>
        <canvas id="windLightChart" style="opacity: 0; transition: opacity 0.4s ease-in-out;"></canvas>
    </div>
    
    <script>
    (function() {
        var chartLibs = [
            "https://cdn.jsdelivr.net/npm/chart.js",
            "https://cdn.jsdelivr.net/npm/luxon",
            "https://cdn.jsdelivr.net/npm/chartjs-adapter-luxon"
        ];
        
        function loadScript(url, callback) {
            if (document.querySelector('script[src="' + url + '"]')) {
                if (callback) callback();
                return;
            }
            var s = document.createElement('script');
            s.type = 'text/javascript';
            s.src = url;
            s.onload = callback;
            document.head.appendChild(s);
        }

        function initChart() {
            var canvas1 = document.getElementById('weatherChart');
            var loading1 = document.getElementById('chartLoadingText1');
            var canvas2 = document.getElementById('windLightChart');
            var loading2 = document.getElementById('chartLoadingText2');
            
            if (!canvas1 || !canvas2 || typeof Chart === 'undefined' || typeof luxon === 'undefined') {
                setTimeout(initChart, 100);
                return;
            }
            
            if (window.myWeatherChart) window.myWeatherChart.destroy();
            if (window.myWindChart) window.myWindChart.destroy();
            
            var ctx1 = canvas1.getContext('2d');
            window.myWeatherChart = new Chart(ctx1, {
                type: 'line',
                data: {
                    datasets: [
                        { label: 'Temperature (°)', data: ${tempJs}, borderColor: 'rgb(255, 99, 132)', yAxisID: 'yTemp', tension: 0.3, pointRadius: 0 },
                        { label: 'Dew Point Spread (°)', data: ${spreadJs}, borderColor: 'rgb(54, 162, 235)', yAxisID: 'yTemp', borderDash: [5, 5], tension: 0.3, pointRadius: 0 },
                        { label: 'Pressure', data: ${pressJs}, borderColor: 'rgb(75, 192, 192)', yAxisID: 'yPress', tension: 0.3, pointRadius: 0 },
                        { label: 'Hazard Probability (%)', data: ${probJs}, backgroundColor: 'rgba(153, 102, 255, 0.2)', borderColor: 'rgb(153, 102, 255)', yAxisID: 'yProb', fill: true, stepped: true, pointRadius: 0 }
                    ]
                },
                options: {
                    responsive: true, maintainAspectRatio: false, interaction: { mode: 'index', intersect: false },
                    scales: {
                        x: { type: 'time', time: { unit: 'hour' }, title: { display: false } },
                        yTemp: { type: 'linear', display: true, position: 'left', title: { display: true, text: 'Temp / Spread' } },
                        yPress: { type: 'linear', display: true, position: 'right', title: { display: true, text: 'Pressure' }, grid: { drawOnChartArea: false } },
                        yProb: { type: 'linear', display: true, position: 'right', min: 0, max: 100, title: { display: true, text: 'Probability %' }, grid: { drawOnChartArea: false } }
                    }
                }
            });

            var ctx2 = canvas2.getContext('2d');
            window.myWindChart = new Chart(ctx2, {
                type: 'bar',
                data: {
                    datasets: [
                        { type: 'line', label: 'Wind Speed (${windUnit})', data: ${windJs}, borderColor: 'rgb(255, 159, 64)', backgroundColor: 'rgba(255, 159, 64, 0.2)', yAxisID: 'yWind', tension: 0.3, pointRadius: 0, fill: true },
                        { type: 'bar', label: 'Lightning Strikes (per hr)', data: ${lightJs}, backgroundColor: 'rgba(255, 205, 86, 0.8)', yAxisID: 'yLight', barThickness: 15 }
                    ]
                },
                options: {
                    responsive: true, maintainAspectRatio: false, interaction: { mode: 'index', intersect: false },
                    scales: {
                        x: { type: 'time', time: { unit: 'hour' }, title: { display: false }, offset: true },
                        yLight: { type: 'linear', display: true, position: 'left', min: 0, title: { display: true, text: 'Strikes/Hr' }, ticks: { precision: 0 } },
                        yWind: { type: 'linear', display: true, position: 'right', min: 0, title: { display: true, text: 'Wind (${windUnit})' }, grid: { drawOnChartArea: false } }
                    }
                }
            });

            if (loading1) loading1.style.display = 'none';
            if (canvas1) canvas1.style.opacity = '1';
            if (loading2) loading2.style.display = 'none';
            if (canvas2) canvas2.style.opacity = '1';
        }

        loadScript(chartLibs[0], function() {
            loadScript(chartLibs[1], function() {
                loadScript(chartLibs[2], function() {
                    initChart();
                });
            });
        });
    })();
    </script>
    """
}

def renderTableHTML() {
    if (!state.tempHistory || state.tempHistory.size() == 0) {
        return "<h4 style='margin:0 0 10px 0; border-bottom:1px solid #ccc; padding-bottom:5px; color:#333;'>24-Hour Timeline</h4><div>Gathering history data... check back in a few minutes.</div>"
    }
    
    def reversedHist = state.tempHistory.reverse()
    
    def tableHTML = """
    <h4 style="margin:0 0 10px 0; border-bottom:1px solid #ccc; padding-bottom:5px; color:#333;">24-Hour Local Data Table</h4>
    <div style="height: 350px; overflow-y: auto; border: 1px solid #eee;">
        <table style="width: 100%; border-collapse: collapse; font-size: 13px; text-align: left; font-family: monospace;">
            <thead style="background: #eee; position: sticky; top: 0; box-shadow: 0 1px 2px rgba(0,0,0,0.1);">
                <tr>
                    <th style="padding: 8px; border-bottom: 1px solid #ccc;">Time</th>
                    <th style="padding: 8px; border-bottom: 1px solid #ccc;">Temp</th>
                    <th style="padding: 8px; border-bottom: 1px solid #ccc;">Pressure</th>
                    <th style="padding: 8px; border-bottom: 1px solid #ccc;">DP Spread</th>
                    <th style="padding: 8px; border-bottom: 1px solid #ccc;">Hazard Prob</th>
                </tr>
            </thead>
            <tbody>
    """
    
    reversedHist.each { entry ->
        def timeStr = new Date((long)entry.time).format("MM/dd HH:mm", location.timeZone)
        def tVal = String.format('%.1f', entry.value)
        
        def findMatch = { hist -> 
            def match = hist?.find { Math.abs(it.time - entry.time) < 120000 }
            return match ? String.format('%.2f', match.value) : "--"
        }
        
        def pVal = findMatch(state.pressureHistory)
        def sVal = findMatch(state.spreadHistory)
        
        def probMatch = state.probHistory?.find { Math.abs(it.time - entry.time) < 120000 }
        def probVal = probMatch ? "${Math.round(probMatch.value)}%" : "--"
        
        tableHTML += """
            <tr style="border-bottom: 1px solid #eee;">
                <td style="padding: 6px 8px; color: #555;">${timeStr}</td>
                <td style="padding: 6px 8px;">${tVal}°</td>
                <td style="padding: 6px 8px;">${pVal}</td>
                <td style="padding: 6px 8px;">${sVal != "--" ? sVal + "°" : "--"}</td>
                <td style="padding: 6px 8px; font-weight: bold; color: ${probMatch && probMatch.value > 50 ? 'red' : 'black'};">${probVal}</td>
            </tr>
        """
    }
    
    tableHTML += """
            </tbody>
        </table>
    </div>
    """
    return tableHTML
}

def renderRadarHTML() {
    def lat = settings.manualLat ?: (location.latitude ?: 39.8283)
    def lon = settings.manualLon ?: (location.longitude ?: -98.5795)
    def radarWind = isMetric() ? "km%2Fh" : "mph"
    def radarTemp = isMetric() ? "%C2%B0C" : "%C2%B0F"

    return """
    <h4 style="margin:0 0 10px 0; border-bottom:1px solid #ccc; padding-bottom:5px; color:#333;">Live Regional Radar <span style='font-size:11px; font-weight:normal; color:#888;'>(source: embed.windy.com, loaded by YOUR BROWSER when viewing this page; the hub itself never contacts it)</span></h4>
    <iframe width="100%" height="450" src="https://embed.windy.com/embed.html?type=map&location=coordinates&metricRain=in&metricTemp=${radarTemp}&metricWind=${radarWind}&zoom=${settings.radarZoom ?: 8}&overlay=radar&product=radar&level=surface&lat=${lat}&lon=${lon}&detailLat=${lat}&detailLon=${lon}&marker=true&message=true" frameborder="0" style="display:block; border-radius: 4px;"></iframe>
    """
}

def mainPage() {
    return dynamicPage(name: "mainPage", title: "<b>Advanced Severe Weather Detector</b>", install: true, uninstall: true) {
     
        section("<b>Live Threat Assessment Matrix</b>") {
            if (app.id) {
                input "refreshDashboardBtn", "button", title: "🔄 Refresh Live Data"
            }
            
            // --- SYSTEM HEALTH & DIAGNOSTIC HEARTBEAT ---
            def lastEvalMs = state.lastHeartbeat ?: now()
            def timeSinceEval = Math.round((now() - lastEvalMs) / 1000)
            def isHealthy = timeSinceEval < 120
            def healthDot = isHealthy ? "<span style='color:#5cb85c;'>●</span>" : "<span style='color:#d9534f;'>●</span>"
            def execTime = state.lastEvalDuration ?: 0
            def emiStatus = ""
            
            def netBadge = (settings.enableInternet == false) ? "<span style='color:#f0ad4e; font-weight:bold;'>[🔒 LOCAL-ONLY: Zero internet use]</span>" : "<span style='color:#569cd6;'>[🌐 Internet: NWS Alerts ${settings.enableNOAA ? 'ON' : 'off'} | Auto-Cal ${settings.enableAutoCal ? 'ON' : 'off'}]</span>"
            def calBadge = ""
            if (state.mslpMiscalibrated) calBadge = " <span style='color:#d9534f; font-weight:bold;'>[⚠ NO SEA-LEVEL PRESSURE YET: set your elevation or enable auto-calibration; pressure tendency still works]</span>"
            else if (state.pressureMode == "station") calBadge = " <span style='color:#5cb85c;'>[📡 Sea-level pressure from your station pressure + ${Math.round((siteElevationM() ?: 0) * 3.28084)} ft (${siteElevationSource()})${state.lastCalPull?.ours != null ? ', last check ' + state.lastCalPull.station + ' ' + state.lastCalPull.ref + ' vs yours ' + state.lastCalPull.ours : ''}]</span>"
            else if (state.lastCalPull) calBadge = " <span style='color:#5cb85c;'>[📡 Calibrated vs ${state.lastCalPull.station}: ${state.lastCalPull.offset > 0 ? '+' : ''}${state.lastCalPull.offset} inHg]</span>"
            def sh = state.sensorHealth ?: [:]
            def bad = sh.findAll { k, v -> v.ok == false }
            if (bad) calBadge += "<br><span style='color:#d9534f; font-weight:bold;'>[⚠ SENSORS: " + bad.collect { k, v -> "${k} ${v.why}" }.join("; ") + "]</span>"
            if (state.nwsCoverage == false) calBadge += " <span style='color:#f0ad4e;'>[🌐 Outside NWS coverage: local sensors only]</span>"
            def errBadge = state.lastEngineError ? "<br><span style='color:#d9534f; font-weight:bold;'>[🛑 LAST ENGINE ERROR: ${state.lastEngineError}]</span>" : ""
            def qualBadge = "<br><span style='color:#9cdcfe;'>[🔎 Sensor Noise Floor: pressure ±${String.format('%.3f', (state.pressResolution ?: 0.01))} inHg | quality gates ${Math.max(0.03, 3.0 * (state.pressResolution ?: 0.01))} inHg min-move]</span>"
            def tafBadge = ""
            if (state.tafAdvisory) {
                def ta = state.tafAdvisory
                tafBadge = " <span style='color:#569cd6;'>[✈ TAF ${ta.station}: TS=${ta.ts ? 'YES' : 'no'} FZRA=${ta.fzra ? 'YES' : 'no'} MaxGust=${ta.gustMph}mph]</span>"
            }
            def winterBadge = state.freezeExpected ? " <span style='color:#9cdcfe; font-weight:bold;'>[❄️ FREEZE EXPECTED (${state.freezeExpectedReason ?: 'learned'}): routine freeze alerts auto-muted; ice alerts active]</span>" : ""
            def qcBadge = state.metarExtras?.windQcFlag ? " <span style='color:#f0ad4e;'>[⚠ Wind sensor diverges 20+ mph from ${state.metarExtras.station}]</span>" : ""
            
            def healthHtml = """
            <div style='background-color:#1e1e1e; color:#d4d4d4; padding: 10px; border-radius: 6px; font-family: monospace; font-size: 11px; margin-bottom: 15px;'>
                <div style='color:#569cd6; font-weight:bold; margin-bottom: 4px;'>${healthDot} SYSTEM DIAGNOSTICS & TELEMETRY ${emiStatus}</div>
                Last Engine Cycle: <b>${timeSinceEval}s ago</b> | Cycle Compute Time: <b>${execTime}ms</b><br>
                Telemetry Arrays: Temp[${state.tempHistory?.size()?:0}] Press[${state.pressureHistory?.size()?:0}] Wind[${state.windHistory?.size()?:0}] Light[${state.lightningHistory?.size()?:0}] Prob[${state.probHistory?.size()?:0}]<br>
                ${netBadge}${calBadge}${tafBadge}${winterBadge}${qcBadge}${errBadge}${qualBadge}
            </div>
            """
            paragraph healthHtml
            
            def isWatchdog = state.watchdogActive ?: false
            def wdHtml = isWatchdog ? "<span style='color:#d9534f; font-weight:bold;'>[🚨 DEFCON WATCHDOG ACTIVE: Overdrive Polling Engaged]</span>" : "<span style='color:#5cb85c;'>[Standard Local Polling]</span>"
            
            def cloudHtml = ""
            if (settings.enableNOAA) {
                if (state.cloudOffline) {
                    cloudHtml = " <span style='color:#d9534f; font-weight:bold;'>[☁️ CLOUD OFFLINE: NOAA Suspended]</span>"
                } else {
                    cloudHtml = " <span style='color:#0275d8; font-weight:bold;'>[☁️ NOAA Sync Active]</span>"
                }
            }
            
            paragraph "<div style='font-size:13px; color:#555;'><b>What it does:</b> Watches your own weather station for five hazards (storm, flood, freeze/ice, heat, fire weather), checks the NWS for official products as corroboration, and sends each kind of alert only the way you set it in the Notification Center. ${wdHtml}${cloudHtml}</div>"
            
            // --- FORENSIC EVENT SUMMARY TABLE ---
            if (state.lastEventReport) {
                def forensicHtml = """
                <table style='width:100%; border-collapse: collapse; font-size: 12px; font-family: sans-serif; background-color: #fffcfc; border: 1px solid #d9534f; margin-bottom: 15px; box-shadow: 0 2px 5px rgba(0,0,0,0.05);'>
                    <tr style='background-color: #d9534f; color: white;'>
                        <th colspan='4' style='padding: 6px; text-align: left; font-size: 13px;'>📊 Forensic Event Summary: ${state.lastEventReport.date}</th>
                    </tr>
                    <tr style='background-color: #fdf0f0; color: #333; text-align: center;'>
                        <td style='padding: 8px; border-right: 1px solid #f2dede;'><b>Max Wind Gust</b><br><span style='font-size:14px; color:#c9302c;'>${String.format('%.1f', state.lastEventReport.maxWind)} mph</span></td>
                        <td style='padding: 8px; border-right: 1px solid #f2dede;'><b>Min Pressure</b><br><span style='font-size:14px; color:#c9302c;'>${String.format('%.2f', state.lastEventReport.minPress)} inHg</span></td>
                        <td style='padding: 8px; border-right: 1px solid #f2dede;'><b>Max Temp Drop</b><br><span style='font-size:14px; color:#c9302c;'>${String.format('%.1f', state.lastEventReport.tempDrop)}°</span></td>
                        <td style='padding: 8px;'><b>Event Duration</b><br><span style='font-size:14px; color:#c9302c;'>${state.lastEventReport.duration}</span></td>
                    </tr>
                </table>
                """
                paragraph forensicHtml
            }

            if (sensorTemp && sensorHum && sensorPress) {
                def matrix = state.threatMatrix ?: [:]
                def noaaMap = state.noaaAlertsMap ?: [:]
                def hazards = [
                    [id: "Thunderstorm", icon: "⛈️"],
                    [id: "Flood", icon: "🌊"],
                    [id: "Freeze", icon: "❄️"],
                    [id: "SevereHeat", icon: "🔥"],
                    [id: "FireWeather", icon: "🔥🌲"]
                ]
                
                hazards.each { hazMap ->
                    def haz = hazMap.id
                    def icon = hazMap.icon
                    def data = matrix[haz] ?: [threat: 0, prob: 0, conf: 0, state: "Clear", howWhy: "Gathering sensor data...", mathEx: "Awaiting math cycle...", lastTrig: 0, history: []]
  
                    def noaaStatus = nwsHazardStatus(haz)
                    def noaaRaw = nwsActiveAlerts().findAll { it.haz == haz }.collect { "<b>${it.event}</b>: ${it.headline}" }.join("<br>") ?: "No official alerts for this sector."
                    def noaaColor = noaaStatus == "ALARM" ? "#d9534f" : (noaaStatus == "WARNING" ? "#f0ad4e" : "#5cb85c")
                    def noaaBg = noaaStatus == "Clear" ? "#f4f8f4" : (noaaStatus == "WARNING" ? "#fcf6ec" : "#fdf0f0")
                    
                    def stateColor = data.state == "ALARM" ? "#d9534f" : (data.state == "WARNING" ? "#f0ad4e" : "#5cb85c")
                    def headerBg = data.state == "ALARM" ? "#fdf0f0" : (data.state == "WARNING" ? "#fcf6ec" : "#f4f8f4")
                    
                    if (data.state == "ICE WARNING" || data.state == "ICE ALARM") {
                        stateColor = "#00bcd4"
                        headerBg = "#e0f7fa"
                    }
                    
                    def tColor = data.threat > 75 ? "#d9534f" : (data.threat > 40 ? "#f0ad4e" : "#5bc0de")
                    def pColor = data.prob > 75 ? "#d9534f" : (data.prob > 40 ? "#f0ad4e" : "#5bc0de")
                    def cColor = data.conf > 75 ? "#5cb85c" : (data.conf > 40 ? "#f0ad4e" : "#d9534f")

                    def histHtml = "<div style='margin-top: 10px; border-top: 1px dashed #ccc; padding-top: 8px;'><b style='font-size:12px; color:#222;'>Recent Event History:</b><br>"
                    if (data.history && data.history.size() > 0) {
                        data.history.each { h ->
                            def c = h.type.contains('ALARM') ? '#d9534f' : '#f0ad4e'
                            histHtml += "<div style='font-size:11px; color:#555;'>• <span style='color:${c}; font-weight:bold;'>${h.type}</span> | Initiated: ${h.start} | Duration: <b>${h.dur}</b></div>"
                        }
                    } else {
                        histHtml += "<div style='font-size:11px; color:#888; font-style:italic;'>No recent events recorded.</div>"
                    }
                    histHtml += "</div>"

                    def prettyName = haz == 'SevereHeat' ? 'Severe Heat' : (haz == 'FireWeather' ? 'Fire Weather' : haz)
                    def sectionTitle = "${icon} ${prettyName} DNA &nbsp;|&nbsp; Threat: ${data.threat}% &nbsp;|&nbsp; Conf: ${data.conf}% &nbsp;|&nbsp; State: ${data.state}"
                    def isHidden = (data.state == "Clear" && noaaStatus == "Clear")

                    section("<b>${sectionTitle}</b>", hideable: true, hidden: isHidden) {
                        def dashboardHtml = """
                        <div style="border: 1px solid #e0e0e0; margin-bottom: 5px; border-radius: 8px; background: #ffffff; overflow: hidden; font-family: 'Segoe UI', Tahoma, sans-serif; box-shadow: 0 2px 5px rgba(0,0,0,0.05);">
                            <div style="display: flex; flex-wrap: wrap; padding: 15px; gap: 15px; border-bottom: 1px solid #eee;">
                                <div style="flex: 1; min-width: 200px;">
                                    <div style="display: inline-block; padding: 3px 10px; background: ${headerBg}; color: ${stateColor}; border: 1px solid ${stateColor}; font-weight: bold; border-radius: 12px; font-size: 11px; margin-bottom: 12px; letter-spacing: 0.5px; text-transform: uppercase;">
                                        LOCAL SYSTEM: ${data.state}
                                    </div>
                                    <div style="margin-bottom: 10px;">
                                        <div style="font-size: 12px; display: flex; justify-content: space-between; margin-bottom: 4px; color: #444;"><b>Threat Intensity</b><b>${data.threat}%</b></div>
                                        <div style="width: 100%; height: 8px; background: #eaecf0; border-radius: 4px;"><div style="width: ${data.threat}%; height: 100%; background: ${tColor}; border-radius: 4px; transition: width 0.5s ease;"></div></div>
                                    </div>
                                    <div style="margin-bottom: 10px;">
                                        <div style="font-size: 12px; display: flex; justify-content: space-between; margin-bottom: 4px; color: #444;"><b>Probability Score</b><b>${data.prob}%</b></div>
                                        <div style="width: 100%; height: 8px; background: #eaecf0; border-radius: 4px;"><div style="width: ${data.prob}%; height: 100%; background: ${pColor}; border-radius: 4px; transition: width 0.5s ease;"></div></div>
                                    </div>
                                    <div>
                                        <div style="font-size: 12px; display: flex; justify-content: space-between; margin-bottom: 4px; color: #444;"><b>System Confidence</b><b>${data.conf}%</b></div>
                                        <div style="width: 100%; height: 8px; background: #eaecf0; border-radius: 4px;"><div style="width: ${data.conf}%; height: 100%; background: ${cColor}; border-radius: 4px; transition: width 0.5s ease;"></div></div>
                                    </div>
                                </div>
                                <div style="flex: 1.2; min-width: 250px; font-size: 13px; color: #444; background: #f8f9fa; border-radius: 6px; padding: 12px; border-left: 4px solid ${stateColor}; display: flex; flex-direction: column; justify-content: flex-start;">
                                    <b style="color:#222; margin-bottom: 6px; font-size: 14px;">Diagnostic Report:</b>
                                    <span style="line-height: 1.4;">${data.howWhy}</span>
                                    ${histHtml}
                                </div>
                                <div style="flex: 1.2; min-width: 200px; font-size: 11px; color: #333; background: #eef2f5; border-radius: 6px; padding: 12px; border-left: 4px solid #8e9eab; display: flex; flex-direction: column; justify-content: flex-start; font-family: 'Courier New', Courier, monospace;">
                                    <b style="color:#222; margin-bottom: 6px; font-size: 12px; font-family: 'Segoe UI', sans-serif;">Algorithmic Engine:</b>
                                    <span style="line-height: 1.5;">${data.mathEx}</span>
                                </div>
                            </div>
                        """
                        if (settings.enableNOAA) {
                            dashboardHtml += """
                            <div style="padding: 12px 15px; background: ${noaaBg}; font-size: 13px; color: #333; display: flex; align-items: center; gap: 15px;">
                                <div style="padding: 3px 10px; background: #fff; color: ${noaaColor}; border: 1px solid ${noaaColor}; font-weight: bold; border-radius: 12px; font-size: 10px; letter-spacing: 0.5px; text-transform: uppercase; white-space: nowrap;">
                                    NOAA: ${noaaStatus}
                                </div>
                                <div style="flex: 1;">
                                    <b>Official NWS Report:</b> ${noaaRaw}
                                </div>
                            </div>
                            """
                        }
                        dashboardHtml += "</div>"
                        paragraph dashboardHtml
                    }
                }
                
                // --- CSS Flexbox Layout for Chart, Table & Radar ---
                def visualWidgets = "<div style='display: flex; flex-wrap: wrap; gap: 15px; margin-top: 15px; align-items: stretch;'>"
                
                def dispMode = settings.historyDisplayMode ?: "Chart (Chart.js)"
                if (dispMode == "Chart (Chart.js)") {
                    visualWidgets += "<div style='flex: 1 1 48%; min-width: 350px; background: #fff; padding: 15px; border: 1px solid #ccc; border-radius: 4px; box-sizing: border-box;'>"
                    visualWidgets += renderChartHTML()
                    visualWidgets += "</div>"
                } else if (dispMode == "Data Table") {
                    visualWidgets += "<div style='flex: 1 1 48%; min-width: 350px; background: #fff; padding: 15px; border: 1px solid #ccc; border-radius: 4px; box-sizing: border-box;'>"
                    visualWidgets += renderTableHTML()
                    visualWidgets += "</div>"
                }
                
                if (settings.enableRadar != false && settings.enableInternet != false && location.latitude && location.longitude) {
                    visualWidgets += "<div style='flex: 1 1 48%; min-width: 350px; background: #fff; padding: 15px; border: 1px solid #ccc; border-radius: 4px; box-sizing: border-box;'>"
                    visualWidgets += renderRadarHTML()
                    visualWidgets += "</div>"
                }
                visualWidgets += "</div>"
                paragraph visualWidgets
                
                section("<b>Core Physics Data Stream</b>", hideable: true, hidden: true) {
                    def pTrend = state.pressureTrendStr ?: "Stable"
                    def tTrend = state.tempTrendStr ?: "Stable"
                    def wTrend = state.windTrendStr ?: "Stable"
                    def sTrend = state.spreadTrendStr ?: "Stable"
                    
                    def altNow = state.currentAltimeter
                    
                    def windDir = getFloat(sensorWindDir, ["windDirection", "winddir", "windDir"], "N/A")
                    def strikes = state.lightningHistory?.size() ?: 0
                    def gravWave = state.gravityWaveActive ? "<span style='color:#f0ad4e; font-weight:bold;'>repeated small swings</span>" : "none"
                    def rho = state.currentAirDensity ?: 1.225
                    def wbgt = state.currentWBGT ?: getFloat(sensorTemp, ["temperature"], 0.0)
                    
                    // Wind Load UI
                    def windLoadVal = state.currentWindLoad ?: 0.0
                    def windLoadUnit = isMetric() ? "Pascals (Pa)" : "lb/ft² (PSF)"
                    def loadColor = windLoadVal > 20.0 ? "red" : (windLoadVal > 10.0 ? "orange" : "#555")
                    
                    def leakWetStr = "DRY"
                    if (sensorLeak || sensorLeak2 || sensorLeak3) {
                        if (state.dewRejectionActive) leakWetStr = "<span style='color:orange; font-weight:bold;'>DEW/IGNORED</span>"
                        else if (state.stuckLeakActive) leakWetStr = "<span style='color:orange; font-weight:bold;'>STUCK/IGNORED</span>"
                        else if (state.leakWetStatus) leakWetStr = "<span style='color:blue; font-weight:bold;'>WET</span>"
                    }
                    
                    def vectorStr = state.lightningVectorStr ?: "N/A"
                    
                    def physicsDisplay = "<div style='padding: 12px; background: #f0f2f5; border-radius: 6px; font-size: 13px; border: 1px solid #dcdfe3; color: #555; line-height: 1.6;'>"
                    def psNow = readStationPressure()
                    physicsDisplay += "<b>Sea-level pressure (altimeter):</b> ${altNow != null ? String.format('%.2f', altNow as Double) + ' inHg' : 'n/a (no elevation yet)'}${psNow != null ? ' | <b>Station pressure:</b> ' + String.format('%.2f', psNow) + ' inHg' : ''} (${pTrend})<br>"
                    physicsDisplay += "<b>Temp Velocity:</b> ${tTrend} &nbsp;|&nbsp; <b>Squeeze Vel:</b> ${sTrend}<br>"
                    physicsDisplay += "<b>Wind:</b> ${wTrend} @ ${windDir}° &nbsp;|&nbsp; <b>Lightning (30m):</b> ${strikes} strikes (${vectorStr})<br>"
                    physicsDisplay += "<b>Wind Pressure (peak gust):</b> <span style='color:${loadColor}; font-weight:bold;'>${String.format('%.2f', windLoadVal)} ${windLoadUnit}</span><br>"
                    physicsDisplay += "<b>Air Density:</b> ${String.format('%.3f', rho)} kg/m³<br>"
                    physicsDisplay += "<b>Wet-Bulb Globe Temp (WBGT Proxy):</b> ${String.format('%.1f', wbgt)}°F<br>"
                    physicsDisplay += "<b>Pressure oscillation (info only):</b> ${gravWave}<br>"
                    physicsDisplay += "<b>Instant Leak Sensors:</b> ${leakWetStr}</div>"
                    paragraph physicsDisplay
                }
                
            } else {
                paragraph "<i>Primary sensors missing. Click Configuration below to assign devices.</i>"
            }
        }

        section("<b>📖 How This Works (Plain English)</b>", hideable: true, hidden: true) {
            paragraph """<div style='font-size:13px; color:#333; line-height:1.6;'>
            <b>The one-paragraph version:</b> Every 90 seconds or so, your weather station pushes fresh readings to this hub. Once a minute, this app looks at how those readings are <i>changing</i>, because storms announce themselves through change: pressure jumping or crashing, temperature suddenly dropping, wind gusting, lightning getting closer. Each of five hazard engines scores its own probability from 0-100%. Cross a threshold and your switches, sirens, and phones fire.<br><br>
            
            <b>⛈️ Thunderstorm:</b> Watches for the classic gust-front fingerprint: within about 30 minutes, pressure JUMPS, temperature CRASHES, and wind spikes. That combination is the cold outflow air a storm shoves ahead of itself, and it usually arrives minutes before the rain. Also counts lightning strikes in the last 30 minutes, checks whether strikes are getting closer or farther (approach speed and ETA), and fires instantly if gusts hit 58 mph, the official government threshold for a "severe" thunderstorm.<br><br>
            
            <b>🌪️ Tornado, 🌀 hurricane and tropical storm:</b> These come from the NWS, as their own notifications. A backyard station cannot see a tornado forming or a hurricane approaching; that takes radar and satellites. The 1.x local Tornado and Tropical scores fired on ordinary wind and pressure and were removed in 2.0.<br><br>
            <b>🌊 Flood:</b> Blends how hard it's raining right now, how much has fallen today, and how soggy the ground already was (your soil sensors). Saturated ground can't absorb more, so the same rain causes worse flooding. Sprinklers can't trigger this: soil moisture only counts when the rain gauge is ALSO seeing real rain, and the irrigation guard mutes everything during watering cycles.<br><br>
            
            <b>❄️ Freeze & Ice:</b> Below 32°F is a freeze, simple. The dangerous one is freezing rain: liquid precipitation falling while the "wet-bulb" temperature (what evaporation cools things to) is below freezing. That means ice glazing on contact, and it gets its own ICE ALARM routing. Routine freezes are only news when they are a surprise: once freezing nights are normal at your place (local log) or the NWS forecast already calls for a freeze tonight, routine freeze pushes and announcements are muted. Ice is never muted.<br><br>
            
            <b>🔥 Severe Heat:</b> Uses WBGT, the heat-stress measure the military and athletics use, built from temperature, humidity, sun load, and wind. It answers "how dangerous is it to be working outside in the sun," which plain temperature does not.<br><br>
            
            <b>🔥🌲 Fire Weather:</b> Dry fuel (little rain in 7 days) + thirsty air (high vapor pressure deficit, or RH ≤ 15%) + wind ≥ 25 mph. Those are the official Red Flag ingredients.<br><br>
            
            <b>Where the data comes from:</b> Everything above runs on YOUR sensors, locally. Optional internet feeds exist, all U.S. Government: official NWS alerts (every 5 min), a reference pressure from your nearest airport station (every 3 hours) used to auto-calibrate, that airport's TAF forecast, and the NWS point forecast (hourly, used only to know when a freeze is expected). All are controlled by the master switch in Configuration, every pull is logged with its URL, and the app is fully functional with the switch off.<br><br>
            
            <b>Why alerts don't flip-flop:</b> Escalation is instant (Clear→Warning→Alarm), but de-escalation waits out a hold timer so one calm minute mid-storm doesn't silence your sirens prematurely.
            </div>"""
        }

        section("<b>System Configuration</b>") {
            href(name: "configPageLink", page: "configPage", title: "▶ Base Hardware & BMS Engine Tuning", description: "Set up local sensors, NOAA Cloud Integration, calibration offsets, DEFCON Watchdog, and advanced physics modules.")
            href(name: "notificationPageLink", page: "notificationPage", title: "▶ Notification Center", description: "Every push, spoken announcement, siren and sound, one at a time: on/off, cooldowns, hold-off, daily caps, quiet hours, modes, devices, text.")
            href(name: "dnaConfigPageLink", page: "dnaConfigPage", title: "▶ Threat DNA Granular Mapping", description: "Engine on/off, probability thresholds, and Warning/Alarm/Ice switches for automations.")
        }
        
        section("<b>Child Device Integration</b>") {
            paragraph "<i>Create a single virtual device that exposes the entire Threat Matrix to your dashboards.</i>"
            if (app.id) {
                input "createDeviceBtn", "button", title: "➕ Create Severe Weather Information Device"
            }
        }
        
        if (app.id) {
            section("<b>Global Actions & Debugging</b>", hideable: true, hidden: true) {
                input "forceEvalBtn", "button", title: "⚙️ Force Matrix Evaluation"
                input "clearStateBtn", "button", title: "⚠ Reset Internal Matrix & Hardware"
                input "txtEnable", "bool", title: "Enable Description Text Logging", defaultValue: true
                input "debugEnable", "bool", title: "Enable Debug Logging", defaultValue: false
            }
        }
    }
}

def configPage() {
    return dynamicPage(name: "configPage", title: "<b>Base Hardware & BMS Engine Tuning</b>", install: false, uninstall: false) {
        
        section("<b>Dashboard UI Preferences</b>", hideable: true, hidden: true) {
            paragraph "<i>Customize how data is visualized on your main application page.</i>"
            input "historyDisplayMode", "enum", title: "24-Hour History Display", options: ["Chart (Chart.js)", "Data Table", "Hidden"], defaultValue: "Chart (Chart.js)", required: true, submitOnChange: true
            input "enableRadar", "bool", title: "Enable Live NOAA Radar Map", defaultValue: true, submitOnChange: true
            if (enableRadar) {
                input "radarZoom", "number", title: "Map Zoom Level (5 - 15)", defaultValue: 8, required: true
            }
        }
        
        section("<b>Primary Thermodynamic Sensors (Required)</b>") {
            input "sensorTemp", "capability.sensor", title: "Outdoor Temperature Sensor", required: true
            input "sensorHum", "capability.sensor", title: "Outdoor Humidity Sensor", required: true
            input "sensorPress", "capability.sensor", title: "Barometric Pressure Sensor", required: true
        }

        section("<b>Kinetic & Precipitation Sensors (Required for Full Matrix)</b>") {
            input "sensorWind", "capability.sensor", title: "Wind Speed / Gust Sensor", required: false
            input "sensorWindDir", "capability.sensor", title: "Wind Direction Sensor", required: false
            input "sensorLightning", "capability.sensor", title: "Lightning Detector", required: false
            input "sensorRain", "capability.sensor", title: "Rain Rate Sensor (in/hr or mm/hr)", required: false
            input "sensorRainDaily", "capability.sensor", title: "Daily Rain Accumulation Sensor", required: false
            input "sensorRainWeekly", "capability.sensor", title: "Weekly Rain Accumulation Sensor (Used for Fire Weather Fuel Proxy)", required: false
            input "sensorSoil", "capability.relativeHumidityMeasurement", title: "Soil Moisture Sensor(s) (Antecedent Wetness for Flood DNA)", required: false, multiple: true
            input "sensorLux", "capability.illuminanceMeasurement", title: "Solar Radiation / Lux Sensor (Used for WBGT)", required: false
        }
        
        section("<b>Instant 'First Drop' Sensors (Micro-Precipitation)</b>") {
            paragraph "<i>Map up to 3 standard Z-Wave/Zigbee leak sensors placed outside to bypass tipping-bucket delays. Provides instant reactive triggers for the Thunderstorm, Flood and Ice checks.</i>"
            input "sensorLeak", "capability.waterSensor", title: "Instant Rain Sensor 1", required: false
            input "sensorLeak2", "capability.waterSensor", title: "Instant Rain Sensor 2", required: false
            input "sensorLeak3", "capability.waterSensor", title: "Instant Rain Sensor 3", required: false
            input "leakSensorRequiredCount", "enum", title: "Number of Instant Sensors required to trigger", options: ["1", "2", "3"], defaultValue: "1", required: true
            input "enableDewRejection", "bool", title: "Dew & Frost Rejection (First Drop Filter)", defaultValue: true, description: "Ignores the instant leak sensor on cold/calm mornings (Dew Point Spread < 3°, Calm Wind, Low Lux) to prevent false alerts."
            input "stuckLeakTimeout", "number", title: "Stuck Sensor Timeout (Minutes)", required: true, defaultValue: 60
        }
        
        section("<b>🌐 Internet Master Switch & Data Sources</b>") {
            paragraph "<i><b>Plain English:</b> This app can run 100% locally on your own sensors. Flip this OFF and it will make ZERO internet requests; all detection still works. Flip it ON to add optional government data feeds, all listed below so you always know exactly what is pulled and from where.</i>"
            input "enableInternet", "bool", title: "Allow Internet Data (Master Switch)", defaultValue: true, submitOnChange: true
            if (settings.enableInternet != false) {
                paragraph """<div style='font-size:12px; background:#f0f7ff; border:1px solid #b8d4f0; border-radius:6px; padding:10px; color:#333;'>
                <b>Everything this app will ever pull from the internet:</b><br>
                1. <b>Official NWS Alerts</b> (Tornado Warnings, etc.) from <code>api.weather.gov/alerts</code>, every 5 minutes. U.S. Government source.<br>
                2. <b>Auto-Calibration reference pressure</b> from your nearest official NWS/airport station via <code>api.weather.gov/stations</code>, every 3 hours. With a station-pressure sensor it is used to learn your elevation once, not to shift your readings. U.S. Government source.<br>
                3. <b>Airport TAF forecast</b> (rides along with Feed 2) from <code>aviationweather.gov/api/data/taf</code>, every 3 hours. U.S. Government (NOAA) source.<br>
                4. <b>NWS point forecast</b> (12-hour periods, for forecast freeze lows; only while Expected-Freeze Auto-Mute and its forecast option are on) from <code>api.weather.gov/points</code> and <code>api.weather.gov/gridpoints</code>, every hour. U.S. Government source.<br>
                That is the complete list. No third-party services, no analytics, nothing else. Every single pull is written to the Action Log with its full URL and the values received. Everything is found from your hub's coordinates (Settings > Hub Details), so after a move it re-discovers itself; outside NWS coverage these feeds stand down and the app runs on your sensors alone.</div>"""
                input "enableNOAA", "bool", title: "Feed 1: NWS / NOAA Official Alerts", defaultValue: true, submitOnChange: true
                if (enableNOAA) {
                    input "noaaTriggersHardware", "bool", title: "Let NWS products also drive your hazard states and switches", defaultValue: false, description: "<i>Plain English: NWS products always get their own notifications (Notification Center, 'Official NWS products'). With this ON, an NWS warning for a hazard this app tracks (e.g. Severe Thunderstorm Warning, Flood Warning, Freeze Warning) also turns that hazard's WARNING/ALARM switches on even if your station has not seen it yet. OFF = your switches follow your own sensors only.</i>"
                }
                input "enableAutoCal", "bool", title: "Feed 2: Auto-Calibrate Pressure from Nearest Official Station", defaultValue: true, submitOnChange: true
                if (settings.enableAutoCal) {
                    input "calStationOverride", "text", title: "Station Override (optional, e.g. KBIL)", required: false, description: "<i>Plain English: leave blank and the app finds the closest official weather station to your hub's coordinates automatically. Enter a 4-letter airport code only if you want a specific one.</i>"
                    input "tafStationOverride", "text", title: "TAF Station Override (optional, e.g. KGTF)", required: false, description: "<i>Plain English: Feed 3, the airport's official short-term FORECAST (TAF), rides along with calibration pulls. It gives the engines a heads-up on forecast thunderstorms, freezing rain, and strong gusts, worth up to 10% probability, never enough to alert alone. Small automated stations often have no TAF; if the log says so, enter your nearest big airport's code here.</i>"
                    if (state.lastCalPull) {
                        def lp = state.lastCalPull
                        paragraph "<div style='font-size:12px; color:#2c7a2c;'>✔ Last calibration pull: <b>${lp.station}</b> reported <b>${lp.ref} inHg</b>, your station read <b>${lp.raw} inHg</b>, so the offset <b>${lp.offset > 0 ? '+' : ''}${lp.offset} inHg</b> was applied automatically (${new Date((long)lp.time).format('MM/dd hh:mm a', location.timeZone)}).</div>"
                    }
                    if (app.id) input "calibrateNowBtn", "button", title: "📡 Calibrate Now"
                }
            } else {
                paragraph "<div style='font-size:12px; background:#fff8f0; border:1px solid #f0d0b0; border-radius:6px; padding:10px; color:#333;'><b>🔒 LOCAL-ONLY MODE.</b> Zero internet requests will be made. NWS alerts and auto-calibration are disabled. Set your pressure offset manually below so sea-level math stays accurate.</div>"
            }
        }
        
        section("<b>Advanced Physics & Calibration Modules</b>") {
            paragraph "<i>These modules separate a hobbyist weather station from a professional meteorological instrument.</i>"
            input "pressureSource", "enum", title: "Pressure reading to use", options: ["auto": "Station (absolute) pressure if the sensor has it (recommended)", "relative": "Relative / sea-level pressure only"], defaultValue: "auto", description: "<i>Plain English: station pressure is what the barometer really measures. The app turns it into a sea-level value itself with the NWS altimeter formula and your elevation, and measures pressure rises/falls on the raw reading, so calibration never shows up as a fake pressure jump.</i>"
            input "stationElevationFt", "decimal", title: "Station elevation (feet, optional)", required: false, description: "<i>Plain English: leave blank and the app learns it by comparing your station pressure with the nearest airport (median of recent pulls), or uses the NWS forecast grid elevation. Enter it only if you know it. ${siteElevationM() != null ? 'Now using <b>' + Math.round(siteElevationM() * 3.28084) + ' ft</b> (' + siteElevationSource() + ').' : 'Not known yet.'}</i>"
            input "pressOffset", "decimal", title: "Barometric MSLP Offset (inHg) - Manual, relative-pressure sensors only", defaultValue: 0.0, description: "<i>Plain English: only used when your sensor has no station (absolute) pressure. Look up your nearest airport METAR (aviationweather.gov), find the 'A' group, e.g. <b>A2992</b> = 29.92 inHg, and subtract your station's relative pressure. A manual value here overrides auto-calibration.</i>"
            input "windUnit", "enum", title: "Wind speed unit your sensor reports", options: ["mph": "mph", "kmh": "km/h", "ms": "m/s", "kn": "knots"], required: false, description: "<i>Blank = mph on a °F hub, km/h on a °C hub. All thresholds in this app are in mph.</i>"
            input "rainUnit", "enum", title: "Rain unit your sensor reports", options: ["in": "inches", "mm": "millimetres"], required: false, description: "<i>Blank = inches on a °F hub, mm on a °C hub. Applies to rain rate, daily and weekly totals.</i>"
            input "distUnit", "enum", title: "Lightning distance unit your sensor reports", options: ["mi": "miles", "km": "kilometres"], required: false, description: "<i>Blank = miles on a °F hub, km on a °C hub.</i>"
            
            input "irrigationSwitch", "capability.switch", title: "Irrigation / Sprinkler System Switch (Optional Guard)", required: false, description: "<i>Plain English: map your sprinkler controller's switch here. While it's on (and for 60 minutes after), the app ignores soil moisture spikes and 'first drop' wetness so a sprinkler cycle can never look like a storm or flood.</i>"
            
            input "seasonalFreezeAuto", "bool", title: "Expected-Freeze Auto-Mute (local climate + NWS forecast)", defaultValue: true, submitOnChange: true, description: "<i>Plain English: freeze alerts matter when a freeze is a SURPRISE: the first hard freeze of autumn (winterize sprinklers, drain hoses) or an unexpected late-spring freeze (protect plants). When freezing nights are already normal, or the forecast already says it will freeze tonight, 'it froze again' is not news. Two signals decide 'expected': (1) LOCAL: your own station's daily lows (enough freezing nights in the last 7), and (2) FORECAST: the NWS point forecast for your hub's location calls for a low at or near freezing in the next 18 hours. While a freeze is expected, routine freeze pushes, TTS, sirens and sounds are muted. Your freeze switches still fire for automations, and ICE STORM alerts are NEVER muted since freezing rain is dangerous in any season.</i>"
            if (settings.seasonalFreezeAuto != false) {
                input "freezeLocalDays", "number", title: "Local: freezing nights out of the last 7 that mean 'freeze season' (1-7)", defaultValue: 4, range: "1..7", description: "<i>Plain English: once this many of the last 7 days dipped to 32°F or below at YOUR station, freezes count as expected. Lower = mutes sooner in autumn. Default 4.</i>"
                if (settings.enableInternet != false) {
                    input "enableFreezeForecast", "bool", title: "Forecast: use NWS forecast lows (Feed 4)", defaultValue: true, submitOnChange: true, description: "<i>Plain English: if the National Weather Service forecast for your hub's coordinates calls for a freeze in the next 18 hours, that freeze is expected and won't ping you. Hourly pull from api.weather.gov. Ignored if the last good forecast is over 12 hours old.</i>"
                    if (settings.enableFreezeForecast != false) {
                        input "freezeForecastMargin", "decimal", title: "Forecast margin (°F above 32 that still counts as 'freeze expected')", defaultValue: 2.0, range: "0..10", description: "<i>Plain English: your yard can run colder than the forecast grid. With 2, a forecast low of 34°F or below counts as an expected freeze. Default 2.</i>"
                    }
                }
                input "freezeAlertFirstOfSeason", "bool", title: "Still alert the first freeze of the season", defaultValue: true, description: "<i>Plain English: if your station hasn't logged a single freezing day in the last 14, the next freeze alerts once even if it was forecast (time to winterize). Off = mute every expected freeze.</i>"
            }
            input "freezeHysteresis", "decimal", title: "Freeze ALARM clear margin (°F above 32)", defaultValue: 2.0, range: "0..10", description: "<i>Plain English: once a freeze ALARM is on, it stays on until the temperature climbs this far above 32°F. Stops a reading that hovers at 32-33°F from flipping ALARM/WARNING all afternoon. Default 2 (clears at 34°F).</i>"
            
            // [SCIENCE: WBGT]
            input "enableWBGT", "bool", title: "Enable Wet-Bulb Globe Temperature (WBGT)", defaultValue: true, description: "Calculates a proxy utilizing Temp, Hum, Wind, and Solar Radiation to measure true heat stress in direct sunlight."
            
            // [SCIENCE: KINETIC AIR DENSITY & STRUCTURAL LOAD]
            input "enableKineticWind", "bool", title: "Enable Air Density & Wind Pressure", defaultValue: true, description: "Air density (kg/m³) from temperature, humidity and station pressure, and the dynamic wind pressure of the peak gust (lb/ft² or Pa). Shown for information and used to weight storm wind."
            
            // [SCIENCE: STORM VECTORING]
            input "enableStormVectoring", "bool", title: "Enable Storm Intercept Vectoring (Approach Velocity)", defaultValue: true, description: "Tracks the distance of lightning strikes over time to calculate approach velocity (MPH) and Estimated Time of Arrival (ETA). Filters out random residential sensor 'ghost' strikes via historical averaging."
            
            // [SCIENCE: GRAVITY WAVES]
            input "enableGravityWave", "bool", title: "Show pressure oscillation indicator", defaultValue: true, description: "Flags repeated small pressure swings on the dashboard. Information only since 2.0: a single backyard barometer cannot identify gravity waves or supercells, so it no longer adds to any score."
            
            // [SCIENCE: DRY MICROBURST]
            input "enableDryMicroburst", "bool", title: "Enable Dry Microburst / Virga Detection", defaultValue: true, description: "Looks for massive Dew Point spreads combined with sudden evaporative cooling (Temp crashes) and Wind Spikes to detect straight-line winds."
            
            paragraph "<i>Sensor dropouts (2.0): when a sensor goes stale or reads an impossible value, only the hazards that need it pause, and they keep their current state (an active alarm is not cleared by a dead sensor). The Sensor offline notification tells you.</i>"
            
            input "enableHardwareFilter", "bool", title: "Enable Hardware Anomaly Rejection", defaultValue: true
            input "enableThermalSmoothing", "bool", title: "Thermal Smoothing (Sun-Spike Protection)", defaultValue: true
            input "staleDataTimeout", "number", title: "Stale Data Timeout (Minutes)", defaultValue: 30, description: "<i>Per sensor (2.0): a device with no update for this long is treated as offline. Only the hazards that need it pause.</i>"
        }
        
        section("<b>DEFCON Watchdog Polling</b>") {
            input "enablePolling", "bool", title: "Enable Standard Active Device Polling", defaultValue: true, submitOnChange: true
            if (enablePolling) {
                input "pollInterval", "number", title: "Standard Polling Interval (Minutes)", required: true, defaultValue: 5
            }
            input "enableDefcon", "bool", title: "Enable DEFCON Watchdog Overdrive", defaultValue: true, submitOnChange: true
            if (enableDefcon) {
                input "defconThresh", "number", title: "DEFCON Activation Threshold (Probability %)", required: true, defaultValue: 25
                input "defconMins", "enum", title: "DEFCON Overdrive Polling Rate (Minutes)", required: true, defaultValue: "1", options: ["1", "2", "3", "4", "5"]
            }
        }
        
        section("<b>Output Hardware Master Devices</b>") {
            input "audioDevices", "capability.speechSynthesis", title: "TTS Audio Devices (e.g., Sonos/Echo)", multiple: true, required: false
            input "soundDevices", "capability.audioNotification", title: "Sound File/MP3 Players", multiple: true, required: false
            input "sirenDevices", "capability.alarm", title: "Sirens & Strobes (e.g., Zooz Siren)", multiple: true, required: false
            input "notifyDevices", "capability.notification", title: "Push Notification Devices", multiple: true, required: false
            input "audioVolume", "number", title: "Master Announcement Volume Level (%)", defaultValue: 65, range: "1..100"
            
            if (audioDevices || soundDevices) {
                href(name: "speakerMappingPageLink", page: "speakerMappingPage", title: "▶ Smart Room Presence Audio Routing", description: "Map your speakers to motion sensors to avoid blasting audio in empty rooms.")
            }
        }
    }
}

def speakerMappingPage() {
    return dynamicPage(name: "speakerMappingPage", title: "<b>Smart Room Presence Audio Routing</b>", install: false, uninstall: false) {
        section() { paragraph "<i>Assign one or multiple motion sensors to your speakers.</i>" }
        def allSpeakers = []
        if (settings.audioDevices) allSpeakers += settings.audioDevices
        if (settings.soundDevices) allSpeakers += settings.soundDevices
        allSpeakers = allSpeakers.unique { it.id }
        
        if (allSpeakers) {
            allSpeakers.each { speaker ->
                section("<b>Routing for: ${speaker.displayName}</b>") {
                    input "isAlwaysOn_${speaker.id}", "bool", title: "🎙️ Make this a Master Always-Announce Speaker", defaultValue: false, submitOnChange: true
                    if (!settings["isAlwaysOn_${speaker.id}"]) {
                        input "motionMap_${speaker.id}", "capability.motionSensor", title: "🚶 Required Motion Sensors (Active within 5 mins)", required: false, multiple: true
                        input "nightSwitch_${speaker.id}", "capability.switch", title: "🌙 Room 'Good Night' Override Switch", required: false
                    }
                }
            }
        }
    }
}

def dnaConfigPage() {
    return dynamicPage(name: "dnaConfigPage", title: "<b>Threat DNA Granular Mapping</b>", install: false, uninstall: false) {
        
        section() {
            paragraph "<div style='font-size:12px; color:#555; background:#fff8e6; border-radius:4px; padding:8px;'><b>2.0:</b> the local Tornado and Tropical engines are gone. A backyard station cannot see a tornado forming or a hurricane approaching (that takes radar and satellites), and their scores fired on ordinary wind and pressure. Tornado, hurricane and tropical storm products now come from the NWS as their own notifications (Notification Center, 'Official NWS products'). Switches you mapped to the old Tornado/Tropical engines were turned off and are no longer used.</div>"
        }
        def dnas = [
            [id: "Thunderstorm", icon: "⛈️", wTTS: "Warning. Elevated probability of severe thunderstorms.", aTTS: "Critical Alert. Severe thunderstorm conditions actively detected.", plain: "Fires on the gust-front fingerprint (pressure jump + temp crash + wind spike), nearby lightning ramping up or approaching, 58+ mph gusts, or the first drops hitting the piezo rain head. This is your best engine for pop-up summer storms."],
            [id: "Flood", icon: "🌊", wTTS: "Warning. Elevated probability of flash flood conditions.", aTTS: "Critical Alert. Flash flood conditions detected locally.", plain: "Fires on heavy rain rates, big daily accumulation, or normal rain landing on already-saturated soil. Sprinklers cannot trigger it: soil moisture only counts alongside real gauge rain, and the irrigation guard mutes it during watering."],
            [id: "Freeze", icon: "❄️", wTTS: "Warning. Rapid trajectory towards freeze detected.", aTTS: "Alert. Freeze conditions actively occurring.", plain: "WARNING when temperature is on a trajectory to hit 32°F within hours (pipe/plant protection lead time). ALARM at or below freezing. Freezing rain gets its own ICE ALARM: liquid rain + sub-freezing wet-bulb = ice glazing everything on contact. Because a backyard sensor can't tell freezing rain from wet snow, ICE ALARM needs the airport TAF, an NWS ice product or the NWS forecast to agree; otherwise it is an ICE WARNING. Routine freezes are muted while a freeze is expected; ice never is."],
            [id: "SevereHeat", icon: "🔥", wTTS: "Warning. High heat index detected. Caution advised.", aTTS: "Critical Alert. Severe heat index conditions detected locally.", plain: "Based on WBGT, the heat-stress standard used by the military and sports medicine (temp + humidity + sun + wind). Answers 'is it dangerous to work outside in the sun right now,' which plain temperature can't."],
            [id: "FireWeather", icon: "🔥🌲", wTTS: "Warning. Elevated fire weather conditions.", aTTS: "Critical Alert. Extreme Red Flag fire weather actively detected. No outdoor sparks.", plain: "The official Red Flag recipe: bone-dry fuel (little rain in 7 days), thirsty air (very low humidity or high vapor pressure deficit), and wind 25+ mph. When this alarms, any spark spreads fast."]
        ]
        
        dnas.each { dna ->
            def id = dna.id
            def lName = id.toLowerCase()
            def prettyName = id == 'SevereHeat' ? 'Severe Heat' : (id == 'FireWeather' ? 'Fire Weather' : id)
            section("<b>${dna.icon} ${prettyName} DNA Configuration</b>", hideable: true, hidden: true) {
                paragraph "<div style='font-size:12px; color:#555; background:#f7f7f7; border-radius:4px; padding:8px;'><b>What this engine does:</b> ${dna.plain}</div>"
                input "enable${id}", "bool", title: "Enable ${id} Engine", defaultValue: true, submitOnChange: true
                if (settings["enable${id}"] != false) {
                    input "${lName}WarnThresh", "number", title: "WARNING Probability Threshold (%)", defaultValue: 50, required: true, description: "<i>Plain English: probability score (0-100) at which the yellow WARNING tier fires. Lower = earlier but more false alerts. Default 50.</i>"
                    input "${lName}AlarmThresh", "number", title: "ALARM Probability Threshold (%)", defaultValue: 80, required: true, description: "<i>Plain English: score at which the red ALARM tier fires: sirens, urgent pushes. Default 80.</i>"
                    input "switch${id}Warn", "capability.switch", title: "Map WARNING Virtual Switch", required: false, description: "<i>Turns on while a WARNING is active. Point Rule Machine at it for custom automations (close blinds, pause irrigation, etc.).</i>"
                    input "switch${id}Alarm", "capability.switch", title: "Map ALARM Virtual Switch", required: false, description: "<i>Turns on while an ALARM is active.</i>"
                    
                    if (id == "Freeze") {
                        input "switchIceAlarm", "capability.switch", title: "Map ICE STORM / FREEZING RAIN Virtual Switch", required: false, description: "<i>Plain English: freezing rain is a different emergency than a dry hard freeze (glazed roads and power lines vs frozen pipes), so it routes to its own switch when precipitation falls into sub-freezing air.</i>"
                    }
                    
                    paragraph "<i>Pushes, announcements, sirens and sounds for this hazard are set in <b>Notification Center</b> (main page).</i>"
                }
            }
        }
        section("<b>System-Wide Debounce</b>") {
            input "debounceMins", "number", title: "Safety Hardware Hold Time (Minutes)", required: true, defaultValue: 15
        }
    }
}

// ==============================================================================
// INTERNAL LOGIC ENGINE (ULTIMATE EDITION)
// ==============================================================================

def installed() { logInfo("Installed"); initialize() }
def updated() { logInfo("Updated"); unsubscribe(); initialize() }

def initialize() {
    if (!state.actionHistory) state.actionHistory = []
    
    // Preserve the existing matrix across settings saves. Wiping it caused
    // duplicate alerts: an active WARNING was reset to Clear on Done, then
    // re-detected and re-pushed seconds later. Event history and in-flight
    // forensics survive now; only genuinely new hazards get fresh entries.
    def m = state.threatMatrix ?: [:]
    hazardIds().each { haz ->
        if (!m[haz]) m[haz] = [threat: 0, prob: 0, conf: 0, state: "Clear", howWhy: "Initializing...", mathEx: "Awaiting calculation cycle...", lastTrig: 0, history: [], currentEvent: null]
    }
    state.threatMatrix = m
    
    state.watchdogActive = false
    state.cloudOffline = false
    state.survivalModeActive = false
    // Global event tracking now survives settings saves, matching the threat
    // matrix. Wiping these mid-event falsely "concluded" forensics on every
    // Done press. They initialize only when genuinely absent.
    if (state.globalThreatActive == null) state.globalThreatActive = false
    if (state.globalAllClearPending == null) state.globalAllClearPending = false
    state.emiActive = false
    state.noaaFailCount = 0
    migrateTo2()
    if (!state.noaaGlobalAlerts) state.noaaGlobalAlerts = "Initializing broadcast listener..."
    
    if (!state.lastHeartbeat) state.lastHeartbeat = now()
    if (!state.smoothedTemp) state.smoothedTemp = null
    
    if (!state.pressureHistory) state.pressureHistory = []
    if (!state.tempHistory) state.tempHistory = []
    if (!state.spreadHistory) state.spreadHistory = []
    if (!state.windHistory) state.windHistory = []
    if (!state.windDirHistory) state.windDirHistory = []
    if (!state.lightningHistory) state.lightningHistory = []
    if (!state.probHistory) state.probHistory = []
    if (!state.sevenDayRain) state.sevenDayRain = []
    
    if (!state.currentDayRain) state.currentDayRain = 0.0
    if (!state.currentDateStr) state.currentDateStr = new Date(now()).format("yyyy-MM-dd", location.timeZone)
    
    if (!state.eventActive) state.eventActive = false
    if (!state.activeEventStats) state.activeEventStats = [:]
    
    subscribeMulti(sensorTemp, tempAttrs(), "tempHandler")
    subscribeMulti(sensorHum, ["humidity"], "stdHandler")
    subscribeMulti(sensorPress, (pressRelAttrs() + pressAbsAttrs()).unique(), "pressureHandler")
    subscribeMulti(sensorWind, ["windSpeed", "windspeedmph", "wind"], "windHandler")
    subscribeMulti(sensorWindDir, ["windDirection", "winddir", "windDir"], "windDirHandler")
    subscribeMulti(sensorLightning, lightningHasCount() ? ["lightningCount"] : ["lightningDistance", "distance"], "lightningHandler")
    subscribeMulti(sensorRain, ["rainRate", "hourlyrainin", "precipRate", "hourlyRain"], "stdHandler")
    subscribeMulti(sensorRain, ["raining"], "stdHandler")                    // WS90 piezo instant rain flag (native first-drop)
    subscribeMulti(sensorWind, ["windGust", "windgustmph", "gust"], "stdHandler") // Gusts trigger evaluation; leading indicator of downbursts
    if (sensorSoil) subscribe(sensorSoil, "humidity", "stdHandler")          // Soil moisture (antecedent wetness for Flood DNA)
    subscribeMulti(sensorRainDaily, ["rainDaily", "dailyrainin", "water", "dailyWater"], "stdHandler")
    subscribeMulti(sensorLux, ["illuminance", "solarradiation", "solarRadiation"], "luxHandler")
    
    [sensorLeak, sensorLeak2, sensorLeak3].each { dev ->
        if (dev) subscribe(dev, "water", "stdHandler")
    }
    
    def motionSensorsToSub = []
    settings.each { key, val ->
        if (key.startsWith("motionMap_") && val) {
            if (val instanceof List) motionSensorsToSub.addAll(val)
            else motionSensorsToSub << val
        }
    }
    if (motionSensorsToSub) {
        motionSensorsToSub = motionSensorsToSub.unique { it.id }
        subscribe(motionSensorsToSub, "motion.active", "motionActiveHandler")
    }
    
    unschedule()
    if (enablePolling && pollInterval) {
        def safeInterval = Math.max(1, Math.min(59, pollInterval.toInteger()))
        schedule("0 */${safeInterval} * ? * *", "standardPoll")
    }
    
    if (settings.enableInternet != false && settings.enableNOAA) {
        runEvery5Minutes("pollNOAA")
        pollNOAA()
    }
    
    if (settings.enableInternet != false && settings.enableAutoCal) {
        runEvery3Hours("pollAutoCal")
        pollAutoCal()
    }

    if (freezeForecastEnabled()) {
        runEvery1Hour("pollForecast")
        runIn(20, "pollForecast")  // after the auto-cal points lookup, not on top of it
    }

    // === SENSOR PROFILER ===
    // Hubitat exposes each device's driver name (typeName). We use it to
    // identify the sensor family and seed known hardware characteristics,
    // then refine empirically from the live data stream (observed minimum
    // pressure step = true resolution). This is how the app auto-adapts to
    // whatever hardware is mapped to it.
    def profile = [:]
    [temp: sensorTemp, press: sensorPress, wind: sensorWind, rain: sensorRain, lightning: sensorLightning].each { k, dev ->
        if (dev) { try { profile[k] = dev.typeName } catch (e) { profile[k] = "unknown" } }
    }
    state.sensorProfile = profile
    def allTypes = profile.values().join(" ").toLowerCase()
    if (allTypes.contains("ecowitt")) {
        if (state.pressResolution == null) state.pressResolution = 0.01  // Ecowitt barometer step (inHg)
        logAction("🔎 SENSOR PROFILE: Ecowitt hardware detected (${profile}). Seeded pressure resolution 0.01 inHg; will refine from observed data. Noise gates scale automatically.")
    }
    
    if (settings.enableInternet == false) {
        logAction("🔒 LOCAL-ONLY MODE: Master internet switch is OFF. This app will make zero internet requests. All detection runs on local sensors only.")
    }
    
    recordPressure()
    runEvery1Minute("evaluateMatrix") 
    logAction("Advanced Severe Weather Detector 2.0 initialized.")
    evaluateMatrix()
}

// One-time upgrade from 1.x (schema 2). Runs on the first Done/initialize.
def migrateTo2() {
    if ((state.schema ?: 1) >= 2) return
    def m = state.threatMatrix ?: [:]
    ["Tornado", "Tropical"].each { haz ->
        m.remove(haz)
        safeOff(settings["switch${haz}Warn"]); safeOff(settings["switch${haz}Alarm"])
    }
    state.threatMatrix = m
    // 1.x stored pressure with the calibration offset baked in; restart the
    // series so the first 2.0 readings are not read as a pressure crash.
    state.pressureHistory = []
    state.remove("pressRaw3")
    state.remove("pressSeriesKind")
    state.remove("noaaAlertsMap")
    state.remove("lastLightningCount")   // next count is a baseline, not strikes
    state.remove("mslpWarned")
    // A 1.0.0 Notification Center page saves its shown default ("any") on
    // Done. Drop those so 2.0's "local sensors" default applies and an NWS
    // product does not push twice (hazard row + NWS row).
    settings.findAll { k, v -> k.toString() ==~ /n_[A-Za-z]+_source/ && v == "any" }.keySet().toList().each { k ->
        try { app.removeSetting(k) } catch (e) {}
    }
    state.schema = 2
    logAction("⬆ UPGRADED to 2.0: Tornado/Tropical local engines removed (now NWS products), pressure history restarted on the new pressure series.")
}

// === BUTTON HANDLER ===
void appButtonHandler(btn) {
    if (btn == "refreshDashboardBtn") return
    if (btn == "createDeviceBtn") { createChildDevice(); return }
    if (btn == "testNotifyBtn") { sendNotification("🔔 Test from ${app.label}: notifications are working."); notifyLog("SENT test push"); return }
    if (btn == "calibrateNowBtn") {
        logAction("MANUAL: On-demand calibration pull requested.")
        pollAutoCal()
        return
    }
    if (btn == "forceEvalBtn") {
        logAction("MANUAL OVERRIDE: Forcing matrix evaluation.")
        evaluateMatrix()
    }
    if (btn == "clearStateBtn") {
        logAction("EMERGENCY RESET: Purging matrix history, records, and internal states.")
        
        // Nuke the arrays from orbit
        state.remove("pressureHistory")
        state.remove("tempHistory")
        state.remove("spreadHistory")
        state.remove("windHistory")
        state.remove("windDirHistory")
        state.remove("lightningHistory")
        state.remove("probHistory")
        state.remove("actionHistory")
        
        // The threat matrix now SURVIVES normal settings saves (prevents
        // duplicate alerts), so this button is the one place it fully resets.
        state.remove("threatMatrix")
        
        // Calibration, learning, and advisory state
        state.remove("calStationId")
        state.remove("calStationName")
        state.remove("calStationFailCount")
        state.remove("lastStationDiscovery")
        state.remove("autoPressOffset")
        state.remove("lastCalPull")
        state.remove("metarExtras")
        state.remove("tafAdvisory")
        state.remove("pressRaw3")
        state.remove("pressResolution")
        state.remove("freezeDayLog")
        state.remove("freezeLogDate")
        state.remove("todayMinTemp")
        state.remove("establishedWinter")
        state.remove("freezeExpected")
        state.remove("notifyLog")
        state.remove("notifyLast")
        state.remove("notifyDay")
        state.remove("famAlarmTs")
        state.remove("famWarnTs")
        state.remove("pendingNotify")
        state.remove("globalEventAlerted")
        state.remove("freezeExpectedReason")
        state.remove("freezeForecast")
        state.remove("forecastUrl")
        state.remove("forecastUrlTime")
        state.remove("forecastFailCount")
        state.remove("lastLightningCount")
        state.remove("lastEngineError")
        ["nwsAlerts", "nwsNotified", "lastNwsGoodPoll", "nwsCoverage", "nwsCoverageCheck", "elevSamples", "elevLearnedM", "gridElevationM",
         "sensorHealth", "sensorDown", "devSeen", "pressSeriesKind", "currentAltimeter", "pressureMode", "siteLat", "siteLon", "noaaAlertsMap"].each { state.remove(it) }
        
        state.remove("lastEventReport")
        state.remove("activeEventStats")
        state.eventActive = false
        state.globalThreatActive = false
        state.globalAllClearPending = false
        state.emiActive = false
        state.watchdogActive = false
        state.survivalModeActive = false
        
        // Re-initialize the clean arrays
        state.pressureHistory = []
        state.tempHistory = []
        state.spreadHistory = []
        state.windHistory = []
        state.windDirHistory = []
        state.lightningHistory = []
        state.probHistory = []
        
        def m = [:]
        hazardIds().each { haz ->
            m[haz] = [threat: 0, prob: 0, conf: 0, state: "Clear", howWhy: "System Reset...", mathEx: "Awaiting next cycle...", lastTrig: now(), history: [], currentEvent: null]
        }
        state.threatMatrix = m
        
        evaluateMatrix()
    }
}

// === UNIT DETECTION HELPER ===
def isMetric() {
    return location?.temperatureScale == "C"
}

// === EFFECTIVE PRESSURE OFFSET ===
// Manual offset wins. If auto-calibration is on and has a value, use it.
def getPressOffset() {
    def manual = settings.pressOffset ?: 0.0
    if (manual != 0.0) return manual
    if (settings.enableInternet != false && settings.enableAutoCal && state.autoPressOffset != null) return state.autoPressOffset
    return 0.0
}

// === IRRIGATION GUARD ===
// True while the mapped irrigation switch is on, or within 60 min after it
// turned off. Used to suppress soil-moisture and first-drop credits so a
// sprinkler cycle can't masquerade as precipitation.
def irrigationActive() {
    def sw = settings.irrigationSwitch
    if (!sw) return false
    if (sw.currentValue("switch") == "on") { state.irrigationLastOn = now(); return true }
    def lastOn = state.irrigationLastOn ?: 0
    return (now() - lastOn) < 3600000
}

def subscribeMulti(device, attrs, handler) {
    if (!device) return
    attrs.each { attr -> subscribe(device, attr, handler) }
}

def getFloat(device, attrs, fallbackStr = null) {
    if (!device) return fallbackStr
    for (attr in attrs) {
        def val = device.currentValue(attr)
        if (val != null) {
            try { return val.toString().replaceAll("[^\\d.-]", "").toFloat() } catch (e) {}
        }
    }
    return fallbackStr
}

def standardPoll() { if (!state.watchdogActive) pollSensors() }
def watchdogPoll() {
    if (state.watchdogActive) {
        pollSensors()
        def mins = (settings.defconMins ?: 1).toInteger()
        runIn(mins * 60, "watchdogPoll")
    }
}

def pollSensors() {
    def refreshable = 0
    [sensorTemp, sensorHum, sensorPress, sensorRain, sensorWind, sensorWindDir, sensorLightning, sensorLux].each { dev ->
        if (dev && dev.hasCommand("refresh")) { refreshable++; try { dev.refresh() } catch (e) {} }
    }
    // Push-based drivers (e.g., Ecowitt) have no refresh(). Polling them does
    // nothing; data cadence is set by the GATEWAY's upload interval. Tell the
    // user once instead of letting DEFCON "overdrive" be a placebo.
    if (refreshable == 0 && !state.pollPlaceboWarned) {
        logAction("⚠ POLLING NO-OP: None of your sensors support refresh(). They are push-based; set the gateway's custom-server upload interval (WSView Plus / gateway web UI) as low as it allows. DEFCON overdrive cannot speed up push drivers.")
        state.pollPlaceboWarned = true
    }
}

// === ASYNC NOAA CLOUD POLLING ===
def pollNOAA() {
    if (settings.enableInternet == false) return
    if (settings.enableNOAA != true) return
    if (!nwsCovered()) return
    if (state.cloudOffline && state.cloudRetryTime && now() < state.cloudRetryTime) return 
    state.lastNoaaPollTime = now()

    def lat = location.latitude
    def lon = location.longitude
    if (!lat || !lon) return

    def params = [
        uri: "https://api.weather.gov/alerts/active?point=${lat},${lon}",
        timeout: 10,
        headers: ["User-Agent": "Hubitat-AdvancedSevereWeatherApp/3.0", "Accept": "application/geo+json"]
    ]
    try { asynchttpGet("noaaResponseHandler", params) } catch (e) { log.error "Async HTTP Get failed: ${e}" }
}

def noaaResponseHandler(response, data) {
    if (response.hasError()) {
        state.noaaFailCount = (state.noaaFailCount ?: 0) + 1
        if (state.noaaFailCount >= 3) {
            state.cloudOffline = true
            state.cloudRetryTime = now() + 1800000 
        }
        return
    }
    
    state.cloudOffline = false
    state.noaaFailCount = 0
    def json = parseJsonRobust(response)
    if (json == null) { logAction("⚠ Response could not be parsed; skipping this cycle."); return }
    
    // 2.0: every product is matched by its EXACT NWS event name (table in
    // nwsProducts()), keeps its own expiry, and is re-checked against the
    // clock at every evaluation. A failed poll can no longer leave an expired
    // warning latched, and an advisory can no longer downgrade a warning.
    def list = []
    (json?.features ?: []).each { f ->
        def pr = f?.properties
        if (!pr) return
        if ((pr.status ?: "Actual") != "Actual") return          // drop Test/Exercise/System
        if (pr.messageType == "Cancel") return
        def event = (pr.event ?: "").toString()
        def info = nwsClassify(event, "${pr.headline ?: ''} ${pr.description ?: ''}")
        def endMs = parseIsoMs(pr.ends) ?: parseIsoMs(pr.expires)
        // /alerts/active still lists it: an "until further notice" product can
        // outlive its expires time. Trust it until the next poll says otherwise.
        if (endMs != null && endMs < now()) endMs = now() + 900000
        def refs = []
        (pr.references ?: []).each { rf -> if (rf?.identifier) refs << rf.identifier.toString() }
        list << [id: (pr.id ?: f.id ?: "${event}-${pr.sent}").toString(), event: escapeHtml(event.take(60)), grp: info.grp, tier: info.tier,
                 haz: info.haz, ice: info.ice, severity: (pr.severity ?: "").toString(), msgType: (pr.messageType ?: "Alert").toString(),
                 headline: escapeHtml((pr.headline ?: event).toString().take(200)), text: (pr.headline ?: event).toString().replaceAll(/[\r\n]+/, " ").take(200),
                 onset: parseIsoMs(pr.onset), ends: endMs, refs: refs]
    }
    state.nwsAlerts = list
    state.lastNwsGoodPoll = now()
    state.noaaGlobalAlerts = list ? list.collect { "<b>${it.event}</b>: ${it.headline}" }.join("<br><br>") : "<i>No active national weather alerts for your area at this time.</i>"
    state.noaaAlertsMap = nwsHazardMap()
    evaluateMatrix()              // hazard states (and the freeze-expected flag) first
    dispatchNwsProducts(list)
}

// ==============================================================================
// NWS PRODUCT TABLE (2.0)
// grp = notification group, tier = WARNING (watch/advisory) or ALARM (warning),
// haz = local hazard it corroborates (and may drive, if "NWS drives switches"
// is on). Names follow the current NWS product list, including the Oct 2024
// cold-product consolidation (Extreme Cold Warning / Cold Weather Advisory)
// and the 2025 Extreme Heat rename; the retired names are kept so an office
// still issuing them is understood.
// ==============================================================================
def nwsProducts() {
    return [
        "Tornado Warning": [grp: "tornado", tier: "ALARM"], "Tornado Watch": [grp: "tornado", tier: "WARNING"],
        "Severe Thunderstorm Warning": [grp: "storm", tier: "ALARM", haz: "Thunderstorm"], "Severe Thunderstorm Watch": [grp: "storm", tier: "WARNING", haz: "Thunderstorm"],
        "Extreme Wind Warning": [grp: "wind", tier: "ALARM"], "High Wind Warning": [grp: "wind", tier: "ALARM"], "High Wind Watch": [grp: "wind", tier: "WARNING"],
        "Wind Advisory": [grp: "wind", tier: "WARNING"], "Dust Storm Warning": [grp: "wind", tier: "ALARM"], "Blowing Dust Warning": [grp: "wind", tier: "ALARM"],
        "Dust Advisory": [grp: "wind", tier: "WARNING"], "Blowing Dust Advisory": [grp: "wind", tier: "WARNING"],
        "Flash Flood Warning": [grp: "flood", tier: "ALARM", haz: "Flood"], "Flood Warning": [grp: "flood", tier: "ALARM", haz: "Flood"],
        "Flash Flood Watch": [grp: "flood", tier: "WARNING", haz: "Flood"], "Flood Watch": [grp: "flood", tier: "WARNING", haz: "Flood"], "Flood Advisory": [grp: "flood", tier: "WARNING", haz: "Flood"],
        "Freeze Warning": [grp: "freeze", tier: "ALARM", haz: "Freeze"], "Hard Freeze Warning": [grp: "freeze", tier: "ALARM", haz: "Freeze"],
        "Freeze Watch": [grp: "freeze", tier: "WARNING", haz: "Freeze"], "Hard Freeze Watch": [grp: "freeze", tier: "WARNING", haz: "Freeze"], "Frost Advisory": [grp: "freeze", tier: "WARNING", haz: "Freeze"],
        "Ice Storm Warning": [grp: "ice", tier: "ALARM", haz: "Freeze", ice: true], "Freezing Rain Advisory": [grp: "ice", tier: "WARNING", haz: "Freeze", ice: true],
        "Winter Storm Warning": [grp: "winter", tier: "ALARM"], "Blizzard Warning": [grp: "winter", tier: "ALARM"], "Snow Squall Warning": [grp: "winter", tier: "ALARM"],
        "Lake Effect Snow Warning": [grp: "winter", tier: "ALARM"], "Winter Storm Watch": [grp: "winter", tier: "WARNING"], "Winter Weather Advisory": [grp: "winter", tier: "WARNING"],
        "Lake Effect Snow Watch": [grp: "winter", tier: "WARNING"], "Lake Effect Snow Advisory": [grp: "winter", tier: "WARNING"],
        "Extreme Cold Warning": [grp: "cold", tier: "ALARM"], "Extreme Cold Watch": [grp: "cold", tier: "WARNING"], "Cold Weather Advisory": [grp: "cold", tier: "WARNING"],
        "Wind Chill Warning": [grp: "cold", tier: "ALARM"], "Wind Chill Watch": [grp: "cold", tier: "WARNING"], "Wind Chill Advisory": [grp: "cold", tier: "WARNING"],
        "Extreme Heat Warning": [grp: "heat", tier: "ALARM", haz: "SevereHeat"], "Excessive Heat Warning": [grp: "heat", tier: "ALARM", haz: "SevereHeat"],
        "Extreme Heat Watch": [grp: "heat", tier: "WARNING", haz: "SevereHeat"], "Excessive Heat Watch": [grp: "heat", tier: "WARNING", haz: "SevereHeat"], "Heat Advisory": [grp: "heat", tier: "WARNING", haz: "SevereHeat"],
        "Red Flag Warning": [grp: "fire", tier: "ALARM", haz: "FireWeather"], "Fire Weather Watch": [grp: "fire", tier: "WARNING", haz: "FireWeather"],
        "Extreme Fire Danger": [grp: "fire", tier: "ALARM", haz: "FireWeather"], "Fire Warning": [grp: "fire", tier: "ALARM"],
        "Hurricane Warning": [grp: "tropical", tier: "ALARM"], "Hurricane Watch": [grp: "tropical", tier: "WARNING"],
        "Tropical Storm Warning": [grp: "tropical", tier: "ALARM"], "Tropical Storm Watch": [grp: "tropical", tier: "WARNING"],
        "Storm Surge Warning": [grp: "tropical", tier: "ALARM"], "Storm Surge Watch": [grp: "tropical", tier: "WARNING"],
        "Dense Fog Advisory": [grp: "air", tier: "WARNING"], "Freezing Fog Advisory": [grp: "air", tier: "WARNING"], "Dense Smoke Advisory": [grp: "air", tier: "WARNING"],
        "Air Quality Alert": [grp: "air", tier: "WARNING"], "Ashfall Warning": [grp: "air", tier: "ALARM"], "Ashfall Advisory": [grp: "air", tier: "WARNING"],
        "Civil Emergency Message": [grp: "civil", tier: "ALARM"], "Evacuation Immediate": [grp: "civil", tier: "ALARM"], "Shelter In Place Warning": [grp: "civil", tier: "ALARM"],
        "Law Enforcement Warning": [grp: "civil", tier: "ALARM"], "Hazardous Materials Warning": [grp: "civil", tier: "ALARM"], "Nuclear Power Plant Warning": [grp: "civil", tier: "ALARM"],
        "Avalanche Warning": [grp: "civil", tier: "ALARM"], "Avalanche Watch": [grp: "civil", tier: "WARNING"], "Avalanche Advisory": [grp: "civil", tier: "WARNING"],
        "Tsunami Warning": [grp: "civil", tier: "ALARM"], "Tsunami Watch": [grp: "civil", tier: "WARNING"], "Tsunami Advisory": [grp: "civil", tier: "WARNING"],
        "Earthquake Warning": [grp: "civil", tier: "ALARM"], "Volcano Warning": [grp: "civil", tier: "ALARM"], "Radiological Hazard Warning": [grp: "civil", tier: "ALARM"],
        "Local Area Emergency": [grp: "civil", tier: "ALARM"], "Child Abduction Emergency": [grp: "civil", tier: "WARNING"], "911 Telephone Outage": [grp: "civil", tier: "WARNING"],
        "Lake Wind Advisory": [grp: "wind", tier: "WARNING"],
        "Special Weather Statement": [grp: "other", tier: "WARNING"]
    ]
}

def nwsGroups() {
    return [[id: "tornado", name: "Tornado", icon: "🌪️"], [id: "storm", name: "Severe Thunderstorm", icon: "⛈️"], [id: "wind", name: "Wind & Dust", icon: "💨"],
            [id: "flood", name: "Flood", icon: "🌊"], [id: "freeze", name: "Freeze & Frost", icon: "❄️"], [id: "ice", name: "Ice Storm", icon: "🧊"],
            [id: "winter", name: "Winter Storm & Snow", icon: "🌨️"], [id: "cold", name: "Extreme Cold & Wind Chill", icon: "🥶"], [id: "heat", name: "Heat", icon: "🔥"],
            [id: "fire", name: "Fire Weather", icon: "🔥🌲"], [id: "tropical", name: "Hurricane & Tropical", icon: "🌀"], [id: "air", name: "Fog, Smoke & Air Quality", icon: "🌫️"],
            [id: "civil", name: "Civil Emergency", icon: "📢"], [id: "other", name: "Statements & other products", icon: "📄"]]
}

def nwsClassify(String event, String text) {
    def info = nwsProducts()[event]
    def r = info ? [grp: info.grp, tier: info.tier, haz: info.haz, ice: (info.ice == true)] : [grp: "other", tier: (event.endsWith("Warning") ? "ALARM" : "WARNING"), haz: null, ice: false]
    // Winter products route as ICE when their text calls for freezing rain
    // or ice accumulation (fork 1.0.0 rule, kept).
    if (r.grp == "winter") {
        def t = (text ?: "").toLowerCase()
        if (t.contains("freezing rain") || t.contains("freezing drizzle") || t.contains("ice accumulation")) { r.ice = true; r.haz = "Freeze" }
    }
    return r
}

// Alerts still in force right now (expiry re-checked every call). Alerts
// are not trusted at all once the last good poll is 30+ minutes old, since a
// cancellation could have been missed.
def nwsActiveAlerts() {
    if (settings.enableInternet == false || settings.enableNOAA != true) return []
    if ((now() - (state.lastNwsGoodPoll ?: 0)) > 1800000) return []
    return (state.nwsAlerts ?: []).findAll { it.ends == null || it.ends > now() }
}

// Hazard STATE only counts a product once it is in effect (onset reached).
// A Freeze Warning issued at 3 pm for 1 am must not switch Freeze to ALARM
// at 55°F; its notification still goes out when it is issued.
def nwsHazardStatus(String haz) {
    def best = "Clear"
    nwsActiveAlerts().each { a -> if (a.haz == haz && (a.onset == null || (a.onset as Long) <= now())) { if (a.tier == "ALARM") best = "ALARM"; else if (best == "Clear") best = "WARNING" } }
    return best
}

def nwsIceActive() { return nwsActiveAlerts().any { it.ice && (it.onset == null || (it.onset as Long) <= now()) } }

def nwsHazardMap() {
    def m = [:]
    hazardIds().each { m[it] = nwsHazardStatus(it) }
    m["FreezeIce"] = nwsIceActive()
    nwsActiveAlerts().each { a -> if (a.haz) m["Raw_${a.haz}"] = ((m["Raw_${a.haz}"] ? m["Raw_${a.haz}"] + "<br>" : "") + "<b>${a.event}</b>: ${a.headline}") }
    return m
}

// One notification per NEW product. Updates/extensions of a product you were
// already told about (NWS sends them as new ids that reference the old one)
// are logged, not pushed.
def dispatchNwsProducts(List list) {
    def seen = state.nwsNotified ?: [:]
    def endOf = { v -> (v instanceof Map ? v.end : v) as Long }
    // prune ids that expired more than a day ago
    seen = seen.findAll { k, v -> endOf(v) > now() - 86400000 }
    list.each { a ->
        if (seen.containsKey(a.id)) return
        def endMark = (a.ends ?: (now() + 86400000)) as Long
        // Winter products that call for freezing rain go to the Ice Storm rows.
        def key = "nws_${a.ice ? 'ice' : a.grp}_${a.tier == 'ALARM' ? 'warn' : 'watch'}"
        // An "update" is a referenced product for the SAME row that was really
        // delivered. A watch upgraded to a warning references the watch too,
        // but it is a different row (and the watch may never have been sent),
        // so it is news.
        def isUpdate = a.refs.any { rid -> def pv = seen[rid]; pv instanceof Map && pv.key == key && pv.sent == true }
        a.refs.each { rid -> def pv = seen[rid]; if (pv instanceof Map && pv.key == key) pv.end = endMark }
        if (isUpdate && !nBool(notifyEvent(key) ?: [:], "updates")) {
            seen[a.id] = [end: endMark, key: key, sent: true]
            notifyLog("NWS update (not pushed): ${a.event}")
            return
        }
        def until = a.ends ? new Date(a.ends as Long).format("EEE h:mm a", location.timeZone) : null
        def sent = dispatchNotification(key, [haz: null, source: "NWS", from: "none", to: a.tier, nws: a,
                                   detail: "${a.text ? a.text : a.event}${until && !(a.text ?: '').contains(' until ') ? ' (until ' + until + ')' : ''}"])
        seen[a.id] = [end: endMark, key: key, sent: (sent == true)]
    }
    state.nwsNotified = seen
}

// ==============================================================================
// SITE, PRESSURE AND SENSOR FOUNDATION (2.0)
// ==============================================================================
def hazardIds() { return ["Thunderstorm", "Flood", "Freeze", "SevereHeat", "FireWeather"] }
def tempAttrs() { return ["temperature", "tempf"] }
def pressAbsAttrs() { return ["pressureAbs", "baromabsin", "Baromabsin", "absolutePressure", "stationPressure"] }
def pressRelAttrs() { return ["pressure", "baromrelin", "Baromrelin", "barometricPressure"] }
def windAttrs() { return ["windSpeed", "windspeedmph", "wind"] }
def gustAttrs() { return ["windGust", "windgustmph", "gust"] }
def windDirAttrs() { return ["windDirection", "winddir", "windDir"] }
def rainRateAttrs() { return ["rainRate", "hourlyrainin", "precipRate", "hourlyRain"] }
def luxAttrs() { return ["illuminance", "solarradiation", "solarRadiation"] }

// Pressure in any common unit -> inHg (metric hubs report hPa or kPa).
def normPressInHg(Double v) {
    if (v == null) return null
    if (v > 500.0) return v / 33.8639        // hPa / mbar
    if (v > 50.0 && v < 120.0) return v * 0.2953  // kPa
    return v
}

def windUnitFactor() {
    def u = settings.windUnit ?: (isMetric() ? "kmh" : "mph")
    return u == "kmh" ? 0.621371 : (u == "ms" ? 2.23694 : (u == "kn" ? 1.15078 : 1.0))
}
def distUnitFactor() {
    def u = settings.distUnit ?: (isMetric() ? "km" : "mi")
    return u == "km" ? 0.621371 : 1.0
}
def rainUnitFactor() {
    def u = settings.rainUnit ?: (isMetric() ? "mm" : "in")
    return u == "mm" ? (1.0 / 25.4) : 1.0
}

// NWS altimeter setting (weather.gov/media/epz/wxcalc/altimeterSetting.pdf):
// A = (Ps - 0.3) * (1 + k*H/(Ps - 0.3)^n)^(1/n), hPa and metres,
// n = 0.190284, k = 1013.25^n * 0.0065 / 288. Because A^n = (Ps-0.3)^n + k*H,
// the formula inverts exactly for the station pressure or the elevation.
def altN() { return 0.190284d }
def altK() { return Math.pow(1013.25d, altN()) * 0.0065d / 288.0d }
def altimeterFromStation(Double psInHg, Double elevM) {
    def ps = psInHg * 33.8639d - 0.3d
    if (ps <= 0) return null
    return (ps * Math.pow(1.0d + altK() * elevM / Math.pow(ps, altN()), 1.0d / altN())) / 33.8639d
}
def stationFromAltimeter(Double altInHg, Double elevM) {
    def base = Math.pow(altInHg * 33.8639d, altN()) - altK() * elevM
    if (base <= 0) return null
    return (Math.pow(base, 1.0d / altN()) + 0.3d) / 33.8639d
}
def impliedElevationM(Double altInHg, Double psInHg) {
    def ps = psInHg * 33.8639d - 0.3d
    if (ps <= 0) return null
    return (Math.pow(altInHg * 33.8639d, altN()) - Math.pow(ps, altN())) / altK()
}

// Elevation used to turn station pressure into sea-level terms: your entry,
// else learned from airport comparisons (median), else the NWS forecast grid.
def siteElevationM() {
    if (settings.stationElevationFt != null && settings.stationElevationFt.toString() != "") {
        try { return (settings.stationElevationFt as Double) * 0.3048d } catch (e) {}
    }
    if (state.elevLearnedM != null && (state.elevSamples ?: []).size() >= 3) return state.elevLearnedM as Double
    if (state.gridElevationM != null) return state.gridElevationM as Double
    return null
}
def siteElevationSource() {
    if (settings.stationElevationFt != null && settings.stationElevationFt.toString() != "") return "your setting"
    if (state.elevLearnedM != null && (state.elevSamples ?: []).size() >= 3) return "learned from ${state.calStationId ?: 'airport'} (${(state.elevSamples ?: []).size()} pulls)"
    if (state.gridElevationM != null) return "NWS forecast grid"
    return null
}

def median(List xs) {
    if (!xs) return null
    def s = xs.collect { it as Double }.sort()
    def n = s.size()
    return n % 2 ? s[(int) (n / 2)] : (s[n / 2 - 1] + s[n / 2]) / 2.0d
}

// Station (absolute) pressure in inHg, or null if the sensor only reports
// relative / sea-level-adjusted pressure.
def readStationPressure() {
    if (settings.pressureSource == "relative") return null
    def v = getFloat(sensorPress, pressAbsAttrs(), null)
    if (v == null) return null
    def inHg = normPressInHg(v as Double)
    return (inHg >= 15.0 && inHg <= 32.5) ? inHg : null
}
def readRelativePressure() {
    def v = getFloat(sensorPress, pressRelAttrs(), null)
    if (v == null) return null
    def inHg = normPressInHg(v as Double)
    return (inHg >= 15.0 && inHg <= 32.5) ? inHg : null
}

// The series pressure TENDENCY is measured on. It must never contain a
// calibration offset: the 1.x app added the auto-cal offset before storing,
// so every 3-hourly re-calibration injected a fake pressure step.
def pressureSeriesValue() {
    def ps = readStationPressure()
    if (ps != null) return [kind: "station", v: ps]
    def rel = readRelativePressure()
    return rel != null ? [kind: "relative", v: rel] : null
}

// Device freshness: newest of Hubitat's last-activity time and the last
// event this app saw from it. null = no way to tell (treated as fresh).
def deviceAgeMin(dev) {
    if (!dev) return null
    def t = 0L
    try { def la = dev.getLastActivity(); if (la) t = (la instanceof Date) ? la.getTime() : (la as Long) } catch (e) {}
    def seen = (state.devSeen ?: [:])[dev.id?.toString()]
    if (seen) t = Math.max(t, seen as Long)
    return t > 0 ? (now() - t) / 60000.0d : null
}
def markSeen(evt) {
    try { def m = state.devSeen ?: [:]; m[evt.deviceId.toString()] = now(); state.devSeen = m } catch (e) {}
    markActive()
}

def readSensors() {
    def staleMin = ((settings.staleDataTimeout ?: 30) as Double)
    def health = [:]
    def rd = [health: health]
    // Devices that can legitimately sit silent for hours (a standalone rain
    // gauge on a dry day, a lightning detector) get a 24 h window; their
    // drivers often send events only when a value changes.
    def quietRoles = ["rain", "lightning"]
    def one = { String role, dev, List attrs, Double lo, Double hi, Closure conv ->
        if (!dev) { health[role] = [ok: null, why: "not configured"]; return null }
        def age = deviceAgeMin(dev)
        def lim = (role in quietRoles) ? Math.max(staleMin, 1440.0d) : staleMin
        if (age != null && age > lim) { health[role] = [ok: false, why: "stale (no update for ${Math.round(age)} min)", age: age]; return null }
        def v = getFloat(dev, attrs, null)
        if (v == null) { health[role] = [ok: false, why: "no reading", age: age]; return null }
        def d = conv ? conv(v as Double) : (v as Double)
        if (d == null || d.isNaN() || d < lo || d > hi) { health[role] = [ok: false, why: "implausible value ${v}", age: age]; return null }
        health[role] = [ok: true, why: "ok", age: age]
        return d
    }
    def cToF = { Double c -> isMetric() ? (c * 9.0d / 5.0d + 32.0d) : c }
    rd.t = one("temp", sensorTemp, tempAttrs(), -80.0d, 140.0d, cToF)
    rd.h = one("hum", sensorHum, ["humidity"], 1.0d, 100.0d, null)
    if (rd.h == null && sensorHum) {
        // Many hygrometers report 100.x in fog; clamp rather than reject.
        def hv = getFloat(sensorHum, ["humidity"], null)
        if (hv != null && (hv as Double) > 100.0d && (hv as Double) <= 105.0d && health.hum?.why?.startsWith("implausible")) { rd.h = 100.0d; health.hum = [ok: true, why: "ok (clamped to 100%)"] }
    }
    rd.wind = one("wind", sensorWind, windAttrs(), 0.0d, 200.0d, { Double w -> w * windUnitFactor() })
    rd.gust = sensorWind && health.wind?.ok ? (getFloat(sensorWind, gustAttrs(), null) != null ? (getFloat(sensorWind, gustAttrs(), null) as Double) * windUnitFactor() : null) : null
    if (rd.gust != null && (rd.gust < 0 || rd.gust > 250)) rd.gust = null
    rd.windDir = sensorWindDir ? one("windDir", sensorWindDir, windDirAttrs(), 0.0d, 360.0d, null) : null
    rd.rain = one("rain", sensorRain, rainRateAttrs(), 0.0d, 30.0d, { Double x -> x * rainUnitFactor() })
    rd.lux = one("lux", sensorLux, luxAttrs(), 0.0d, 250000.0d, null)
    if (sensorLightning) {
        def age = deviceAgeMin(sensorLightning)
        health.lightning = (age != null && age > Math.max(staleMin, 1440.0d)) ? [ok: false, why: "stale (no update for ${Math.round(age)} min)", age: age] : [ok: true, why: "ok", age: age]
    }
    // Pressure: station pressure + elevation -> altimeter; else relative + offset.
    def pAge = deviceAgeMin(sensorPress)
    if (pAge != null && pAge > staleMin) {
        health.press = [ok: false, why: "stale (no update for ${Math.round(pAge)} min)", age: pAge]
        rd.pressMode = "unavailable"
    } else {
        rd.ps = readStationPressure()
        def rel = readRelativePressure()
        rd.rawP = rd.ps ?: rel
        def elev = siteElevationM()
        if (rd.ps != null && elev != null) { rd.alt = altimeterFromStation(rd.ps, elev); rd.pressMode = "station" }
        else if (rel != null && getPressOffset() != 0.0) { rd.alt = rel + getPressOffset(); rd.pressMode = "offset" }
        else if (rel != null && rel >= 28.5) { rd.alt = rel; rd.pressMode = "sensor" }   // sensor already sea-level adjusted
        else rd.pressMode = (rd.rawP != null) ? "uncalibrated" : "unavailable"
        health.press = rd.rawP != null ? [ok: true, why: rd.pressMode == "uncalibrated" ? "ok (no elevation yet, so no sea-level value)" : "ok", age: pAge] : [ok: false, why: "no reading", age: pAge]
    }
    return rd
}

// Sensor offline / back notifications. A role must stay bad for one more
// stale period before it is reported, so a single slow upload is not news.
def reportSensorHealth(Map health) {
    def down = state.sensorDown ?: [:]
    def names = [temp: "Temperature", hum: "Humidity", press: "Pressure", wind: "Wind", windDir: "Wind direction", rain: "Rain gauge", lux: "Solar/lux", lightning: "Lightning detector"]
    health.each { role, hInfo ->
        if (hInfo.ok == false) {
            if (!down[role]) down[role] = [since: now(), told: false, logged: false]
            else if (!down[role].told && (now() - (down[role].since as Long)) >= 600000 && now() >= ((down[role].retryAt ?: 0) as Long)) {
                if (!down[role].logged) { logAction("⚠ SENSOR: ${names[role] ?: role} ${hInfo.why}."); down[role].logged = true }
                def sent = dispatchNotification("sensorStale", [haz: null, from: "ok", to: "stale", detail: "${names[role] ?: role}: ${hInfo.why}. Hazards that need it are paused and keep their current state."])
                // Told only when it really went out; otherwise retry in 30 min
                // (quiet hours, daily cap...). Each sensor is announced on its own.
                if (sent == true) down[role].told = true
                else down[role].retryAt = now() + 1800000
            }
        } else if (down[role]) {
            if (down[role].told) {
                logAction("✅ SENSOR: ${names[role] ?: role} is reporting again.")
                dispatchNotification("sensorBack", [haz: null, from: "stale", to: "ok", detail: "${names[role] ?: role} is reporting again."])
            }
            down.remove(role)
        }
    }
    state.sensorDown = down
}

// Hold a hazard's state when its inputs are unavailable (2.0). The 1.x app
// computed it from 0 instead, or cleared everything when one sensor slept.
def source0(target, localTarget) { return (target != localTarget) ? "NWS" : "Local" }

def holdHazard(String haz, String why) {
    def matrix = state.threatMatrix ?: [:]
    def data = matrix[haz]
    if (!data) return
    // NWS can still drive (and must still release) a paused hazard when it
    // is allowed to drive switches. The local part counts as "no change".
    if (settings.enableNOAA && settings.noaaTriggersHardware && (data.stateSource == "NWS" || nwsHazardStatus(haz) != "Clear")) {
        def localProb = (data.stateSource == "NWS") ? 0 : (data.prob ?: 0)
        processHazardState(haz, data.threat ?: 0, localProb, data.conf ?: 0, "⏸ Paused: ${why}. Local part held; NWS still applies.", (data.stateSource == "NWS") ? "Inputs unavailable." : (data.mathEx ?: ""), (settings.debounceMins ?: 15) * 60000)
        return
    }
    data.howWhy = "⏸ Paused: ${why}. Holding the current state (${data.state}) until the sensor reports again."
    data.mathEx = "Inputs unavailable."
    matrix[haz] = data
    state.threatMatrix = matrix
}

// Location-agnostic (2.0): when the hub's coordinates move more than 10 km,
// everything learned about the old site is dropped and rediscovered.
def checkSiteMoved() {
    def lat = location.latitude; def lon = location.longitude
    if (lat == null || lon == null) return
    lat = lat as Double; lon = lon as Double
    if (state.siteLat == null) { state.siteLat = lat; state.siteLon = lon; return }
    def dLat = Math.toRadians(lat - (state.siteLat as Double)); def dLon = Math.toRadians(lon - (state.siteLon as Double))
    def aa = Math.sin(dLat / 2) ** 2 + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(state.siteLat as Double)) * Math.sin(dLon / 2) ** 2
    def km = 6371.0d * 2 * Math.atan2(Math.sqrt(aa), Math.sqrt(1 - aa))
    if (km < 10.0d) return
    logAction("📍 LOCATION CHANGE: hub moved ${Math.round(km)} km. Re-discovering NWS office, forecast grid, airport and elevation; local climate history restarts.")
    ["calStationId", "calStationName", "calStationFailCount", "lastStationDiscovery", "autoPressOffset", "lastCalPull", "metarExtras", "tafAdvisory",
     "forecastUrl", "forecastUrlTime", "freezeForecast", "gridElevationM", "elevSamples", "elevLearnedM", "nwsCoverage", "nwsCoverageCheck",
     "freezeDayLog", "pressureHistory", "pressRaw3", "nwsAlerts", "nwsNotified", "lastNwsGoodPoll"].each { state.remove(it) }
    state.pressureHistory = []
    state.siteLat = lat; state.siteLon = lon
    if (settings.stationElevationFt != null && settings.stationElevationFt.toString() != "") logAction("⚠ LOCATION CHANGE: your entered station elevation (${settings.stationElevationFt} ft) is still in use. Clear or update it in Configuration.")
    if (settings.enableInternet != false) { runIn(5, "pollAutoCal"); runIn(30, "pollForecast"); runIn(10, "pollNOAA") }
}

// ==============================================================================
// ==============================================================================
// AUTO-CALIBRATION ENGINE (Government sources only: api.weather.gov / NWS)
// Discovers the nearest official NWS observation station from the hub's
// coordinates, pulls its latest altimeter reading, and computes the MSLP
// offset automatically. Every pull is logged with source URL and values.
// ==============================================================================
def pollAutoCal() {
    if (settings.enableInternet == false) return
    if (!settings.enableAutoCal) return
    def hdrs = ["User-Agent": "Hubitat-AdvancedSevereWeatherApp/4.0 (auto-calibration)", "Accept": "application/geo+json"]
    
    def manualStation = settings.calStationOverride?.trim()?.toUpperCase()
    if (manualStation) {
        // Whitelist: station IDs are strictly alphanumeric. Anything else is
        // rejected rather than being interpolated into a request URL.
        if (manualStation ==~ /^[A-Z0-9]{3,8}$/) {
            state.calStationId = manualStation
        } else {
            logAction("⚠ AUTO-CAL: Station override '${escapeHtml(manualStation)}' is not a valid station ID (3-8 letters/numbers, e.g. KBIL). Ignoring it.")
        }
    }
    
    // Re-run discovery periodically instead of locking onto the first station
    // forever. Guards: manual override always wins; a fresh pick only
    // replaces the cached one if it's a genuinely different, closer station
    // (hysteresis) so it can't ping-pong between two similar-distance picks
    // every 3 hours.
    def rediscoverDue = !state.calStationId || !state.lastStationDiscovery || (now() - state.lastStationDiscovery) > 604800000 // 7 days
    
    if (state.calStationId && !rediscoverDue) {
        def url = "https://api.weather.gov/stations/${state.calStationId}/observations/latest"
        logAction("🌐 DATA PULL: Requesting latest observation from ${url}")
        try { asynchttpGet("calObsHandler", [uri: url, timeout: 10, headers: hdrs, followRedirects: true]) } catch (e) { log.error "AutoCal obs fetch failed: ${e}" }
    } else {
        if (!nwsCovered()) return
        def lat = location.latitude; def lon = location.longitude
        if (!lat || !lon) { logAction("⚠ AUTO-CAL: Hub has no coordinates set (Settings > Hub Details)."); return }
        // NWS API rejects coordinates with more than 4 decimal places (301
        // redirect the async client doesn't follow). Round to 4dp.
        def latR = String.format(java.util.Locale.US, "%.4f", lat as Double)
        def lonR = String.format(java.util.Locale.US, "%.4f", lon as Double)
        def url = "https://api.weather.gov/points/${latR},${lonR}"
        logAction("🌐 DATA PULL: ${state.calStationId ? 'Re-checking nearest station (weekly refresh)' : 'Discovering nearest NWS station'} via ${url}")
        try { asynchttpGet("calPointsHandler", [uri: url, timeout: 10, headers: hdrs, followRedirects: true]) } catch (e) { log.error "AutoCal points fetch failed: ${e}" }
    }
}

def calPointsHandler(response, data) {
    if (response.hasError()) { nwsCoverageMiss(response); logAction("⚠ AUTO-CAL: NWS points lookup failed (${response.getStatus()})."); return }
    state.nwsCoverage = true
    def json = parseJsonRobust(response)
    if (json == null) { logAction("⚠ Response could not be parsed; skipping this cycle."); return }
    def stationsUrl = json?.properties?.observationStations
    if (!stationsUrl) { logAction("⚠ AUTO-CAL: No observation stations URL returned."); return }
    def hdrs = ["User-Agent": "Hubitat-AdvancedSevereWeatherApp/4.0 (auto-calibration)", "Accept": "application/geo+json"]
    logAction("🌐 DATA PULL: Fetching station list from ${stationsUrl}")
    try { asynchttpGet("calStationsHandler", [uri: stationsUrl, timeout: 10, headers: hdrs, followRedirects: true]) } catch (e) { log.error "AutoCal stations fetch failed: ${e}" }
}

def calStationsHandler(response, data) {
    if (response.hasError()) { logAction("⚠ AUTO-CAL: Station list fetch failed."); return }
    def json = parseJsonRobust(response)
    if (json == null) { logAction("⚠ Response could not be parsed; skipping this cycle."); return }
    def first = json?.features ? json.features[0] : null
    def stId = first?.properties?.stationIdentifier?.toString()?.replaceAll(/[^A-Za-z0-9]/, "")
    if (!stId) { logAction("⚠ AUTO-CAL: No stations found near hub coordinates."); return }
    
    state.lastStationDiscovery = now()
    def priorStation = state.calStationId
    def failCount = state.calStationFailCount ?: 0
    
    // Hysteresis: don't hop stations just because the weekly re-check turned
    // up a different "first" result (NWS station lists can reorder). Only
    // switch when there was no prior station, or the prior one has been
    // failing observation pulls (3+ consecutive misses).
    if (!priorStation || priorStation == stId || failCount >= 3) {
        if (priorStation && priorStation != stId) {
            logAction("🌐 AUTO-CAL: Switching reference station from ${priorStation} to ${stId} (${priorStation} failed ${failCount} consecutive pulls).")
        }
        state.calStationId = stId
        state.calStationName = escapeHtml(first?.properties?.name ?: stId)
        state.calStationFailCount = 0
        logAction("🌐 AUTO-CAL: Reference station is ${stId} (${state.calStationName}).")
    } else {
        logAction("🌐 AUTO-CAL: Weekly check found ${stId} as nearest, but keeping working station ${priorStation} (no reason to switch).")
    }
    pollAutoCal()
}

def calObsHandler(response, data) {
    if (response.hasError()) {
        logAction("⚠ AUTO-CAL: Observation fetch from ${state.calStationId} failed (${response.getStatus()}). Will retry next cycle.")
        return
    }
    def json = parseJsonRobust(response)
    if (json == null) { logAction("⚠ Response could not be parsed; skipping this cycle."); return }
    def props = json?.properties
    if (!props) return
    
    // barometricPressure = altimeter setting (what home stations calibrate
    // 'relative pressure' against). seaLevelPressure is the fallback.
    // 2.0: altimeter setting only. seaLevelPressure is reduced with the
    // station's temperature history and is not the same quantity (about 100 m
    // of elevation error in cold air here), and stale observations are skipped.
    def obsMs = parseIsoMs(props.timestamp)
    if (obsMs != null && (now() - obsMs) > 7200000) { logAction("⚠ AUTO-CAL: ${state.calStationId} observation is over 2 h old; skipped."); return }
    def refPa = props.barometricPressure?.value
    if (refPa == null) { logAction("⚠ AUTO-CAL: Station ${state.calStationId} reported no pressure this cycle."); return }
    def refInHg = (refPa as Double) / 3386.39
    if (refInHg < 27.0 || refInHg > 32.5) { logAction("⚠ AUTO-CAL REJECTED: reference ${String.format('%.2f', refInHg)} inHg does not look like an altimeter setting."); return }
    
    // 2.0: with a station (absolute) pressure reading, the airport is used to
    // LEARN this site's effective elevation (median of recent pulls), and the
    // sea-level value is computed from your own barometer with the NWS
    // altimeter formula. Your pressure no longer jumps when a pull lands.
    // Sensors with only relative pressure keep the 1.x offset method.
    def ps = readStationPressure()
    def rawP = ps ?: readRelativePressure()
    if (rawP == null) { logAction("⚠ AUTO-CAL: Local pressure sensor returned no reading; cannot calibrate."); return }
    def offset = Math.round((refInHg - rawP) * 100.0) / 100.0
    if (ps != null) {
        def hImp = impliedElevationM(refInHg, ps)
        if (hImp == null || hImp < -450 || hImp > 5500) { logAction("⚠ AUTO-CAL REJECTED: ${state.calStationId} ${String.format('%.2f', refInHg)} inHg vs station ${String.format('%.2f', ps)} inHg implies an impossible elevation."); return }
        def samples = state.elevSamples ?: []
        samples << Math.round(hImp * 10.0) / 10.0
        if (samples.size() > 24) samples = samples.drop(samples.size() - 24)
        state.elevSamples = samples
        state.elevLearnedM = Math.round(median(samples) * 10.0) / 10.0
        logAction("🌐 AUTO-CAL: ${state.calStationId} ${String.format('%.2f', refInHg)} inHg with your station pressure ${String.format('%.2f', ps)} inHg implies ${Math.round(hImp * 3.28084)} ft; using ${Math.round((state.elevLearnedM as Double) * 3.28084)} ft (median of ${samples.size()}).")
    } else {
        if (Math.abs(offset) > 10.0) {
            logAction("⚠ AUTO-CAL REJECTED: Reference ${String.format('%.2f', refInHg)} inHg vs local ${String.format('%.2f', rawP)} produced an implausible offset (${offset}). Not applied.")
            return
        }
        state.autoPressOffset = offset
    }
    def tempC = props.temperature?.value
    def tempDeltaStr = "n/a"
    if (tempC != null) {
        def refF = ((tempC as Double) * 9.0 / 5.0) + 32.0
        def localT = getFloat(sensorTemp, tempAttrs(), null)
        if (localT != null && isMetric()) localT = (localT as Double) * 9.0 / 5.0 + 32.0
        if (localT != null) {
            state.calTempDelta = Math.round((localT - refF) * 10.0) / 10.0
            tempDeltaStr = "${state.calTempDelta > 0 ? '+' : ''}${state.calTempDelta}°F vs airport (informational only, local microclimates differ)"
        }
    }
    def elevNow = siteElevationM()
    def ours = (ps != null && elevNow != null) ? altimeterFromStation(ps, elevNow) : null
    state.lastCalPull = [time: now(), station: state.calStationId, ref: Math.round(refInHg * 100.0) / 100.0, raw: Math.round(rawP * 100.0) / 100.0, offset: offset,
                         kind: (ps != null ? "station" : "offset"), ours: ours != null ? Math.round(ours * 100.0) / 100.0 : null]
    
    // === METAR EXTRAS: visibility, wind QC, fallback snapshot ===
    def extras = [time: now(), station: state.calStationId]
    def visM = props.visibility?.value
    if (visM != null) {
        extras.visMiles = Math.round(((visM as Double) / 1609.34) * 10.0) / 10.0
        extras.fog = (visM as Double) < 1600  // < 1 statute mile
    }
    def wKmh = props.windSpeed?.value
    if (wKmh != null) {
        extras.windMph = Math.round((wKmh as Double) * 0.621371 * 10.0) / 10.0
        def localW = getFloat(sensorWind, windAttrs(), null)
        if (localW != null) localW = (localW as Double) * windUnitFactor()
        // QC cross-check: informational only. Airport wind is NOT auto-trusted
        // over your sensor (microclimates are real), but a 20+ mph divergence
        // suggests a stuck anemometer or very local sheltering worth knowing about.
        if (localW != null && Math.abs(extras.windMph - localW) >= 20.0) {
            extras.windQcFlag = true
            logAction("⚠ SENSOR QC: Your anemometer reads ${localW} mph but ${state.calStationId} reports ${extras.windMph} mph. If this persists, check for obstruction or a stuck sensor.")
        }
    }
    if (tempC != null) extras.tempF = Math.round((((tempC as Double) * 9.0 / 5.0) + 32.0) * 10.0) / 10.0
    state.metarExtras = extras
    
    if (ps == null) logAction("🌐 AUTO-CAL APPLIED: ${state.calStationId} altimeter ${String.format('%.2f', refInHg)} inHg | your station ${String.format('%.2f', rawP)} inHg | offset set to ${offset > 0 ? '+' : ''}${offset} inHg. Temp check: ${tempDeltaStr}.")
    else logAction("🌐 AUTO-CAL CHECK: ${state.calStationId} ${String.format('%.2f', refInHg)} inHg, yours ${ours != null ? String.format('%.2f', ours) : 'n/a'} inHg from station pressure + elevation. Temp check: ${tempDeltaStr}.")
    runIn(5, "pollTaf")
    runIn(2, "evaluateMatrix")
}

// ==============================================================================
// TAF FORECAST ADVISORY (aviationweather.gov, US Government/NOAA)
// A TAF is the airport's official short-term forecast. Used ONLY as an
// advisory confidence layer: it can nudge probabilities by <=10% and raise
// confidence, but can NEVER trigger an alert on its own. Local sensors always
// remain the authority; the app functions identically without it.
// ==============================================================================
def pollTaf() {
    if (settings.enableInternet == false || !settings.enableAutoCal) return
    def st = (settings.tafStationOverride?.trim()?.toUpperCase() ?: state.calStationId)
    if (!st || !(st ==~ /^[A-Z0-9]{3,8}$/)) return
    def url = "https://aviationweather.gov/api/data/taf?ids=${st}&format=raw"
    logAction("🌐 DATA PULL: Requesting TAF forecast from ${url}")
    def hdrs = ["User-Agent": "Hubitat-AdvancedSevereWeatherApp/4.0 (taf-advisory)"]
    try { asynchttpGet("tafResponseHandler", [uri: url, timeout: 10, headers: hdrs, followRedirects: true]) } catch (e) { log.error "TAF fetch failed: ${e}" }
}

def tafResponseHandler(response, data) {
    if (response.hasError() || !response.getData()) {
        logAction("⚠ TAF: No forecast available for this station (many small AWOS sites have no TAF). Advisory layer idle; set a TAF Station Override (e.g. KGTF) to use the nearest major airport.")
        state.tafAdvisory = null
        return
    }
    def raw = response.getData().toString()
    // Same Hubitat quirk can Base64-encode this body; TAF raw text always
    // contains spaces, a Base64 blob never does.
    if (!raw.contains(" ") && raw.matches(/[A-Za-z0-9+\/=\r\n]+/)) {
        try { raw = new String(raw.decodeBase64(), "UTF-8") } catch (e) {}
    }
    if (raw.trim().size() < 10) { state.tafAdvisory = null; return }
    
    def upper = raw.toUpperCase()
    def hasTs = (upper =~ /\bTS[A-Z]{0,4}\b/).find() || upper.contains("VCTS")
    def hasFzra = upper.contains("FZRA") || upper.contains("FZDZ")
    def maxGustKt = 0
    (upper =~ /G(\d{2,3})KT/).each { m -> def g = m[1].toInteger(); if (g > maxGustKt) maxGustKt = g }
    
    state.tafAdvisory = [ts: hasTs, fzra: hasFzra, gustKt: maxGustKt, gustMph: Math.round(maxGustKt * 1.15078), time: now(), station: (settings.tafStationOverride?.trim()?.toUpperCase() ?: state.calStationId), raw: escapeHtml(raw.take(300))]
    logAction("🌐 TAF ADVISORY: ${state.tafAdvisory.station} forecasts: Thunderstorms=${hasTs ? 'YES' : 'no'}, Freezing Precip=${hasFzra ? 'YES' : 'no'}, Max Gust=${maxGustKt}kt (${state.tafAdvisory.gustMph} mph).")
}

// ==============================================================================
// NWS POINT FORECAST (Feed 4, api.weather.gov, US Government)
// Used ONLY to decide whether a freeze is expected, so routine freeze alerts
// can stay quiet. It never raises a freeze probability or fires anything.
// ==============================================================================
// Outside NWS coverage (another country, or an ocean point) the points
// lookup returns 404. Then every NWS feed stands down for a week and the app
// runs on local sensors alone; it re-checks weekly and after a move.
def nwsCoverageMiss(response) {
    def code = 0
    try { code = response.getStatus() as Integer } catch (e) {}
    if (code == 404) {
        if (state.nwsCoverage != false) logAction("🌐 NWS: this location is outside NWS coverage. Running on local sensors only (re-checked weekly).")
        state.nwsCoverage = false
        state.nwsCoverageCheck = now()
    }
}
def nwsCovered() {
    if (state.nwsCoverage != false) return true
    return (now() - (state.nwsCoverageCheck ?: 0)) > 604800000
}

def freezeForecastEnabled() {
    return settings.enableInternet != false && settings.seasonalFreezeAuto != false && settings.enableFreezeForecast != false
}

def pollForecast() {
    if (!freezeForecastEnabled() || !nwsCovered()) return
    def hdrs = ["User-Agent": "Hubitat-AdvancedSevereWeatherApp/4.0 (freeze-forecast)", "Accept": "application/geo+json"]
    // The forecast URL for a point rarely changes; refresh it weekly or after errors.
    if (!state.forecastUrl || !state.forecastUrlTime || (now() - state.forecastUrlTime) > 604800000) {
        def lat = location.latitude; def lon = location.longitude
        if (!lat || !lon) { logAction("⚠ FORECAST: Hub has no coordinates set (Settings > Hub Details)."); return }
        def latR = String.format(java.util.Locale.US, "%.4f", lat as Double)
        def lonR = String.format(java.util.Locale.US, "%.4f", lon as Double)
        def url = "https://api.weather.gov/points/${latR},${lonR}"
        logAction("🌐 DATA PULL: Looking up NWS forecast grid via ${url}")
        try { asynchttpGet("forecastPointsHandler", [uri: url, timeout: 15, headers: hdrs, followRedirects: true]) } catch (e) { log.error "Forecast points fetch failed: ${e}" }
        return
    }
    try { asynchttpGet("forecastHandler", [uri: state.forecastUrl, timeout: 15, headers: hdrs, followRedirects: true]) } catch (e) { log.error "Forecast fetch failed: ${e}" }
}

def forecastPointsHandler(response, data) {
    if (response.hasError()) { nwsCoverageMiss(response); logAction("⚠ FORECAST: NWS points lookup failed (${response.getStatus()}). Will retry next hour."); return }
    state.nwsCoverage = true
    def json = parseJsonRobust(response)
    def url = json?.properties?.forecast?.toString()
    // Only ever follow a forecast link that stays on api.weather.gov.
    if (!url || !url.startsWith("https://api.weather.gov/gridpoints/")) {
        logAction("⚠ FORECAST: NWS points lookup returned no usable forecast URL.")
        return
    }
    state.forecastUrl = url
    state.forecastUrlTime = now()
    logAction("🌐 FORECAST: Using NWS forecast ${url}")
    runIn(2, "pollForecast")
}

def forecastHandler(response, data) {
    if (response.hasError()) {
        def fails = (state.forecastFailCount ?: 0) + 1
        state.forecastFailCount = fails
        // NWS gridpoint forecasts throw intermittent 500/503s; keep the last
        // good forecast (it ages out after 12 h) and log only now and then.
        if (fails == 1 || fails % 6 == 0) logAction("⚠ FORECAST: NWS forecast fetch failed (${response.getStatus()}), ${fails} in a row. Keeping last good forecast until it is 12 h old.")
        if (fails % 3 == 0) state.forecastUrl = null  // re-resolve the grid in case it moved
        return
    }
    def json = parseJsonRobust(response)
    def periods = json?.properties?.periods
    if (!(periods instanceof List) || periods.isEmpty()) { logAction("⚠ FORECAST: NWS forecast had no periods."); return }
    def parsed = []
    periods.take(14).each { p ->
        def tF = forecastTempF(p.temperature, p.temperatureUnit)
        def start = parseIsoMs(p.startTime)
        def end = parseIsoMs(p.endTime)
        if (tF == null || start == null || end == null) return
        parsed << [name: escapeHtml((p.name ?: "").toString().take(30)), start: start, end: end, day: (p.isDaytime == true), temp: tF,
                   text: escapeHtml((p.shortForecast ?: "").toString().take(80))]
    }
    if (!parsed) { logAction("⚠ FORECAST: NWS forecast periods could not be read."); return }
    state.forecastFailCount = 0
    state.freezeForecast = [time: now(), periods: parsed]
    try {
        def ev = json?.properties?.elevation
        if (ev?.value != null) state.gridElevationM = (ev.unitCode?.toString()?.contains("ft") ? (ev.value as Double) * 0.3048 : (ev.value as Double))
    } catch (e) {}
    def nights = parsed.findAll { !it.day }.take(3).collect { "${it.name} ${Math.round(it.temp)}°F" }.join(", ")
    logAction("🌐 FORECAST: NWS lows ${nights ?: 'n/a'}.")
    runIn(2, "evaluateMatrix")
}

// NWS returns either a plain number + temperatureUnit, or a quantity object.
def forecastTempF(temp, unit) {
    if (temp == null) return null
    def v = temp
    def u = unit?.toString() ?: "F"
    if (temp instanceof Map) {
        v = temp.value
        u = (temp.unitCode?.toString()?.contains("degC")) ? "C" : "F"
    }
    if (v == null) return null
    try {
        def d = v as Double
        return (u == "C") ? (d * 9.0 / 5.0 + 32.0) : d
    } catch (e) { return null }
}

def parseIsoMs(str) {
    if (!str) return null
    try { return toDateTime(str.toString())?.getTime() } catch (e) { return null }
}

// === ICE CORROBORATION ===
// Returns a short source description if any forecast/official source calls
// for freezing precipitation now or in the next 18 h, else null.
def iceCorroboration() {
    if (settings.enableInternet == false) return null
    def taf = state.tafAdvisory
    if (taf?.fzra && (now() - (taf.time ?: 0)) < 21600000) return "${taf.station} TAF FZRA/FZDZ"
    if (nwsIceActive()) return "NWS ice product"
    def fc = state.freezeForecast
    if (fc?.periods && (now() - (fc.time ?: 0)) < 43200000) {
        def horizon = now() + 64800000
        def hit = fc.periods.find { p -> p.end > now() && p.start < horizon && isIceForecastText(p.text) }
        if (hit) return "NWS forecast: ${hit.name} ${hit.text}"
    }
    return null
}

def isIceForecastText(txt) {
    if (!txt) return false
    def s = txt.toString().toLowerCase()
    return s.contains("freezing rain") || s.contains("freezing drizzle") || s.contains("sleet") || s.contains("ice pellets") || s.contains("wintry mix")
}

// === EXPECTED-FREEZE DECISION ===
// "Expected" = local climate says freezes are normal now, OR the NWS forecast
// calls for a freeze in the next 18 h. Exception: the first freeze of the
// season (no freezing day logged in 14 days) still alerts if the user wants it.
def computeFreezeExpectation() {
    def r = [expected: false, local: false, forecast: false, firstOfSeason: false, reason: ""]
    if (settings.seasonalFreezeAuto == false) return r

    def flog = state.freezeDayLog ?: []
    def last7 = flog.size() > 7 ? flog.drop(flog.size() - 7) : flog
    def need = Math.max(1, Math.min(7, ((settings.freezeLocalDays != null ? settings.freezeLocalDays : 4) as Integer)))
    def froze7 = last7.count { it }
    r.local = (froze7 >= need)

    def reasons = []
    if (r.local) reasons << "local: ${froze7} of last ${last7.size()} days froze"

    def fc = state.freezeForecast
    if (freezeForecastEnabled() && fc?.periods && (now() - (fc.time ?: 0)) < 43200000) {
        def margin = (settings.freezeForecastMargin != null ? settings.freezeForecastMargin : 2.0) as Double
        def horizon = now() + 64800000  // 18 h
        def hit = fc.periods.find { p -> p.end > now() && p.start < horizon && p.temp <= 32.0 + margin }
        if (hit) {
            r.forecast = true
            reasons << "forecast: ${hit.name} ${hit.day ? 'high' : 'low'} ${Math.round(hit.temp)}°F"
        }
    }

    // The log only gains today's result at midnight, so a freeze that starts
    // after midnight would still look like "first of season" that evening.
    // A routine freeze alert sent in the last 48 h closes that gap.
    def recentlyAlerted = (now() - (state.lastRoutineFreezeAlertTs ?: 0)) < 172800000
    r.firstOfSeason = (settings.freezeAlertFirstOfSeason != false) && !flog.any { it } && !recentlyAlerted
    r.expected = (r.local || r.forecast) && !r.firstOfSeason
    r.reason = reasons.join("; ")
    if ((r.local || r.forecast) && r.firstOfSeason) r.reason += " (first freeze of the season still alerts)"
    return r
}

// === HISTORY, PRESENCE, & HEARTBEAT ===
def markActive() { state.lastHeartbeat = now() }
def motionActiveHandler(evt) { state["motionLastActive_${evt.deviceId}"] = now() }

def updateHistory(historyName, val, maxAgeMs) {
    markActive()
    if (val == null) return
    def cleanVal
    try { cleanVal = val.toString().replaceAll("[^\\d.-]", "").toFloat() } catch(e) { return }
    
    // PRESSURE QUANTIZATION DEFENSE: consumer barometers report in 0.01 inHg
    // steps and gateways only push on change, so the raw stream toggles
    // 27.34 <-> 27.35 endlessly. Each lone step over a short gap computes as
    // a violent "rate" (0.01 over 90s = 0.4 inHg/hr), which caused a
    // documented false tornado warning. A median-of-3 filter makes a lone
    // toggle invisible while real monotonic moves pass with one-sample delay.
    if (historyName == "pressureHistory") {
        def raw3 = state.pressRaw3 ?: []
        if (raw3.size() > 0) {
            def step = Math.abs(cleanVal - (raw3.last() as Float))
            if (step > 0.0001) {
                def res = state.pressResolution ?: 0.01
                if (step < res) state.pressResolution = Math.round(step * 1000.0) / 1000.0
            }
        }
        raw3.add(cleanVal)
        if (raw3.size() > 3) raw3 = raw3.drop(raw3.size() - 3)
        state.pressRaw3 = raw3
        // sort(false) = sorted COPY. 1.x used sort(), which reordered the stored
        // window, so the next drop() discarded the lowest value instead of the
        // oldest and a falling pressure barely registered.
        if (raw3.size() == 3) cleanVal = raw3.sort(false)[1]  // median
    }
    
    if (settings.enableHardwareFilter != false) {
        def hist = state."${historyName}" ?: []
        if (hist.size() > 0) {
            def lastVal = hist.last().value
            def timeDiffSecs = (now() - hist.last().time) / 1000.0
            def delta = Math.abs(cleanVal - lastVal)
            if (timeDiffSecs < 120) {
                // Asymmetric filter: sun-spikes are UPWARD errors; downward temp
                // crashes of 10-20F in minutes are real gust-front signatures
                // (the exact signal the Thunderstorm DNA needs). Only reject
                // implausible jumps in each direction.
                if (historyName == "tempHistory" && cleanVal > lastVal && delta > 8.0) return
                if (historyName == "tempHistory" && cleanVal < lastVal && delta > 20.0) return
                if (historyName == "pressureHistory" && delta > 0.3) return
            }
        }
    }
    
    def hist = state."${historyName}" ?: []
    // probHistory spans 24h but the cap is 288 points; at 1/min evaluation the
    // old code silently truncated the "24-hour" chart to ~4.8 hours. Thin to a
    // 5-minute cadence instead, preserving the max (alarm peaks must survive).
    if (historyName == "probHistory" && hist.size() > 0 && (now() - hist.last().time) < 300000) {
        if (cleanVal > hist.last().value) hist[-1] = [time: hist.last().time, value: cleanVal]
        state."${historyName}" = hist
        return
    }
    hist.add([time: now(), value: cleanVal])
    def cutoff = now() - maxAgeMs
    hist = hist.findAll { it.time >= cutoff }
    
    // Memory Bloat Protection (Limit to 288 points = 24 hours at 5m intervals)
    def maxPoints = 288
    if (hist.size() > maxPoints) {
        hist = hist.drop(hist.size() - maxPoints)
    }
    
    state."${historyName}" = hist
}

def stdHandler(evt) { markSeen(evt); runIn(1, "evaluateMatrix") }
def tempHandler(evt) {
    markSeen(evt)
    def v = null
    try { v = evt.value.toString().replaceAll("[^\\d.-]", "") as Double } catch (e) {}
    if (v != null && isMetric()) v = v * 9.0d / 5.0d + 32.0d
    if (v != null && v >= -80.0d && v <= 140.0d) updateHistory("tempHistory", v, 3600000)
    runIn(1, "evaluateMatrix")
}
// 2.0: whichever pressure attribute fired, the history gets the one chosen
// series (station pressure when the sensor has it), with no offset added.
def pressureHandler(evt) {
    markSeen(evt)
    recordPressure()
    runIn(1, "evaluateMatrix")
}
def recordPressure() {
    def sv = pressureSeriesValue()
    if (sv == null) return
    if (state.pressSeriesKind != sv.kind) {
        if (state.pressSeriesKind != null) logAction("🔧 PRESSURE: tendency now measured on ${sv.kind} pressure (was ${state.pressSeriesKind}); pressure history restarted.")
        state.pressSeriesKind = sv.kind
        state.pressureHistory = []
        state.pressRaw3 = []
    }
    // Two pressure attributes often arrive in the same upload; keep one
    // sample (compared on the RAW value, not the median-filtered one).
    def lr = state.pressLastRaw
    if (lr && (now() - (lr.t as Long)) < 20000 && Math.abs((lr.v as Double) - sv.v) < 0.0001) return
    state.pressLastRaw = [t: now(), v: sv.v]
    updateHistory("pressureHistory", sv.v, 10800000)
}
def windHandler(evt) {
    markSeen(evt)
    def v = null
    try { v = (evt.value.toString().replaceAll("[^\\d.-]", "") as Double) * windUnitFactor() } catch (e) {}
    if (v != null && v >= 0 && v <= 200) updateHistory("windHistory", v, 3600000)
    runIn(1, "evaluateMatrix")
}
def windDirHandler(evt) { markSeen(evt); updateHistory("windDirHistory", evt.value, 3600000); runIn(1, "evaluateMatrix") }
def lightningHasCount() {
    if (!sensorLightning) return false
    try { if (sensorLightning.hasAttribute("lightningCount")) return true } catch (e) {}
    return sensorLightning.currentValue("lightningCount") != null
}
def lightningHandler(evt) {
    markSeen(evt)
    // Ecowitt WH57 emits a cumulative daily "lightningCount", not per-strike distance events.
    // Convert count deltas into individual strike entries so the 30-min window works.
    if (evt.name == "lightningCount") {
        def count = 0
        try { count = evt.value.toString().replaceAll("[^\\d]", "").toInteger() } catch(e) { return }
        // First reading after install/upgrade is a baseline, not new strikes
        // (the daily total would otherwise land as a burst of "new" strikes).
        if (state.lastLightningCount == null) { state.lastLightningCount = count; return }
        def lastCount = state.lastLightningCount ?: 0
        if (count > lastCount) {
            def newStrikes = Math.min(count - lastCount, 50) // sanity cap
            def dist = (getFloat(sensorLightning, ["lightningDistance", "distance"], 99.0) as Double) * distUnitFactor()
            def hist = state.lightningHistory ?: []
            newStrikes.times { hist.add([time: now(), value: dist]) }
            def cutoff = now() - 1800000
            hist = hist.findAll { it.time >= cutoff }
            if (hist.size() > 288) hist = hist.drop(hist.size() - 288)
            state.lightningHistory = hist
        }
        state.lastLightningCount = count // handles midnight reset (count < lastCount) automatically
        markActive()
    } else if (!lightningHasCount()) {
        // Distance-style sensors: value is km/mi to last strike. Detectors that
        // also report a strike COUNT are counted from the count only; 1.x
        // counted every distance update as another strike (double counting).
        def dv = null
        try { dv = (evt.value.toString().replaceAll("[^\\d.-]", "") as Double) * distUnitFactor() } catch (e) {}
        if (dv != null) updateHistory("lightningHistory", dv, 1800000)
    }
    runIn(1, "evaluateMatrix")
}
def luxHandler(evt) { runIn(1, "evaluateMatrix") }

// ==============================================================================
// [SCIENCE: ADVANCED METEOROLOGICAL MATHEMATICS]
// ==============================================================================

def getTrendData(hist, minTimeHr) {
    if (!hist || hist.size() < 2) return [rate: 0.0, diff: 0.0, str: "Gathering Data"]
    def oldest = hist.first()
    def newest = hist.last()
    def diff = newest.value - oldest.value
    def timeSpanHr = (newest.time - oldest.time) / 3600000.0
    if (timeSpanHr < minTimeHr) return [rate: 0.0, diff: diff, str: "Stable"]
    // Least-squares slope over all points. The old two-endpoint slope was
    // hostage to noise in exactly two samples; OLS uses the whole series.
    def n = hist.size()
    def t0 = oldest.time
    def sumX = 0.0, sumY = 0.0, sumXY = 0.0, sumXX = 0.0
    hist.each { pt ->
        def x = (pt.time - t0) / 3600000.0
        def y = pt.value
        sumX += x; sumY += y; sumXY += (x * y); sumXX += (x * x)
    }
    def denom = (n * sumXX) - (sumX * sumX)
    def ratePerHour = (denom == 0.0) ? 0.0 : (((n * sumXY) - (sumX * sumY)) / denom)
    return [rate: ratePerHour, diff: diff, str: "${diff > 0 ? '+' : ''}${String.format('%.2f', ratePerHour)}/hr"]
}

// Trend over only the most recent window. Convective (pop-up storm) signatures
// live at the 10-30 minute scale; full-window trends dilute them.
def getRecentTrend(hist, windowMs, minTimeHr) {
    if (!hist) return [rate: 0.0, diff: 0.0, str: "Gathering Data"]
    def cutoff = now() - windowMs
    return getTrendData(hist.findAll { it.time >= cutoff }, minTimeHr)
}

def getAccelerationData(hist) {
    if (!hist || hist.size() < 4) return 0.0
    def cutoff = now() - 900000 
    def recent = hist.findAll { it.time >= cutoff }
    def older = hist.findAll { it.time < cutoff && it.time >= now() - 3600000 } 
    if (recent.size() < 2 || older.size() < 2) return 0.0
    return getTrendData(recent, 0.1).rate - getTrendData(older, 0.1).rate
}

def getStormVectorData(hist) {
    if (!hist || hist.size() < 4) return [status: "Gathering", speed: 0.0, eta: -1]
    
    def sorted = hist.sort { it.time }
    def mid = (sorted.size() / 2).toInteger()
    def older = sorted[0..(mid-1)]
    def newer = sorted[mid..(sorted.size()-1)]
    
    def oldDist = older.sum { it.value } / older.size()
    def newDist = newer.sum { it.value } / newer.size()
    def oldTime = older.sum { it.time } / older.size()
    def newTime = newer.sum { it.time } / newer.size()
    
    def timeDiffHr = (newTime - oldTime) / 3600000.0
    if (timeDiffHr <= 0) return [status: "Stalled", speed: 0.0, eta: -1]
    
    def distDiff = oldDist - newDist // Positive means approaching
    def speedMph = distDiff / timeDiffHr
    
    if (speedMph > 2.0) {
        def etaHr = newDist / speedMph
        return [status: "Approaching", speed: speedMph, eta: etaHr * 60.0]
    } else if (speedMph < -2.0) {
        return [status: "Departing", speed: Math.abs(speedMph), eta: -1]
    }
    return [status: "Stalled/Lateral", speed: 0.0, eta: -1]
}

def detectGravityWaves(hist) {
    if (!hist || hist.size() < 8) return false
    def cutoff = now() - 1800000 // 30 mins
    def recent = hist.findAll { it.time >= cutoff }
    if (recent.size() < 8) return false
    
    // Threshold must clear the sensor's quantization step by a real margin,
    // or 0.01 inHg toggling reads as permanent "oscillation" (it did).
    def res = state.pressResolution ?: 0.01
    def noiseFloor = Math.max(0.02, 2.5 * res)
    
    int directionChanges = 0
    def lastDeltaSign = 0
    for (int i = 1; i < recent.size(); i++) {
        def delta = recent[i].value - recent[i-1].value
        if (Math.abs(delta) < noiseFloor) continue 
        def currentSign = delta > 0 ? 1 : -1
        if (lastDeltaSign != 0 && currentSign != lastDeltaSign) {
            directionChanges++
        }
        lastDeltaSign = currentSign
    }
    return (directionChanges >= 3)
}

def calculateAirDensity(tF, rh, pInHg) {
    def tC = (tF - 32.0) * (5.0 / 9.0)
    def tK = tC + 273.15
    def pMb = pInHg * 33.8639
    def pPa = pMb * 100.0 
    
    def ePa = (rh / 100.0) * 6.1078 * Math.pow(10, (7.5 * tC) / (tC + 237.3)) * 100.0
    def rDry = 287.058
    def rVapor = 461.495
    
    def pDry = pPa - ePa
    def density = (pDry / (rDry * tK)) + (ePa / (rVapor * tK))
    return density 
}

def calculateWindLoad(rho, windMph) {
    def v_ms = windMph * 0.44704
    def q_Pa = 0.5 * rho * (v_ms * v_ms)
    return isMetric() ? q_Pa : (q_Pa * 0.0208854) // Returns Pa or PSF
}

def calculateWBGT(tF, rh, windMph, lux) {
    def wbF = calculateWetBulb(tF, rh)
    def solarHeat = (lux / 10000.0) * 3.5 
    def windCooling = (windMph * 0.3)
    def globeF = tF + solarHeat - windCooling
    if (globeF < tF) globeF = tF 
    
    def wbgt = (0.7 * wbF) + (0.2 * globeF) + (0.1 * tF)
    return wbgt
}

def calculateVPD(tVal, rh) {
    def tC = (tVal - 32.0) * (5.0 / 9.0)
    def svp = 0.61078 * Math.exp((17.27 * tC) / (tC + 237.3))
    def avp = svp * (rh / 100.0)
    return svp - avp
}

def calculateDewPoint(tF, rh) {
    def tC = (tF - 32.0) * (5.0 / 9.0)
    def gamma = Math.log(rh / 100.0) + ((17.62 * tC) / (243.12 + tC))
    def dpC = (243.12 * gamma) / (17.62 - gamma)
    return (dpC * (9.0 / 5.0)) + 32.0
}

def calculateWetBulb(tF, rh) {
    def tC = (tF - 32.0) * (5.0 / 9.0)
    def twC = tC * Math.atan(0.151977 * Math.sqrt(rh + 8.313659)) + Math.atan(tC + rh) - Math.atan(rh - 1.676331) + 0.00391838 * Math.pow(rh, 1.5) * Math.atan(0.023101 * rh) - 4.686035
    return (twC * (9.0 / 5.0)) + 32.0
}

def getAngularDiff(angle1, angle2) {
    def diff = Math.abs(angle1 - angle2) % 360
    return diff > 180 ? 360 - diff : diff
}

def calculateHeatIndex(tF, rh) {
    if (tF < 80.0) return tF
    def hi = -42.379 + (2.04901523 * tF) + (10.14333127 * rh) - (0.22475541 * tF * rh) - (0.00683783 * tF * tF) - (0.05481717 * rh * rh) + (0.00122874 * tF * tF * rh) + (0.00085282 * tF * rh * rh) - (0.00000199 * tF * tF * rh * rh)
    return hi
}

// === THE MATRIX EVALUATOR ===
// === ROBUST JSON PARSING ===
// Hubitat's async HTTP client doesn't recognize NWS's 'application/geo+json'
// content type and hands handlers a Base64-encoded body instead of text.
// Try native parse, then raw text, then Base64-decoded text.
def parseJsonRobust(response) {
    try { def j = response.getJson(); if (j != null) return j } catch (e) {}
    def body = null
    try { body = response.getData()?.toString() } catch (e) {}
    if (!body) return null
    def slurper = new groovy.json.JsonSlurper()
    try { return slurper.parseText(body) } catch (e) {}
    try {
        def decoded = new String(body.decodeBase64(), "UTF-8")
        return slurper.parseText(decoded)
    } catch (e) {
        logAction("⚠ API response was not parsable as JSON, raw or Base64-decoded (${e.class?.simpleName}).")
        return null
    }
}

// === SECURITY: HTML ESCAPING ===
// Any string that arrives from the internet and later gets rendered into the
// admin UI must be escaped. api.weather.gov is a trusted source, but defense
// in depth costs nothing: a compromised response must render as inert text,
// never as markup or script inside your hub's browser session.
def escapeHtml(txt) {
    if (txt == null) return ""
    return txt.toString().replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll("\"", "&quot;")
}

// === CRASH GUARD ===
// A single uncaught exception used to silently abort an entire evaluation
// cycle (see the old ice-storm scope bug). Now any engine error is caught,
// logged with detail, and surfaced on the dashboard instead of hiding.
def evaluateMatrix() {
    try {
        doEvaluateMatrix()
        state.lastEngineError = null
    } catch (e) {
        state.lastEngineError = "[${new Date(now()).format('MM/dd hh:mm a', location.timeZone)}] ${e}"
        log.error "${app.label}: ENGINE CYCLE FAILED: ${e}"
    }
}

def doEvaluateMatrix() {
    def evalStart = now() 
    
    // MIDNIGHT ROLLOVER - Daily Rain pushed to 7-Day History
    def todayStr = new Date(now()).format("yyyy-MM-dd", location.timeZone)
    if (!state.currentDateStr) state.currentDateStr = todayStr
    
    if (state.currentDateStr != todayStr) {
        def yesterdayTotal = state.currentDayRain ?: 0.0
        def hist = state.sevenDayRain ?: []
        hist.add(0, [date: state.currentDateStr, amount: yesterdayTotal])
        if (hist.size() > 7) hist = hist[0..6]
        state.sevenDayRain = hist

        state.currentDateStr = todayStr
        state.currentDayRain = 0.0
    }
    
    def currentDaily = (getFloat(sensorRainDaily, ["rainDaily", "dailyrainin", "water", "dailyWater"], 0.0) as Double) * rainUnitFactor()
    if (currentDaily > (state.currentDayRain ?: 0.0)) state.currentDayRain = currentDaily

    if (!sensorTemp || !sensorHum || !sensorPress) return
    checkSiteMoved()

    // --- READINGS WITH PER-SENSOR QUALITY CONTROL (2.0) ---
    // Each reading is null when its device is stale, the attribute is
    // missing, or the value is physically implausible. Engines that need a
    // missing reading hold their current state instead of computing from a
    // fake 0 (a missing temperature used to read as 0°F = hard freeze).
    def rd = readSensors()
    state.sensorHealth = rd.health
    reportSensorHealth(rd.health)
    def t = rd.t
    def h = rd.h
    def rawP = rd.rawP
    def ps = rd.ps                                 // station pressure (inHg) or null
    def p = rd.alt != null ? rd.alt : (rawP ?: 0.0)  // altimeter / sea-level (inHg) for display
    state.currentAltimeter = rd.alt
    state.pressureMode = rd.pressMode
    state.mslpMiscalibrated = (rd.alt == null)

    def r = rd.rain != null ? rd.rain : 0.0
    def windVal = rd.wind != null ? rd.wind : 0.0
    def gustVal = rd.gust != null ? rd.gust : windVal
    if (gustVal < windVal) gustVal = windVal
    def windDirVal = rd.windDir != null ? rd.windDir : 0.0
    def luxVal = rd.lux != null ? rd.lux : 0.0
    def strikeCount = state.lightningHistory?.size() ?: 0

    // WS90 piezo haptic rain flag: instant native "first drop" detector
    def piezoRaining = (rd.health.rain?.ok != false) && (sensorRain?.currentValue("raining")?.toString() == "true")

    def isStale = (t == null && h == null && rawP == null)
    state.isStale = isStale

    if (settings.enableThermalSmoothing != false && t != null) {
        def lastT = state.smoothedTemp != null ? state.smoothedTemp : t
        def delta = Math.abs(t - lastT)
        // Only smooth RISING temps (sun-spike protection). Falling temps are
        // gust fronts / outflow boundaries and must pass through undamped.
        if (delta > 3.0 && t > lastT && state.tempHistory?.size() > 0) t = lastT + ((t - lastT) * 0.3)
        state.smoothedTemp = t
    }

    def haveTH = (t != null && h != null && h > 0)
    def dp = haveTH ? calculateDewPoint(t, h) : null
    def dpSpread = haveTH ? Math.max(0.0, t - dp) : 99.0
    if (haveTH) updateHistory("spreadHistory", dpSpread, 3600000)

    def pTrendData = getTrendData(state.pressureHistory, 0.25)
    def pTrend3Hr = getTrendData(state.pressureHistory, 2.5) 
    def pTrend30m = getRecentTrend(state.pressureHistory, 1800000, 0.15) // mesoscale pressure jumps/falls
    def tTrend30m = getRecentTrend(state.tempHistory, 1800000, 0.15)    // outflow boundary temp crashes
    def pAccel = getAccelerationData(state.pressureHistory)
    
    // === DATA QUALITY GATES (accuracy-first) ===
    // A pressure "signal" must exceed 3x the sensor's learned resolution
    // before any rate rule may act on it. One quantization step is not weather.
    def pressRes = state.pressResolution ?: 0.01
    def pressMinDiff = Math.max(0.03, 3.0 * pressRes)
    def press30mPts = (state.pressureHistory ?: []).count { it.time >= now() - 1800000 }
    def pressQuality = (press30mPts >= 5 && Math.abs(pTrend30m.diff) >= pressMinDiff)
    
    // Wind vanes wander freely in near-calm air; direction data is only
    // meteorologically meaningful with real airflow (~8+ mph sustained).
    def windDirValid = (windVal >= 8.0)
    
    // === SEASONAL CLIMATE LEARNING (for Freeze auto-mute) ===
    // Track each day's minimum temp; a rolling 14-day log of "did it freeze"
    // tells us whether the location is in established winter, where routine
    // freeze alerts are noise (everything's already winterized).
    def climDayStr = new Date(now()).format("yyyy-MM-dd", location.timeZone)
    if (state.freezeLogDate != climDayStr) {
        if (state.freezeLogDate != null && state.todayMinTemp != null) {
            def flog = state.freezeDayLog ?: []
            flog.add(state.todayMinTemp <= 32.0)
            if (flog.size() > 14) flog = flog.drop(flog.size() - 14)
            state.freezeDayLog = flog
        }
        state.freezeLogDate = climDayStr
        state.todayMinTemp = t
    }
    if (t != null && (state.todayMinTemp == null || t < state.todayMinTemp)) state.todayMinTemp = t
    def winterLog = state.freezeDayLog ?: []
    state.establishedWinter = (winterLog.size() >= 10 && winterLog.count { it } >= 10)  // kept for reference/dashboards
    def freezeExp = computeFreezeExpectation()
    if (freezeExp.expected != (state.freezeExpected == true)) {
        logAction(freezeExp.expected ? "❄️ FREEZE EXPECTED (${freezeExp.reason}): routine freeze alerts muted; ice alerts stay live." : "❄️ Freeze no longer expected${freezeExp.reason ? ' (' + freezeExp.reason + ')' : ''}: routine freeze alerts active again.")
    }
    state.freezeExpected = freezeExp.expected
    state.freezeExpectedReason = freezeExp.reason
    def tTrendData = getTrendData(state.tempHistory, 0.25)
    def sTrendData = getTrendData(state.spreadHistory, 0.25)
    def wTrendData = getTrendData(state.windHistory, 0.25)
    
    state.pressureTrendStr = pTrendData.str
    state.tempTrendStr = tTrendData.str
    state.spreadTrendStr = sTrendData.str
    state.windTrendStr = sensorWind ? wTrendData.str : "N/A"
    
    def vpd = haveTH ? calculateVPD(t, h) : 0.0
    state.currentVPD = vpd
    
    // No humidity: the wet bulb cannot be above the air temperature, so the
    // air temperature is the safe (ice-favouring) stand-in.
    def wb = haveTH ? calculateWetBulb(t, h) : t
    state.currentWetBulb = wb
    
    // Kinetic & Load Physics. Air density needs the pressure AT the station,
    // not sea-level pressure (the 1.x code used MSLP, ~11% too dense here).
    def airDensity = 1.225
    def windLoad = 0.0
    def densP = ps ?: (rd.alt != null && siteElevationM() != null ? stationFromAltimeter(rd.alt, siteElevationM()) : null)
    if (settings.enableKineticWind != false && haveTH && densP != null) {
        airDensity = calculateAirDensity(t, h, densP)
        state.currentAirDensity = airDensity
        if (sensorWind) {
            // Structural load scales with the PEAK gust (ASCE 7 basis), not sustained wind
            windLoad = calculateWindLoad(airDensity, gustVal)
            state.currentWindLoad = windLoad
        }
    }
    
    def kineticWindMultiplier = airDensity / 1.225 
    
    // Pressure oscillations are shown for interest only; 2.0 no longer adds
    // probability for them (no published basis for a backyard barometer).
    def isGravityWave = false
    if (settings.enableGravityWave != false) {
        isGravityWave = detectGravityWaves(state.pressureHistory)
        state.gravityWaveActive = isGravityWave
    }

    def shiftMagnitude = 0.0
    if (sensorWindDir && state.windDirHistory && state.windDirHistory.size() > 5) {
        def oldestDir = state.windDirHistory.first().value
        shiftMagnitude = getAngularDiff(oldestDir, windDirVal)
    }

    // Storm Vectoring (ETA)
    def vectorData = getStormVectorData(state.lightningHistory)
    if (settings.enableStormVectoring != false && sensorLightning && strikeCount > 0) {
        if (vectorData.status == "Approaching") state.lightningVectorStr = "Approaching @ ${String.format('%.1f', vectorData.speed)} mph (ETA: ~${String.format('%.0f', vectorData.eta)}m)"
        else if (vectorData.status == "Departing") state.lightningVectorStr = "Departing @ ${String.format('%.1f', vectorData.speed)} mph"
        else state.lightningVectorStr = vectorData.status
    } else {
        state.lightningVectorStr = "N/A"
    }

    // INTELLIGENT DEW REJECTION (First Drop Leak Sensors)
    def wetCountRaw = [sensorLeak, sensorLeak2, sensorLeak3].count { it?.currentValue("water") == "wet" }
    def reqWets = settings.leakSensorRequiredCount ? settings.leakSensorRequiredCount.toInteger() : 1
    def rawLeakWet = (wetCountRaw >= reqWets)
    
    if (rawLeakWet) {
        if (!state.leakWetStartTime) state.leakWetStartTime = now()
    } else {
        state.leakWetStartTime = null
    }

    def stuckLeakTimeoutMins = settings.stuckLeakTimeout ?: 60
    def stuckLeakActive = false
    if (rawLeakWet && state.leakWetStartTime && r == 0) {
        if ((now() - state.leakWetStartTime) > (stuckLeakTimeoutMins * 60000)) stuckLeakActive = true
    }
    state.stuckLeakActive = stuckLeakActive
    
    // Dew Rejection filters out morning frost/dew by ensuring wind is calm, lux is low, and spread is tight, unless real rain gauge tips
    def dewRejectionActive = false
    if (rawLeakWet && settings.enableDewRejection != false && r == 0.0) {
        def checkLux = sensorLux ? (luxVal < 100) : true
        def checkWind = sensorWind ? (windVal < 2.0) : true
        if (checkLux && checkWind && dpSpread <= 3.0) dewRejectionActive = true
    }
    state.dewRejectionActive = dewRejectionActive
    
    def leakWet = rawLeakWet && !stuckLeakActive && !dewRejectionActive
    state.leakWetStatus = leakWet

    def debounceMs = (settings.debounceMins ?: 15) * 60000
    def highestProbThisCycle = 0.0

    // --------------------------------------------------------------------------------
    // 2. THUNDERSTORM DNA
    // --------------------------------------------------------------------------------
    def tsWindLost = (sensorWind && rd.wind == null && strikeCount == 0)
    if (settings.enableThunderstorm != false && tsWindLost) {
        holdHazard("Thunderstorm", "wind sensor ${rd.health.wind?.why ?: 'unavailable'}")
    } else if (settings.enableThunderstorm != false && (rd.wind != null || rd.health.lightning?.ok || rd.ps != null || rawP != null)) {
        def tsThreat = 0.0
        def tsMath = ""
        
        if (sensorWind) {
            def kineticWindForce = windVal * kineticWindMultiplier
            tsThreat = (kineticWindForce / 40.0) * 100.0
            if (tsThreat > 100.0) tsThreat = 100.0
            tsMath += "Thrt(Wind): (W[${String.format('%.1f', windVal)}] * Dens[${String.format('%.2f', kineticWindMultiplier)}] / 40) * 100 = ${String.format('%.1f', tsThreat)}%<br>"
        }
        if (strikeCount > 0) {
            def lThreat = (strikeCount / 20.0) * 100.0
            if (lThreat > 100.0) lThreat = 100.0
            if (lThreat > tsThreat) tsThreat = lThreat
            tsMath += "Thrt(Lght): (Strk[${strikeCount}] / 20) * 100 = ${String.format('%.1f', lThreat)}%<br>"
        }
        if (!sensorWind && strikeCount == 0) tsMath += "Thrt: 0.0%<br>"
        
        def tsProb = 0.0
        def tsConf = 40
        def tsWhy = "No localized convective or electrical activity detected."
        tsMath += "Prob Base: 0.0%<br>"
        
        if (isGravityWave) tsMath += "&nbsp;↳ Pressure oscillation noted (information only, no score)<br>"
        
        if (haveTH && t > 75.0 && dp > 65.0) {
            tsProb += 20.0
            tsMath += "&nbsp;↳ PROACTIVE: High Heat/Moisture (CAPE Proxy): +20.0%<br>"
            if (sTrendData.rate <= -2.0) {
                tsProb += 25.0
                tsMath += "&nbsp;&nbsp;↳ PROACTIVE: Severe Atmospheric Squeeze: +25.0%<br>"
            }
        }
        
        // Storm Vectoring Impact
        if (settings.enableStormVectoring != false && vectorData.status == "Approaching") {
            tsProb += 40.0
            tsMath += "&nbsp;↳ REACTIVE: Storm Core Approaching: +40.0%<br>"
        } else if (settings.enableStormVectoring != false && vectorData.status == "Departing") {
            tsProb -= 40.0
            tsMath += "&nbsp;↳ REACTIVE: Storm Core Departing: -40.0%<br>"
        } else if (strikeCount > 0) {
            tsConf += 30
            def lScore = (strikeCount / 10.0) * 40.0
            def lCap = lScore > 50.0 ? 50.0 : lScore
            tsProb += lCap
            tsMath += "&nbsp;↳ REACTIVE: Strikes [${strikeCount}]: +${String.format('%.1f', lCap)}%<br>"
        }
        
        // Gust front: mesohigh pressure JUMP + temp crash at the 30-min scale,
        // gated on GUST (leading indicator) rather than sustained wind.
        if (gustVal > 25.0 && pressQuality && t != null && pTrend30m.rate >= 0.05 && tTrend30m.rate <= -5.0) {
            tsProb += 40.0
            tsMath += "&nbsp;↳ REACTIVE: Gust Front / Mesohigh Pressure Jump (30m scale): +40.0%<br>"
        }
        
        // ADVISORY: airport TAF forecasts thunderstorms. Small nudge + confidence
        // only; a forecast is context, not detection, and can never alert alone.
        def taf = state.tafAdvisory
        if (taf?.ts && (now() - (taf.time ?: 0)) < 21600000) {
            tsProb += 10.0
            tsConf += 15
            tsMath += "&nbsp;↳ ADVISORY: ${taf.station} TAF forecasts thunderstorms: +10.0%<br>"
        }
        
        // NWS severe thunderstorm criterion: gust >= 58 mph
        if (gustVal >= 58.0) {
            tsProb += 40.0
            tsConf += 20
            tsThreat = 100.0
            tsMath += "&nbsp;↳ REACTIVE: NWS Severe Criterion Gust [${String.format('%.1f', gustVal)} mph >= 58]: +40.0%<br>"
        }
        
        // REACTIVE INSTANT DROP (Leak Sensor or WS90 piezo haptic rain)
        // Suppressed during/after irrigation so sprinklers can't fake a storm.
        if ((leakWet || piezoRaining) && !irrigationActive()) {
            tsProb += 30.0
            tsMath += "&nbsp;↳ REACTIVE: First Drop (${piezoRaining ? 'WS90 Piezo' : 'Leak Sensor'}): +30.0%<br>"
        } else if ((leakWet || piezoRaining) && irrigationActive()) {
            tsMath += "&nbsp;↳ First Drop IGNORED: Irrigation active/recent.<br>"
        }
        
        // DRY MICROBURST / VIRGA DETECTION
        if (settings.enableDryMicroburst != false && haveTH && dpSpread >= 25.0 && tTrend30m.diff <= -3.0 && (wTrendData.diff >= 15.0 || gustVal - windVal >= 15.0) && r == 0.0) {
            tsProb += 60.0
            tsThreat = 100.0
            tsMath += "&nbsp;↳ REACTIVE: DRY MICROBURST (Virga Evaporative Cooling): +60.0%<br>"
            tsWhy += "<b>DRY MICROBURST DETECTED:</b> Massive dew point spread + Temp crash + Wind spike with ZERO rain indicates Virga flash-cooling the air column resulting in destructive straight-line downbursts. "
        }
        
        if (sensorWind) tsConf += 30
        
        tsProb = Math.round(tsProb)
        if (tsProb > 100) tsProb = 100
        if (tsProb < 0) tsProb = 0
        tsThreat = Math.round(tsThreat)
        if (tsThreat > 100) tsThreat = 100
        
        tsMath += "<b>Final Prob: ${tsProb}%</b>"
        
        if (tsProb > highestProbThisCycle) highestProbThisCycle = tsProb
        
        if (tsProb > 20 && !tsWhy.contains("MICROBURST")) {
            tsWhy = "Probability is ${tsProb}%. "
            if (vectorData.status == "Approaching") tsWhy += "<b>STORM VECTORING:</b> Active storm cell approaching sensor. "
            if (haveTH && t > 75.0 && dp > 65.0 && sTrendData.rate <= -2.0) tsWhy += "<b>PROACTIVE THREAT:</b> High instability combined with rapid saturation indicates brewing convection. "
            if (gustVal > 25.0 && pTrend30m.rate >= 0.05) tsWhy += "<b>REACTIVE THREAT:</b> A sudden pressure jump, temp crash, and wind spike indicates a severe downdraft/gust front is directly overhead. "
            if (gustVal >= 58.0) tsWhy += "<b>REACTIVE THREAT:</b> Wind gusts have met the official NWS severe thunderstorm criterion (58+ mph). "
        }
        
        processHazardState("Thunderstorm", tsThreat, tsProb, tsConf, tsWhy, tsMath, debounceMs)
    } else if (settings.enableThunderstorm != false) {
        holdHazard("Thunderstorm", "wind, lightning and pressure all unavailable")
    }

    // --------------------------------------------------------------------------------
    // 3. FLOOD DNA
    // --------------------------------------------------------------------------------
    if (settings.enableFlood != false && sensorRain && rd.rain == null) {
        holdHazard("Flood", "rain gauge ${rd.health.rain?.why ?: 'unavailable'}")
    } else if (settings.enableFlood != false) {
        def fMath = ""
        def fThreat = (state.currentDayRain / 3.0) * 100.0
        if (fThreat > 100.0) fThreat = 100.0
        fMath += "Thrt: (Acc[${String.format('%.2f', state.currentDayRain)}] / 3.0) * 100 = ${String.format('%.1f', fThreat)}%<br>"
        
        def fProb = 0.0
        def fConf = 20
        def fWhy = "Ground is absorbing moisture effectively with no extreme rain rates."
        fMath += "Prob Base: 0.0%<br>"
        
        if (sensorRain) fConf += 40
        if (sensorRainDaily) fConf += 40
        
        if (state.currentDayRain >= 1.5 && pTrendData.rate <= -0.03) {
            fProb += 30.0
            fMath += "&nbsp;↳ PREDICTIVE: Saturated Ground + Low Pressure Front: +30.0%<br>"
        }
        
        def rScore = (r / 2.0) * 60.0
        def rCap = rScore > 70.0 ? 70.0 : rScore
        
        // TORRENTIAL-BURST GUARD: instantaneous rate alone is not a flood.
        // A 2-minute cloudburst or hail pinging the piezo head reads as an
        // extreme in/hr rate while depositing almost no water (documented
        // false Flood WARNING, 7/13/26, on a day with 0.02" total QPF).
        // Flood = rate SUSTAINED into accumulation, so until 0.20" has
        // actually fallen today the rate term is capped below warn level.
        // At a genuinely dangerous 1.5 in/hr, 0.20" accrues in ~8 minutes,
        // so real events are delayed only minutes; bursts never fire at all.
        def accumBacked = ((state.currentDayRain ?: 0.0) >= 0.20)
        if (!accumBacked && rCap > 30.0) {
            rCap = 30.0
            fMath += "&nbsp;↳ BURST GUARD: Rate term capped at +30% (only ${String.format('%.2f', state.currentDayRain ?: 0.0)}\" accumulated today; torrential rate without accumulated water = burst or hail ping, not flood).<br>"
        }
        fProb += rCap
        fMath += "&nbsp;↳ Rate [${String.format('%.2f', r)} in/hr]: +${String.format('%.1f', rCap)}%<br>"
        
        if ((leakWet || piezoRaining) && r == 0.0 && !irrigationActive()) {
            fProb += 15.0
            fMath += "&nbsp;↳ Reactive: Instant First Drop (Pre-Gauge): +15.0%<br>"
        }
        
        // Antecedent soil moisture: primary flash-flood predictor. Saturated
        // ground means new rain runs off instead of infiltrating. Requires
        // ACTIVE GAUGE RAIN so sprinklers alone can never trigger it, and is
        // fully suppressed during/after an irrigation cycle.
        def soilAvg = -1.0
        if (sensorSoil) {
            def soilVals = sensorSoil.collect { sm -> getFloat(sm, ["humidity", "soilMoisture"], null) }.findAll { it != null }
            if (soilVals) soilAvg = soilVals.sum() / soilVals.size()
        }
        if (soilAvg >= 0) {
            fConf += 10
            fMath += "&nbsp;↳ Antecedent Soil Moisture Avg: ${String.format('%.0f', soilAvg)}%<br>"
            if (soilAvg >= 85.0 && ((r > 0.0 && accumBacked) || state.currentDayRain >= 0.5) && !irrigationActive()) {
                fProb += 25.0
                fMath += "&nbsp;&nbsp;↳ Saturated Soil + Active Rain (Runoff Mode): +25.0%<br>"
            } else if (soilAvg >= 85.0 && irrigationActive()) {
                fMath += "&nbsp;&nbsp;↳ Saturated Soil IGNORED: Irrigation active/recent.<br>"
            }
        }
        
        def aScore = (state.currentDayRain / 3.0) * 40.0
        def aCap = aScore > 60.0 ? 60.0 : aScore
        fProb += aCap
        fMath += "&nbsp;↳ Accumulation: +${String.format('%.1f', aCap)}%<br>"
        
        fProb = Math.round(fProb)
        if (fProb > 100) fProb = 100
        fThreat = Math.round(fThreat)
        
        fMath += "<b>Final Prob: ${fProb}%</b>"
        
        if (fProb > highestProbThisCycle) highestProbThisCycle = fProb
        
        if (fProb > 0) {
            fWhy = "Threat intensity is ${fThreat}% based on ${String.format('%.2f', state.currentDayRain)} inches of total daily accumulation. "
            if (state.currentDayRain >= 1.5 && pTrendData.rate <= -0.03) fWhy += "<b>PREDICTIVE METRICS ENGAGED:</b> Ground is already heavily saturated and an incoming low-pressure front is detected. "
            if (r > 0) fWhy += "Probability of flash flooding is ${fProb}% driven by a real-time rain rate of ${String.format('%.2f', r)} in/hr. "
        }
        
        processHazardState("Flood", fThreat, fProb, fConf, fWhy, fMath, debounceMs)
    }

    // --------------------------------------------------------------------------------
    // 4. FREEZE & ICE DNA
    // --------------------------------------------------------------------------------
    if (settings.enableFreeze != false && t == null) {
        holdHazard("Freeze", "temperature ${rd.health.temp?.why ?: 'unavailable'}")
    } else if (settings.enableFreeze != false) {
        def frMath = ""
        def frThreat = 0.0
        if (t <= 32.0) {
            frThreat = 100.0
            frMath += "Thrt: Temp[${t}] <= 32 = 100.0%<br>"
        } else {
            frThreat = 100.0 - ((t - 32.0) * 5.0)
            if (frThreat < 0.0) frThreat = 0.0
            frMath += "Thrt: 100 - ((T[${t}] - 32) * 5) = ${String.format('%.1f', frThreat)}%<br>"
        }
        
        def frProb = 0.0
        def frConf = 70
        def frWhy = "Temperatures safely above freezing."
        frMath += "Prob Base: 0.0%<br>"
        
        if (sensorHum) frConf += 30
        def isIceStorm = false
        def frPrevState = state.threatMatrix?.Freeze?.state ?: "Clear"
        def frHoldAlarm = (frPrevState == "ALARM")
        def frClearAt = 32.0 + Math.max(0.0, ((settings.freezeHysteresis != null ? settings.freezeHysteresis : 2.0) as Double))
        
        // ADVISORY: airport TAF forecasts freezing rain/drizzle. Raises
        // confidence and gives lead time context; cannot alert alone.
        def tafF = state.tafAdvisory
        if (tafF?.fzra && (now() - (tafF.time ?: 0)) < 21600000 && t <= 40.0) {
            frProb += 10.0
            frConf += 15
            frMath += "&nbsp;↳ ADVISORY: ${tafF.station} TAF forecasts freezing precipitation: +10.0%<br>"
        }
        
        // ICE DETECTION (fork 1.0.0). The original rule required 31-35F, so
        // freezing rain at 28F was scored as a routine "hard freeze", which the
        // expected-freeze mute would silence. No lower bound now. Local sensors
        // cannot tell freezing rain from wet snow/graupel on the piezo, so an
        // ICE ALARM needs corroboration (TAF FZRA/FZDZ, an NWS ice product, or
        // NWS forecast text with freezing rain/drizzle, sleet or wintry mix).
        // Uncorroborated: ICE WARNING tier. Local-only mode: no way to
        // corroborate, keep the original ALARM behaviour.
        def precipNow = (r > 0.0 || leakWet || piezoRaining)
        def iceLocal = (precipNow && t <= 35.0 && wb <= 32.0)
        if (iceLocal) state.lastIcePrecipTs = now()
        // Hold an ICE state through short breaks in the precipitation (60 min)
        // so showers don't flap ICE ALARM -> ALARM -> ICE ALARM.
        def iceHold = (!iceLocal && frPrevState.contains("ICE") && t <= 35.0 && (now() - (state.lastIcePrecipTs ?: 0)) < 3600000)
        def iceCorr = iceCorroboration()
        if (iceLocal || iceHold) {
            isIceStorm = true
            frThreat = 100
            def why = iceHold ? "precip paused < 60 min, holding" : "liquid precip + sub-freezing wet bulb"
            if (iceCorr || settings.enableInternet == false) {
                frProb = 100
                frMath += "&nbsp;↳ REACTIVE: ICE STORM (${why}; ${iceCorr ?: 'local-only mode'}): 100%<br>"
                frWhy = "<b>ICE ACCUMULATION WARNING:</b> Precipitation is falling into a sub-freezing layer (${iceCorr ?: 'local sensors'}). Flash-freezing and ice accumulation likely. "
            } else {
                def wT = (settings.freezeWarnThresh ?: 50) as Integer
                def aT = (settings.freezeAlarmThresh ?: 80) as Integer
                frProb = Math.max(wT, Math.min(aT - 1, 65))
                frMath += "&nbsp;↳ REACTIVE: Possible ice (${why}), NOT corroborated by TAF/NWS forecast or alerts: ICE WARNING tier ${frProb}%<br>"
                frWhy = "<b>POSSIBLE FREEZING PRECIPITATION:</b> Precipitation at ${String.format('%.1f', t)}° with a sub-freezing wet bulb, but no forecast or official product calls for freezing rain (could be wet snow or sleet on the sensor). "
            }
        }
        else if (t <= 32.0) {
            frProb = 100
            frMath += "&nbsp;↳ Hard Freeze Active: 100%<br>"
        } else if (frHoldAlarm && t < frClearAt) {
            // HYSTERESIS: a sensor sitting at 32-33F used to flip ALARM/WARNING
            // every debounce period (5 flips in 90 min on 11/20/26). Hold the
            // ALARM until the temperature has clearly recovered.
            frProb = 100
            frMath += "&nbsp;↳ Holding Freeze ALARM until ${String.format('%.1f', frClearAt)}° (clear margin): 100%<br>"
        } else if (t <= 40.0 && tTrendData.rate < 0) {
            // BUG FIX (fork 1.0.0): was abs(rate > 0.1 ? rate : 0.1). For a falling
            // temperature rate is negative, so dropRate was always 0.1F/hr and the
            // trajectory never left the ">4 hrs: 30%" branch.
            def dropRate = Math.max(Math.abs(tTrendData.rate as Double), 0.1)
            def hoursToFreeze = (t - 32.0) / dropRate
            frMath += "&nbsp;↳ PREDICTIVE: Est Time to 32°: ${String.format('%.1f', hoursToFreeze)} hrs<br>"
            if (hoursToFreeze <= 2.0) { frProb = 75; frMath += "&nbsp;&nbsp;&nbsp;↳ < 2hrs: +75.0% (WARNING tier)<br>" }
            else if (hoursToFreeze <= 4.0) { frProb = 65; frMath += "&nbsp;&nbsp;&nbsp;↳ < 4hrs: +65.0%<br>" }
            else { frProb = 30; frMath += "&nbsp;&nbsp;&nbsp;↳ > 4hrs: +30.0%<br>" }
        } else {
             frMath += "&nbsp;↳ Trajectory Safe.<br>"
        }
        
        // A prediction (or the TAF nudge) above 32F is a WARNING, never an ALARM:
        // ALARM means it is actually freezing (or the hysteresis hold).
        if (!isIceStorm && t > 32.0 && !(frHoldAlarm && t < frClearAt)) {
            def aCap = ((settings.freezeAlarmThresh ?: 80) as Integer) - 1
            if (frProb > aCap) frProb = aCap
        }
        frProb = Math.round(frProb)
        if (frProb > 100) frProb = 100
        frThreat = Math.round(frThreat)
        if (frThreat > 100) frThreat = 100
        
        frMath += "<b>Final Prob: ${frProb}%</b>"
        
        // Only ice drives DEFCON overdrive polling and the hazard chart. A dry
        // freeze is slow and needs no 1-minute polling (it used to keep DEFCON
        // engaged all night, every night, all winter).
        if (isIceStorm && frProb > highestProbThisCycle) highestProbThisCycle = frProb

        if (frProb > 0 && !isIceStorm) {
            frWhy = "Current temperature is ${String.format('%.1f', t)}°. Threat intensity is ${frThreat}%. "
            if (frProb == 100 && t > 32.0) {
                frWhy += "Just above freezing; the freeze ALARM holds until ${String.format('%.1f', frClearAt)}° so it does not flap. "
            } else if (frProb == 100) {
                frWhy += (dpSpread <= 3.0) ? "Hard Freeze with Hoar Frost actively occurring. " : "Dry Hard Freeze actively occurring. "
            } else {
                frWhy += "Probability is ${frProb}% based on a trajectory of dropping ${String.format('%.2f', Math.abs(tTrendData.rate))}°/hr towards the freezing point. "
            }
        }
        
        // BUG FIX: was "mathEx +=" but mathEx doesn't exist in this scope.
        // That threw MissingPropertyException and aborted the ENTIRE evaluation
        // cycle precisely when an ice storm was detected, and the ICE flag
        // never reached processHazardState.
        if (state.freezeExpected && !isIceStorm) {
            frWhy += "<br><i>❄️ Freeze expected (${state.freezeExpectedReason}): routine freeze pushes and announcements are muted. Ice alerts stay live.</i>"
        }
        if (isIceStorm) frMath += "|ICE_FLAG_TRUE"
        processHazardState("Freeze", frThreat, frProb, frConf, frWhy, frMath, debounceMs)
    }

    // --------------------------------------------------------------------------------
    // 5. SEVERE HEAT DNA (WBGT)
    // --------------------------------------------------------------------------------
    if (settings.enableSevereHeat != false && !haveTH) {
        holdHazard("SevereHeat", "temperature/humidity unavailable")
    } else if (settings.enableSevereHeat != false) {
        def shMath = ""
        def targetHeatMetric = 0.0
        
        if (settings.enableWBGT != false && sensorLux && sensorWind) {
            targetHeatMetric = calculateWBGT(t, h, windVal, luxVal)
            state.currentWBGT = targetHeatMetric
            shMath += "True WBGT (Sun+Wind+Hum): ${String.format('%.1f', targetHeatMetric)}°F<br>"
        } else {
            targetHeatMetric = calculateHeatIndex(t, h)
            shMath += "NWS Shade Heat Index: ${String.format('%.1f', targetHeatMetric)}°F<br>"
        }
        
        def shThreat = 0.0
        if (targetHeatMetric >= 90.0) {
            shThreat = ((targetHeatMetric - 90.0) / 35.0) * 100.0
        }
        if (shThreat > 100.0) shThreat = 100.0
        shMath += "Thrt: ((Metric - 90)/35)*100 = ${String.format('%.1f', shThreat)}%<br>"
        
        def shProb = 0.0
        def shConf = 60
        if (sensorHum) shConf += 40
        def shWhy = "Heat levels are currently safe."
        shMath += "Prob Base: 0.0%<br>"
        
        if (targetHeatMetric >= 90.0) {
            shProb = shThreat 
            shMath += "&nbsp;↳ Base Level: +${String.format('%.1f', shProb)}%<br>"
            if (tTrendData.rate > 0) {
                def bump = (tTrendData.rate / 2.0) * 20.0
                shProb += bump
                shMath += "&nbsp;↳ PREDICTIVE: Heating Bump [${String.format('%.1f', tTrendData.rate)}°/hr]: +${String.format('%.1f', bump)}%<br>"
            }
        } else if (t >= 80.0 && tTrendData.rate > 1.0) {
             shProb = ((t - 80.0)/10.0) * 20.0
             shMath += "&nbsp;↳ PREDICTIVE: Trajectory to 90°: +${String.format('%.1f', shProb)}%<br>"
        }
        
        shProb = Math.round(shProb)
        if (shProb > 100) shProb = 100
        shThreat = Math.round(shThreat)
        
        shMath += "<b>Final Prob: ${shProb}%</b>"
        
        if (shProb > highestProbThisCycle) highestProbThisCycle = shProb
        
        if (shProb > 0) {
            shWhy = "Current Heat Metric is ${String.format('%.1f', targetHeatMetric)}°F. "
            if (targetHeatMetric >= 103.0) shWhy += "<b>DANGER:</b> High risk of heat exhaustion or heat stroke for individuals or pets outside. "
            else if (targetHeatMetric >= 90.0) shWhy += "CAUTION: Prolonged exposure and physical activity may lead to heat exhaustion. "
        }
        
        processHazardState("SevereHeat", shThreat, shProb, shConf, shWhy, shMath, debounceMs)
    }

    // --------------------------------------------------------------------------------
    // 7. FIRE WEATHER (RED FLAG) DNA
    // --------------------------------------------------------------------------------
    if (settings.enableFireWeather != false && !haveTH) {
        holdHazard("FireWeather", "temperature/humidity unavailable")
    } else if (settings.enableFireWeather != false) {
        def fwMath = ""
        def fwThreat = 0.0
        
        def rainWeek = (getFloat(sensorRainWeekly, ["rainWeekly", "weeklyrainin", "weeklyWater"], 0.0) as Double) * rainUnitFactor()
        def past7DayTotal = state.sevenDayRain?.sum { it.amount } ?: 0.0
        def totalFuelMoisture = rainWeek > 0.0 ? rainWeek : past7DayTotal
        fwMath += "Fuel Moisture Proxy (7d Rain): ${String.format('%.2f', totalFuelMoisture)} in<br>"
        
        if (vpd > 1.5 && windVal > 10.0 && totalFuelMoisture < 0.25) {
            fwThreat = ((vpd - 1.5) / 1.5) * 50.0 + ((windVal - 10.0) / 20.0) * 50.0
        }
        if (fwThreat > 100.0) fwThreat = 100.0
        if (fwThreat < 0.0) fwThreat = 0.0
        fwMath += "Thrt: VPD[${String.format('%.2f', vpd)}] + Wind[${String.format('%.1f', windVal)}] = ${String.format('%.1f', fwThreat)}%<br>"
        
        def fwProb = 0.0
        def fwConf = 75
        def fwWhy = "Fire spread risk is currently low."
        fwMath += "Prob Base: 0.0%<br>"
        
        if (totalFuelMoisture < 0.1) {
            if (vpd >= 2.0) {
                fwProb += 40.0
                fwMath += "&nbsp;↳ Severe VPD (Air sucking moisture from fuel): +40.0%<br>"
            } else if (vpd >= 1.5) {
                fwProb += 20.0
                fwMath += "&nbsp;↳ Elevated VPD: +20.0%<br>"
            }
            
            if (windVal >= 25.0) {
                fwProb += 60.0
                fwMath += "&nbsp;↳ Critical Wind Spread Potential: +60.0%<br>"
            } else if (windVal >= 15.0) {
                fwProb += 30.0
                fwMath += "&nbsp;↳ Elevated Wind Spread Potential: +30.0%<br>"
            }
            
            // NWS Red Flag criteria (typical Northern Rockies: RH <= 15% with
            // sustained wind >= 25 mph). Catches cold-season chinook/grass-fire
            // setups that VPD misses, since VPD collapses at low temperatures.
            if (h <= 15.0 && windVal >= 25.0 && fwProb < 80.0) {
                fwProb = 80.0
                fwMath += "&nbsp;↳ NWS Red Flag Criteria Met (RH<=15% + Wind>=25mph): floor 80.0%<br>"
            }
        } else {
            fwMath += "&nbsp;↳ Recent Rain (${String.format('%.2f', totalFuelMoisture)} in) suppressing fire threat.<br>"
        }
        
        fwProb = Math.round(fwProb)
        if (fwProb > 100) fwProb = 100
        fwThreat = Math.round(fwThreat)
        
        fwMath += "<b>Final Prob: ${fwProb}%</b>"
        
        if (fwProb > highestProbThisCycle) highestProbThisCycle = fwProb
        
        if (fwProb > 0) {
            fwWhy = "Probability is ${fwProb}% based on bone-dry local fuel (0.00in rain recently). "
            if (vpd >= 2.0) fwWhy += "<b>DANGER:</b> Extreme Vapor Pressure Deficit (${String.format('%.2f', vpd)} kPa) is actively dehydrating vegetation. "
            if (windVal >= 15.0) fwWhy += "Sustained winds of ${String.format('%.1f', windVal)} mph create critical rapid-spread potential for any spark."
        }
        
        processHazardState("FireWeather", fwThreat, fwProb, fwConf, fwWhy, fwMath, debounceMs)
    }

    if (isStale) highestProbThisCycle = 0.0
    
    // Store highest probability for chart rendering
    updateHistory("probHistory", highestProbThisCycle, 86400000)

    // --- FORENSIC EVENT TRACKING & SUMMARY ---
    // A routine (dry) freeze is not a severe-weather "event": counting it sent
    // a "Weather All-Clear" push every winter morning and overwrote the
    // forensic report with freeze nights. Ice still counts.
    def anyAlarmActive = state.threatMatrix?.any { k, v -> ((v.state == "ALARM" || v.state == "WARNING") && k != "Freeze") || v.state == "ICE ALARM" || v.state == "ICE WARNING" }
    
    if (anyAlarmActive) {
        state.globalThreatActive = true
        state.globalAllClearPending = false 
        if (!state.eventActive) {
            state.eventActive = true
            state.activeEventStats = [
                maxWind: windVal, 
                minPress: p, 
                startTemp: t ?: 0.0, 
                minTemp: t ?: 0.0, 
                startTime: now()
            ]
        } else {
            if (windVal > state.activeEventStats.maxWind) state.activeEventStats.maxWind = windVal
            if (p < state.activeEventStats.minPress && p > 0) state.activeEventStats.minPress = p
            if (t != null && t < (state.activeEventStats.minTemp ?: t)) state.activeEventStats.minTemp = t
        }
    } else if (state.eventActive) {
        state.eventActive = false
        def durMs = now() - state.activeEventStats.startTime
        def durMins = Math.round(durMs / 60000)
        def durStr = durMins > 60 ? "${Math.round(durMins/60)}h ${durMins%60}m" : "${durMins}m"
        def tDrop = state.activeEventStats.startTemp - state.activeEventStats.minTemp
        
        state.lastEventReport = [
            date: new Date(state.activeEventStats.startTime).format("MM/dd/yy hh:mm a", location.timeZone),
            maxWind: state.activeEventStats.maxWind,
            minPress: state.activeEventStats.minPress,
            tempDrop: tDrop > 0 ? tDrop : 0.0,
            duration: durStr
        ]
        logAction("Forensic Event Concluded: Max Wind ${state.lastEventReport.maxWind}mph, Min Press ${state.lastEventReport.minPress}inHg, Duration ${durStr}.")
    }

    // --- GLOBAL STAND-DOWN SEQUENCER (ALL-CLEAR) ---
    if (!anyAlarmActive && state.globalThreatActive) {
        if (pTrendData.rate >= 0.0 && windVal < 15.0) {
            logAction("✅ GLOBAL STAND-DOWN: All hazard matrices have cleared, pressure is stabilizing, and winds are calm.")
            state.globalThreatActive = false
            state.globalAllClearPending = false
            
            dispatchNotification("allClear", [haz: null, from: "event", to: "Clear"])
            state.globalEventAlerted = false
        } else {
            if (!state.globalAllClearPending) {
                logAction("⏳ STAND-DOWN PENDING: Matrix cleared, but waiting for barometric/kinetic stabilization to officially broadcast all-clear.")
                state.globalAllClearPending = true
            }
        }
    }

    // DEFCON WATCHDOG LOGIC
    if (settings.enableDefcon != false) {
        def threshold = settings.defconThresh ?: 25
        if (highestProbThisCycle >= threshold && !state.watchdogActive) {
            state.watchdogActive = true
            logAction("🚨 DEFCON WATCHDOG ENGAGED: Threat probability crossed ${threshold}%. Commencing overdrive polling.")
            watchdogPoll()
        } else if (highestProbThisCycle < threshold && state.watchdogActive) {
            state.watchdogActive = false
            logAction("✅ DEFCON WATCHDOG DISENGAGED: All threat probabilities below ${threshold}%. Returning to standard polling schedule.")
        }
    } else {
        state.watchdogActive = false
    }

    flushPendingNotifications()
    state.lastEvalDuration = now() - evalStart
    updateChildDevice()
}

// === HAZARD STATE ROUTING ===
def processHazardState(haz, threat, prob, conf, why, mathEx, debounceMs) {
    // Confidence means "how much sensor coverage backs this estimate";
    // engines add per-sensor bonuses that can sum past 100 (e.g. base 40 +
    // wind 30 + strikes 30 + TAF 15 = 115). Clamp here so every engine is
    // covered by one fix.
    if (conf > 100) conf = 100
    if (conf < 0) conf = 0
    
    def matrix = state.threatMatrix ?: [:]
    def data = matrix[haz] ?: [threat: 0, prob: 0, conf: 0, state: "Clear", howWhy: "Initializing...", mathEx: "Awaiting calculation cycle...", lastTrig: 0, history: [], currentEvent: null]
    if (!data.history) data.history = []
    
    def pfx = haz.toLowerCase()
    def warnThresh = settings["${pfx}WarnThresh"] ?: 50
    def alarmThresh = settings["${pfx}AlarmThresh"] ?: 80
    
    def targetState = "Clear"
    if (prob >= alarmThresh) targetState = "ALARM"
    else if (prob >= warnThresh) targetState = "WARNING"
    
    def isIceStorm = mathEx.contains("|ICE_FLAG_TRUE")
    if (haz == "Freeze" && isIceStorm && targetState != "Clear") {
        targetState = targetState == "ALARM" ? "ICE ALARM" : "ICE WARNING"
    }
    
    def localTarget = targetState
    if (settings.enableNOAA && settings.noaaTriggersHardware) {
        def officialStatus = nwsHazardStatus(haz)
        // An official Ice Storm / freezing rain product is ice, not a routine
        // freeze: route it to the ICE states so it is never muted.
        if (haz == "Freeze" && nwsIceActive() && officialStatus != "Clear") isIceStorm = true

        if (officialStatus == "ALARM") {
            // A plain Freeze Warning does not confirm local "possible ice":
            // ICE ALARM needs an NWS ice product or local corroboration.
            targetState = isIceStorm ? ((nwsIceActive() || localTarget == "ICE ALARM") ? "ICE ALARM" : localTarget) : "ALARM"
            why = "<b>[🚨 OVERRIDE: NOAA OFFICIAL ALARM TRIGGERED]</b> " + why
        } else if (officialStatus == "WARNING" && targetState == "Clear") {
            targetState = isIceStorm ? "ICE WARNING" : "WARNING"
            why = "<b>[⚠ OVERRIDE: NOAA OFFICIAL WARNING TRIGGERED]</b> " + why
        }
    }
    
    def currentState = data.state
    def timeSinceChange = now() - data.lastTrig
    def allowTransition = false
    
    // 2.0: the "Eye of the Storm" pressure lock is gone. It held Thunderstorm
    // ALARMs whenever sea-level pressure sat under 29.60 inHg, which is an
    // ordinary winter low in the northern US, and it had no published basis.
    if (currentState == "Clear" && targetState != "Clear") allowTransition = true
    else if (currentState.contains("WARNING") && targetState.contains("ALARM")) allowTransition = true
    else if (currentState == targetState) allowTransition = true
    else if (timeSinceChange >= debounceMs) allowTransition = true
    else why += " <br><span style='color:#f0ad4e;'><b>[Safety Hardware Hold Active for ${Math.ceil((debounceMs - timeSinceChange)/60000).toInteger()} more mins]</b></span>"
    
    if (allowTransition && currentState != targetState) {
        logAction("${haz} DNA shifted from ${currentState} to ${targetState}.")
        data.lastTrig = now()
        
        if (currentState != "Clear") {
            def startTs = data.currentEvent?.start ?: (now() - timeSinceChange)
            def durMs = now() - startTs
            def durMins = Math.round(durMs / 60000)
            def durStr = durMins > 60 ? "${Math.round(durMins/60)}h ${durMins%60}m" : "${durMins}m"
            def startStr = new Date(startTs).format("MM/dd HH:mm", location.timeZone)
            
            data.history.add(0, [type: currentState, start: startStr, dur: durStr])
            if (data.history.size() > 5) data.history = data.history[0..4]
        }
        data.currentEvent = [state: targetState, start: now()]
        data.stateSource = source0(targetState, localTarget)

        def warnSw = settings["switch${haz}Warn"]
        def alarmSw = settings["switch${haz}Alarm"]
        
        // Switches/automations: always follow the state (never muted).
        if (targetState.contains("ALARM")) {
            if (haz == "Freeze" && isIceStorm && settings.switchIceAlarm) {
                safeOn(settings.switchIceAlarm)
                safeOff(alarmSw)
            } else {
                safeOn(alarmSw)
                if (haz == "Freeze" && settings.switchIceAlarm) safeOff(settings.switchIceAlarm)
            }
            safeOff(warnSw)
        } else if (targetState.contains("WARNING")) {
            safeOff(alarmSw)
            if (haz == "Freeze" && settings.switchIceAlarm) safeOff(settings.switchIceAlarm)
            safeOn(warnSw)
        } else {
            safeOff(alarmSw)
            safeOff(warnSw)
            if (haz == "Freeze" && settings.switchIceAlarm) safeOff(settings.switchIceAlarm)
            if (currentState != "Clear") stopAllSirens()
        }
        
        // Humans: one dispatcher, one notification type per transition
        // (see NOTIFICATION CENTER). Freeze splits into routine freeze and ice.
        def evKey = null
        if (targetState == "Clear") evKey = (currentState == "Clear") ? null : (haz == "Freeze" ? "freezeClear" : "${pfx}Clear")
        else if (haz == "Freeze") evKey = (targetState.contains("ICE") ? "ice" : "freeze") + (targetState.contains("ALARM") ? "Alarm" : "Warn")
        else evKey = pfx + (targetState.contains("ALARM") ? "Alarm" : "Warn")
        def source = (targetState != localTarget) ? "NWS" : "Local"
        if (evKey) dispatchNotification(evKey, [haz: haz, prob: prob, conf: conf, source: source, from: currentState, to: targetState, wasIce: currentState.contains("ICE"), data: data])
        if (targetState == "Clear") data.episodeAlerted = false
    }
    
    data.threat = threat
    data.prob = prob
    data.conf = conf
    data.state = allowTransition ? targetState : currentState
    data.howWhy = why
    data.mathEx = mathEx
    
    matrix[haz] = data
    state.threatMatrix = matrix
}

// ==============================================================================
// NOTIFICATION CENTER (fork 1.0.0)
// Every human-facing output (push, TTS, siren, sound) goes through one
// dispatcher, keyed by an individual notification type. Each type has its
// own on/off switches and tuning. Switches/automations are NOT affected by
// any of this; they always follow the hazard state.
// Settings are n_<key>_<field>. When a new setting is unset, the old
// per-hazard setting (pre-fork name) is used, so existing configs carry over.
// ==============================================================================
def dnaTtsDefaults() {
    return [
        Thunderstorm: ["Warning. Elevated probability of severe thunderstorms.", "Critical Alert. Severe thunderstorm conditions actively detected."],
        Flood: ["Warning. Elevated probability of flash flood conditions.", "Critical Alert. Flash flood conditions detected locally."],
        Freeze: ["Warning. Rapid trajectory towards freeze detected.", "Alert. Freeze conditions actively occurring."],
        SevereHeat: ["Warning. High heat index detected. Caution advised.", "Critical Alert. Severe heat index conditions detected locally."],
        FireWeather: ["Warning. Elevated fire weather conditions.", "Critical Alert. Extreme Red Flag fire weather actively detected. No outdoor sparks."]
    ]
}

def prettyHaz(id) { return id == 'SevereHeat' ? 'Severe Heat' : (id == 'FireWeather' ? 'Fire Weather' : id) }

def notifyEvents() {
    def tts = dnaTtsDefaults()
    def warnCd = (settings.realertCooldownMins != null ? settings.realertCooldownMins : 120) as Integer
    def evs = []
    ["Thunderstorm", "Flood", "SevereHeat", "FireWeather"].each { id ->
        def p = id.toLowerCase(); def nm = prettyHaz(id)
        evs << [key: "${p}Warn", haz: id, tier: "WARNING", fam: p, group: id, label: "${nm} WARNING",
                hint: "Early heads-up: ingredients assembling.",
                legacy: [push: "${p}WarnNotify", text: "push${id}Warn", tts: "${p}WarnTTS", ttsText: "tts${id}Warn", modes: "${p}WarnModes", devices: "notify${id}"],
                defaults: [push: true, tts: true, ttsText: tts[id][0], cooldown: warnCd, holdoff: 0, maxPerDay: 0, details: true, source: "local"],
                text: "⚠ WARNING: Elevated ${nm} Conditions."]
        evs << [key: "${p}Alarm", haz: id, tier: "ALARM", fam: p, group: id, label: "${nm} ALARM",
                hint: "Act-now alert.",
                legacy: [push: "${p}AlarmNotify", text: "push${id}Alarm", tts: "${p}AlarmTTS", ttsText: "tts${id}Alarm", siren: "${p}AlarmSiren", sound: "${p}AlarmSound", soundUrl: "url${id}Alarm", modes: "${p}AlarmModes", devices: "notify${id}"],
                defaults: [push: true, tts: true, ttsText: tts[id][1], siren: (id == "Thunderstorm"), sound: false, cooldown: 0, holdoff: 0, maxPerDay: 0, details: true, escalate: true, source: "local"],
                text: "🚨 CRITICAL: ${nm} ALARM Conditions Detected!"]
        evs << [key: "${p}Clear", haz: id, tier: "Clear", fam: p, group: id, label: "${nm} cleared",
                hint: "'It's over' message for this hazard.",
                legacy: [push: "${p}ClearNotify", text: "push${id}Clear", devices: "notify${id}"],
                defaults: [push: false, tts: false, cooldown: 0, holdoff: 0, maxPerDay: 0, details: false, onlyIfAlerted: true],
                text: "✅ ${nm} threat has cleared."]
    }
    // Freeze splits into routine freeze and ice, which behave very differently.
    def fz = [push: "freezeWarnNotify", text: "pushFreezeWarn", tts: "freezeWarnTTS", ttsText: "ttsFreezeWarn", modes: "freezeWarnModes", devices: "notifyFreeze"]
    def fa = [push: "freezeAlarmNotify", text: "pushFreezeAlarm", tts: "freezeAlarmTTS", ttsText: "ttsFreezeAlarm", siren: "freezeAlarmSiren", sound: "freezeAlarmSound", soundUrl: "urlFreezeAlarm", modes: "freezeAlarmModes", devices: "notifyFreeze"]
    evs << [key: "freezeWarn", haz: "Freeze", tier: "WARNING", fam: "freeze", group: "Freeze", label: "Freeze WARNING (heading for 32°F)",
            hint: "Temperature trending toward freezing within hours, or an NWS Freeze Watch / Frost Advisory.",
            legacy: fz, defaults: [push: true, tts: true, ttsText: tts.Freeze[0], cooldown: 720, holdoff: 0, maxPerDay: 1, details: true, muteExpected: true, source: "local"],
            text: "⚠ WARNING: Freeze likely within hours."]
    evs << [key: "freezeAlarm", haz: "Freeze", tier: "ALARM", fam: "freeze", group: "Freeze", label: "Freeze ALARM (at/below 32°F)",
            hint: "Hard freeze happening now, or an NWS Freeze Warning.",
            legacy: fa, defaults: [push: true, tts: true, ttsText: tts.Freeze[1], siren: false, sound: false, cooldown: 720, holdoff: 0, maxPerDay: 1, details: true, muteExpected: true, escalate: false, source: "local"],
            text: "🚨 Freeze ALARM: at or below 32°F."]
    evs << [key: "iceWarn", haz: "Freeze", tier: "WARNING", fam: "ice", group: "Freeze", label: "ICE WARNING (possible freezing rain)",
            hint: "Precipitation into sub-freezing air that no forecast/official product confirms (could be wet snow), or an NWS ice advisory. Never muted by the expected-freeze logic.",
            legacy: fz, defaults: [push: true, tts: false, ttsText: "Warning. Possible freezing rain.", cooldown: 360, holdoff: 0, maxPerDay: 0, details: true, source: "local"],
            text: "⚠ WARNING: Possible freezing rain / ice."]
    evs << [key: "iceAlarm", haz: "Freeze", tier: "ALARM", fam: "ice", group: "Freeze", label: "ICE STORM ALARM (freezing rain)",
            hint: "Freezing rain confirmed by TAF, NWS forecast or an NWS ice product. Never muted by the expected-freeze logic.",
            legacy: fa, defaults: [push: true, tts: true, ttsText: "Critical alert. Freezing rain. Ice accumulating.", siren: false, sound: false, cooldown: 360, holdoff: 0, maxPerDay: 0, details: true, escalate: true, source: "local"],
            text: "🚨 CRITICAL: ICE STORM ALARM Conditions Detected!"]
    evs << [key: "freezeClear", haz: "Freeze", tier: "Clear", fam: "freeze", group: "Freeze", label: "Freeze / ice cleared",
            hint: "Back above freezing / ice ended.",
            legacy: [push: "freezeClearNotify", text: "pushFreezeClear", devices: "notifyFreeze"],
            defaults: [push: false, tts: false, cooldown: 0, holdoff: 0, maxPerDay: 0, details: false, onlyIfAlerted: true, muteExpected: true],
            text: "✅ Freeze threat has cleared."]
    evs << [key: "allClear", haz: null, tier: "AllClear", fam: "global", group: "Global", label: "Global all-clear (whole event over)",
            hint: "Every hazard Clear, pressure stabilising and wind under 15 mph. A routine freeze does not count as an event.",
            legacy: [push: "globalAllClearNotify", tts: "globalAllClearTTS", ttsText: "ttsGlobalAllClear", sound: "globalAllClearSound", soundUrl: "urlGlobalAllClear"],
            defaults: [push: true, tts: true, ttsText: "The severe weather event has concluded. All clear.", sound: false, cooldown: 0, maxPerDay: 0, details: false, onlyIfAlerted: true],
            text: "✅ Weather All-Clear: All severe weather threats have passed."]
    // Sensor health (2.0)
    evs << [key: "sensorStale", haz: null, tier: "Info", fam: "sensor", group: "System", label: "Sensor offline",
            hint: "A sensor stopped reporting or reads an impossible value for 10+ minutes past the stale timeout. Hazards that need it pause.",
            legacy: [:], defaults: [push: true, tts: false, cooldown: 0, holdoff: 0, maxPerDay: 4, details: false], text: "⚠ Weather sensor problem."]
    evs << [key: "sensorBack", haz: null, tier: "Info", fam: "sensor", group: "System", label: "Sensor back online",
            hint: "A sensor you were told about is reporting again.",
            legacy: [:], defaults: [push: false, tts: false, cooldown: 0, holdoff: 0, maxPerDay: 3, details: false], text: "✅ Weather sensor reporting again."]
    // Official NWS products (2.0): their own rows, separate from your station.
    def warnOn = [tornado: [push: true, tts: true, siren: true], storm: [push: true, tts: true], wind: [push: true], flood: [push: true, tts: true],
                  freeze: [push: true], ice: [push: true, tts: true], winter: [push: true], cold: [push: true], heat: [push: true], fire: [push: true],
                  tropical: [push: true, tts: true], air: [push: true], civil: [push: true, tts: true], other: [:]]
    def watchOn = [tornado: [push: true], storm: [push: true], flood: [push: true], ice: [push: true], tropical: [push: true]]
    nwsGroups().each { g ->
        def wo = warnOn[g.id] ?: [:]; def wa = watchOn[g.id] ?: [:]
        def prods = nwsProducts().findAll { k, v -> v.grp == g.id }
        def warnNames = prods.findAll { k, v -> v.tier == "ALARM" }.keySet().join(", ")
        def watchNames = prods.findAll { k, v -> v.tier == "WARNING" }.keySet().join(", ")
        def extra = (g.id == "winter") ? " Winter products that call for freezing rain or ice accumulation also count as Ice Storm." : ""
        def xDef = (g.id == "freeze") ? [muteExpected: false] : [:]
        evs << [key: "nws_${g.id}_warn", haz: null, nws: true, tier: "ALARM", fam: "nws_${g.id}", group: "NWS", label: "NWS ${g.icon} ${g.name}: warnings",
                hint: "${warnNames ?: 'Any other NWS warning'}.${extra}", legacy: [:],
                defaults: [push: wo.push == true, tts: wo.tts == true, siren: wo.siren == true, sound: false, ttsText: "Official National Weather Service ${g.name} warning for your location.",
                           cooldown: 0, holdoff: 0, maxPerDay: 0, updates: false] + xDef,
                text: "🚨 NWS:"]
        evs << [key: "nws_${g.id}_watch", haz: null, nws: true, tier: "WARNING", fam: "nws_${g.id}", group: "NWS", label: "NWS ${g.icon} ${g.name}: watches & advisories",
                hint: "${watchNames ?: 'Any other NWS watch, advisory or statement'}.${extra}", legacy: [:],
                defaults: [push: wa.push == true, tts: false, ttsText: "National Weather Service ${g.name} watch or advisory for your location.",
                           cooldown: 0, holdoff: 0, maxPerDay: 0, updates: false] + xDef,
                text: "⚠ NWS:"]
    }
    return evs
}

def notifyEvent(key) { return notifyEvents().find { it.key == key } }

// Resolve one setting: new n_ setting, else legacy setting, else default.
def nSet(ev, field) {
    def v = settings["n_${ev.key}_${field}"]
    if (v != null && v != "") return v
    def lk = ev.legacy?.get(field)
    if (lk) {
        def lv = settings[lk]
        if (lv != null && lv != "") return lv
    }
    return ev.defaults?.get(field)
}

def nInt(ev, field) {
    def v = nSet(ev, field)
    if (v == null || v == "") return 0
    try { return (v as BigDecimal).intValue() } catch (e) { return 0 }
}

def nBool(ev, field) { return nSet(ev, field) == true || nSet(ev, field) == "true" }

def notifyLog(String line) {
    def h = state.notifyLog ?: []
    h.add(0, "[${new Date(now()).format('MM/dd hh:mm a', location.timeZone)}] ${line}")
    if (h.size() > 40) h = h[0..39]
    state.notifyLog = h
}

def mapGet(name, k) { return ((state[name] ?: [:])[k] ?: 0) as Long }
def mapPut(name, k, v) { def m = state[name] ?: [:]; m[k] = v; state[name] = m }

// ctx: haz, prob, conf, source ("Local"/"NWS"), from, to, wasIce, data (hazard matrix entry, mutable)
def dispatchNotification(String key, Map ctx) {
    def ev = notifyEvent(key)
    if (!ev) return
    def outs = []
    if (nBool(ev, "push")) outs << "push"
    if (nBool(ev, "tts")) outs << "TTS"
    if (ev.tier in ["ALARM"] && nBool(ev, "siren")) outs << "siren"
    if (ev.tier in ["ALARM", "AllClear"] && nBool(ev, "sound")) outs << "sound"
    
    // A new transition replaces anything still held for this hazard.
    if (ctx.haz && !ctx.fromPending) {
        def pend = state.pendingNotify ?: [:]
        def stale = pend.findAll { k, p -> p.haz == ctx.haz }.keySet().toList()
        stale.each { k -> pend.remove(k); notifyLog("DROPPED held ${k}: ${ctx.haz} moved on to ${ctx.to}") }
        state.pendingNotify = pend
    }
    if (!outs) { notifyLog("OFF ${ev.label}: no outputs enabled"); return }
    
    def skip = { String why -> notifyLog("SKIPPED ${ev.label}: ${why}") }
    
    def src = nSet(ev, "source") ?: "any"
    if (ctx.source && src == "local" && ctx.source == "NWS") { skip("source filter is Local only (this came from NWS)"); return }
    if (ctx.source && src == "nws" && ctx.source != "NWS") { skip("source filter is NWS only (this was local)"); return }
    
    def minProb = nInt(ev, "minProb")
    if (minProb > 0 && ctx.source != "NWS" && ctx.prob != null && ctx.prob < minProb) { skip("probability ${ctx.prob}% < minimum ${minProb}%"); return }
    
    if (ev.defaults.containsKey("muteExpected") && !ctx.wasIce && !(ctx.nws?.ice) && nBool(ev, "muteExpected") && state.freezeExpected == true && settings.seasonalFreezeAuto != false) {
        skip("freeze expected (${state.freezeExpectedReason ?: 'learned'})"); return
    }
    
    if (ev.defaults.containsKey("onlyIfAlerted") && nBool(ev, "onlyIfAlerted")) {
        def alerted = (ev.tier == "AllClear") ? (state.globalEventAlerted == true) : (ctx.data?.episodeAlerted == true)
        if (!alerted) { skip("nothing was announced for this episode"); return }
    }
    
    def modes = nSet(ev, "modes")
    if (modes && !(modes.contains(location.mode))) { skip("mode ${location.mode} not in allowed modes"); return }
    
    // Quiet hours (optional, per notification)
    def qs = nSet(ev, "quietStart"); def qe = nSet(ev, "quietEnd")
    if (qs && qe) {
        try {
            if (timeOfDayIsBetween(toDateTime(qs), toDateTime(qe), new Date(now()), location.timeZone)) { skip("quiet hours"); return }
        } catch (e) {}
    }
    
    def cdMs = nInt(ev, "cooldown") * 60000L
    if (cdMs > 0) {
        def last = mapGet("notifyLast", ev.key)
        // A WARNING right after an ALARM of the same family is a step-down, not news.
        if (ev.tier == "WARNING") last = Math.max(last, mapGet("famAlarmTs", ev.fam))
        // Ice ending into a plain freeze is a step-down too.
        if (ev.fam == "freeze" && ctx.wasIce) last = Math.max(last, mapGet("famAlarmTs", "ice"))
        def fromWarn = (ctx.from == (ev.fam == "ice" ? "ICE WARNING" : "WARNING"))
        def escalation = (ev.tier == "ALARM" && nBool(ev, "escalate") && fromWarn && mapGet("famWarnTs", ev.fam) > mapGet("famAlarmTs", ev.fam))
        if (!escalation && (now() - last) < cdMs) {
            skip("re-alert cooldown, ${Math.ceil((cdMs - (now() - last)) / 60000).toInteger()} min left"); return
        }
    }
    
    def cap = nInt(ev, "maxPerDay")
    def today = new Date(now()).format("yyyy-MM-dd", location.timeZone)
    def day = state.notifyDay ?: [:]
    if (day.date != today) day = [date: today, counts: [:]]
    def sentToday = (day.counts[ev.key] ?: 0) as Integer
    if (cap > 0 && sentToday >= cap) { skip("daily cap ${cap} reached"); return }
    
    def hold = nInt(ev, "holdoff")
    if (hold > 0 && !ctx.fromPending && ev.haz && ev.tier != "AllClear") {
        def pend = state.pendingNotify ?: [:]
        pend[ev.key] = [key: ev.key, haz: ctx.haz, to: ctx.to, due: now() + hold * 60000L, prob: ctx.prob, conf: ctx.conf, source: ctx.source, from: ctx.from, wasIce: ctx.wasIce]
        state.pendingNotify = pend
        notifyLog("HELD ${ev.label} for ${hold} min (sent only if still ${ctx.to})")
        return
    }
    
    // ---- deliver ----
    def msg = nSet(ev, "text") ?: ev.text
    if (ctx.detail) msg += " " + ctx.detail
    if (nBool(ev, "details") && ctx.haz) {
        def bits = []
        if (ctx.prob != null && ev.tier in ["WARNING", "ALARM"]) bits << "score ${ctx.prob}%"
        def tNow = getFloat(sensorTemp, tempAttrs(), null)
        if (tNow != null && isMetric()) tNow = (tNow as Double) * 9.0 / 5.0 + 32.0
        if (ctx.haz == "Freeze" && tNow != null) bits << "${String.format('%.1f', tNow as Double)}°F"
        if (ctx.source) bits << (ctx.source == "NWS" ? "NWS" : "local sensors")
        // Corroboration (2.0): a local detection says whether NWS agrees.
        def nwsSame = nwsActiveAlerts().findAll { it.haz == ctx.haz }.collect { it.event }.unique()
        if (ctx.source != "NWS" && nwsSame) bits << "NWS agrees: ${nwsSame.join(', ')}"
        if (bits) msg += " (" + bits.join(", ") + ")"
    }
    if ("push" in outs) {
        def devs = nSet(ev, "devices")
        sendNotification(msg, devs ?: null)
    }
    if ("TTS" in outs) playAudio(nSet(ev, "ttsText") ?: msg)
    if ("siren" in outs) triggerSiren()
    if ("sound" in outs) playSoundFile(nSet(ev, "soundUrl"))
    
    mapPut("notifyLast", ev.key, now())
    if (ev.tier == "ALARM") mapPut("famAlarmTs", ev.fam, now())
    if (ev.tier == "WARNING") mapPut("famWarnTs", ev.fam, now())
    day.counts[ev.key] = sentToday + 1
    state.notifyDay = day
    if (ctx.data != null && ev.tier in ["WARNING", "ALARM"]) ctx.data.episodeAlerted = true
    if (ev.fam == "freeze" && ev.tier in ["WARNING", "ALARM"]) state.lastRoutineFreezeAlertTs = now()
    if (ctx.haz && ev.fam != "freeze" && ev.tier in ["WARNING", "ALARM"]) state.globalEventAlerted = true
    notifyLog("SENT ${ev.label} via ${outs.join('+')}: ${msg}")
    return true
}

// Held notifications go out only if the hazard is still in the same state.
def flushPendingNotifications() {
    def pend = state.pendingNotify ?: [:]
    if (!pend) return
    def m = state.threatMatrix ?: [:]
    def done = []
    pend.each { k, p ->
        if (now() < (p.due as Long)) return
        done << k
        def cur = m[p.haz]?.state
        if (cur == p.to) {
            dispatchNotification(k, [haz: p.haz, prob: m[p.haz]?.prob, conf: m[p.haz]?.conf, source: p.source, from: p.from, to: p.to, wasIce: p.wasIce, data: m[p.haz], fromPending: true])
        } else {
            notifyLog("DROPPED held ${k}: ${p.haz} is now ${cur}")
        }
    }
    def pend2 = state.pendingNotify ?: [:]
    done.each { pend2.remove(it) }
    state.pendingNotify = pend2
    state.threatMatrix = m
}

def notificationPage() {
    return dynamicPage(name: "notificationPage", title: "<b>Notification Center</b>", install: false, uninstall: false) {
        section() {
            paragraph "<div style='font-size:13px; color:#333;'>Every push, spoken announcement, siren and sound this app can send, one row each. Switch each one on/off and tune it. <b>Switches and automations are not affected</b>; they always follow the hazard state. Settings start from your previous per-hazard choices.</div>"
            input "realertCooldownMins", "number", title: "Default WARNING re-alert cooldown (minutes)", defaultValue: 120, description: "<i>Starting value for every WARNING row's cooldown that you haven't set yourself.</i>"
            if (app.id) input "testNotifyBtn", "button", title: "🔔 Send a test push to the master notification devices"
        }
        section("<b>Recent notification decisions</b>", hideable: true, hidden: true) {
            def h = state.notifyLog ?: []
            paragraph h ? "<div style='font-family:monospace; font-size:11px; line-height:1.5;'>" + h.collect { escapeHtml(it) }.join("<br>") + "</div>" : "<i>Nothing yet.</i>"
        }
        def groups = [["Thunderstorm", "⛈️"], ["Flood", "🌊"], ["Freeze", "❄️"], ["SevereHeat", "🔥"], ["FireWeather", "🔥🌲"], ["Global", "✅"], ["System", "🔧"], ["NWS", "🏛️"]]
        def evs = notifyEvents()
        groups.each { g ->
            def gid = g[0]
            def gEvs = evs.findAll { it.group == gid }
            def onCount = gEvs.count { e -> nBool(e, "push") || nBool(e, "tts") }
            def gName = gid == 'Global' ? 'Global' : (gid == 'System' ? 'Sensor health' : (gid == 'NWS' ? 'Official NWS products (separate from your station)' : "Your station: " + prettyHaz(gid)))
            section("<b>${g[1]} ${gName}</b> <span style='font-size:11px; color:#777;'>(${onCount} of ${gEvs.size()} on)</span>", hideable: true, hidden: true) {
                if (gid == "NWS") paragraph "<i>One message per new NWS product for your hub's location, with its end time. Updates and extensions of a product you already got are logged, not sent, unless you switch that on. ${settings.enableNOAA ? '' : '<b>NWS alerts (Feed 1) are off in Configuration, so none of these will fire.</b>'}</i>"
                gEvs.each { ev -> notificationRow(ev) }
            }
        }
    }
}

def notificationRow(ev) {
    def k = "n_${ev.key}_"
    def hasNws = (ev.haz != null && ev.tier in ["WARNING", "ALARM"])
    def isNws = (ev.nws == true)
    paragraph "<div style='margin-top:8px; padding:6px 8px; background:#f2f4f7; border-left:4px solid ${ev.tier == 'ALARM' ? '#d9534f' : (ev.tier == 'WARNING' ? '#f0ad4e' : '#5cb85c')}; font-size:13px;'><b>${ev.label}</b><br><span style='font-size:11px; color:#555;'>${ev.hint}</span></div>"
    input "${k}push", "bool", title: "Push notification", defaultValue: nBool(ev, "push"), submitOnChange: true
    input "${k}tts", "bool", title: "Spoken announcement (TTS)", defaultValue: nBool(ev, "tts"), submitOnChange: true
    if (ev.tier == "ALARM") input "${k}siren", "bool", title: "Siren", defaultValue: nBool(ev, "siren")
    if (ev.tier in ["ALARM", "AllClear"]) input "${k}sound", "bool", title: "Sound file / chime", defaultValue: nBool(ev, "sound"), submitOnChange: true
    def anyOn = nBool(ev, "push") || nBool(ev, "tts") || (ev.tier == "ALARM" && nBool(ev, "siren")) || nBool(ev, "sound")
    if (!anyOn) return
    if (nBool(ev, "push")) {
        input "${k}text", "text", title: "Push text (blank = default)", required: false, description: "<i>Default: ${ev.text}</i>"
        if (ev.haz) input "${k}details", "bool", title: "Add details to the push (score, temperature, local vs NWS, whether NWS agrees)", defaultValue: nBool(ev, "details")
        input "${k}devices", "capability.notification", title: "Send to these devices instead of the usual ones (optional)", multiple: true, required: false
    }
    if (nBool(ev, "tts")) input "${k}ttsText", "text", title: "Spoken text", required: false, defaultValue: nSet(ev, "ttsText")
    if (ev.tier in ["ALARM", "AllClear"] && nBool(ev, "sound")) input "${k}soundUrl", "text", title: "Sound file URL or chime #", required: false, defaultValue: nSet(ev, "soundUrl")
    input "${k}cooldown", "number", title: "Re-alert cooldown (minutes, 0 = none)", defaultValue: nInt(ev, "cooldown"), range: "0..10080", description: "<i>Repeats of this notification inside this window are logged, not sent.${ev.tier == 'WARNING' ? ' Also counts from the last ALARM of the same hazard, so an ALARM stepping down to WARNING is quiet.' : ''}</i>"
    if (ev.tier == "ALARM") input "${k}escalate", "bool", title: "Escalation from a WARNING you were told about skips the cooldown", defaultValue: nBool(ev, "escalate")
    if (ev.haz) input "${k}holdoff", "number", title: "Hold-off (minutes, 0 = send immediately)", defaultValue: nInt(ev, "holdoff"), range: "0..240", description: "<i>Wait this long and send only if the hazard is still in this state. Filters short blips.</i>"
    input "${k}maxPerDay", "number", title: "Max per day (0 = unlimited)", defaultValue: nInt(ev, "maxPerDay"), range: "0..100"
    if (isNws) input "${k}updates", "bool", title: "Also send updates / extensions of a product already sent", defaultValue: nBool(ev, "updates")
    if (ev.tier in ["WARNING", "ALARM"] && !isNws) input "${k}minProb", "number", title: "Only if probability at least (%, blank = any)", required: false, range: "0..100", description: "<i>Applies to local detections. NWS-triggered states always pass.</i>"
    if (hasNws) input "${k}source", "enum", title: "Trigger source", options: ["any": "Local sensors or NWS", "local": "Local sensors only", "nws": "NWS only"], defaultValue: nSet(ev, "source") ?: "any"
    if (ev.defaults.containsKey("muteExpected")) input "${k}muteExpected", "bool", title: "Mute while a freeze is expected (local log / NWS forecast)", defaultValue: nBool(ev, "muteExpected")
    if (ev.defaults.containsKey("onlyIfAlerted")) input "${k}onlyIfAlerted", "bool", title: "Only if something was announced first", defaultValue: nBool(ev, "onlyIfAlerted"), description: "<i>No 'all clear' for an event nobody was told about.</i>"
    input "${k}modes", "mode", title: "Only in these modes (blank = all)", multiple: true, required: false, defaultValue: nSet(ev, "modes")
    input "${k}quietStart", "time", title: "Quiet hours start (optional)", required: false
    input "${k}quietEnd", "time", title: "Quiet hours end (optional)", required: false
}

// === HARDWARE ROUTING ===
def shouldSpeakerAnnounce(device) {
    if (settings["isAlwaysOn_${device.id}"]) return true
    def overrideSw = settings["nightSwitch_${device.id}"]
    if (overrideSw && overrideSw.currentValue("switch") == "on") return true
    
    def mappedSensors = settings["motionMap_${device.id}"]
    if (!mappedSensors) return true 
    
    def sensorList = mappedSensors instanceof List ? mappedSensors : [mappedSensors]
    def isActive = false
    for (sensor in sensorList) {
        if (sensor.currentValue("motion") == "active") {
            isActive = true
            break
        }
        def lastActive = state["motionLastActive_${sensor.id}"] ?: 0
        if (now() - lastActive <= 300000) {
            isActive = true
            break
        }
    }
    return isActive
}

def safeOn(dev) {
    if (dev && dev.currentValue("switch") != "on") {
        try { dev.on() } catch (e) { log.error "Failed ON: ${e}" }
    }
}

def safeOff(dev) {
    if (dev && dev.currentValue("switch") != "off") {
        try { dev.off() } catch (e) { log.error "Failed OFF: ${e}" }
    }
}

def sendNotification(msg, overrideDevices = null) {
    def targets = overrideDevices ?: notifyDevices
    if (targets) {
        targets.each { it.deviceNotification(msg) }
        logAction("Push Sent${overrideDevices ? ' (hazard-specific devices)' : ''}: ${msg}")
    }
}

def playAudio(msg, force = false) {
    if (!msg || !audioDevices) return
    def vol = audioVolume ?: 65
    try {
        audioDevices.each { speaker ->
            if (force || shouldSpeakerAnnounce(speaker)) {
                if (speaker.hasCommand("setVolume")) speaker.setVolume(vol)
                if (speaker.hasCommand("speak")) speaker.speak(msg)
                else if (speaker.hasCommand("playText")) speaker.playText(msg)
            } else {
                logAction("TTS suppressed on ${speaker.displayName} - Room Empty/No Motion.")
            }
        }
        logAction("TTS Broadcast Execution Completed.")
    } catch (e) { log.error "TTS routing failed: ${e}" }
}

def playSoundFile(url, force = false) {
    if (!url || !soundDevices) return
    def vol = audioVolume ?: 65
    try {
        soundDevices.each { player ->
            if (force || shouldSpeakerAnnounce(player)) {
                if (player.hasCommand("setVolume")) player.setVolume(vol)
                if (url.trim().isInteger() && player.hasCommand("playSound")) {
                    player.playSound(url.trim().toInteger())
                } else if (player.hasCommand("playTrack")) {
                    player.playTrack(url.trim())
                }
            } else {
                logAction("Sound file suppressed on ${player.displayName} - Room Empty/No Motion.")
            }
        }
        logAction("Sound File Routing Completed.")
    } catch (e) { log.error "Audio routing failed: ${e}" }
}

def triggerSiren() {
    if (sirenDevices) {
        try {
            sirenDevices.each { siren -> 
                if (siren.hasCommand("siren")) siren.siren()
                else if (siren.hasCommand("on")) siren.on() 
            }
            logAction("SIREN TRIGGERED")
        } catch (e) { log.error "Siren trigger failed: ${e}" }
    }
}

def stopAllSirens() {
    if (sirenDevices) {
        try {
            sirenDevices.each { siren ->
                if (siren.hasCommand("off")) siren.off()
            }
            logAction("All Sirens Silenced")
        } catch (e) { log.error "Siren shutoff failed: ${e}" }
    }
}

def createChildDevice() {
    def deviceId = "SevWeather-${app.id}"
    if (!getChildDevice(deviceId)) {
        try {
            addChildDevice("franktank", "Advanced Severe Weather Information Device", deviceId, null, [name: "Advanced Severe Weather Information Device", label: "Advanced Severe Weather Information Device", isComponent: false])
            logAction("Child device successfully created.")
            updateChildDevice()
        } catch (e) { log.error "Failed to create child device. Ensure the driver is installed. ${e}" }
    }
}

// === CHILD DEVICE SYNCHRONIZATION ===
def updateChildDevice() {
    def child = getChildDevice("SevWeather-${app.id}")
    if (child) {
        def matrix = state.threatMatrix
        def hazards = hazardIds()
        
        def highestState = "Clear"
        hazards.each { haz ->
            if (!matrix[haz]) return
            def s = matrix[haz].state
            if (haz == "Freeze" && !s.contains("ICE")) return  // routine freeze is not a severe-weather state
            if (s.contains("ALARM")) highestState = "ALARM"
            else if (s.contains("WARNING") && highestState == "Clear") highestState = "WARNING"
        }
        
        def statsMap = [
            globalState: highestState,
            pressureTrend: state.pressureTrendStr ?: "Stable",
            tempTrend: state.tempTrendStr ?: "Stable",
            windTrend: state.windTrendStr ?: "Stable",
            currentDayRain: state.currentDayRain ?: 0.0
        ]
        
        child.updateData(matrix, statsMap)
    }
}

// === LOGGING ===
def logAction(msg) { 
    if(txtEnable) log.info "${app.label}: ${msg}"
    def h = state.actionHistory ?: []
    h.add(0, "[${new Date(now()).format("MM/dd hh:mm a", location.timeZone)}] ${msg}")
    if(h.size() > 30) h = h[0..29]
    state.actionHistory = h 
}

def logInfo(msg) { if(txtEnable) log.info "${app.label}: ${msg}" }
def modeChangeHandler(evt) { evaluateMatrix() }