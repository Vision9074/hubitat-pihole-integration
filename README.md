# Pi-hole Integration for Hubitat

A parent app plus two drivers that bring one or more Pi-holes into Hubitat —
statistics as attributes, ad blocking as a switch.

| File | Type | Purpose |
|---|---|---|
| [PiholeIntegration.groovy](PiholeIntegration.groovy) | App | Fleet management, connection testing, notifications |
| [PiholeServer.groovy](PiholeServer.groovy) | Driver | One device per Pi-hole — polling and control |
| [PiholeGroup.groovy](PiholeGroup.groovy) | Driver | Optional. One device that drives the whole fleet |
| [packageManifest.json](packageManifest.json) | HPM | Package manifest — what Hubitat Package Manager installs |
| [repository.json](repository.json) | HPM | Repository listing, so the package is browsable in HPM |

Namespace for all three files is `vision9074`. If you change it, change it in
*all three* (`definition(namespace:)` in each, plus `DRIVER_NAMESPACE` in the app).

**Requires Pi-hole v6 or later** (Pi-hole 6.0, February 2025). Pi-hole v5's
`/admin/api.php` is a different, incompatible interface and is not supported;
a v5 host is detected during the connection test and reported as such rather
than failing with a confusing 404.

---

## API notes

Four things about the Pi-hole v6 API shape the design here.

1. **Sessions are a scarce resource.** `POST /api/auth` returns a session id
   sent back as `X-FTL-SID`, but Pi-hole permits only a limited number of
   concurrent sessions and binds each to the calling IP. So each device holds
   exactly one, renews it 30 s before `validity` lapses, and hands it back with
   `DELETE /api/auth` whenever the connection details change or the device is
   removed. The app's **Test Connection** button logs out immediately too.
   Naively logging in on every poll works right up until it exhausts the pool.

2. **`/api/padd` collapses six requests into one.** It is the aggregate
   endpoint the PADD dashboard uses, and it carries blocking state, gravity
   size, cache counters, CPU temperature and host identity together. It is not
   present on every v6 build, so a 404 there permanently falls back to
   `/api/dns/blocking` plus `/api/info/sensors`, logs a warning, and keeps
   polling.

3. **The blocking timer only exists on one endpoint.** `/api/padd` reports
   *that* blocking is off but not *for how long*; only `GET /api/dns/blocking`
   returns the countdown. Rather than always paying for a second request, the
   driver fetches it only while blocking is actually off.

4. **Destructive actions are deliberately not wrapped.** Gravity updates, DNS
   restarts and log flushes all have API endpoints, and none of them are here.
   They are slow, disruptive, gated behind `webserver.api.allow_destructive`,
   and produce no result an attribute can usefully hold — so putting them on a
   dashboard tile only adds ways to break a working Pi-hole by accident. Run
   them from the Pi-hole's own interface, where the output is visible.

The consequence for structure: unlike a cloud integration, each Pi-hole is an
independent LAN endpoint with its own credentials and its own session. There is
nothing to share between them, so **each device owns its own connection and
polls itself**, and the app provisions and coordinates rather than proxying
HTTP. That also means a device keeps working if the app is removed, and
passwords live in the device's settings — never in app state, which is visible
in plain text on the app status page.

---

## Install

### Option A — Hubitat Package Manager (recommended)

HPM installs the files in the right order and handles updates afterwards.
In the HPM app, either:

* **Install → From a URL**, and paste the package manifest:
  `https://raw.githubusercontent.com/vision9074/hubitat-pihole-integration/main/packageManifest.json`
* or **Package Manager Settings → Add a Custom Repository**, paste:
  `https://raw.githubusercontent.com/vision9074/hubitat-pihole-integration/main/repository.json`
  then **Install → From a Repository → Integrations → Pi-hole Integration**.
  Adding the repository is the better option if you want this package to show
  up in HPM's browse and search lists.

The **Pi-hole Group** driver is offered as an optional component; tick it if you
run more than one Pi-hole. Then continue from step 4 below.

### Option B — manual import

All three files carry an `importUrl`, so you can use **Import** and paste the
raw URL instead of the code. Install the **drivers first** — the app needs them
to exist before it can create devices.

