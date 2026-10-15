# SolarNode System Diagnostics

This plugin provides read-only, bounded diagnostics about the node itself (OS, JVM, storage, and
summaries of recent SolarNode log warnings and errors) via the `SystemConfiguration` instruction
topic. It lets a remote client, such as a SolarNetwork operations tool, gather this evidence through
the normal [SolarUser instruction API][instr-api] without SSH access or any OS credentials.

All results are redacted on the node, capped in size, and returned as a compressed JSON document.
The plugin never modifies the node, opens database connections, or runs processes. See
[Safety](#safety) for details.

# Install

This plugin is opt-in. It is provided by the `solarnode-app-system-diagnostics` package, which can
be installed on SolarNodeOS like this:

```sh
sudo apt install solarnode-app-system-diagnostics
```

# Use

The plugin has no settings UI. Once installed, it responds to `SystemConfiguration` instructions
whose `service` parameter is `net.solarnetwork.node.diagnostics`. For example, using the SolarUser
[queue instruction][instr-api] API with a JSON body like this:

```json
{
	"nodeId": 123,
	"topic": "SystemConfiguration",
	"params": {
		"service": "net.solarnetwork.node.diagnostics",
		"op": "logs",
		"minLevel": "ERROR"
	}
}
```

Once the node has executed the instruction, its `Completed` state carries a `result` result
parameter (see [Result](#result)), or its `Declined` state carries `code` and `message` result
parameters (see [Errors](#errors)).

A client should start with the `capabilities` op to learn which ops are available, their versions,
and the current limits. If the plugin is not installed, no handler accepts the instruction, so a
client should treat any outcome other than a `Completed` state with a `result`, or a `Declined`
state with an `NDS.*` code, as the plugin being unavailable.

# `SystemConfiguration` instruction support

The `SystemConfiguration` instruction topic is used with a `service` parameter of
`net.solarnetwork.node.diagnostics`. The `op` parameter selects the operation to run.

Every parameter must have exactly one value of at most 256 printable ASCII characters. Any parameter
name not listed for the requested op is rejected.

## Common parameters

| Parameter  | Required | Description                                                         |
|:-----------|:---------|:--------------------------------------------------------------------|
| `service`  | yes      | Must be `net.solarnetwork.node.diagnostics`.                        |
| `op`       | yes      | The operation to run; see [Operations](#operations).                |
| `v`        | no       | The API version. If provided, must be `1`.                          |
| `compress` | no       | Accepted for compatibility and ignored: results are always gzipped. |

## Operations

| `op`           | Rate class | Hard deadline                      | Description                                                                          |
|:---------------|:-----------|:-----------------------------------|:-------------------------------------------------------------------------------------|
| `capabilities` | none       | `opDeadlineMs` (default 5 seconds) | Supported ops and versions, disabled ops, limits, rate tokens, stuck ops, log files. |
| `system`       | light      | `opDeadlineMs` (default 5 seconds) | OS, kernel, Java and JVM, memory, uptime, load, platform state, DB backend, bundles. |
| `storage`      | light      | `opDeadlineMs` (default 5 seconds) | Mount usage, DB files, DB and settings backups, log and audit log files (stat only). |
| `logs`         | scan       | `logsDeadlineMs` (default 10 s)    | Summaries of recent SolarNode log warnings and errors.                               |

Each op runs on its own worker thread. A soft deadline 500ms before the hard deadline makes an op
stop early and return the partial data collected so far, with a `timeout` entry in `notAvailable`
(the `logs` op also reports `scanned.complete` as `false`). If an op does not finish by its hard
deadline the instruction is declined with `NDS.0004`, and further requests for that op are declined
with `NDS.0007` until the stuck run finishes.

The rate classes are limited by token buckets:

| Rate class | Default rate | Burst | Notes                                      |
|:-----------|:-------------|:------|:-------------------------------------------|
| none       | 120 / hour   | 10    | Used by `capabilities` only.               |
| light      | 60 / hour    | 5     | Rate configurable with `lightRatePerHour`. |
| scan       | 20 / hour    | 2     | Rate configurable with `scanRatePerHour`.  |

In addition, the light and scan classes share a wall-time budget of 60 seconds of op run time per 10
minutes. A token is only taken after a request passes validation, including checks against the
node's current time such as the `logs` window, so invalid requests do not use any tokens. Requests
declined with `NDS.0007` because the op is busy do not use any tokens either.

## `capabilities` parameters

The `capabilities` op takes no parameters. Its `data` includes:

| Property      | Description                                                                                                               |
|:--------------|:--------------------------------------------------------------------------------------------------------------------------|
| `api`         | The API version, `1`.                                                                                                     |
| `ops`         | An object of op name to op version.                                                                                       |
| `disabledOps` | The disabled op names.                                                                                                    |
| `limits`      | `resultBudget`, `opDeadlineMs`, `logsDeadlineMs`, `maxScanBytes`, `maxInstructionAgeMin`, `maxWindowDays` and `maxLimit`. |
| `rate`        | `light` and `scan` objects with `perHour` and the currently `available` tokens.                                           |
| `stuckOps`    | A list of `{op, sinceMs}` for ops whose last run exceeded its hard deadline.                                              |
| `logs`        | `supported`, the detected log `layout`, the log `dir` and its `files` (name, size, mtime).                                |

The op specific properties, such as `logs`, are collected on each op's worker thread, sharing the
`opDeadlineMs` hard deadline. An op whose properties are not collected in time, or whose previous run
is still stuck, is listed in `notAvailable` with a `timeout` reason, and appears in `stuckOps` until
the stuck call finishes.

## `system` parameters

The `system` op takes no parameters. Its `data` includes `os` (allowlisted `os-release` keys, from
`/etc/os-release` or else `/usr/lib/os-release`),
`kernel`, `arch`, `cpus`, `java`, `jvm` (start time, uptime, heap, threads and allowlisted
`inputArgs` such as `-Xmx`; `-D` values are never included), `memory`, `uptime` (`systemMs` and
`jvmMs`: comparing them shows whether SolarNode has restarted since boot), `loadAvg`, `platform`,
`db` (the backend detected from OSGi service metadata) and `bundles` (the number of bundles and any
that are not active).

## `storage` parameters

The `storage` op takes no parameters. Its `data` includes `mounts` (file store type, total and
usable bytes), `db` (backend, DB directory files and `*-reset.<ms>` reset backup directories),
`dbBackups`, `settingsBackups` (count, total size, newest and oldest times, and the newest 10 files),
`logs` and `auditLog`. Only file names, sizes and modification times are returned.

## `logs` parameters

The `logs` op scans the `solarnode.log` and `solarnode-N.log` files in the SolarNode log directory,
newest first, and summarises entries at or above the requested level. No raw log lines are
returned: each entry's message is redacted and normalised into a pattern, and entries with the same
level, logger and pattern are counted together.

| Parameter  | Default         | Rules                                                                                    |
|:-----------|:----------------|:-----------------------------------------------------------------------------------------|
| `sinceMs`  | `untilMs` − 24h | Epoch milliseconds, 1–13 digits. Must be less than `untilMs`, at most 14 days before it. |
| `untilMs`  | now             | Epoch milliseconds, 1–13 digits. At most 5 minutes after the node's current time.        |
| `minLevel` | `WARN`          | `WARN` or `ERROR`.                                                                       |
| `source`   |                 | An exact source ID, matching `[A-Za-z0-9_./:~-]{1,64}`. See below.                       |
| `limit`    | `20`            | The maximum number of summaries to return, `1` – `30`.                                   |

The window includes `sinceMs` and excludes `untilMs`. The `source` filter matches only the source ID
the plugin extracts from known log message templates (for example `Communication problem reading
source X from ...`). Entries whose source ID differs are counted in `otherSources`, and entries
without an extracted source ID are excluded.

Its `data` includes:

| Property        | Description                                                                                                                                                                                             |
|:----------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `request`       | The effective query: `sinceMs`, `untilMs`, `minLevel`, `source` and `limit`.                                                                                                                            |
| `window`        | `requested` (`sinceMs`, `untilMs`), `available` (`earliestMs` from the oldest log file, `latestMs` of the newest entry read) and, when the scan is incomplete, `scannedFromMs` (the oldest entry read). |
| `layout`        | The detected log layout: `default`, `noMillis` or `unsupported`.                                                                                                                                        |
| `scanned`       | `files`, `bytes`, `lines`, `entries`, `matched`, `unparsed`, `longLines`, `rotatedDuringScan` and `complete`.                                                                                           |
| `summaries`     | Up to `limit` summaries, ordered by level, then count, then most recent.                                                                                                                                |
| `otherPatterns` | The number of other summary `keys` beyond `limit`, and their total `entries`.                                                                                                                           |
| `otherSources`  | Only when `source` is given: up to 10 other `{source, count}` values.                                                                                                                                   |

Each summary has `level`, `logger` (as written in the log, which may be truncated from the left),
`pattern`, `count`, `firstSeenMs`, `lastSeenMs`, up to 3 `targets` and `sources` (as
`{value, count}`) and, when a stack trace was logged, `exception.root` (the class name of the last
`Caused by:` exception).

At most `maxScanBytes` are read per request, newest file first. When a file is larger than the bytes
remaining, only its end is read, so the most recent entries are always included; `scanned.complete`
is then `false`, and `window.scannedFromMs` shows how far back the scan reached.

The log layout is detected from the content of the log files. Only the default SolarNode layout and
the older layout without milliseconds are supported; with a custom layout the op reports
`unsupported`.

## Result

On success the instruction state is `Completed` and the `result` result parameter is a string:
the Base64 encoding of the gzip compressed UTF-8 JSON envelope. The encoded string is at most 7000
characters long.

| Property       | Type     | Description                                                                        |
|:---------------|:---------|:-----------------------------------------------------------------------------------|
| `v`            | `int`    | The API version, `1`.                                                              |
| `op`           | `String` | The op name.                                                                       |
| `opV`          | `int`    | The op version.                                                                    |
| `plugin`       | `String` | The plugin bundle version.                                                         |
| `tookMs`       | `long`   | The op run time, in milliseconds.                                                  |
| `node`         | `Object` | `nowMs` (the node's time when the request was handled) and `zone` (its time zone). |
| `data`         | `Object` | The op specific data.                                                              |
| `truncated`    | `List`   | `{path, total, kept, reason}` for each list shortened by a cap or the size budget. |
| `notAvailable` | `List`   | `{item, reason}` for each item that could not be collected; see below.             |
| `redacted`     | `int`    | The number of values masked by redaction.                                          |

A `notAvailable` reason is one of `denied`, `absent`, `timeout`, `unsupported` or `error`.

A `truncated` reason of `maxItems` means a fixed list cap was applied. A reason of `budget` means
items were dropped from the end of the longest list in `data` to fit the result size budget; the
`path` of a list is relative to the envelope, like `data.summaries`. If even that is not enough
the result is reduced to `{v, op, opV, error: "budget"}`.

To decode a result on the command line:

```sh
echo "$RESULT" | base64 -d | gunzip
```

Example decoded `logs` result, for the default parameters:

```json
{
	"v": 1,
	"op": "logs",
	"opV": 1,
	"plugin": "1.0.0",
	"tookMs": 412,
	"node": {
		"nowMs": 1792096200000,
		"zone": "Pacific/Auckland"
	},
	"data": {
		"request": {
			"sinceMs": 1792009800000,
			"untilMs": 1792096200000,
			"minLevel": "WARN",
			"limit": 20
		},
		"window": {
			"requested": {
				"sinceMs": 1792009800000,
				"untilMs": 1792096200000
			},
			"available": {
				"earliestMs": 1791889201042,
				"latestMs": 1792096198004
			}
		},
		"layout": "default",
		"scanned": {
			"files": 2,
			"bytes": 3145728,
			"lines": 24117,
			"entries": 19870,
			"matched": 293,
			"unparsed": 0,
			"longLines": 0,
			"rotatedDuringScan": false,
			"complete": true
		},
		"summaries": [
			{
				"level": "ERROR",
				"logger": ".node.datum.modbus.ModbusDatumDataSource",
				"pattern": "Communication problem reading source <source> from Modbus device <n>@NiftyTcpModbusNetwork{<target>}: No route to host",
				"count": 288,
				"firstSeenMs": 1792010100123,
				"lastSeenMs": 1792095900123,
				"targets": [
					{
						"value": "192.168.20.33:502",
						"count": 288
					}
				],
				"sources": [
					{
						"value": "/SITE-C/INV/3",
						"count": 288
					}
				],
				"exception": {
					"root": "java.net.NoRouteToHostException"
				}
			},
			{
				"level": "WARN",
				"logger": "twork.node.upload.mqtt.MqttUploadService",
				"pattern": "Error posting datum Datum{…} via MQTT @ mqtts://<target>, falling back to batch mode: Connection lost",
				"count": 5,
				"firstSeenMs": 1792055400517,
				"lastSeenMs": 1792055640861,
				"targets": [
					{
						"value": "queue.solarnetwork.net:8883",
						"count": 5
					}
				],
				"sources": []
			}
		],
		"otherPatterns": {
			"keys": 0,
			"entries": 0
		}
	},
	"truncated": [],
	"notAvailable": [],
	"redacted": 5
}
```

Example decoded `storage` result (shortened):

```json
{
	"v": 1,
	"op": "storage",
	"opV": 1,
	"plugin": "1.0.0",
	"tookMs": 38,
	"node": {
		"nowMs": 1792096200000,
		"zone": "Pacific/Auckland"
	},
	"data": {
		"mounts": [
			{
				"path": "/",
				"type": "ext4",
				"totalBytes": 7516192768,
				"usableBytes": 4831838208
			},
			{
				"path": "/run/solarnode",
				"type": "tmpfs",
				"totalBytes": 104857600,
				"usableBytes": 87031808
			}
		],
		"db": {
			"backend": "h2",
			"dirs": [
				{
					"path": "/run/solarnode/db",
					"files": [
						{
							"name": "solarnode.mv.db",
							"sizeBytes": 6291456,
							"mtimeMs": 1792096140000
						}
					],
					"resetBackups": []
				}
			]
		},
		"settingsBackups": {
			"count": 12,
			"totalBytes": 98304,
			"newestMs": 1792072800000,
			"oldestMs": 1790776800000,
			"files": [
				{
					"name": "settings_20261016T030000.txt",
					"sizeBytes": 8192,
					"mtimeMs": 1792072800000
				}
			]
		},
		"logs": {
			"dir": "/run/solarnode/log",
			"totalBytes": 3145728,
			"files": [
				{
					"name": "solarnode.log",
					"sizeBytes": 1048576,
					"mtimeMs": 1792096198004
				}
			]
		}
	},
	"truncated": [],
	"notAvailable": [
		{
			"item": "dbBackups",
			"reason": "absent"
		}
	],
	"redacted": 0
}
```

## Errors

When a request cannot be handled, the instruction state is `Declined` with `code` and `message`
result parameters. Messages may name a parameter, but never include a parameter value.

| Code       | Description                                                                                                                                                                                                               |
|:-----------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `NDS.0001` | Unknown or missing `op`, or an unsupported `v` API version.                                                                                                                                                               |
| `NDS.0002` | Invalid parameter: an unknown parameter name, a parameter with more than one value, a value that is too long or not printable ASCII, or a value the op rejects (for example `untilMs` more than 5 minutes in the future). |
| `NDS.0003` | Rate limited. A `retryAfterSec` result parameter gives the number of seconds to wait.                                                                                                                                     |
| `NDS.0004` | The op did not finish within its hard deadline.                                                                                                                                                                           |
| `NDS.0005` | The op is disabled by the `disabledOps` setting.                                                                                                                                                                          |
| `NDS.0006` | The instruction is older than `maxInstructionAgeMin`. The message includes the node's current time and the instruction time, in milliseconds, so clock skew is visible.                                                   |
| `NDS.0007` | The op is busy: a previous run exceeded its hard deadline and has not finished yet.                                                                                                                                       |
| `NDS.0100` | Internal error. The message includes only the error's class name.                                                                                                                                                         |

# Configuration

The plugin is configured with a properties file at
`/etc/solarnode/services/net.solarnetwork.node.system.diagnostics.cfg`. All settings are optional,
and changes are applied without restarting SolarNode. For example, to disable the `logs` op:

```
disabledOps = logs
```

Only the settings present in the file are applied: removing a setting, or the whole file, leaves
its last value in place until SolarNode restarts. To change a setting back, set it explicitly
instead, for example `disabledOps =` (empty) to enable every op again, or `opDeadlineMs = 5000`.

| Setting                | Default   | Description                                                                               |
|:-----------------------|:----------|:------------------------------------------------------------------------------------------|
| `disabledOps`          |           | A comma-delimited list of op names to decline with `NDS.0005`, including `capabilities`.  |
| `opDeadlineMs`         | `5000`    | The hard deadline for the `system` and `storage` ops, in milliseconds. Minimum `200`.     |
| `logsDeadlineMs`       | `10000`   | The hard deadline for the `logs` op, in milliseconds. Minimum `200`.                      |
| `maxScanBytes`         | `8388608` | The maximum number of log bytes the `logs` op reads per request.                          |
| `maxInstructionAgeMin` | `15`      | The maximum instruction age, in minutes. Older instructions are declined with `NDS.0006`. |
| `lightRatePerHour`     | `60`      | The light rate class token rate, per hour.                                                |
| `scanRatePerHour`      | `20`      | The scan rate class token rate, per hour.                                                 |

The directories used are derived from the `SOLARNODE_HOME` (or the `sn.home` system property),
`SOLARNODE_RAM_DIR`, `SOLARNODE_DB_DIR`, `SOLARNODE_LOGDIR` and `SOLARNODE_PERSIST_LOGDIR`
environment variables, defaulting to `/var/lib/solarnode` and `/run/solarnode` and directories
within them.

# Safety

This plugin is designed so that it can be left installed on production nodes:

* **Read-only.** It only reads small `/proc` and `os-release` files, file metadata, and the
  SolarNode log files. It never writes files or changes settings.
* **No database connections.** The database backend is detected from OSGi service registration
  metadata only, and database files are only listed. No JDBC connection is ever opened.
* **No processes.** No external commands are run, and `sudo` is never used.
* **Stat only for sensitive files.** Settings backups, database backups and settings audit logs are
  only listed: their names, sizes and modification times are returned, but their contents are never
  read. Only allowlisted `os-release` keys and JVM arguments are returned, and never `-D` system
  property values.
* **Bounded.** Small file reads, directory listings, log scanning (`maxScanBytes`, 8 KB per line)
  and result sizes (7000 characters) are capped, and every op has a deadline.
* **No raw log lines.** The `logs` op returns only normalised patterns, counts and times. Settings
  audit log entries are dropped.
* **On-node redaction.** Every string in a result is redacted before it leaves the node: values of
  sensitive keys (tokens, secrets, passwords, API keys and similar), credential-like words after a
  bare keyword such as `token`, AWS access key IDs, URL credentials and query strings,
  authorization headers, private keys, the contents of `{...}` groups (except a group holding only
  a network address and port or a serial device, like `{192.168.1.2:502}`), long token-like
  strings, and the parameters of known log messages that include instruction data. A log line cut
  by the 8 KB line cap has any partial token-like string at the cut removed before redaction. The `redacted`
  result property counts the masked values.
* **Redaction has limits.** It is based on patterns, so it cannot recognise every secret, such as a
  short secret with no recognisable key name, or a format it does not know. Summaries also include
  host names, IP addresses with ports, device paths and source IDs. Treat results as sensitive.
* **Rate limited.** Requests are limited per rate class and by a shared wall-time budget. Each op
  runs on its own worker thread with a hard deadline, so a slow op holds up other instruction
  handling for at most that deadline.
* **Results persist.** Instruction results are stored in SolarNetwork's instruction history, where
  anyone able to view the node's instructions can read them.
* **No extra authorization.** Any security token able to queue instructions for the node can call
  this plugin. Use `disabledOps`, or uninstall the plugin, to restrict it.

Each request is logged at `INFO` level with the op, the parameter names (never their values), the
outcome and the result size.

[instr-api]: https://github.com/SolarNetwork/solarnetwork/wiki/SolarUser-API#queue-instruction

