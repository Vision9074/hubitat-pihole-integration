/**
 *  Pihole Server
 *  ==============
 *  Driver for a single Pihole, exposing its statistics as attributes and its
 *  blocking state as a switch.
 *
 *  Normally created and configured by the "Pihole Integration" app, but it is
 *  self-contained: fill in the hostname and password below and it works on its
 *  own. The app adds fleet management, notifications and the group device.
 *
 *  ---------------------------------------------------------------------------
 *  SWITCH SEMANTICS
 *  ---------------------------------------------------------------------------
 *  on()  = blocking enabled  (ads are being blocked)
 *  off() = blocking disabled (ads get through)
 *
 *  off() uses the "Default off duration" preference. Left at 0 it disables
 *  blocking until something turns it back on, which is what the Pihole web
 *  interface calls "indefinitely". Set it to, say, 5 and every off() becomes a
 *  five minute reprieve that Pihole itself reverses - safer for a rule that
 *  might not get to run its "on" half.
 *
 *  ---------------------------------------------------------------------------
 *  API NOTES
 *  ---------------------------------------------------------------------------
 *  Pihole v6 REST API (Pihole 6.0, February 2025, and later). Pihole v5's
 *  /admin/api.php is a different, incompatible interface and is not supported.
 *
 *  Auth:  POST /api/auth with {"password": "..."} returns a session id. It is
 *         sent back as the X-FTL-SID header. Sessions expire after `validity`
 *         seconds of inactivity, are bound to the calling IP, and Pihole only
 *         permits a limited number at once - so this driver holds exactly one,
 *         renews it before it lapses, and hands it back on DELETE /api/auth
 *         whenever the connection details change.
 *
 *  Polling is split in two. The fast cycle is what changes minute to minute:
 *         GET /api/stats/summary   queries, clients, gravity size
 *         GET /api/padd            blocking state, cache, CPU temperature, host
 *         GET /api/dns/blocking    only while blocking is off, for the timer
 *  The slow cycle is what does not:
 *         GET /api/info/system     uptime, load, memory
 *         GET /api/info/version    core/FTL/web versions and updates
 *         GET /api/info/messages/count   Pihole's own diagnostics count
 *
 *  /api/padd is the aggregate endpoint PADD uses. It is not present on every
 *  v6 build, so a 404 there permanently falls back to /api/dns/blocking plus
 *  /api/info/sensors instead of losing the poll.
 *
 *  Scope: this driver reads statistics and toggles blocking, and deliberately
 *  stops there. Gravity updates, DNS restarts and log flushes belong to the
 *  Pihole's own interface - they are slow, disruptive, gated behind
 *  `webserver.api.allow_destructive`, and give no useful feedback through an
 *  attribute, so wrapping them in a Hubitat command only adds ways to break a
 *  working Pihole from a dashboard tile.
 *
 *  License: MIT
 */

import groovy.json.JsonOutput
import groovy.transform.Field

