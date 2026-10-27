"""
WolfTeam LAN Server Discovery Module
Scans local subnets for active WolfTeam Broker servers on port 40706
and queries channel/server information using BrokerCodec.
"""

import sys
import os
import socket
import asyncio
import struct
import json
import ipaddress
import argparse
import subprocess
import shutil
import time
import select
import urllib.request

# Ensure server path is in sys.path
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from radmin_peers import get_radmin_peers

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

DEFAULT_PORT = 40706
DISCOVERY_PORT = 40705
DISCOVERY_REQUEST = b'WOLFTEAM_2012_LAN_DISCOVER_V1'
DISCOVERY_REPLY = b'WOLFTEAM_2012_LAN_SERVER_V1'


def get_radmin_interfaces():
    """Read active Radmin addresses and interface indices without CIM queries."""
    if sys.platform != 'win32':
        return []
    powershell = shutil.which('pwsh.exe') or shutil.which('powershell.exe')
    if not powershell:
        return []
    script = r'''$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$rows=@(foreach($adapter in [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
  if($adapter.OperationalStatus -ne 'Up') { continue }
  foreach($a in $adapter.GetIPProperties().UnicastAddresses) {
    if($a.Address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { continue }
    [pscustomobject]@{IP=$a.Address.ToString(); Mask=$a.IPv4Mask.ToString(); Description=$adapter.Description; Alias=$adapter.Name; Index=$adapter.GetIPProperties().GetIPv4Properties().Index}
  }
}); ConvertTo-Json -InputObject $rows -Compress'''
    try:
        result = subprocess.run([powershell, '-NoProfile', '-NonInteractive', '-Command', script],
                                capture_output=True, text=True, encoding='utf-8',
                                errors='replace', timeout=10,
                                creationflags=0x08000000)
        if result.returncode:
            return []
        rows = json.loads(result.stdout.lstrip('\ufeff'))
        if isinstance(rows, dict):
            rows = rows.get('value', rows.get('Value', [rows]))
    except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError, TypeError):
        return []
    interfaces = []
    for row in rows:
        try:
            address = ipaddress.ip_address(row['IP'])
            mask = row.get('Mask') or row['Prefix']
            network = ipaddress.ip_interface(f'{address}/{mask}').network
            description = (row.get('Description', '') + ' ' + row.get('Alias', '')).lower()
            radmin = 'radmin' in description or 'famatech' in description
            if radmin:
                if address not in ipaddress.ip_network('26.0.0.0/8'):
                    continue
                interfaces.append({'ip': str(address), 'broadcast': str(network.broadcast_address), 'index': int(row['Index'])})
        except (KeyError, ValueError, TypeError):
            continue
    return interfaces


def get_broadcast_interfaces():
    return [(entry['ip'], entry['broadcast']) for entry in get_radmin_interfaces()]


def get_broadcast_targets():
    return sorted({target for _, target in get_broadcast_interfaces()})


def discover_by_broadcast(port=DISCOVERY_PORT, timeout=0.65, interfaces=None):
    """Find Logan hosts without scanning Radmin's large virtual subnet."""
    if interfaces is None:
        interfaces = get_broadcast_interfaces()
    if not interfaces:
        return []
    found = set()
    sockets = []
    try:
        for source, target in interfaces:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                sock.bind((source, 0))
                sock.setblocking(False)
            except OSError:
                sock.close()
                continue
            sockets.append(sock)
            for destination in {target, '255.255.255.255'}:
                try:
                    sock.sendto(DISCOVERY_REQUEST, (destination, port))
                except OSError:
                    continue
        end = time.monotonic() + timeout
        while sockets and time.monotonic() < end:
            try:
                readable, _, _ = select.select(sockets, [], [], max(0, end - time.monotonic()))
            except OSError:
                break
            for sock in readable:
                try:
                    data, peer = sock.recvfrom(256)
                    if data == DISCOVERY_REPLY:
                        found.add(peer[0])
                except OSError:
                    continue
    finally:
        for sock in sockets:
            sock.close()
    return sorted(found)


def get_local_subnets():
    """Detect local private IPv4 subnets from network interfaces."""
    subnets = []
    try:
        hostname = socket.gethostname()
        addr_infos = socket.getaddrinfo(hostname, None, socket.AF_INET)
        local_ips = sorted(list(set(info[4][0] for info in addr_infos)))
    except Exception:
        local_ips = []

    for ip_str in local_ips:
        try:
            ip = ipaddress.ip_address(ip_str)
            if ip.is_private and not ip.is_loopback:
                # Use standard /24 for local class C/B/A LAN segments
                net = ipaddress.ip_network(f"{ip_str}/24", strict=False)
                if net not in subnets:
                    subnets.append(net)
        except Exception:
            continue

    return subnets, local_ips


async def check_tcp_port(ip_str, port=DEFAULT_PORT, timeout=0.35):
    """Fast non-blocking TCP connect check."""
    try:
        conn = asyncio.open_connection(ip_str, port)
        reader, writer = await asyncio.wait_for(conn, timeout=timeout)
        writer.close()
        await writer.wait_closed()
        return ip_str
    except Exception:
        return None


