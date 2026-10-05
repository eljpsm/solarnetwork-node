# SolarNode Bluetooth Setup

This plugin turns the SolarNode Bluetooth setup radio (the BLE peripheral
provided by the `solarnode-bluetooth-setup` OS package) on only while it is
needed, so the node is not reachable over Bluetooth at all times.

The radio is **off** unless at least one of the following holds:

1. **Operational mode.** The configured operational mode (default `bt-setup`) is
   active. SolarNetwork can enable this remotely, with an expiration, using the
   standard `EnableOperationalModes` instruction. A Bluetooth client can enable,
   extend, or end it with the `enable` and `disable` actions described below.
2. **Offline fallback.** The node has been unable to reach SolarNetwork for at
   least the _Offline Threshold_ number of minutes. The radio stays on until the
   _Online Grace_ number of minutes after the connection is restored. This
   covers the case where a technician visits a site because the node is offline,
   when no remote trigger can reach it.
3. **Always on.** The _Always On_ setting is enabled.

Whenever the desired state differs from the actual state the plugin runs
`solarcfg bluetooth enable` or `solarcfg bluetooth disable`. It also checks the
actual state periodically, so a radio left on by something else (for example
after an OS upgrade) is turned off.

# Settings

| Setting           | Default                                                    | Description                                                                                                                                        |
| ----------------- | ---------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| Status            |                                                            | The current radio state and why it is on.                                                                                                          |
| Enable For        | `0`                                                        | Enter a number of minutes to turn the radio on for. Transient: returns to `0` once applied.                                                        |
| Always On         | off                                                        | Keep the radio on at all times.                                                                                                                    |
| Operational Mode  | `bt-setup`                                                 | The operational mode that turns the radio on while it is active.                                                                                   |
| Default Duration  | `1800`                                                     | Seconds to enable the radio for when an `enable` request has no `duration`.                                                                        |
| Maximum Duration  | `14400`                                                    | The longest a single `enable` request can turn the radio on for, in seconds. `0` for no limit.                                                     |
| Offline Trigger   | on                                                         | Turn the radio on automatically while SolarNetwork is unreachable.                                                                                 |
| Offline Threshold | `15`                                                       | Minutes SolarNetwork must be unreachable before the radio turns on.                                                                                |
| Online Grace      | `10`                                                       | Minutes to keep the radio on after SolarNetwork is reachable again.                                                                                |
| Check Interval    | `60`                                                       | Seconds between SolarNetwork connectivity checks. `0` disables them.                                                                               |
| Ping Test ID      | `net\.solarnetwork\.node\.upload\.mqtt\.MqttUploadService` | Regular expression matching the ID of the ping test that indicates SolarNetwork connectivity. If no test matches, the offline trigger never fires. |
| Command           | `{sn.home}/bin/solarcfg`                                   | The `solarcfg` command to execute.                                                                                                                 |

The connectivity check uses the node's own system health ping tests, so by
default it reflects whether the SolarIn/MQTT connection is established. Nodes
that upload over HTTP instead can point _Ping Test ID_ at a different test, or
configure an HTTP Ping control and use its ID.

# Turning the radio on

## From SolarNetwork

Queue an `EnableOperationalModes` instruction for the node with the mode name
and an expiration (milliseconds since the epoch). For example, with the
SolarUser API:

```
POST /solaruser/api/v1/sec/instr/add/EnableOperationalModes
nodeId=123&parameters[0].name=OpMode&parameters[0].value=bt-setup&parameters[1].name=Expiration&parameters[1].value=1791000000000
```

The node persists the mode across restarts and turns it off automatically at the
expiration. A `DisableOperationalModes` instruction with `OpMode=bt-setup` turns
the radio off early.

## Over Bluetooth (STOMP)

This plugin handles the `SystemConfigure` instruction topic when the `service`
parameter is `/setup/bluetooth`. Over the Bluetooth STOMP setup connection the
destination becomes the `service` parameter and any other headers become
instruction parameters, so an authenticated client can send:

```
SEND
destination:/setup/bluetooth
action:enable
duration:600

^@
```

| `action`  | Parameters           | Effect                                                                                                                                              |
| --------- | -------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `status`  |                      | Return the current status (the default when no action is given).                                                                                    |
| `enable`  | `duration` (seconds) | Enable the operational mode for `duration` seconds (default _Default Duration_, capped at _Maximum Duration_). Calling it again extends the window. |
| `disable` |                      | Disable the operational mode. The radio turns off unless the offline fallback or _Always On_ holds, which **drops the client's own connection**.    |
| `restart` |                      | Restart the Bluetooth peripheral service.                                                                                                           |

The `result` of `status`, `enable` and `disable` is a status object:

```json
{
	"radioActive": true,
	"discoverable": true,
	"powered": true,
	"installEnabled": false,
	"opModeActive": true,
	"opModeExpires": 1791000000000,
	"offline": false,
	"offlineSince": null,
	"alwaysOn": false
}
```

The same instruction can be queued from SolarNetwork as well, with
`service=/setup/bluetooth` and `action=enable`.

# Notes

- The offline state is not persisted. If the node restarts while offline the
  radio stays off until the _Offline Threshold_ elapses again.
- The OS package's own `CFG_BT_SETUP_ALWAYS_ON` option keeps the peripheral
  enabled at boot without this plugin. With this plugin installed, use the
  _Always On_ setting instead.
- This plugin requires `solarnode-bluetooth-setup` 4.0 or later, which provides
  the `solarcfg bluetooth` helper.