metadata {
    definition(name: "Pihole Server", namespace: "vision9074", author: "vision9074",
               importUrl: "https://raw.githubusercontent.com/vision9074/hubitat-pihole-integration/main/PiholeServer.groovy") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "Initialize"
        capability "Switch"                  // on = blocking enabled
        capability "TemperatureMeasurement"  // the Pihole host's CPU temperature

        // --- Blocking
        attribute "blocking", "enum", ["enabled", "disabled", "failed", "unknown"]
        attribute "blockingTimer", "number"        // seconds until Pihole re-enables itself
        attribute "blockingResumesAt", "string"

        // --- Query statistics
        attribute "queriesTotal", "number"
        attribute "queriesBlocked", "number"
        attribute "percentBlocked", "number"       // %
        attribute "queriesForwarded", "number"
        attribute "queriesCached", "number"
        attribute "queryFrequency", "number"       // queries per second
        attribute "uniqueDomains", "number"
        attribute "clientsActive", "number"
        attribute "clientsTotal", "number"

        // --- Blocklist
        attribute "domainsBlocked", "number"       // gravity size
        attribute "gravityLastUpdate", "string"

        // --- Notable domains and clients
        attribute "topDomain", "string"
        attribute "topBlockedDomain", "string"
        attribute "topClient", "string"
        attribute "recentBlocked", "string"

        // --- Host health
        attribute "cpuLoad", "number"              // % (1 minute load average)
        attribute "memoryUsage", "number"          // % of RAM in use
        attribute "uptime", "number"               // seconds
        attribute "uptimeText", "string"

        // --- Identity and versions
        attribute "hostName", "string"
        attribute "hostModel", "string"
        attribute "ipAddress", "string"
        attribute "versionCore", "string"
        attribute "versionFtl", "string"
        attribute "versionWeb", "string"
        attribute "updateAvailable", "enum", ["yes", "no"]
        attribute "diagnosticMessages", "number"   // Pihole's own warnings
        attribute "privacyLevel", "number"
        attribute "dhcpActive", "enum", ["on", "off"]

        // --- Driver health
        attribute "connectionStatus", "enum", ["online", "offline"]
        attribute "lastError", "string"
        attribute "lastUpdate", "string"

        // --- Commands beyond the standard capabilities
        command "enableBlocking"
        command "disableBlocking", [[name: "Minutes", type: "NUMBER",
                                     description: "Blank or 0 disables blocking until it is turned back on"]]
    }

    preferences {
        input name: "piHost", type: "text", title: "Hostname or IP address",
              description: "e.g. 192.168.1.10 or pi.hole", required: true
        input name: "piPort", type: "number", title: "Port", defaultValue: 80,
              range: "1..65535", required: true
        input name: "piHttps", type: "bool", title: "Use HTTPS", defaultValue: false
        input name: "piIgnoreSsl", type: "bool", title: "Accept a self-signed certificate (HTTPS only)",
              defaultValue: true
        input name: "piPassword", type: "password",
              title: "Password or application password",
              description: "Use an application password if two-factor authentication is enabled"
        // These option maps are spelled out rather than referencing the @Field
        // constants below: the metadata block is evaluated before the script
        // body, and reading a @Field from it is not reliable on Hubitat.
        input name: "pollInterval", type: "enum", title: "Statistics refresh",
              defaultValue: "5", required: true,
              options: ["1": "Every minute", "5": "Every 5 minutes (recommended)",
                        "10": "Every 10 minutes", "15": "Every 15 minutes",
                        "30": "Every 30 minutes", "60": "Every hour"]
        input name: "detailInterval", type: "enum", title: "Uptime, version and diagnostics refresh",
              defaultValue: "30", required: true,
              options: ["15": "Every 15 minutes", "30": "Every 30 minutes (recommended)",
                        "60": "Every hour", "360": "Every 6 hours"]
        input name: "defaultOffMinutes", type: "number",
              title: "Default off duration in minutes (0 = until turned back on)",
              defaultValue: 0, range: "0..1440", required: true
        input name: "logEnable", type: "bool", title: "Enable debug logging (auto-off after 30 minutes)",
              defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptive text logging", defaultValue: true
    }
}

// =============================================================================
// Constants
// =============================================================================
@Field static final Map POLL_OPTIONS = [
    "1" : "Every minute",
    "5" : "Every 5 minutes (recommended)",
    "10": "Every 10 minutes",
    "15": "Every 15 minutes",
    "30": "Every 30 minutes",
    "60": "Every hour"
]

@Field static final Map DETAIL_OPTIONS = [
    "15" : "Every 15 minutes",
    "30" : "Every 30 minutes (recommended)",
    "60" : "Every hour",
    "360": "Every 6 hours"
]

/** Renew the session this many seconds before Pihole would expire it. */
@Field static final int SESSION_SKEW_SECONDS = 30
/** Tolerate this many consecutive failures before declaring the host offline. */
@Field static final int OFFLINE_AFTER_FAILURES = 2
@Field static final int HTTP_TIMEOUT_SECONDS = 20