1. **Drivers → Add driver → Import**, paste:
   `https://raw.githubusercontent.com/vision9074/hubitat-pihole-integration/main/PiholeServer.groovy`
   then Save.
2. Optionally repeat for `PiholeGroup.groovy` if you run more than one Pi-hole.
3. **Apps code → Add app → Import**, paste:
   `https://raw.githubusercontent.com/vision9074/hubitat-pihole-integration/main/PiholeIntegration.groovy`
   then Save.

### Either way

4. **Apps → Add user app → Pi-hole Integration**.
5. Press **Add a Pi-hole**, fill in the name, hostname and password, and press
   **Test Connection**. You should see "Connected".
6. Press **Add Pi-hole**. Repeat for each Pi-hole you run.
7. Back on the main page set the poll intervals, turn on the group device if you
   want one, pick your notifications, and press **Done**.

### Getting the password right

* Use the same password you use for the Pi-hole web interface.
* **If two-factor authentication is enabled, a plain password will not work**
  unattended — there is no way to supply a TOTP code on a schedule. Create an
  **application password** under Settings → Web interface / API and paste that
  instead. Application passwords bypass 2FA and can be revoked on their own.
* A Pi-hole with no API password set at all is supported; leave the box empty.
* Docker installs usually publish the web interface on a port other than 80.

---

## Standalone use

The **Pi-hole Server** driver is self-contained. If you only have one Pi-hole
and do not want the app, add a **virtual device** with that driver, fill in the
hostname and password in its preferences, and save. You lose the fleet
notifications and the group device; everything else works.

---

## What the Pi-hole Server driver exposes

**Capabilities:** Actuator, Sensor, Refresh, Initialize, Switch,
TemperatureMeasurement.

| Attribute | Unit | Notes |
|---|---|---|
| `switch` | on/off | **on = blocking enabled**, off = ads getting through |
| `blocking` | | `enabled`, `disabled`, `failed`, `unknown` |
| `blockingTimer` | s | Seconds until Pi-hole re-enables itself, 0 if none |
| `blockingResumesAt` | | Wall-clock time blocking comes back |
| `queriesTotal`, `queriesBlocked` | | Today's counts |
| `percentBlocked` | % | |
| `queriesForwarded`, `queriesCached` | | |
| `queryFrequency` | /s | Queries per second |
| `uniqueDomains` | | |
| `clientsActive`, `clientsTotal` | | |
| `domainsBlocked` | | Gravity blocklist size |
| `gravityLastUpdate` | | When the blocklists were last rebuilt |
| `topDomain`, `topBlockedDomain`, `topClient`, `recentBlocked` | | |
| `temperature` | °C/°F | **The Pi-hole host's CPU temperature**, converted to the hub's scale |
| `cpuLoad` | % | 1-minute load average against the core count |
| `memoryUsage` | % | RAM in use |
| `uptime`, `uptimeText` | s | e.g. `12d 4h 33m` |
| `hostName`, `hostModel`, `ipAddress` | | |
| `versionCore`, `versionFtl`, `versionWeb` | | |
| `updateAvailable` | yes/no | Any component behind its published release |
| `diagnosticMessages` | | Count of Pi-hole's own warnings |
| `privacyLevel`, `dhcpActive` | | |
| `connectionStatus` | | `online` / `offline` |
| `lastError`, `lastUpdate` | | |

**Commands beyond the standard capabilities:** `enableBlocking`,
`disableBlocking(minutes)`.

That is the whole control surface, on purpose — see API note 4 above.

### Switch semantics

`off()` uses the **Default off duration** preference. Left at 0 it disables
blocking until something turns it back on. Set it to, say, 5 and every `off()`
becomes a five-minute reprieve that *Pi-hole itself* reverses — which is the
safer shape for a rule whose "on" half might never get to run, because the
timer survives a hub reboot and this integration failing.

---

## What the Pi-hole Group driver exposes

One device covering every Pi-hole. `on()`/`off()`, `enableBlocking` and
`disableBlocking(minutes)` all fan out to the whole fleet.

