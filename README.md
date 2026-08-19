# Pihole Integration for Hubitat

Bring your [Pihole](https://pi-hole.net) servers into Hubitat: blocking
statistics, host health, update alerts, and ad blocking as a switch you can put
on a dashboard or drive from a rule. Handles any number of Piholes, and can
treat a redundant pair as one.

> **Unofficial.** This is not made or supported by the Pihole project. It uses
> Pihole's own documented v6 REST API, entirely over your local network.

---

## What you get

For each Pihole, one Hubitat device exposing:

* **Blocking** — on/off as a standard switch, with an optional timer that
  Pihole itself reverses
* **Queries** — total, blocked, percent blocked, forwarded, cached, per second
* **Clients** — active now and seen in total
* **Blocklist** — size, when it was last rebuilt, top domains and clients
* **Host health** — CPU temperature, load, memory, uptime
* **Versions** — Core, FTL and Web, plus whether an update is waiting
* **Diagnostics** — online/offline, Pihole's own warning count, last error

Everything is a standard Hubitat attribute, so it all works in Rule Machine,
dashboards, and any other app.

The app adds notifications when a Pihole stops responding, when blocking is
turned off, when an update appears, and when your block rate falls below a
threshold you set — often the first sign a device is bypassing your DNS.

## Requirements

* Hubitat hub on firmware **2.3.0** or later
* **Pihole v6.0 or later** — v6 replaced the old `/admin/api.php` interface
  with a completely different API, and only the new one is supported. Point this
  at a v5 host and the connection test will say so rather than failing obscurely
* Your Pihole web password or an application password — or nothing at all, if
  the Pihole has no password set
* Nothing leaves your network — there is no cloud service and no account

---

## Install

### Option A — Hubitat Package Manager (recommended)

HPM installs all the files in the right order and handles updates afterwards.
Don't have it yet? [Install HPM first][hpm-install].

1. Open **Hubitat Package Manager** and choose **Install → Browse by Tags**.
2. Pick the **LAN** or **Monitoring** tag, then **Pihole Integration**.
3. If you run more than one Pihole, tick **Pihole Group** when HPM offers the
   optional components. You can add or drop it later with **Modify**.
4. Click **Next** to confirm, and HPM installs the app and drivers.

**Install → Search by Keywords** for `pihole` works too, once HPM's search index
has picked this package up — it rebuilds on its own schedule, separately from
the package listing, so browsing by tag is the reliable route in the meantime.

To go straight to it, **Install → From a URL** with the package manifest also
works at any time. Note this is the URL of a *package JSON*, not of an app or
driver file:

```
https://raw.githubusercontent.com/vision9074/hubitat-pihole-integration/main/packageManifest.json
```

[hpm-install]: https://hubitatpackagemanager.hubitatcommunity.com/installing.html

### Option B — manual import

Install the **drivers first** — the app needs them to exist before it can create
devices.

1. **Drivers code → New Driver → Import**, paste this, then Save:
   ```
   https://raw.githubusercontent.com/vision9074/hubitat-pihole-integration/main/PiholeServer.groovy
   ```
2. Only if you run more than one Pihole, repeat for the group driver:
   ```
   https://raw.githubusercontent.com/vision9074/hubitat-pihole-integration/main/PiholeGroup.groovy
   ```
3. **Apps code → New App → Import**, paste this, then Save:
   ```
   https://raw.githubusercontent.com/vision9074/hubitat-pihole-integration/main/PiholeIntegration.groovy
   ```

If you later want HPM to manage updates for a manual install, use its
**Match Up** function to adopt it.

### Set it up

Once the app and drivers are installed, by either method:

1. **Apps → Add user app → Pihole Integration**.
2. Press **Add a Pihole**, enter a name, the hostname or IP address, and the
   password, then press **Test Connection**. You should see "Connected" —
   nothing is created until it does.
3. Press **Add Pihole**. Repeat for each Pihole you run.
4. Back on the main page, set your poll intervals, turn on the group device if
   you want one, choose your notifications, and press **Done**.

### Getting the password right

Use the same password as the Pihole web interface, with three exceptions:

* **If two-factor authentication is on, your normal password will not work** —
  nothing can type a rotating code on a schedule. Create an **application
  password** under *Settings → Web interface / API* on the Pihole and use that.
  It skips 2FA and can be revoked on its own without changing your login.
* **A Pihole with no API password** is supported; leave the box empty.
* **Docker installs** usually publish the web interface on a port other than 80.
  For HTTPS with the default self-signed certificate, tick **Use HTTPS** and
  then **Accept the self-signed certificate**.

Your password is stored in the device's settings, never in the app's — Hubitat
shows an app's stored data in plain text on its status page, and device settings
are not shown that way.

### Using the driver without the app

The **Pihole Server** driver is self-contained. With a single Pihole and no
interest in the app, add a **Virtual Device** using that driver, fill in the
hostname and password in its preferences, and save. You give up the
notifications and the group device; everything else is identical.

---

## Turning blocking off safely

`off()` disables blocking, and the interesting question is what turns it back
on. Each device has a **Default off duration** preference:

* Left at **0**, blocking stays off until something turns it back on. This is
  what the Pihole web interface calls disabling indefinitely.
* Set to **5**, every `off()` becomes a five-minute reprieve.

The second is usually the better choice, because the countdown runs *on the
Pihole*. It still fires if your hub reboots, if a rule's second half never
runs, or if this integration falls over entirely. A rule that turns blocking off
and promises to turn it back on later has none of those guarantees.

`disableBlocking(minutes)` sets a one-off duration regardless of the preference,
which is what you want behind a dashboard button — someone hits "5 minute pass"
when a site breaks, and it heals itself.

While blocking is off, `blockingTimer` counts down and `blockingResumesAt` shows
the clock time it comes back.

## Running more than one Pihole

Most people who run a second Pihole do it for redundancy, with every client
configured to use both. That makes single-device control almost useless:
disabling blocking on one just sends queries to the other.

The optional **Pihole Group** device solves that. It summarises the whole set
and switches all of them together.

| Attribute | Notes |
|---|---|
| `switch` | See below |
| `blocking` | `enabled`, `disabled`, `mixed`, `unknown` |
| `instancesTotal`, `instancesOnline`, `instancesOffline`, `instancesBlocking` | |
| `offlineInstances` | names of whatever isn't responding |
| `queriesTotal`, `queriesBlocked`, `percentBlocked` | added up across the set |
| `domainsBlocked`, `clientsActive` | largest value, **not** a total |
| `updateAvailable`, `lastUpdate` | |

Query counts are added together because each Pihole answers a different share
of your traffic. Blocklist size and client counts take the largest value
instead — a redundant pair runs the same lists for the same clients, so adding
them would count everything twice.

The switch follows the app's *reports on only when every Pihole is blocking*
option. Left on (the default), the group reads `on` only when nothing is getting
through, which is the useful reading for a redundant pair; turned off, one
Pihole still blocking is enough. If nothing is reachable at all the group
reports `unknown` rather than `disabled`, so a rule watching for "blocking got
turned off" doesn't fire on a network outage.

---

## Device reference

**Capabilities:** Actuator, Sensor, Refresh, Initialize, Switch,
TemperatureMeasurement.

### Attributes

| Attribute | Unit | Notes |
|---|---|---|
| `switch` | on/off | **`on` means blocking is enabled** |
| `blocking` | | `enabled`, `disabled`, `failed`, `unknown` |
| `blockingTimer` | s | until Pihole re-enables itself; `0` if none |
| `blockingResumesAt` | | clock time blocking comes back |
| `queriesTotal`, `queriesBlocked` | | today's counts |
| `percentBlocked` | % | |
| `queriesForwarded`, `queriesCached` | | |
| `queryFrequency` | /s | queries per second |
| `uniqueDomains` | | |
| `clientsActive`, `clientsTotal` | | |
| `domainsBlocked` | | blocklist size — Pihole calls this gravity |
| `gravityLastUpdate` | | when the blocklists were last rebuilt |
| `topDomain`, `topBlockedDomain`, `topClient`, `recentBlocked` | | |
| `temperature` | °C/°F | the Pihole host's CPU temperature, converted to your hub's scale |
| `cpuLoad` | % | one-minute load average, relative to core count |
| `memoryUsage` | % | RAM in use |
| `uptime` | s | |
| `uptimeText` | | e.g. `12d 4h 33m` |
| `hostName`, `hostModel`, `ipAddress` | | |
| `versionCore`, `versionFtl`, `versionWeb` | | |
| `updateAvailable` | yes/no | any component behind its published release |
| `diagnosticMessages` | | Pihole's own warning count |
| `privacyLevel`, `dhcpActive` | | |
| `connectionStatus` | | `online` / `offline` |
| `lastError` | | most recent failure — check here first |
| `lastUpdate` | | time of the last successful poll |

### Commands

Beyond the standard capability commands: `enableBlocking` and
`disableBlocking(minutes)`. Leave the minutes blank, or set `0`, to disable
blocking until it's turned back on.

That is the whole control surface, deliberately — see [Limitations](#limitations).

---

## Troubleshooting

Check the device's `lastError` attribute first. Most failures land there in
plain language, and the same text appears on the app's setup page.

**"Password rejected" or "refused the password"** — the password is wrong. If
two-factor authentication is enabled on the Pihole, you need an application
password instead.

**"No `/api/auth` endpoint. This integration needs Pihole v6 or later"** —
almost certainly a Pihole v5 host. There is no workaround short of upgrading.

**"Cannot resolve..."** — the hostname isn't resolvable from your hub. Try the
IP address instead.

**"Cannot reach..."** — wrong port, a firewall, or the Pihole is down. Docker
installs rarely use port 80.

**SSL or certificate errors** — tick **Accept the self-signed certificate** in
the device preferences.

**"rate limiting login attempts"** — Pihole throttles repeated logins. Wait a
minute and try again.

**A Pihole shows "not yet created"** — its device was deleted by hand. Open it
in the app and save it again, with the password, to rebuild it.

**No devices can be created at all** — install the drivers before the app. The
app can't create a device from a driver that doesn't exist yet.

**New attributes don't appear after an update** — Hubitat doesn't refresh a
device when its driver code changes. Press **Refresh** on the device, or wait
for the next poll.

Debug logging in the device preferences shows every request, and switches itself
off after 30 minutes.

## Limitations

* **Blocking is the only thing you can change from here.** Gravity updates, DNS
  restarts and log flushes all have API endpoints and none are included — they
  are slow, disruptive, and produce output no attribute can hold. A dashboard
  tile that can break a working Pihole with a mis-tap is a bad trade. Run them
  from Pihole's own interface, where you can see what happened.
* **Pihole v6 or later only.** See [Requirements](#requirements).
* **Two-factor authentication requires an application password.**
* **Polling only.** Pihole has no push channel, so a change made in its web
  interface appears at the next poll. Changes made from Hubitat schedule a
  catch-up refresh a few seconds later.
* **No query-level detail.** Individual query logs, per-client history and the
  allow/deny lists exist in the API but don't reduce to the single values a
  Hubitat attribute holds.

---

## How it works

Each device owns its own connection and polls itself, and the app provisions and
coordinates rather than relaying requests. That split is the opposite of a cloud
integration, and for a good reason: every Pihole is a separate machine with its
own credentials and its own login session, so there is nothing to share. A
device therefore keeps working even if you remove the app.

A few details worth knowing if you're reading the code:

* **Polling runs at two speeds.** The fast cycle (5 minutes by default) is two
  requests covering what actually changes — queries, clients, blocking state,
  CPU temperature. The slow cycle (30 minutes) is three more for uptime,
  versions and diagnostics. Both are configurable.
* **Sessions are reused, not re-created.** Pihole allows only a limited number
  of concurrent API sessions and binds each to the calling IP. Each device holds
  exactly one, renews it shortly before expiry, and formally logs out when its
  settings change or it's removed. A session that lapses mid-request triggers
  one re-authentication and one retry, so it costs nothing rather than losing a
  poll.
* **Nothing blocks the hub.** Every scheduled request is asynchronous, so a
  Pihole that has been unplugged can't tie up a hub thread waiting to time out.
* **Offline is a considered verdict.** One failed request is a blip; two in a
  row mark the device offline and set `blocking` to `unknown` rather than
  guessing.
* **Endpoint differences are survivable.** The poll prefers Pihole's aggregate
  `/api/padd` endpoint, which isn't present on every v6 build; a 404 there falls
  back permanently to the per-topic endpoints and keeps polling.
* **Switching feels instant.** Turning blocking on or off updates the device
  immediately and schedules a confirming refresh, so a dashboard responds at
  once but corrects itself if the Pihole disagreed.

## Credits

Built against the [Pihole v6 API documentation](https://docs.pi-hole.net/api/)
and the endpoint definitions in [pi-hole/FTL](https://github.com/pi-hole/FTL).

Pi-hole® is a registered trademark of Pi-hole LLC. This project is not
affiliated with, endorsed by, or sponsored by the Pihole project.
