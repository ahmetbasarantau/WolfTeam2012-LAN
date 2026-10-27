import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]


class StartupTests(unittest.TestCase):
    def start(self, trace):
        env = os.environ.copy()
        env.update(WOLF_TRACE_DIR=str(trace), WOLF_BIND_IP='127.0.0.1', WOLF_LAN_SUBNET='127.0.0.0/8')
        return subprocess.Popen([sys.executable, '-u', str(ROOT/'server/local_server.py')],
                                cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def test_actual_tcp_udp_readiness_owned_pid(self):
        # These are the fixed service ports consumed by this particular client.
        sockets = []
        try:
            for kind, ports in ((socket.SOCK_STREAM,(80,30706,40706,40707,10300)),
                                 (socket.SOCK_DGRAM,(40707,40708))):
                for port in ports:
                    sock = socket.socket(socket.AF_INET,kind)
                    sockets.append(sock)
                    if hasattr(socket,'SO_EXCLUSIVEADDRUSE'):
                        sock.setsockopt(socket.SOL_SOCKET,socket.SO_EXCLUSIVEADDRUSE,1)
                    try: sock.bind(('127.0.0.1',port))
                    except OSError as exc: self.skipTest(f'Service port {port} unavailable: {exc}')
        finally:
            for sock in sockets: sock.close()
        with tempfile.TemporaryDirectory() as temp:
            process = self.start(temp)
            try:
                ready = Path(temp)/'ready.json'
                deadline = time.monotonic()+8
                while not ready.exists() and process.poll() is None and time.monotonic()<deadline:
                    time.sleep(.05)
                self.assertTrue(ready.exists(), 'Startup did not publish readiness')
                status = json.loads(ready.read_text())
                self.assertEqual(status['pid'], process.pid)
                self.assertEqual(len(status['services']),7)
                self.assertIn(['udp',40708],status['services'])
                with socket.create_connection(('127.0.0.1',80),timeout=2) as client:
                    client.sendall(b'GET /unknown HTTP/1.0\r\n\r\n')
                    self.assertIn(b'404',client.recv(1024))
            finally:
                if process.poll() is None: process.terminate()
                process.communicate(timeout=3)

    def test_port_conflict_exits_without_ready_marker(self):
        with socket.socket() as occupied, tempfile.TemporaryDirectory() as temp:
            if hasattr(socket,'SO_EXCLUSIVEADDRUSE'):
                occupied.setsockopt(socket.SOL_SOCKET,socket.SO_EXCLUSIVEADDRUSE,1)
            try: occupied.bind(('127.0.0.1',30706))
            except OSError as exc: self.skipTest(f'Broker port already occupied: {exc}')
            occupied.listen(1)
            process = self.start(temp)
            try:
                output,_ = process.communicate(timeout=8)
                self.assertEqual(process.returncode,1,output.decode(errors='replace'))
                self.assertFalse((Path(temp)/'ready.json').exists())
                # The conflicting process/socket is still alive.
                self.assertEqual(occupied.getsockname()[1],30706)
            finally:
                if process.poll() is None:
                    process.terminate()
                    process.communicate(timeout=3)
