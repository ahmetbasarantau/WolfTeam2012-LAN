import unittest
import socket
import struct
import threading
import ipaddress
import sys
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SERVER_DIR = os.path.dirname(SCRIPT_DIR)
if SERVER_DIR not in sys.path:
    sys.path.insert(0, SERVER_DIR)

from broker_codec import BrokerCodec
import lan_discovery


class LanDiscoveryTests(unittest.TestCase):
    def test_get_local_subnets(self):
        subnets, local_ips = lan_discovery.get_local_subnets()
        self.assertIsInstance(subnets, list)
        self.assertIsInstance(local_ips, list)
        for net in subnets:
            self.assertIsInstance(net, ipaddress.IPv4Network)
            self.assertTrue(net.is_private)

    def test_query_server_details(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.bind(('127.0.0.1', 0))
        port = srv.getsockname()[1]
        srv.listen(1)

        def mock_broker():
            try:
                conn, _ = srv.accept()
                codec = BrokerCodec()
                msg_id, seq, payload = codec.read_frame(conn)
                if msg_id == 0x1106:
                    name = "Balıkesirli Wolftimciler Derneği".encode("utf-16le")
                    p = bytearray([0x00, 2, len(name)]) + name + struct.pack("<HHBB", 0, 0, 0, 0)
                    codec.write_frame(conn, 0x1107, 0, bytes(p))
                conn.close()
            except Exception:
                pass

        t = threading.Thread(target=mock_broker, daemon=True)
        t.start()

        details = lan_discovery.query_server_details('127.0.0.1', port=port, timeout=1.0)
        srv.close()

        self.assertEqual(details["ip"], "127.0.0.1")
        self.assertEqual(details["port"], port)
        self.assertEqual(details["name"], "Balıkesirli Wolftimciler Derneği")
        self.assertEqual(details["channels"], 2)


if __name__ == '__main__':
    unittest.main()
