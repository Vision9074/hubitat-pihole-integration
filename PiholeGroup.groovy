/**
 *  Pi-hole Group
 *  =============
 *  Child driver for the "Pi-hole Integration" app. One device that summarises
 *  every Pi-hole on the hub and controls all of them at once.
 *
 *  It holds no credentials and makes no network calls of its own: the app
 *  aggregates the child devices and pushes the result down here, and commands
 *  are fanned back out through the app.
 *
 *  The reason this exists: the usual reason to run a second Pi-hole is
 *  redundancy, and every client is configured with both. Disabling blocking on
 *  only one of a redundant pair therefore does nothing useful - queries just
 *  get answered by the other one. Anything that turns blocking off should turn
 *  it off everywhere, which is what this device is for.
 *
 *  ---------------------------------------------------------------------------
 *  SWITCH SEMANTICS
 *  ---------------------------------------------------------------------------
 *  on()  = enable blocking on every Pi-hole
 *  off() = disable blocking on every Pi-hole, for the default off duration
 *
 *  The reported switch state depends on the app's "reports on only when every
 *  Pi-hole is blocking" option. With it set (the default) the group is "on"
 *  only when nothing is letting ads through, which is the honest reading for a
 *  redundant pair. With it clear, one Pi-hole still blocking is enough.
 *
 *  Aggregation rules: query counts are summed across the fleet, because every
 *  Pi-hole answers a different share of the traffic. Blocklist size and active
 *  client counts take the largest value instead, because a redundant pair runs
 *  the same lists and serves the same clients, so summing would double-count.
 *
 *  License: MIT
 */

metadata {
    definition(name: "Pi-hole Group", namespace: "vision9074", author: "vision9074",
               importUrl: "https://raw.githubusercontent.com/vision9074/hubitat-pihole-integration/main/PiholeGroup.groovy") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "Switch"

        // --- Fleet state
        attribute "blocking", "enum", ["enabled", "disabled", "mixed", "unknown"]
        attribute "instancesTotal", "number"
        attribute "instancesOnline", "number"
        attribute "instancesOffline", "number"
        attribute "instancesBlocking", "number"
        attribute "offlineInstances", "string"

        // --- Aggregated statistics
        attribute "queriesTotal", "number"        // summed
        attribute "queriesBlocked", "number"      // summed
        attribute "percentBlocked", "number"      // % of all queries, fleet-wide
        attribute "domainsBlocked", "number"      // largest blocklist, not a sum
        attribute "clientsActive", "number"       // largest client count, not a sum

        attribute "updateAvailable", "enum", ["yes", "no"]
        attribute "lastUpdate", "string"

        // --- Commands beyond the standard capabilities
        command "enableBlocking"
        command "disableBlocking", [[name: "Minutes", type: "NUMBER",
                                     description: "Blank or 0 disables blocking until it is turned back on"]]
    }

    preferences {
        input name: "defaultOffMinutes", type: "number",
              title: "Default off duration in minutes (0 = until turned back on)",
              defaultValue: 0, range: "0..1440", required: true
        input name: "logEnable", type: "bool", title: "Enable debug logging (auto-off after 30 minutes)",
              defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptive text logging", defaultValue: true
    }
}

// =============================================================================
// Lifecycle
// =============================================================================
void installed() {
    logDebug "installed()"
    sendEvent(name: "blocking", value: "unknown")
    runIn(3, "refresh")
}

void updated() {
    logDebug "updated()"
    unschedule()
    if (logEnable) runIn(1800, "disableDebugLogging")
    refresh()
}

void disableDebugLogging() {
    logInfo "Debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

void refresh() {
    logDebug "refresh()"
    parent?.refreshAll()
}

// =============================================================================
// Commands
// =============================================================================
void on() { enableBlocking() }

void off() {
    Integer mins = (settings.defaultOffMinutes ?: 0) as Integer
    disableBlocking(mins > 0 ? mins : null)
}

void enableBlocking() {
    logDebug "enableBlocking() across the fleet"
    parent?.groupEnableBlocking()
    // The app schedules a fleet refresh that will correct this if a Pi-hole
    // did not take the command.
    optimistic("enabled")
}

void disableBlocking(minutes = null) {
    Integer mins = minutes == null ? null : intOf(minutes)
    logDebug "disableBlocking(${mins ?: 'no timer'}) across the fleet"
    parent?.groupDisableBlocking((mins != null && mins > 0) ? mins : null)
    optimistic("disabled")
}

private void optimistic(String blocking) {
    updateAttr("blocking", blocking)
    updateAttr("switch", blocking == "enabled" ? "on" : "off")
}

// =============================================================================
// Data from the parent app
// =============================================================================
/**
 * Called by the app whenever any Pi-hole reports in. Every key is optional so
 * the app can grow the summary without breaking an older copy of this driver.
 */
void updateGroupState(Map summary) {
    if (!summary) return
    logDebug "updateGroupState: ${summary.online}/${summary.total} online, ${summary.blocking} blocking"

    int total    = intOf(summary.total) ?: 0
    int online   = intOf(summary.online) ?: 0
    int blocking = intOf(summary.blocking) ?: 0

    updateAttr("instancesTotal", total)
    updateAttr("instancesOnline", online)
    updateAttr("instancesOffline", intOf(summary.offline) ?: 0)
    updateAttr("instancesBlocking", blocking)
    updateAttr("offlineInstances", (summary.offlineNames ?: "").toString())

    updateAttr("queriesTotal", summary.queriesTotal)
    updateAttr("queriesBlocked", summary.queriesBlocked)
    updateAttr("percentBlocked", summary.percentBlocked, "%")
    updateAttr("domainsBlocked", summary.domainsBlocked)
    updateAttr("clientsActive", summary.clientsActive)
    updateAttr("updateAvailable", summary.updateAvailable ? "yes" : "no")

    applyFleetBlocking(total, online, blocking, summary.requireAll != false)
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}

private void applyFleetBlocking(int total, int online, int blocking, boolean requireAll) {
    if (total == 0 || online == 0) {
        // Nothing is reachable, so the fleet's blocking state is genuinely not
        // known. Saying "disabled" here would be a lie that could trigger rules.
        updateAttr("blocking", "unknown")
        return
    }
    String fleetState
    if (blocking == online)   fleetState = "enabled"
    else if (blocking == 0)   fleetState = "disabled"
    else                      fleetState = "mixed"
    updateAttr("blocking", fleetState)

    boolean on = requireAll ? (blocking == online) : (blocking > 0)
    updateAttr("switch", on ? "on" : "off")
}

// =============================================================================
// Conversions
// =============================================================================
private Integer intOf(value) {
    if (value == null) return null
    try {
        return new BigDecimal(value.toString()).setScale(0, java.math.RoundingMode.HALF_UP).intValue()
    } catch (Exception ignored) {
        return null
    }
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