// =============================================================================
// Lifecycle
// =============================================================================
void installed() {
    logDebug "installed()"
    sendEvent(name: "connectionStatus", value: "offline")
    sendEvent(name: "blocking", value: "unknown")
}

void updated() {
    logDebug "updated()"
    // The connection details may have just changed, so drop the old session.
    // releaseSession() targets the host the session was issued by, not whatever
    // is in the settings now.
    releaseSession()
    initialize()
}

void initialize() {
    logDebug "initialize()"
    unschedule()
    if (logEnable) runIn(1800, "disableDebugLogging")
    if (!settings.piHost) {
        logWarn "No hostname set; polling is not scheduled."
        return
    }
    applySchedule()
    runIn(2, "refresh")
}

void uninstalled() {
    releaseSession()
}

void disableDebugLogging() {
    logInfo "Debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

/**
 * Called by the parent app. Only the keys present are applied, so the app can
 * push a schedule change without touching the credentials.
 */
void setConnection(Map cfg) {
    if (!cfg) return

    boolean connectionChanged =
        (cfg.containsKey("host")     && cfg.host?.toString()  != settings.piHost) ||
        (cfg.containsKey("port")     && (cfg.port as Integer)  != (settings.piPort as Integer)) ||
        (cfg.containsKey("https")    && (cfg.https == true)    != (settings.piHttps == true)) ||
        (cfg.containsKey("password") && cfg.password)

    // Hand the old session back before the settings that identify it change.
    if (connectionChanged) releaseSession()

    if (cfg.containsKey("host"))      device.updateSetting("piHost", [value: cfg.host?.toString(), type: "text"])
    if (cfg.containsKey("port"))      device.updateSetting("piPort", [value: (cfg.port ?: 80) as Integer, type: "number"])
    if (cfg.containsKey("https"))     device.updateSetting("piHttps", [value: (cfg.https == true), type: "bool"])
    if (cfg.containsKey("ignoreSsl")) device.updateSetting("piIgnoreSsl", [value: (cfg.ignoreSsl != false), type: "bool"])
    if (cfg.password)                 device.updateSetting("piPassword", [value: cfg.password.toString(), type: "password"])
    if (cfg.pollInterval)             device.updateSetting("pollInterval", [value: cfg.pollInterval.toString(), type: "enum"])
    if (cfg.detailInterval)           device.updateSetting("detailInterval", [value: cfg.detailInterval.toString(), type: "enum"])

    logDebug "setConnection applied${connectionChanged ? ' (connection changed)' : ''}"
    initialize()
}

private void applySchedule() {
    switch ((settings.pollInterval ?: "5").toString()) {
        case "1":  runEvery1Minute("poll");   break
        case "10": runEvery10Minutes("poll"); break
        case "15": runEvery15Minutes("poll"); break
        case "30": runEvery30Minutes("poll"); break
        case "60": runEvery1Hour("poll");     break
        default:   runEvery5Minutes("poll")
    }
    switch ((settings.detailInterval ?: "30").toString()) {
        case "15":  runEvery15Minutes("pollDetails"); break
        case "60":  runEvery1Hour("pollDetails");     break
        // No runEvery6Hours, so pin it to the hour with cron.
        case "360": schedule("0 0 0/6 * * ?", "pollDetails"); break
        default:    runEvery30Minutes("pollDetails")
    }
    logDebug "Scheduled: stats ${POLL_OPTIONS[(settings.pollInterval ?: '5').toString()]}, " +
             "details ${DETAIL_OPTIONS[(settings.detailInterval ?: '30').toString()]}"
}

// =============================================================================
// Polling
// =============================================================================
void refresh() {
    poll()
    pollDetails()
}

/** The fast cycle: everything that moves between one minute and the next. */
void poll() {
    if (!ensureSession()) return
    apiGet("/stats/summary", "summaryCallback", [op: "summary"])
    if (state.noPadd) {
        apiGet("/dns/blocking", "blockingCallback", [op: "blocking"])
        apiGet("/info/sensors", "sensorsCallback", [op: "sensors"])
    } else {
        apiGet("/padd", "paddCallback", [op: "padd"])
    }
}

/** The slow cycle: uptime, versions and Pihole's own diagnostics. */
void pollDetails() {
    if (!ensureSession()) return
    apiGet("/info/system", "systemCallback", [op: "system"])
    apiGet("/info/version", "versionCallback", [op: "version"])
    apiGet("/info/messages/count", "messagesCallback", [op: "messages"])
}

void summaryCallback(resp, Map data) {
    Map json = readResponse(resp, data)
    if (json == null) return

    Map queries = json.queries as Map
    if (queries) {
        updateAttr("queriesTotal", intOf(queries.total))
        updateAttr("queriesBlocked", intOf(queries.blocked))
        updateAttr("percentBlocked", round(decimalOf(queries.percent_blocked), 1), "%")
        updateAttr("queriesForwarded", intOf(queries.forwarded))
        updateAttr("queriesCached", intOf(queries.cached))
        updateAttr("uniqueDomains", intOf(queries.unique_domains))
        updateAttr("queryFrequency", round(decimalOf(queries.frequency), 2), "/s")
    }
    Map clients = json.clients as Map
    if (clients) {
        updateAttr("clientsActive", intOf(clients.active))
        updateAttr("clientsTotal", intOf(clients.total))
    }
    Map gravity = json.gravity as Map
    if (gravity) {
        updateAttr("domainsBlocked", intOf(gravity.domains_being_blocked))
        updateAttr("gravityLastUpdate", epochToDateTime(gravity.last_update))
    }
    markOnline()
}

void paddCallback(resp, Map data) {
    // /api/padd is absent on some v6 builds. Fall back permanently rather than
    // losing the blocking state and CPU temperature on every poll.
    if (resp.hasError() && resp.status == 404 && !state.noPadd) {
        state.noPadd = true
        logWarn "This Pihole has no /api/padd endpoint; using /api/dns/blocking and " +
                "/api/info/sensors instead."
        runIn(3, "poll", [overwrite: true])
        return
    }
    Map json = readResponse(resp, data)
    if (json == null) return

    applyBlocking(json.blocking?.toString(), null)

    Map queries = json.queries as Map
    if (queries?.query_frequency != null) {
        updateAttr("queryFrequency", round(decimalOf(queries.query_frequency), 2), "/s")
    }
    if (json.gravity_size != null)  updateAttr("domainsBlocked", intOf(json.gravity_size))
    if (json.active_clients != null) updateAttr("clientsActive", intOf(json.active_clients))

    updateAttr("topDomain", json.top_domain?.toString())
    updateAttr("topBlockedDomain", json.top_blocked?.toString())
    updateAttr("topClient", json.top_client?.toString())
    updateAttr("recentBlocked", json.recent_blocked?.toString())
    updateAttr("hostName", json.node_name?.toString())
    updateAttr("hostModel", json.host_model?.toString())
    updateAttr("ipAddress", (json.iface as Map)?.v4?.addr?.toString())

    Map config = json.config as Map
    if (config) {
        updateAttr("privacyLevel", intOf(config.privacy_level))
        updateAttr("dhcpActive", config.dhcp_active == null ? null : (config.dhcp_active ? "on" : "off"))
    }

    applySensors(json.sensors as Map)
    markOnline()
}

void blockingCallback(resp, Map data) {
    Map json = readResponse(resp, data)
    if (json == null) return
    applyBlocking(json.blocking?.toString(), json.timer)
    markOnline()
}

void sensorsCallback(resp, Map data) {
    Map json = readResponse(resp, data)
    if (json == null) return
    applySensors(json.sensors as Map)
    markOnline()
}

void systemCallback(resp, Map data) {
    Map json = readResponse(resp, data)
    if (json == null) return
    Map sys = json.system as Map
    if (!sys) return

    Long up = longOf(sys.uptime)
    if (up != null) {
        updateAttr("uptime", up, "s")
        updateAttr("uptimeText", formatDuration(up))
    }
    // "load.percent" is the raw load average already expressed against the
    // core count, which is the number worth alerting on.
    def loadPercent = (sys.cpu as Map)?.load?.percent
    if (loadPercent instanceof List && loadPercent) {
        updateAttr("cpuLoad", round(decimalOf(loadPercent[0]), 1), "%")
    }
    def ramUsed = ((sys.memory as Map)?.ram as Map)?.get("%used")
    if (ramUsed != null) {
        updateAttr("memoryUsage", round(decimalOf(ramUsed), 1), "%")
    }
    markOnline()
}

void versionCallback(resp, Map data) {
    Map json = readResponse(resp, data)
    if (json == null) return
    Map version = json.version as Map
    if (!version) return

    updateAttr("versionCore", localVersion(version.core as Map))
    updateAttr("versionFtl",  localVersion(version.ftl as Map))
    updateAttr("versionWeb",  localVersion(version.web as Map))

    boolean outdated = componentOutdated(version.core as Map) ||
                       componentOutdated(version.ftl as Map)  ||
                       componentOutdated(version.web as Map)  ||
                       dockerOutdated(version.docker as Map)
    updateAttr("updateAvailable", outdated ? "yes" : "no")
    markOnline()
}

void messagesCallback(resp, Map data) {
    Map json = readResponse(resp, data)
    if (json == null) return
    updateAttr("diagnosticMessages", intOf(json.count))
    markOnline()
}

// =============================================================================
// Commands
// =============================================================================
void on()  { enableBlocking() }

void off() {
    Integer mins = (settings.defaultOffMinutes ?: 0) as Integer
    disableBlocking(mins > 0 ? mins : null)
}

void enableBlocking() {
    logDebug "enableBlocking()"
    setBlocking(true, null)
}

void disableBlocking(minutes = null) {
    Integer mins = minutes == null ? null : (decimalOf(minutes)?.intValue())
    logDebug "disableBlocking(${mins ?: 'no timer'})"
    setBlocking(false, (mins != null && mins > 0) ? mins : null)
}

private void setBlocking(boolean enabled, Integer minutes) {
    Map body = [blocking: enabled]
    // The API rejects a timer of 0; absent means "no timer".
    if (minutes) body.timer = minutes * 60

    // Reflect the request straight away. Pihole applies it immediately, but
    // the confirming response is a round trip away - which is also why there is
    // no point chasing the timer here.
    applyBlocking(enabled ? "enabled" : "disabled", minutes ? (minutes * 60) : null, false)
    apiPost("/dns/blocking", body, "blockingSetCallback", [op: "setBlocking"])
}

void blockingSetCallback(resp, Map data) {
    Map json = readResponse(resp, data)
    if (json == null) return
    applyBlocking(json.blocking?.toString(), json.timer)
    logInfo "${device.displayName} blocking is ${json.blocking}"
    markOnline()
    parent?.childUpdated(device)
}

// =============================================================================
// Applying values
// =============================================================================
private void applyBlocking(String blocking, timer, boolean chaseTimer = true) {
    if (!blocking) return
    updateAttr("blocking", blocking)
    updateAttr("switch", blocking == "enabled" ? "on" : "off")

    Integer secs = timer == null ? 0 : (decimalOf(timer)?.intValue() ?: 0)
    updateAttr("blockingTimer", secs, "s")
    updateAttr("blockingResumesAt",
               secs > 0 ? new Date(now() + (secs * 1000L)).format("yyyy-MM-dd HH:mm:ss", location.timeZone)
                        : "")

    // While blocking is off there is a countdown worth showing, and /api/padd
    // does not carry it. One extra request, only in the state that needs it.
    if (chaseTimer && blocking != "enabled" && timer == null && !state.noPadd && !state.chasingTimer) {
        state.chasingTimer = true
        apiGet("/dns/blocking", "blockingCallback", [op: "blocking"])
    } else if (blocking == "enabled") {
        state.remove("chasingTimer")
    }
    if (timer != null) state.remove("chasingTimer")
}

private void applySensors(Map sensors) {
    if (!sensors) return
    BigDecimal temp = decimalOf(sensors.cpu_temp)
    if (temp == null) return
    // Pihole reports in whichever unit its own settings use; Hubitat wants the
    // hub's scale.
    String unit = (sensors.unit ?: "C").toString().toUpperCase()
    BigDecimal celsius
    switch (unit) {
        case "F": celsius = fahrenheitToCelsius(temp); break
        case "K": celsius = temp - 273.15G; break
        default:  celsius = temp
    }
    BigDecimal shown = (location.temperatureScale == "F") ? celsiusToFahrenheit(celsius) : celsius
    updateAttr("temperature", round(shown, 1), "°${location.temperatureScale}")
}

private String localVersion(Map component) {
    return component?.local?.version?.toString()
}

private boolean componentOutdated(Map component) {
    String local  = component?.local?.version?.toString()
    String remote = component?.remote?.version?.toString()
    if (!local || !remote) return false
    // A checkout of a development branch reports versions that never match the
    // published release, so do not nag about it.
    String branch = component?.local?.branch?.toString()
    if (branch && branch != "master" && branch != "main") return false
    return local != remote
}

private boolean dockerOutdated(Map docker) {
    String local  = docker?.local?.toString()
    String remote = docker?.remote?.toString()
    if (!local || !remote) return false
    return local != remote
}

// =============================================================================
// Session handling
// =============================================================================
private String apiBase() {
    String host = settings.piHost?.toString()?.trim()
    if (!host) return null
    Integer port = (settings.piPort ?: 80) as Integer
    return "${settings.piHttps ? 'https' : 'http'}://${host}:${port}".toString()
}

/**
 * Guarantees a usable session, renewing it just before Pihole would expire it.
 * Synchronous by design: this is one short request on the local network and
 * every caller needs the answer before it can build its own request.
 */
private boolean ensureSession() {
    if (!settings.piHost) {
        noteError("No hostname configured")
        return false
    }
    Long expiry = (state.sidExpiresAt ?: 0L) as Long
    if (state.sid != null && now() < (expiry - (SESSION_SKEW_SECONDS * 1000L))) {
        return true
    }
    return login()
}

private boolean login() {
    String base = apiBase()
    if (!base) return false

    Map params = [
        uri               : base,
        path              : "/api/auth",
        requestContentType: "application/json",
        contentType       : "application/json",
        body              : [password: (settings.piPassword ?: "")],
        timeout           : HTTP_TIMEOUT_SECONDS
    ]
    if (settings.piHttps && settings.piIgnoreSsl != false) params.ignoreSSLIssues = true

    boolean ok = false
    try {
        httpPost(params) { resp ->
            Map session = (resp.data as Map)?.session as Map
            if (session?.valid) {
                // A Pihole with no API password returns a valid session and no
                // sid; "" then means "authenticated, send no header".
                state.sid = session.sid ?: ""
                state.sidBase = base
                Long validity = longOf(session.validity) ?: 300L
                // Negative validity means the session does not expire.
                state.sidExpiresAt = now() + ((validity > 0 ? validity : 3600L) * 1000L)
                logDebug "Authenticated${session.sid ? '' : ' (this Pihole has no API password)'}"
                clearError()
                ok = true
            } else {
                noteError("Pihole refused the password")
                markOffline()
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        switch (e.statusCode) {
            case 401:
                noteError("Password rejected. If two-factor authentication is enabled, use an application password.")
                break
            case 404:
                noteError("No /api/auth endpoint. This integration needs Pihole v6 or later.")
                break
            case 429:
                noteError("Pihole is rate limiting login attempts")
                break
            default:
                noteError("Login failed: HTTP ${e.statusCode}")
        }
        markOffline()
    } catch (Exception e) {
        noteError("Cannot reach ${base}: ${e.message}")
        markOffline()
    }
    return ok
}

/**
 * Hands the session back so it stops occupying one of Pihole's limited
 * concurrent session slots. Uses the host the session was issued by, which is
 * not necessarily the one in the settings now.
 */
private void releaseSession() {
    String base = state.sidBase
    String sid = state.sid
    state.remove("sid")
    state.remove("sidBase")
    state.remove("sidExpiresAt")
    if (!base || !sid) return

    Map params = [uri: base, path: "/api/auth", headers: ["X-FTL-SID": sid], timeout: 10]
    if (base.startsWith("https") && settings.piIgnoreSsl != false) params.ignoreSSLIssues = true
    try {
        httpDelete(params) { resp -> }
        logDebug "Session released"
    } catch (Exception e) {
        logDebug "Could not release the session (harmless, it will time out): ${e.message}"
    }
}

// =============================================================================
// HTTP transport
// =============================================================================
private Map apiParams(String path, Map body = null) {
    Map params = [
        uri        : apiBase(),
        path       : "/api" + path,
        contentType: "application/json",
        timeout    : HTTP_TIMEOUT_SECONDS,
        headers    : [:]
    ]
    if (state.sid) params.headers["X-FTL-SID"] = state.sid
    if (settings.piHttps && settings.piIgnoreSsl != false) params.ignoreSSLIssues = true
    if (body != null) {
        params.requestContentType = "application/json"
        params.body = JsonOutput.toJson(body)
    }
    return params
}

private void apiGet(String path, String callback, Map data) {
    Map ctx = (data ?: [:]) + [path: path, method: "GET", callback: callback]
    try {
        asynchttpGet(callback, apiParams(path), ctx)
    } catch (Exception e) {
        noteError("${ctx.op}: could not send request: ${e.message}")
    }
}

private void apiPost(String path, Map body, String callback, Map data) {
    if (!ensureSession()) return
    Map ctx = (data ?: [:]) + [path: path, method: "POST", callback: callback, body: body]
    try {
        asynchttpPost(callback, apiParams(path, body), ctx)
    } catch (Exception e) {
        noteError("${ctx.op}: could not send request: ${e.message}")
    }
}

/**
 * Shared response handling for every async callback. Deals with an expired
 * session (one re-auth and retry), refusals, and transport failures.
 *
 * Returns the decoded body, or null when the caller should stop.
 */
private Map readResponse(resp, Map data) {
    String op = data?.op ?: "request"

    if (resp.hasError()) {
        Integer status = resp.status
        if (status == 401 && !data.retried) {
            logDebug "${op}: session expired, re-authenticating and retrying once"
            state.remove("sid")
            state.remove("sidExpiresAt")
            if (login()) reissue(data)
            return null
        }
        if (status == 403) {
            // The session authenticated but is not permitted to do this. With an
            // application password that usually means 'webserver.api.app_sudo'.
            noteError("Pihole refused ${op} (403). The session is authenticated but not " +
                      "permitted; check the API permissions for this password.")
            return null
        }
        if (status == 429) {
            noteError("Pihole is rate limiting requests (429); reduce the poll frequency.")
            return null
        }
        // status is null when nothing answered at all, which is the common case
        // for a powered-down Pihole and deserves plainer wording than "HTTP null".
        noteError(status ? "${op} failed: HTTP ${status} ${resp.getErrorMessage() ?: ''}"
                         : "${op} failed: no response from ${apiBase()}")
        markOffline()
        return null
    }

    try {
        return resp.getJson() as Map
    } catch (Exception e) {
        noteError("${op}: Pihole did not return JSON (${e.message})")
        return null
    }
}

private void reissue(Map data) {
    Map params = apiParams(data.path as String, data.body as Map)
    Map ctx = data + [retried: true]
    try {
        if (data.method == "POST") {
            asynchttpPost(data.callback as String, params, ctx)
        } else {
            asynchttpGet(data.callback as String, params, ctx)
        }
    } catch (Exception e) {
        noteError("${data.op}: retry failed: ${e.message}")
    }
}

// =============================================================================
// Health
// =============================================================================
private void markOnline() {
    state.failures = 0
    updateAttr("connectionStatus", "online")
    updateAttr("lastUpdate", new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
    // One poll produces several callbacks. Debounce so the app recomputes the
    // group device once per cycle rather than once per response.
    runIn(2, "notifyParent", [overwrite: true])
}

void notifyParent() {
    parent?.childUpdated(device)
}

private void markOffline() {
    // A single failed request is usually a blip. Two in a row is a problem, and
    // that is what the app's offline notification keys off.
    int failures = ((state.failures ?: 0) as Integer) + 1
    state.failures = failures
    if (failures < OFFLINE_AFTER_FAILURES) {
        logDebug "Request failed (${failures}/${OFFLINE_AFTER_FAILURES} before reporting offline)"
        return
    }
    if (device.currentValue("connectionStatus") != "offline") {
        updateAttr("connectionStatus", "offline")
        updateAttr("blocking", "unknown")
        parent?.childUpdated(device)
    }
}

/**
 * The String parameter matters: it coerces GStrings at the call boundary, and
 * only plain Strings should be written into state or events.
 */
private void noteError(String msg) {
    logError msg
    sendEvent(name: "lastError", value: msg)
}

private void clearError() {
    if (device.currentValue("lastError")) sendEvent(name: "lastError", value: "")
}

// =============================================================================
// Conversions
// =============================================================================
private BigDecimal decimalOf(value) {
    if (value == null) return null
    if (value instanceof BigDecimal) return value
    try { return new BigDecimal(value.toString()) } catch (Exception ignored) { return null }
}

private Integer intOf(value) {
    return decimalOf(value)?.setScale(0, java.math.RoundingMode.HALF_UP)?.intValue()
}

private Long longOf(value) {
    return decimalOf(value)?.longValue()
}

private BigDecimal round(BigDecimal value, int places) {
    return value?.setScale(places, java.math.RoundingMode.HALF_UP)
}

private String epochToDateTime(value) {
    Long secs = longOf(value)
    if (secs == null || secs <= 0) return null
    return new Date(secs * 1000L).format("yyyy-MM-dd HH:mm", location.timeZone)
}

private String formatDuration(Long seconds) {
    if (seconds == null || seconds < 0) return null
    long days = seconds.intdiv(86400)
    long hours = (seconds % 86400).intdiv(3600)
    long minutes = (seconds % 3600).intdiv(60)
    if (days > 0)  return "${days}d ${hours}h ${minutes}m"
    if (hours > 0) return "${hours}h ${minutes}m"
    return "${minutes}m"
}

// =============================================================================
// Event helper
// =============================================================================
/** Sends an event only when the value actually changed, keeping the log clean. */
private void updateAttr(String name, value, String unit = null) {
    if (value == null) return
    def current = device.currentValue(name)
    if (current != null && current.toString() == value.toString()) return

    Map evt = [name: name, value: value]
    if (unit) evt.unit = unit
    evt.descriptionText = "${device.displayName} ${name} is ${value}${unit ?: ''}"
    sendEvent(evt)
    logInfo evt.descriptionText
}

// =============================================================================
// Logging
// =============================================================================
private void logDebug(String msg) { if (settings.logEnable != false) log.debug "${device.displayName}: ${msg}" }
private void logInfo(String msg)  { if (settings.txtEnable != false) log.info  "${msg}" }
private void logWarn(String msg)  { log.warn  "${device.displayName}: ${msg}" }
private void logError(String msg) { log.error "${device.displayName}: ${msg}" }
