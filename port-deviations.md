# Port deviations

Columba is a port of the LXMF/Reticulum stack from python (`~/repos/Reticulum`,
`~/repos/LXMF`) to Kotlin / Android. Per `feedback-port-must-match-reference`, the kt
port repos (`reticulum-kt`, `LXMF-kt`) mirror the python source and only diverge with
documented justification. This file lives in the Columba app itself and tracks
**Android-app-only divergences** — features that exist in Columba but have no python
upstream equivalent and aren't expected to back-port. The kt/swift port libraries each
maintain their own `port-deviations.md`.

---

## `InterfaceConfig.networkRestriction` (Columba-only)

Each interface configuration carries a `networkRestriction: NetworkRestriction = ANY`
field with values `ANY | WIFI_ONLY | CELLULAR_ONLY`. The python reference
(`RNS/Interfaces/`) has no equivalent — `AutoInterface.carrier_changed` is internal-only
and not user-controllable, and no other interface type exposes a transport filter.

The field is enforced by `InterfaceTransportFilter.filterByTransport` against the
device's current `NetworkCapabilities` (Wi-Fi/Ethernet → `WIFI_LIKE`, cellular →
`CELLULAR`, unsupported live default route → `UNKNOWN`, none → `NONE`). On transport
transitions, `InterfaceTransportObserver` re-applies the filter and feeds the resulting subset into
`ReticulumProtocol.reloadInterfaces`. The filter ignores the field for non-IP
transports (`AndroidBLE` and `RNode` with `connectionMode != "tcp"`) — those don't
ride on the IP carrier so the restriction is meaningless for them.

**`AutoInterface` defaults to `WIFI_ONLY`** (not `ANY`). UDP multicast does not work
over mobile carriers, so an AutoInterface enabled on cellular just generates noise
without producing peers. All other types default to `ANY`.

**Justification.** This is a mobile-specific concern (battery + bandwidth on cellular)
that's irrelevant to the desktop python use case. Sideband (`~/repos/Sideband/sbapp/`)
doesn't have an equivalent either. Adding it here is a deliberate Columba divergence,
not a port gap.

**ETHERNET bucketing.** `TRANSPORT_ETHERNET` is treated as `WIFI_LIKE` so USB tethering
to a PC and dock setups behave like the user expects (a wired LAN-only TCP transport
should reach a LAN node over Ethernet, not get filtered out as "not Wi-Fi"). A
VPN-only or otherwise unsupported live default network is classified as `UNKNOWN` so
unrestricted IP interfaces stay up while Wi-Fi-only and cellular-only interfaces remain
safely filtered out. If Android exposes a deterministic underlying transport on the same
default-network capabilities, that underlying transport wins. `NONE` is reserved for the
absence of a live default route.

---

## `InterfaceConfig.Meshtastic` (Columba-only)

A `Meshtastic` interface type runs Reticulum through a stock Meshtastic node, over
Bluetooth LE, the node's Wi-Fi API (TCP 4403) or USB serial. Upstream RNS has no
Meshtastic interface; the closest reference is the third-party custom interface
[landandair/RNS_Over_Meshtastic](https://github.com/landandair/RNS_Over_Meshtastic)
for desktop `rnsd`, and this implementation is wire-compatible with it: same
`RETICULUM_TUNNEL_APP` port, same `[index u8][pos i8] + chunk` fragments (checked
byte-for-byte against its `PacketHandler` output in `MeshtasticTest`), same `REQ`
resend requests, same per-preset send pacing. Python `rnsd` nodes running that
interface and Columba reach each other over the air.

The protocol code lives in `:rns-meshtastic` (`MeshtasticSession`, node links, the
client-API protobuf subset, `RnsTunnel`) and is shared by both backends:
`:rns-backend-kt` wraps it as a reticulum-kt `Interface`; the Python backend's bundled
`ColumbaMeshtasticInterface` drives it through `KotlinMeshtasticBridge` (the
`meshtastic` Python package can't reach serial or BLE under Chaquopy).

**Deliberate differences from the reference interface:**
- Replies to link traffic are sent unicast to the node the link lives behind (the
  reference tries to, but its check compares against the class, so it always broadcasts).
- A packet is reassembled once every fragment is in, even when the last fragment
  arrived before a resent middle one (the reference drops it).
- The node's radio config is never written; pacing follows the node's own preset
  (the reference rewrites the preset to its `data_speed` setting).
- Only tunnel packets on the configured channel, addressed to this node or broadcast,
  are accepted.

**Justification.** Meshtastic nodes are common hardware; this lets their owners carry
Reticulum traffic without reflashing, alongside (not instead of) RNode. It is opt-in
and off the default interface set. Hop limit defaults to 3 and the UI explains that
higher values make every relay on the channel retransmit the traffic.
