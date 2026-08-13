/**
 *  Pihole Integration
 *  ===================
 *  Hubitat integration app for one or more Pihole servers.
 *
 *  This app is the fleet manager. It:
 *    - keeps the list of Piholes and provisions one child device per server,
 *    - verifies host, port and password before a device is ever created,
 *    - pushes the shared polling schedule down to every child,
 *    - optionally maintains a "Pihole Group" device that aggregates the fleet
 *      and can enable/disable blocking everywhere at once,
 *    - raises notifications for offline servers, low block rates and available
 *      Pihole updates.
 *
 *  Requires the companion drivers: "Pihole Server" and, if the group device is
 *  enabled, "Pihole Group".
 *
 *  ---------------------------------------------------------------------------
 *  WHERE THE HTTP LIVES
 *  ---------------------------------------------------------------------------
 *  Unlike a cloud integration, each Pihole is an independent LAN endpoint with
 *  its own credentials and its own API session. There is nothing to share
 *  between them, so each child device owns its own connection and polls itself.
 *  This app provisions and coordinates; it only talks to a Pihole directly when
 *  testing a connection from the setup page.
 *
 *  The one consequence worth knowing: passwords are stored in the child device's
 *  settings, never in this app's state. App state is visible in plain text on
 *  the app status page, so the password typed here is handed to the device and
 *  then erased from the app.
 *
 *  ---------------------------------------------------------------------------
 *  API NOTES
 *  ---------------------------------------------------------------------------
 *  Targets the Pihole v6 REST API (Pihole 6.0, February 2025, and later).
 *  The v5 `/admin/api.php` interface is not supported; a v5 host is detected
 *  during the connection test and reported as such rather than failing with a
 *  confusing 404.
 *
 *  Auth: POST /api/auth with {"password": "..."} returns a session id (SID).
 *        Pihole allows only a limited number of concurrent sessions, so the
 *        connection test logs out again as soon as it is done.
 *
 *  If two-factor authentication is enabled on the Pihole, a plain password
 *  cannot be used unattended. Create an *application password* in
 *  Settings -> Web interface / API and use that instead.
 *
 *  License: MIT
 */

import groovy.transform.Field

definition(
    name: "Pihole Integration",
    namespace: "vision9074",
    author: "vision9074",
    description: "Monitor and control one or more Pihole servers from Hubitat",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    singleThreaded: true,
    installOnOpen: true,
    documentationLink: "https://github.com/vision9074/hubitat-pihole-integration",
    importUrl: "https://raw.githubusercontent.com/vision9074/hubitat-pihole-integration/main/PiholeIntegration.groovy"
)

preferences {
    page(name: "mainPage")
    page(name: "serverPage")
}

// =============================================================================
// Constants
// =============================================================================
@Field static final String DRIVER_NAMESPACE = "vision9074"
@Field static final String SERVER_DRIVER    = "Pihole Server"
@Field static final String GROUP_DRIVER     = "Pihole Group"

/** Connection tests are synchronous, so they get a short leash. */
@Field static final int TEST_TIMEOUT_SECONDS = 10

@Field static final Map POLL_OPTIONS = [
    "1" : "Every minute",
    "5" : "Every 5 minutes (recommended)",
    "10": "Every 10 minutes",
    "15": "Every 15 minutes",
    "30": "Every 30 minutes",
    "60": "Every hour"
]

/**
 * Uptime, versions and diagnostic counts change slowly and cost three extra
 * requests, so they are polled on their own, longer cycle.
 */
@Field static final Map DETAIL_OPTIONS = [
    "15" : "Every 15 minutes",
    "30" : "Every 30 minutes (recommended)",
    "60" : "Every hour",
    "360": "Every 6 hours"
]

