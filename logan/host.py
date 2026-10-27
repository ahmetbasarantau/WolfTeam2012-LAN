"""Own the donor Java/PHP/MariaDB processes. No gameplay protocol lives here."""
import argparse
import concurrent.futures
import contextlib
import ctypes
import http.server
import ipaddress
import json
import os
from pathlib import Path
import signal
import socket
import socketserver
import sqlite3
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parent.parent
RUNTIME = ROOT / '.runtime' / 'logan'
MARIA = RUNTIME / 'mariadb' / 'mariadb-11.4.8-winx64' / 'bin'
STOP = threading.Event()
DISCOVERY_PORT = 40705
DISCOVERY_REQUEST = b'WOLFTEAM_2012_LAN_DISCOVER_V1'
DISCOVERY_REPLY = b'WOLFTEAM_2012_LAN_SERVER_V1'


def serve_lan_discovery(sock, allowed_network):
    """Answer launcher broadcasts on the selected LAN/Radmin interface."""
    sock.settimeout(.5)
    while not STOP.is_set():
        try:
            data, peer = sock.recvfrom(256)
        except socket.timeout:
            continue
        except OSError as exc:
            if getattr(exc, 'winerror', None) == 10054:
                continue
            break
        if data == DISCOVERY_REQUEST and ipaddress.ip_address(peer[0]) in allowed_network:
            try:
                sock.sendto(DISCOVERY_REPLY, peer)
            except OSError:
                # One disconnected VPN peer must not stop discovery for everyone.
                continue


def wait_port(port, process=None, address='127.0.0.1', timeout=30):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if process and process.poll() is not None:
            raise RuntimeError(f'Service exited with {process.returncode}; inspect the run logs')
        try:
            with socket.create_connection((address, port), .2):
                return
        except OSError:
            time.sleep(.15)
    raise RuntimeError(f'Service {address}:{port} did not become ready')


def sql(text, database=False):
    args = [str(MARIA / 'mariadb.exe'), '--no-defaults', '--protocol=tcp',
            '-h127.0.0.1', '-P13306', '-uroot', '--batch', '--skip-column-names']
    if database:
        args.append('wolfteam')
    return subprocess.run(args, input=text, text=True, encoding='utf-8',
                          capture_output=True, check=True, creationflags=0x08000000).stdout.strip()


def verify_schema():
    """Fail clearly on a partial/old local DB instead of starting a broken API."""
    required = {
        'users': {'id', 'username', 'password', 'callsign', 'level', 'gp', 'gold', 'wc', 'cash', 'kill', 'death', 'last_login'},
        'inventory': {'id', 'user_id', 'item_id', 'type', 'upgrade', 'expiry'},
        'equipment': {'id', 'user_id', 'slot', 'item_id'},
        'char_slots': {'id', 'user_id', 'slot', 'char_id'},
        'character_equipment': {'user_id', 'char_id', 'slot', 'item_id'},
        'character_loadout': {'user_id', 'red_char_id', 'blue_char_id', 'updated_at'},
        'power_slots': {'user_id', 'slot', 'unlocked_at'},
        'weapon_slot_extensions': {'user_id', 'set_index', 'unlocked_at'},
        'kill_log': {'id', 'killer_id', 'victim_id', 'timestamp'},
        'channels': {'id', 'name', 'port', 'is_open', 'enabled', 'f1', 'f2', 'f3', 'f4'},
    }
    names = ','.join("'%s'" % name for name in required)
    tables = set(sql(
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema='wolfteam' AND table_name IN (%s)" % names, True
    ).splitlines())
    missing_tables = set(required) - tables
    columns = set(sql(
        "SELECT CONCAT(table_name,'.',column_name) FROM information_schema.columns "
        "WHERE table_schema='wolfteam' AND table_name IN (%s)" % names, True
    ).splitlines())
    missing_columns = sorted('%s.%s' % (table, column)
                             for table, expected in required.items()
                             for column in expected
                             if table in tables and '%s.%s' % (table, column) not in columns)
    if missing_tables or missing_columns:
        details = []
        if missing_tables:
            details.append('missing tables: ' + ', '.join(sorted(missing_tables)))
        if missing_columns:
            details.append('missing columns: ' + ', '.join(missing_columns))
        raise RuntimeError('Local WolfTeam database schema is incomplete; ' + '; '.join(details)
                           + '. Existing data was left untouched.')


def hex_string(value):
    return "CONVERT(X'%s' USING utf8mb4)" % value.encode('utf-8').hex()


