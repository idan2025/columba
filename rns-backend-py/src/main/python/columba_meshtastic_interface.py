"""
ColumbaMeshtasticInterface — Reticulum over a stock Meshtastic node for the
Python backend.

The node link (Bluetooth LE / TCP / USB serial), the client-API handshake and
the RNS_Over_Meshtastic fragmenting tunnel all run in Kotlin
(`:rns-meshtastic`'s MeshtasticSession, shared with the Kotlin backend), reached
through `KotlinMeshtasticBridge` (event_bridge.get_meshtastic_bridge()). This
class only adapts that session to an RNS Interface: whole RNS packets in and out.

Deployed by event_bridge.deploy_bundled_interfaces() to
<configdir>/interfaces/ColumbaMeshtasticInterface.py and selected by the
`type = ColumbaMeshtasticInterface` section RnsConfigFile emits.
"""

import RNS
from RNS.Interfaces.Interface import Interface


class ColumbaMeshtasticInterface(Interface):
    DEFAULT_IFAC_SIZE = 8
    HW_MTU = 564  # largest RNS packet the tunnel carries (3 Meshtastic fragments)

    def __init__(self, owner, configuration):
        super().__init__()
        c = Interface.get_config_obj(configuration)
        self.owner = owner
        self.name = c["name"]
        self.HW_MTU = ColumbaMeshtasticInterface.HW_MTU
        self.bitrate = 500
        self.online = False
        self.detached = False

        self.bridge = None
        try:
            from event_bridge import get_meshtastic_bridge
            self.bridge = get_meshtastic_bridge()
        except Exception as e:  # noqa: BLE001
            RNS.log(f"{self}: failed to get KotlinMeshtasticBridge: {e}", RNS.LOG_ERROR)
        if self.bridge is None:
            RNS.log(f"{self}: KotlinMeshtasticBridge not available, interface stays offline", RNS.LOG_ERROR)
            return

        started = self.bridge.start(
            self.name,
            c.get("connection_mode", "ble"),
            c.get("target_device_address", ""),
            c.get("tcp_host", ""),
            int(c.get("tcp_port", 4403)),
            int(c.get("usb_vendor_id", -1)),
            int(c.get("usb_product_id", -1)),
            int(c.get("channel", 0)),
            int(c.get("hop_limit", 3)),
            self._on_packet,
            self._on_online,
        )
        if not started:
            RNS.log(f"{self}: could not start the Meshtastic session", RNS.LOG_ERROR)

    def _on_packet(self, data):
        if self.detached:
            return
        data = bytes(data)
        self.rxb += len(data)
        self.owner.inbound(data, self)

    def _on_online(self, online):
        self.online = bool(online)
        RNS.log(f"{self}: node {'connected' if self.online else 'disconnected'}", RNS.LOG_INFO)

    def process_outgoing(self, data):
        if self.online and self.bridge is not None and self.bridge.send(self.name, data):
            self.txb += len(data)

    def detach(self):
        self.detached = True
        self.online = False
        if self.bridge is not None:
            self.bridge.stop(self.name)

    @staticmethod
    def should_ingress_limit():
        return False

    def __str__(self):
        return f"MeshtasticInterface[{self.name}]"


interface_class = ColumbaMeshtasticInterface