// =============================================================================
// Pages
// =============================================================================
def mainPage() {
    // Reaching the list always means the edit form is finished with, so let the
    // next visit to serverPage re-prime itself from scratch.
    state.remove("formPrimedFor")

    dynamicPage(name: "mainPage", title: "<h2>Pihole Integration</h2>", install: true, uninstall: true) {

        section("<b>Piholes</b>") {
            List servers = serverList()
            if (!servers) {
                paragraph "No Piholes have been added yet."
            }
            servers.each { Map srv ->
                href name: "edit_${srv.id}", page: "serverPage", params: [id: srv.id],
                     title: "${srv.label} &mdash; ${serverStatusLine(srv)}",
                     description: serverUrl(srv)
            }
            href name: "addServer", page: "serverPage", params: [id: ""],
                 title: "&#10133; Add a Pihole", description: ""
        }

        if (serverList()) {
            section("<b>Polling</b>") {
                input name: "pollInterval", type: "enum", width: 6,
                      title: "Refresh queries and blocking status",
                      options: POLL_OPTIONS, defaultValue: "5", required: true, submitOnChange: true
                input name: "detailInterval", type: "enum", width: 6,
                      title: "Refresh uptime, versions and diagnostics",
                      options: DETAIL_OPTIONS, defaultValue: "30", required: true, submitOnChange: true
                paragraph "Each Pihole polls itself on this schedule. The fast cycle is two " +
                          "requests, the slow cycle is three, and both are local to your network."
            }

            section("<b>Group device</b>") {
                input name: "createGroup", type: "bool", submitOnChange: true,
                      title: "Create a <b>Pihole Group</b> device", defaultValue: false
                paragraph "One device that summarises every Pihole and turns blocking on or off " +
                          "across all of them at once &mdash; the usual reason to run a second " +
                          "Pihole is redundancy, and disabling only one of a redundant pair " +
                          "does nothing."
                if (settings.createGroup) {
                    input name: "groupRequiresAll", type: "bool", width: 6,
                          title: "Group reports <i>on</i> only when <b>every</b> Pihole is blocking",
                          defaultValue: true
                }
            }

            section("<b>Notifications</b>") {
                input name: "notifyDevices", type: "capability.notification", multiple: true,
                      title: "Send notifications to", required: false, submitOnChange: true
                if (settings.notifyDevices) {
                    input name: "notifyOffline", type: "bool", width: 6,
                          title: "A Pihole stops responding", defaultValue: true
                    input name: "notifyBlockingOff", type: "bool", width: 6,
                          title: "Blocking is turned off", defaultValue: false
                    input name: "notifyUpdates", type: "bool", width: 6,
                          title: "A Pihole update is available", defaultValue: false
                    input name: "percentThreshold", type: "decimal", width: 6,
                          title: "Block rate falls below this % (blank to disable)", required: false
                }
            }

            section("<b>Options</b>") {
                input name: "logEnable", type: "bool", width: 6,
                      title: "Enable debug logging (auto-off after 30 minutes)", defaultValue: true
                input name: "txtEnable", type: "bool", width: 6,
                      title: "Enable descriptive text logging", defaultValue: true
            }
        }

        section {
            label title: "Name this instance", required: false
        }
    }
}

