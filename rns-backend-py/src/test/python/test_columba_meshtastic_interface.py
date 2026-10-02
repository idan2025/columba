"""Unit tests for ColumbaMeshtasticInterface (Python backend's Meshtastic adapter).

Loads the real columba_meshtastic_interface module with stubbed RNS / Interface
base / event_bridge and a fake KotlinMeshtasticBridge.
"""
import importlib.util
import sys
import types
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[2] / "main/python/columba_meshtastic_interface.py"


class _Interface:
    def __init__(self):
        self.rxb = 0
        self.txb = 0

    @staticmethod
    def get_config_obj(configuration):
        return configuration


class _FakeBridge:
    def __init__(self, start_ok=True, send_ok=True):
        self.start_ok = start_ok
        self.send_ok = send_ok
        self.started = None
        self.sent = []
        self.stopped = []

    def start(self, *args):
        self.started = args
        return self.start_ok

    def send(self, name, data):
        self.sent.append((name, data))
        return self.send_ok

    def stop(self, name):
        self.stopped.append(name)


class _Owner:
    def __init__(self):
        self.inbound_calls = []

    def inbound(self, data, iface):
        self.inbound_calls.append((data, iface))


def _load_module(bridge):
    rns = types.ModuleType("RNS")
    rns.LOG_ERROR = 1
    rns.LOG_INFO = 3
    rns.log = lambda *a, **k: None
    interfaces_pkg = types.ModuleType("RNS.Interfaces")
    iface_mod = types.ModuleType("RNS.Interfaces.Interface")
    iface_mod.Interface = _Interface
    event_bridge = types.ModuleType("event_bridge")
    event_bridge.get_meshtastic_bridge = lambda: bridge
    sys.modules["RNS"] = rns
    sys.modules["RNS.Interfaces"] = interfaces_pkg
    sys.modules["RNS.Interfaces.Interface"] = iface_mod
    sys.modules["event_bridge"] = event_bridge
    spec = importlib.util.spec_from_file_location("columba_meshtastic_interface_test", MODULE_PATH)
    assert spec is not None and spec.loader is not None
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class ColumbaMeshtasticInterfaceTest(unittest.TestCase):
    def _make(self, config, bridge=None):
        bridge = bridge if bridge is not None else _FakeBridge()
        mod = _load_module(bridge)
        owner = _Owner()
        iface = mod.ColumbaMeshtasticInterface(owner, config)
        return iface, owner, bridge

    def test_starts_session_with_config_values(self):
        iface, _, bridge = self._make(
            {
                "name": "Mesh",
                "connection_mode": "usb",
                "usb_vendor_id": "9114",
                "usb_product_id": "33035",
                "channel": "1",
                "hop_limit": "2",
            }
        )
        name, mode, address, host, port, vid, pid, channel, hops, on_packet, on_online = bridge.started
        self.assertEqual(("Mesh", "usb", "", "", 4403, 9114, 33035, 1, 2), (name, mode, address, host, port, vid, pid, channel, hops))
        self.assertEqual(on_packet, iface._on_packet)
        self.assertEqual(on_online, iface._on_online)
        self.assertEqual(564, iface.HW_MTU)
        self.assertFalse(iface.online)

    def test_unset_usb_ids_are_passed_as_minus_one(self):
        _, _, bridge = self._make({"name": "Mesh", "connection_mode": "ble", "target_device_address": "AA:BB:CC:DD:EE:FF"})
        self.assertEqual(("ble", "AA:BB:CC:DD:EE:FF"), bridge.started[1:3])
        self.assertEqual((-1, -1), bridge.started[5:7])

    def test_outgoing_only_when_online(self):
        iface, _, bridge = self._make({"name": "Mesh"})
        iface.process_outgoing(b"early")
        self.assertEqual([], bridge.sent)
        iface._on_online(True)
        iface.process_outgoing(b"hello")
        self.assertEqual([("Mesh", b"hello")], bridge.sent)
        self.assertEqual(5, iface.txb)

    def test_dropped_send_is_not_counted(self):
        iface, _, _ = self._make({"name": "Mesh"}, _FakeBridge(send_ok=False))
        iface._on_online(True)
        iface.process_outgoing(b"hello")
        self.assertEqual(0, iface.txb)

    def test_incoming_packets_reach_transport_as_bytes(self):
        iface, owner, _ = self._make({"name": "Mesh"})
        iface._on_packet(bytearray(b"\x01\x02\x03"))
        self.assertEqual([(b"\x01\x02\x03", iface)], owner.inbound_calls)
        self.assertIsInstance(owner.inbound_calls[0][0], bytes)
        self.assertEqual(3, iface.rxb)

    def test_detach_stops_session_and_ignores_late_packets(self):
        iface, owner, bridge = self._make({"name": "Mesh"})
        iface._on_online(True)
        iface.detach()
        self.assertEqual(["Mesh"], bridge.stopped)
        self.assertFalse(iface.online)
        iface._on_packet(b"late")
        self.assertEqual([], owner.inbound_calls)

    def test_missing_bridge_leaves_interface_offline(self):
        mod = _load_module(None)
        iface = mod.ColumbaMeshtasticInterface(_Owner(), {"name": "Mesh"})
        self.assertFalse(iface.online)
        iface.process_outgoing(b"x")  # no crash
        iface.detach()


if __name__ == "__main__":
    unittest.main()