def query_server_details(ip_str, port=DEFAULT_PORT, timeout=1.0):
    """Connect via BrokerCodec and query WorldInfo (0x1106) for channel names."""
    sock = None
    try:
        # Discovery itself uses only UDP/TCP sockets. Broker metadata is
        # optional, so join-only installs do not need PyCryptodome.
        from broker_codec import BrokerCodec

        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(timeout)
        sock.connect((ip_str, port))
        codec = BrokerCodec()
        codec.write_frame(sock, 0x1106, 0, b"")
        msg_id, seq, payload = codec.read_frame(sock)
        if msg_id == 0x1107 and len(payload) >= 3:
            channel_count = payload[1]
            name_len = payload[2]
            name = payload[3:3 + name_len].decode("utf-16le", errors="replace")
            return {
                "ip": ip_str,
                "port": port,
                "name": name if name else "WolfTeam LAN Sunucusu",
                "channels": channel_count
            }
    except Exception:
        pass
    finally:
        if sock:
            try:
                sock.close()
            except Exception:
                pass

    return {
        "ip": ip_str,
        "port": port,
        "name": "WolfTeam LAN Sunucusu",
        "channels": 1
    }


async def scan_all_subnets(subnets, port=DEFAULT_PORT):
    """Scan all candidate hosts in parallel."""
    tasks = []
    # Always include 127.0.0.1
    tasks.append(check_tcp_port("127.0.0.1", port, timeout=0.2))

    for net in subnets:
        for host in net.hosts():
            host_str = str(host)
            tasks.append(check_tcp_port(host_str, port, timeout=0.35))

    results = await asyncio.gather(*tasks)
    active_ips = [ip for ip in results if ip]
    # Unique preserve order (loopback first if present)
    seen = set()
    unique_active = []
    for ip in active_ips:
        if ip not in seen:
            seen.add(ip)
            unique_active.append(ip)

    return unique_active


def query_radmin_server(address, port=DEFAULT_PORT, timeout=1.5):
    """Require the host's protocol marker, not merely an open TCP port."""
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(f'http://{address}/wolfteam/discovery', timeout=timeout) as response:
            if response.status != 200:
                return None
            data = json.loads(response.read(4097))
        if (data.get('protocol') != 'wolfteam-radmin-v1' or data.get('ip') != address
                or data.get('port') != port):
            return None
        return {'ip': address, 'port': port,
                'name': str(data.get('name') or 'WolfTeam Radmin Sunucusu'),
                'channels': int(data.get('channels', 1))}
    except (OSError, ValueError, TypeError, AttributeError):
        return None


async def check_radmin_hosts(addresses, port):
    replies = await asyncio.gather(*(check_tcp_port(address, port, timeout=1.5) for address in sorted(addresses)))
    return {address for address in replies if address}


def discover_servers(port=DEFAULT_PORT, diagnostics=None):
    """Probe known Radmin peers directly; broadcasts are an additional source."""
    interfaces = get_radmin_interfaces()
    candidates = get_radmin_peers(entry['index'] for entry in interfaces)
    candidates.update(entry['ip'] for entry in interfaces)
    candidates.update(discover_by_broadcast(interfaces=[(entry['ip'], entry['broadcast']) for entry in interfaces]))
    candidates = {address for address in candidates
                  if ipaddress.ip_address(address) in ipaddress.ip_network('26.0.0.0/8')}
    active_ips = asyncio.run(check_radmin_hosts(candidates, port))
    if diagnostics is not None:
        diagnostics.update(interfaces=len(interfaces), candidates=len(candidates), reachable=len(active_ips))
    servers = []
    for ip in sorted(active_ips):
        details = query_radmin_server(ip, port)
        if details:
            servers.append(details)
    return servers


def main():
    parser = argparse.ArgumentParser(description="WolfTeam LAN Server Discovery")
    parser.add_argument("--json", action="store_true", help="Output JSON array")
    parser.add_argument("--launcher", action="store_true", help="Output versioned tab-separated launcher records")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="Broker port to scan")
    args = parser.parse_args()

    diagnostics = {}
    servers = discover_servers(port=args.port, diagnostics=diagnostics)

    if args.launcher:
        print(f"WTRADMIN1\t{diagnostics.get('interfaces', 0)}\t{diagnostics.get('candidates', 0)}\t{diagnostics.get('reachable', 0)}")
        for server in servers:
            address = ipaddress.ip_address(server['ip'])
            if address.version != 4:
                continue
            name = ' '.join(str(server.get('name') or 'WolfTeam LAN Sunucusu').split())
            print(f"WTLAN1\t{address}\t{int(server['port'])}\t{int(server['channels'])}\t{name}")
        return

    if args.json:
        print(json.dumps(servers, ensure_ascii=False))
        return

    if not servers:
        print("Radmin sunucusu bulunamadi. Sunucu acik ve iki bilgisayar ayni Radmin aginda olmali.")
        return

    print("LAN Ağında Bulunan WolfTeam Sunucuları:")
    for idx, s in enumerate(servers, 1):
        print(f"  [{idx}] {s['ip']}:{s['port']} - {s['name']} ({s['channels']} Kanal)")


if __name__ == "__main__":
    main()