def serverPage(params) {
    // Hubitat re-invokes this method on every submitOnChange, and depending on
    // the version it may replay the original href params. Priming the form on
    // params alone would therefore wipe out whatever is being typed, so it is
    // latched: prime once per target, and mainPage clears the latch on the way
    // back out.
    if (params?.containsKey("id")) state.editingId = (params.id ?: "").toString()
    String id = (state.editingId ?: "").toString()
    if (state.formPrimedFor != id) {
        primeServerForm(id)
        state.formPrimedFor = id
    }
    Map existing = findServer(id)

    dynamicPage(name: "serverPage", title: "<h2>${existing ? existing.label : 'Add a Pihole'}</h2>") {
        section {
            input name: "srvLabel", type: "text", title: "Name", required: true, submitOnChange: true
            input name: "srvHost", type: "text", title: "Hostname or IP address",
                  description: "e.g. 192.168.1.10 or pi.hole", required: true, submitOnChange: true
            input name: "srvPort", type: "number", title: "Port", defaultValue: 80,
                  range: "1..65535", required: true, submitOnChange: true, width: 6
            input name: "srvHttps", type: "bool", title: "Use HTTPS", defaultValue: false,
                  submitOnChange: true, width: 6
            if (settings.srvHttps) {
                input name: "srvIgnoreSsl", type: "bool", submitOnChange: true,
                      title: "Accept the self-signed certificate", defaultValue: true
            }
            // Not marked required: a Pihole with no API password set is
            // unusual but legal, and the connection test is a better judge of
            // whether the password is acceptable than a blank check is.
            input name: "srvPassword", type: "password", submitOnChange: true, required: false,
                  title: existing ? "Password (leave blank to keep the current one)"
                                  : "Password or application password"
        }

        section {
            input name: "btnTest", type: "button", title: "Test Connection", width: 4
            input name: "btnSave", type: "button",
                  title: existing ? "Save Changes" : "Add Pihole", width: 4
            if (existing) {
                input name: "btnDelete", type: "button", title: "Remove This Pihole", width: 4
            }
            if (state.serverMessage) paragraph state.serverMessage
        }

        section {
            paragraph "<small>If this Pihole has two-factor authentication enabled, a plain " +
                      "password will not work unattended. Create an <b>application password</b> " +
                      "under Settings &rarr; Web interface / API and paste that instead.<br>" +
                      "Docker installs usually publish the web interface on a port other than 80.</small>"
        }

        section {
            href name: "backToMain", page: "mainPage", title: "&larr; Back to the Pihole list", description: ""
        }
    }
}

/** Loads an existing server into the form, or clears it for a new one. */
private void primeServerForm(String id) {
    Map srv = findServer(id)
    app.updateSetting("srvLabel",  [value: (srv?.label ?: ""), type: "text"])
    app.updateSetting("srvHost",   [value: (srv?.host ?: ""), type: "text"])
    app.updateSetting("srvPort",   [value: (srv?.port ?: 80), type: "number"])
    app.updateSetting("srvHttps",  [value: (srv?.https ?: false), type: "bool"])
    app.updateSetting("srvIgnoreSsl", [value: (srv?.ignoreSsl != false), type: "bool"])
    app.removeSetting("srvPassword")
    state.remove("serverMessage")
}

void appButtonHandler(String btn) {
    switch (btn) {
        case "btnTest":   testConnectionFromForm(); break
        case "btnSave":   saveServerFromForm();     break
        case "btnDelete": deleteServerFromForm();   break
    }
}

// =============================================================================
// Server list (state.servers never holds a password)
// =============================================================================
private List<Map> serverList() {
    return (state.servers ?: []) as List<Map>
}

private Map findServer(String id) {
    if (!id) return null
    return serverList().find { it.id == id }
}

private String serverUrl(Map srv) {
    return "${srv.https ? 'https' : 'http'}://${srv.host}:${srv.port}".toString()
}

private String serverStatusLine(Map srv) {
    def cd = getChildDevice(serverDni(srv.id as String))
    if (!cd) return "not yet created"
    if (cd.currentValue("connectionStatus") == "offline") return "&#9888; offline"
    String blocking = cd.currentValue("blocking") ?: "unknown"
    String pct = cd.currentValue("percentBlocked")?.toString()
    return "blocking ${blocking}${pct ? ", ${pct}% of queries" : ''}"
}

private String serverDni(String id) { return "pihole-${id}".toString() }
private String groupDni()           { return "pihole-group-${app.id}".toString() }