def import_legacy():
    """Import owned items once. Do not reinterpret the old, incorrect role offsets."""
    source = ROOT / 'server' / 'player_state.sqlite3'
    if not source.exists():
        return
    sql('CREATE TABLE IF NOT EXISTS local_imports (player VARCHAR(32) PRIMARY KEY)', True)
    with contextlib.closing(sqlite3.connect(f'file:{source.as_posix()}?mode=ro', uri=True)) as old:
        for (name,) in old.execute('SELECT DISTINCT player FROM items'):
            literal = hex_string(name)
            if sql(f'SELECT COUNT(*) FROM local_imports WHERE player={literal}', True) != '0':
                continue
            rows = [x[0] for x in old.execute('SELECT item_id FROM items WHERE player=?', (name,))]
            # PHP creates the same fresh profile for both imported and first-time LAN names.
            import urllib.parse
            import urllib.request
            user_id = int(urllib.request.urlopen('http://127.0.0.1:18080/wolfteam/users.php?req=login&name=' + urllib.parse.quote(name), timeout=5).read())
            commands = ['START TRANSACTION;']
            for item in rows:
                if not 0 < item <= 65535:
                    continue
                commands.append(f"INSERT INTO inventory(user_id,item_id,type,upgrade,expiry) SELECT {user_id},{item},{int(30000 <= item < 40000)},0,'permanent' WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE user_id={user_id} AND item_id={item});")
            commands.extend([f'INSERT INTO local_imports VALUES({literal});', 'COMMIT;'])
            sql('\n'.join(commands), True)
            print(f'[IMPORT] {name}: owned items copied; original SQLite untouched', flush=True)


class UpdateHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        # Do not log client IPs; record only the updater route and response below.
        pass

    def _serve_update(self, include_body):
        path = self.path.split('?', 1)[0]
        if path == '/wolfteam/discovery':
            payload = json.dumps({'protocol': 'wolfteam-radmin-v1',
                                  'ip': self.server.server_address[0], 'port': 40706,
                                  'name': 'WolfTeam Radmin Sunucusu', 'channels': 1}).encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(payload)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            if include_body:
                self.wfile.write(payload)
            return
        if path not in ('/update.u', '/apex/wolfteam/psserver/web/update.u'):
            self.send_error(404)
            print(f'[UPDATE] {self.command} {path} -> 404', flush=True)
            return
        update_file = ROOT / 'avital' / 'update.u'
        if not update_file.is_file():
            self.send_error(500)
            print(f'[UPDATE] {self.command} {path} -> 500 (update.u missing)', flush=True)
            return
        data = update_file.read_bytes()
        self.send_response(200)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        if include_body:
            self.wfile.write(data)
        print(f'[UPDATE] {self.command} {path} -> 200 ({len(data)} bytes)', flush=True)

    def do_GET(self):
        self._serve_update(include_body=True)

    def do_HEAD(self):
        self._serve_update(include_body=False)