| Attribute | Notes |
|---|---|
| `switch` | See below |
| `blocking` | `enabled`, `disabled`, `mixed`, `unknown` |
| `instancesTotal`, `instancesOnline`, `instancesOffline`, `instancesBlocking` | |
| `offlineInstances` | Names of whatever is not responding |
| `queriesTotal`, `queriesBlocked`, `percentBlocked` | Summed across the fleet |
| `domainsBlocked`, `clientsActive` | Largest value, **not** a sum |
| `updateAvailable`, `lastUpdate` | |

**Why this device exists:** the usual reason to run a second Pi-hole is
redundancy, and every client is configured with both. Disabling blocking on
only one of a redundant pair therefore does nothing useful — queries just get
answered by the other one. Anything that turns blocking off should turn it off
everywhere.

**Aggregation rules.** Query counts are summed, because each Pi-hole answers a
different share of the traffic. Blocklist size and active client counts take the
largest value instead, because a redundant pair runs the same lists and serves
the same clients, so summing would double-count.

**Switch state** follows the app's *reports on only when every Pi-hole is
blocking* option. With it set (the default) the group is `on` only when nothing
is letting ads through, which is the honest reading for a redundant pair. With
it clear, one Pi-hole still blocking is enough. When nothing is reachable the
group reports `unknown` rather than `disabled` — saying "disabled" there would
be a lie that could fire a rule.

---

## Design notes

* **Two-speed polling.** The fast cycle (default 5 minutes, two requests) is
  what changes minute to minute: queries, clients, blocking state, cache, CPU
  temperature. The slow cycle (default 30 minutes, three requests) is uptime,
  versions and Pi-hole's diagnostic count. Both are local to your network.
* **Async HTTP on the polling path.** Every scheduled request is
  `asynchttpGet`/`asynchttpPost`, so a Pi-hole that has gone away cannot tie up
  a hub thread waiting for a timeout. The two synchronous calls that remain are
  login and the setup page's connection test, where the caller genuinely cannot
  continue without the answer.
* **Session handling.** Renewed 30 s before expiry; a 401 mid-flight triggers
  one re-auth and one retry of the original request, so a lapsed session costs
  nothing rather than losing a poll.
* **Offline is a considered verdict.** One failed request is a blip. Two
  consecutive failures set `connectionStatus` to `offline`, set `blocking` to
  `unknown`, and are what the app's offline notification keys off.
* **Optimistic switch updates.** Turning blocking on or off updates the
  attributes immediately and schedules a confirming refresh, so a dashboard
  tile responds at once but still self-corrects if the Pi-hole refused.
* **Endpoint drift guard.** A 404 on `/api/padd` falls back permanently to the
  documented per-topic endpoints instead of losing the poll.
* **Passwords are not in app state.** The password typed on the setup page is
  handed to the device and then erased from the app's own settings. App state
  is visible in plain text on the app status page; device settings are not.
* **Threshold alerts latch.** A block rate parked below the threshold notifies
  once, not on every poll, and re-arms when it recovers.

---

## Known limitations

* **Pi-hole v6 only.** See the top of this file.
* **Two-factor authentication needs an application password.** A TOTP code
  cannot be supplied on a schedule.
* **Blocking is the only thing you can change from here.** Gravity updates, DNS
  restarts, log flushes and the DNS cache counters were all built and then
  removed on purpose: they belong to the Pi-hole's own interface, where the
  output is actually visible. See API note 4.
* **Polling only.** There is no push channel, so a change made in the Pi-hole
  web interface appears at the next poll. Turning blocking on or off from
  Hubitat schedules a catch-up refresh a few seconds later.
* **Query-level detail is not exposed.** `/api/queries`, per-client history and
  the allow/deny list endpoints are all available and none of them fit
  Hubitat's attribute model, which wants scalars.
* **A device deleted by hand is not recreated.** It still shows in the app's
  list as *not yet created*; re-save it there, with the password, to rebuild it.
  Recreating it silently would produce a device with no credentials.

## Credits

Built against the [Pi-hole v6 API documentation](https://docs.pi-hole.net/api/)
and the endpoint definitions in
[pi-hole/FTL](https://github.com/pi-hole/FTL). MIT licensed, as is Pi-hole
itself.
