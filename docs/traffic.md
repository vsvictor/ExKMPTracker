# Application network traffic counters

[Українською](traffic.uk.md) | **English**

The `TrafficCounter` port exposes two synchronous methods, not a `Future`:

- `totalIncomingBytes(): Long` — bytes received by the application UID;
- `totalOutgoingBytes(): Long` — bytes sent by the application UID.

`AndroidTrafficCounter` reads `TrafficStats.getUidRxBytes` and `getUidTxBytes` for
`Process.myUid()`. The baseline is the value when that counter instance is created,
not Flutter engine attach and not process start. Each call returns the difference,
floored at zero. The totals cover every component of the UID: JSON-RPC, TLS/QUIC
overhead, map downloads and the foreground service. This is UID byte accounting, not
a packet capture: link-layer framing and other UIDs are excluded. The values live
only in the process and reset when the counter is created again. If Android returns
`TrafficStats.UNSUPPORTED`, the method throws `UnsupportedOperationException`.

`IosTrafficCounter` always throws `UnsupportedOperationException` with the message
that iOS does not expose total network traffic for an app UID. It does not return
zero in place of the error.

This library has no statistics screen. Host UI that shows the counters calls them
itself and handles the iOS error.