class Auxiliary(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(1)
        while not STOP.is_set():
            try:
                if not self.request.recv(1024):
                    break
            except socket.timeout:
                pass
            except OSError:
                break


class AuxiliaryServer(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bind', default='127.0.0.1')
    parser.add_argument('--discovery-subnet', help='Selected LAN/Radmin network allowed to discover this host')
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--init-only', action='store_true')
    parser.add_argument('--services-only', action='store_true',
                        help='Run only MariaDB and the loopback PHP API; do not launch game services')
    args = parser.parse_args()
    bind_address = ipaddress.IPv4Address(args.bind)
    # Older launchers do not pass a subnet. Keep their Radmin/loopback scope;
    # updated launchers pass the actual selected interface subnet explicitly.
    default_prefix = 8 if bind_address.is_loopback or bind_address in ipaddress.ip_network('26.0.0.0/8') else 24
    discovery_network = ipaddress.ip_network(args.discovery_subnet or f'{args.bind}/{default_prefix}', strict=False)
    if discovery_network.version != 4 or bind_address not in discovery_network:
        parser.error('Discovery subnet must contain the selected IPv4 bind address')
    args.run.mkdir(parents=True, exist_ok=True)
    # Killing the supervisor also kills only its children (launcher window close included).
    import sys
    sys.path.insert(0, str(ROOT / 'server'))
    from client_diagnostics import EXTENDED_LIMIT, checked, kernel
    job = checked(kernel.CreateJobObjectW(None, None))
    limits = EXTENDED_LIMIT()
    limits.BasicLimitInformation.LimitFlags = 0x2000
    checked(kernel.SetInformationJobObject(job, 9, ctypes.byref(limits), ctypes.sizeof(limits)))
    children, logs, servers, discovery_sockets = [], [], [], []

    def start(name, command, cwd=ROOT, env=None):
        out = open(args.run / (name + '.stdout.log'), 'wb')
        err = open(args.run / (name + '.stderr.log'), 'wb')
        logs.extend([out, err])
        process = subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                   stdout=out, stderr=err, creationflags=0x08000000)
        children.append(process)
        checked(kernel.AssignProcessToJobObject(job, int(process._handle)))
        return process

    def stop(*_):
        STOP.set()
    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    try:
        for port in (13306, 18080):
            with socket.socket() as probe:
                try:
                    probe.bind(('127.0.0.1', port))
                except OSError:
                    raise RuntimeError(f'Private service port {port} is occupied; another process is not stopped automatically')
        data = RUNTIME / 'data'
        if not (data / 'mysql').is_dir():
            subprocess.run([str(MARIA / 'mariadb-install-db.exe'),
                            '--datadir=' + str(data), '--port=13306'], check=True,
                           creationflags=0x08000000)
        database = start('database', [str(MARIA / 'mariadbd.exe'), '--no-defaults',
                         '--datadir=' + str(data), '--bind-address=127.0.0.1', '--port=13306',
                         '--skip-name-resolve', '--console', '--innodb-buffer-pool-size=32M'])
        wait_port(13306, database)
        exists = sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='wolfteam' AND table_name='users'")
        if exists == '0':
            sql((ROOT / 'logan/sql/schema.sql').read_text(encoding='utf-8-sig'))
            sql("UPDATE channels SET port=40707; UPDATE channels SET name='LAN',f4=0", True)
        # Additive, idempotent upgrades for existing profiles. No equipment or
        # character selections are deleted by these migrations.
        for migration in ('011_character_equipment.sql', '012_default_open_slots.sql',
                          '013_repair_weapon_roles.sql'):
            sql((ROOT / 'logan/sql/migrations' / migration).read_text(encoding='utf-8-sig'))
        verify_schema()
        php = RUNTIME / 'php' / 'php.exe'
        api = start('api', [str(php), '-n', '-d', 'extension_dir=' + str(php.parent / 'ext'),
                    '-d', 'extension=php_pdo_mysql.dll', '-d', 'display_errors=0',
                    '-d', 'log_errors=1', '-S', '127.0.0.1:18080', '-t', str(ROOT / 'logan/web')])
        wait_port(18080, api)
        import_legacy()
        if args.init_only:
            print('[READY] database initialized and legacy ownership imported', flush=True)
            return
        if args.services_only:
            ready = {'pid': os.getpid(), 'engine': 'local-inventory-api',
                     'children': [p.pid for p in children], 'services': ['database', 'api']}
            (args.run / 'ready.json').write_text(json.dumps(ready), encoding='utf-8')
            print('[READY] Local inventory API is ready; game services were not started', flush=True)
            while not STOP.wait(.5):
                if (args.run / 'stop.request').exists():
                    break
                for child in children:
                    if child.poll() is not None:
                        raise RuntimeError(f'Owned service {child.pid} exited: {child.returncode}')
            return
        for server in (http.server.ThreadingHTTPServer((args.bind, 80), UpdateHandler),
                       AuxiliaryServer((args.bind, 10300), Auxiliary)):
            servers.append(server)
            threading.Thread(target=server.serve_forever, daemon=True).start()
        discovery = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        # A socket bound only to a unicast address can miss directed broadcasts.
        # Listen for broadcasts on all interfaces and enforce the selected LAN
        # on received peers before replying. Game sockets stay on args.bind.
        discovery.bind(('0.0.0.0', DISCOVERY_PORT))
        discovery_sockets.append(discovery)
        threading.Thread(target=serve_lan_discovery, args=(discovery, discovery_network), daemon=True,
                         name='lan-discovery').start()
        env = dict(os.environ, WOLF_BIND_IP=args.bind)
        java = start('java', [str(RUNTIME / 'java/bin/java.exe'), '-Dwt.log.udp=false',
                    '-Dwt.api=http://127.0.0.1:18080/wolfteam/', '-cp', str(RUNTIME / 'classes'),
                    'wtserver.WTServer'], cwd=ROOT / 'logan/resources', env=env)
        with concurrent.futures.ThreadPoolExecutor(3) as executor:
            list(executor.map(lambda p: wait_port(p, java, args.bind), (30706, 40706, 40707)))
        ready = {'pid': os.getpid(), 'engine': 'logan-java', 'bind': args.bind,
                 'children': [p.pid for p in children],
                 'services': ['database', 'api', 'update', 'auxiliary', 'lan-discovery', 'broker', 'broker-legacy', 'game']}
        (args.run / 'ready.json').write_text(json.dumps(ready), encoding='utf-8')
        print('[READY] WolfTeam 2012 LAN services are ready', flush=True)
        while not STOP.wait(.5):
            if (args.run / 'stop.request').exists():
                break
            for child in children:
                if child.poll() is not None:
                    raise RuntimeError(f'Owned service {child.pid} exited: {child.returncode}')
    finally:
        STOP.set()
        for sock in discovery_sockets:
            sock.close()
        for server in servers:
            server.shutdown()
            server.server_close()
        for child in reversed(children):
            if child.poll() is None:
                if child is children[0]:
                    subprocess.run([str(MARIA / 'mariadb-admin.exe'), '--no-defaults',
                        '-h127.0.0.1', '-P13306', '-uroot', 'shutdown'],
                        capture_output=True, timeout=10, creationflags=0x08000000)
                else:
                    child.terminate()
                try:
                    child.wait(10)
                except subprocess.TimeoutExpired:
                    child.kill()
        kernel.CloseHandle(job)
        for log in logs:
            log.close()


if __name__ == '__main__':
    main()