// =============================================================================
// Add / edit / remove
// =============================================================================
/** Normalises whatever the user pasted into a bare host. */
private String cleanHost(String raw) {
    String h = raw?.trim()
    if (!h) return null
    h = h.replaceFirst(/(?i)^https?:\/\//, "")   // a pasted URL
    h = h.replaceFirst(/\/.*$/, "")              // any path
    h = h.replaceFirst(/:\d+$/, "")              // an inline port; the port input owns that
    return h ?: null
}

private Map readServerForm() {
    return [
        label    : (settings.srvLabel ?: "").toString().trim(),
        host     : cleanHost(settings.srvHost as String),
        port     : (settings.srvPort ?: 80) as Integer,
        https    : (settings.srvHttps == true),
        ignoreSsl: (settings.srvIgnoreSsl != false),
        password : (settings.srvPassword ?: "").toString()
    ]
}

private void testConnectionFromForm() {
    Map form = readServerForm()
    if (!form.host) {
        state.serverMessage = "&#10060; Enter a hostname or IP address first."
        return
    }
    if (!form.password && findServer(state.editingId as String)) {
        state.serverMessage = "&#9888; Leave the password blank only when saving. To test, " +
                              "re-enter it &mdash; the stored password lives on the device, " +
                              "not in this app."
        return
    }
    Map result = probe(form)
    // toString() matters: probe builds GStrings, and only plain Strings should
    // be written into state.
    state.serverMessage = result.message?.toString()
}

private void saveServerFromForm() {
    Map form = readServerForm()
    if (!form.label) {
        state.serverMessage = "&#10060; Give this Pihole a name."
        return
    }
    if (!form.host) {
        state.serverMessage = "&#10060; Enter a hostname or IP address."
        return
    }
    String id = (state.editingId ?: "").toString()
    Map existing = findServer(id)

    // Verify on the way in for a new Pihole, so a bad password is caught
    // before a device exists. On an edit with the password box left blank there
    // is nothing to verify with, and refusing to save would block harmless
    // changes like renaming or moving to a new port.
    if (form.password || !existing) {
        Map result = probe(form)
        if (!result.ok) {
            state.serverMessage = result.message?.toString()
            return
        }
    }

    List<Map> servers = serverList()
    if (!existing) {
        id = UUID.randomUUID().toString().substring(0, 8)
        existing = [id: id]
        servers = servers + [existing]
        state.editingId = id
    } else {
        servers = servers.collect { it.id == id ? existing : it }
    }
    existing.label     = form.label
    existing.host      = form.host
    existing.port      = form.port
    existing.https     = form.https
    existing.ignoreSsl = form.ignoreSsl
    state.servers = servers

    def cd = provisionChild(existing, form.password as String)
    // The password has been handed to the device. Do not leave a copy behind in
    // the app's own settings.
    app.removeSetting("srvPassword")

    state.serverMessage = cd ? "&#9989; ${form.label} saved.".toString()
                             : "&#10060; Could not create the device. Check the logs."
    if (cd) logInfo "Saved Pihole '${form.label}' at ${serverUrl(existing)}"
    refreshGroupDevice()
}

private void deleteServerFromForm() {
    String id = (state.editingId ?: "").toString()
    Map srv = findServer(id)
    if (!srv) return
    String dni = serverDni(id)
    if (getChildDevice(dni)) deleteChildDevice(dni)
    state.servers = serverList().findAll { it.id != id }
    state.editingId = ""
    state.serverMessage = "&#9989; Removed ${srv.label}.".toString()
    logInfo "Removed Pihole '${srv.label}'"
    refreshGroupDevice()
}

/** Creates the child if needed, then pushes the connection settings into it. */
private def provisionChild(Map srv, String password) {
    String dni = serverDni(srv.id as String)
    def cd = getChildDevice(dni)
    if (!cd) {
        try {
            cd = addChildDevice(DRIVER_NAMESPACE, SERVER_DRIVER, dni,
                                [name: SERVER_DRIVER, label: srv.label, isComponent: false])
        } catch (Exception e) {
            logError "Could not create '${srv.label}': ${e.message}. Confirm the " +
                     "'${SERVER_DRIVER}' driver is installed in namespace '${DRIVER_NAMESPACE}'."
            return null
        }
    } else if (cd.getLabel() != srv.label) {
        cd.setLabel(srv.label as String)
    }

    Map cfg = [
        host          : srv.host,
        port          : srv.port,
        https         : srv.https,
        ignoreSsl     : srv.ignoreSsl,
        pollInterval  : (settings.pollInterval ?: "5"),
        detailInterval: (settings.detailInterval ?: "30")
    ]
    // An empty password means "keep what the device already has".
    if (password) cfg.password = password
    cd.setConnection(cfg)
    return cd
}

// =============================================================================
// Connection test
// =============================================================================
/**
 * Synchronous login against a candidate Pihole. Synchronous by design: the
 * setup page cannot render a verdict it does not have yet.
 *
 * Returns [ok: boolean, message: String].
 */
private Map probe(Map form) {
    String base = "${form.https ? 'https' : 'http'}://${form.host}:${form.port}".toString()
    Map params = [
        uri               : base,
        path              : "/api/auth",
        requestContentType: "application/json",
        contentType       : "application/json",
        body              : [password: form.password],
        timeout           : TEST_TIMEOUT_SECONDS
    ]
    if (form.https && form.ignoreSsl) params.ignoreSSLIssues = true

    String sid = null
    Map outcome = [ok: false, message: null]
    try {
        httpPost(params) { resp ->
            Map session = (resp.data as Map)?.session as Map
            if (session?.valid) {
                sid = session.sid
                outcome.ok = true
                outcome.message = "&#9989; Connected to ${form.host}." +
                                  (sid ? "" : " This Pihole has no API password set.")
            } else {
                outcome.message = "&#10060; ${form.host} refused the password."
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        outcome.message = describeProbeError(e, form.host as String)
    } catch (java.net.UnknownHostException e) {
        outcome.message = "&#10060; Cannot resolve '${form.host}'."
    } catch (Exception e) {
        outcome.message = "&#10060; Cannot reach ${base}: ${e.message}"
    }

    // Pihole caps concurrent API sessions, so give this one straight back.
    if (sid) releaseSession(base, sid, params.ignoreSSLIssues == true)
    logDebug "Connection test for ${base}: ${outcome.ok ? 'ok' : outcome.message}"
    return outcome
}

private String describeProbeError(groovyx.net.http.HttpResponseException e, String host) {
    switch (e.statusCode) {
        case 401:
            return "&#10060; ${host} refused the password. If two-factor authentication is on, " +
                   "use an application password."
        case 404:
            // /api/auth is the v6 API root. A 404 from a live web server almost
            // always means this is a Pihole v5 host.
            return "&#10060; ${host} answered, but has no <code>/api/auth</code> endpoint. " +
                   "That usually means Pihole v5, which this integration does not support &mdash; " +
                   "upgrade to Pihole v6 or later."
        case 429:
            return "&#10060; ${host} is rate limiting login attempts. Wait a moment and try again."
        default:
            return "&#10060; ${host} returned HTTP ${e.statusCode}."
    }
}

private void releaseSession(String base, String sid, boolean ignoreSsl) {
    Map params = [
        uri    : base,
        path   : "/api/auth",
        headers: ["X-FTL-SID": sid],
        timeout: TEST_TIMEOUT_SECONDS
    ]
    if (ignoreSsl) params.ignoreSSLIssues = true
    try {
        httpDelete(params) { resp -> }
    } catch (Exception e) {
        logDebug "Could not log the test session out (harmless, it will time out): ${e.message}"
    }
}

// =============================================================================
// Lifecycle
// =============================================================================
void installed() {
    logDebug "installed()"
    initialize()
}

void updated() {
    logDebug "updated()"
    unsubscribe()
    unschedule()
    initialize()
}

void uninstalled() {
    logInfo "Removing all Pihole child devices"
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

private void initialize() {
    state.remove("serverMessage")
    if (logEnable) runIn(1800, "disableDebugLogging")

    syncGroupDevice()
    pushScheduleToChildren()
    subscribeToChildren()
    runIn(5, "refreshAll")
}

void disableDebugLogging() {
    logInfo "Debug logging disabled"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

/** Re-applies the shared polling schedule; connection settings are untouched. */
private void pushScheduleToChildren() {
    Map cfg = [
        pollInterval  : (settings.pollInterval ?: "5"),
        detailInterval: (settings.detailInterval ?: "30")
    ]
    serverDevices().each { cd ->
        try {
            cd.setConnection(cfg)
        } catch (Exception e) {
            logError "${cd.displayName} rejected the schedule update: ${e.message}"
        }
    }
}

private void subscribeToChildren() {
    List kids = serverDevices()
    if (!kids || !settings.notifyDevices) return
    if (settings.notifyOffline != false)   subscribe(kids, "connectionStatus", "connectionHandler")
    if (settings.notifyBlockingOff)        subscribe(kids, "blocking", "blockingHandler")
    if (settings.notifyUpdates)            subscribe(kids, "updateAvailable", "updateHandler")
    if (settings.percentThreshold != null) subscribe(kids, "percentBlocked", "percentHandler")
}

// =============================================================================
// Group device
// =============================================================================
private List serverDevices() {
    String gd = groupDni()
    return getChildDevices().findAll { it.deviceNetworkId != gd }
}

private def groupDevice() {
    return getChildDevice(groupDni())
}

private void syncGroupDevice() {
    def existing = groupDevice()
    if (settings.createGroup && !existing) {
        try {
            addChildDevice(DRIVER_NAMESPACE, GROUP_DRIVER, groupDni(),
                           [name: GROUP_DRIVER, label: "Pihole Group", isComponent: false])
            logInfo "Created the Pihole Group device"
        } catch (Exception e) {
            logError "Could not create the group device: ${e.message}. Confirm the " +
                     "'${GROUP_DRIVER}' driver is installed in namespace '${DRIVER_NAMESPACE}'."
        }
    } else if (!settings.createGroup && existing) {
        deleteChildDevice(groupDni())
        logInfo "Removed the Pihole Group device"
    }
}

/**
 * Called by each child once it has finished processing a poll, and after any
 * change made from the group device.
 */
void childUpdated(cd) {
    refreshGroupDevice()
}

void refreshGroupDevice() {
    def group = groupDevice()
    if (!group) return

    List kids = serverDevices()
    int online = 0, blocking = 0, disabled = 0
    long totalQueries = 0L, totalBlocked = 0L
    long domainsBlocked = 0L, clientsActive = 0L
    boolean updateAvailable = false
    List<String> offlineNames = []

    kids.each { cd ->
        boolean isOnline = cd.currentValue("connectionStatus") != "offline"
        if (isOnline) online++ else offlineNames << (cd.getLabel() ?: cd.displayName)

        // Deliberately not called "state": that name is the app's persistent
        // state map and shadowing it here would be a trap.
        String blockingState = cd.currentValue("blocking")
        if (blockingState == "enabled") blocking++
        else if (blockingState == "disabled") disabled++

        totalQueries += longOf(cd.currentValue("queriesTotal"))
        totalBlocked += longOf(cd.currentValue("queriesBlocked"))
        // Gravity lists are normally identical across a redundant pair and
        // clients overlap heavily, so the largest is meaningful where a sum
        // would just double-count.
        domainsBlocked = Math.max(domainsBlocked, longOf(cd.currentValue("domainsBlocked")))
        clientsActive  = Math.max(clientsActive,  longOf(cd.currentValue("clientsActive")))
        if (cd.currentValue("updateAvailable") == "yes") updateAvailable = true
    }

    BigDecimal percent = totalQueries > 0
        ? ((totalBlocked * 100.0G) / totalQueries).setScale(1, java.math.RoundingMode.HALF_UP)
        : 0.0G

    group.updateGroupState([
        total          : kids.size(),
        online         : online,
        offline        : kids.size() - online,
        blocking       : blocking,
        disabled       : disabled,
        requireAll     : (settings.groupRequiresAll != false),
        queriesTotal   : totalQueries,
        queriesBlocked : totalBlocked,
        percentBlocked : percent,
        domainsBlocked : domainsBlocked,
        clientsActive  : clientsActive,
        updateAvailable: updateAvailable,
        offlineNames   : offlineNames.join(", ")
    ])
}

private long longOf(value) {
    if (value == null) return 0L
    try { return new BigDecimal(value.toString()).longValue() } catch (Exception ignored) { return 0L }
}

// =============================================================================
// Fleet commands, invoked by the group device
// =============================================================================
void refreshAll() {
    serverDevices().each { cd ->
        try {
            cd.refresh()
        } catch (Exception e) {
            logError "${cd.displayName} could not refresh: ${e.message}"
        }
    }
}

void groupEnableBlocking() {
    logInfo "Enabling blocking on every Pihole"
    serverDevices().each { cd ->
        try {
            cd.enableBlocking()
        } catch (Exception e) {
            logError "${cd.displayName} could not enable blocking: ${e.message}"
        }
    }
    runIn(8, "refreshAll", [overwrite: true])
}

void groupDisableBlocking(Integer minutes) {
    logInfo "Disabling blocking on every Pihole${minutes ? " for ${minutes} minute(s)" : ''}"
    serverDevices().each { cd ->
        try {
            cd.disableBlocking(minutes)
        } catch (Exception e) {
            logError "${cd.displayName} could not disable blocking: ${e.message}"
        }
    }
    runIn(8, "refreshAll", [overwrite: true])
}

// =============================================================================
// Notifications
// =============================================================================
void connectionHandler(evt) {
    if (settings.notifyOffline == false) return
    // Only the transition into offline is worth waking someone for; the
    // recovery is reported so they know it cleared.
    if (evt.value == "offline") {
        notify("Pihole '${evt.device.displayName}' is not responding.")
    } else if (evt.value == "online") {
        notify("Pihole '${evt.device.displayName}' is back online.")
    }
}

void blockingHandler(evt) {
    if (!settings.notifyBlockingOff) return
    if (evt.value == "disabled") {
        def cd = evt.device
        String timer = cd?.currentValue("blockingResumesAt")
        notify("Pihole '${evt.device.displayName}' has blocking turned off" +
               (timer ? " until ${timer}." : " with no timer set."))
    }
}

void updateHandler(evt) {
    if (!settings.notifyUpdates) return
    if (evt.value == "yes") {
        notify("Pihole '${evt.device.displayName}' has an update available.")
    }
}

void percentHandler(evt) {
    BigDecimal threshold = settings.percentThreshold as BigDecimal
    if (threshold == null) return
    BigDecimal value
    try { value = new BigDecimal(evt.value.toString()) } catch (Exception ignored) { return }
    if (value >= threshold) {
        state.remove("belowThreshold_${evt.device.id}".toString())
        return
    }
    // Latch, so a block rate parked under the threshold does not send a message
    // on every single poll.
    String key = "belowThreshold_${evt.device.id}".toString()
    if (state[key]) return
    state[key] = true
    notify("Pihole '${evt.device.displayName}' block rate is ${value}%, below the ${threshold}% threshold.")
}

private void notify(String msg) {
    logInfo msg
    settings.notifyDevices?.each { nd ->
        try {
            nd.deviceNotification(msg)
        } catch (Exception e) {
            logError "Could not notify ${nd.displayName}: ${e.message}"
        }
    }
}

// =============================================================================
// Logging
// =============================================================================
private void logDebug(String msg) { if (settings.logEnable != false) log.debug "Pihole: ${msg}" }
private void logInfo(String msg)  { if (settings.txtEnable != false) log.info  "Pihole: ${msg}" }
private void logWarn(String msg)  { log.warn  "Pihole: ${msg}" }
private void logError(String msg) { log.error "Pihole: ${msg}" }
