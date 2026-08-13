# Pi-hole Integration for Hubitat

Bring your Pi-hole into Hubitat. See how much it is blocking, get told when it
stops responding, and turn ad blocking off for five minutes when a site breaks —
without walking over to a browser.

Works with any number of Pi-holes, which matters if you run a second one for
redundancy: this can treat them as a single unit.

> **Unofficial.** A community integration, not affiliated with or endorsed by
> the Pi-hole project.

**Requires [Pi-hole v6](https://pi-hole.net/) or later** (released February
2025). Pi-hole v5 is not supported — see [Requirements](#requirements).

---

## Contents

- [What you get](#what-you-get)
- [Requirements](#requirements)
- [Install](#install)
- [Adding your first Pi-hole](#adding-your-first-pi-hole)
- [Things people use it for](#things-people-use-it-for)
- [Attribute reference](#attribute-reference)
- [The group device](#the-group-device)
- [Troubleshooting](#troubleshooting)
- [How it works](#how-it-works)
- [Limitations](#limitations)
- [Contributing](#contributing)
- [License and credits](#license-and-credits)

---

## What you get

**One device per Pi-hole**, reporting more than thirty attributes — queries today,
percent blocked, active clients, blocklist size, host CPU temperature, uptime,
version, and whether an update is waiting. Ad blocking appears as a plain
**switch**: `on` means blocking is enabled.

**An app that manages the whole set.** Add each Pi-hole once, and it tests the
connection before creating anything, keeps them on a shared polling schedule,
and sends notifications when:

- a Pi-hole stops responding, and again when it comes back
- blocking gets turned off
- a Pi-hole update becomes available
- the block rate drops below a percentage you choose — often the first sign a
  device is bypassing your DNS

**An optional group device** that summarises every Pi-hole and switches them all
together. If you run a redundant pair, this is the one you actually want on your
dashboard: turning blocking off on only one of them does nothing, because
clients just use the other.

---

## Requirements

| | |
|---|---|
| Hubitat | 2.3.0 or later |
| Pi-hole | **v6.0 or later** |
| Network | Hubitat needs to reach the Pi-hole's web interface |
| Password | Your Pi-hole web password, or an application password |

**Why v6 only.** Pi-hole v6 replaced the old `/admin/api.php` interface with a
completely different REST API. Supporting both would mean two of everything, for
a version that has been superseded since early 2025. If you point this at a v5
host, the connection test recognises it and tells you so, rather than failing
with a confusing error.

---

## Install

### Option A — Hubitat Package Manager (recommended)

HPM installs everything in the right order and handles updates later.

1. In **HPM → Package Manager Settings → Add a Custom Repository**, paste:
   ```
   https://raw.githubusercontent.com/vision9074/hubitat-pihole-integration/main/repository.json
   ```
2. Then **Install → From a Repository → Integrations → Pi-hole Integration**.

The **Pi-hole Group** driver is offered as an optional component — tick it if
you run more than one Pi-hole.

*Alternatively*, **Install → From a URL** with the manifest below works too, but
does not add the package to HPM's browse and search lists:

```
https://raw.githubusercontent.com/vision9074/hubitat-pihole-integration/main/packageManifest.json
```

### Option B — manual import

Every file carries an `importUrl`, so you can paste the raw URL instead of the
code. **Install the drivers first** — the app needs them to exist before it can
create devices.

1. **Drivers code → New Driver → Import**, paste this, then **Save**:
   ```
   https://raw.githubusercontent.com/vision9074/hubitat-pihole-integration/main/PiholeServer.groovy
   ```
2. Only if you run more than one Pi-hole, repeat for the group driver:
   ```
   https://raw.githubusercontent.com/vision9074/hubitat-pihole-integration/main/PiholeGroup.groovy
   ```
3. **Apps code → New App → Import**, paste this, then **Save**:
   ```
   https://raw.githubusercontent.com/vision9074/hubitat-pihole-integration/main/PiholeIntegration.groovy
   ```

---

## Adding your first Pi-hole

However you installed, the setup is the same:

1. **Apps → Add User App → Pi-hole Integration**.
2. Press **Add a Pi-hole**. Fill in a name, the hostname or IP address, and the
   password.
3. Press **Test Connection**. You are looking for *"Connected"*. If not, see
   [Troubleshooting](#troubleshooting) — nothing is created until this passes.
4. Press **Add Pi-hole**. Repeat for each one you run.
5. Back on the main page, choose your poll intervals, turn on the group device
   if you want one, pick your notifications, and press **Done**.

### Getting the password right

- Use the same password as the Pi-hole web interface.
- **If you have two-factor authentication turned on, your normal password will
  not work.** Nothing can type a rotating code on a schedule. Create an
  **application password** under *Settings → Web interface / API* on the Pi-hole
  and paste that instead. Application passwords skip 2FA and can be revoked on
  their own without changing your login.
- A Pi-hole with no API password set is fine — leave the box empty.
- Running Pi-hole in Docker? It usually publishes the web interface on a port
  other than 80. Set the port accordingly.
- Using HTTPS with the default self-signed certificate? Tick **Use HTTPS**, then
  **Accept the self-signed certificate**.

Your password is stored in the device's settings, never in the app's. (Hubitat
displays an app's stored data in plain text on its status page; device settings
are not shown that way.)

### Using the driver on its own

The **Pi-hole Server** driver is self-contained. With a single Pi-hole and no
interest in the app, add a **Virtual Device** using that driver, fill in the
hostname and password in its preferences, and save. You give up the
notifications and the group device; everything else works the same.

---

## Things people use it for

**A "5 minute pass" dashboard button.** A site breaks, someone presses the
button, blocking resumes on its own. In Rule Machine, run the device's
`disableBlocking` command with `5`. The countdown lives on the *Pi-hole*, so it
still fires if your hub reboots or this integration falls over — which is why
this is safer than a rule that turns blocking off and promises to turn it back
on later.

**Know before your family does.** The app notifies you when a Pi-hole stops
answering. With a redundant pair, DNS keeps working and nobody notices the
outage — you may not find out for weeks otherwise.

**Catch a device dodging your DNS.** Set the block-rate threshold to a bit under
your normal percentage. A sustained drop usually means something picked up a
hardcoded or DoH resolver.

**Watch a Raspberry Pi cook.** The `temperature` attribute is the Pi-hole host's
CPU temperature, converted to your hub's units, so ordinary temperature rules
work on it.

**Put it on a dashboard.** `percentBlocked`, `queriesTotal` and `domainsBlocked`
are plain numeric attributes and tile fine.

**Fail safe.** A rule watching for `blocking` staying `disabled` longer than you
intended can put it back — useful if someone disables blocking indefinitely from
the Pi-hole's own web interface and forgets.

---

## Attribute reference

Everything below belongs to the **Pi-hole Server** device.

**Capabilities:** Actuator, Sensor, Refresh, Initialize, Switch,
TemperatureMeasurement.

### Blocking

| Attribute | Unit | Notes |
|---|---|---|
| `switch` | on/off | **`on` = blocking enabled.** `off` = ads getting through |
| `blocking` | | `enabled`, `disabled`, `failed`, `unknown` |
| `blockingTimer` | s | Seconds until Pi-hole re-enables itself; `0` if none |
| `blockingResumesAt` | | Clock time blocking comes back |

### Queries and clients

| Attribute | Unit | Notes |
|---|---|---|
| `queriesTotal`, `queriesBlocked` | | Today's counts |
| `percentBlocked` | % | |
| `queriesForwarded`, `queriesCached` | | |
| `queryFrequency` | /s | Queries per second |
| `uniqueDomains` | | |
| `clientsActive`, `clientsTotal` | | |

### Blocklist

| Attribute | Notes |
|---|---|
| `domainsBlocked` | Blocklist size (Pi-hole calls this gravity) |
| `gravityLastUpdate` | When the blocklists were last rebuilt |
| `topDomain`, `topBlockedDomain`, `topClient`, `recentBlocked` | |

### Host health

| Attribute | Unit | Notes |
|---|---|---|
| `temperature` | °C/°F | The Pi-hole host's CPU temperature, in your hub's units |
| `cpuLoad` | % | One-minute load average, relative to core count |
| `memoryUsage` | % | RAM in use |
| `uptime` / `uptimeText` | s | e.g. `12d 4h 33m` |

### Identity, versions and status

| Attribute | Notes |
|---|---|
| `hostName`, `hostModel`, `ipAddress` | |
| `versionCore`, `versionFtl`, `versionWeb` | |
| `updateAvailable` | `yes` / `no` — any component behind its release |
| `diagnosticMessages` | Count of Pi-hole's own warnings |
| `privacyLevel`, `dhcpActive` | |
| `connectionStatus` | `online` / `offline` |
| `lastError` | Most recent failure, if any — **check here first** |
| `lastUpdate` | Time of the last successful poll |

### Commands

Beyond the standard `on`, `off` and `refresh`:

| Command | Notes |
|---|---|
| `enableBlocking` | |
| `disableBlocking(minutes)` | Blank or `0` disables until turned back on |

That is the whole control surface, deliberately — see
[Limitations](#limitations).

### What `off` does

`off()` uses the device's **Default off duration** preference. At `0` (the
default) it disables blocking until something turns it back on. Set it to `5`
and every `off()` becomes a five-minute reprieve that Pi-hole reverses by
itself — generally the safer choice, since it does not depend on your hub
surviving to run the other half of the rule.

---

## The group device

One optional device covering every Pi-hole. `on`, `off`, `enableBlocking` and
`disableBlocking(minutes)` all fan out to the whole set.

| Attribute | Notes |
|---|---|
| `switch` | See below |
| `blocking` | `enabled`, `disabled`, `mixed`, `unknown` |
| `instancesTotal`, `instancesOnline`, `instancesOffline`, `instancesBlocking` | |
| `offlineInstances` | Names of whatever is not responding |
| `queriesTotal`, `queriesBlocked`, `percentBlocked` | Added up across the set |
| `domainsBlocked`, `clientsActive` | Largest value, **not** a total |
| `updateAvailable`, `lastUpdate` | |

**Why some values are added and others are not.** Each Pi-hole answers a
different share of your traffic, so query counts are worth adding together. But
a redundant pair runs the same blocklists and serves the same clients, so adding
those would count them twice — the largest value is the meaningful one.

**Switch state** follows the app's *reports on only when every Pi-hole is
blocking* option. Left on (the default), the group only shows `on` when nothing
is getting through, which is the useful reading for a redundant pair. Turned
off, one Pi-hole still blocking is enough.

If nothing is reachable, the group reports `unknown` rather than `disabled`, so
a rule watching for "blocking got turned off" does not fire on a network outage.

---

## Troubleshooting

Check the device's `lastError` attribute first — most failures land there in
plain language.

| What you see | What it means |
|---|---|
| *"Password rejected"* or *"refused the password"* | Wrong password. If 2FA is on, you need an application password |
| *"no `/api/auth` endpoint... needs Pi-hole v6"* | Almost certainly Pi-hole v5. Upgrade, or use a different integration |
| *"Cannot resolve..."* | Hostname is wrong or not resolvable from the hub. Try the IP address |
| *"Cannot reach..."* | Wrong port, firewall, or the Pi-hole is down. Docker installs rarely use port 80 |
| SSL or certificate errors | Tick **Accept the self-signed certificate** |
| *"rate limiting login attempts"* | Pi-hole throttles repeated logins. Wait a minute |
| *"(403)... not permitted"* | The password authenticated but lacks permission for that action |
| Device stuck `offline` | Two consecutive failures are needed before this shows, so it is a real problem, not a blip |
| A Pi-hole shows *"not yet created"* | Its device was deleted by hand. Open it in the app and save it again, with the password |
| No devices appear at all | Install the **drivers** before the app; the app cannot create a device from a driver that does not exist |

Turning on debug logging in the device preferences shows every request. It
switches itself off after 30 minutes.

---

## How it works

Not required reading, but useful if you are judging whether to trust this on
your network.

**Everything stays on your LAN.** No cloud service, no account, no outbound
connection. The hub talks to your Pi-hole directly.

**Each device polls itself.** Unlike a cloud integration, every Pi-hole is a
separate machine with its own credentials and its own login session — there is
nothing for the app to share by relaying requests. The app sets things up and
coordinates; the devices do their own work. A device therefore keeps running
even if you remove the app.

**Polling runs at two speeds.** The fast cycle (5 minutes by default) is two
requests covering what actually changes: queries, clients, blocking state, CPU
temperature. The slow cycle (30 minutes) is three more for uptime, versions and
Pi-hole's diagnostics count. Both are configurable.

**Sessions are reused, not re-created.** Pi-hole allows only a limited number of
API sessions at once. Each device holds a single session, renews it shortly
before it expires, and formally logs out when its settings change or it is
removed. If a session lapses mid-request, the device re-authenticates and
retries that one request instead of losing the poll.

**Nothing blocks the hub.** Scheduled requests are asynchronous, so a Pi-hole
that has been unplugged cannot tie up a hub thread waiting to time out.

**Offline is a considered verdict.** One failed request is a blip; two in a row
marks the device offline and sets `blocking` to `unknown` rather than guessing.

**Switching feels instant.** Turning blocking on or off updates the device
straight away and schedules a confirming refresh, so the dashboard responds
immediately but corrects itself if the Pi-hole disagreed.

**Threshold alerts do not nag.** A block rate sitting below your threshold
notifies once, then re-arms when it recovers.

---

## Limitations

**Blocking is the only thing you can change from here.** Gravity updates, DNS
restarts and log flushes all have API endpoints, and none are included. They are
slow, disruptive, and produce output no attribute can meaningfully hold — a
dashboard tile that can break a working Pi-hole with a mis-tap is a bad trade.
Run those from the Pi-hole's own interface, where you can see what happened.

**Pi-hole v6 or later only.** See [Requirements](#requirements).

**Two-factor authentication needs an application password.** No way around it.

**Polling, not push.** Pi-hole has no channel to notify Hubitat, so a change
made in its web interface shows up at the next poll. Changes made *from*
Hubitat schedule a catch-up refresh within seconds.

**No query-level detail.** Individual query logs, per-client history and the
allow/deny lists are all available in the API, but none of them reduce to the
single values Hubitat attributes hold.

**A device deleted by hand is not silently recreated**, because its stored
password went with it. It stays listed in the app as *not yet created* until you
re-save it.

---

## Contributing

Issues and pull requests are welcome.

| File | Purpose |
|---|---|
| [`PiholeIntegration.groovy`](PiholeIntegration.groovy) | The app — setup, provisioning, notifications |
| [`PiholeServer.groovy`](PiholeServer.groovy) | Driver — one device per Pi-hole |
| [`PiholeGroup.groovy`](PiholeGroup.groovy) | Driver — optional group device |
| [`packageManifest.json`](packageManifest.json) | What HPM installs |
| [`repository.json`](repository.json) | Repository listing, for HPM browse and search |

All three Groovy files use the namespace `vision9074`. If you change it, change
it in all three — `definition(namespace:)` in each file, plus `DRIVER_NAMESPACE`
in the app.

The app and drivers talk to each other by name: the app calls `setConnection`,
`refresh`, `enableBlocking`, `disableBlocking` and `updateGroupState` on its
children, and the drivers call `childUpdated`, `refreshAll`,
`groupEnableBlocking` and `groupDisableBlocking` on the app. Groovy resolves
these at run time, so renaming one without the other fails silently until that
code path runs.

---

## License and credits

MIT — see [LICENSE](LICENSE).

Built against the [Pi-hole v6 API documentation](https://docs.pi-hole.net/api/)
and the endpoint definitions in [pi-hole/FTL](https://github.com/pi-hole/FTL).

Pi-hole® is a registered trademark of Pi-hole LLC. This project is not
affiliated with, endorsed by, or sponsored by the Pi-hole project. Pi-hole
itself is also MIT licensed.
