# SolarNode MQTT SolarIn Connector

This project provides SolarNode plugin that uses a SolarIn MQTT server to
post datum to SolarNet and receive instructions from SolarNet, in real time.

# Install

This is a core SolarNode plugin and must be deployed manually or as part of
the base platform.

# Use

There are no user-visible settings for this plugin. Once installed, SolarNode
will connect to the SolarIn MQTT server returned as the **solarin-mqtt**
service URL reported by SolarIn.

Note that this plugin works in **conjuction** with the `BulkUploadService`
(generally provided by the [Bulk JSON Web Uploader][bulkjsonwebpost] plugin).
If a connection to the MQTT server is not available for any reason, then datum
will be persisted locally, to be picked up by the bulk upload service, the same
as if this plugin were not installed.

As of version **1.7** MQTT version 5 is used by default.

# Instruction handling

This plugin listens for instructions from SolarNet as well, and attempts to
execute the instructions immediately and then post the resulting execution
status to SolarNet (via MQTT). If any step in that process fails, the instruction
will be persisted locally, to be picked up by the batch instruction service, the
same as if this plugin were not installed. The batch instruction service is
typically provided by the [Reactor Service][reactor.simple] plugin.

# Configuration

The following properties can be configured in an
`/etc/solarnode/services/net.solarnetwork.node.upload.mqtt.cfg` file:

| Property                    | Default | Description                                                                                                                                                                                                    |
|:----------------------------|:--------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `client.keepAliveInterval`  | `60`    | The MQTT keep-alive (PING) interval, in seconds. Set to `0` to disable.                                                                                                                                        |
| `client.maximumMessageSize` | `8192`  | The maximum MQTT message size, in bytes, for both published and received messages. Must be between `1` and `256000000`. Messages larger than this, such as large instruction results, cannot be sent via MQTT. |

Increasing `client.maximumMessageSize` only helps if the SolarIn MQTT server also accepts messages
of the configured size.

[bulkjsonwebpost]: https://github.com/SolarNetwork/solarnetwork-node/tree/master/net.solarnetwork.node.upload.bulkjsonwebpost
[reactor.simple]: https://github.com/SolarNetwork/solarnetwork-node/tree/master/net.solarnetwork.node.reactor.simple

